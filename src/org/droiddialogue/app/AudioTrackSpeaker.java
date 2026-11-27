package org.droiddialogue.app;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import org.droiddialogue.core.Dbg;
import org.droiddialogue.core.Speaker;

import java.util.concurrent.LinkedBlockingQueue;

/** Plays the reply audio from Home Assistant with a streaming AudioTrack on its own thread. */
final class AudioTrackSpeaker implements Speaker {
    private static final Object STOP = new Object();

    private static final class Begin {
        final int rate;
        final int width;
        final int channels;

        Begin(int rate, int width, int channels) {
            this.rate = rate;
            this.width = width;
            this.channels = channels;
        }
    }

    private static final class End {
        final Runnable done;

        End(Runnable done) {
            this.done = done;
        }
    }

    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<Object>();
    private final Thread thread;
    private volatile boolean running = true;

    private AudioTrack track;
    private long framesWritten;
    private int bufFrames;
    private int rate = 16000;
    private int channels = 1;
    private int width = 2;

    AudioTrackSpeaker() {
        thread = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "tts-player");
        thread.setDaemon(true);
        thread.start();
    }

    public void begin(int rate, int width, int channels) {
        queue.offer(new Begin(rate, width, channels));
    }

    public void write(byte[] pcm) {
        queue.offer(pcm);
    }

    public void end(Runnable onFinished) {
        queue.offer(new End(onFinished));
    }

    public void stopNow() {
        queue.clear();
        queue.offer(STOP);
    }

    void shutdown() {
        running = false;
        thread.interrupt();
    }

    private void loop() {
        while (running) {
            Object o;
            try {
                o = queue.take();
            } catch (InterruptedException e) {
                break;
            }
            try {
                if (o instanceof Begin) {
                    open((Begin) o);
                } else if (o instanceof byte[]) {
                    writePcm((byte[]) o);
                } else if (o instanceof End) {
                    drain();
                    closeTrack(false);
                    ((End) o).done.run();
                } else if (o == STOP) {
                    closeTrack(true);
                }
            } catch (Throwable t) {
                Dbg.d("playback error: " + t);
                closeTrack(true);
                if (o instanceof End) {
                    ((End) o).done.run();
                }
            }
        }
        closeTrack(true);
    }

    private void open(Begin b) {
        closeTrack(true);
        rate = b.rate;
        width = b.width;
        channels = b.channels;
        if (width != 2) {
            Dbg.d("unsupported sample width " + width);
            return;
        }
        int cfg = channels >= 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int min = AudioTrack.getMinBufferSize(rate, cfg, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            Dbg.d("AudioTrack does not support " + rate + " Hz");
            return;
        }
        int bytes = Math.max(min * 2, rate * channels);
        AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, rate, cfg, AudioFormat.ENCODING_PCM_16BIT,
                bytes, AudioTrack.MODE_STREAM);
        if (t.getState() != AudioTrack.STATE_INITIALIZED) {
            t.release();
            Dbg.d("AudioTrack failed to initialise");
            return;
        }
        t.play();
        track = t;
        framesWritten = 0;
        bufFrames = bytes / (2 * channels);
    }

    private void writePcm(byte[] data) {
        AudioTrack t = track;
        if (t == null) {
            return;
        }
        int off = 0;
        while (off < data.length && running) {
            int w = t.write(data, off, data.length - off);
            if (w <= 0) {
                break;
            }
            off += w;
        }
        framesWritten += off / (2 * channels);
    }

    /** Waits until what was written has been played, but never longer than the buffer length plus a margin. */
    private void drain() {
        AudioTrack t = track;
        if (t == null) {
            return;
        }
        long deadline = System.currentTimeMillis() + (bufFrames * 1000L / rate) + 800;
        while (running && System.currentTimeMillis() < deadline) {
            long head = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
            if (head >= framesWritten) {
                break;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void closeTrack(boolean immediately) {
        AudioTrack t = track;
        track = null;
        if (t == null) {
            return;
        }
        try {
            if (immediately) {
                t.pause();
                t.flush();
            } else {
                t.stop();
            }
        } catch (Exception e) {
            // ignore
        }
        try {
            t.release();
        } catch (Exception e) {
            // ignore
        }
    }
}
