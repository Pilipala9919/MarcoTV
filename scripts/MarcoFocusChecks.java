// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import static com.pilipala.tvhome.MarcoFocusController.*;

/** Dependency-free checks of the production reducer, on host JVM or Android ART. */
public final class MarcoFocusChecks {
    private static int assertions, scenarios, enumerated;
    private static final class FakeClock implements Clock {
        long time = 1000;
        public long nowMillis() { return time; }
    }
    private static final class Fixture {
        final FakeClock clock = new FakeClock();
        final MarcoFocusController c = new MarcoFocusController(clock);
        Fixture() { this(new int[]{3, 0, 2}, new int[]{1, -1, 0}, 0); }
        Fixture(int[] sizes, int[] selected, int row) {
            c.replaceLayout(sizes, selected, row);
            c.setBlocked(Blocker.PAUSED, false);
            c.setBlocked(Blocker.WINDOW_UNFOCUSED, false);
            c.setBlocked(Blocker.SCREEN_OFF, false);
        }
        Fixture lease() { c.beginAuthorizedLease(5000); return this; }
        Command pending(Direction direction) { return c.prepare(c.snapshot(), direction, 100); }
        Result step(Direction direction) { return c.execute(pending(direction)); }
    }
    private interface Check { void run() throws Exception; }
    private static void scenario(String name, Check body) throws Exception {
        body.run(); scenarios++; System.out.println("PASS " + name);
    }
    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static void expect(Class<? extends Throwable> type, Check body) {
        try { body.run(); } catch (Throwable error) {
            require(type.isInstance(error), "expected " + type.getName() + ", got " + error);
            return;
        }
        throw new AssertionError("missing " + type.getName());
    }
    private static void position(Snapshot s, int row, int column) {
        require(s.row == row && s.column == column, "unexpected position " + s.row + "/" + s.column);
    }
    private static void unchanged(Snapshot before, Snapshot after) {
        position(after, before.row, before.column);
        require(before.focusRevision == after.focusRevision, "unexpected focus revision");
        require(before.layoutRevision == after.layoutRevision, "unexpected layout revision");
    }
    private static void rejectedWithoutMovement(Fixture f, Command command, Status expected) {
        Snapshot before = f.c.snapshot();
        Result result = f.c.execute(command);
        require(result.status == expected, "expected " + expected + ", got " + result.status);
        unchanged(before, result.state);
    }

