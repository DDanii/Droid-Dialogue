package org.droiddialogue.wake;

/** In-place radix-2 FFT. */
final class Fft {
    private final int n;
    private final double[] cosT;
    private final double[] sinT;
    private final int[] rev;

    Fft(int n) {
        if (n < 2 || (n & (n - 1)) != 0) {
            throw new IllegalArgumentException("FFT size must be a power of two");
        }
        this.n = n;
        int levels = 31 - Integer.numberOfLeadingZeros(n);
        cosT = new double[n / 2];
        sinT = new double[n / 2];
        for (int i = 0; i < n / 2; i++) {
            cosT[i] = Math.cos(2 * Math.PI * i / n);
            sinT[i] = Math.sin(2 * Math.PI * i / n);
        }
        rev = new int[n];
        for (int i = 0; i < n; i++) {
            rev[i] = Integer.reverse(i) >>> (32 - levels);
        }
    }

    void transform(double[] re, double[] im) {
        for (int i = 0; i < n; i++) {
            int j = rev[i];
            if (j > i) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int size = 2; size <= n; size *= 2) {
            int half = size / 2;
            int step = n / size;
            for (int i = 0; i < n; i += size) {
                for (int j = i, k = 0; j < i + half; j++, k += step) {
                    int l = j + half;
                    double tre = re[l] * cosT[k] + im[l] * sinT[k];
                    double tim = -re[l] * sinT[k] + im[l] * cosT[k];
                    re[l] = re[j] - tre;
                    im[l] = im[j] - tim;
                    re[j] += tre;
                    im[j] += tim;
                }
            }
        }
    }
}
