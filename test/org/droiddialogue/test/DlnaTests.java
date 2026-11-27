package org.droiddialogue.test;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.droiddialogue.dlna.Control;
import org.droiddialogue.dlna.Descriptions;
import org.droiddialogue.dlna.MediaBackend;
import org.droiddialogue.dlna.MediaRenderer;
import org.droiddialogue.dlna.Xml;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests of the UPnP/DLNA media renderer. A fake control point does what Home Assistant's UPnP library
 * does: search with SSDP, fetch the description, parse every service description, call the actions.
 * It checks that the pieces are consistent with each other and with the UPnP rules; it cannot prove
 * that a real Home Assistant accepts the device.
 */
final class DlnaTests {
    private DlnaTests() {
    }

    static void check(boolean ok, String msg) {
        TestAll.check(ok, msg);
    }

    // ------------------------------------------------------------------ fake backend

    static final class FakeBackend implements MediaBackend {
        volatile int state = NO_MEDIA;
        volatile String uri = "";
        volatile long position;
        volatile long duration = 187000;
        volatile int volume = 40;
        volatile boolean muted;
        volatile Listener listener;
        final AtomicInteger changes = new AtomicInteger();

        public void setListener(Listener l) {
            listener = l;
        }

        private void set(int s) {
            state = s;
            changes.incrementAndGet();
        }

        public void setUri(String u) {
            uri = u;
            position = 0;
            set(u.length() == 0 ? NO_MEDIA : STOPPED);
        }

        public void play() {
            if (uri.length() == 0) {
                throw new IllegalStateException("no media");
            }
            set(PLAYING);
        }

        public void pause() {
            if (state == PLAYING) {
                set(PAUSED);
            }
        }

        public void stop() {
            if (uri.length() > 0) {
                position = 0;
                set(STOPPED);
            }
        }

        public void seek(long ms) {
            position = ms;
        }

        public int getState() {
            return state;
        }

        public long getPositionMs() {
            return position;
        }

        public long getDurationMs() {
            return state == NO_MEDIA ? 0 : duration;
        }

        public int getVolume() {
            return volume;
        }

        public void setVolume(int v) {
            volume = v;
        }

        public boolean isMuted() {
            return muted;
        }

        public void setMuted(boolean m) {
            muted = m;
        }
    }

    // ------------------------------------------------------------------ XML and HTTP helpers

