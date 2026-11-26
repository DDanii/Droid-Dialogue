package org.wyominglegacy.app;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import org.wyominglegacy.core.Dbg;
import org.wyominglegacy.core.SatelliteCore;
import org.wyominglegacy.core.WyomingServer;

import java.io.IOException;

/** Foreground service that keeps the Wyoming server, the microphone and the speaker running. */
public class SatelliteService extends Service {
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private WyomingServer server;
    private MicCapture mic;
    private AudioTrackSpeaker speaker;
    private NsdAnnounce nsd;
    private boolean started;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (started) {
            return START_STICKY;
        }
        started = true;
        Dbg.sink = new Dbg.Sink() {
            public void d(String m) {
                Log.d("WyomingLegacy", m);
            }
        };
        Config.load(getSharedPreferences("wl", MODE_PRIVATE));
        Shared.startError = "";

        try {
            startForeground(Notifs.ID, Notifs.build(this, "Listening on port " + Config.port));
        } catch (RuntimeException e) {
            fail("Cannot run in the foreground: " + e.getMessage());
            return START_NOT_STICKY;
        }

        speaker = new AudioTrackSpeaker();
        String name = satelliteName();
        SatelliteCore core = new SatelliteCore(name, "1.0", speaker);
        server = new WyomingServer(core);
        try {
            server.start(Config.port);
        } catch (IOException e) {
            fail("Cannot listen on port " + Config.port + ": " + e.getMessage());
            return START_NOT_STICKY;
        }
        Shared.core = core;
        acquireLocks();

        mic = new MicCapture(this, core);
        mic.start();

        if (Build.VERSION.SDK_INT >= 16) {
            nsd = new NsdAnnounce();
            nsd.register(this, name, Config.port);
        }
        return START_STICKY;
    }

    private void fail(String message) {
        Shared.startError = message;
        stopSelf();
    }

    private static String satelliteName() {
        String model = Build.MODEL == null ? "" : Build.MODEL.replaceAll("[^A-Za-z0-9 ]", "").trim();
        if (model.length() == 0) {
            model = "Android";
        }
        return "Wyoming Legacy " + model;
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wyoming-legacy:satellite");
            wakeLock.acquire();
        } catch (Exception e) {
            Dbg.d("no wake lock: " + e);
        }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "wyoming-legacy");
            wifiLock.acquire();
        } catch (Exception e) {
            Dbg.d("no wifi lock: " + e);
        }
    }

    @Override
    public void onDestroy() {
        Shared.core = null;
        Shared.liveScore = -1;
        if (nsd != null) {
            nsd.unregister();
        }
        if (server != null) {
            server.stop();
        }
        if (mic != null) {
            mic.shutdown();
            try {
                mic.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (speaker != null) {
            speaker.shutdown();
        }
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception e) {
            // ignore
        }
        stopForeground(true);
        super.onDestroy();
    }
}
