// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Bounded LAN DLNA MediaRenderer transport (HTTP SOAP + SSDP multicast).
 * Uses a fixed-ceiling thread pool and per-IP concurrent socket limits to protect low-RAM TVs.
 */
final class DlnaServer implements AutoCloseable {
    interface SoapHandler {
        SoapResult onSoapAction(String action, String body, String remoteIp);
    }

    static final class SoapResult {
        final int httpStatus;
        final String xml;

        SoapResult(int httpStatus, String xml) {
            this.httpStatus = httpStatus;
            this.xml = xml;
        }

        static SoapResult ok(String serviceType, String action, String innerXml) {
            return new SoapResult(200, CastProtocol.wrapSoapResponse(serviceType, action, innerXml));
        }

        static SoapResult fault(int upnpCode, String description) {
            return new SoapResult(500, CastProtocol.buildSoapFault(upnpCode, description));
        }
    }

    private static final int HEADER_TIMEOUT_MS = 4000;
    private static final int BODY_TIMEOUT_MS = 5000;
    private static final int MAX_PER_IP = 16;
    // Match well-known DLNA + Marco Movie iOS sideload probe ports (avoiding Xiaomi's 49152).
    private static final int[] PREFERRED_PORTS = {18652, 49153, 49154, 49595, 18653};

    private final Context appContext;
    private final Handler mainHandler;
    private final String ip;
    private final InetAddress bindAddr;
    private final NetworkInterface multicastNif;
    private final String uuid;
    private final String friendlyName;
    private final String serverToken;
    private final SoapHandler soapHandler;

    private final ServerSocket httpServer;
    private final int port;
    private final ThreadPoolExecutor ioPool;
    private final Set<Socket> sockets = Collections.synchronizedSet(new HashSet<Socket>());
    private final Map<String, Integer> activeByIp = new HashMap<>();
    private final Object ssdpSendLock = new Object();
    private final Map<String, Long> searchLogAt = new HashMap<>();
    private MulticastSocket ssdpSocket;
    private WifiManager.MulticastLock multicastLock;
    private final Object nsdLock = new Object();
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener nsdListener;
    private boolean nsdRegistered;
    private boolean nsdUnregisterWanted;
    private volatile boolean closed;
    private volatile boolean ssdpSearchBound;

    private final Runnable ssdpAliveTicker = new Runnable() {
        @Override public void run() {
            if (closed) return;
            announceAlive();
            mainHandler.postDelayed(this, ssdpSearchBound ? 5000L : 4000L);
        }
    };

