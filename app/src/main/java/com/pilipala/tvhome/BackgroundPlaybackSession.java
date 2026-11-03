// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

/** Pure playback lifecycle protocol. Not yet wired to Android; caller serializes all events.
 * RELEASE must finish before released(token) is acknowledged. Until then no new
 * PREPARE is issued, even if a surface or owner changes repeatedly.
 */
final class BackgroundPlaybackSession {
    enum Mode { STATIC, AMBIENT, CONTINUOUS }
    enum Owner { DESKTOP, DREAM, PREVIEW }
    enum State { IDLE, PREPARING, STARTING, PLAYING, RELEASING, FAILED }
    enum Action { NONE, PREPARE, START, SHOW_VIDEO, RELEASE }

    static final class Request {
        final Owner owner;
        final String asset;
        final long surfaceGeneration;
        Request(Owner owner, String asset, long surfaceGeneration) {
            if (owner == null || asset == null || !asset.matches("[A-Za-z0-9_.:-]{1,128}") || surfaceGeneration <= 0)
                throw new IllegalArgumentException("Invalid background request");
            this.owner = owner;
            this.asset = asset;
            this.surfaceGeneration = surfaceGeneration;
        }
        boolean same(Request other) {
            return other != null && owner == other.owner && asset.equals(other.asset)
                    && surfaceGeneration == other.surfaceGeneration;
        }
    }
    static final class Command {
        final Action action;
        final long token;
        final Request request;
        Command(Action action, long token, Request request) {
            this.action = action;
            this.token = token;
            this.request = request;
        }
    }

    private Request desired, active, failed;
    private State state = State.IDLE;
    private long token;

    static Owner owner(Mode mode, boolean screenOn, boolean desktopVisible, boolean ambient,
                       boolean dreamVisible, boolean explicitPreview) {
        if (!screenOn || mode == null) return null;
        if (dreamVisible) return mode == Mode.STATIC ? null : Owner.DREAM;
        if (explicitPreview) return Owner.PREVIEW;
        if (desktopVisible && (mode == Mode.CONTINUOUS || mode == Mode.AMBIENT && ambient)) return Owner.DESKTOP;
        return null;
    }
    State state() { return state; }
    boolean showVideo() { return state == State.PLAYING; }
    private Command command(Action action) { return new Command(action, token, active); }

    Command update(Request next) {
        if (desired == null ? next != null : !desired.same(next)) failed = null;
        desired = next;
        if (state == State.RELEASING) return command(Action.NONE);
        if (active != null) {
            if (active.same(next)) return command(Action.NONE);
            state = State.RELEASING;
            return command(Action.RELEASE);
        }
        return begin();
    }
    private Command begin() {
        if (desired == null) {
            state = State.IDLE;
            return command(Action.NONE);
        }
        if (desired.same(failed)) {
            state = State.FAILED;
            return command(Action.NONE);
        }
        active = desired;
        token++;
        state = State.PREPARING;
        return command(Action.PREPARE);
    }
    Command prepared(long callbackToken) {
        if (callbackToken != token || state != State.PREPARING) return command(Action.NONE);
        state = State.STARTING;
        return command(Action.START);
    }
    Command firstFrame(long callbackToken) {
        if (callbackToken != token || state != State.STARTING) return command(Action.NONE);
        state = State.PLAYING;
        return command(Action.SHOW_VIDEO);
    }
    Command failed(long callbackToken) {
        if (callbackToken != token || active == null || state == State.RELEASING) return command(Action.NONE);
        failed = active;
        state = State.RELEASING;
        return command(Action.RELEASE);
    }
    Command released(long callbackToken) {
        if (callbackToken != token || state != State.RELEASING) return command(Action.NONE);
        active = null;
        return begin();
    }
    Command retry() {
        failed = null;
        return state == State.FAILED ? begin() : command(Action.NONE);
    }
}
