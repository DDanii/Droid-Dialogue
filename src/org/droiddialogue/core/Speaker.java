package org.droiddialogue.core;

/** Plays text-to-speech audio sent by Home Assistant. Implemented with AudioTrack on Android. */
public interface Speaker {
    /** A new audio stream starts. Width is bytes per sample (2 = 16-bit). */
    void begin(int rate, int width, int channels);

    /** Raw little-endian PCM for the current stream. */
    void write(byte[] pcm);

    /** The stream is complete. Call onFinished once everything queued has been played. */
    void end(Runnable onFinished);

    /** Drop everything immediately. */
    void stopNow();
}
