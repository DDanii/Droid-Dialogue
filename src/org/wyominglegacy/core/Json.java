package org.wyominglegacy.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser and writer. No dependencies, safe for Android API 14.
 * Objects are Map&lt;String,Object&gt;, arrays are List&lt;Object&gt;, numbers are Long or Double.
 */
public final class Json {
    private Json() {
    }

    public static Object parse(String s) {
        Parser p = new Parser(s);
        p.skipWs();
        Object v = p.readValue();
        p.skipWs();
        if (p.pos != s.length()) {
            throw p.err("Trailing characters");
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String s) {
        Object o = parse(s);
        if (!(o instanceof Map)) {
            throw new IllegalArgumentException("Not a JSON object");
        }
        return (Map<String, Object>) o;
    }

    public static String stringify(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object o) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String) {
            quote(sb, (String) o);
        } else if (o instanceof Boolean) {
            sb.append(((Boolean) o).booleanValue() ? "true" : "false");
        } else if (o instanceof Double || o instanceof Float) {
            double d = ((Number) o).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else {
                sb.append(d);
            }
        } else if (o instanceof Number) {
            sb.append(((Number) o).longValue());
        } else if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object v : (List<?>) o) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, v);
            }
            sb.append(']');
        } else {
            quote(sb, o.toString());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        String hex = Integer.toHexString(c);
                        sb.append("\\u");
                        for (int k = hex.length(); k < 4; k++) {
                            sb.append('0');
                        }
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        IllegalArgumentException err(String msg) {
            return new IllegalArgumentException(msg + " at position " + pos);
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object readValue() {
            if (pos >= s.length()) {
                throw err("Unexpected end");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return readNumber();
            }
        }

        void expect(String word) {
            if (!s.startsWith(word, pos)) {
                throw err("Unexpected token");
            }
            pos += word.length();
        }

        Map<String, Object> readObject() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            pos++; // {
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != '"') {
                    throw err("Expected string key");
                }
                String key = readString();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ':') {
                    throw err("Expected ':'");
                }
                pos++;
                skipWs();
                m.put(key, readValue());
                skipWs();
                if (pos >= s.length()) {
                    throw err("Unterminated object");
                }
                char c = s.charAt(pos++);
                if (c == ',') {
                    continue;
                }
                if (c == '}') {
                    return m;
                }
                throw err("Expected ',' or '}'");
            }
        }

        List<Object> readArray() {
            List<Object> l = new ArrayList<Object>();
            pos++; // [
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return l;
            }
            while (true) {
                skipWs();
                l.add(readValue());
                skipWs();
                if (pos >= s.length()) {
                    throw err("Unterminated array");
                }
                char c = s.charAt(pos++);
                if (c == ',') {
                    continue;
                }
                if (c == ']') {
                    return l;
                }
                throw err("Expected ',' or ']'");
            }
        }

        String readString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw err("Unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= s.length()) {
                    throw err("Bad escape");
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (pos + 4 > s.length()) {
                            throw err("Bad unicode escape");
                        }
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default:
                        throw err("Bad escape");
                }
            }
        }

        Object readNumber() {
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
            if (start == pos) {
                throw err("Unexpected character '" + s.charAt(pos) + "'");
            }
            String t = s.substring(start, pos);
            if (t.indexOf('.') >= 0 || t.indexOf('e') >= 0 || t.indexOf('E') >= 0) {
                return Double.valueOf(t);
            }
            try {
                return Long.valueOf(t);
            } catch (NumberFormatException ex) {
                return Double.valueOf(t);
            }
        }
    }
}
