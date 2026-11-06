// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import java.security.MessageDigest;
import java.security.SecureRandom;

/** Serialized security policy. No Android, sockets, real input, or persistent client credentials. */
final class FocusSession {
    public interface Clock { long now(); }
    public enum State { CLOSED, WINDOW, PENDING, ACTIVE }
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private State state = State.CLOSED;
    private byte[] ticketHash;
    private String channel, session, challenge;
    private MarcoFocusController.Snapshot original;
    private long ticketEnd, approvalEnd, sessionEnd, lastActivity, challengeEnd, seq, rateStart;
    private long lastNow = -1;
    private int attempts, rateCount;

    FocusSession(Clock clock) { this.clock = clock; }
    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    private String randomId() { byte[] b = new byte[32]; random.nextBytes(b); return hex(b); }
    private static byte[] hash(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes("US-ASCII")); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private long now() {
        long n = clock.now();
        if (n < 0 || n < lastNow || n > Long.MAX_VALUE - 600_000) {
            revoke(); throw new IllegalStateException("CLOCK_INVALID");
        }
        lastNow = n;
        if ((state == State.WINDOW && n >= ticketEnd)
                || (state == State.PENDING && n >= approvalEnd)
                || (state == State.ACTIVE && (n >= sessionEnd || n - lastActivity >= 15_000))) revoke();
        return n;
    }
    public synchronized String openWindow() {
        revoke(); long n = now(); String ticket = randomId(); ticketHash = hash(ticket);
        ticketEnd = n + 120_000; attempts = 0; state = State.WINDOW;
        return ticket;
    }
    public synchronized String pair(String connection, String ticket) {
        long n = now();
        if (state != State.WINDOW) return "PAIR_CLOSED";
        if (ticket == null || !ticket.matches("[a-f0-9]{64}") || !MessageDigest.isEqual(ticketHash, hash(ticket))) {
            if (++attempts >= 5) revoke();
            return "BAD_TICKET";
        }
        ticketHash = null; channel = connection; approvalEnd = Math.min(ticketEnd, n + 30_000);
        state = State.PENDING; return "PENDING";
    }
    /** Called only from the local TV UI, never from a protocol message. */
    public synchronized boolean approve(String connection) {
        long n = now();
        if (state != State.PENDING || !connection.equals(channel)) return false;
        state = State.ACTIVE; session = randomId(); sessionEnd = n + 300_000;
        lastActivity = n; seq = 0; rateCount = 0; rateStart = n;
        return true;
    }
    public synchronized String grant(String connection) {
        now(); return state == State.ACTIVE && connection.equals(channel) ? session : null;
    }
    public synchronized boolean pending(String connection) {
        now(); return state == State.PENDING && connection.equals(channel);
    }
    public synchronized State state() { now(); return state; }
    public synchronized void disconnect(String connection) {
        if (connection.equals(channel)) revoke();
    }
    public synchronized void revoke() {
        original = null; state = State.CLOSED; ticketHash = null; channel = session = challenge = null;
    }

    interface Surface {
        MarcoFocusController.Snapshot snapshot();
        MarcoFocusController.Result move(MarcoFocusController.Snapshot expected,
                MarcoFocusController.Direction direction, long absoluteDeadline);
    }

    // One instance per local window. The monitor is the commit/revoke ordering point.
    // No socket I/O or wait occurs inside it. Called on the owning UI thread.
    synchronized String frame(String connection, String sid, long next, String verb,
            String argument, long received, Surface surface) {
        long at = now();
        if (state != State.ACTIVE || !connection.equals(channel) || !session.equals(sid))
            return "UNAUTHORIZED";
        if (next != seq + 1) { revoke(); return "REPLAY"; }
        seq = next;
        if (at - rateStart >= 1000) { rateStart = at; rateCount = 0; }
        if (++rateCount > 10) { revoke(); return "RATE_LIMIT"; }
        lastActivity = at;
        if (verb.equals("CLOSE") && argument.isEmpty()) { revoke(); return "CLOSED"; }
        if (verb.equals("PING") && argument.isEmpty()) return "PONG";
        if (received > at || at - received >= MarcoFocusController.MAX_COMMAND_MS) {
            original = null; return "COMMAND_EXPIRED";
        }
        if (verb.equals("STATE") && argument.isEmpty()) {
            original = surface.snapshot();
            if (original.availability != MarcoFocusController.Status.READY) {
                revoke(); return "BLOCKED";
            }
            challenge = randomId();
            challengeEnd = Math.min(sessionEnd, received + MarcoFocusController.MAX_COMMAND_MS);
            return "STATE " + challenge + " " + position(original);
        }
        if (!verb.equals("STEP")) return "BAD_COMMAND";
        String[] parts = argument.split(" ", -1);
        if (parts.length != 2 || !parts[1].matches("LEFT|RIGHT|UP|DOWN")) return "BAD_COMMAND";
        MarcoFocusController.Snapshot expected = original;
        original = null;
        if (expected == null || !parts[0].equals(challenge) || at >= challengeEnd)
            return "STALE_CHALLENGE";
        MarcoFocusController.Result result = surface.move(expected,
                MarcoFocusController.Direction.valueOf(parts[1]), challengeEnd);
        return result.status.name() + " " + position(result.state);
    }
    private static String position(MarcoFocusController.Snapshot s) {
        return s.epoch + " " + s.layoutRevision + " " + s.focusRevision + " " + s.row + " " + s.column;
    }
}
