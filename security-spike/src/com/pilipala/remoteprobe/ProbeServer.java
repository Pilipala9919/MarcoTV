// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.remoteprobe;

import java.io.*;
import java.net.*;
import java.util.UUID;
import javax.net.ssl.*;

final class ProbeServer implements AutoCloseable {
    interface Observer { void event(String event); void pending(String connection, String peer); }
    private final SessionPolicy policy;
    private final Observer observer;
    private final SSLServerSocket listener;
    private volatile SSLSocket socket;
    private volatile boolean closed;
    private final boolean diagnostic;
    private final long end;
    ProbeServer(DeviceIdentity identity, InetAddress bind, SessionPolicy policy, Observer observer, boolean diagnostic) throws Exception {
        this.policy = policy; this.observer = observer; this.diagnostic = diagnostic;
        if (!bind.isSiteLocalAddress() || bind.isLoopbackAddress() || bind.isAnyLocalAddress()) throw new IOException("LAN_REQUIRED");
        listener = (SSLServerSocket) identity.context.getServerSocketFactory().createServerSocket(0, 1, bind);
        listener.setEnabledProtocols(new String[]{"TLSv1.2"});
        listener.setEnabledCipherSuites(new String[]{"TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"});
        listener.setSoTimeout(1000);
        end = android.os.SystemClock.elapsedRealtime() + (diagnostic ? 60_000 : 450_000);
    }
    int port() { return listener.getLocalPort(); }
    void start() { new Thread(this::serve, "marco-tls-spike").start(); }
    private void serve() {
        try {
            while (!closed && android.os.SystemClock.elapsedRealtime() < end) {
                if (!diagnostic && policy.state() == SessionPolicy.State.CLOSED) break;
                SSLSocket accepted;
                try { accepted = (SSLSocket) listener.accept(); }
                catch (SocketTimeoutException timeout) { continue; }
                socket = accepted;
                if (closed) { accepted.close(); break; }
                String connection = UUID.randomUUID().toString();
                try (SSLSocket current = accepted) {
                    current.setSoTimeout(5000); current.startHandshake();
                    observer.event("TLS_OK " + current.getSession().getProtocol() + " " + current.getSession().getCipherSuite());
                    if (diagnostic) {
                        // No pairing or command parser exists on this path. No application data sent.
                        observer.event("DIAGNOSTIC_NO_APPLICATION_DATA"); continue;
                    }
                    String pair = read(current, 5000);
                    if (!pair.matches("PAIR [a-f0-9]{64}")) { write(current, "BAD_FRAME"); continue; }
                    String result = policy.pair(connection, pair.substring(5));
                    write(current, result); observer.event(result);
                    if (!result.equals("PENDING")) continue;
                    observer.pending(connection, current.getInetAddress().getHostAddress());
                    while (!closed && policy.pending(connection)) Thread.sleep(100);
                    String sid = policy.grant(connection);
                    if (sid == null) { write(current, "DENIED_OR_EXPIRED"); continue; }
                    write(current, "GRANTED " + sid); observer.event("SESSION_GRANTED");
                    while (!closed && policy.grant(connection) != null) {
                        String frame = read(current, 15_000);
                        String[] fields = frame.split(" ", 4);
                        if (fields.length < 3 || !fields[0].matches("[a-f0-9]{64}")
                                || !fields[1].matches("[1-9][0-9]{0,17}")) throw new IOException("BAD_FRAME");
                        result = policy.command(connection, fields[0], Long.parseLong(fields[1]), fields[2], fields.length == 4 ? fields[3] : "");
                        write(current, result);
                        observer.event(result.split(" ")[0]); // Never log secrets/challenges/session IDs.
                    }
                } catch (Exception error) {
                    observer.event("CONNECTION_CLOSED " + error.getClass().getSimpleName());
                } finally {
                    policy.disconnect(connection); socket = null;
                }
            }
        } catch (Exception error) {
            if (!closed) observer.event("SERVER_ERROR " + error.getClass().getSimpleName());
        } finally { close(); observer.event("LISTENER_CLOSED"); }
    }
    private static String read(SSLSocket socket, int totalMillis) throws IOException {
        long deadline = android.os.SystemClock.elapsedRealtime() + totalMillis;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream in = socket.getInputStream();
        while (true) {
            long left = deadline - android.os.SystemClock.elapsedRealtime();
            if (left <= 0) throw new SocketTimeoutException("FRAME_DEADLINE");
            socket.setSoTimeout((int)left);
            int value = in.read();
            if (value < 0) throw new EOFException();
            if (value == 10) return out.toString("US-ASCII");
            if (value < 32 || value > 126 || out.size() >= 4096) throw new IOException("BAD_FRAME");
            out.write(value);
        }
    }
    private static void write(SSLSocket socket, String value) throws IOException {
        socket.getOutputStream().write((value + "\n").getBytes("US-ASCII"));
        socket.getOutputStream().flush();
    }
    @Override public void close() {
        closed = true; policy.revoke();
        try { listener.close(); } catch (IOException ignored) { }
        SSLSocket current = socket;
        if (current != null) try { current.close(); } catch (IOException ignored) { }
    }
}
