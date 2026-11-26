package org.wyominglegacy.wake;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Turns a short recording of the wake phrase into a template and derives a detection threshold. */
public final class TemplateBuilder {
    public static final int MIN_FRAMES = 25;   // 0.25 s
    public static final int MAX_FRAMES = 220;  // 2.2 s
    public static final int MIN_TEMPLATES = 3;

    private static final int PAD_FRAMES = 4;
    private static final double THRESHOLD_FACTOR = 1.4;

    private TemplateBuilder() {
    }

    /**
     * Extracts the spoken part of a recording (silence trimmed) as MFCC frames.
     * Throws IllegalArgumentException with a message fit for the user if the recording is unusable.
     */
    public static float[][] build(short[] pcm, int n) {
        final List<float[]> feats = new ArrayList<float[]>();
        final List<Float> energies = new ArrayList<Float>();
        FrameExtractor fx = new FrameExtractor();
        fx.feed(pcm, n, new FrameExtractor.Sink() {
            public void onFrame(float[] f, float e) {
                feats.add(f.clone());
                energies.add(Float.valueOf(e));
            }
        });
        int total = feats.size();
        if (total < 60) {
            throw new IllegalArgumentException("Recording too short");
        }
        float[] en = new float[total];
        for (int i = 0; i < total; i++) {
            en[i] = energies.get(i).floatValue();
        }
        float[] sorted = en.clone();
        Arrays.sort(sorted);
        double floor = sorted[total / 5];
        double peak = sorted[total - 1 - total / 50];
        if (peak - floor < 12.0) {
            throw new IllegalArgumentException("Too quiet or too noisy. Speak louder and closer to the mic");
        }
        double thr = Math.max(floor + 8.0, peak - 30.0);
        boolean[] on = new boolean[total];
        for (int i = 0; i < total; i++) {
            on[i] = en[i] >= thr;
        }
        int first = -1;
        int last = -1;
        for (int i = 0; i + 2 < total; i++) {
            if (on[i] && on[i + 1] && on[i + 2]) {
                first = i;
                break;
            }
        }
        for (int i = total - 1; i >= 2; i--) {
            if (on[i] && on[i - 1] && on[i - 2]) {
                last = i;
                break;
            }
        }
        if (first < 0 || last < first) {
            throw new IllegalArgumentException("No speech found. Try again");
        }
        if (last >= total - 3) {
            throw new IllegalArgumentException("The phrase was cut off. Start speaking right after tapping");
        }
        int start = Math.max(0, first - PAD_FRAMES);
        int end = Math.min(total - 1, last + PAD_FRAMES);
        int len = end - start + 1;
        if (len < MIN_FRAMES) {
            throw new IllegalArgumentException("Phrase too short. Use at least a quarter second");
        }
        if (len > MAX_FRAMES) {
            throw new IllegalArgumentException("Phrase too long. Keep it under 2 seconds");
        }
        float[][] out = new float[len][];
        for (int i = 0; i < len; i++) {
            out[i] = feats.get(start + i);
        }
        return out;
    }

    /** Average frame distance of the best full alignment of two templates, or a huge value if none fits. */
    public static double pairDistance(float[][] a, float[][] b) {
        int na = a.length;
        int nb = b.length;
        double inf = DtwMatcher.INF;
        double[][] d = new double[na][nb];
        int[][] ln = new int[na][nb];
        for (int i = 0; i < na; i++) {
            Arrays.fill(d[i], inf);
        }
        d[0][0] = DtwMatcher.dist(a[0], b[0]);
        ln[0][0] = 1;
        for (int i = 0; i < na; i++) {
            for (int j = 0; j < nb; j++) {
                if (i == 0 && j == 0) {
                    continue;
                }
                double best = inf;
                int bl = 0;
                if (i >= 1 && j >= 1 && d[i - 1][j - 1] < best) {
                    best = d[i - 1][j - 1];
                    bl = ln[i - 1][j - 1];
                }
                if (i >= 1 && j >= 2 && d[i - 1][j - 2] < best) {
                    best = d[i - 1][j - 2];
                    bl = ln[i - 1][j - 2];
                }
                if (i >= 2 && j >= 1 && d[i - 2][j - 1] < best) {
                    best = d[i - 2][j - 1];
                    bl = ln[i - 2][j - 1];
                }
                if (best < inf) {
                    d[i][j] = best + DtwMatcher.dist(a[i], b[j]);
                    ln[i][j] = bl + 1;
                }
            }
        }
        double r = d[na - 1][nb - 1];
        return r >= inf ? inf : r / ln[na - 1][nb - 1];
    }

    /**
     * Threshold derived from how much the user's own recordings differ from each other.
     * Returns 0 (detection off) with fewer than MIN_TEMPLATES templates.
     */
    public static double autoThreshold(List<float[][]> templates) {
        if (templates.size() < MIN_TEMPLATES) {
            return 0;
        }
        double max = 0;
        int count = 0;
        for (int i = 0; i < templates.size(); i++) {
            for (int j = i + 1; j < templates.size(); j++) {
                double d = pairDistance(templates.get(i), templates.get(j));
                if (d < DtwMatcher.INF / 2) {
                    if (d > max) {
                        max = d;
                    }
                    count++;
                }
            }
        }
        // Measured on synthetic speech: the same phrase spoken live scores about 1.0x the largest
        // distance between two templates (95th percentile 1.4x), a similar word at least 1.6x, a
        // different word at least 2.2x. 1.4x keeps most genuine utterances and stays below the
        // similar-word floor. The sensitivity slider in the app scales this value.
        return count == 0 ? 0 : max * THRESHOLD_FACTOR;
    }
}
