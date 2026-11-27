package org.droiddialogue.app;

import android.content.SharedPreferences;

/** User settings. Volatile fields so the UI thread can change them while the mic thread reads them. */
final class Config {
    static volatile int port = 10700;
    static volatile String wakeName = "hey phone";
    static volatile boolean wakeEnabled = true;
    /** 0..100, 50 keeps the automatic threshold, higher fires more easily. */
    static volatile int sensitivity = 50;
    /** Microphone gain in tenths: 10 = 1.0x, 20 = 2.0x, 100 = 10x. */
    static volatile int gainTenths = 20;
    /** Also show up as a media player (DLNA speaker) in Home Assistant. Read when the service starts. */
    static volatile boolean speakerEnabled = true;
    /** Identity of the DLNA speaker. Generated once and kept, Home Assistant recognizes the device by it. */
    static volatile String upnpUuid = "";
    /** Port of the speaker's description and control URLs; another free port is used if this one is taken. */
    static final int UPNP_PORT = 49152;

    private Config() {
    }

    static void load(SharedPreferences p) {
        port = p.getInt("port", 10700);
        wakeName = p.getString("wakeName", "hey phone");
        wakeEnabled = p.getBoolean("wakeEnabled", true);
        sensitivity = p.getInt("sensitivity", 50);
        gainTenths = p.getInt("gainTenths", 20);
        speakerEnabled = p.getBoolean("speakerEnabled", true);
        String id = p.getString("upnpUuid", "");
        if (id.length() == 0) {
            id = java.util.UUID.randomUUID().toString();
            p.edit().putString("upnpUuid", id).commit();
        }
        upnpUuid = id;
    }

    static void save(SharedPreferences p) {
        p.edit()
                .putInt("port", port)
                .putString("wakeName", wakeName)
                .putBoolean("wakeEnabled", wakeEnabled)
                .putInt("sensitivity", sensitivity)
                .putInt("gainTenths", gainTenths)
                .putBoolean("speakerEnabled", speakerEnabled)
                .putString("upnpUuid", upnpUuid)
                .commit();
    }

    static double thresholdScale() {
        return 0.5 + sensitivity / 100.0;
    }

    static float gain() {
        return gainTenths / 10f;
    }
}
