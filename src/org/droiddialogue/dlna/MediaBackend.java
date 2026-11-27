package org.droiddialogue.dlna;

/**
 * What the media renderer needs from the device that really plays the audio. The renderer calls these
 * from its HTTP threads, so implementations must be thread safe. Android-free on purpose: the real
 * implementation (android.media.MediaPlayer) lives in the app package.
 */
public interface MediaBackend {
    int NO_MEDIA = 0;
    int STOPPED = 1;
    int PLAYING = 2;
    int PAUSED = 3;
    /** A play request is waiting for the stream to load. */
    int LOADING = 4;

    interface Listener {
        void onStateChanged(int state);
    }

    void setListener(Listener listener);

    /** Loads the address without starting it. An empty address unloads whatever was loaded. */
    void setUri(String uri);

    /** Starts or resumes playback, as soon as the media is ready. Throws IllegalStateException without media. */
    void play();

    void pause();

    /** Stops and rewinds; play() afterwards starts from the beginning. */
    void stop();

    void seek(long positionMs);

    int getState();

    long getPositionMs();

    /** 0 when unknown. */
    long getDurationMs();

    /** 0..100 */
    int getVolume();

    void setVolume(int percent);

    boolean isMuted();

    void setMuted(boolean muted);
}
