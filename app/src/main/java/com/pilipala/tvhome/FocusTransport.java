// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.os.SystemClock;
import java.io.*;
import java.net.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.*;

/** One foreground pairing window. Reader remains available to observe EOF while UI work waits. */
final class FocusTransport {
    interface Events {
        void pending(FocusTransport owner, String channel, String peer);
        void frame(FocusTransport owner, String channel, String[] fields, long received);
        void ended(FocusTransport owner);
    }
    final FocusSession policy;
    final InetAddress address;
    final SSLServerSocket listener;
    private final Events events;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean outstanding = new AtomicBoolean();
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final AtomicReference<SSLSocket> peer = new AtomicReference<>();
    FocusTransport(FocusIdentity id, InetAddress address, FocusSession policy, Events events) throws IOException {
        this.address = address; this.policy = policy; this.events = events;
        if (!(address instanceof Inet4Address) || !address.isSiteLocalAddress()) throw new IOException("LAN_REQUIRED");
        listener = (SSLServerSocket) id.context.getServerSocketFactory().createServerSocket(0, 1, address);
        listener.setEnabledProtocols(new String[]{"TLSv1.2"});
        listener.setEnabledCipherSuites(new String[]{"TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"});
        listener.setSoTimeout(1000);
    }
    void start() { new Thread(this::serve, "marco-focus-reader").start(); }
    private void serve() {
        try {
            while (!stopped.get() && policy.state() == FocusSession.State.WINDOW) {
                SSLSocket socket;
                try { socket = (SSLSocket) listener.accept(); }
                catch (SocketTimeoutException wait) { continue; }
                peer.set(socket);
                if (stopped.get()) break;
                socket.setSoTimeout(5000);
                socket.startHandshake();
                String pair = read(socket, 5000);
                if (!pair.matches("PAIR [a-f0-9]{64}")) break;
                String channel = UUID.randomUUID().toString();
                String answer = policy.pair(channel, pair.substring(5));
                send(socket, answer);
                if (!answer.equals("PENDING")) break;
                events.pending(this, channel, socket.getInetAddress().getHostAddress());
                while (policy.pending(channel)) Thread.sleep(40);
                String sid = policy.grant(channel);
                if (sid == null) break;
                send(socket, "GRANTED " + sid);
                while (!stopped.get()) {
                    String[] fields = read(socket, 15000).split(" ", 4);
                    long received = SystemClock.elapsedRealtime();
                    if (fields.length < 3 || !fields[0].matches("[a-f0-9]{64}")
                            || !fields[1].matches("[1-9][0-9]{0,17}") || !outstanding.compareAndSet(false,true)) break;
                    events.frame(this, channel, fields, received);
                }
                break;
            }
        } catch (IOException | InterruptedException closed) {
            // Every loss of transport ends this grant; no reconnect with the old ticket.
        } finally {
            close();
            closePeer();
        }
    }
    private void closePeer() {
        SSLSocket socket = peer.getAndSet(null);
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }
    void reply(String value, boolean terminal) {
        if (stopped.get()) return;
        try {
            writer.execute(() -> {
                try {
                    outstanding.set(false);
                    SSLSocket socket = peer.get();
                    if (socket != null) send(socket, value);
                } catch (IOException lost) { close(); }
                finally { if (terminal) close(); }
            });
        } catch (RejectedExecutionException closed) { close(); }
    }
    void close() {
        policy.revoke(); // Same monitor as the UI commit. No I/O on caller.
        if (!stopped.compareAndSet(false,true)) return;
        writer.shutdownNow();
        new Thread(() -> {
            try { listener.close(); } catch (IOException ignored) { }
            closePeer();
            events.ended(this);
        }, "marco-focus-close").start();
    }
    private static void send(SSLSocket socket,String s) throws IOException {
        socket.getOutputStream().write((s+"\n").getBytes("US-ASCII"));
        socket.getOutputStream().flush();
    }
    private static String read(SSLSocket socket,int timeout) throws IOException {
        long deadline = SystemClock.elapsedRealtime()+timeout;
        StringBuilder text = new StringBuilder();
        InputStream stream = socket.getInputStream();
        while (true) {
            long left = deadline-SystemClock.elapsedRealtime();
            if (left <= 0) throw new SocketTimeoutException();
            socket.setSoTimeout((int)left);
            int ch = stream.read();
            if (ch == '\n') return text.toString();
            if (ch < 32 || ch > 126 || text.length() >= 4096) throw new IOException("INVALID_FRAME_OR_EOF");
            text.append((char)ch);
        }
    }
}
