package org.droiddialogue.dlna;

import org.droiddialogue.core.Dbg;

import java.io.IOException;
import java.util.Map;

/**
 * A UPnP/DLNA MediaRenderer ("DMR"): this is what makes the phone show up in Home Assistant as a
 * media player, through its built-in "DLNA Digital Media Renderer" integration. HTTP serves the
 * description and takes the SOAP control calls, SSDP announces the device.
 *
 * Event subscriptions are deliberately refused (HTTP 501). Home Assistant treats that as "this device
 * has no events" and polls the state instead, which is simpler and sturdier than pushing NOTIFY
 * messages from a phone.
 */
public final class MediaRenderer {
    public static final String SERVER_NAME = "Android/4 UPnP/1.0 DroidDialogue/1.0";

    private final String uuid;
    private final String friendlyName;
    private final String version;
    private final Control control;
    private final String deviceXml;
    private final HttpServer http;
    private volatile Ssdp ssdp;
    private volatile int httpPort = -1;
    private volatile int ssdpBoundPort = -1;
    private volatile String discoveryError = "";

    public MediaRenderer(String uuid, String friendlyName, String version, MediaBackend backend) {
        this.uuid = uuid;
        this.friendlyName = friendlyName;
        this.version = version;
        this.control = new Control(backend);
        this.deviceXml = Descriptions.deviceXml(uuid, friendlyName, version);
        this.http = new HttpServer(new HttpServer.Handler() {
            public HttpServer.Response handle(HttpServer.Request r) {
                return route(r);
            }
        }, SERVER_NAME);
    }

    /**
     * @param httpPort  fixed port for the description and control URLs (a busy port falls back to any free one)
     * @param ssdpPort  1900 in real use, 0 in tests
     * @param multicast false in tests
     */
    public void start(int httpPort, int ssdpPort, boolean multicast) throws IOException {
        int p;
        try {
            p = http.start(httpPort);
        } catch (IOException e) {
            if (httpPort == 0) {
                throw e;
            }
            Dbg.d("UPnP: port " + httpPort + " is busy, using another one");
            p = http.start(0);
        }
        this.httpPort = p;
        Ssdp s = new Ssdp(uuid, p, ssdpPort, multicast, SERVER_NAME);
        try {
            ssdpBoundPort = s.start();
            ssdp = s;
        } catch (IOException e) {
            // Without SSDP Home Assistant cannot find the speaker by itself, but adding it by address works.
            discoveryError = String.valueOf(e.getMessage());
            Dbg.d("UPnP: discovery (SSDP) is not available: " + e);
            s.stop();
        }
    }

    public void stop() {
        Ssdp s = ssdp;
        ssdp = null;
        if (s != null) {
            s.stop();
        }
        http.stop();
    }

    public int getHttpPort() {
        return httpPort;
    }

    /** The port SSDP listens on, or -1 if discovery could not start. */
    public int getSsdpPort() {
        return ssdpBoundPort;
    }

    /** Empty when discovery works, otherwise why it does not. */
    public String getDiscoveryError() {
        return discoveryError;
    }

    public String getFriendlyName() {
        return friendlyName;
    }

    public String getVersion() {
        return version;
    }

    public Control getControl() {
        return control;
    }

    // ------------------------------------------------------------------ routing

    private HttpServer.Response route(HttpServer.Request r) {
        String path = r.path;
        boolean read = r.method.equals("GET") || r.method.equals("HEAD");
        if (path.equals("/description.xml")) {
            return read ? xml(deviceXml) : notAllowed();
        }
        for (int i = 0; i < Descriptions.SERVICES.length; i++) {
            Descriptions.Service s = Descriptions.SERVICES[i];
            if (path.equals(s.scpdPath())) {
                return read ? xml(s.scpdXml()) : notAllowed();
            }
            if (path.equals(s.controlPath())) {
                return r.method.equals("POST") ? soap(s, r) : notAllowed();
            }
            if (path.equals(s.eventPath())) {
                return HttpServer.Response.text(501, "text/plain", "Events are not supported, please poll");
            }
        }
        return HttpServer.Response.text(404, "text/plain", "Not Found");
    }

    private static HttpServer.Response xml(String body) {
        return HttpServer.Response.text(200, "text/xml; charset=\"utf-8\"", body);
    }

    private static HttpServer.Response notAllowed() {
        return HttpServer.Response.text(405, "text/plain", "Method Not Allowed");
    }

    private HttpServer.Response soap(Descriptions.Service svc, HttpServer.Request r) {
        String text = new String(r.body, HttpServer.UTF8);
        String action = actionFromHeader(r.headers.get("soapaction"));
        if (action == null) {
            action = Xml.actionName(text);
        }
        if (action == null || action.length() == 0) {
            return fault(401, "Invalid Action");
        }
        Map<String, String> args = Xml.args(text, action);
        try {
            String[][] out = control.invoke(svc.key, action, args);
            return HttpServer.Response.text(200, "text/xml; charset=\"utf-8\"", Xml.envelope(svc.type, action, out));
        } catch (Control.UpnpError e) {
            return fault(e.code, e.getMessage());
        } catch (RuntimeException e) {
            Dbg.d("UPnP " + action + " failed: " + e);
            return fault(501, "Action Failed");
        }
    }

    /** SOAPACTION: "urn:schemas-upnp-org:service:AVTransport:1#Play" gives Play. */
    private static String actionFromHeader(String h) {
        if (h == null) {
            return null;
        }
        String t = h.trim().replace("\"", "");
        int hash = t.lastIndexOf('#');
        return hash >= 0 && hash + 1 < t.length() ? t.substring(hash + 1) : null;
    }

    private static HttpServer.Response fault(int code, String description) {
        return HttpServer.Response.text(500, "text/xml; charset=\"utf-8\"", Xml.fault(code, description));
    }
}
