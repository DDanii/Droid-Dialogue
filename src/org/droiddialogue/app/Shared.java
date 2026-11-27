package org.droiddialogue.app;

import org.droiddialogue.core.SatelliteCore;

/** State shared between the service threads and the UI. The app runs in a single process. */
final class Shared {
    static volatile SatelliteCore core;
    static volatile String startError = "";
    static volatile String micError = "";
    /** Sample rate the microphone really runs at, for the status line. */
    static volatile String micInfo = "";

    /** The DLNA speaker: its port (-1 when not running), a short state word and a problem text if any. */
    static volatile int speakerPort = -1;
    static volatile String speakerState = "off";
    static volatile String speakerError = "";

    static volatile boolean enrollRequested;
    static volatile boolean clearRequested;
    static volatile String enrollMsg = "";
    static volatile int templateCount;
    static volatile double threshold;
    /** Lowest recent match score, or -1 when the detector is not running. */
    static volatile double liveScore = -1;
    static volatile int detections;
    static volatile String lastDetect = "";

    private Shared() {
    }
}
