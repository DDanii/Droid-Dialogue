package org.droiddialogue.dlna;

import java.util.ArrayList;
import java.util.List;

/**
 * The device description and the three service descriptions (SCPD) of a UPnP/DLNA MediaRenderer:
 * AVTransport, RenderingControl and ConnectionManager. The services are generated from compact tables
 * so that every argument is guaranteed to refer to a declared state variable.
 */
public final class Descriptions {
    public static final String DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1";

    public static final class Arg {
        public final String name;
        public final boolean in;
        public final String variable;

        Arg(String name, boolean in, String variable) {
            this.name = name;
            this.in = in;
            this.variable = variable;
        }
    }

    public static final class Action {
        public final String name;
        public final List<Arg> args = new ArrayList<Arg>();

        Action(String name) {
            this.name = name;
        }
    }

    public static final class Var {
        public final String name;
        public final String type;
        public boolean events;
        public String[] allowed;
        public String range; // "min,max,step"
        public String def;

        Var(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }

    public static final class Service {
        /** Short key used in URLs and by the control code: AVTransport, RenderingControl, ConnectionManager. */
        public final String key;
        public final String type;
        public final String id;
        public final List<Action> actions = new ArrayList<Action>();
        public final List<Var> vars = new ArrayList<Var>();

        Service(String key, String id, String[] actionSpecs, String[] varSpecs) {
            this.key = key;
            this.type = "urn:schemas-upnp-org:service:" + key + ":1";
            this.id = id;
            for (int i = 0; i < actionSpecs.length; i++) {
                actions.add(parseAction(actionSpecs[i]));
            }
            for (int i = 0; i < varSpecs.length; i++) {
                vars.add(parseVar(varSpecs[i]));
            }
        }

        public String scpdPath() {
            return "/" + key + "/scpd.xml";
        }

        public String controlPath() {
            return "/" + key + "/control";
        }

        public String eventPath() {
            return "/" + key + "/event";
        }

        public Action action(String name) {
            for (int i = 0; i < actions.size(); i++) {
                if (actions.get(i).name.equals(name)) {
                    return actions.get(i);
                }
            }
            return null;
        }

        public String scpdXml() {
            StringBuilder sb = new StringBuilder(8192);
            sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
            sb.append("<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion>");
            sb.append("<actionList>");
            for (int i = 0; i < actions.size(); i++) {
                Action a = actions.get(i);
                sb.append("<action><name>").append(a.name).append("</name>");
                if (!a.args.isEmpty()) {
                    sb.append("<argumentList>");
                    for (int j = 0; j < a.args.size(); j++) {
                        Arg g = a.args.get(j);
                        sb.append("<argument><name>").append(g.name).append("</name><direction>")
                                .append(g.in ? "in" : "out").append("</direction><relatedStateVariable>")
                                .append(g.variable).append("</relatedStateVariable></argument>");
                    }
                    sb.append("</argumentList>");
                }
                sb.append("</action>");
            }
            sb.append("</actionList><serviceStateTable>");
            for (int i = 0; i < vars.size(); i++) {
                Var v = vars.get(i);
                sb.append("<stateVariable sendEvents=\"").append(v.events ? "yes" : "no").append("\"><name>")
                        .append(v.name).append("</name><dataType>").append(v.type).append("</dataType>");
                if (v.def != null) {
                    sb.append("<defaultValue>").append(Xml.esc(v.def)).append("</defaultValue>");
                }
                if (v.allowed != null) {
                    sb.append("<allowedValueList>");
                    for (int j = 0; j < v.allowed.length; j++) {
                        sb.append("<allowedValue>").append(Xml.esc(v.allowed[j])).append("</allowedValue>");
                    }
                    sb.append("</allowedValueList>");
                }
                if (v.range != null) {
                    String[] r = v.range.split(",");
                    sb.append("<allowedValueRange><minimum>").append(r[0]).append("</minimum><maximum>").append(r[1])
                            .append("</maximum><step>").append(r[2]).append("</step></allowedValueRange>");
                }
                sb.append("</stateVariable>");
            }
            sb.append("</serviceStateTable></scpd>");
            return sb.toString();
        }
    }

    /** "Name|in:Arg=Variable,out:Arg=Variable" */
    private static Action parseAction(String spec) {
        String[] parts = spec.split("\\|", -1);
        Action a = new Action(parts[0]);
        if (parts.length > 1 && parts[1].length() > 0) {
            String[] args = parts[1].split(",");
            for (int i = 0; i < args.length; i++) {
                int colon = args[i].indexOf(':');
                int eq = args[i].indexOf('=');
                a.args.add(new Arg(args[i].substring(colon + 1, eq), args[i].startsWith("in:"), args[i].substring(eq + 1)));
            }
        }
        return a;
    }

