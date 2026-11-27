package org.droiddialogue.dlna;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The little bit of XML a UPnP device needs: escaping, pulling the arguments out of a SOAP request
 * and writing SOAP responses. Tolerant on purpose, control points differ in how they write requests
 * (namespace prefixes, CDATA, unescaped metadata).
 */
public final class Xml {
    private static final Pattern BODY = Pattern.compile("<(?:[A-Za-z0-9_.-]+:)?Body(?:\\s[^>]*)?>");

    private Xml() {
    }

    public static String esc(String s) {
        if (s == null || s.length() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&apos;");
                    break;
                default:
                    if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                        break; // not allowed in XML 1.0, drop it
                    }
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String unesc(String s) {
        if (s == null || s.indexOf('&') < 0) {
            return s == null ? "" : s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '&') {
                int semi = s.indexOf(';', i);
                if (semi > i && semi - i <= 10) {
                    String ent = s.substring(i + 1, semi);
                    String rep = null;
                    if (ent.equals("amp")) {
                        rep = "&";
                    } else if (ent.equals("lt")) {
                        rep = "<";
                    } else if (ent.equals("gt")) {
                        rep = ">";
                    } else if (ent.equals("quot")) {
                        rep = "\"";
                    } else if (ent.equals("apos")) {
                        rep = "'";
                    } else if (ent.length() > 1 && ent.charAt(0) == '#') {
                        try {
                            int cp = ent.charAt(1) == 'x' || ent.charAt(1) == 'X'
                                    ? Integer.parseInt(ent.substring(2), 16)
                                    : Integer.parseInt(ent.substring(1));
                            rep = new String(Character.toChars(cp));
                        } catch (IllegalArgumentException e) {
                            rep = null;
                        }
                    }
                    if (rep != null) {
                        sb.append(rep);
                        i = semi + 1;
                        continue;
                    }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static boolean nameEnd(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '/' || c == '>';
    }

    private static String local(String qname) {
        int colon = qname.indexOf(':');
        return colon >= 0 ? qname.substring(colon + 1) : qname;
    }

    /** Name (without prefix) of the first element inside the SOAP Body, or null. */
    public static String actionName(String xml) {
        Matcher m = BODY.matcher(xml);
        if (!m.find()) {
            return null;
        }
        int i = m.end();
        while (i < xml.length()) {
            int lt = xml.indexOf('<', i);
            if (lt < 0 || lt + 1 >= xml.length()) {
                return null;
            }
            char c = xml.charAt(lt + 1);
            if (c == '/') {
                return null;
            }
            if (c == '?' || c == '!') {
                int gt = xml.indexOf('>', lt);
                if (gt < 0) {
                    return null;
                }
                i = gt + 1;
                continue;
            }
            int e = lt + 1;
            while (e < xml.length() && !nameEnd(xml.charAt(e))) {
                e++;
            }
            return local(xml.substring(lt + 1, e));
        }
        return null;
    }

    /** The child elements of the action element, name (no prefix) to text. Empty map if there are none. */
    public static Map<String, String> args(String xml, String action) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        Matcher m = Pattern.compile("<(?:[A-Za-z0-9_.-]+:)?" + Pattern.quote(action) + "(?=[\\s/>])").matcher(xml);
        if (!m.find()) {
            return out;
        }
        int gt = xml.indexOf('>', m.end());
        if (gt < 0 || xml.charAt(gt - 1) == '/') {
            return out;
        }
        int i = gt + 1;
        while (i < xml.length()) {
            int lt = xml.indexOf('<', i);
            if (lt < 0 || lt + 1 >= xml.length() || xml.charAt(lt + 1) == '/') {
                break;
            }
            if (xml.startsWith("<!--", lt)) {
                int ce = xml.indexOf("-->", lt);
                if (ce < 0) {
                    break;
                }
                i = ce + 3;
                continue;
            }
            int ne = lt + 1;
            while (ne < xml.length() && !nameEnd(xml.charAt(ne))) {
                ne++;
            }
            String qname = xml.substring(lt + 1, ne);
            int tagEnd = xml.indexOf('>', ne);
            if (tagEnd < 0) {
                break;
            }
            if (xml.charAt(tagEnd - 1) == '/') {
                out.put(local(qname), "");
                i = tagEnd + 1;
                continue;
            }
            String close = "</" + qname + ">";
            int ce = xml.indexOf(close, tagEnd + 1);
            if (ce < 0) {
                break;
            }
            out.put(local(qname), text(xml.substring(tagEnd + 1, ce)));
            i = ce + close.length();
        }
        return out;
    }

    private static String text(String raw) {
        String t = raw.trim();
        if (t.startsWith("<![CDATA[")) {
            int end = t.lastIndexOf("]]>");
            if (end > 9) {
                return t.substring(9, end);
            }
        }
        return unesc(t);
    }

    public static String envelope(String serviceType, String action, String[][] out) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
                .append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>");
        sb.append("<u:").append(action).append("Response xmlns:u=\"").append(serviceType).append("\">");
        if (out != null) {
            for (int i = 0; i < out.length; i++) {
                sb.append('<').append(out[i][0]).append('>').append(esc(out[i][1])).append("</").append(out[i][0]).append('>');
            }
        }
        sb.append("</u:").append(action).append("Response></s:Body></s:Envelope>");
        return sb.toString();
    }

    public static String fault(int code, String description) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><s:Fault>"
                + "<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>"
                + "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>" + code + "</errorCode>"
                + "<errorDescription>" + esc(description) + "</errorDescription></UPnPError>"
                + "</detail></s:Fault></s:Body></s:Envelope>";
    }
}
