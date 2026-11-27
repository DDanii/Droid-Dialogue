package org.droiddialogue.core;

/**
 * Streaming sample rate converter that turns the microphone's native rate into 16 kHz.
 *
 * Every output sample is the average of the input samples it covers (a box filter with fractional
 * edges). That is cheap enough for a 1 GHz phone and keeps most of the aliasing out of the speech
 * band. Chunk boundaries do not matter: feeding the same audio in different chunk sizes gives the
 * same output. No Android classes, so it runs in the desktop tests too.
 */
public final class Resampler {
    private final double ratio; // input samples per output sample
    private double sum;
    private double need;

    public Resampler(int fromRate, int toRate) {
        if (fromRate < toRate || toRate <= 0) {
            throw new IllegalArgumentException("can only convert down or keep the rate: " + fromRate + " -> " + toRate);
        }
        this.ratio = (double) fromRate / toRate;
        this.need = ratio;
    }

    /** Largest number of samples {@link #process} can produce for {@code inputLength} input samples. */
    public int maxOutput(int inputLength) {
        return (int) (inputLength / ratio) + 2;
    }

    public void reset() {
        sum = 0;
        need = ratio;
    }

    /**
     * Converts {@code n} samples of {@code in} and writes the result to {@code out}, which must hold at
     * least {@code maxOutput(n)} samples. Returns how many samples were written.
     */
    public int process(short[] in, int n, short[] out) {
        int o = 0;
        for (int i = 0; i < n; i++) {
            double x = in[i];
            double w = 1.0;
            while (w > 1e-12) {
                double take = w < need ? w : need;
                sum += x * take;
                need -= take;
                w -= take;
                if (need <= 1e-9) {
                    long v = Math.round(sum / ratio);
                    if (v > 32767) {
                        v = 32767;
                    } else if (v < -32768) {
                        v = -32768;
                    }
                    out[o++] = (short) v;
                    sum = 0;
                    need = ratio;
                }
            }
        }
        return o;
    }
}
