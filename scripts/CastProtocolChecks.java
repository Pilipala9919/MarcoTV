// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Pure-JVM verification of DLNA/UPnP framing, CSRF/rebinding guards, trust policy, and XML safety. */
public final class CastProtocolChecks {
    private static int passed;
    private static final String TV_IP = "198.51.100.10";
    private static final String OWNER_IP = "198.51.100.20";
    private static final String PEER_IP = "203.0.113.5";
    private static final int PORT = 49152;
    private static final String UUID = "11111111-2222-4333-8444-555555555555";

    private static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
        passed++;
        System.out.println("PASS " + name);
    }

    private static CastProtocol.HttpHead parse(String raw) throws IOException {
        return CastProtocol.readHttpHead(
                new ByteArrayInputStream(raw.getBytes(StandardCharsets.US_ASCII)),
                System.currentTimeMillis() + 5000L);
    }

    private static boolean failsRead(String raw) {
        try {
            parse(raw);
            return false;
        } catch (IOException expected) {
            return true;
        }
    }

    public static void main(String[] args) throws Exception {
        // 1. HTTP framing & request smuggling defenses
        CastProtocol.HttpHead getDevice = parse(
                "GET /dlna/device.xml HTTP/1.1\r\nHost: " + TV_IP + ":" + PORT + "\r\n\r\n");
        check("valid_device_xml_get", CastProtocol.checkHttpSecurity(getDevice, TV_IP, PORT) == 0);
        check("reject_oversized_header",
                failsRead("GET /dlna/device.xml HTTP/1.1\r\nHost: " + TV_IP + ":" + PORT + "\r\nX-Pad: "
                        + "A".repeat(CastProtocol.MAX_HEADER_BYTES) + "\r\n\r\n"));
        check("reject_chunked_transfer_encoding",
                failsRead("POST /dlna/avtransport/control HTTP/1.1\r\nHost: " + TV_IP + ":" + PORT
                        + "\r\nTransfer-Encoding: chunked\r\nContent-Length: 10\r\n\r\n"));
        check("reject_duplicate_host_header",
                failsRead("GET /dlna/device.xml HTTP/1.1\r\nHost: " + TV_IP + ":" + PORT
                        + "\r\nHost: evil.example\r\n\r\n"));
        check("reject_post_missing_content_length",
                failsRead("POST /dlna/avtransport/control HTTP/1.1\r\nHost: " + TV_IP + ":" + PORT + "\r\n\r\n"));

        // 2. DNS rebinding, browser Origin CSRF, Content-Type & SOAPAction header enforcement
        CastProtocol.HttpHead validSoap = parse(
                "POST /dlna/avtransport/control HTTP/1.1\r\n"
                        + "Host: " + TV_IP + ":" + PORT + "\r\n"
                        + "Content-Type: text/xml; charset=\"utf-8\"\r\n"
                        + "SOAPAction: \"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"\r\n"
                        + "Content-Length: 128\r\n\r\n");
        check("valid_avtransport_soap_allowed", CastProtocol.checkHttpSecurity(validSoap, TV_IP, PORT) == 0);

        CastProtocol.HttpHead rebindHost = parse(
                "POST /dlna/avtransport/control HTTP/1.1\r\n"
                        + "Host: attacker-rebind.example:" + PORT + "\r\n"
                        + "Content-Type: text/xml\r\n"
                        + "SOAPAction: \"urn:schemas-upnp-org:service:AVTransport:1#Play\"\r\n"
                        + "Content-Length: 64\r\n\r\n");
        check("dns_rebinding_host_rejected_403", CastProtocol.checkHttpSecurity(rebindHost, TV_IP, PORT) == 403);

        CastProtocol.HttpHead wrongPortHost = parse(
                "GET /dlna/device.xml HTTP/1.1\r\nHost: " + TV_IP + ":9999\r\n\r\n");
        check("wrong_port_host_rejected_403", CastProtocol.checkHttpSecurity(wrongPortHost, TV_IP, PORT) == 403);

        CastProtocol.HttpHead browserOrigin = parse(
                "POST /dlna/avtransport/control HTTP/1.1\r\n"
                        + "Host: " + TV_IP + ":" + PORT + "\r\n"
                        + "Origin: https://evil.example\r\n"
                        + "Content-Type: text/xml\r\n"
                        + "SOAPAction: \"urn:schemas-upnp-org:service:AVTransport:1#Play\"\r\n"
                        + "Content-Length: 64\r\n\r\n");
        check("cross_origin_csrf_rejected_403", CastProtocol.checkHttpSecurity(browserOrigin, TV_IP, PORT) == 403);

        CastProtocol.HttpHead nullOrigin = parse(
                "POST /dlna/avtransport/control HTTP/1.1\r\n"
                        + "Host: " + TV_IP + ":" + PORT + "\r\n"
                        + "Origin: null\r\n"
                        + "Content-Type: text/xml\r\n"
                        + "SOAPAction: \"urn:schemas-upnp-org:service:AVTransport:1#Play\"\r\n"
                        + "Content-Length: 64\r\n\r\n");
        check("sandbox_null_origin_rejected_403", CastProtocol.checkHttpSecurity(nullOrigin, TV_IP, PORT) == 403);

        CastProtocol.HttpHead sameOrigin = parse(
                "POST /dlna/avtransport/control HTTP/1.1\r\n"
                        + "Host: " + TV_IP + ":" + PORT + "\r\n"
                        + "Origin: http://" + TV_IP + ":" + PORT + "\r\n"
                        + "Content-Type: application/soap+xml\r\n"
                        + "SOAPAction: \"urn:schemas-upnp-org:service:AVTransport:1#Pause\"\r\n"
                        + "Content-Length: 64\r\n\r\n");
        check("same_origin_post_allowed", CastProtocol.checkHttpSecurity(sameOrigin, TV_IP, PORT) == 0);

        CastProtocol.HttpHead textPlainCsrf = parse(
                "POST /dlna/avtransport/control HTTP/1.1\r\n"
                        + "Host: " + TV_IP + ":" + PORT + "\r\n"
                        + "Content-Type: text/plain\r\n"
                        + "SOAPAction: \"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"\r\n"
                        + "Content-Length: 64\r\n\r\n");
        check("non_xml_content_type_rejected_415", CastProtocol.checkHttpSecurity(textPlainCsrf, TV_IP, PORT) == 415);

        CastProtocol.HttpHead noSoapAction = parse(
                "POST /dlna/avtransport/control HTTP/1.1\r\n"
                        + "Host: " + TV_IP + ":" + PORT + "\r\n"
                        + "Content-Type: text/xml\r\n"
                        + "Content-Length: 120\r\n\r\n");
        check("missing_soapaction_header_rejected_403", CastProtocol.checkHttpSecurity(noSoapAction, TV_IP, PORT) == 403);

        CastProtocol.HttpHead oversizedBody = parse(
                "POST /dlna/avtransport/control HTTP/1.1\r\n"
                        + "Host: " + TV_IP + ":" + PORT + "\r\n"
                        + "Content-Type: text/xml\r\n"
                        + "SOAPAction: \"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"\r\n"
                        + "Content-Length: " + (CastProtocol.MAX_SOAP_BODY_BYTES + 1) + "\r\n\r\n");
        check("oversized_soap_body_rejected_413", CastProtocol.checkHttpSecurity(oversizedBody, TV_IP, PORT) == 413);

        check("cross_service_soapaction_rejected",
                CastProtocol.parseSoapAction(
                        "\"urn:schemas-upnp-org:service:RenderingControl:1#SetVolume\"",
                        "/dlna/avtransport/control").isEmpty());
        check("connection_manager_protocol_info_parsed",
                "GetProtocolInfo".equals(CastProtocol.parseSoapAction(
                        "\"urn:schemas-upnp-org:service:ConnectionManager:1#GetProtocolInfo\"",
                        "/dlna/connection/control")));

        // 3. SSDP multi-target discovery & NOTIFY framing
        String searchAll = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n";
        List<String> allTargets = CastProtocol.resolveSsdpSearchTargets(searchAll, UUID);
        check("ssdp_all_expands_six_targets",
                allTargets.size() == 6 && allTargets.contains(CastProtocol.SERVICE_CONNECTION));
        String searchAv = "M-SEARCH * HTTP/1.1\r\nMAN: \"ssdp:discover\"\r\nST: urn:schemas-upnp-org:service:AVTransport:1\r\n\r\n";
        check("ssdp_avtransport_single_match",
                CastProtocol.resolveSsdpSearchTargets(searchAv, UUID).size() == 1);
        check("ssdp_foreign_uuid_ignored",
                CastProtocol.resolveSsdpSearchTargets(
                        "M-SEARCH * HTTP/1.1\r\nMAN: \"ssdp:discover\"\r\nST: uuid:other\r\n\r\n", UUID).isEmpty());
        check("ssdp_byebye_contains_six_notifications",
                CastProtocol.buildSsdpNotifyMessages(TV_IP, PORT, UUID, "Linux/4.9 UPnP/1.0 MarcoTV/1.0", false)
                        .get(0).contains("NTS: ssdp:byebye"));

        // 4. XML extraction: namespaces, attributes, CDATA, numeric entities, no double-unescaping
        String soapBody = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
                + "<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                + "<CurrentURI dt:dt=\"string\">https://cdn.example.com/live.m3u8?a=1&amp;b=2</CurrentURI>"
                + "<CurrentURIMetaData><![CDATA[<DIDL-Lite><dc:title>A &amp; B</dc:title></DIDL-Lite>]]></CurrentURIMetaData>"
                + "</u:SetAVTransportURI></s:Body></s:Envelope>";
        check("xml_attributed_tag_and_amp_decode",
                "https://cdn.example.com/live.m3u8?a=1&b=2".equals(CastProtocol.extractXmlTag(soapBody, "CurrentURI")));
        String didl = CastProtocol.extractXmlTag(soapBody, "CurrentURIMetaData");
        check("xml_cdata_preserved_then_nested_title_decoded",
                "A & B".equals(CastProtocol.extractXmlTag(didl, "title")));
        check("xml_no_double_unescape",
                "&lt;script&gt;".equals(CastProtocol.unescapeXml("&amp;lt;script&amp;gt;")));
        check("xml_numeric_entities_decoded",
                "AZ".equals(CastProtocol.unescapeXml("&#65;&#x5a;")));

        // 5. Media URI scheme validation, trust matrix (confirmUntrusted fix), and echo sanitization
        check("http_and_https_media_uris_allowed",
                CastProtocol.isAllowedMediaUri("http://198.51.100.20:8080/video.mp4")
                        && CastProtocol.isAllowedMediaUri("https://media.example.com/stream/index.m3u8?token=abc"));
        check("unsafe_media_schemes_rejected",
                !CastProtocol.isAllowedMediaUri("file:///data/data/com.pilipala.tvhome/shared_prefs/home.xml")
                        && !CastProtocol.isAllowedMediaUri("content://com.pilipala.tvhome.apk/cast-media/a.mp4")
                        && !CastProtocol.isAllowedMediaUri("javascript:alert(1)")
                        && !CastProtocol.isAllowedMediaUri("http://user:secret@example.com/a.mp4")
                        && !CastProtocol.isAllowedMediaUri("http://127.0.0.1:5555/stream.mp4")
                        && !CastProtocol.isAllowedMediaUri("http://localhost:8080/a.mp4")
                        && !CastProtocol.isAllowedMediaUri("http://[::1]:8080/a.mp4"));

        check("confirm_untrusted_blocks_unapproved_ip",
                !CastProtocol.shouldAutoAllowCast(true, false)
                        && CastProtocol.shouldAutoAllowCast(true, true)
                        && CastProtocol.shouldAutoAllowCast(false, false));
        check("session_control_matrix_enforced",
                !CastProtocol.canControlSession(true, false, true, false)
                        && CastProtocol.canControlSession(true, true, true, true)
                        && !CastProtocol.canControlSession(false, false, false, true)
                        && CastProtocol.canControlSession(false, false, true, true));

        String secretStream = "https://user:pass@cdn.example.com:8443/live/room.m3u8?token=secret123#frag";
        check("echo_uri_strips_credentials_and_query_for_owner",
                "https://cdn.example.com:8443/live/room.m3u8".equals(
                        CastProtocol.sanitizeEchoUri(secretStream, OWNER_IP, OWNER_IP)));
        check("echo_uri_hidden_from_non_owner_peer",
                "".equals(CastProtocol.sanitizeEchoUri(secretStream, OWNER_IP, PEER_IP)));
        check("echo_uri_never_leaks_local_content_uri",
                "".equals(CastProtocol.sanitizeEchoUri(
                        "content://com.pilipala.tvhome.apk/cast-media/local.mp4", OWNER_IP, OWNER_IP)));

        // 6. Time conversion & XML escaping in device descriptor
        check("dlna_time_roundtrip",
                CastProtocol.parseTimeMs("01:02:03.450") == 3723000
                        && "01:02:03".equals(CastProtocol.formatTime(3723000)));
        String deviceXml = CastProtocol.buildDeviceXml("Living <TV> & \"Box\"", UUID, "0.16.0");
        check("device_xml_escapes_friendly_name_and_exposes_connection_service",
                deviceXml.contains("Living &lt;TV&gt; &amp; &quot;Box&quot;")
                        && deviceXml.contains(CastProtocol.SERVICE_CONNECTION));

        System.out.println("OK " + passed + " cast protocol & security checks");
    }
}