    static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
    }

    static List<Element> children(Element parent, String localName) {
        List<Element> out = new ArrayList<Element>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && localName.equals(n.getLocalName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    static Element child(Element parent, String localName) {
        List<Element> l = children(parent, localName);
        return l.isEmpty() ? null : l.get(0);
    }

    static String text(Element parent, String localName) {
        Element e = child(parent, localName);
        return e == null ? null : e.getTextContent();
    }

    static final class Http {
        int status;
        String body;
        String contentType;
    }

    static Http http(String method, String url, String body, String soapAction) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
        c.setRequestMethod(method);
        c.setConnectTimeout(3000);
        c.setReadTimeout(5000);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            if (soapAction != null) {
                c.setRequestProperty("SOAPACTION", "\"" + soapAction + "\"");
            }
            byte[] b = body.getBytes("UTF-8");
            c.setFixedLengthStreamingMode(b.length);
            OutputStream o = c.getOutputStream();
            o.write(b);
            o.close();
        }
        Http h = new Http();
        h.status = c.getResponseCode();
        h.contentType = c.getContentType();
        InputStream is = h.status >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (is != null) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
        }
        h.body = new String(bo.toByteArray(), "UTF-8");
        return h;
    }

    /** Sends raw bytes and returns everything the server answers until it closes the connection. */
    static String raw(int port, String request) throws Exception {
        Socket s = new Socket("127.0.0.1", port);
        try {
            s.setSoTimeout(4000);
            s.getOutputStream().write(request.getBytes("ISO-8859-1"));
            s.getOutputStream().flush();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            try {
                int n;
                while ((n = s.getInputStream().read(buf)) > 0) {
                    bo.write(buf, 0, n);
                }
            } catch (SocketTimeoutException e) {
                // return what we have
            }
            return new String(bo.toByteArray(), "ISO-8859-1");
        } finally {
            s.close();
        }
    }

    static final class SoapFault extends Exception {
        final int code;

        SoapFault(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** Calls a SOAP action like a control point does. Values are escaped here. */
    static Map<String, String> soap(String base, Descriptions.Service svc, String action, String... pairs) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
                .append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:").append(action)
                .append(" xmlns:u=\"").append(svc.type).append("\">");
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            sb.append('<').append(pairs[i]).append('>').append(Xml.esc(pairs[i + 1])).append("</").append(pairs[i]).append('>');
        }
        sb.append("</u:").append(action).append("></s:Body></s:Envelope>");
        Http h = http("POST", base + svc.controlPath(), sb.toString(), svc.type + "#" + action);
        Document d = parse(h.body);
        Element env = d.getDocumentElement();
        Element body = child(env, "Body");
        if (h.status == 500) {
            Element fault = child(body, "Fault");
            Element err = child(child(fault, "detail"), "UPnPError");
            throw new SoapFault(Integer.parseInt(text(err, "errorCode")), text(err, "errorDescription"));
        }
        if (h.status != 200) {
            throw new Exception("HTTP " + h.status);
        }
        Element resp = null;
        for (Node n = body.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element) {
                resp = (Element) n;
                break;
            }
        }
        check(resp != null && (action + "Response").equals(resp.getLocalName()), action + " answered with " + action + "Response");
        check(resp != null && svc.type.equals(resp.getNamespaceURI()), action + " response has the service namespace");
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Node n = resp.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element) {
                out.put(n.getLocalName(), n.getTextContent());
            }
        }
        return out;
    }

    static int faultCode(String base, Descriptions.Service svc, String action, String... pairs) throws Exception {
        try {
            soap(base, svc, action, pairs);
            return 0;
        } catch (SoapFault f) {
            return f.code;
        }
    }

    // ------------------------------------------------------------------ the tests

    static void run() throws Exception {
        testXml();
        testDescriptions();
        testRenderer();
    }

    static void testXml() {
        String tricky = "<DIDL-Lite xmlns=\"urn:x\" a='1'>Tom & Jerry \"quoted\"</DIDL-Lite>";
        check(Xml.unesc(Xml.esc(tricky)).equals(tricky), "escape round trip");
        check(Xml.esc("a\u0001b").equals("ab"), "control characters are dropped");
        check(Xml.unesc("&#65;&#x42;&amp;lt;").equals("AB&lt;"), "numeric entities and no double unescape");
        check(Xml.unesc("&bogus; & more").equals("&bogus; & more"), "unknown entities stay");

        String body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
                + "<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><InstanceID>0</InstanceID>"
                + "<CurrentURI>http://a/b?x=1&amp;y=2</CurrentURI>"
                + "<CurrentURIMetaData>&lt;DIDL-Lite&gt;&lt;/DIDL-Lite&gt;</CurrentURIMetaData></u:SetAVTransportURI>"
                + "</s:Body></s:Envelope>";
        check("SetAVTransportURI".equals(Xml.actionName(body)), "action name with prefixes");
        Map<String, String> a = Xml.args(body, "SetAVTransportURI");
        check("0".equals(a.get("InstanceID")), "InstanceID parsed");
        check("http://a/b?x=1&y=2".equals(a.get("CurrentURI")), "entities in the URI are decoded");
        check("<DIDL-Lite></DIDL-Lite>".equals(a.get("CurrentURIMetaData")), "escaped metadata decoded");

        String variants = "<Envelope><Body><Play><InstanceID>0</InstanceID><Speed>1</Speed><Empty/><X><![CDATA[<a>&b]]></X>"
                + "<Raw><DIDL-Lite id=\"1\"/></Raw></Play></Body></Envelope>";
        check("Play".equals(Xml.actionName(variants)), "action name without prefixes");
        Map<String, String> v = Xml.args(variants, "Play");
        check("".equals(v.get("Empty")), "empty element");
        check("<a>&b".equals(v.get("X")), "CDATA unwrapped");
        check("<DIDL-Lite id=\"1\"/>".equals(v.get("Raw")), "unescaped metadata kept as text");
        check(Xml.args("<a/>", "Nope").isEmpty(), "no action, no arguments");
        check(Xml.args("<Body><Stop/></Body>", "Stop").isEmpty(), "self-closing action has no arguments");
        check(Xml.actionName("not xml") == null, "garbage has no action");

        check(Control.formatTime(0).equals("0:00:00") && Control.formatTime(3725999).equals("1:02:05"), "time format");
        check(Control.parseTime("0:01:05") == 65000 && Control.parseTime("1:00:00.500") == 3600500, "time parse");
        boolean bad = false;
        try {
            Control.parseTime("1:99:00");
        } catch (Control.UpnpError e) {
            bad = e.code == 711;
        }
        check(bad, "illegal seek target is error 711");
    }

    static void testDescriptions() throws Exception {
        Document dev = parse(Descriptions.deviceXml("11111111-2222-3333-4444-555555555555", "Test & <Speaker>", "9.9"));
        Element root = dev.getDocumentElement();
        check("urn:schemas-upnp-org:device-1-0".equals(root.getNamespaceURI()), "device namespace");
        Element device = child(root, "device");
        check(Descriptions.DEVICE_TYPE.equals(text(device, "deviceType")), "device type is MediaRenderer:1");
        check("uuid:11111111-2222-3333-4444-555555555555".equals(text(device, "UDN")), "UDN");
        check("Test & <Speaker>".equals(text(device, "friendlyName")), "friendly name is escaped and survives");
        List<Element> services = children(child(device, "serviceList"), "service");
        check(services.size() == 3, "three services");
        Set<String> ids = new HashSet<String>();
        for (int i = 0; i < services.size(); i++) {
            ids.add(text(services.get(i), "serviceId"));
            check(text(services.get(i), "SCPDURL").startsWith("/") && text(services.get(i), "controlURL").startsWith("/")
                    && text(services.get(i), "eventSubURL").startsWith("/"), "service URLs are absolute paths");
        }
        check(ids.contains("urn:upnp-org:serviceId:AVTransport") && ids.contains("urn:upnp-org:serviceId:RenderingControl")
                && ids.contains("urn:upnp-org:serviceId:ConnectionManager"), "the service ids Home Assistant looks for");

        for (int s = 0; s < Descriptions.SERVICES.length; s++) {
            Descriptions.Service svc = Descriptions.SERVICES[s];
            Element scpd = parse(svc.scpdXml()).getDocumentElement();
            check("urn:schemas-upnp-org:service-1-0".equals(scpd.getNamespaceURI()), svc.key + " scpd namespace");
            Set<String> vars = new HashSet<String>();
            for (Element v : children(child(scpd, "serviceStateTable"), "stateVariable")) {
                String name = text(v, "name");
                check(vars.add(name), svc.key + " state variable " + name + " is unique");
                check(text(v, "dataType") != null, svc.key + " " + name + " has a type");
            }
            Set<String> actions = new HashSet<String>();
            for (Element a : children(child(scpd, "actionList"), "action")) {
                String an = text(a, "name");
                check(actions.add(an), svc.key + " action " + an + " is unique");
                Element list = child(a, "argumentList");
                if (list != null) {
                    for (Element arg : children(list, "argument")) {
                        String rel = text(arg, "relatedStateVariable");
                        check(vars.contains(rel), svc.key + "." + an + " argument " + text(arg, "name") + " -> " + rel + " exists");
                        String dir = text(arg, "direction");
                        check("in".equals(dir) || "out".equals(dir), svc.key + "." + an + " direction");
                    }
                }
            }
            if (!svc.key.equals("ConnectionManager")) {
                check(vars.contains("LastChange"), svc.key + " declares LastChange");
            }
        }
        // volume range as Home Assistant reads it for volume_level
        Element rc = parse(Descriptions.RENDERING_CONTROL.scpdXml()).getDocumentElement();
        boolean range = false;
        for (Element v : children(child(rc, "serviceStateTable"), "stateVariable")) {
            if ("Volume".equals(text(v, "name"))) {
                Element r = child(v, "allowedValueRange");
                range = r != null && "0".equals(text(r, "minimum")) && "100".equals(text(r, "maximum")) && "ui2".equals(text(v, "dataType"));
            }
        }
        check(range, "Volume is ui2 with range 0..100");
    }

    static String sample(String argName) {
        if (argName.equals("InstanceID") || argName.equals("ConnectionID")) {
            return "0";
        }
        if (argName.equals("Channel")) {
            return "Master";
        }
        if (argName.equals("Speed")) {
            return "1";
        }
        if (argName.equals("Unit")) {
            return "REL_TIME";
        }
        if (argName.equals("Target")) {
            return "0:00:05";
        }
        if (argName.equals("CurrentURI")) {
            return "http://example.com/a.mp3";
        }
        if (argName.equals("DesiredVolume")) {
            return "30";
        }
        if (argName.equals("DesiredMute")) {
            return "0";
        }
        if (argName.equals("PresetName")) {
            return "FactoryDefaults";
        }
        return "";
    }

    static List<String[]> ssdpSearch(int port, String st, String man, int waitMs, int expect) throws Exception {
        DatagramSocket c = new DatagramSocket();
        try {
            c.setSoTimeout(300);
            String req = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: " + man + "\r\nMX: 1\r\nST: " + st + "\r\n\r\n";
            byte[] b = req.getBytes("ISO-8859-1");
            c.send(new DatagramPacket(b, b.length, InetAddress.getByName("127.0.0.1"), port));
            List<String[]> out = new ArrayList<String[]>();
            long end = System.currentTimeMillis() + waitMs;
            while (System.currentTimeMillis() < end && out.size() < expect) {
                DatagramPacket p = new DatagramPacket(new byte[2048], 2048);
                try {
                    c.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                String msg = new String(p.getData(), 0, p.getLength(), "ISO-8859-1");
                String loc = "", stv = "", usn = "";
                for (String line : msg.split("\r\n")) {
                    String l = line.toLowerCase();
                    if (l.startsWith("location:")) {
                        loc = line.substring(9).trim();
                    } else if (l.startsWith("st:")) {
                        stv = line.substring(3).trim();
                    } else if (l.startsWith("usn:")) {
                        usn = line.substring(4).trim();
                    }
                }
                check(msg.startsWith("HTTP/1.1 200 OK\r\n"), "search answer is a 200 OK");
                out.add(new String[] {loc, stv, usn});
            }
            return out;
        } finally {
            c.close();
        }
    }

    static void testRenderer() throws Exception {
        FakeBackend backend = new FakeBackend();
        String uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        MediaRenderer r = new MediaRenderer(uuid, "Test Speaker", "1.0", backend);
        r.start(0, 0, false);
        try {
            int httpPort = r.getHttpPort();
            int ssdpPort = r.getSsdpPort();
            check(httpPort > 0 && ssdpPort > 0, "http and ssdp are listening");
            String base = "http://127.0.0.1:" + httpPort;

            // --- discovery
            List<String[]> all = ssdpSearch(ssdpPort, "ssdp:all", "\"ssdp:discover\"", 3000, 6);
            Set<String> sts = new HashSet<String>();
            for (String[] a : all) {
                sts.add(a[1]);
                check((base + "/description.xml").equals(a[0]), "LOCATION points at the description (" + a[0] + ")");
                check(a[2].startsWith("uuid:" + uuid), "USN carries the uuid");
            }
            check(all.size() == 6, "ssdp:all gets six answers, got " + all.size());
            check(sts.contains("upnp:rootdevice") && sts.contains(Descriptions.DEVICE_TYPE)
                    && sts.contains(Descriptions.AV_TRANSPORT.type) && sts.contains("uuid:" + uuid), "all the expected search targets");
            List<String[]> one = ssdpSearch(ssdpPort, Descriptions.DEVICE_TYPE, "\"ssdp:discover\"", 1500, 3);
            check(one.size() == 1 && one.get(0)[1].equals(Descriptions.DEVICE_TYPE)
                    && one.get(0)[2].equals("uuid:" + uuid + "::" + Descriptions.DEVICE_TYPE), "targeted search gets exactly one answer");
            check(ssdpSearch(ssdpPort, "urn:schemas-upnp-org:device:MediaServer:1", "\"ssdp:discover\"", 700, 1).isEmpty(),
                    "other device types are not answered");
            check(ssdpSearch(ssdpPort, "ssdp:all", "\"something-else\"", 700, 1).isEmpty(), "wrong MAN is ignored");

            // --- description and service descriptions over HTTP
            Http d = http("GET", base + "/description.xml", null, null);
            check(d.status == 200 && d.contentType != null && d.contentType.startsWith("text/xml"), "description served as text/xml");
            Element device = child(parse(d.body).getDocumentElement(), "device");
            check("Test Speaker".equals(text(device, "friendlyName")), "served description has the friendly name");
            for (Descriptions.Service svc : Descriptions.SERVICES) {
                Http h = http("GET", base + svc.scpdPath(), null, null);
                check(h.status == 200 && h.body.equals(svc.scpdXml()), svc.key + " scpd served");
            }
            check(http("HEAD", base + "/description.xml", null, null).status == 200, "HEAD works");
            check(http("GET", base + "/nothing", null, null).status == 404, "unknown path is 404");
            check(http("POST", base + "/description.xml", "x", null).status == 405, "POST to the description is 405");
            check(http("GET", base + Descriptions.AV_TRANSPORT.controlPath(), null, null).status == 405, "GET on control is 405");
            String sub = raw(httpPort, "SUBSCRIBE /AVTransport/event HTTP/1.1\r\nHOST: 127.0.0.1\r\nCALLBACK: <http://127.0.0.1:1/x>\r\nNT: upnp:event\r\nTIMEOUT: Second-300\r\n\r\n");
            check(sub.startsWith("HTTP/1.1 501"), "SUBSCRIBE is refused so the controller polls (" + sub.split("\r\n")[0] + ")");

            // --- behaviour, the way Home Assistant uses a renderer
            Descriptions.Service av = Descriptions.AV_TRANSPORT;
            Descriptions.Service rc = Descriptions.RENDERING_CONTROL;
            Descriptions.Service cm = Descriptions.CONNECTION_MANAGER;

            Map<String, String> proto = soap(base, cm, "GetProtocolInfo");
            check(proto.get("Sink").contains("http-get:*:audio/mpeg:*") && "".equals(proto.get("Source")), "sink protocol info lists mp3");
            check("0".equals(soap(base, cm, "GetCurrentConnectionIDs").get("ConnectionIDs")), "connection ids");
            check(faultCode(base, cm, "GetCurrentConnectionInfo", "ConnectionID", "5") == 706, "unknown connection is 706");

            check("NO_MEDIA_PRESENT".equals(soap(base, av, "GetTransportInfo", "InstanceID", "0").get("CurrentTransportState")),
                    "starts without media");
            // Home Assistant runs Stop before every play_media, and since 2026.9 an error from it aborts the
            // script (the Yamaha 701 reports). Stop and Pause must never fail, even with nothing loaded.
            check(faultCode(base, av, "Stop", "InstanceID", "0") == 0, "Stop with no media succeeds");
            check(faultCode(base, av, "Pause", "InstanceID", "0") == 0, "Pause with no media succeeds");
            check("Play,Pause,Stop,Seek".equals(soap(base, av, "GetCurrentTransportActions", "InstanceID", "0").get("Actions")),
                    "all transport actions are offered even without media");
            check(faultCode(base, av, "Play", "InstanceID", "0", "Speed", "1") == 701, "play without media is 701");
            check(faultCode(base, av, "GetTransportInfo", "InstanceID", "3") == 718, "bad instance id is 718");
            check(faultCode(base, av, "SetAVTransportURI", "InstanceID", "0", "CurrentURI", "file:///sdcard/x.mp3", "CurrentURIMetaData", "") == 716,
                    "file:// addresses are refused");
            check(faultCode(base, av, "SetAVTransportURI", "InstanceID", "0") == 402, "missing argument is 402");
            check(backend.uri.length() == 0, "refused addresses never reach the player");

            String didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"><item id=\"1\"><dc:title xmlns:dc=\"http://purl.org/dc/elements/1.1/\">Tom &amp; Jerry</dc:title></item></DIDL-Lite>";
            String url = "http://homeassistant.local:8123/api/tts_proxy/abc_en-us_def.mp3?authSig=1&x=2";
            soap(base, av, "SetAVTransportURI", "InstanceID", "0", "CurrentURI", url, "CurrentURIMetaData", didl);
            check(url.equals(backend.uri), "player got the exact address, entities decoded");
            check("STOPPED".equals(soap(base, av, "GetTransportInfo", "InstanceID", "0").get("CurrentTransportState")), "stopped once loaded");
            Map<String, String> mi = soap(base, av, "GetMediaInfo", "InstanceID", "0");
            check(url.equals(mi.get("CurrentURI")) && didl.equals(mi.get("CurrentURIMetaData")), "media info returns address and metadata unchanged");
            check("1".equals(mi.get("NrTracks")) && "0:03:07".equals(mi.get("MediaDuration")), "track count and duration");

            soap(base, av, "Play", "InstanceID", "0", "Speed", "1");
            check(backend.state == MediaBackend.PLAYING, "play reaches the player");
            Map<String, String> ti = soap(base, av, "GetTransportInfo", "InstanceID", "0");
            check("PLAYING".equals(ti.get("CurrentTransportState")) && "OK".equals(ti.get("CurrentTransportStatus"))
                    && "1".equals(ti.get("CurrentSpeed")), "transport info while playing");
            check("Play,Pause,Stop,Seek".equals(soap(base, av, "GetCurrentTransportActions", "InstanceID", "0").get("Actions")),
                    "all transport actions are offered while playing");
            backend.position = 65000;
            Map<String, String> pi = soap(base, av, "GetPositionInfo", "InstanceID", "0");
            check("0:01:05".equals(pi.get("RelTime")) && "0:03:07".equals(pi.get("TrackDuration")) && url.equals(pi.get("TrackURI")),
                    "position info while playing");
            soap(base, av, "Pause", "InstanceID", "0");
            check("PAUSED_PLAYBACK".equals(soap(base, av, "GetTransportInfo", "InstanceID", "0").get("CurrentTransportState")), "paused");
            check("Play,Pause,Stop,Seek".equals(soap(base, av, "GetCurrentTransportActions", "InstanceID", "0").get("Actions")),
                    "all transport actions are offered while paused");
            soap(base, av, "Seek", "InstanceID", "0", "Unit", "REL_TIME", "Target", "0:02:10");
            check(backend.position == 130000, "seek reaches the player");
            check(faultCode(base, av, "Seek", "InstanceID", "0", "Unit", "REL_TIME", "Target", "soon") == 711, "bad seek target is 711");
            check(faultCode(base, av, "Seek", "InstanceID", "0", "Unit", "REL_COUNT", "Target", "1") == 710, "unsupported seek mode is 710");
            soap(base, av, "Play", "InstanceID", "0", "Speed", "1");
            soap(base, av, "Stop", "InstanceID", "0");
            check(backend.state == MediaBackend.STOPPED && backend.position == 0, "stop rewinds");
            check(faultCode(base, av, "Next", "InstanceID", "0") == 701 && faultCode(base, av, "Previous", "InstanceID", "0") == 701,
                    "next and previous are 701");
            check(faultCode(base, av, "NoSuchAction", "InstanceID", "0") == 401, "unknown action is 401");

            check("40".equals(soap(base, rc, "GetVolume", "InstanceID", "0", "Channel", "Master").get("CurrentVolume")), "volume read");
            soap(base, rc, "SetVolume", "InstanceID", "0", "Channel", "Master", "DesiredVolume", "73");
            check(backend.volume == 73, "volume set");
            soap(base, rc, "SetVolume", "InstanceID", "0", "Channel", "Master", "DesiredVolume", "250");
            check(backend.volume == 100, "volume is clamped to 100");
            check(faultCode(base, rc, "SetVolume", "InstanceID", "0", "Channel", "Master", "DesiredVolume", "loud") == 402, "bad volume is 402");
            soap(base, rc, "SetMute", "InstanceID", "0", "Channel", "Master", "DesiredMute", "1");
            check(backend.muted && "1".equals(soap(base, rc, "GetMute", "InstanceID", "0", "Channel", "Master").get("CurrentMute")), "mute");
            soap(base, rc, "SetMute", "InstanceID", "0", "Channel", "Master", "DesiredMute", "false");
            check(!backend.muted, "unmute with false");

            soap(base, av, "SetAVTransportURI", "InstanceID", "0", "CurrentURI", "", "CurrentURIMetaData", "");
            check(backend.state == MediaBackend.NO_MEDIA, "empty address unloads");
            check(faultCode(base, av, "Play", "InstanceID", "0", "Speed", "1") == 701, "play after unload is 701");

            // --- every action of every service, with the arguments its own description declares
            soap(base, av, "SetAVTransportURI", "InstanceID", "0", "CurrentURI", "http://example.com/a.mp3", "CurrentURIMetaData", "");
            int calls = 0;
            for (Descriptions.Service svc : Descriptions.SERVICES) {
                Element scpd = parse(http("GET", base + svc.scpdPath(), null, null).body).getDocumentElement();
                for (Element a : children(child(scpd, "actionList"), "action")) {
                    String an = text(a, "name");
                    List<String> in = new ArrayList<String>();
                    List<String> outNames = new ArrayList<String>();
                    Element list = child(a, "argumentList");
                    if (list != null) {
                        for (Element arg : children(list, "argument")) {
                            if ("in".equals(text(arg, "direction"))) {
                                in.add(text(arg, "name"));
                                in.add(sample(text(arg, "name")));
                            } else {
                                outNames.add(text(arg, "name"));
                            }
                        }
                    }
                    if (an.equals("Next") || an.equals("Previous")) {
                        continue;
                    }
                    Map<String, String> res = soap(base, svc, an, in.toArray(new String[0]));
                    calls++;
                    for (String o : outNames) {
                        check(res.containsKey(o), svc.key + "." + an + " returns " + o);
                    }
                    check(res.size() == outNames.size(), svc.key + "." + an + " returns only declared values (" + res.keySet() + ")");
                }
            }
            check(calls >= 20, "walked all actions, " + calls + " calls");

            // --- robustness
            String soapBody = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
                    + "<u:GetVolume xmlns:u=\"" + rc.type + "\"><InstanceID>0</InstanceID><Channel>Master</Channel></u:GetVolume></s:Body></s:Envelope>";
            String head = "POST /RenderingControl/control HTTP/1.1\r\nHOST: 127.0.0.1\r\nSOAPACTION: \"" + rc.type + "#GetVolume\"\r\n";
            String chunked = raw(httpPort, head + "Transfer-Encoding: chunked\r\n\r\n"
                    + Integer.toHexString(soapBody.length()) + "\r\n" + soapBody + "\r\n0\r\n\r\n");
            String expectVol = "<CurrentVolume>" + backend.volume + "</CurrentVolume>";
            check(chunked.startsWith("HTTP/1.1 200") && chunked.contains(expectVol),
                    "chunked request bodies work (" + chunked.replace("\r\n", " | ") + ")");
            String expect = raw(httpPort, head + "Expect: 100-continue\r\nContent-Length: " + soapBody.length() + "\r\n\r\n" + soapBody);
            check(expect.startsWith("HTTP/1.1 100 Continue") && expect.contains("HTTP/1.1 200"), "Expect: 100-continue is honoured");
            String noAction = raw(httpPort, "POST /RenderingControl/control HTTP/1.1\r\nHOST: x\r\nContent-Length: " + soapBody.length() + "\r\n\r\n" + soapBody);
            check(noAction.startsWith("HTTP/1.1 200"), "action found in the body when SOAPACTION is missing");
            check(raw(httpPort, "POST /RenderingControl/control HTTP/1.1\r\nContent-Length: 99999999\r\n\r\n").startsWith("HTTP/1.1 413"), "huge bodies are refused");
            StringBuilder big = new StringBuilder("GET / HTTP/1.1\r\n");
            for (int i = 0; i < 3000; i++) {
                big.append("X-Pad: aaaaaaaaaaaaaaaa\r\n");
            }
            check(raw(httpPort, big.append("\r\n").toString()).startsWith("HTTP/1.1 431"), "huge headers are refused");
            check(raw(httpPort, "\u0000\u0001garbage\r\n\r\n").startsWith("HTTP/1.1 400"), "garbage gets 400");
            check(http("GET", base + "/description.xml", null, null).status == 200, "server still fine after bad requests");

            final AtomicInteger good = new AtomicInteger();
            final String fb = base;
            final String wantVol = String.valueOf(backend.volume);
            Thread[] ts = new Thread[16];
            for (int i = 0; i < ts.length; i++) {
                ts[i] = new Thread(new Runnable() {
                    public void run() {
                        try {
                            if (wantVol.equals(soap(fb, Descriptions.RENDERING_CONTROL, "GetVolume", "InstanceID", "0", "Channel", "Master").get("CurrentVolume"))) {
                                good.incrementAndGet();
                            }
                        } catch (Exception e) {
                            // counted as missing
                        }
                    }
                });
                ts[i].start();
            }
            for (Thread t : ts) {
                t.join(10000);
            }
            check(good.get() == ts.length, "16 parallel requests all answered (" + good.get() + ")");
        } finally {
            r.stop();
        }
        Thread.sleep(200);
        boolean closed = false;
        try {
            new Socket("127.0.0.1", r.getHttpPort()).close();
        } catch (java.io.IOException e) {
            closed = true;
        }
        check(closed, "http port is closed after stop");
    }
}
