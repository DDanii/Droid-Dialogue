package org.droiddialogue.app;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import org.droiddialogue.core.Dbg;
import org.droiddialogue.core.Resampler;
import org.droiddialogue.core.SatelliteCore;
import org.droiddialogue.wake.TemplateBuilder;
import org.droiddialogue.wake.TemplateStore;
import org.droiddialogue.wake.WakeWordDetector;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Owns the microphone. Reads mono audio (16 kHz if the phone allows it, otherwise the first rate it
 * does support, converted to 16 kHz), applies the gain, forwards it to Home Assistant while a
 * pipeline is listening, runs the wake word detector otherwise and records new wake word samples
 * when asked to. Only this thread touches the AudioRecord and the detector.
 */
final class MicCapture extends Thread {
    private static final int RATE = 16000;
    private static final int CHUNK = 480; // 30 ms at 16 kHz
    private static final int MAX_TEMPLATES = 8;
    private static final int ENROLL_SECONDS = 3;
    /** Tried in this order. Some old phones only record at 44.1 kHz or 48 kHz. */
    private static final int[] CAPTURE_RATES = {16000, 48000, 44100, 32000, 22050};

    private final SatelliteCore core;
    private int captureRate = RATE;
    private Resampler resampler;
    private final File file;
    private final WakeWordDetector det = new WakeWordDetector();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private volatile boolean running = true;

    private List<float[][]> templates = new ArrayList<float[][]>();
    private short[] enrollBuf;
    private int enrollPos;
    private boolean enrolling;
    private boolean wasBusy;
    private long cooldownUntil;

    MicCapture(Context ctx, SatelliteCore core) {
        super("mic-capture");
        this.core = core;
        this.file = new File(ctx.getFilesDir(), "templates.bin");
        setDaemon(true);
    }

    void shutdown() {
        running = false;
    }

    @Override
    public void run() {
        templates = TemplateStore.load(file);
        applyTemplates();
        short[] raw = new short[CHUNK];
        short[] buf = new short[CHUNK];
        AudioRecord rec = null;
        while (running) {
            if (rec == null) {
                rec = openRecorder();
                if (rec == null) {
                    Shared.micError = "Microphone unavailable (permission denied, or another app uses it)";
                    Shared.micInfo = "";
                    sleepQuiet(2000);
                    continue;
                }
                Shared.micError = "";
                Shared.micInfo = captureRate == RATE ? "16000 Hz" : captureRate + " Hz, converted to 16000 Hz";
                int want = (int) ((long) CHUNK * captureRate / RATE);
                raw = new short[want];
                buf = new short[resampler == null ? CHUNK : resampler.maxOutput(want)];
            }
            int n = rec.read(raw, 0, raw.length);
            if (n < 0) {
                release(rec);
                rec = null;
                sleepQuiet(500);
                continue;
            }
            if (n == 0) {
                sleepQuiet(5);
                continue;
            }
            if (resampler != null) {
                n = resampler.process(raw, n, buf);
                if (n == 0) {
                    continue;
                }
            } else {
                System.arraycopy(raw, 0, buf, 0, n);
            }
            applyGain(buf, n);
            core.onMicChunk(buf, n);
            handle(buf, n);
        }
        release(rec);
        Shared.liveScore = -1;
    }

    /** Opens the microphone at the first rate the phone accepts and sets up the conversion to 16 kHz. */
    private AudioRecord openRecorder() {
        for (int i = 0; i < CAPTURE_RATES.length; i++) {
            int rate = CAPTURE_RATES[i];
            AudioRecord r = tryOpen(rate);
            if (r != null) {
                captureRate = rate;
                resampler = rate == RATE ? null : new Resampler(rate, RATE);
                if (rate != RATE) {
                    Dbg.d("microphone opened at " + rate + " Hz, converting to " + RATE + " Hz");
                }
                return r;
            }
        }
        return null;
    }

