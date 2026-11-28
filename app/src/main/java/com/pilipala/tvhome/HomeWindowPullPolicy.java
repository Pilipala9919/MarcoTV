// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

/**
 * Leading-edge OEM-home window pull controller.
 *
 * Pulls immediately (0 ms) on the first matching OEM launcher notification instead of waiting
 * behind a sliding 60 ms debounce that starves on multi-window OEM launchers. A subsequent
 * notification in the same episode either pulls immediately if the OEM switched to a second
 * launcher activity class, or schedules a single bounded follow-up that is never pushed out
 * repeatedly and is cancelled as soon as Marco TV or any other app comes to the front.
 */
final class HomeWindowPullPolicy {
    static final long COALESCE_MS = 120L;
    static final long FOLLOWUP_DELAY_MS = 60L;
    static final long EPISODE_CAP_MS = 1200L;
    static final int MAX_IMMEDIATE_PULLS = 3;
    static final int MAX_EPISODE_PULLS = 4;

    enum Action {
        PULL_NOW,
        SCHEDULE_FOLLOWUP,
        KEEP_FOLLOWUP,
        IGNORE
    }

    static final class Decision {
        final Action action;
        final long delayMs;
        final boolean newEpisode;

        Decision(Action action, long delayMs, boolean newEpisode) {
            this.action = action;
            this.delayMs = delayMs;
            this.newEpisode = newEpisode;
        }
    }

    private boolean inEpisode;
    private long episodeStartAt = -1L;
    private long lastPullAt = -1L;
    private String lastClassName = "";
    private int pullsInEpisode;
    private boolean followupPending;

    Decision onOriginalHomeWindow(long now, String className) {
        String cls = className == null ? "" : className;
        if (!inEpisode || (episodeStartAt >= 0L && now - episodeStartAt > EPISODE_CAP_MS)) {
            inEpisode = true;
            episodeStartAt = now;
            lastPullAt = now;
            lastClassName = cls;
            pullsInEpisode = 1;
            followupPending = false;
            return new Decision(Action.PULL_NOW, 0L, true);
        }
        if (pullsInEpisode >= MAX_EPISODE_PULLS) {
            return new Decision(Action.IGNORE, 0L, false);
        }
        boolean classChanged = !cls.isEmpty() && !lastClassName.isEmpty() && !cls.equals(lastClassName);
        long sincePull = now - lastPullAt;
        if (sincePull >= COALESCE_MS || (classChanged && pullsInEpisode < MAX_IMMEDIATE_PULLS)) {
            lastPullAt = now;
            if (!cls.isEmpty()) lastClassName = cls;
            pullsInEpisode++;
            followupPending = false;
            return new Decision(Action.PULL_NOW, 0L, false);
        }
        if (!cls.isEmpty()) lastClassName = cls;
        if (followupPending) {
            return new Decision(Action.KEEP_FOLLOWUP, 0L, false);
        }
        followupPending = true;
        long remaining = Math.max(1L, FOLLOWUP_DELAY_MS - Math.max(0L, sincePull));
        return new Decision(Action.SCHEDULE_FOLLOWUP, remaining, false);
    }

    boolean onFollowupDue(long now) {
        if (!followupPending || !inEpisode) {
            followupPending = false;
            return false;
        }
        followupPending = false;
        if (pullsInEpisode >= MAX_EPISODE_PULLS) return false;
        lastPullAt = now;
        pullsInEpisode++;
        return true;
    }

    void onNonOriginalWindow() {
        reset();
    }

    void reset() {
        inEpisode = false;
        episodeStartAt = -1L;
        lastPullAt = -1L;
        lastClassName = "";
        pullsInEpisode = 0;
        followupPending = false;
    }

    boolean isInEpisode() {
        return inEpisode;
    }

    boolean isFollowupPending() {
        return followupPending;
    }

    int pullsInEpisode() {
        return pullsInEpisode;
    }
}