    DlnaServer(Context context, Handler handler, String ip, String uuid, String friendlyName, SoapHandler soapHandler) throws IOException {
        this.appContext = context.getApplicationContext();
        this.mainHandler = handler;
        this.ip = ip;
        this.uuid = uuid;
        this.friendlyName = friendlyName;
        this.serverToken = "Android/" + Build.VERSION.RELEASE + " UPnP/1.0 MarcoTV/" + ProjectInfo.VERSION;
        this.soapHandler = soapHandler;

        this.bindAddr = InetAddress.getByName(ip);
        NetworkInterface resolvedNif = null;
        try {
            resolvedNif = NetworkInterface.getByInetAddress(bindAddr);
        } catch (SocketException ignored) {}
        this.multicastNif = resolvedNif;

        httpServer = bindHttpServer(bindAddr);
        port = httpServer.getLocalPort();

        ioPool = new ThreadPoolExecutor(2, 12, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(32));

        try {
            WifiManager wifi = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("marcotv-dlna");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (RuntimeException ignored) {}

        Thread acceptThread = new Thread(this::acceptLoop, "MarcoDlnaHttp");
        acceptThread.setDaemon(true);
        acceptThread.start();

        InetAddress group = InetAddress.getByName(CastProtocol.SSDP_ADDRESS);
        MulticastSocket bound = bindSsdpListener(group, multicastNif);
        if (bound != null) {
            ssdpSocket = bound;
            ssdpSearchBound = true;
            Thread ssdpThread = new Thread(() -> listenSsdp(bound), "MarcoDlnaSsdp");
            ssdpThread.setDaemon(true);
            ssdpThread.start();
        } else {
            ssdpSearchBound = false;
            Diagnostics.event("CAST_SSDP_PORT_BUSY");
        }
        Log.i("MarcoTV", "DLNA started on " + ip + ":" + port + " ssdpBound=" + ssdpSearchBound
                + " nif=" + (multicastNif == null ? "default" : multicastNif.getName()));

        registerNsd();

        mainHandler.post(ssdpAliveTicker);
        mainHandler.postDelayed(this::announceAlive, 400L);
        mainHandler.postDelayed(this::announceAlive, 1200L);
    }

    private void registerNsd() {
        try {
            final NsdManager mgr = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
            if (mgr == null) return;
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(friendlyName);
            info.setServiceType("_dlna._tcp.");
            info.setPort(port);
            final NsdManager.RegistrationListener listener = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo registered) {
                    Log.i("MarcoTV", "DLNA mDNS registered: " + registered.getServiceName() + " _dlna._tcp.:" + port);
                    boolean unregisterNow;
                    synchronized (nsdLock) {
                        nsdRegistered = true;
                        unregisterNow = nsdUnregisterWanted || closed;
                        if (unregisterNow) {
                            nsdRegistered = false;
                            nsdManager = null;
                            nsdListener = null;
                        }
                    }
                    if (unregisterNow) {
                        try { mgr.unregisterService(this); } catch (Throwable ignored) {}
                    }
                }
                @Override public void onRegistrationFailed(NsdServiceInfo info, int errorCode) {
                    Log.w("MarcoTV", "DLNA mDNS register failed: code=" + errorCode);
                    synchronized (nsdLock) {
                        nsdRegistered = false;
                        nsdManager = null;
                        nsdListener = null;
                    }
                }
                @Override public void onServiceUnregistered(NsdServiceInfo info) {}
                @Override public void onUnregistrationFailed(NsdServiceInfo info, int errorCode) {}
            };
            synchronized (nsdLock) {
                if (closed) return;
                nsdManager = mgr;
                nsdListener = listener;
            }
            mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (Throwable error) {
            Log.w("MarcoTV", "DLNA mDNS unavailable: " + error.getMessage());
        }
    }

    private void unregisterNsd() {
        NsdManager mgr = null;
        NsdManager.RegistrationListener listener = null;
        synchronized (nsdLock) {
            nsdUnregisterWanted = true;
            if (nsdRegistered) {
                mgr = nsdManager;
                listener = nsdListener;
                nsdRegistered = false;
                nsdManager = null;
                nsdListener = null;
            }
        }
        if (mgr != null && listener != null) {
            try {
                mgr.unregisterService(listener);
            } catch (Throwable ignored) {}
        }
    }

    private static ServerSocket bindHttpServer(InetAddress bindAddr) throws IOException {
        for (int candidate : PREFERRED_PORTS) {
            ServerSocket srv = null;
            try {
                srv = new ServerSocket();
                srv.setReuseAddress(true);
                srv.bind(new InetSocketAddress(bindAddr, candidate), 32);
                return srv;
            } catch (IOException busy) {
                if (srv != null) {
                    try { srv.close(); } catch (IOException ignored) {}
                }
            }
        }
        ServerSocket fallback = new ServerSocket();
        fallback.setReuseAddress(true);
        fallback.bind(new InetSocketAddress(bindAddr, 0), 32);
        return fallback;
    }

