// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
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
    private static final int MAX_PER_IP = 6;

    private final Context appContext;
    private final Handler mainHandler;
    private final String ip;
    private final String uuid;
    private final String friendlyName;
    private final String serverToken;
    private final SoapHandler soapHandler;

    private final ServerSocket httpServer;
    private final int port;
    private final ThreadPoolExecutor ioPool;
    private final Set<Socket> sockets = Collections.synchronizedSet(new HashSet<Socket>());
    private final Map<String, Integer> activeByIp = new HashMap<>();
    private MulticastSocket ssdpSocket;
    private WifiManager.MulticastLock multicastLock;
    private volatile boolean closed;
    private volatile boolean ssdpSearchBound;

    private final Runnable ssdpAliveTicker = new Runnable() {
        @Override public void run() {
            if (closed) return;
            announceAlive();
            mainHandler.postDelayed(this, ssdpSearchBound ? 25000L : 15000L);
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

        InetAddress bindAddr = InetAddress.getByName(ip);
        httpServer = new ServerSocket();
        httpServer.setReuseAddress(true);
        httpServer.bind(new InetSocketAddress(bindAddr, 0), 16);
        port = httpServer.getLocalPort();

        ioPool = new ThreadPoolExecutor(2, 6, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(16));

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

        try {
            MulticastSocket ms = new MulticastSocket(null);
            ms.setReuseAddress(true);
            ms.bind(new InetSocketAddress(CastProtocol.SSDP_PORT));
            NetworkInterface nif = NetworkInterface.getByInetAddress(bindAddr);
            InetAddress group = InetAddress.getByName(CastProtocol.SSDP_ADDRESS);
            if (nif != null) ms.joinGroup(new InetSocketAddress(group, CastProtocol.SSDP_PORT), nif);
            else ms.joinGroup(group);
            ssdpSocket = ms;
            ssdpSearchBound = true;
            Thread ssdpThread = new Thread(() -> listenSsdp(ms), "MarcoDlnaSsdp");
            ssdpThread.setDaemon(true);
            ssdpThread.start();
        } catch (IOException portBusy) {
            ssdpSearchBound = false;
            Diagnostics.event("CAST_SSDP_PORT_BUSY");
        }

        mainHandler.post(ssdpAliveTicker);
        mainHandler.postDelayed(this::announceAlive, 350L);
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
            ioPool.execute(() -> sendSsdpNotify(ip, port, uuid, serverToken, true));
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
                writeEmptyStatus(out, 400, "Bad Request");
                return;
            }
            int denied = CastProtocol.checkHttpSecurity(head, ip, port);
            if (denied != 0) {
                writeEmptyStatus(out, denied, statusText(denied));
                return;
            }
            if ("SUBSCRIBE".equals(head.method) || "UNSUBSCRIBE".equals(head.method)) {
                String subReply = "HTTP/1.1 200 OK\r\n"
                        + "SID: uuid:marcotv-event-" + uuid + "\r\n"
                        + "TIMEOUT: Second-1800\r\n"
                        + "Content-Length: 0\r\n"
                        + "Connection: close\r\n\r\n";
                out.write(subReply.getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return;
            }
            if ("GET".equals(head.method)) {
                String xml;
                if (head.path.equals("/dlna/device.xml")) xml = CastProtocol.buildDeviceXml(friendlyName, uuid, ProjectInfo.VERSION);
                else if (head.path.equals("/dlna/avtransport.xml")) xml = CastProtocol.AVTRANSPORT_SCPD;
                else if (head.path.equals("/dlna/rendering.xml")) xml = CastProtocol.RENDERING_SCPD;
                else xml = CastProtocol.CONNECTION_SCPD;
                writeHttpXml(out, 200, xml);
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
            writeHttpXml(out, result.httpStatus, result.xml);
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

    private static void writeHttpXml(OutputStream out, int code, String xml) throws IOException {
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + code + " " + statusText(code) + "\r\n"
                + "Content-Type: text/xml; charset=\"utf-8\"\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "EXT:\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    private void listenSsdp(MulticastSocket sock) {
        byte[] buf = new byte[2048];
        while (!closed && !sock.isClosed()) {
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                sock.receive(pkt);
                String msg = new String(pkt.getData(), pkt.getOffset(), pkt.getLength(), StandardCharsets.UTF_8);
                List<String> targets = CastProtocol.resolveSsdpSearchTargets(msg, uuid);
                for (String target : targets) {
                    String reply = CastProtocol.buildSsdpSearchResponse(ip, port, uuid, serverToken, target);
                    byte[] data = reply.getBytes(StandardCharsets.UTF_8);
                    sock.send(new DatagramPacket(data, data.length, pkt.getAddress(), pkt.getPort()));
                }
            } catch (IOException ignored) {}
        }
    }

    private static void sendSsdpNotify(String ip, int port, String uuid, String serverToken, boolean alive) {
        List<String> messages = CastProtocol.buildSsdpNotifyMessages(ip, port, uuid, serverToken, alive);
        try (DatagramSocket sock = new DatagramSocket()) {
            InetAddress group = InetAddress.getByName(CastProtocol.SSDP_ADDRESS);
            for (String msg : messages) {
                byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
                sock.send(new DatagramPacket(bytes, bytes.length, group, CastProtocol.SSDP_PORT));
            }
        } catch (IOException ignored) {}
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        mainHandler.removeCallbacks(ssdpAliveTicker);
        Thread bye = new Thread(() -> sendSsdpNotify(ip, port, uuid, serverToken, false), "MarcoDlnaBye");
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
