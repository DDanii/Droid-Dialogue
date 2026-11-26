package org.wyominglegacy.core;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The satellite state machine. Home Assistant connects to us (we are the TCP server), asks what we
 * are with describe/info, and we then run a pipeline whenever the wake word is heard:
 *
 *   detection, run-pipeline, audio-start, audio-chunk... (until Home Assistant sends transcript)
 *   then Home Assistant sends the spoken reply as audio-start, audio-chunk..., audio-stop
 *   and we answer with played once it has finished playing.
 */
public final class SatelliteCore {
    public static final int RATE = 16000;
    public static final int WIDTH = 2;
    public static final int CHANNELS = 1;

    static final long MAX_STREAM_MS = 15000;
    static final long PIPELINE_TIMEOUT_MS = 45000;
    static final long TTS_TIMEOUT_MS = 120000;
    static final int MAX_QUEUED_AUDIO = 300;

    public final Status status = new Status();

    private final String name;
    private final String version;
    private final Speaker speaker;

    private Session session;
    private boolean streaming;
    private long streamStartedAt;
    private long pipelineStartedAt;
    private boolean ttsPlaying;
    private long ttsStartedAt;

    public SatelliteCore(String name, String version, Speaker speaker) {
        this.name = name;
        this.version = version;
        this.speaker = speaker;
    }

    // ------------------------------------------------------------------ connection handling

    void attach(Socket socket) throws IOException {
        Session s = new Session(this, socket);
        Session old;
        synchronized (this) {
            old = session;
            session = s;
            resetLocked();
            status.connected = true;
            status.peer = s.peer();
        }
        if (old != null) {
            old.close();
            speaker.stopNow();
        }
        Dbg.d("Home Assistant connected from " + s.peer());
        s.start();
    }

    void onSessionClosed(Session s) {
        boolean wasCurrent;
        synchronized (this) {
            wasCurrent = (session == s);
            if (wasCurrent) {
                session = null;
                resetLocked();
                status.connected = false;
                status.peer = "";
            }
        }
        if (wasCurrent) {
            speaker.stopNow();
            Dbg.d("Home Assistant disconnected");
        }
    }

    public void shutdown() {
        Session s;
        synchronized (this) {
            s = session;
            session = null;
            resetLocked();
            status.connected = false;
            status.peer = "";
        }
        if (s != null) {
            s.close();
        }
        speaker.stopNow();
    }

    private void resetLocked() {
        streaming = false;
        pipelineStartedAt = 0;
        ttsPlaying = false;
        status.pipeline = "idle";
    }

    // ------------------------------------------------------------------ called by the mic thread

    /** True while a pipeline is running or a reply is playing; the wake word detector must stay quiet. */
    public synchronized boolean isBusy() {
        long now = System.currentTimeMillis();
        if (pipelineStartedAt != 0 && now - pipelineStartedAt > PIPELINE_TIMEOUT_MS) {
            endPipelineLocked("timeout");
        }
        if (ttsPlaying && now - ttsStartedAt > TTS_TIMEOUT_MS) {
            ttsPlaying = false;
        }
        return pipelineStartedAt != 0 || ttsPlaying;
    }

    /** The wake word was heard (or the user pressed the talk button). Returns false if it was ignored. */
    public synchronized boolean onWakeDetected(String wakeName) {
        Session s = session;
        if (s == null || s.isClosed() || isBusy()) {
            return false;
        }
        long now = System.currentTimeMillis();
        pipelineStartedAt = now;
        streaming = true;
        streamStartedAt = now;
        status.pipeline = "listening";
        s.send(Event.of("detection", "name", wakeName));
        s.send(Event.of("run-pipeline",
                "start_stage", "asr",
                "end_stage", "tts",
                "restart_on_end", Boolean.FALSE,
                "snd_format", Event.map("rate", RATE, "width", WIDTH, "channels", CHANNELS)));
        s.send(Event.of("audio-start", "rate", RATE, "width", WIDTH, "channels", CHANNELS, "timestamp", 0));
        Dbg.d("Wake word '" + wakeName + "', pipeline started");
        return true;
    }

