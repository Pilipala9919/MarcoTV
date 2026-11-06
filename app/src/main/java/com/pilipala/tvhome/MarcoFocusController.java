// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import java.util.EnumSet;

/**
 * G1 domain slice. Own-card focus only; no Android events, sockets or authentication.
 * The HomeView adapter calls each method on the UI
 * thread, mirror local/layout/lifecycle events, and apply a MOVED result atomically
 * to HomeView before yielding that thread. An internal result is not a rendered ACK.
 *
 * Lease epochs are cancellation fences, NEVER network credentials. Only a future
 * reviewed, TV-approved session owner may call beginAuthorizedLease().
 */
final class MarcoFocusController {
    enum Direction { UP, DOWN, LEFT, RIGHT }
    enum Blocker {
        PAUSED, WINDOW_UNFOCUSED, SCREEN_OFF, DIALOG, DRAWER, RECENTS,
        CARD_MOVE, PREVIEW, AMBIENT
    }
    enum Status {
        READY, MOVED, NO_CHANGE, NO_TARGET, UNSUPPORTED_SURFACE, BLOCKED, DESTROYED,
        NO_SESSION, SESSION_EXPIRED, STALE_SESSION, STALE_LAYOUT, STALE_FOCUS,
        COMMAND_EXPIRED, WRONG_CONTROLLER, ALREADY_CONSUMED
    }
    interface Clock { long nowMillis(); }

    static final long MAX_LEASE_MS = 300_000;
    static final long MAX_COMMAND_MS = 1_000;
    static final int MAX_ROWS = 64;
    static final int MAX_CARDS_PER_ROW = 1024;

    static final class Snapshot {
        private final MarcoFocusController owner;
        final long focusRevision, layoutRevision, epoch;
        final int row, column;
        // Occurrence ID, not an app/package identifier; only meaningful in a lease.
        final String focusId;
        final Status availability;
        private final EnumSet<Blocker> blockers;

        private Snapshot(MarcoFocusController owner, Status availability) {
            this.owner = owner;
            this.focusRevision = owner.focusRevision;
            this.layoutRevision = owner.layoutRevision;
            this.epoch = owner.epoch;
            this.row = owner.row;
            this.column = row >= 0 ? owner.selected[row] : -1;
            this.focusId = column < 0 ? null
                    : "e" + epoch + ":l" + layoutRevision + ":r" + row + ":c" + column;
            this.availability = availability;
            this.blockers = owner.blockers.clone();
        }

        boolean isBlockedBy(Blocker blocker) { return blockers.contains(blocker); }
    }

    static final class Command {
        private final Snapshot expected;
        private final Direction direction;
        private final long deadline;
        private boolean consumed;

        private Command(Snapshot expected, Direction direction, long deadline) {
            this.expected = expected;
            this.direction = direction;
            this.deadline = deadline;
        }
    }

    static final class Result {
        final Status status;
        final Snapshot state;
        private Result(Status status, Snapshot state) {
            this.status = status;
            this.state = state;
        }
    }

    private final Thread ownerThread = Thread.currentThread();
    private final Clock clock;
    private final EnumSet<Blocker> blockers = EnumSet.of(
            Blocker.PAUSED, Blocker.WINDOW_UNFOCUSED, Blocker.SCREEN_OFF);
    private int[] sizes = new int[0], selected = new int[0];
    private int row = -1;
    private long focusRevision, layoutRevision, epoch, expiresAt;
    private long lastNow = -1;
    private boolean lease, destroyed;

    MarcoFocusController(Clock clock) {
        if (clock == null) throw new IllegalArgumentException("clock required");
        this.clock = clock;
    }

    /** Replace even when sizes are unchanged: reorder/replacement changes identity. */
    void replaceLayout(int[] rowSizes, int[] rowSelections, int activeRow) {
        checkAlive();
        validate(rowSizes, rowSelections, activeRow);
        sizes = rowSizes.clone();
        selected = rowSelections.clone();
        row = activeRow;
        layoutRevision++;
        focusRevision++;
    }

    /** Call for EVERY local navigation, including boundary presses and ABA changes. */
    void localFocus(int activeRow, int[] rowSelections) {
        checkAlive();
        validate(sizes, rowSelections, activeRow);
        selected = rowSelections.clone();
        row = activeRow;
        focusRevision++;
    }

    /** Any newly blocked surface revokes the lease; clearing it never reauthorizes. */
    void setBlocked(Blocker blocker, boolean blocked) {
        checkAlive();
        if (blocker == null) throw new IllegalArgumentException("blocker required");
        boolean changed = blocked ? blockers.add(blocker) : blockers.remove(blocker);
        if (changed) {
            focusRevision++;
            if (blocked) revoke();
        }
    }

    /** Trusted, process-local precondition: TLS + pin + TV approval already passed. */
    Snapshot beginAuthorizedLease(long durationMillis) {
        checkAlive();
        long now = now();
        long expiry = deadline(now, durationMillis, MAX_LEASE_MS);
        if (surfaceStatus() != Status.READY)
            throw new IllegalStateException("card surface is unavailable");
        epoch++;
        lease = true;
        expiresAt = expiry;
        return snapshotAt(now);
    }

    /** Transport EOF, heartbeat loss, explicit close and revocation share this fence. */
    void disconnect() {
        checkThread();
        if (!destroyed) revoke();
    }

