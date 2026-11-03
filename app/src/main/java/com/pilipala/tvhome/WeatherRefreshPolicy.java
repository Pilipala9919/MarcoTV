// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

/** Pure city-scoped request gate; network, persistence and provider parsing are separate. */
final class WeatherRefreshPolicy {
    static final long REFRESH_MS = 30 * 60 * 1000L;
    static final long MAX_STALE_MS = 6 * 60 * 60 * 1000L;
    static final long MIN_ATTEMPT_MS = 60 * 1000L;
    enum Freshness { FRESH, STALE, EXPIRED, UNKNOWN }
    private String city;
    private long generation, inFlight, lastAttempt = -1, nextAttempt = -1, nextAutomatic = -1;

    static Freshness freshness(long nowWall, long dataWall) {
        if (nowWall <= 0 || dataWall <= 0 || dataWall > nowWall && dataWall - nowWall > 5 * 60 * 1000L)
            return Freshness.UNKNOWN;
        long age = Math.max(0, nowWall - dataWall);
        return age < REFRESH_MS ? Freshness.FRESH : age <= MAX_STALE_MS ? Freshness.STALE : Freshness.EXPIRED;
    }
    void select(String confirmedCityKey) {
        if (confirmedCityKey != null && !confirmedCityKey.matches("[A-Za-z0-9_.:-]{1,128}"))
            throw new IllegalArgumentException("Invalid city key");
        if (city == null ? confirmedCityKey == null : city.equals(confirmedCityKey)) return;
        city = confirmedCityKey;
        generation++;
        inFlight = 0;
        lastAttempt = nextAttempt = nextAutomatic = -1;
    }
    void cancel() { generation++; inFlight = 0; }

    /** Returns zero if no request is allowed. Times are monotonic within this process. */
    long begin(boolean enabled, boolean visible, boolean online, long nowElapsed,
               boolean matchingFreshCache, boolean manual) {
        if (!enabled || !visible || !online || city == null || inFlight != 0 || nowElapsed < 0) return 0;
        // A clock rollback must not create an unbounded suppression interval.
        if (lastAttempt >= 0 && nowElapsed < lastAttempt) lastAttempt = nextAttempt = nextAutomatic = -1;
        if (nextAttempt >= 0 && nowElapsed < nextAttempt) return 0;
        if (!manual && (matchingFreshCache || nextAutomatic >= 0 && nowElapsed < nextAutomatic)) return 0;
        lastAttempt = nowElapsed;
        nextAttempt = deadline(nowElapsed, MIN_ATTEMPT_MS);
        inFlight = ++generation;
        return inFlight;
    }
    boolean complete(long callbackToken, long nowElapsed, boolean success, long retryAfterMs) {
        if (callbackToken == 0 || callbackToken != inFlight) return false;
        inFlight = 0;
        long delay = success ? MIN_ATTEMPT_MS : Math.max(MIN_ATTEMPT_MS, retryAfterMs);
        nextAttempt = deadline(Math.max(0, nowElapsed), delay);
        if (success) nextAutomatic = deadline(Math.max(0, nowElapsed), REFRESH_MS);
        return true;
    }
    private static long deadline(long now, long delay) {
        return now > Long.MAX_VALUE - delay ? Long.MAX_VALUE : now + delay;
    }
}