    /** 16 kHz mono 16-bit samples from the microphone. Forwarded to Home Assistant while listening. */
    public void onMicChunk(short[] pcm, int n) {
        byte[] bytes = null;
        synchronized (this) {
            if (!streaming || session == null) {
                return;
            }
            if (System.currentTimeMillis() - streamStartedAt > MAX_STREAM_MS) {
                stopStreamingLocked();
                return;
            }
        }
        bytes = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            bytes[2 * i] = (byte) (pcm[i] & 0xff);
            bytes[2 * i + 1] = (byte) ((pcm[i] >> 8) & 0xff);
        }
        synchronized (this) {
            if (!streaming || session == null) {
                return;
            }
            session.sendAudio(new Event("audio-chunk",
                    Event.map("rate", RATE, "width", WIDTH, "channels", CHANNELS), bytes));
        }
    }

    // ------------------------------------------------------------------ events from Home Assistant

    void handleEvent(Session s, Event e) {
        synchronized (this) {
            if (s != session) {
                return;
            }
        }
        String t = e.type;
        if (!"audio-chunk".equals(t)) {
            status.lastEvent = t;
        }

        if ("describe".equals(t)) {
            s.send(infoEvent());
        } else if ("ping".equals(t)) {
            Map<String, Object> d = new LinkedHashMap<String, Object>();
            Object text = e.data.get("text");
            if (text != null) {
                d.put("text", text);
            }
            s.send(new Event("pong", d, null));
        } else if ("transcript".equals(t) || "voice-stopped".equals(t)) {
            synchronized (this) {
                stopStreamingLocked();
            }
        } else if ("pause-satellite".equals(t)) {
            synchronized (this) {
                stopStreamingLocked();
            }
        } else if ("error".equals(t)) {
            Dbg.d("Pipeline error: " + e.data);
            synchronized (this) {
                stopStreamingLocked();
                if (!ttsPlaying) {
                    endPipelineLocked("error");
                }
            }
        } else if ("audio-start".equals(t)) {
            int rate = e.integer("rate", 22050);
            int width = e.integer("width", 2);
            int channels = e.integer("channels", 1);
            synchronized (this) {
                stopStreamingLocked();
                ttsPlaying = true;
                ttsStartedAt = System.currentTimeMillis();
                status.pipeline = "speaking";
            }
            speaker.begin(rate, width, channels);
        } else if ("audio-chunk".equals(t)) {
            if (e.payload != null) {
                speaker.write(e.payload);
            }
        } else if ("audio-stop".equals(t)) {
            final Session fs = s;
            speaker.end(new Runnable() {
                public void run() {
                    fs.send(Event.of("played"));
                    onTtsFinished();
                }
            });
        }
        // run-satellite, voice-started, synthesize, transcribe, detect, timer-* and anything else are ignored.
    }

    private void onTtsFinished() {
        synchronized (this) {
            ttsPlaying = false;
            if (pipelineStartedAt != 0) {
                endPipelineLocked("played");
            } else {
                status.pipeline = "idle";
            }
        }
    }

    private void stopStreamingLocked() {
        if (!streaming) {
            return;
        }
        streaming = false;
        Session s = session;
        if (s != null) {
            s.send(Event.of("audio-stop", "timestamp", 0));
        }
        if ("listening".equals(status.pipeline)) {
            status.pipeline = "thinking";
        }
    }

    private void endPipelineLocked(String reason) {
        stopStreamingLocked();
        pipelineStartedAt = 0;
        status.pipeline = "idle";
        Dbg.d("Pipeline finished: " + reason);
    }

    private Event infoEvent() {
        Map<String, Object> satellite = Event.map(
                "name", name,
                "attribution", Event.map("name", "Wyoming Legacy Satellite", "url", ""),
                "installed", Boolean.TRUE,
                "description", "Android satellite for old phones",
                "version", version);
        return Event.of("info",
                "asr", new ArrayList<Object>(),
                "tts", new ArrayList<Object>(),
                "handle", new ArrayList<Object>(),
                "intent", new ArrayList<Object>(),
                "wake", new ArrayList<Object>(),
                "mic", new ArrayList<Object>(),
                "snd", new ArrayList<Object>(),
                "satellite", satellite);
    }
}