    /** "Name:type[:events][:allowed=a|b][:range=min,max,step][:default=x]" */
    private static Var parseVar(String spec) {
        String[] p = spec.split(":");
        Var v = new Var(p[0], p[1]);
        for (int i = 2; i < p.length; i++) {
            if (p[i].equals("events")) {
                v.events = true;
            } else if (p[i].startsWith("allowed=")) {
                v.allowed = p[i].substring(8).split("\\|");
            } else if (p[i].startsWith("range=")) {
                v.range = p[i].substring(6);
            } else if (p[i].startsWith("default=")) {
                v.def = p[i].substring(8);
            }
        }
        return v;
    }

    public static final Service AV_TRANSPORT = new Service("AVTransport", "urn:upnp-org:serviceId:AVTransport",
            new String[] {
                "SetAVTransportURI|in:InstanceID=A_ARG_TYPE_InstanceID,in:CurrentURI=AVTransportURI,in:CurrentURIMetaData=AVTransportURIMetaData",
                "GetMediaInfo|in:InstanceID=A_ARG_TYPE_InstanceID,out:NrTracks=NumberOfTracks,out:MediaDuration=CurrentMediaDuration,"
                        + "out:CurrentURI=AVTransportURI,out:CurrentURIMetaData=AVTransportURIMetaData,out:NextURI=NextAVTransportURI,"
                        + "out:NextURIMetaData=NextAVTransportURIMetaData,out:PlayMedium=PlaybackStorageMedium,"
                        + "out:RecordMedium=RecordStorageMedium,out:WriteStatus=RecordMediumWriteStatus",
                "GetTransportInfo|in:InstanceID=A_ARG_TYPE_InstanceID,out:CurrentTransportState=TransportState,"
                        + "out:CurrentTransportStatus=TransportStatus,out:CurrentSpeed=TransportPlaySpeed",
                "GetPositionInfo|in:InstanceID=A_ARG_TYPE_InstanceID,out:Track=CurrentTrack,out:TrackDuration=CurrentTrackDuration,"
                        + "out:TrackMetaData=CurrentTrackMetaData,out:TrackURI=CurrentTrackURI,out:RelTime=RelativeTimePosition,"
                        + "out:AbsTime=AbsoluteTimePosition,out:RelCount=RelativeCounterPosition,out:AbsCount=AbsoluteCounterPosition",
                "GetDeviceCapabilities|in:InstanceID=A_ARG_TYPE_InstanceID,out:PlayMedia=PossiblePlaybackStorageMedia,"
                        + "out:RecMedia=PossibleRecordStorageMedia,out:RecQualityModes=PossibleRecordQualityModes",
                "GetTransportSettings|in:InstanceID=A_ARG_TYPE_InstanceID,out:PlayMode=CurrentPlayMode,"
                        + "out:RecQualityMode=CurrentRecordQualityMode",
                "Stop|in:InstanceID=A_ARG_TYPE_InstanceID",
                "Play|in:InstanceID=A_ARG_TYPE_InstanceID,in:Speed=TransportPlaySpeed",
                "Pause|in:InstanceID=A_ARG_TYPE_InstanceID",
                "Seek|in:InstanceID=A_ARG_TYPE_InstanceID,in:Unit=A_ARG_TYPE_SeekMode,in:Target=A_ARG_TYPE_SeekTarget",
                "Next|in:InstanceID=A_ARG_TYPE_InstanceID",
                "Previous|in:InstanceID=A_ARG_TYPE_InstanceID",
                "GetCurrentTransportActions|in:InstanceID=A_ARG_TYPE_InstanceID,out:Actions=CurrentTransportActions"
            },
            new String[] {
                "LastChange:string:events",
                "TransportState:string:allowed=STOPPED|PAUSED_PLAYBACK|PLAYING|TRANSITIONING|NO_MEDIA_PRESENT",
                "TransportStatus:string:allowed=OK|ERROR_OCCURRED",
                "PlaybackStorageMedium:string",
                "RecordStorageMedium:string",
                "PossiblePlaybackStorageMedia:string",
                "PossibleRecordStorageMedia:string",
                "CurrentPlayMode:string:allowed=NORMAL:default=NORMAL",
                "TransportPlaySpeed:string:allowed=1:default=1",
                "RecordMediumWriteStatus:string",
                "CurrentRecordQualityMode:string",
                "PossibleRecordQualityModes:string",
                "NumberOfTracks:ui4:range=0,4294967295,1",
                "CurrentTrack:ui4:range=0,4294967295,1",
                "CurrentTrackDuration:string",
                "CurrentMediaDuration:string",
                "CurrentTrackMetaData:string",
                "CurrentTrackURI:string",
                "AVTransportURI:string",
                "AVTransportURIMetaData:string",
                "NextAVTransportURI:string",
                "NextAVTransportURIMetaData:string",
                "RelativeTimePosition:string",
                "AbsoluteTimePosition:string",
                "RelativeCounterPosition:i4",
                "AbsoluteCounterPosition:ui4",
                "CurrentTransportActions:string",
                "A_ARG_TYPE_SeekMode:string:allowed=REL_TIME|ABS_TIME|TRACK_NR",
                "A_ARG_TYPE_SeekTarget:string",
                "A_ARG_TYPE_InstanceID:ui4"
            });

