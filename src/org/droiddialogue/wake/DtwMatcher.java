package org.droiddialogue.wake;

/**
 * Streaming subsequence DTW of one template against live audio. For every new frame it updates one
 * column of the cost matrix, so the cost per frame is only the template length. A path may start at
 * any input frame (free start) and the score is the average frame distance of the best path that
 * ends at the current frame and has consumed the whole template.
 *
 * Allowed steps (template, input): (1,1), (1,2) and (2,1), so the spoken phrase may be between
 * half and twice as long as the template but cannot stall or skip wildly.
 */
final class DtwMatcher {
    static final double INF = 1e30;

    final float[][] tpl;
    final int len;

    private double[] c1;
    private double[] c2;
    private double[] cur;
    private int[] l1;
    private int[] l2;
    private int[] lc;

    DtwMatcher(float[][] template) {
        this.tpl = template;
        this.len = template.length;
        c1 = new double[len];
        c2 = new double[len];
        cur = new double[len];
        l1 = new int[len];
        l2 = new int[len];
        lc = new int[len];
        reset();
    }

    void reset() {
        for (int i = 0; i < len; i++) {
            c1[i] = INF;
            c2[i] = INF;
            cur[i] = INF;
            l1[i] = 0;
            l2[i] = 0;
            lc[i] = 0;
        }
    }

    static double dist(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            s += d * d;
        }
        return Math.sqrt(s);
    }

    /** Returns the normalised score for a path ending at this frame, or INF if none exists yet. */
    double push(float[] x) {
        for (int i = 0; i < len; i++) {
            double d = dist(x, tpl[i]);
            if (i == 0) {
                cur[0] = d;
                lc[0] = 1;
                continue;
            }
            double best = c1[i - 1];
            int bl = l1[i - 1];
            if (c2[i - 1] < best) {
                best = c2[i - 1];
                bl = l2[i - 1];
            }
            if (i >= 2 && c1[i - 2] < best) {
                best = c1[i - 2];
                bl = l1[i - 2];
            }
            if (best >= INF) {
                cur[i] = INF;
                lc[i] = 0;
            } else {
                cur[i] = best + d;
                lc[i] = bl + 1;
            }
        }
        double score = cur[len - 1] >= INF ? INF : cur[len - 1] / lc[len - 1];
        double[] tc = c2;
        c2 = c1;
        c1 = cur;
        cur = tc;
        int[] tl = l2;
        l2 = l1;
        l1 = lc;
        lc = tl;
        return score;
    }
}