    private AudioRecord tryOpen(int rate) {
        AudioRecord r = null;
        try {
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) {
                return null;
            }
            int bytes = Math.max(min * 2, rate);
            r = new AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bytes);
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                return null;
            }
            r.startRecording();
            if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                r.release();
                return null;
            }
            return r;
        } catch (Throwable t) {
            Dbg.d("cannot open microphone at " + rate + " Hz: " + t);
            if (r != null) {
                try {
                    r.release();
                } catch (Throwable ignored) {
                    // nothing more to do
                }
            }
            return null;
        }
    }

    private void release(AudioRecord r) {
        if (r == null) {
            return;
        }
        try {
            r.stop();
        } catch (Exception e) {
            // not recording
        }
        try {
            r.release();
        } catch (Exception e) {
            // ignore
        }
    }

    private static void applyGain(short[] buf, int n) {
        float g = Config.gain();
        if (g == 1.0f) {
            return;
        }
        for (int i = 0; i < n; i++) {
            int v = Math.round(buf[i] * g);
            if (v > 32767) {
                v = 32767;
            } else if (v < -32768) {
                v = -32768;
            }
            buf[i] = (short) v;
        }
    }

    private void handle(short[] buf, int n) {
        if (Shared.clearRequested) {
            Shared.clearRequested = false;
            templates = new ArrayList<float[][]>();
            file.delete();
            applyTemplates();
            Shared.enrollMsg = "All samples cleared";
        }
        if (Shared.enrollRequested && !enrolling) {
            Shared.enrollRequested = false;
            enrolling = true;
            enrollBuf = new short[RATE * ENROLL_SECONDS];
            enrollPos = 0;
            Shared.enrollMsg = "Recording for " + ENROLL_SECONDS + " seconds. Say the phrase now";
        }
        if (enrolling) {
            int c = Math.min(n, enrollBuf.length - enrollPos);
            System.arraycopy(buf, 0, enrollBuf, enrollPos, c);
            enrollPos += c;
            if (enrollPos >= enrollBuf.length) {
                finishEnrollment();
            }
            return;
        }

        if (!Config.wakeEnabled || !det.isReady()) {
            Shared.liveScore = -1;
            return;
        }
        if (core.isBusy()) {
            wasBusy = true;
            Shared.liveScore = -1;
            return;
        }
        long now = System.currentTimeMillis();
        if (wasBusy) {
            wasBusy = false;
            det.reset();
            cooldownUntil = now + 700; // let the tail of the spoken reply die away
        }
        if (now < cooldownUntil) {
            return;
        }
        det.setScale(Config.thresholdScale());
        if (det.process(buf, n)) {
            Shared.detections++;
            boolean sent = core.onWakeDetected(Config.wakeName);
            Shared.lastDetect = (sent ? "sent to Home Assistant at " : "heard, but not connected, at ")
                    + clock.format(new Date());
        }
        Shared.liveScore = det.recentBest();
    }

    private void finishEnrollment() {
        enrolling = false;
        try {
            float[][] t = TemplateBuilder.build(enrollBuf, enrollPos);
            templates.add(t);
            while (templates.size() > MAX_TEMPLATES) {
                templates.remove(0);
            }
            String saved = "";
            try {
                TemplateStore.save(templates, file);
            } catch (IOException e) {
                saved = " (could not write to storage: " + e.getMessage() + ")";
            }
            applyTemplates();
            int have = templates.size();
            if (have >= TemplateBuilder.MIN_TEMPLATES) {
                Shared.enrollMsg = "Sample " + have + " saved. Wake word is active" + saved;
            } else {
                Shared.enrollMsg = "Sample " + have + " saved. Record "
                        + (TemplateBuilder.MIN_TEMPLATES - have) + " more" + saved;
            }
        } catch (IllegalArgumentException e) {
            Shared.enrollMsg = e.getMessage() + ". Tap Record sample to try again";
        }
        enrollBuf = null;
        // The detector heard nothing while recording, so its streaming state is stale.
        det.reset();
        cooldownUntil = System.currentTimeMillis() + 700;
    }

    private void applyTemplates() {
        double thr = TemplateBuilder.autoThreshold(templates);
        det.setTemplates(templates, thr);
        Shared.templateCount = templates.size();
        Shared.threshold = thr;
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // ignore
        }
    }
}