    public static final Service RENDERING_CONTROL = new Service("RenderingControl", "urn:upnp-org:serviceId:RenderingControl",
            new String[] {
                "ListPresets|in:InstanceID=A_ARG_TYPE_InstanceID,out:CurrentPresetNameList=PresetNameList",
                "SelectPreset|in:InstanceID=A_ARG_TYPE_InstanceID,in:PresetName=A_ARG_TYPE_PresetName",
                "GetMute|in:InstanceID=A_ARG_TYPE_InstanceID,in:Channel=A_ARG_TYPE_Channel,out:CurrentMute=Mute",
                "SetMute|in:InstanceID=A_ARG_TYPE_InstanceID,in:Channel=A_ARG_TYPE_Channel,in:DesiredMute=Mute",
                "GetVolume|in:InstanceID=A_ARG_TYPE_InstanceID,in:Channel=A_ARG_TYPE_Channel,out:CurrentVolume=Volume",
                "SetVolume|in:InstanceID=A_ARG_TYPE_InstanceID,in:Channel=A_ARG_TYPE_Channel,in:DesiredVolume=Volume"
            },
            new String[] {
                "LastChange:string:events",
                "PresetNameList:string",
                "Mute:boolean",
                "Volume:ui2:range=0,100,1",
                "A_ARG_TYPE_Channel:string:allowed=Master",
                "A_ARG_TYPE_InstanceID:ui4",
                "A_ARG_TYPE_PresetName:string:allowed=FactoryDefaults"
            });

    public static final Service CONNECTION_MANAGER = new Service("ConnectionManager", "urn:upnp-org:serviceId:ConnectionManager",
            new String[] {
                "GetProtocolInfo|out:Source=SourceProtocolInfo,out:Sink=SinkProtocolInfo",
                "GetCurrentConnectionIDs|out:ConnectionIDs=CurrentConnectionIDs",
                "GetCurrentConnectionInfo|in:ConnectionID=A_ARG_TYPE_ConnectionID,out:RcsID=A_ARG_TYPE_RcsID,"
                        + "out:AVTransportID=A_ARG_TYPE_AVTransportID,out:ProtocolInfo=A_ARG_TYPE_ProtocolInfo,"
                        + "out:PeerConnectionManager=A_ARG_TYPE_ConnectionManager,out:PeerConnectionID=A_ARG_TYPE_ConnectionID,"
                        + "out:Direction=A_ARG_TYPE_Direction,out:Status=A_ARG_TYPE_ConnectionStatus"
            },
            new String[] {
                "SourceProtocolInfo:string:events",
                "SinkProtocolInfo:string:events",
                "CurrentConnectionIDs:string:events",
                "A_ARG_TYPE_ConnectionStatus:string:allowed=OK|ContentFormatMismatch|InsufficientBandwidth|UnreliableChannel|Unknown",
                "A_ARG_TYPE_ConnectionManager:string",
                "A_ARG_TYPE_Direction:string:allowed=Input|Output",
                "A_ARG_TYPE_ProtocolInfo:string",
                "A_ARG_TYPE_ConnectionID:i4",
                "A_ARG_TYPE_AVTransportID:i4",
                "A_ARG_TYPE_RcsID:i4"
            });

    public static final Service[] SERVICES = {AV_TRANSPORT, RENDERING_CONTROL, CONNECTION_MANAGER};

    private Descriptions() {
    }

    public static Service byKey(String key) {
        for (int i = 0; i < SERVICES.length; i++) {
            if (SERVICES[i].key.equals(key)) {
                return SERVICES[i];
            }
        }
        return null;
    }

    public static String deviceXml(String uuid, String friendlyName, String version) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">");
        sb.append("<specVersion><major>1</major><minor>0</minor></specVersion><device>");
        sb.append("<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>");
        sb.append("<deviceType>").append(DEVICE_TYPE).append("</deviceType>");
        sb.append("<friendlyName>").append(Xml.esc(friendlyName)).append("</friendlyName>");
        sb.append("<manufacturer>Droid Dialogue</manufacturer>");
        sb.append("<modelDescription>Speaker of an old Android phone</modelDescription>");
        sb.append("<modelName>Droid Dialogue Satellite</modelName>");
        sb.append("<modelNumber>").append(Xml.esc(version)).append("</modelNumber>");
        sb.append("<UDN>uuid:").append(uuid).append("</UDN>");
        sb.append("<serviceList>");
        for (int i = 0; i < SERVICES.length; i++) {
            Service s = SERVICES[i];
            sb.append("<service><serviceType>").append(s.type).append("</serviceType><serviceId>").append(s.id)
                    .append("</serviceId><SCPDURL>").append(s.scpdPath()).append("</SCPDURL><controlURL>")
                    .append(s.controlPath()).append("</controlURL><eventSubURL>").append(s.eventPath())
                    .append("</eventSubURL></service>");
        }
        sb.append("</serviceList></device></root>");
        return sb.toString();
    }
}