    private static MulticastSocket bindSsdpListener(InetAddress group, NetworkInterface nif) {
        InetSocketAddress[] candidates = new InetSocketAddress[]{
                new InetSocketAddress(ipv4Wildcard(), CastProtocol.SSDP_PORT),
                new InetSocketAddress(group, CastProtocol.SSDP_PORT),
                new InetSocketAddress(CastProtocol.SSDP_PORT)
        };
        for (InetSocketAddress addr : candidates) {
            if (addr.getAddress() == null && addr.getPort() != CastProtocol.SSDP_PORT) continue;
            MulticastSocket ms = null;
            try {
                ms = new MulticastSocket(null);
                ms.setReuseAddress(true);
                ms.bind(addr);
                if (nif != null) {
                    try { ms.setNetworkInterface(nif); } catch (SocketException ignored) {}
                }
                try { ms.setTimeToLive(4); } catch (IOException ignored) {}
                if (nif != null) ms.joinGroup(new InetSocketAddress(group, CastProtocol.SSDP_PORT), nif);
                else ms.joinGroup(group);
                return ms;
            } catch (IOException error) {
                if (ms != null) {
                    try { ms.close(); } catch (RuntimeException ignored) {}
                }
            }
        }
        return null;
    }

    private static InetAddress ipv4Wildcard() {
        try {
            return InetAddress.getByAddress(new byte[]{0, 0, 0, 0});
        } catch (UnknownHostException ignored) {
            return null;
        }
    }

    String ip() {
        return ip;
    }

    int port() {
        return port;
    }

    boolean ssdpSearchBound() {
        return ssdpSearchBound;
    }

    void announceAlive() {
        if (closed) return;
        try {
            ioPool.execute(() -> sendSsdpNotify(ssdpSocket, ssdpSendLock, ip, port, uuid, serverToken, multicastNif, true));
        } catch (RejectedExecutionException ignored) {}
    }

    private boolean acquireIp(String remoteIp) {
        if (remoteIp.isEmpty()) return true;
        synchronized (activeByIp) {
            Integer cur = activeByIp.get(remoteIp);
            int n = cur == null ? 0 : cur;
            if (n >= MAX_PER_IP) return false;
            activeByIp.put(remoteIp, n + 1);
            return true;
        }
    }

    private void releaseIp(String remoteIp) {
        if (remoteIp.isEmpty()) return;
        synchronized (activeByIp) {
            Integer cur = activeByIp.get(remoteIp);
            int n = cur == null ? 0 : cur;
            if (n <= 1) activeByIp.remove(remoteIp);
            else activeByIp.put(remoteIp, n - 1);
        }
    }

    private void acceptLoop() {
        while (!closed && !httpServer.isClosed()) {
            try {
                final Socket client = httpServer.accept();
                final String remoteIp = client.getInetAddress() == null ? "" : client.getInetAddress().getHostAddress();
                if (!acquireIp(remoteIp)) {
                    try { client.close(); } catch (IOException ignored) {}
                    continue;
                }
                try {
                    client.setSoTimeout(HEADER_TIMEOUT_MS);
                    sockets.add(client);
                    ioPool.execute(() -> handleHttpClient(client, remoteIp));
                } catch (RejectedExecutionException | IOException rejected) {
                    sockets.remove(client);
                    releaseIp(remoteIp);
                    try { client.close(); } catch (IOException ignored) {}
                }
            } catch (IOException e) {
                if (!closed && httpServer.isClosed()) close();
            }
        }
    }

