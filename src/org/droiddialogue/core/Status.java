package org.droiddialogue.core;

/** Snapshot of what the satellite is doing, read by the UI thread. */
public final class Status {
    public volatile boolean serverRunning;
    public volatile boolean connected;
    public volatile String peer = "";
    /** idle, listening, thinking or speaking. */
    public volatile String pipeline = "idle";
    public volatile String lastEvent = "";
}
