package org.wyominglegacy.dlna;

import org.wyominglegacy.core.Dbg;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A very small HTTP/1.1 server for the UPnP description and SOAP control requests. One request per
 * connection (Connection: close), thread per connection with a hard cap, strict size limits. UPnP uses
 * non-standard methods (SUBSCRIBE), so a servlet container or the JDK server are not an option on
 * Android 4.
 */
public final class HttpServer {
    static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_HEADER = 16 * 1024;
    private static final int MAX_BODY = 256 * 1024;
    private static final int MAX_CONNECTIONS = 8;
    private static final int READ_TIMEOUT_MS = 8000;

    public static final class Request {
        public String method = "";
        public String path = "";
        /** Header names in lower case. */
        public final Map<String, String> headers = new LinkedHashMap<String, String>();
        public byte[] body = new byte[0];
    }

    public static final class Response {
        public int status = 200;
        public String contentType = "text/plain";
        public byte[] body = new byte[0];

        public static Response text(int status, String contentType, String text) {
            Response r = new Response();
            r.status = status;
            r.contentType = contentType;
            r.body = text.getBytes(UTF8);
            return r;
        }
    }

    public interface Handler {
        Response handle(Request request);
    }

    private static final class BadRequest extends Exception {
        final int status;

        BadRequest(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final Handler handler;
    private final String serverName;
    private final AtomicInteger active = new AtomicInteger();
    private volatile ServerSocket server;
    private volatile boolean running;

    public HttpServer(Handler handler, String serverName) {
        this.handler = handler;
        this.serverName = serverName;
    }

    /** Starts listening and returns the port (useful with port 0). */
    public int start(int port) throws IOException {
        final ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(port));
        server = ss;
        running = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                acceptLoop(ss);
            }
        }, "upnp-http-accept");
        t.setDaemon(true);
        t.start();
        return ss.getLocalPort();
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
    }

    private void acceptLoop(ServerSocket ss) {
        while (running) {
            try {
                final Socket s = ss.accept();
                if (active.incrementAndGet() > MAX_CONNECTIONS) {
                    active.decrementAndGet();
                    closeQuietly(s);
                    continue;
                }
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        try {
                            serve(s);
                        } finally {
                            active.decrementAndGet();
                            closeQuietly(s);
                        }
                    }
                }, "upnp-http");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!running) {
                    return;
                }
                Dbg.d("UPnP http accept failed: " + e);
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private void serve(Socket s) {
        try {
            s.setSoTimeout(READ_TIMEOUT_MS);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            Request req;
            try {
                req = readRequest(in, out);
            } catch (BadRequest e) {
                write(out, "GET", Response.text(e.status, "text/plain", e.getMessage()));
                return;
            }
            if (req == null) {
                return;
            }
            Response resp;
            try {
                resp = handler.handle(req);
            } catch (RuntimeException e) {
                Dbg.d("UPnP http handler failed: " + e);
                resp = Response.text(500, "text/plain", "Internal Server Error");
            }
            write(out, req.method, resp);
        } catch (IOException e) {
            // peer went away or timed out, nothing to do
        }
    }

    // ------------------------------------------------------------------ reading

    private static Request readRequest(InputStream in, OutputStream out) throws IOException, BadRequest {
        ByteArrayOutputStream head = new ByteArrayOutputStream(512);
        int state = 0; // matches \r\n\r\n
        while (state < 4) {
            int b = in.read();
            if (b < 0) {
                if (head.size() == 0) {
                    return null;
                }
                throw new BadRequest(400, "Bad Request");
            }
            head.write(b);
            if (head.size() > MAX_HEADER) {
                throw new BadRequest(431, "Request Header Fields Too Large");
            }
            if ((b == '\r' && (state == 0 || state == 2)) || (b == '\n' && (state == 1 || state == 3))) {
                state++;
            } else {
                state = b == '\r' ? 1 : 0;
            }
        }
        String[] lines = new String(head.toByteArray(), UTF8).split("\r\n");
        String[] first = lines[0].trim().split(" +");
        if (first.length < 2) {
            throw new BadRequest(400, "Bad Request");
        }
        Request r = new Request();
        r.method = first[0];
        r.path = first[1];
        int q = r.path.indexOf('?');
        if (q >= 0) {
            r.path = r.path.substring(0, q);
        }
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                r.headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.US), lines[i].substring(colon + 1).trim());
            }
        }
        String expect = r.headers.get("expect");
        if (expect != null && expect.equalsIgnoreCase("100-continue")) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(UTF8));
            out.flush();
        }
        String te = r.headers.get("transfer-encoding");
        if (te != null && te.toLowerCase(Locale.US).contains("chunked")) {
            r.body = readChunked(in);
        } else {
            String cl = r.headers.get("content-length");
            if (cl != null) {
                int len;
                try {
                    len = Integer.parseInt(cl.trim());
                } catch (NumberFormatException e) {
                    throw new BadRequest(400, "Bad Content-Length");
                }
                if (len < 0 || len > MAX_BODY) {
                    throw new BadRequest(413, "Payload Too Large");
                }
                r.body = readFully(in, len);
            }
        }
        return r;
    }

    private static byte[] readFully(InputStream in, int len) throws IOException {
        byte[] b = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n < 0) {
                throw new IOException("short body");
            }
            off += n;
        }
        return b;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("short chunked body");
            }
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                sb.append((char) b);
            }
            if (sb.length() > 64) {
                throw new IOException("chunk header too long");
            }
        }
        return sb.toString();
    }

    private static byte[] readChunked(InputStream in) throws IOException, BadRequest {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String line = readLine(in).trim();
            int semi = line.indexOf(';');
            if (semi >= 0) {
                line = line.substring(0, semi);
            }
            int size;
            try {
                size = Integer.parseInt(line, 16);
            } catch (NumberFormatException e) {
                throw new BadRequest(400, "Bad chunk");
            }
            if (size < 0 || body.size() + size > MAX_BODY) {
                throw new BadRequest(413, "Payload Too Large");
            }
            if (size == 0) {
                while (readLine(in).length() > 0) {
                    // skip trailers
                }
                return body.toByteArray();
            }
            body.write(readFully(in, size));
            readLine(in); // the CRLF after the chunk
        }
    }

    // ------------------------------------------------------------------ writing

    private void write(OutputStream out, String method, Response r) throws IOException {
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n");
        sb.append("Content-Type: ").append(r.contentType).append("\r\n");
        sb.append("Content-Length: ").append(r.body.length).append("\r\n");
        sb.append("Connection: close\r\n");
        sb.append("EXT:\r\n");
        sb.append("Server: ").append(serverName).append("\r\n\r\n");
        out.write(sb.toString().getBytes(UTF8));
        if (!"HEAD".equals(method)) {
            out.write(r.body);
        }
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200:
                return "OK";
            case 400:
                return "Bad Request";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 413:
                return "Payload Too Large";
            case 431:
                return "Request Header Fields Too Large";
            case 500:
                return "Internal Server Error";
            case 501:
                return "Not Implemented";
            default:
                return "Status";
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException e) {
            // ignore
        }
    }
}