    private void handleHttpClient(Socket socket, String remoteIp) {
        try (Socket s = socket;
             InputStream in = new BufferedInputStream(s.getInputStream());
             OutputStream out = new BufferedOutputStream(s.getOutputStream())) {
            CastProtocol.HttpHead head;
            try {
                head = CastProtocol.readHttpHead(in, System.currentTimeMillis() + HEADER_TIMEOUT_MS);
            } catch (IOException badHead) {
                Log.w("MarcoTV", "DLNA HTTP 400 from " + remoteIp + " err=" + badHead.getMessage());
                writeEmptyStatus(out, 400, "Bad Request");
                return;
            }
            int denied = CastProtocol.checkHttpSecurity(head, ip, port);
            if (denied != 0) {
                Log.w("MarcoTV", "DLNA HTTP " + denied + " " + head.method + " " + head.path
                        + " from " + remoteIp + " host=" + head.header("host"));
                writeEmptyStatus(out, denied, statusText(denied));
                return;
            }
            if ("SUBSCRIBE".equals(head.method) || "UNSUBSCRIBE".equals(head.method)) {
                Log.i("MarcoTV", "DLNA HTTP 200 " + head.method + " " + head.path + " from " + remoteIp);
                String subReply = "HTTP/1.1 200 OK\r\n"
                        + "DATE: " + CastProtocol.httpDateGmt() + "\r\n"
                        + "SERVER: " + serverToken + "\r\n"
                        + "SID: uuid:marcotv-event-" + uuid + "\r\n"
                        + "TIMEOUT: Second-1800\r\n"
                        + "Content-Length: 0\r\n"
                        + "Connection: close\r\n\r\n";
                out.write(subReply.getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return;
            }
            if ("GET".equals(head.method) || "HEAD".equals(head.method)) {
                boolean headOnly = "HEAD".equals(head.method);
                Log.i("MarcoTV", "DLNA HTTP 200 " + head.method + " " + head.path + " from " + remoteIp);
                if (head.path.equals("/")) {
                    writeHttpText(out, 200, "text/html; charset=\"utf-8\"",
                            "<!doctype html><title>Marco TV</title><p>Marco TV DLNA MediaRenderer</p>", headOnly);
                    return;
                }
                String xml;
                if (head.path.equals("/dlna/device.xml")) {
                    xml = CastProtocol.buildDeviceXml(friendlyName, uuid, ProjectInfo.VERSION, "http://" + ip + ":" + port);
                } else if (head.path.equals("/dlna/avtransport.xml")) {
                    xml = CastProtocol.AVTRANSPORT_SCPD;
                } else if (head.path.equals("/dlna/rendering.xml")) {
                    xml = CastProtocol.RENDERING_SCPD;
                } else {
                    xml = CastProtocol.CONNECTION_SCPD;
                }
                writeHttpXml(out, 200, xml, headOnly);
                return;
            }
            s.setSoTimeout(BODY_TIMEOUT_MS);
            byte[] bodyBytes = new byte[head.contentLength];
            int read = 0;
            long bodyDeadline = System.currentTimeMillis() + BODY_TIMEOUT_MS;
            while (read < head.contentLength) {
                if (closed || System.currentTimeMillis() > bodyDeadline) throw new SocketTimeoutException("body deadline");
                int n = in.read(bodyBytes, read, head.contentLength - read);
                if (n < 0) throw new EOFException("truncated soap body");
                read += n;
            }
            String body = new String(bodyBytes, StandardCharsets.UTF_8);
            String action = CastProtocol.parseSoapAction(head.header("soapaction"), head.path);
            SoapResult result = soapHandler.onSoapAction(action, body, remoteIp);
            Log.i("MarcoTV", "DLNA SOAP " + action + " -> " + result.httpStatus + " from " + remoteIp);
            writeHttpXml(out, result.httpStatus, result.xml, false);
        } catch (Exception ignored) {
        } finally {
            sockets.remove(socket);
            releaseIp(remoteIp);
        }
    }

    private static String statusText(int code) {
        switch (code) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 413: return "Payload Too Large";
            case 415: return "Unsupported Media Type";
            default: return "Internal Server Error";
        }
    }

    private static void writeEmptyStatus(OutputStream out, int code, String reason) throws IOException {
        String resp = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Length: 0\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Connection: close\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private void writeHttpXml(OutputStream out, int code, String xml, boolean headOnly) throws IOException {
        writeHttpText(out, code, "text/xml; charset=\"utf-8\"", xml, headOnly);
    }

    private void writeHttpText(OutputStream out, int code, String contentType, String text, boolean headOnly) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + code + " " + statusText(code) + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Date: " + CastProtocol.httpDateGmt() + "\r\n"
                + "Server: " + serverToken + "\r\n"
                + "EXT:\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        if (!headOnly) out.write(bytes);
        out.flush();
    }

