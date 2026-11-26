package org.wyominglegacy.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** One Wyoming protocol event: a type, a JSON data object and an optional binary payload. */
public final class Event {
    public final String type;
    public final Map<String, Object> data;
    public final byte[] payload;

    public Event(String type, Map<String, Object> data, byte[] payload) {
        this.type = type;
        this.data = data != null ? data : new LinkedHashMap<String, Object>();
        this.payload = payload;
    }

    /** Event with data given as alternating key, value arguments. */
    public static Event of(String type, Object... keyValues) {
        return new Event(type, map(keyValues), null);
    }

    public static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    public String str(String key, String def) {
        Object o = data.get(key);
        return o instanceof String ? (String) o : def;
    }

    public int integer(String key, int def) {
        Object o = data.get(key);
        return o instanceof Number ? ((Number) o).intValue() : def;
    }

    @Override
    public String toString() {
        return type + " " + Json.stringify(data) + (payload != null ? " +" + payload.length + "B" : "");
    }
}
