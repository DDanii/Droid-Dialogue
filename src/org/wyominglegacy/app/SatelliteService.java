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
import org.wyominglegacy.dlna.MediaBackend;
import org.wyominglegacy.dlna.MediaRenderer;
import org.wyominglegacy.dlna.Ssdp;

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

    // the DLNA speaker, started on its own thread so network setup never blocks the UI thread
    private final Object speakerLock = new Object();
    private boolean speakerDestroyed;
    private MediaPlayerBackend media;
    private MediaRenderer renderer;
    private WifiManager.MulticastLock multicastLock;

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
        Shared.speakerError = "";
        if (Config.speakerEnabled) {
            startSpeakerAsync(core, name);
        }
        return START_STICKY;
    }

    private void startSpeakerAsync(final SatelliteCore core, final String name) {
        Shared.speakerState = "starting";
        Thread t = new Thread(new Runnable() {
            public void run() {
                startSpeaker(core, name);
            }
        }, "speaker-start");
        t.setDaemon(true);
        t.start();
    }

    private void startSpeaker(final SatelliteCore core, String name) {
        MediaPlayerBackend backend = null;
        MediaRenderer r = null;
        try {
            backend = new MediaPlayerBackend(this);
            backend.setListener(new MediaBackend.Listener() {
                public void onStateChanged(int s) {
                    // while the speaker plays, the wake word must not listen to it
                    core.setExternalAudio(s == MediaBackend.PLAYING || s == MediaBackend.LOADING);
                    Shared.speakerState = stateLabel(s);
                }
            });
            r = new MediaRenderer(Config.upnpUuid, name + " Speaker", "1.0", backend);
            r.start(Config.UPNP_PORT, Ssdp.STANDARD_PORT, true);
        } catch (Throwable t) {
            Dbg.d("speaker failed: " + t);
            if (r != null) {
                r.stop();
            }
            if (backend != null) {
                backend.shutdown();
            }
            Shared.speakerError = "Speaker not available: " + t.getMessage();
            Shared.speakerState = "off";
            return;
        }
        synchronized (speakerLock) {
            if (speakerDestroyed) {
                r.stop();
                backend.shutdown();
                return;
            }
            media = backend;
            renderer = r;
        }
        Shared.speakerPort = r.getHttpPort();
        String problem = r.getDiscoveryError();
        Shared.speakerError = problem.length() == 0 ? ""
                : "Home Assistant cannot find the speaker by itself (" + problem + "), add it by address";
        Shared.speakerState = "idle";
    }

    private static String stateLabel(int s) {
        switch (s) {
            case MediaBackend.PLAYING:
                return "playing";
            case MediaBackend.PAUSED:
                return "paused";
            case MediaBackend.LOADING:
                return "loading";
            default:
                return "idle";
        }
    }

    private void stopSpeaker() {
        MediaRenderer r;
        MediaPlayerBackend m;
        synchronized (speakerLock) {
            speakerDestroyed = true;
            r = renderer;
            m = media;
            renderer = null;
            media = null;
        }
        if (r != null) {
            r.stop();
        }
        if (m != null) {
            m.shutdown();
        }
        Shared.speakerPort = -1;
        Shared.speakerState = "off";
        Shared.speakerError = "";
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
            if (Config.speakerEnabled) {
                // without this many phones drop incoming multicast, and discovery (SSDP) is multicast
                multicastLock = wm.createMulticastLock("wyoming-legacy-ssdp");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Exception e) {
            Dbg.d("no wifi lock: " + e);
        }
    }

    @Override
    public void onDestroy() {
        Shared.core = null;
        Shared.liveScore = -1;
        stopSpeaker();
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
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (Exception e) {
            // ignore
        }
        stopForeground(true);
        super.onDestroy();
    }
}