    private void listenSsdp(MulticastSocket sock) {
        byte[] buf = new byte[2048];
        DatagramSocket replySock = null;
        try {
            replySock = new DatagramSocket(0, bindAddr);
        } catch (IOException ignored) {}
        try {
            while (!closed && !sock.isClosed()) {
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    sock.receive(pkt);
                    String msg = new String(pkt.getData(), pkt.getOffset(), pkt.getLength(), StandardCharsets.UTF_8);
                    List<String> targets = CastProtocol.resolveSsdpSearchTargets(msg, uuid);
                    if (targets.isEmpty()) continue;
                    String srcIp = pkt.getAddress() == null ? "" : pkt.getAddress().getHostAddress();
                    logSearchSample(srcIp, pkt.getPort(), CastProtocol.extractHeader(msg, "ST"), targets.size());
                    for (String target : targets) {
                        String reply = CastProtocol.buildSsdpSearchResponse(ip, port, uuid, serverToken, target);
                        byte[] data = reply.getBytes(StandardCharsets.UTF_8);
                        DatagramPacket outPkt = new DatagramPacket(data, data.length, pkt.getAddress(), pkt.getPort());
                        try {
                            synchronized (ssdpSendLock) {
                                sock.send(outPkt);
                            }
                        } catch (IOException fallback) {
                            if (replySock != null) replySock.send(outPkt);
                        }
                    }
                } catch (IOException ignored) {}
            }
        } finally {
            if (replySock != null) {
                try { replySock.close(); } catch (RuntimeException ignored) {}
            }
        }
    }

    private void logSearchSample(String srcIp, int srcPort, String st, int count) {
        String key = srcIp + "|" + st;
        long now = System.currentTimeMillis();
        synchronized (searchLogAt) {
            Long prev = searchLogAt.get(key);
            if (prev != null && now - prev < 3000L) return;
            if (searchLogAt.size() > 64) searchLogAt.clear();
            searchLogAt.put(key, now);
        }
        Log.i("MarcoTV", "DLNA M-SEARCH from " + srcIp + ":" + srcPort + " st=" + st + " -> " + count + " replies");
    }

    private static void sendSsdpNotify(MulticastSocket sharedSock, Object sendLock, String ip, int port,
            String uuid, String serverToken, NetworkInterface nif, boolean alive) {
        List<String> messages = CastProtocol.buildSsdpNotifyMessages(ip, port, uuid, serverToken, alive);
        try {
            InetAddress group = InetAddress.getByName(CastProtocol.SSDP_ADDRESS);
            if (alive && sharedSock != null && !sharedSock.isClosed()) {
                try {
                    synchronized (sendLock) {
                        for (String msg : messages) {
                            byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
                            sharedSock.send(new DatagramPacket(bytes, bytes.length, group, CastProtocol.SSDP_PORT));
                        }
                    }
                    return;
                } catch (IOException ignored) {
                    // Fall back to dedicated multicast sender below.
                }
            }
            try (MulticastSocket sock = new MulticastSocket()) {
                sock.setReuseAddress(true);
                if (nif != null) {
                    try { sock.setNetworkInterface(nif); } catch (SocketException ignored) {}
                }
                try { sock.setTimeToLive(4); } catch (IOException ignored) {}
                for (String msg : messages) {
                    byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
                    sock.send(new DatagramPacket(bytes, bytes.length, group, CastProtocol.SSDP_PORT));
                }
            }
        } catch (IOException error) {
            Log.w("MarcoTV", "DLNA SSDP notify failed: " + error.getMessage());
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        mainHandler.removeCallbacks(ssdpAliveTicker);
        unregisterNsd();
        Thread bye = new Thread(() -> sendSsdpNotify(null, ssdpSendLock, ip, port, uuid, serverToken, multicastNif, false), "MarcoDlnaBye");
        bye.setDaemon(true);
        bye.start();
        if (ssdpSocket != null) {
            try { ssdpSocket.close(); } catch (RuntimeException ignored) {}
            ssdpSocket = null;
        }
        try { httpServer.close(); } catch (IOException ignored) {}
        synchronized (sockets) {
            for (Socket s : sockets) {
                try { s.close(); } catch (IOException ignored) {}
            }
            sockets.clear();
        }
        ioPool.shutdownNow();
        if (multicastLock != null) {
            try { if (multicastLock.isHeld()) multicastLock.release(); } catch (RuntimeException ignored) {}
            multicastLock = null;
        }
    }
}
