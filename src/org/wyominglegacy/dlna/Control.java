package org.wyominglegacy.dlna;

import org.wyominglegacy.core.Dbg;

import java.util.Locale;
import java.util.Map;

/**
 * The actions of the three services of a MediaRenderer, mapped onto a {@link MediaBackend}.
 * Input and output are plain maps and name/value pairs; the XML and HTTP parts live elsewhere.
 */
public final class Control {
    /** A UPnP error, sent to the control point as a SOAP fault with this code. */
    public static final class UpnpError extends RuntimeException {
        public final int code;

        public UpnpError(int code, String description) {
            super(description);
            this.code = code;
        }
    }

    public static final String SINK_PROTOCOLS =
            "http-get:*:audio/mpeg:*,http-get:*:audio/x-mpeg:*,http-get:*:audio/mp3:*,"
                    + "http-get:*:audio/wav:*,http-get:*:audio/x-wav:*,http-get:*:audio/mp4:*,"
                    + "http-get:*:audio/aac:*,http-get:*:audio/ogg:*,http-get:*:audio/flac:*";

    private static final String[][] NONE = new String[0][];

    private final MediaBackend backend;
    private volatile String uri = "";
    private volatile String metadata = "";

    public Control(MediaBackend backend) {
        this.backend = backend;
    }

    public String currentUri() {
        return uri;
    }

    /** @return the output arguments in order, possibly none. */
    public String[][] invoke(String service, String action, Map<String, String> a) {
        Dbg.d("UPnP " + service + "." + action + (action.equals("SetAVTransportURI") ? " " + a.get("CurrentURI") : ""));
        if ("AVTransport".equals(service)) {
            checkInstance(a);
            return avTransport(action, a);
        }
        if ("RenderingControl".equals(service)) {
            checkInstance(a);
            return rendering(action, a);
        }
        if ("ConnectionManager".equals(service)) {
            return connection(action, a);
        }
        throw new UpnpError(401, "Invalid Action");
    }

    private static void checkInstance(Map<String, String> a) {
        String id = a.get("InstanceID");
        if (id != null && !id.trim().equals("0")) {
            throw new UpnpError(718, "Invalid InstanceID");
        }
    }

    // ------------------------------------------------------------------ AVTransport

    private String[][] avTransport(String action, Map<String, String> a) {
        boolean hasMedia = uri.length() > 0;
        if (action.equals("SetAVTransportURI")) {
            String u = a.get("CurrentURI");
            if (u == null || u.length() > 4096) {
                throw new UpnpError(402, "Invalid Args");
            }
            u = u.trim();
            String lower = u.toLowerCase(Locale.US);
            if (u.length() > 0 && !lower.startsWith("http://") && !lower.startsWith("https://")) {
                throw new UpnpError(716, "Resource not found");
            }
            String md = a.get("CurrentURIMetaData");
            metadata = md == null ? "" : md;
            uri = u;
            try {
                backend.setUri(u);
            } catch (RuntimeException e) {
                throw new UpnpError(501, "Action Failed: " + e.getMessage());
            }
            return NONE;
        }
        if (action.equals("Play")) {
            if (!hasMedia) {
                throw new UpnpError(701, "Transition not available");
            }
            try {
                backend.play();
            } catch (IllegalStateException e) {
                throw new UpnpError(701, "Transition not available");
            } catch (RuntimeException e) {
                throw new UpnpError(501, "Action Failed: " + e.getMessage());
            }
            return NONE;
        }
        if (action.equals("Pause")) {
            try {
                backend.pause();
            } catch (RuntimeException e) {
                throw new UpnpError(501, "Action Failed: " + e.getMessage());
            }
            return NONE;
        }
        if (action.equals("Stop")) {
            try {
                backend.stop();
            } catch (RuntimeException e) {
                throw new UpnpError(501, "Action Failed: " + e.getMessage());
            }
            return NONE;
        }
        if (action.equals("Seek")) {
            String unit = a.get("Unit");
            String target = a.get("Target");
            if (unit == null || target == null) {
                throw new UpnpError(402, "Invalid Args");
            }
            long ms;
            if (unit.equals("TRACK_NR")) {
                if (!target.trim().equals("1")) {
                    throw new UpnpError(711, "Illegal seek target");
                }
                ms = 0;
            } else if (unit.equals("REL_TIME") || unit.equals("ABS_TIME")) {
                ms = parseTime(target);
            } else {
                throw new UpnpError(710, "Seek mode not supported");
            }
            if (!hasMedia) {
                throw new UpnpError(701, "Transition not available");
            }
            backend.seek(ms);
            return NONE;
        }
        if (action.equals("Next") || action.equals("Previous")) {
            throw new UpnpError(701, "Transition not available");
        }
        if (action.equals("GetTransportInfo")) {
            return new String[][] {
                {"CurrentTransportState", stateName(backend.getState())},
                {"CurrentTransportStatus", "OK"},
                {"CurrentSpeed", "1"}};
        }
        if (action.equals("GetPositionInfo")) {
            String pos = formatTime(backend.getPositionMs());
            return new String[][] {
                {"Track", hasMedia ? "1" : "0"},
                {"TrackDuration", formatTime(backend.getDurationMs())},
                {"TrackMetaData", metadata},
                {"TrackURI", uri},
                {"RelTime", pos},
                {"AbsTime", pos},
                {"RelCount", "2147483647"},
                {"AbsCount", "2147483647"}};
        }
        if (action.equals("GetMediaInfo")) {
            return new String[][] {
                {"NrTracks", hasMedia ? "1" : "0"},
                {"MediaDuration", formatTime(backend.getDurationMs())},
                {"CurrentURI", uri},
                {"CurrentURIMetaData", metadata},
                {"NextURI", ""},
                {"NextURIMetaData", ""},
                {"PlayMedium", hasMedia ? "NETWORK" : "NONE"},
                {"RecordMedium", "NOT_IMPLEMENTED"},
                {"WriteStatus", "NOT_IMPLEMENTED"}};
        }
        if (action.equals("GetDeviceCapabilities")) {
            return new String[][] {
                {"PlayMedia", "NETWORK"},
                {"RecMedia", "NOT_IMPLEMENTED"},
                {"RecQualityModes", "NOT_IMPLEMENTED"}};
        }
        if (action.equals("GetTransportSettings")) {
            return new String[][] {
                {"PlayMode", "NORMAL"},
                {"RecQualityMode", "NOT_IMPLEMENTED"}};
        }
        if (action.equals("GetCurrentTransportActions")) {
            // Always everything, whatever the state. Home Assistant reads this list by polling (we do not send
            // events) and waits for "Play" to appear before it starts a stream. A list that follows the state
            // would be stale at that moment and make every play_media wait for its timeout. Pause, Stop and
            // Play never fail in a wrong state here, so offering them always is safe.
            return new String[][] {{"Actions", "Play,Pause,Stop,Seek"}};
        }
        throw new UpnpError(401, "Invalid Action");
    }

