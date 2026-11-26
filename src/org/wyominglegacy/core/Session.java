package org.wyominglegacy.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/** One TCP connection from Home Assistant: a reader thread, a writer thread and an outgoing queue. */
final class Session {
    private static final Event POISON = new Event("poison", new LinkedHashMap<String, Object>(), null);

    private final SatelliteCore core;
    private final Socket socket;
    private final String peer;
    private final LinkedBlockingQueue<Event> out = new LinkedBlockingQueue<Event>();
    private volatile boolean closed;

    Session(SatelliteCore core, Socket socket) {
        this.core = core;
        this.socket = socket;
        String p = "unknown";
        try {
            p = socket.getInetAddress().getHostAddress();
        } catch (Exception e) {
            // keep default
        }
        this.peer = p;
    }

    String peer() {
        return peer;
    }

    boolean isClosed() {
        return closed;
    }

    void start() {
        Thread reader = new Thread(new Runnable() {
            public void run() {
                readLoop();
            }
        }, "wyoming-reader");
        reader.setDaemon(true);
        reader.start();

        Thread writer = new Thread(new Runnable() {
            public void run() {
                writeLoop();
            }
        }, "wyoming-writer");
        writer.setDaemon(true);
        writer.start();
    }

    /** Queue a control event. Never dropped. */
    void send(Event e) {
        if (!closed) {
            out.offer(e);
        }
    }

    /** Queue an audio event. Dropped when the network cannot keep up, so the mic loop never blocks. */
    void sendAudio(Event e) {
        if (!closed && out.size() < SatelliteCore.MAX_QUEUED_AUDIO) {
            out.offer(e);
        }
    }

    void close() {
        if (closed) {
            return;
        }
        closed = true;
        out.offer(POISON);
        try {
            socket.close();
        } catch (IOException e) {
            // already closed
        }
    }

    private void readLoop() {
        try {
            InputStream in = new BufferedInputStream(socket.getInputStream(), 8192);
            Event e;
            while (!closed && (e = WyomingIO.read(in)) != null) {
                core.handleEvent(this, e);
            }
        } catch (IOException e) {
            Dbg.d("read ended: " + e);
        } catch (RuntimeException e) {
            Dbg.d("read failed: " + e);
        } finally {
            close();
            core.onSessionClosed(this);
        }
    }

    private void writeLoop() {
        try {
            OutputStream os = new BufferedOutputStream(socket.getOutputStream(), 8192);
            while (!closed) {
                Event e = out.take();
                if (e == POISON) {
                    break;
                }
                os.write(WyomingIO.encode(e));
                if (out.isEmpty()) {
                    os.flush();
                }
            }
        } catch (IOException e) {
            Dbg.d("write ended: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            close();
        }
    }
}
