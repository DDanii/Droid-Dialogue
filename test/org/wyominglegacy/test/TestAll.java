package org.wyominglegacy.test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.wyominglegacy.core.Event;
import org.wyominglegacy.core.Json;
import org.wyominglegacy.core.Resampler;
import org.wyominglegacy.core.SatelliteCore;
import org.wyominglegacy.core.Speaker;
import org.wyominglegacy.core.WyomingIO;
import org.wyominglegacy.core.WyomingServer;
import org.wyominglegacy.wake.TemplateBuilder;
import org.wyominglegacy.wake.TemplateStore;
import org.wyominglegacy.wake.WakeWordDetector;

/** Plain main()-style tests, no framework needed: java org.wyominglegacy.test.TestAll */
public final class TestAll {
    static int failures = 0;
    static int checks = 0;

    static void check(boolean ok, String msg) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL: " + msg);
        }
    }

    public static void main(String[] args) throws Exception {
        run("json", new Runnable() {
            public void run() {
                testJson();
            }
        });
        run("wyoming framing", new Runnable() {
            public void run() {
                try {
                    testFraming();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        run("protocol session", new Runnable() {
            public void run() {
                try {
                    testSession();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        run("resampler", new Runnable() {
            public void run() {
                testResampler();
            }
        });
        run("wake word", new Runnable() {
            public void run() {
                try {
                    testWakeWord();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        System.out.println();
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void run(String name, Runnable r) {
        System.out.println("== " + name);
        try {
            r.run();
        } catch (Throwable t) {
            failures++;
            System.out.println("  EXCEPTION: " + t);
            t.printStackTrace(System.out);
        }
    }

    // ------------------------------------------------------------------ resampler

    static short[] sine(int rate, double hz, double amp, int samples) {
        short[] s = new short[samples];
        for (int i = 0; i < samples; i++) {
            s[i] = (short) Math.round(amp * Math.sin(2 * Math.PI * hz * i / rate));
        }
        return s;
    }

    static double rms(short[] s, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += (double) s[i] * s[i];
        }
        return Math.sqrt(sum / (to - from));
    }

    static void testResampler() {
        int[] rates = {16000, 22050, 32000, 44100, 48000};
        for (int rate : rates) {
            Resampler rs = new Resampler(rate, 16000);
            short[] in = sine(rate, 1000, 10000, rate); // one second
            short[] out = new short[rs.maxOutput(in.length)];
            int n = rs.process(in, in.length, out);
            check(Math.abs(n - 16000) <= 1, rate + " Hz gives about 16000 samples, got " + n);
            // a 1 kHz tone keeps nearly all of its level (the box filter costs well under 1 dB there)
            double level = rms(out, 800, n - 800) / (10000 / Math.sqrt(2));
            check(level > 0.9 && level < 1.05, rate + " Hz: 1 kHz tone level " + level);
            // and still has 1 kHz: about 2000 zero crossings per second
            int cross = 0;
            for (int i = 801; i < n - 800; i++) {
                if ((out[i - 1] < 0) != (out[i] < 0)) {
                    cross++;
                }
            }
            double hz = cross / 2.0 / ((n - 1600) / 16000.0);
            check(Math.abs(hz - 1000) < 20, rate + " Hz: frequency " + hz);
        }

        // DC passes through unchanged
        Resampler dc = new Resampler(44100, 16000);
        short[] flat = new short[4410];
        java.util.Arrays.fill(flat, (short) 12345);
        short[] dcOut = new short[dc.maxOutput(flat.length)];
        int dn = dc.process(flat, flat.length, dcOut);
        boolean allSame = dn > 100;
        for (int i = 0; i < dn; i++) {
            allSame &= dcOut[i] == 12345;
        }
        check(allSame, "constant input stays constant");

        // chunk boundaries must not change the result
        Random r = new Random(7);
        short[] in = new short[48000];
        for (int i = 0; i < in.length; i++) {
            in[i] = (short) (r.nextInt(20000) - 10000);
        }
        Resampler whole = new Resampler(44100, 16000);
        short[] a = new short[whole.maxOutput(in.length)];
        int an = whole.process(in, in.length, a);
        Resampler parts = new Resampler(44100, 16000);
        short[] b = new short[an + 8];
        int bn = 0;
        int pos = 0;
        int[] sizes = {1, 7, 480, 1323, 3, 2205, 999};
        int k = 0;
        while (pos < in.length) {
            int len = Math.min(sizes[k++ % sizes.length], in.length - pos);
            short[] chunk = new short[len];
            System.arraycopy(in, pos, chunk, 0, len);
            short[] tmp = new short[parts.maxOutput(len)];
            int c = parts.process(chunk, len, tmp);
            System.arraycopy(tmp, 0, b, bn, c);
            bn += c;
            pos += len;
        }
        check(an == bn, "same length in chunks: " + an + " vs " + bn);
        boolean same = an == bn;
        for (int i = 0; same && i < an; i++) {
            same = a[i] == b[i];
        }
        check(same, "same samples in chunks");

        // clipping instead of wrapping around
        Resampler clip = new Resampler(48000, 16000);
        short[] loud = new short[300];
        java.util.Arrays.fill(loud, Short.MAX_VALUE);
        short[] loudOut = new short[clip.maxOutput(loud.length)];
        int ln = clip.process(loud, loud.length, loudOut);
        boolean positive = ln > 50;
        for (int i = 0; i < ln; i++) {
            positive &= loudOut[i] == Short.MAX_VALUE;
        }
        check(positive, "full scale input does not wrap");

        boolean threw = false;
        try {
            new Resampler(8000, 16000);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "upsampling is refused");
    }

    // ------------------------------------------------------------------ JSON

    static void testJson() {
        String src = "{\"a\":1,\"b\":[true,false,null,1.5,\"x\\n\\u00e9\"],\"c\":{\"d\":-3},\"e\":{}}";
        Map<String, Object> m = Json.parseObject(src);
        check(((Long) m.get("a")).longValue() == 1, "int parsed as Long");
        List<?> b = (List<?>) m.get("b");
        check(b.size() == 5 && Boolean.TRUE.equals(b.get(0)) && b.get(2) == null, "array contents");
        check(((Double) b.get(3)).doubleValue() == 1.5, "double parsed");
        check("x\né".equals(b.get(4)), "string escapes");
        String out = Json.stringify(m);
        check(Json.stringify(Json.parseObject(out)).equals(out), "round trip stable");
        check(Json.stringify("a\"b\\c\n\u0001").equals("\"a\\\"b\\\\c\\n\\u0001\""), "string escaping on write");
        check(Json.stringify(Event.map("n", Integer.valueOf(7), "t", Boolean.TRUE)).equals("{\"n\":7,\"t\":true}"),
                "map with Integer and Boolean");
        String[] bad = {"{\"a\":}", "{\"a\" 1}", "[1,2", "{\"a\":1} x", "\"abc", "nul"};
        for (int i = 0; i < bad.length; i++) {
            boolean threw = false;
            try {
                Json.parse(bad[i]);
            } catch (IllegalArgumentException e) {
                threw = true;
            }
            check(threw, "rejects " + bad[i]);
        }
    }

    // ------------------------------------------------------------------ framing

    static void testFraming() throws IOException {
        byte[] pcm = new byte[]{1, 2, 3, 4, 5};
        Event e1 = new Event("audio-chunk", Event.map("rate", 16000, "width", 2, "channels", 1), pcm);
        Event e2 = Event.of("ping", "text", "héllo");
        Event e3 = Event.of("describe");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(WyomingIO.encode(e1));
        bos.write(WyomingIO.encode(e2));
        bos.write(WyomingIO.encode(e3));
        InputStream in = new ByteArrayInputStream(bos.toByteArray());
        Event r1 = WyomingIO.read(in);
        check(r1.type.equals("audio-chunk") && r1.integer("rate", 0) == 16000, "audio-chunk header");
        check(r1.payload != null && java.util.Arrays.equals(r1.payload, pcm), "audio-chunk payload");
        Event r2 = WyomingIO.read(in);
        check("héllo".equals(r2.str("text", null)) && r2.payload == null, "ping with unicode");
        Event r3 = WyomingIO.read(in);
        check(r3.type.equals("describe") && r3.data.isEmpty(), "describe has no data");
        check(WyomingIO.read(in) == null, "clean EOF returns null");

        // Header with a separate data block, as written by the Python library.
        String block = "{\"b\":2}";
        String raw = "{\"type\":\"x\",\"data\":{\"a\":1},\"data_length\":" + block.length()
                + ",\"payload_length\":3}\n" + block + "abc" + "{\"type\":\"y\"}\n";
        InputStream in2 = new ByteArrayInputStream(raw.getBytes("UTF-8"));
        Event x = WyomingIO.read(in2);
        check(x.integer("a", 0) == 1 && x.integer("b", 0) == 2, "inline data merged with data block");
        check(x.payload != null && new String(x.payload, "UTF-8").equals("abc"), "payload after data block");
        check(WyomingIO.read(in2).type.equals("y"), "next event after block");

        boolean threw = false;
        try {
            WyomingIO.read(new ByteArrayInputStream("not json\n".getBytes("UTF-8")));
        } catch (IOException e) {
            threw = true;
        }
        check(threw, "garbage header raises IOException");
        threw = false;
        try {
            WyomingIO.read(new ByteArrayInputStream("{\"type\":\"a\",\"payload_length\":10}\nabc".getBytes("UTF-8")));
        } catch (IOException e) {
            threw = true;
        }
        check(threw, "truncated payload raises IOException");
    }

    // ------------------------------------------------------------------ protocol

    static final class FakeSpeaker implements Speaker {
        volatile int begins;
        volatile int rate;
        volatile int bytes;
        volatile int ends;
        volatile int stops;

        public void begin(int r, int width, int channels) {
            begins++;
            rate = r;
        }

        public void write(byte[] pcm) {
            bytes += pcm.length;
        }

        public void end(final Runnable done) {
            ends++;
            new Thread(new Runnable() {
                public void run() {
                    try {
                        Thread.sleep(30);
                    } catch (InterruptedException e) {
                        // ignore
                    }
                    done.run();
                }
            }).start();
        }

        public void stopNow() {
            stops++;
        }
    }

    static Event next(InputStream in) throws IOException {
        Event e = WyomingIO.read(in);
        if (e == null) {
            throw new IOException("connection closed while waiting for an event");
        }
        return e;
    }

    static void send(OutputStream out, Event e) throws IOException {
        out.write(WyomingIO.encode(e));
        out.flush();
    }

    static boolean waitFor(java.util.concurrent.Callable<Boolean> cond, int ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (cond.call().booleanValue()) {
                return true;
            }
            Thread.sleep(10);
        }
        return cond.call().booleanValue();
    }

    @SuppressWarnings("unchecked")
    static void testSession() throws Exception {
        final FakeSpeaker speaker = new FakeSpeaker();
        final SatelliteCore core = new SatelliteCore("Test Sat", "9.9", speaker);
        WyomingServer server = new WyomingServer(core);
        server.start(0);
        try {
            Socket ha = new Socket("127.0.0.1", server.getPort());
            ha.setSoTimeout(5000);
            InputStream in = new BufferedInputStream(ha.getInputStream());
            OutputStream out = ha.getOutputStream();

            send(out, Event.of("describe"));
            Event info = next(in);
            check(info.type.equals("info"), "describe is answered with info");
            Map<String, Object> sat = (Map<String, Object>) info.data.get("satellite");
            check(sat != null && "Test Sat".equals(sat.get("name")), "info carries the satellite name");
            check(sat != null && Boolean.TRUE.equals(sat.get("installed")), "satellite is marked installed");
            check(sat != null && sat.get("attribution") instanceof Map, "attribution present");
            check(info.data.get("wake") instanceof List, "other service lists present");

            send(out, Event.of("ping", "text", "abc"));
            Event pong = next(in);
            check(pong.type.equals("pong") && "abc".equals(pong.str("text", null)), "ping is answered with pong echo");

            send(out, Event.of("run-satellite"));
            check(waitFor(new java.util.concurrent.Callable<Boolean>() {
                public Boolean call() {
                    return Boolean.valueOf(core.status.connected);
                }
            }, 1000), "status shows connected");

            // wake word heard
            check(core.onWakeDetected("hey test"), "wake detection accepted when connected");
            check(!core.onWakeDetected("again"), "second detection ignored while busy");
            Event det = next(in);
            check(det.type.equals("detection") && "hey test".equals(det.str("name", null)), "detection event");
            Event run = next(in);
            check(run.type.equals("run-pipeline"), "run-pipeline event");
            check("asr".equals(run.str("start_stage", "")) && "tts".equals(run.str("end_stage", "")), "pipeline stages");
            Map<String, Object> fmt = (Map<String, Object>) run.data.get("snd_format");
            check(fmt != null && ((Number) fmt.get("rate")).intValue() == 16000, "snd_format requested");
            check(Boolean.FALSE.equals(run.data.get("restart_on_end")), "restart_on_end false");
            Event as = next(in);
            check(as.type.equals("audio-start") && as.integer("rate", 0) == 16000
                    && as.integer("width", 0) == 2 && as.integer("channels", 0) == 1, "audio-start format");

            short[] pcm = new short[480];
            for (int i = 0; i < pcm.length; i++) {
                pcm[i] = (short) (i - 240);
            }
            for (int i = 0; i < 3; i++) {
                core.onMicChunk(pcm, pcm.length);
            }
            for (int i = 0; i < 3; i++) {
                Event ch = next(in);
                check(ch.type.equals("audio-chunk") && ch.payload != null && ch.payload.length == 960,
                        "audio-chunk " + i + " has 960 bytes");
                if (i == 0 && ch.payload != null) {
                    int first = (ch.payload[0] & 0xff) | (ch.payload[1] << 8);
                    check(first == -240, "samples are little-endian 16-bit");
                }
            }
            check(core.isBusy(), "busy while listening");

            // Home Assistant finished listening
            send(out, Event.of("transcript", "text", "turn on the light"));
            Event stop = next(in);
            check(stop.type.equals("audio-stop"), "audio-stop after transcript");
            core.onMicChunk(pcm, pcm.length);
            send(out, Event.of("ping", "text", "z"));
            Event after = next(in);
            check(after.type.equals("pong"), "no audio sent after transcript (got " + after.type + ")");

            // spoken reply
            send(out, Event.of("synthesize", "text", "ok"));
            send(out, Event.of("audio-start", "rate", 22050, "width", 2, "channels", 1, "timestamp", 0));
            send(out, new Event("audio-chunk", Event.map("rate", 22050, "width", 2, "channels", 1), new byte[100]));
            send(out, new Event("audio-chunk", Event.map("rate", 22050, "width", 2, "channels", 1), new byte[100]));
            send(out, Event.of("audio-stop"));
            Event played = next(in);
            check(played.type.equals("played"), "played after the reply finished (got " + played.type + ")");
            check(speaker.begins == 1 && speaker.rate == 22050 && speaker.bytes == 200 && speaker.ends == 1,
                    "speaker received the reply");
            check(waitFor(new java.util.concurrent.Callable<Boolean>() {
                public Boolean call() {
                    return Boolean.valueOf(!core.isBusy());
                }
            }, 1000), "idle again after played");

            // pipeline error ends the run
            check(core.onWakeDetected("hey test"), "second wake accepted");
            next(in);
            next(in);
            next(in);
            send(out, Event.of("error", "text", "boom", "code", "stt-no-text-recognized"));
            check(next(in).type.equals("audio-stop"), "audio-stop after error");
            check(waitFor(new java.util.concurrent.Callable<Boolean>() {
                public Boolean call() {
                    return Boolean.valueOf(!core.isBusy());
                }
            }, 1000), "idle after error");

            // an announcement without a pipeline is played and acknowledged
            send(out, Event.of("audio-start", "rate", 16000, "width", 2, "channels", 1));
            send(out, new Event("audio-chunk", Event.map("rate", 16000, "width", 2, "channels", 1), new byte[64]));
            send(out, Event.of("audio-stop"));
            check(next(in).type.equals("played"), "announcement acknowledged");

            // a new connection replaces the old one
            Socket ha2 = new Socket("127.0.0.1", server.getPort());
            ha2.setSoTimeout(5000);
            InputStream in2 = new BufferedInputStream(ha2.getInputStream());
            OutputStream out2 = ha2.getOutputStream();
            send(out2, Event.of("describe"));
            check(next(in2).type.equals("info"), "second connection works");
            boolean closed = false;
            try {
                closed = WyomingIO.read(in) == null;
            } catch (IOException e) {
                closed = true;
            }
            check(closed, "first connection was closed");
            check(core.onWakeDetected("x"), "wake works on the new connection");
            check(next(in2).type.equals("detection"), "events go to the new connection");
            ha2.close();
            ha.close();
            check(waitFor(new java.util.concurrent.Callable<Boolean>() {
                public Boolean call() {
                    return Boolean.valueOf(!core.status.connected);
                }
            }, 2000), "status shows disconnected");
            check(!core.onWakeDetected("y"), "wake ignored without a connection");
        } finally {
            server.stop();
        }
    }

    // ------------------------------------------------------------------ wake word

    /** phone = {duration ms, type (0 silence, 1 voiced, 2 noise), f0, F1, F2} */
    static final double[][] WORD_A = {
        {110, 2, 0, 1500, 3000}, {160, 1, 128, 600, 1800}, {130, 1, 135, 300, 2300}, {70, 0, 0, 0, 0},
        {90, 2, 0, 4000, 6000}, {210, 1, 125, 500, 900}, {130, 1, 118, 250, 1200}};
    static final double[][] WORD_B = {
        {200, 1, 110, 800, 1300}, {140, 2, 0, 3500, 5000}, {170, 1, 120, 350, 2100}, {210, 1, 115, 450, 800},
        {100, 2, 0, 1200, 2400}};
    /** Starts like A, ends differently. */
    static final double[][] WORD_C = {
        {110, 2, 0, 1500, 3000}, {160, 1, 128, 600, 1800}, {130, 1, 135, 300, 2300}, {70, 0, 0, 0, 0},
        {140, 1, 120, 700, 1100}, {230, 1, 112, 400, 1700}, {110, 2, 0, 5000, 7000}};

    static double gauss(Random r) {
        return r.nextGaussian();
    }

    static short[] noise(int ms, double amp, Random r) {
        short[] s = new short[ms * 16];
        for (int i = 0; i < s.length; i++) {
            s[i] = (short) Math.round(gauss(r) * amp);
        }
        return s;
    }

    static short[] concat(short[]... parts) {
        int n = 0;
        for (int i = 0; i < parts.length; i++) {
            n += parts[i].length;
        }
        short[] out = new short[n];
        int pos = 0;
        for (int i = 0; i < parts.length; i++) {
            System.arraycopy(parts[i], 0, out, pos, parts[i].length);
            pos += parts[i].length;
        }
        return out;
    }

    /** Crude formant synthesis: voiced phones are harmonics shaped by two resonances, noise phones are band noise. */
    static short[] word(double[][] phones, Random r, double timeJitter, double f0Scale, double peak, double noiseAmp) {
        int[] lens = new int[phones.length];
        int total = 0;
        for (int p = 0; p < phones.length; p++) {
            double jitter = 1 + timeJitter * (r.nextDouble() * 2 - 1);
            lens[p] = (int) Math.round(phones[p][0] * jitter * 16);
            total += lens[p];
        }
        double[] out = new double[total];
        double[] noiseLayer = new double[total];
        int pos = 0;
        for (int p = 0; p < phones.length; p++) {
            int n = lens[p];
            int type = (int) phones[p][1];
            double ramp = 0.015 * 16000;
            if (type == 1) {
                double f0 = phones[p][2] * f0Scale * (1 + 0.03 * (r.nextDouble() * 2 - 1));
                double f1 = phones[p][3] * (1 + 0.04 * (r.nextDouble() * 2 - 1));
                double f2 = phones[p][4] * (1 + 0.04 * (r.nextDouble() * 2 - 1));
                for (int h = 1; h * f0 < 5500; h++) {
                    double freq = h * f0;
                    double w = Math.exp(-sq((freq - f1) / (0.3 * f1 + 60))) + 0.6 * Math.exp(-sq((freq - f2) / (0.25 * f2 + 100)));
                    if (w < 0.02) {
                        continue;
                    }
                    double phase = r.nextDouble() * 2 * Math.PI;
                    for (int s = 0; s < n; s++) {
                        double env = Math.min(1.0, Math.min(s / ramp, (n - s) / ramp));
                        out[pos + s] += w * Math.sin(2 * Math.PI * freq * s / 16000.0 + phase) * env;
                    }
                }
            } else if (type == 2) {
                double fc = (phones[p][3] + phones[p][4]) / 2;
                double prev = 0;
                for (int s = 0; s < n; s++) {
                    double x = gauss(r);
                    double lp = 0.5 * (x + prev);
                    prev = x;
                    double env = Math.min(1.0, Math.min(s / ramp, (n - s) / ramp));
                    noiseLayer[pos + s] = lp * Math.cos(2 * Math.PI * fc * s / 16000.0) * env;
                }
            }
            pos += n;
        }
        double vpeak = 1e-9;
        for (int i = 0; i < total; i++) {
            vpeak = Math.max(vpeak, Math.abs(out[i]));
        }
        double npeak = 1e-9;
        for (int i = 0; i < total; i++) {
            npeak = Math.max(npeak, Math.abs(noiseLayer[i]));
        }
        for (int i = 0; i < total; i++) {
            out[i] = out[i] / vpeak + 0.3 * noiseLayer[i] / npeak;
        }
        short[] s = new short[total];
        for (int i = 0; i < total; i++) {
            double v = out[i] * peak + gauss(r) * noiseAmp;
            s[i] = (short) Math.max(-32768, Math.min(32767, Math.round(v)));
        }
        return s;
    }

    static double sq(double x) {
        return x * x;
    }

    static short[] babble(Random r, int seconds) {
        List<short[]> parts = new ArrayList<short[]>();
        int ms = 0;
        while (ms < seconds * 1000) {
            int count = 2 + r.nextInt(5);
            double[][] phones = new double[count][];
            for (int i = 0; i < count; i++) {
                int type = r.nextInt(5) == 0 ? 2 : 1;
                phones[i] = new double[]{80 + r.nextInt(180), type, 100 + r.nextInt(90),
                    250 + r.nextInt(650), 800 + r.nextInt(1700)};
                if (type == 2) {
                    phones[i][3] = 1000 + r.nextInt(3000);
                    phones[i][4] = phones[i][3] + 1000 + r.nextInt(2000);
                }
            }
            short[] w = word(phones, r, 0.1, 1.0, 3000 + r.nextInt(8000), 30);
            parts.add(w);
            int gap = 100 + r.nextInt(1500);
            parts.add(noise(gap, 30, r));
            ms += w.length / 16 + gap;
        }
        return concat(parts.toArray(new short[parts.size()][]));
    }

    static short[] recording(Random r) {
        double f0 = 0.93 + 0.2 * r.nextDouble();
        double peak = 4000 + 6000 * r.nextDouble();
        return concat(noise(400, 30, r), word(WORD_A, r, 0.12, f0, peak, 30), noise(900, 30, r));
    }

    static final class Segment {
        final String label;
        final int start;
        final int end;
        final boolean positive;
        double bestScore = 1e9;
        int hits;

        Segment(String label, int start, int end, boolean positive) {
            this.label = label;
            this.start = start;
            this.end = end;
            this.positive = positive;
        }
    }

    static void testWakeWord() throws Exception {
        Random r = new Random(12345);
        List<float[][]> templates = new ArrayList<float[][]>();
        for (int i = 0; i < 4; i++) {
            short[] rec = recording(r);
            float[][] t = TemplateBuilder.build(rec, rec.length);
            templates.add(t);
            check(t.length >= TemplateBuilder.MIN_FRAMES && t.length <= TemplateBuilder.MAX_FRAMES,
                    "template " + i + " length " + t.length + " is sane");
        }
        double threshold = TemplateBuilder.autoThreshold(templates);
        check(threshold > 0, "auto threshold available with 4 templates");
        check(TemplateBuilder.autoThreshold(templates.subList(0, 2)) == 0, "no threshold with fewer than 3 templates");
        System.out.println("  templates: " + templates.size() + ", auto threshold " + String.format("%.2f", threshold));

        // bad recordings are rejected with a message
        String[] bad = new String[2];
        short[] silence = noise(2500, 30, r);
        try {
            TemplateBuilder.build(silence, silence.length);
        } catch (IllegalArgumentException e) {
            bad[0] = e.getMessage();
        }
        check(bad[0] != null, "silence is rejected as a template");
        short[] cut = concat(noise(400, 30, r), word(WORD_A, r, 0.0, 1.0, 6000, 30));
        cut = java.util.Arrays.copyOf(cut, cut.length - 400); // chops the end off the phrase
        try {
            TemplateBuilder.build(cut, cut.length);
        } catch (IllegalArgumentException e) {
            bad[1] = e.getMessage();
        }
        check(bad[1] != null, "a phrase cut off at the end is rejected");

        // storage round trip
        File f = File.createTempFile("tpl", ".bin");
        f.deleteOnExit();
        TemplateStore.save(templates, f);
        List<float[][]> loaded = TemplateStore.load(f);
        check(loaded.size() == templates.size() && loaded.get(0).length == templates.get(0).length
                && loaded.get(0)[3][5] == templates.get(0)[3][5], "templates survive save and load");
        check(TemplateStore.load(new File("/nonexistent/x.bin")).isEmpty(), "missing file loads as empty");

        // test stream
        List<Segment> segs = new ArrayList<Segment>();
        List<short[]> parts = new ArrayList<short[]>();
        int[] pos = {0};
        addPart(parts, segs, pos, "silence", noise(1000, 30, r), false, false);
        addPart(parts, segs, pos, "A1", word(WORD_A, r, 0.12, 1.00, 7000, 30), true, true);
        addPart(parts, segs, pos, "gap", noise(1500, 30, r), false, false);
        addPart(parts, segs, pos, "B", word(WORD_B, r, 0.12, 1.00, 7000, 30), true, false);
        addPart(parts, segs, pos, "gap", noise(1500, 30, r), false, false);
        addPart(parts, segs, pos, "C", word(WORD_C, r, 0.12, 1.00, 7000, 30), true, false);
        addPart(parts, segs, pos, "gap", noise(1500, 30, r), false, false);
        addPart(parts, segs, pos, "A2", word(WORD_A, r, 0.15, 1.08, 4500, 30), true, true);
        addPart(parts, segs, pos, "gap", noise(1500, 30, r), false, false);
        addPart(parts, segs, pos, "A3 loud+noisy", word(WORD_A, r, 0.12, 0.95, 14000, 150), true, true);
        addPart(parts, segs, pos, "gap", noise(1500, 150, r), false, false);
        addPart(parts, segs, pos, "B2", word(WORD_B, r, 0.10, 1.05, 5000, 30), true, false);
        addPart(parts, segs, pos, "gap", noise(1000, 30, r), false, false);
        short[] stream = concat(parts.toArray(new short[parts.size()][]));

        WakeWordDetector det = new WakeWordDetector();
        det.setTemplates(templates, threshold);
        List<Segment> speech = new ArrayList<Segment>();
        for (Segment s : segs) {
            if (s.positive || s.label.equals("B") || s.label.equals("C") || s.label.equals("B2")) {
                speech.add(s);
            }
        }
        int falseHits = 0;
        short[] chunk = new short[480];
        for (int off = 0; off + chunk.length <= stream.length; off += chunk.length) {
            System.arraycopy(stream, off, chunk, 0, chunk.length);
            boolean hit = det.process(chunk, chunk.length);
            double score = det.recentBest();
            int at = off + chunk.length;
            Segment owner = null;
            for (Segment s : segs) {
                if (at > s.start && at <= s.end + 4800) {
                    owner = s;
                }
            }
            if (owner != null && score < owner.bestScore) {
                owner.bestScore = score;
            }
            if (hit) {
                if (owner != null && owner.label.equals("A1") || owner != null && owner.label.equals("A2")
                        || owner != null && owner.label.startsWith("A3")) {
                    owner.hits++;
                } else {
                    falseHits++;
                    System.out.println("  unexpected detection at " + (at / 16000.0) + " s in " + (owner != null ? owner.label : "gap"));
                }
            }
        }
        for (Segment s : speech) {
            System.out.println("  segment " + s.label + ": best score " + String.format("%.2f", s.bestScore)
                    + ", detections " + s.hits + (s.bestScore < threshold ? "  (below threshold)" : ""));
            if (s.positive && s.label.startsWith("A")) {
                check(s.hits == 1, "wake phrase " + s.label + " detected exactly once");
            }
        }
        check(falseHits == 0, "no detections on other words or gaps");

        // long run of unrelated speech-like audio must not trigger
        short[] long1 = babble(new Random(777), 90);
        WakeWordDetector det2 = new WakeWordDetector();
        det2.setTemplates(templates, threshold);
        int fa = 0;
        for (int off = 0; off + chunk.length <= long1.length; off += chunk.length) {
            System.arraycopy(long1, off, chunk, 0, chunk.length);
            if (det2.process(chunk, chunk.length)) {
                fa++;
            }
        }
        System.out.println("  false detections in 90 s of random speech-like audio: " + fa);
        check(fa == 0, "no false detections in 90 s of unrelated speech-like audio");

        // pure noise and silence
        short[] quiet = concat(noise(20000, 30, r), noise(20000, 300, r), new short[16000 * 5]);
        WakeWordDetector det3 = new WakeWordDetector();
        det3.setTemplates(templates, threshold);
        int fq = 0;
        for (int off = 0; off + chunk.length <= quiet.length; off += chunk.length) {
            System.arraycopy(quiet, off, chunk, 0, chunk.length);
            if (det3.process(chunk, chunk.length)) {
                fq++;
            }
        }
        check(fq == 0, "no detections on noise and digital silence");

        // detector without templates stays silent
        WakeWordDetector det4 = new WakeWordDetector();
        check(!det4.isReady(), "detector without templates is not ready");
        boolean any = false;
        for (int off = 0; off + chunk.length <= stream.length; off += chunk.length) {
            System.arraycopy(stream, off, chunk, 0, chunk.length);
            any |= det4.process(chunk, chunk.length);
        }
        check(!any, "detector without templates never fires");
    }

    static void addPart(List<short[]> parts, List<Segment> segs, int[] pos, String label, short[] data,
                        boolean speech, boolean positive) {
        segs.add(new Segment(label, pos[0], pos[0] + data.length, speech && positive));
        parts.add(data);
        pos[0] += data.length;
    }
}