    static String stateName(int state) {
        switch (state) {
            case MediaBackend.PLAYING:
                return "PLAYING";
            case MediaBackend.PAUSED:
                return "PAUSED_PLAYBACK";
            case MediaBackend.LOADING:
                return "TRANSITIONING";
            case MediaBackend.NO_MEDIA:
                return "NO_MEDIA_PRESENT";
            default:
                return "STOPPED";
        }
    }

    // ------------------------------------------------------------------ RenderingControl

    private String[][] rendering(String action, Map<String, String> a) {
        if (action.equals("GetVolume")) {
            return new String[][] {{"CurrentVolume", String.valueOf(backend.getVolume())}};
        }
        if (action.equals("SetVolume")) {
            String v = a.get("DesiredVolume");
            int vol;
            try {
                vol = Integer.parseInt(v == null ? "" : v.trim());
            } catch (NumberFormatException e) {
                throw new UpnpError(402, "Invalid Args");
            }
            backend.setVolume(Math.max(0, Math.min(100, vol)));
            return NONE;
        }
        if (action.equals("GetMute")) {
            return new String[][] {{"CurrentMute", backend.isMuted() ? "1" : "0"}};
        }
        if (action.equals("SetMute")) {
            String v = a.get("DesiredMute");
            if (v == null) {
                throw new UpnpError(402, "Invalid Args");
            }
            String t = v.trim().toLowerCase(Locale.US);
            backend.setMuted(t.equals("1") || t.equals("true") || t.equals("yes"));
            return NONE;
        }
        if (action.equals("ListPresets")) {
            return new String[][] {{"CurrentPresetNameList", "FactoryDefaults"}};
        }
        if (action.equals("SelectPreset")) {
            return NONE;
        }
        throw new UpnpError(401, "Invalid Action");
    }

    // ------------------------------------------------------------------ ConnectionManager

    private String[][] connection(String action, Map<String, String> a) {
        if (action.equals("GetProtocolInfo")) {
            return new String[][] {{"Source", ""}, {"Sink", SINK_PROTOCOLS}};
        }
        if (action.equals("GetCurrentConnectionIDs")) {
            return new String[][] {{"ConnectionIDs", "0"}};
        }
        if (action.equals("GetCurrentConnectionInfo")) {
            String id = a.get("ConnectionID");
            if (id != null && !id.trim().equals("0")) {
                throw new UpnpError(706, "Invalid connection reference");
            }
            return new String[][] {
                {"RcsID", "0"},
                {"AVTransportID", "0"},
                {"ProtocolInfo", ""},
                {"PeerConnectionManager", ""},
                {"PeerConnectionID", "-1"},
                {"Direction", "Input"},
                {"Status", "OK"}};
        }
        throw new UpnpError(401, "Invalid Action");
    }

    // ------------------------------------------------------------------ time

    /** H:MM:SS as UPnP wants it. */
    public static String formatTime(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    /** H:MM:SS or H:MM:SS.fff to milliseconds; anything else is an "illegal seek target". */
    public static long parseTime(String t) {
        String[] p = t.trim().split(":");
        if (p.length != 3) {
            throw new UpnpError(711, "Illegal seek target");
        }
        try {
            long h = Long.parseLong(p[0].replace("+", ""));
            long m = Long.parseLong(p[1]);
            double s = Double.parseDouble(p[2]);
            if (h < 0 || m < 0 || m > 59 || s < 0 || s >= 60) {
                throw new UpnpError(711, "Illegal seek target");
            }
            return (h * 3600 + m * 60) * 1000 + Math.round(s * 1000);
        } catch (NumberFormatException e) {
            throw new UpnpError(711, "Illegal seek target");
        }
    }
}
