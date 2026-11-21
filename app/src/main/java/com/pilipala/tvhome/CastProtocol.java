// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import java.io.*;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Dependency-free, pure-JVM DLNA / UPnP protocol parser, builder, and security policy.
 * Enforces strict Host / Origin / Content-Type / SOAPAction validation to block browser
 * CSRF and DNS rebinding while preserving compatibility with standard UPnP control points.
 */
final class CastProtocol {
    static final String SSDP_ADDRESS = "239.255.255.250";
    static final int SSDP_PORT = 1900;
    static final int MAX_HEADER_BYTES = 8192;
    static final int MAX_SOAP_BODY_BYTES = 65536;

    static final String DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1";
    static final String SERVICE_AVTRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1";
    static final String SERVICE_RENDERING = "urn:schemas-upnp-org:service:RenderingControl:1";
    static final String SERVICE_CONNECTION = "urn:schemas-upnp-org:service:ConnectionManager:1";

    private static final Set<String> AVTRANSPORT_ACTIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "SetAVTransportURI", "SetNextAVTransportURI", "Play", "Pause", "Stop", "Seek",
            "GetPositionInfo", "GetTransportInfo", "GetMediaInfo", "GetCurrentTransportActions",
            "GetDeviceCapabilities", "GetTransportSettings"
    )));
    private static final Set<String> RENDERING_ACTIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "GetVolume", "SetVolume", "GetMute", "SetMute", "ListPresets", "SelectPreset"
    )));
    private static final Set<String> CONNECTION_ACTIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "GetProtocolInfo", "GetCurrentConnectionIDs", "GetCurrentConnectionInfo"
    )));
    private static final Set<String> CRITICAL_SINGLE_HEADERS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "host", "content-length", "transfer-encoding", "expect", "origin", "content-type", "soapaction"
    )));

    static final String AVTRANSPORT_SCPD = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">"
            + "<specVersion><major>1</major><minor>0</minor></specVersion>"
            + "<actionList>"
            + "<action><name>SetAVTransportURI</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>CurrentURI</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>"
            + "<argument><name>CurrentURIMetaData</name><direction>in</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>"
            + "</argumentList></action>"
            + "<action><name>SetNextAVTransportURI</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>NextURI</name><direction>in</direction><relatedStateVariable>NextAVTransportURI</relatedStateVariable></argument>"
            + "<argument><name>NextURIMetaData</name><direction>in</direction><relatedStateVariable>NextAVTransportURIMetaData</relatedStateVariable></argument>"
            + "</argumentList></action>"
            + "<action><name>GetMediaInfo</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>NrTracks</name><direction>out</direction><relatedStateVariable>NumberOfTracks</relatedStateVariable></argument>"
            + "<argument><name>MediaDuration</name><direction>out</direction><relatedStateVariable>CurrentMediaDuration</relatedStateVariable></argument>"
            + "<argument><name>CurrentURI</name><direction>out</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>"
            + "<argument><name>CurrentURIMetaData</name><direction>out</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>"
            + "<argument><name>NextURI</name><direction>out</direction><relatedStateVariable>NextAVTransportURI</relatedStateVariable></argument>"
            + "<argument><name>NextURIMetaData</name><direction>out</direction><relatedStateVariable>NextAVTransportURIMetaData</relatedStateVariable></argument>"
            + "<argument><name>PlayMedium</name><direction>out</direction><relatedStateVariable>PlaybackStorageMedium</relatedStateVariable></argument>"
            + "<argument><name>RecordMedium</name><direction>out</direction><relatedStateVariable>RecordStorageMedium</relatedStateVariable></argument>"
            + "<argument><name>WriteStatus</name><direction>out</direction><relatedStateVariable>RecordMediumWriteStatus</relatedStateVariable></argument>"
            + "</argumentList></action>"
            + "<action><name>GetTransportInfo</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>CurrentTransportState</name><direction>out</direction><relatedStateVariable>TransportState</relatedStateVariable></argument>"
            + "<argument><name>CurrentTransportStatus</name><direction>out</direction><relatedStateVariable>TransportStatus</relatedStateVariable></argument>"
            + "<argument><name>CurrentSpeed</name><direction>out</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>"
            + "</argumentList></action>"
            + "<action><name>GetPositionInfo</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>Track</name><direction>out</direction><relatedStateVariable>CurrentTrack</relatedStateVariable></argument>"
            + "<argument><name>TrackDuration</name><direction>out</direction><relatedStateVariable>CurrentTrackDuration</relatedStateVariable></argument>"
            + "<argument><name>TrackMetaData</name><direction>out</direction><relatedStateVariable>CurrentTrackMetaData</relatedStateVariable></argument>"
            + "<argument><name>TrackURI</name><direction>out</direction><relatedStateVariable>CurrentTrackURI</relatedStateVariable></argument>"
            + "<argument><name>RelTime</name><direction>out</direction><relatedStateVariable>RelativeTimePosition</relatedStateVariable></argument>"
            + "<argument><name>AbsTime</name><direction>out</direction><relatedStateVariable>AbsoluteTimePosition</relatedStateVariable></argument>"
            + "<argument><name>RelCount</name><direction>out</direction><relatedStateVariable>RelativeCounterPosition</relatedStateVariable></argument>"
            + "<argument><name>AbsCount</name><direction>out</direction><relatedStateVariable>AbsoluteCounterPosition</relatedStateVariable></argument>"
            + "</argumentList></action>"
            + "<action><name>GetDeviceCapabilities</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>PlayMedia</name><direction>out</direction><relatedStateVariable>PossiblePlaybackStorageMedia</relatedStateVariable></argument>"
            + "<argument><name>RecMedia</name><direction>out</direction><relatedStateVariable>PossibleRecordStorageMedia</relatedStateVariable></argument>"
            + "<argument><name>RecQualityModes</name><direction>out</direction><relatedStateVariable>PossibleRecordQualityModes</relatedStateVariable></argument>"
            + "</argumentList></action>"
            + "<action><name>GetTransportSettings</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>PlayMode</name><direction>out</direction><relatedStateVariable>CurrentPlayMode</relatedStateVariable></argument>"
            + "<argument><name>RecQualityMode</name><direction>out</direction><relatedStateVariable>CurrentRecordQualityMode</relatedStateVariable></argument>"
            + "</argumentList></action>"
            + "<action><name>Stop</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "</argumentList></action>"
            + "<action><name>Play</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>Speed</name><direction>in</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>"
            + "</argumentList></action>"
            + "<action><name>Pause</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "</argumentList></action>"
            + "<action><name>Seek</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>Unit</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekMode</relatedStateVariable></argument>"
            + "<argument><name>Target</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekTarget</relatedStateVariable></argument>"
            + "</argumentList></action>"
            + "<action><name>GetCurrentTransportActions</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>Actions</name><direction>out</direction><relatedStateVariable>CurrentTransportActions</relatedStateVariable></argument>"
            + "</argumentList></action>"
            + "</actionList><serviceStateTable>"
            + "<stateVariable sendEvents=\"yes\"><name>LastChange</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>TransportState</name><dataType>string</dataType><allowedValueList><allowedValue>STOPPED</allowedValue><allowedValue>PAUSED_PLAYBACK</allowedValue><allowedValue>PLAYING</allowedValue><allowedValue>TRANSITIONING</allowedValue><allowedValue>NO_MEDIA_PRESENT</allowedValue></allowedValueList></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>TransportStatus</name><dataType>string</dataType><allowedValueList><allowedValue>OK</allowedValue><allowedValue>ERROR_OCCURRED</allowedValue></allowedValueList></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>PlaybackStorageMedium</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>RecordStorageMedium</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>PossiblePlaybackStorageMedia</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>PossibleRecordStorageMedia</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>CurrentPlayMode</name><dataType>string</dataType><defaultValue>NORMAL</defaultValue></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>TransportPlaySpeed</name><dataType>string</dataType><allowedValueList><allowedValue>1</allowedValue></allowedValueList><defaultValue>1</defaultValue></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>RecordMediumWriteStatus</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>CurrentRecordQualityMode</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>PossibleRecordQualityModes</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>NumberOfTracks</name><dataType>ui4</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>CurrentTrack</name><dataType>ui4</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>CurrentTrackDuration</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>CurrentMediaDuration</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>CurrentTrackMetaData</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>CurrentTrackURI</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>AVTransportURI</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>AVTransportURIMetaData</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>NextAVTransportURI</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>NextAVTransportURIMetaData</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>RelativeTimePosition</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>AbsoluteTimePosition</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>RelativeCounterPosition</name><dataType>i4</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>AbsoluteCounterPosition</name><dataType>i4</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>CurrentTransportActions</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_SeekMode</name><dataType>string</dataType><allowedValueList><allowedValue>REL_TIME</allowedValue><allowedValue>ABS_TIME</allowedValue><allowedValue>TRACK_NR</allowedValue></allowedValueList></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_SeekTarget</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>"
            + "</serviceStateTable></scpd>";

    static final String RENDERING_SCPD = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">"
            + "<specVersion><major>1</major><minor>0</minor></specVersion>"
            + "<actionList>"
            + "<action><name>ListPresets</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>CurrentPresetNameList</name><direction>out</direction><relatedStateVariable>PresetNameList</relatedStateVariable></argument>"
            + "</argumentList></action>"
            + "<action><name>SelectPreset</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>PresetName</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_PresetName</relatedStateVariable></argument>"
            + "</argumentList></action>"
            + "<action><name>GetMute</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>"
            + "<argument><name>CurrentMute</name><direction>out</direction><relatedStateVariable>Mute</relatedStateVariable></argument>"
            + "</argumentList></action>"
            + "<action><name>SetMute</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>"
            + "<argument><name>DesiredMute</name><direction>in</direction><relatedStateVariable>Mute</relatedStateVariable></argument>"
            + "</argumentList></action>"
            + "<action><name>GetVolume</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>"
            + "<argument><name>CurrentVolume</name><direction>out</direction><relatedStateVariable>Volume</relatedStateVariable></argument>"
            + "</argumentList></action>"
            + "<action><name>SetVolume</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>"
            + "<argument><name>DesiredVolume</name><direction>in</direction><relatedStateVariable>Volume</relatedStateVariable></argument>"
            + "</argumentList></action>"
            + "</actionList><serviceStateTable>"
            + "<stateVariable sendEvents=\"yes\"><name>LastChange</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>PresetNameList</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>Mute</name><dataType>boolean</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>Volume</name><dataType>ui2</dataType><allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_Channel</name><dataType>string</dataType><allowedValueList><allowedValue>Master</allowedValue></allowedValueList></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_PresetName</name><dataType>string</dataType><allowedValueList><allowedValue>FactoryDefaults</allowedValue></allowedValueList></stateVariable>"
            + "</serviceStateTable></scpd>";

    static final String CONNECTION_SCPD = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">"
            + "<specVersion><major>1</major><minor>0</minor></specVersion>"
            + "<actionList>"
            + "<action><name>GetProtocolInfo</name><argumentList>"
            + "<argument><name>Source</name><direction>out</direction><relatedStateVariable>SourceProtocolInfo</relatedStateVariable></argument>"
            + "<argument><name>Sink</name><direction>out</direction><relatedStateVariable>SinkProtocolInfo</relatedStateVariable></argument>"
            + "</argumentList></action>"
            + "<action><name>GetCurrentConnectionIDs</name><argumentList>"
            + "<argument><name>ConnectionIDs</name><direction>out</direction><relatedStateVariable>CurrentConnectionIDs</relatedStateVariable></argument>"
            + "</argumentList></action>"
            + "<action><name>GetCurrentConnectionInfo</name><argumentList>"
            + "<argument><name>ConnectionID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>"
            + "<argument><name>RcsID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_RcsID</relatedStateVariable></argument>"
            + "<argument><name>AVTransportID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_AVTransportID</relatedStateVariable></argument>"
            + "<argument><name>ProtocolInfo</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ProtocolInfo</relatedStateVariable></argument>"
            + "<argument><name>PeerConnectionManager</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionManager</relatedStateVariable></argument>"
            + "<argument><name>PeerConnectionID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>"
            + "<argument><name>Direction</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Direction</relatedStateVariable></argument>"
            + "<argument><name>Status</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionStatus</relatedStateVariable></argument>"
            + "</argumentList></action>"
            + "</actionList><serviceStateTable>"
            + "<stateVariable sendEvents=\"yes\"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"yes\"><name>SinkProtocolInfo</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"yes\"><name>CurrentConnectionIDs</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_ConnectionStatus</name><dataType>string</dataType><allowedValueList><allowedValue>OK</allowedValue><allowedValue>ContentFormatMismatch</allowedValue><allowedValue>InsufficientBandwidth</allowedValue><allowedValue>UnreliableChannel</allowedValue><allowedValue>Unknown</allowedValue></allowedValueList></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_ConnectionManager</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_Direction</name><dataType>string</dataType><allowedValueList><allowedValue>Input</allowedValue><allowedValue>Output</allowedValue></allowedValueList></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_ProtocolInfo</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_ConnectionID</name><dataType>i4</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_AVTransportID</name><dataType>i4</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_RcsID</name><dataType>i4</dataType></stateVariable>"
            + "</serviceStateTable></scpd>";

    private CastProtocol() {}

    static final class HttpHead {
        final String method;
        final String path;
        final String version;
        final Map<String, String> headers;
        final int contentLength;

        HttpHead(String method, String path, String version, Map<String, String> headers, int contentLength) {
            this.method = method;
            this.path = path;
            this.version = version;
            this.headers = headers;
            this.contentLength = contentLength;
        }

        String header(String lowerName) {
            String v = headers.get(lowerName);
            return v == null ? "" : v;
        }
    }

    static HttpHead readHttpHead(InputStream in, long deadlineMs) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            if (System.currentTimeMillis() > deadlineMs) throw new SocketTimeoutException("header deadline");
            int b = in.read();
            if (b < 0) throw new EOFException("eof in header");
            if (buf.size() >= MAX_HEADER_BYTES) throw new IOException("header limit");
            buf.write(b);
            state = (state == 0 && b == '\r') ? 1
                    : (state == 1 && b == '\n') ? 2
                    : (state == 2 && b == '\r') ? 3
                    : (state == 3 && b == '\n') ? 4
                    : (b == '\r') ? 1 : 0;
        }
        String raw = new String(buf.toByteArray(), StandardCharsets.US_ASCII);
        String[] lines = raw.split("\r\n");
        if (lines.length == 0 || lines[0].isEmpty()) throw new IOException("empty request");
        String[] start = lines[0].split(" ");
        if (start.length != 3 || !(start[2].equals("HTTP/1.1") || start[2].equals("HTTP/1.0"))) {
            throw new IOException("bad request line");
        }
        String method = start[0].toUpperCase(Locale.ROOT);
        String target = start[1];
        if (!target.startsWith("/") || target.startsWith("//") || target.contains("#")) {
            throw new IOException("bad request target");
        }
        int q = target.indexOf('?');
        String path = q >= 0 ? target.substring(0, q) : target;

        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon < 1 || Character.isWhitespace(line.charAt(0))) throw new IOException("malformed header");
            String key = line.substring(0, colon).toLowerCase(Locale.ROOT);
            String val = line.substring(colon + 1).trim();
            if (headers.containsKey(key)) {
                if (CRITICAL_SINGLE_HEADERS.contains(key)) throw new IOException("duplicate critical header");
                continue;
            }
            headers.put(key, val);
        }
        if (headers.containsKey("transfer-encoding") || headers.containsKey("expect")) {
            throw new IOException("unsupported framing");
        }
        int contentLength = 0;
        String lenRaw = headers.get("content-length");
        if (lenRaw != null && !lenRaw.isEmpty()) {
            if (!lenRaw.matches("[0-9]{1,9}")) throw new IOException("invalid content-length");
            long parsed = Long.parseLong(lenRaw);
            if (parsed < 0 || parsed > Integer.MAX_VALUE) throw new IOException("invalid content-length");
            contentLength = (int) parsed;
        } else if ("POST".equals(method)) {
            throw new IOException("missing content-length");
        }
        return new HttpHead(method, path, start[2], headers, contentLength);
    }

    /**
     * Validates HTTP method, Host (DNS rebinding defense), Origin (browser CSRF defense),
     * path, Content-Type, Content-Length, and SOAPAction header.
     * Returns 0 when valid, or an HTTP error status code (400, 403, 404, 405, 413, 415).
     */
    static int checkHttpSecurity(HttpHead head, String boundIp, int boundPort) {
        if (head == null || boundIp == null || boundIp.isEmpty() || boundPort <= 0) return 400;
        String m = head.method;
        if (!("GET".equals(m) || "HEAD".equals(m) || "POST".equals(m) || "SUBSCRIBE".equals(m) || "UNSUBSCRIBE".equals(m))) {
            return 405;
        }
        if (!isValidHost(head.header("host"), boundIp, boundPort)) {
            return 403;
        }
        String origin = head.header("origin");
        if (!origin.isEmpty() && !origin.equals("http://" + boundIp + ":" + boundPort)) {
            return 403;
        }
        if ("GET".equals(m) || "HEAD".equals(m)) {
            if (head.contentLength != 0) return 400;
            if (head.path.equals("/")
                    || head.path.equals("/dlna/device.xml")
                    || head.path.equals("/dlna/avtransport.xml")
                    || head.path.equals("/dlna/rendering.xml")
                    || head.path.equals("/dlna/connection.xml")) {
                return 0;
            }
            return 404;
        }
        if ("SUBSCRIBE".equals(m) || "UNSUBSCRIBE".equals(m)) {
            if (head.contentLength != 0) return 400;
            if (head.path.equals("/dlna/avtransport/event")
                    || head.path.equals("/dlna/rendering/event")
                    || head.path.equals("/dlna/connection/event")) {
                return 0;
            }
            return 404;
        }
        // POST control action
        if (!(head.path.equals("/dlna/avtransport/control")
                || head.path.equals("/dlna/rendering/control")
                || head.path.equals("/dlna/connection/control"))) {
            return 404;
        }
        if (!isXmlContentType(head.header("content-type"))) {
            return 415;
        }
        if (head.contentLength <= 0) return 400;
        if (head.contentLength > MAX_SOAP_BODY_BYTES) return 413;
        if (parseSoapAction(head.header("soapaction"), head.path).isEmpty()) {
            return 403;
        }
        return 0;
    }

    static boolean isValidHost(String hostHeader, String boundIp, int boundPort) {
        if (hostHeader == null || hostHeader.isEmpty() || boundIp == null || boundIp.isEmpty()) return false;
        String h = hostHeader.trim();
        return h.equals(boundIp + ":" + boundPort) || h.equals(boundIp);
    }

    static boolean isXmlContentType(String contentType) {
        if (contentType == null || contentType.isEmpty()) return false;
        int semi = contentType.indexOf(';');
        String media = (semi >= 0 ? contentType.substring(0, semi) : contentType).trim().toLowerCase(Locale.ROOT);
        return media.equals("text/xml") || media.equals("application/xml") || media.equals("application/soap+xml");
    }

    /**
     * Parses SOAPAction strictly from the HTTP SOAPAction header and verifies it matches
     * the target UPnP service endpoint. Never sniffs raw POST bodies (prevents no-cors CSRF).
     */
    static String parseSoapAction(String soapActionHeader, String controlPath) {
        if (soapActionHeader == null || soapActionHeader.isEmpty()) return "";
        String clean = soapActionHeader.trim();
        if (clean.length() >= 2 && clean.startsWith("\"") && clean.endsWith("\"")) {
            clean = clean.substring(1, clean.length() - 1).trim();
        }
        int hash = clean.indexOf('#');
        if (hash <= 0 || hash + 1 >= clean.length()) return "";
        String serviceUrn = clean.substring(0, hash).trim();
        String action = clean.substring(hash + 1).trim();
        if (!serviceUrn.startsWith("urn:schemas-upnp-org:service:")) return "";

        if ("/dlna/avtransport/control".equals(controlPath)) {
            return serviceUrn.contains(":service:AVTransport:") && AVTRANSPORT_ACTIONS.contains(action) ? action : "";
        }
        if ("/dlna/rendering/control".equals(controlPath)) {
            return serviceUrn.contains(":service:RenderingControl:") && RENDERING_ACTIONS.contains(action) ? action : "";
        }
        if ("/dlna/connection/control".equals(controlPath)) {
            return serviceUrn.contains(":service:ConnectionManager:") && CONNECTION_ACTIONS.contains(action) ? action : "";
        }
        return "";
    }

    static String serviceTypeForAction(String action) {
        if (RENDERING_ACTIONS.contains(action)) return SERVICE_RENDERING;
        if (CONNECTION_ACTIONS.contains(action)) return SERVICE_CONNECTION;
        return SERVICE_AVTRANSPORT;
    }

    static String extractHeader(String rawHttp, String name) {
        if (rawHttp == null || name == null) return "";
        String prefix = name.toLowerCase(Locale.ROOT) + ":";
        for (String line : rawHttp.split("\r?\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    static List<String> notifyTargets(String uuid) {
        return Arrays.asList(
                "upnp:rootdevice",
                "uuid:" + uuid,
                DEVICE_TYPE,
                SERVICE_AVTRANSPORT,
                SERVICE_RENDERING,
                SERVICE_CONNECTION
        );
    }

    static String httpDateGmt() {
        SimpleDateFormat fmt = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("GMT"));
        return fmt.format(new Date());
    }

    static List<String> resolveSsdpSearchTargets(String rawPacket, String uuid) {
        if (rawPacket == null || uuid == null || uuid.isEmpty()) return Collections.emptyList();
        String trimmed = rawPacket.trim();
        if (trimmed.length() < 19
                || !(trimmed.substring(0, 19).equalsIgnoreCase("M-SEARCH * HTTP/1.1")
                || trimmed.substring(0, 19).equalsIgnoreCase("M-SEARCH * HTTP/1.0"))) {
            return Collections.emptyList();
        }
        String man = extractHeader(rawPacket, "MAN").toLowerCase(Locale.ROOT);
        if (!man.contains("ssdp:discover")) return Collections.emptyList();
        String st = extractHeader(rawPacket, "ST");
        if (st.isEmpty()) return Collections.emptyList();
        if (st.equalsIgnoreCase("ssdp:all")) {
            return notifyTargets(uuid);
        }
        if (st.equalsIgnoreCase("upnp:rootdevice")) {
            return Collections.singletonList("upnp:rootdevice");
        }
        if (st.equals("uuid:" + uuid)) {
            return Collections.singletonList(st);
        }
        if (st.startsWith("urn:schemas-upnp-org:device:MediaRenderer:")) {
            return Collections.singletonList(DEVICE_TYPE);
        }
        if (st.startsWith("urn:schemas-upnp-org:service:AVTransport:")) {
            return Collections.singletonList(SERVICE_AVTRANSPORT);
        }
        if (st.startsWith("urn:schemas-upnp-org:service:RenderingControl:")) {
            return Collections.singletonList(SERVICE_RENDERING);
        }
        if (st.startsWith("urn:schemas-upnp-org:service:ConnectionManager:")) {
            return Collections.singletonList(SERVICE_CONNECTION);
        }
        return Collections.emptyList();
    }

    static String buildSsdpSearchResponse(String ip, int port, String uuid, String serverToken, String target) {
        String usn = "uuid:" + uuid + (target.startsWith("uuid:") ? "" : "::" + target);
        return "HTTP/1.1 200 OK\r\n"
                + "CACHE-CONTROL: max-age=1800\r\n"
                + "DATE: " + httpDateGmt() + "\r\n"
                + "EXT:\r\n"
                + "LOCATION: http://" + ip + ":" + port + "/dlna/device.xml\r\n"
                + "OPT: \"http://schemas.upnp.org/upnp/1/0/\"; ns=01\r\n"
                + "SERVER: " + serverToken + "\r\n"
                + "ST: " + target + "\r\n"
                + "USN: " + usn + "\r\n"
                + "BOOTID.UPNP.ORG: 1\r\n"
                + "CONFIGID.UPNP.ORG: 1\r\n\r\n";
    }

    static List<String> buildSsdpNotifyMessages(String ip, int port, String uuid, String serverToken, boolean alive) {
        List<String> targets = notifyTargets(uuid);
        List<String> out = new ArrayList<>(targets.size());
        for (String nt : targets) {
            String usn = "uuid:" + uuid + (nt.startsWith("uuid:") ? "" : "::" + nt);
            String msg = "NOTIFY * HTTP/1.1\r\n"
                    + "HOST: " + SSDP_ADDRESS + ":" + SSDP_PORT + "\r\n"
                    + "CACHE-CONTROL: max-age=1800\r\n"
                    + "LOCATION: http://" + ip + ":" + port + "/dlna/device.xml\r\n"
                    + "NT: " + nt + "\r\n"
                    + "NTS: " + (alive ? "ssdp:alive" : "ssdp:byebye") + "\r\n"
                    + "SERVER: " + serverToken + "\r\n"
                    + "USN: " + usn + "\r\n"
                    + "BOOTID.UPNP.ORG: 1\r\n"
                    + "CONFIGID.UPNP.ORG: 1\r\n\r\n";
            out.add(msg);
        }
        return out;
    }

    /**
     * Extracts text content of the first matching XML element (supporting namespace prefixes,
     * element attributes, self-closing tags, and verbatim CDATA sections).
     */
    static String extractXmlTag(String xml, String localTag) {
        if (xml == null || xml.isEmpty() || localTag == null || localTag.isEmpty()) return "";
        int len = xml.length();
        int idx = 0;
        while (idx < len) {
            int lt = xml.indexOf('<', idx);
            if (lt < 0 || lt + 1 >= len) break;
            char second = xml.charAt(lt + 1);
            if (second == '!') {
                if (xml.startsWith("<![CDATA[", lt)) {
                    int cdataEnd = xml.indexOf("]]>", lt + 9);
                    idx = cdataEnd >= 0 ? cdataEnd + 3 : len;
                    continue;
                }
                if (xml.startsWith("<!--", lt)) {
                    int commentEnd = xml.indexOf("-->", lt + 4);
                    idx = commentEnd >= 0 ? commentEnd + 3 : len;
                    continue;
                }
                int bangEnd = xml.indexOf('>', lt + 2);
                idx = bangEnd >= 0 ? bangEnd + 1 : len;
                continue;
            }
            if (second == '?' || second == '/') {
                int skipEnd = xml.indexOf('>', lt + 2);
                idx = skipEnd >= 0 ? skipEnd + 1 : len;
                continue;
            }
            int nameEnd = lt + 1;
            while (nameEnd < len) {
                char ch = xml.charAt(nameEnd);
                if (Character.isWhitespace(ch) || ch == '>' || ch == '/') break;
                nameEnd++;
            }
            String fullName = xml.substring(lt + 1, nameEnd);
            int colon = fullName.indexOf(':');
            String local = colon >= 0 ? fullName.substring(colon + 1) : fullName;

            int tagClose = findTagClose(xml, nameEnd);
            if (tagClose < 0) break;

            if (local.equals(localTag)) {
                if (isSelfClosing(xml, nameEnd, tagClose)) return "";
                int contentStart = tagClose + 1;
                String closeToken = "</" + fullName;
                int closeIdx = findClosingTag(xml, contentStart, closeToken);
                if (closeIdx < 0) return "";
                return decodeXmlContent(xml.substring(contentStart, closeIdx)).trim();
            }
            idx = tagClose + 1;
        }
        return "";
    }

    private static int findTagClose(String xml, int from) {
        int len = xml.length();
        char quote = 0;
        for (int i = from; i < len; i++) {
            char ch = xml.charAt(i);
            if (quote != 0) {
                if (ch == quote) quote = 0;
            } else if (ch == '"' || ch == '\'') {
                quote = ch;
            } else if (ch == '>') {
                return i;
            }
        }
        return -1;
    }

    private static boolean isSelfClosing(String xml, int from, int gtIndex) {
        for (int i = gtIndex - 1; i >= from; i--) {
            char ch = xml.charAt(i);
            if (Character.isWhitespace(ch)) continue;
            return ch == '/';
        }
        return false;
    }

    private static int findClosingTag(String xml, int from, String closePrefix) {
        int len = xml.length();
        int i = from;
        while (i < len) {
            int cdata = xml.indexOf("<![CDATA[", i);
            int close = xml.indexOf(closePrefix, i);
            if (close < 0) return -1;
            if (cdata >= 0 && cdata < close) {
                int cdataEnd = xml.indexOf("]]>", cdata + 9);
                if (cdataEnd < 0) return -1;
                i = cdataEnd + 3;
                continue;
            }
            int after = close + closePrefix.length();
            while (after < len && Character.isWhitespace(xml.charAt(after))) after++;
            if (after < len && xml.charAt(after) == '>') return close;
            i = close + 2;
        }
        return -1;
    }

    private static String decodeXmlContent(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder out = new StringBuilder(raw.length());
        int idx = 0;
        while (idx < raw.length()) {
            int cdata = raw.indexOf("<![CDATA[", idx);
            if (cdata < 0) {
                out.append(unescapeXml(raw.substring(idx)));
                break;
            }
            if (cdata > idx) {
                out.append(unescapeXml(raw.substring(idx, cdata)));
            }
            int end = raw.indexOf("]]>", cdata + 9);
            if (end < 0) {
                out.append(raw.substring(cdata + 9));
                break;
            }
            out.append(raw, cdata + 9, end);
            idx = end + 3;
        }
        return out.toString();
    }

    static String unescapeXml(String s) {
        if (s == null || s.isEmpty() || s.indexOf('&') < 0) return s == null ? "" : s;
        StringBuilder out = new StringBuilder(s.length());
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char ch = s.charAt(i);
            if (ch != '&') {
                out.append(ch);
                continue;
            }
            int semi = s.indexOf(';', i + 1);
            if (semi < 0 || semi - i > 12) {
                out.append(ch);
                continue;
            }
            String ent = s.substring(i + 1, semi);
            if (ent.equals("lt")) out.append('<');
            else if (ent.equals("gt")) out.append('>');
            else if (ent.equals("quot")) out.append('"');
            else if (ent.equals("apos")) out.append('\'');
            else if (ent.equals("amp")) out.append('&');
            else if (ent.length() > 1 && ent.charAt(0) == '#') {
                try {
                    int codePoint = (ent.charAt(1) == 'x' || ent.charAt(1) == 'X')
                            ? Integer.parseInt(ent.substring(2), 16)
                            : Integer.parseInt(ent.substring(1), 10);
                    if (codePoint > 0 && Character.isValidCodePoint(codePoint)) {
                        out.appendCodePoint(codePoint);
                    } else {
                        out.append('&').append(ent).append(';');
                    }
                } catch (NumberFormatException e) {
                    out.append('&').append(ent).append(';');
                }
            } else {
                out.append('&').append(ent).append(';');
            }
            i = semi;
        }
        return out.toString();
    }

    static String escapeXml(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    static boolean isAllowedMediaUri(String uri) {
        if (uri == null) return false;
        String clean = uri.trim();
        if (clean.length() < 8 || clean.length() > 4096) return false;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c <= 0x20 || c == 0x7F) return false;
        }
        String lower = clean.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return false;
        int schemeEnd = clean.indexOf("://") + 3;
        if (schemeEnd >= clean.length() || clean.charAt(schemeEnd) == '/') return false;
        int slash = clean.indexOf('/', schemeEnd);
        String rawAuth = slash >= 0 ? clean.substring(schemeEnd, slash) : clean.substring(schemeEnd);
        int q = rawAuth.indexOf('?');
        if (q >= 0) rawAuth = rawAuth.substring(0, q);
        if (rawAuth.isEmpty() || rawAuth.contains("@")) return false;
        String hostPart = rawAuth;
        if (hostPart.startsWith("[")) {
            int rb = hostPart.indexOf(']');
            if (rb <= 1) return false;
            hostPart = hostPart.substring(1, rb);
        } else {
            int colon = hostPart.indexOf(':');
            if (colon >= 0) hostPart = hostPart.substring(0, colon);
        }
        String lowerHost = hostPart.toLowerCase(Locale.ROOT);
        if (lowerHost.isEmpty() || lowerHost.equals("localhost") || lowerHost.endsWith(".localhost")
                || lowerHost.startsWith("127.") || lowerHost.equals("0.0.0.0")
                || lowerHost.equals("::1") || lowerHost.equals("0:0:0:0:0:0:0:1") || lowerHost.equals("::")) {
            return false;
        }
        try {
            URI parsed = new URI(clean);
            if (parsed.getRawUserInfo() != null) return false;
            String host = parsed.getHost();
            if (host != null && !host.isEmpty()) return true;
            String auth = parsed.getRawAuthority();
            return auth != null && !auth.isEmpty() && !auth.contains("@");
        } catch (Exception e) {
            // Allow URLs with unescaped UTF-8 or pipe chars used by some video CDNs as long as authority is valid
            return true;
        }
    }

    /**
     * When "Confirm Untrusted Devices" is enabled, only previously approved session IPs
     * may auto-start playback without user confirmation on TV.
     */
    static boolean shouldAutoAllowCast(boolean confirmUntrusted, boolean isTrustedIp) {
        return !confirmUntrusted || isTrustedIp;
    }

    /**
     * Determines whether a peer IP is permitted to mutate active transport or volume state.
     * When confirmUntrusted is true, untrusted IPs cannot hijack playback, queue NextURI, or change volume.
     * Even when confirmUntrusted is false, volume changes require an active cast session or trusted IP.
     */
    static boolean canControlSession(boolean confirmUntrusted, boolean isTrustedIp, boolean isPlaying, boolean isVolumeAction) {
        if (confirmUntrusted) return isTrustedIp;
        if (isVolumeAction) return isPlaying || isTrustedIp;
        return true;
    }

    /**
     * Redacts URI echo in GetPositionInfo / GetMediaInfo:
     * - Never echoes local file:// or content:// URIs.
     * - Never echoes URIs to third-party LAN nodes other than the active session owner IP.
     * - Strips credentials, query tokens (?token=...), and fragments even for the owner IP.
     */
    static String sanitizeEchoUri(String uri, String ownerIp, String requesterIp) {
        if (uri == null || uri.isEmpty()) return "";
        if (ownerIp == null || ownerIp.isEmpty() || requesterIp == null || !ownerIp.equals(requesterIp)) {
            return "";
        }
        String lower = uri.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return "";
        int schemeSep = uri.indexOf("://");
        if (schemeSep < 0) return "";
        String scheme = uri.substring(0, schemeSep + 3);
        String rest = uri.substring(schemeSep + 3);
        int q = rest.indexOf('?');
        if (q >= 0) rest = rest.substring(0, q);
        int hash = rest.indexOf('#');
        if (hash >= 0) rest = rest.substring(0, hash);
        int slash = rest.indexOf('/');
        String authority = slash >= 0 ? rest.substring(0, slash) : rest;
        String path = slash >= 0 ? rest.substring(slash) : "";
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        if (authority.isEmpty()) return "";
        return scheme + authority + path;
    }

    static int parseTimeMs(String target) {
        if (target == null || target.isEmpty()) return 0;
        String clean = target.trim();
        int dot = clean.indexOf('.');
        if (dot >= 0) clean = clean.substring(0, dot);
        String[] parts = clean.split(":");
        try {
            if (parts.length == 3) {
                int h = Integer.parseInt(parts[0]);
                int m = Integer.parseInt(parts[1]);
                int s = Integer.parseInt(parts[2]);
                if (h < 0 || m < 0 || s < 0) return 0;
                return (h * 3600 + m * 60 + s) * 1000;
            }
            if (parts.length == 2) {
                int m = Integer.parseInt(parts[0]);
                int s = Integer.parseInt(parts[1]);
                if (m < 0 || s < 0) return 0;
                return (m * 60 + s) * 1000;
            }
            int s = Integer.parseInt(parts[0]);
            return Math.max(0, s * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String formatTime(int ms) {
        int totalSec = Math.max(0, ms / 1000);
        int h = totalSec / 3600;
        int m = (totalSec % 3600) / 60;
        int s = totalSec % 60;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, s);
    }

    static String wrapSoapResponse(String serviceType, String action, String innerXml) {
        String respTag = (action == null || action.isEmpty()) ? "ActionResponse" : action + "Response";
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + respTag + " xmlns:u=\"" + serviceType + "\">"
                + (innerXml == null ? "" : innerXml)
                + "</u:" + respTag + "></s:Body></s:Envelope>";
    }

    static String buildSoapFault(int upnpCode, String description) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>"
                + "<detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">"
                + "<errorCode>" + upnpCode + "</errorCode>"
                + "<errorDescription>" + escapeXml(description) + "</errorDescription>"
                + "</UPnPError></detail></s:Fault></s:Body></s:Envelope>";
    }

    static String buildDeviceXml(String friendlyName, String uuid, String modelVersion) {
        return buildDeviceXml(friendlyName, uuid, modelVersion, null);
    }

    static String buildDeviceXml(String friendlyName, String uuid, String modelVersion, String baseUrl) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">"
                + "<specVersion><major>1</major><minor>0</minor></specVersion>"
                + (baseUrl != null && !baseUrl.isEmpty() ? "<URLBase>" + escapeXml(baseUrl) + "</URLBase>" : "")
                + "<device>"
                + "<deviceType>" + DEVICE_TYPE + "</deviceType>"
                + "<friendlyName>" + escapeXml(friendlyName) + "</friendlyName>"
                + "<manufacturer>Pilipala</manufacturer>"
                + "<manufacturerURL>https://github.com/Pilipala9919/MarcoTV</manufacturerURL>"
                + "<modelDescription>Marco TV DLNA MediaRenderer</modelDescription>"
                + "<modelName>Marco TV</modelName>"
                + "<modelNumber>" + escapeXml(modelVersion) + "</modelNumber>"
                + "<modelURL>https://github.com/Pilipala9919/MarcoTV</modelURL>"
                + "<serialNumber>1</serialNumber>"
                + "<UDN>uuid:" + escapeXml(uuid) + "</UDN>"
                + "<dlna:X_DLNADOC xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">DMR-1.50</dlna:X_DLNADOC>"
                + "<presentationURL>/</presentationURL>"
                + "<serviceList>"
                + "<service>"
                + "<serviceType>" + SERVICE_AVTRANSPORT + "</serviceType>"
                + "<serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>"
                + "<SCPDURL>/dlna/avtransport.xml</SCPDURL>"
                + "<controlURL>/dlna/avtransport/control</controlURL>"
                + "<eventSubURL>/dlna/avtransport/event</eventSubURL>"
                + "</service>"
                + "<service>"
                + "<serviceType>" + SERVICE_RENDERING + "</serviceType>"
                + "<serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>"
                + "<SCPDURL>/dlna/rendering.xml</SCPDURL>"
                + "<controlURL>/dlna/rendering/control</controlURL>"
                + "<eventSubURL>/dlna/rendering/event</eventSubURL>"
                + "</service>"
                + "<service>"
                + "<serviceType>" + SERVICE_CONNECTION + "</serviceType>"
                + "<serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>"
                + "<SCPDURL>/dlna/connection.xml</SCPDURL>"
                + "<controlURL>/dlna/connection/control</controlURL>"
                + "<eventSubURL>/dlna/connection/event</eventSubURL>"
                + "</service>"
                + "</serviceList>"
                + "</device></root>";
    }
}
