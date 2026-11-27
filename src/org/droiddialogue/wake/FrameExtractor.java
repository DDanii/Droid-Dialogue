package org.droiddialogue.wake;

/**
 * Streaming MFCC front end for 16 kHz mono audio: 25 ms Hamming windows every 10 ms, 24 mel bands
 * (100 to 5500 Hz), 12 liftered cepstral coefficients (c1..c12, no c0, so loudness mostly drops out)
 * and a frame energy in dB that is used for voice activity decisions.
 */
public final class FrameExtractor {
    public static final int RATE = 16000;
    public static final int WIN = 400;
    public static final int HOP = 160;
    public static final int NFFT = 512;
    public static final int NMEL = 24;
    public static final int NCEP = 12;

    private static final int BINS = NFFT / 2 + 1;
    private static final float PREEMPH = 0.97f;

    /** Receives each frame. The features array is reused, so copy it if you keep it. */
    public interface Sink {
        void onFrame(float[] features, float energyDb);
    }

    private static final double[] HAMMING = new double[WIN];
    private static final float[][] DCT = new float[NCEP + 1][NMEL];
    private static final float[] LIFTER = new float[NCEP + 1];
    private static final float[][] MEL = new float[NMEL][BINS];
    private static final int[] MEL_START = new int[NMEL];
    private static final int[] MEL_END = new int[NMEL];

    static {
        for (int i = 0; i < WIN; i++) {
            HAMMING[i] = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (WIN - 1));
        }
        double scale = Math.sqrt(2.0 / NMEL);
        for (int n = 0; n <= NCEP; n++) {
            for (int m = 0; m < NMEL; m++) {
                DCT[n][m] = (float) (Math.cos(Math.PI * n * (m + 0.5) / NMEL) * scale);
            }
            LIFTER[n] = (float) (1 + 11.0 * Math.sin(Math.PI * n / 22.0));
        }
        double melLow = hzToMel(100);
        double melHigh = hzToMel(5500);
        double[] c = new double[NMEL + 2];
        for (int i = 0; i < c.length; i++) {
            c[i] = melToHz(melLow + (melHigh - melLow) * i / (NMEL + 1));
        }
        for (int m = 0; m < NMEL; m++) {
            double f0 = c[m];
            double f1 = c[m + 1];
            double f2 = c[m + 2];
            int start = -1;
            int end = -1;
            for (int k = 0; k < BINS; k++) {
                double f = k * (double) RATE / NFFT;
                double w = 0;
                if (f > f0 && f <= f1) {
                    w = (f - f0) / (f1 - f0);
                } else if (f > f1 && f < f2) {
                    w = (f2 - f) / (f2 - f1);
                }
                MEL[m][k] = (float) w;
                if (w > 0) {
                    if (start < 0) {
                        start = k;
                    }
                    end = k;
                }
            }
            if (start < 0) {
                start = 0;
                end = -1;
            }
            MEL_START[m] = start;
            MEL_END[m] = end;
        }
    }

    private static double hzToMel(double hz) {
        return 2595.0 * Math.log10(1.0 + hz / 700.0);
    }

    private static double melToHz(double mel) {
        return 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0);
    }

    private final Fft fft = new Fft(NFFT);
    private final double[] re = new double[NFFT];
    private final double[] im = new double[NFFT];
    private final double[] pow = new double[BINS];
    private final float[] logMel = new float[NMEL];
    private final float[] feat = new float[NCEP];
    private final float[] buf = new float[WIN];
    private int fill;
    private float prev;

    public void reset() {
        fill = 0;
        prev = 0;
    }

    /** Feed 16 kHz mono samples. The sink is called once per completed 10 ms hop. */
    public void feed(short[] pcm, int n, Sink sink) {
        for (int i = 0; i < n; i++) {
            float x = pcm[i] / 32768f;
            buf[fill++] = x - PREEMPH * prev;
            prev = x;
            if (fill == WIN) {
                computeFrame(sink);
                System.arraycopy(buf, HOP, buf, 0, WIN - HOP);
                fill = WIN - HOP;
            }
        }
    }

    private void computeFrame(Sink sink) {
        double sumsq = 0;
        for (int i = 0; i < WIN; i++) {
            float v = buf[i];
            sumsq += v * v;
            re[i] = v * HAMMING[i];
            im[i] = 0;
        }
        for (int i = WIN; i < NFFT; i++) {
            re[i] = 0;
            im[i] = 0;
        }
        fft.transform(re, im);
        for (int k = 0; k < BINS; k++) {
            pow[k] = re[k] * re[k] + im[k] * im[k];
        }
        for (int m = 0; m < NMEL; m++) {
            double acc = 0;
            float[] w = MEL[m];
            for (int k = MEL_START[m]; k <= MEL_END[m]; k++) {
                acc += w[k] * pow[k];
            }
            logMel[m] = (float) Math.log(acc + 1e-10);
        }
        for (int n = 1; n <= NCEP; n++) {
            float sum = 0;
            float[] d = DCT[n];
            for (int m = 0; m < NMEL; m++) {
                sum += logMel[m] * d[m];
            }
            feat[n - 1] = sum * LIFTER[n];
        }
        float energyDb = (float) (10.0 * Math.log10(sumsq / WIN + 1e-10));
        sink.onFrame(feat, energyDb);
    }
}