    public static void main(String[] args) throws Exception {
        scenario("closed by default; lease fences are not credentials", () -> {
            FakeClock clock = new FakeClock();
            MarcoFocusController c = new MarcoFocusController(clock);
            c.replaceLayout(new int[]{2}, new int[]{0}, 0);
            Snapshot s = c.snapshot();
            require(s.availability == Status.BLOCKED, "must start blocked");
            for (Blocker b : new Blocker[]{Blocker.PAUSED, Blocker.WINDOW_UNFOCUSED, Blocker.SCREEN_OFF})
                require(s.isBlockedBy(b), "missing default guard");
            expect(IllegalStateException.class, () -> c.beginAuthorizedLease(1000));
            Fixture f = new Fixture();
            require(f.c.snapshot().availability == Status.NO_SESSION, "surface alone cannot authorize");
            rejectedWithoutMovement(f, f.pending(Direction.RIGHT), Status.NO_SESSION);
            require(f.lease().c.snapshot().availability == Status.READY, "explicit domain lease");
        });

        scenario("four directions; saved selection per row; skip empty rows", () -> {
            Fixture f = new Fixture().lease();
            long revision = f.c.snapshot().focusRevision;
            Direction[] steps = {Direction.RIGHT, Direction.DOWN, Direction.RIGHT, Direction.UP,
                    Direction.LEFT, Direction.DOWN};
            int[][] positions = {{0,2},{2,0},{2,1},{0,2},{0,1},{2,1}};
            for (int i = 0; i < steps.length; i++) {
                Result r = f.step(steps[i]);
                require(r.status == Status.MOVED, "move status");
                position(r.state, positions[i][0], positions[i][1]);
                require(r.state.focusRevision == ++revision, "one revision per move");
            }
        });

        scenario("all four boundaries stay in cards and do not revise", () -> {
            Fixture f = new Fixture(new int[]{1}, new int[]{0}, 0).lease();
            Snapshot before = f.c.snapshot();
            for (Direction direction : Direction.values()) {
                require(f.step(direction).status == Status.NO_CHANGE, "boundary");
                unchanged(before, f.c.snapshot());
                require(before.focusId.equals(f.c.snapshot().focusId), "boundary identity");
            }
        });

        scenario("empty layouts, empty active row, and header are unavailable", () -> {
            Fixture empty = new Fixture(new int[0], new int[0], -1);
            require(empty.c.snapshot().availability == Status.NO_TARGET, "empty layout");
            expect(IllegalStateException.class, () -> empty.lease());
            Fixture f = new Fixture().lease();
            f.c.replaceLayout(new int[]{0, 2}, new int[]{-1, 0}, 0);
            rejectedWithoutMovement(f, f.pending(Direction.DOWN), Status.NO_TARGET);
            require(f.c.snapshot().focusId == null, "empty occurrence ID");
            f.c.localFocus(-1, new int[]{-1, 0});
            rejectedWithoutMovement(f, f.pending(Direction.DOWN), Status.UNSUPPORTED_SURFACE);
            require(f.c.snapshot().focusId == null, "header has no card identity");
            f.c.replaceLayout(new int[]{0, 0}, new int[]{-1, -1}, -1);
            rejectedWithoutMovement(f, f.pending(Direction.RIGHT), Status.NO_TARGET);
        });

        scenario("local remote boundary and ABA both invalidate queued snapshots", () -> {
            Fixture f = new Fixture().lease();
            Command same = f.pending(Direction.RIGHT);
            f.c.localFocus(0, new int[]{1, -1, 0});
            rejectedWithoutMovement(f, same, Status.STALE_FOCUS);
            Command aba = f.pending(Direction.RIGHT);
            f.c.localFocus(0, new int[]{2, -1, 0});
            f.c.localFocus(0, new int[]{1, -1, 0});
            rejectedWithoutMovement(f, aba, Status.STALE_FOCUS);
            require(f.step(Direction.RIGHT).status == Status.MOVED, "fresh snapshot after local input");
        });

        scenario("layout shrink, same-size reorder, removal, and restore cancel old commands", () -> {
            Fixture f = new Fixture().lease();
            int[][] shapes = {{3,0,2}, {1}, {}, {3,0,2}};
            int[][] selections = {{1,-1,0}, {0}, {}, {1,-1,0}};
            for (int i = 0; i < shapes.length; i++) {
                Command old = f.pending(Direction.RIGHT);
                Snapshot before = f.c.snapshot();
                f.c.replaceLayout(shapes[i], selections[i], shapes[i].length == 0 ? -1 : 0);
                Snapshot after = f.c.snapshot();
                require(after.layoutRevision == before.layoutRevision + 1, "layout event revision");
                require(after.focusRevision == before.focusRevision + 1, "layout invalidates focus");
                rejectedWithoutMovement(f, old, Status.STALE_LAYOUT);
            }
        });

        scenario("every surface blocker cancels queued commands; clear requires fresh lease", () -> {
            for (Blocker blocker : Blocker.values()) {
                Fixture f = new Fixture().lease();
                Command old = f.pending(Direction.RIGHT);
                Snapshot oldSnapshot = f.c.snapshot();
                f.c.setBlocked(blocker, true);
                require(f.c.snapshot().availability == Status.BLOCKED, "blocked " + blocker);
                require(!oldSnapshot.isBlockedBy(blocker), "snapshot must remain immutable");
                rejectedWithoutMovement(f, old, Status.STALE_SESSION);
                expect(IllegalStateException.class, () -> f.lease());
                long revision = f.c.snapshot().focusRevision;
                f.c.setBlocked(blocker, true);
                require(f.c.snapshot().focusRevision == revision, "duplicate blocker is idempotent");
                Command whileBlocked = f.pending(Direction.RIGHT);
                f.c.setBlocked(blocker, false);
                require(f.c.snapshot().availability == Status.NO_SESSION, "no implicit reauthorization");
                rejectedWithoutMovement(f, whileBlocked, Status.NO_SESSION);
                f.lease();
                require(f.step(Direction.RIGHT).status == Status.MOVED, "new approved lease");
            }
        });

        scenario("overlapping dialogs and window loss cannot clear each other", () -> {
            Fixture f = new Fixture().lease();
            f.c.setBlocked(Blocker.DIALOG, true);
            f.c.setBlocked(Blocker.WINDOW_UNFOCUSED, true);
            f.c.setBlocked(Blocker.DIALOG, false);
            require(f.c.snapshot().availability == Status.BLOCKED, "window guard survives dialog close");
            require(f.c.snapshot().isBlockedBy(Blocker.WINDOW_UNFOCUSED), "remaining guard");
            expect(IllegalStateException.class, () -> f.lease());
        });

        scenario("disconnect, reconnect and lease replacement never replay old input", () -> {
            Fixture f = new Fixture().lease();
            Command old = f.pending(Direction.RIGHT);
            f.c.disconnect();
            rejectedWithoutMovement(f, old, Status.STALE_SESSION);
            rejectedWithoutMovement(f, f.pending(Direction.RIGHT), Status.NO_SESSION);
            Command disconnected = f.pending(Direction.RIGHT);
            f.lease();
            rejectedWithoutMovement(f, disconnected, Status.STALE_SESSION);
            Command replaced = f.pending(Direction.RIGHT);
            f.lease();
            rejectedWithoutMovement(f, replaced, Status.STALE_SESSION);
            require(f.step(Direction.RIGHT).status == Status.MOVED, "fresh command after reapproval");
        });

        scenario("lease expiry at exact deadline and snapshot-triggered expiry", () -> {
            Fixture f = new Fixture();
            f.c.beginAuthorizedLease(50);
            Command old = f.pending(Direction.RIGHT);
            f.clock.time += 50;
            Result r = f.c.execute(old); // no snapshot before execute: exercises execute-time expiry
            require(r.status == Status.SESSION_EXPIRED, "exact lease deadline");
            position(r.state, 0, 1);
            require(r.state.availability == Status.NO_SESSION, "expired lease revoked");
            f.c.beginAuthorizedLease(50);
            Command old2 = f.pending(Direction.RIGHT);
            f.clock.time += 50;
            require(f.c.snapshot().availability == Status.SESSION_EXPIRED, "snapshot expiry");
            rejectedWithoutMovement(f, old2, Status.STALE_SESSION);
        });

        scenario("command TTL starts before queue wait and expires exactly at deadline", () -> {
            Fixture f = new Fixture().lease();
            Command expired = f.pending(Direction.RIGHT);
            f.clock.time += 100;
            rejectedWithoutMovement(f, expired, Status.COMMAND_EXPIRED);
            require(f.c.snapshot().availability == Status.READY, "command TTL does not renew/revoke lease");
            Command justInTime = f.pending(Direction.RIGHT);
            f.clock.time += 99;
            require(f.c.execute(justInTime).status == Status.MOVED, "before deadline");
        });

        scenario("single-use commands and concurrent snapshot conflicts", () -> {
            Fixture f = new Fixture().lease();
            Snapshot s = f.c.snapshot();
            Command first = f.c.prepare(s, Direction.RIGHT, 100);
            Command second = f.c.prepare(s, Direction.LEFT, 100);
            require(f.c.execute(first).status == Status.MOVED, "first wins");
            rejectedWithoutMovement(f, first, Status.ALREADY_CONSUMED);
            rejectedWithoutMovement(f, second, Status.STALE_FOCUS);
            rejectedWithoutMovement(f, second, Status.ALREADY_CONSUMED);
        });

        scenario("destroy and recreation never revive commands even at matching revisions", () -> {
            Fixture f = new Fixture().lease();
            Fixture replacement = new Fixture().lease();
            Command old = f.pending(Direction.RIGHT);
            rejectedWithoutMovement(replacement, old, Status.WRONG_CONTROLLER);
            expect(IllegalArgumentException.class, () -> replacement.c.prepare(f.c.snapshot(), Direction.RIGHT, 50));
            f.c.destroy();
            f.c.destroy();
            rejectedWithoutMovement(f, old, Status.DESTROYED);
            require(f.c.snapshot().availability == Status.DESTROYED, "terminal state");
            expect(IllegalStateException.class, () -> f.lease());
            expect(IllegalStateException.class, () -> f.c.setBlocked(Blocker.PAUSED, false));
            expect(IllegalStateException.class, () -> f.c.localFocus(0, new int[]{1,-1,0}));
            expect(IllegalStateException.class, () -> f.c.replaceLayout(new int[]{1},new int[]{0},0));
            expect(IllegalStateException.class, () -> f.pending(Direction.RIGHT));
        });

        scenario("invalid input fails atomically; layout and snapshot ownership are defensive", () -> {
            Fixture f = new Fixture().lease();
            Snapshot before = f.c.snapshot();
            int[][] sizes = {null, {1}, {-1}, {1025}, {0}, {2}, {2}, new int[65]};
            int[][] selections = {{0}, {}, {0}, {0}, {0}, {-1}, {2}, new int[65]};
            for (int i = 0; i < sizes.length; i++) {
                final int n = i;
                expect(IllegalArgumentException.class, () -> f.c.replaceLayout(sizes[n], selections[n], 0));
                unchanged(before, f.c.snapshot());
            }
            expect(IllegalArgumentException.class, () -> f.c.replaceLayout(new int[]{1}, null, 0));
            expect(IllegalArgumentException.class, () -> f.c.replaceLayout(new int[]{1},new int[]{0},-2));
            expect(IllegalArgumentException.class, () -> f.c.replaceLayout(new int[]{1},new int[]{0},1));
            expect(IllegalArgumentException.class, () -> f.c.localFocus(3,new int[]{1,-1,0}));
            expect(IllegalArgumentException.class, () -> f.c.localFocus(0,new int[]{3,-1,0}));
            unchanged(before, f.c.snapshot());
            int[] shape = {2}, chosen = {0};
            f.c.replaceLayout(shape, chosen, 0);
            shape[0] = 0; chosen[0] = 99;
            require(f.step(Direction.RIGHT).status == Status.MOVED, "layout copies arrays");
            int[] local = {0}; f.c.localFocus(0,local); local[0] = 99;
            position(f.c.snapshot(),0,0);
        });

        scenario("nulls, bounds and duration overflow cannot grant or extend a lease", () -> {
            Fixture f = new Fixture();
            expect(IllegalArgumentException.class, () -> new MarcoFocusController(null));
            expect(IllegalArgumentException.class, () -> f.c.setBlocked(null,true));
            expect(IllegalArgumentException.class, () -> f.c.execute(null));
            expect(IllegalArgumentException.class, () -> f.c.prepare(null,Direction.RIGHT,100));
            expect(IllegalArgumentException.class, () -> f.c.prepare(f.c.snapshot(),null,100));
            for (long duration : new long[]{-1,0,MAX_LEASE_MS+1,Long.MAX_VALUE})
                expect(IllegalArgumentException.class, () -> f.c.beginAuthorizedLease(duration));
            require(f.c.snapshot().availability == Status.NO_SESSION, "invalid lease remains closed");
            f.c.beginAuthorizedLease(MAX_LEASE_MS);
            for (long duration : new long[]{-1,0,MAX_COMMAND_MS+1,Long.MAX_VALUE})
                expect(IllegalArgumentException.class, () -> f.c.prepare(f.c.snapshot(),Direction.RIGHT,duration));
            require(f.c.execute(f.c.prepare(f.c.snapshot(),Direction.RIGHT,MAX_COMMAND_MS)).status == Status.MOVED,
                    "maximum command TTL valid");
            Fixture far = new Fixture(); far.clock.time = Long.MAX_VALUE;
            expect(IllegalArgumentException.class, () -> far.c.beginAuthorizedLease(1));
        });

        scenario("clock regression permanently closes the controller", () -> {
            Fixture f = new Fixture().lease();
            Command pending = f.pending(Direction.RIGHT);
            f.clock.time--;
            expect(IllegalStateException.class, () -> f.c.execute(pending));
            f.clock.time = 1001;
            require(f.c.snapshot().availability == Status.DESTROYED, "clock fail closed");
            expect(IllegalStateException.class, () -> f.lease());
            Fixture negative = new Fixture(); negative.clock.time = -1;
            expect(IllegalStateException.class, () -> negative.c.snapshot());
        });

        scenario("all entry points enforce owner thread before any state change", () -> {
            Fixture f = new Fixture().lease();
            Command pending = f.pending(Direction.RIGHT);
            Snapshot before = f.c.snapshot();
            Check[] calls = {() -> f.c.snapshot(), () -> f.c.execute(pending),
                    () -> f.c.prepare(before,Direction.RIGHT,100), () -> f.c.beginAuthorizedLease(100),
                    () -> f.c.localFocus(0,new int[]{1,-1,0}),
                    () -> f.c.replaceLayout(new int[]{1},new int[]{0},0),
                    () -> f.c.setBlocked(Blocker.DIALOG,true), () -> f.c.disconnect(), () -> f.c.destroy()};
            for (Check call : calls) {
                Throwable[] error = {null};
                Thread t = new Thread(() -> { try { call.run(); } catch(Throwable e) { error[0]=e; } });
                t.start(); t.join();
                require(error[0] instanceof IllegalStateException, "cross-thread call rejected");
                unchanged(before, f.c.snapshot());
            }
            require(f.c.execute(pending).status == Status.MOVED, "wrong-thread attempt did not consume command");
        });

        scenario("small-layout enumeration: direction, boundary, revision and scope invariants", () -> {
            // Enumerate all shapes of 1..3 rows, 0..3 cards, all active positions,
            // and first/last remembered selection profiles. This is NOT all possible layouts.
            for (int rows = 1; rows <= 3; rows++) {
                int shapes = 1 << (rows*2);
                for (int code = 0; code < shapes; code++) {
                    int[] shape = new int[rows];
                    for (int i = 0; i < rows; i++) shape[i] = (code >> (i*2)) & 3;
                    for (int active = 0; active < rows; active++) for (int column = 0; column < shape[active]; column++)
                        for (int profile = 0; profile < 2; profile++) for (Direction dir : Direction.values()) {
                            int[] selected = new int[rows];
                            for (int i=0;i<rows;i++) selected[i] = shape[i]==0 ? -1 : profile==0 ? 0 : shape[i]-1;
                            selected[active] = column;
                            Fixture f = new Fixture(shape,selected,active).lease();
                            Snapshot before = f.c.snapshot();
                            Result r = f.step(dir);
                            Snapshot after = r.state;
                            require(after.row>=0 && after.row<rows, "stays inside card rows");
                            require(after.column>=0 && after.column<shape[after.row], "stays inside card columns");
                            require(after.layoutRevision==before.layoutRevision, "step cannot alter layout");
                            boolean moved = after.row!=active || after.column!=column;
                            require(r.status==(moved ? Status.MOVED : Status.NO_CHANGE), "status agrees with state");
                            require(after.focusRevision==before.focusRevision+(moved?1:0), "revision invariant");
                            if (dir==Direction.LEFT || dir==Direction.RIGHT) {
                                require(after.row==active, "horizontal cannot change rows");
                                int delta=after.column-column;
                                require(dir==Direction.LEFT ? delta==0||delta==-1 : delta==0||delta==1, "one horizontal step");
                                boolean edge=dir==Direction.LEFT ? column==0 : column==shape[active]-1;
                                require(moved!=edge, "horizontal progress unless boundary");
                            } else {
                                require(after.column==selected[after.row], "vertical preserves row selection");
                                if(moved) require(dir==Direction.UP ? after.row<active : after.row>active, "vertical sign");
                                int lo=Math.min(active,after.row), hi=Math.max(active,after.row);
                                for(int i=lo+1;i<hi;i++) require(shape[i]==0,"only empty rows skipped");
                                if(!moved) {
                                    for(int i=0;i<rows;i++) if(dir==Direction.UP ? i<active : i>active)
                                        require(shape[i]==0,"cannot stop before a target row");
                                }
                            }
                            require(after.availability==Status.READY, "valid step preserves availability");
                            enumerated++;
                        }
                }
            }
        });
        System.out.println("PASS MarcoFocusChecks: " + scenarios + " scenarios, " + enumerated
                + " enumerated transitions, " + assertions + " assertions");
    }
}