    /** Activity recreation must use a new controller and a newly approved lease. */
    void destroy() {
        checkThread();
        if (!destroyed) {
            destroyed = true;
            revoke();
            focusRevision++;
        }
    }

    Snapshot snapshot() {
        checkThread();
        return snapshotAt(now());
    }

    /** No internal queue. TTL starts at receipt, never when a queued job is executed. */
    Command prepare(Snapshot expected, Direction direction, long ttlMillis) {
        checkAlive();
        if (expected == null || direction == null)
            throw new IllegalArgumentException("snapshot and direction required");
        if (expected.owner != this) throw new IllegalArgumentException("foreign snapshot");
        return new Command(expected, direction, deadline(now(), ttlMillis, MAX_COMMAND_MS));
    }

    /** Recheck at execution, immediately before mutating focus on the owning thread. */
    Command prepareUntil(Snapshot expected, Direction direction, long end) {
        checkAlive();
        if (expected == null || expected.owner != this || direction == null ||
                end > now() + MAX_COMMAND_MS) throw new IllegalArgumentException("invalid grant");
        return new Command(expected, direction, end);
    }

    Result execute(Command command) {
        checkThread();
        if (command == null) throw new IllegalArgumentException("command required");
        if (command.expected.owner != this) return result(Status.WRONG_CONTROLLER, now());
        long now = now();
        if (command.consumed) return result(Status.ALREADY_CONSUMED, now);
        command.consumed = true;
        if (destroyed) return result(Status.DESTROYED, now);
        if (command.expected.epoch != epoch) return result(Status.STALE_SESSION, now);
        if (!lease) return result(Status.NO_SESSION, now);
        if (now >= expiresAt) {
            revoke();
            return result(Status.SESSION_EXPIRED, now);
        }
        if (now >= command.deadline) return result(Status.COMMAND_EXPIRED, now);
        if (command.expected.layoutRevision != layoutRevision)
            return result(Status.STALE_LAYOUT, now);
        if (command.expected.focusRevision != focusRevision)
            return result(Status.STALE_FOCUS, now);
        Status surface = surfaceStatus();
        if (surface != Status.READY) return result(surface, now);

        int nextRow = row, nextColumn = selected[row];
        switch (command.direction) {
            case LEFT: nextColumn = Math.max(0, nextColumn - 1); break;
            case RIGHT: nextColumn = Math.min(sizes[row] - 1, nextColumn + 1); break;
            case UP:
            case DOWN:
                int delta = command.direction == Direction.UP ? -1 : 1;
                int candidate = row + delta;
                while (candidate >= 0 && candidate < sizes.length && sizes[candidate] == 0)
                    candidate += delta;
                if (candidate >= 0 && candidate < sizes.length) {
                    nextRow = candidate;
                    nextColumn = selected[candidate];
                }
                break;
            default: throw new AssertionError("unhandled direction");
        }
        if (nextRow == row && nextColumn == selected[row]) return result(Status.NO_CHANGE, now);
        row = nextRow;
        selected[row] = nextColumn;
        focusRevision++;
        return result(Status.MOVED, now);
    }

    private Status surfaceStatus() {
        if (destroyed) return Status.DESTROYED;
        if (!blockers.isEmpty()) return Status.BLOCKED;
        boolean any = false;
        for (int size : sizes) if (size > 0) { any = true; break; }
        if (!any || (row >= 0 && sizes[row] == 0)) return Status.NO_TARGET;
        if (row < 0) return Status.UNSUPPORTED_SURFACE;
        return Status.READY; // internal availability, not rendered evidence
    }

    private Snapshot snapshotAt(long now) {
        Status status = surfaceStatus();
        if (lease && now >= expiresAt) {
            revoke();
            if (status == Status.READY) status = Status.SESSION_EXPIRED;
        } else if (status == Status.READY && !lease) status = Status.NO_SESSION;
        return new Snapshot(this, status);
    }

    private Result result(Status status, long now) { return new Result(status, snapshotAt(now)); }

    private void revoke() {
        epoch++;
        lease = false;
        expiresAt = 0;
    }

    private long now() {
        long value = clock.nowMillis();
        if (value < 0 || value < lastNow) {
            // Fail permanently closed on a broken adapter clock, not a renewed TTL.
            destroyed = true;
            revoke();
            throw new IllegalStateException("monotonic nonnegative clock required");
        }
        lastNow = value;
        return value;
    }

    private static long deadline(long now, long duration, long max) {
        if (duration <= 0 || duration > max || now > Long.MAX_VALUE - duration)
            throw new IllegalArgumentException("invalid duration");
        return now + duration;
    }

    private static void validate(int[] sizes, int[] selections, int row) {
        if (sizes == null || selections == null || sizes.length != selections.length
                || sizes.length > MAX_ROWS || row < -1 || row >= sizes.length)
            throw new IllegalArgumentException("invalid layout/focus");
        for (int i = 0; i < sizes.length; i++) {
            if (sizes[i] < 0 || sizes[i] > MAX_CARDS_PER_ROW
                    || (sizes[i] == 0 ? selections[i] != -1
                            : selections[i] < 0 || selections[i] >= sizes[i]))
                throw new IllegalArgumentException("invalid row selection");
        }
    }

    private void checkThread() {
        if (Thread.currentThread() != ownerThread)
            throw new IllegalStateException("owning thread required");
    }

    private void checkAlive() {
        checkThread();
        if (destroyed) throw new IllegalStateException("controller destroyed");
    }
}
