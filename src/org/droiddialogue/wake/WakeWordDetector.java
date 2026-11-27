package org.droiddialogue.wake;

import java.util.ArrayList;
import java.util.List;

/**
 * Personal wake word detector: compares the live audio with a few recordings of the phrase made by
 * the user (templates) using MFCC features and dynamic time warping. It runs entirely in plain
 * Java so it works on any Android version and CPU.
 *
 * It is a speaker dependent "say it like you recorded it" detector, much simpler and less accurate
 * than a neural model such as openWakeWord. It only fires when enough of the matched stretch
 * contained speech, which keeps silence and steady noise from triggering it.
 *
 * Call from one thread only (the microphone thread). The status getters are safe from any thread.
 */
public final class WakeWordDetector implements FrameExtractor.Sink {
    private static final int RING = 256;
    private static final int REFRACTORY_FRAMES = 150; // 1.5 s
    private static final double SPEECH_MARGIN_DB = 8.0;
    private static final double MIN_SPEECH_FRACTION = 0.4;

    private final FrameExtractor fx = new FrameExtractor();
    private final List<DtwMatcher> matchers = new ArrayList<DtwMatcher>();
    private double baseThreshold;
    private double scale = 1.0;

    private final boolean[] speech = new boolean[RING];
    private int ringPos;
    private double floorDb;
    private boolean floorInit;
    private int refractory;
    private boolean fired;

    private final double[] recent = new double[25];
    private int recentPos;
    private volatile double uiBest = DtwMatcher.INF;

    public WakeWordDetector() {
        for (int i = 0; i < recent.length; i++) {
            recent[i] = DtwMatcher.INF;
        }
    }

    /** Install templates. A threshold of 0 or less disables detection. */
    public void setTemplates(List<float[][]> templates, double threshold) {
        matchers.clear();
        for (float[][] t : templates) {
            matchers.add(new DtwMatcher(t));
        }
        baseThreshold = threshold;
        resetState();
    }

    /** Multiplies the threshold: below 1 is stricter, above 1 is more sensitive. */
    public void setScale(double s) {
        scale = s;
    }

    public double threshold() {
        return baseThreshold * scale;
    }

    public boolean isReady() {
        return !matchers.isEmpty() && baseThreshold > 0;
    }

    /** Feed 16 kHz mono samples. Returns true if the wake word was just heard. */
    public boolean process(short[] pcm, int n) {
        fired = false;
        fx.feed(pcm, n, this);
        return fired;
    }

    /** Forget buffered audio, for example after the microphone was ignored for a while. */
    public void reset() {
        resetState();
    }

    /** Best (lowest) recent match score, for showing a live value in the UI. Large when nothing matches. */
    public double recentBest() {
        return uiBest;
    }

    private void resetState() {
        fx.reset();
        for (DtwMatcher m : matchers) {
            m.reset();
        }
        for (int i = 0; i < RING; i++) {
            speech[i] = false;
        }
        for (int i = 0; i < recent.length; i++) {
            recent[i] = DtwMatcher.INF;
        }
        uiBest = DtwMatcher.INF;
        refractory = 0;
    }

    public void onFrame(float[] feat, float energyDb) {
        if (!floorInit) {
            floorDb = energyDb;
            floorInit = true;
        } else if (energyDb < floorDb) {
            floorDb = 0.8 * floorDb + 0.2 * energyDb;
        } else {
            floorDb += 0.0005 * (energyDb - floorDb);
        }
        speech[ringPos] = energyDb > floorDb + SPEECH_MARGIN_DB;
        ringPos = (ringPos + 1) % RING;

        if (!isReady()) {
            return;
        }
        double thr = baseThreshold * scale;
        double best = DtwMatcher.INF;
        boolean hit = false;
        for (int i = 0; i < matchers.size(); i++) {
            DtwMatcher m = matchers.get(i);
            double sc = m.push(feat);
            if (sc < best) {
                best = sc;
            }
            if (sc < thr && speechFraction(m.len) >= MIN_SPEECH_FRACTION) {
                hit = true;
            }
        }
        recent[recentPos] = best;
        recentPos = (recentPos + 1) % recent.length;
        double min = DtwMatcher.INF;
        for (int i = 0; i < recent.length; i++) {
            if (recent[i] < min) {
                min = recent[i];
            }
        }
        uiBest = min;

        if (refractory > 0) {
            refractory--;
            return;
        }
        if (hit) {
            fired = true;
            refractory = REFRACTORY_FRAMES;
            for (DtwMatcher m : matchers) {
                m.reset();
            }
        }
    }

    private double speechFraction(int frames) {
        int n = Math.min(frames, RING);
        int count = 0;
        int pos = ringPos;
        for (int i = 0; i < n; i++) {
            pos = (pos - 1 + RING) % RING;
            if (speech[pos]) {
                count++;
            }
        }
        return n == 0 ? 0 : (double) count / n;
    }
}
