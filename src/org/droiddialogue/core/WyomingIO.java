package org.droiddialogue.core;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wyoming wire format: one JSON header line (type, optional data, data_length, payload_length),
 * then data_length bytes of extra JSON, then payload_length bytes of binary payload.
 */
public final class WyomingIO {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_HEADER = 1 << 20;
    private static final int MAX_BLOCK = 16 << 20;

    private WyomingIO() {
    }

    /** Reads one event. Returns null at a clean end of stream. The stream should be buffered. */
    public static Event read(InputStream in) throws IOException {
        String line;
        do {
            line = readLine(in);
            if (line == null) {
                return null;
            }
        } while (line.trim().length() == 0);

        Map<String, Object> header;
        try {
            header = Json.parseObject(line);
        } catch (IllegalArgumentException e) {
            throw new IOException("Bad Wyoming header: " + e.getMessage());
        }
        Object typeObj = header.get("type");
        if (!(typeObj instanceof String)) {
            throw new IOException("Wyoming header has no type");
        }

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        Object inline = header.get("data");
        if (inline instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) inline).entrySet()) {
                data.put(String.valueOf(e.getKey()), e.getValue());
            }
        }

        long dataLength = number(header.get("data_length"));
        if (dataLength > 0) {
            byte[] block = readFully(in, dataLength);
            try {
                data.putAll(Json.parseObject(new String(block, UTF8)));
            } catch (IllegalArgumentException e) {
                throw new IOException("Bad Wyoming data block: " + e.getMessage());
            }
        }

        byte[] payload = null;
        long payloadLength = number(header.get("payload_length"));
        if (payloadLength > 0) {
            payload = readFully(in, payloadLength);
        }
        return new Event((String) typeObj, data, payload);
    }

    /** Serialises an event (header line plus payload) into one byte array. */
    public static byte[] encode(Event e) {
        Map<String, Object> header = new LinkedHashMap<String, Object>();
        header.put("type", e.type);
        if (!e.data.isEmpty()) {
            header.put("data", e.data);
        }
        int payloadLen = e.payload != null ? e.payload.length : 0;
        if (payloadLen > 0) {
            header.put("payload_length", Long.valueOf(payloadLen));
        }
        byte[] head = (Json.stringify(header) + "\n").getBytes(UTF8);
        if (payloadLen == 0) {
            return head;
        }
        byte[] all = new byte[head.length + payloadLen];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(e.payload, 0, all, head.length, payloadLen);
        return all;
    }

    private static long number(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                return new String(bos.toByteArray(), UTF8);
            }
            bos.write(c);
            if (bos.size() > MAX_HEADER) {
                throw new IOException("Wyoming header too long");
            }
        }
        return null;
    }

    private static byte[] readFully(InputStream in, long length) throws IOException {
        if (length > MAX_BLOCK) {
            throw new IOException("Wyoming block too large: " + length);
        }
        byte[] buf = new byte[(int) length];
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new EOFException("Stream ended inside a Wyoming block");
            }
            off += n;
        }
        return buf;
    }
}
