package org.droiddialogue.core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/** Accepts connections from Home Assistant. Only one is active at a time; a new one replaces the old. */
public final class WyomingServer {
    private final SatelliteCore core;
    private volatile ServerSocket server;
    private volatile boolean running;

    public WyomingServer(SatelliteCore core) {
        this.core = core;
    }

    /** Pass port 0 to pick a free port (used by tests). */
    public void start(int port) throws IOException {
        final ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(port));
        server = ss;
        running = true;
        core.status.serverRunning = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                acceptLoop(ss);
            }
        }, "wyoming-accept");
        t.setDaemon(true);
        t.start();
    }

    public int getPort() {
        ServerSocket ss = server;
        return ss != null ? ss.getLocalPort() : -1;
    }

    private void acceptLoop(ServerSocket ss) {
        while (running) {
            try {
                Socket s = ss.accept();
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);
                core.attach(s);
            } catch (IOException e) {
                if (!running) {
                    break;
                }
                Dbg.d("accept failed: " + e);
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
    }

    public void stop() {
        running = false;
        ServerSocket ss = server;
        server = null;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException e) {
                // ignore
            }
        }
        core.shutdown();
        core.status.serverRunning = false;
    }
}
