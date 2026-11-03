// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import static com.pilipala.tvhome.BackgroundPlaybackSession.*;

public final class AmbientWeatherChecks {
    private static int passed;
    private static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
        passed++;
        System.out.println("PASS " + name);
    }
    private static Request request(Owner owner, String asset, long surface) { return new Request(owner, asset, surface); }
    private static void action(String name, Command command, Action expected) { check(name, command.action == expected); }
    public static void main(String[] args) {
        check("screen_off_has_no_owner", owner(Mode.CONTINUOUS, false, true, true, true, true) == null);
        check("invalid_mode_fails_static", owner(null, true, true, true, true, true) == null);
        check("static_desktop_has_no_decoder", owner(Mode.STATIC, true, true, false, false, false) == null);
        check("ambient_mode_desktop_is_static", owner(Mode.AMBIENT, true, true, false, false, false) == null);
        check("ambient_mode_idle_plays", owner(Mode.AMBIENT, true, true, true, false, false) == Owner.DESKTOP);
        check("dream_takes_priority", owner(Mode.CONTINUOUS, true, true, true, true, true) == Owner.DREAM);
        check("explicit_preview_allowed_in_static_mode", owner(Mode.STATIC, true, true, false, false, true) == Owner.PREVIEW);
        check("invisible_desktop_has_no_owner", owner(Mode.CONTINUOUS, true, false, false, false, false) == null);
        BackgroundPlaybackSession session = new BackgroundPlaybackSession();
        Request first = request(Owner.DESKTOP, "local:a", 1), second = request(Owner.DREAM, "local:a", 2);
        Command preparing = session.update(first);
        action("first_request_prepares", preparing, Action.PREPARE);
        check("cover_until_first_frame", !session.showVideo());
        action("same_request_does_not_allocate_twice", session.update(first), Action.NONE);
        action("early_frame_ignored", session.firstFrame(preparing.token), Action.NONE);
        action("prepared_starts", session.prepared(preparing.token), Action.START);
        check("prepared_is_not_first_frame", !session.showVideo());
        action("first_frame_shows_video", session.firstFrame(preparing.token), Action.SHOW_VIDEO);
        check("playing_is_visible", session.showVideo());
        action("owner_change_releases_first", session.update(second), Action.RELEASE);
        check("old_video_hidden_during_release", !session.showVideo());
        action("stale_frame_cannot_reshow", session.firstFrame(preparing.token), Action.NONE);
        Request latest = request(Owner.PREVIEW, "local:b", 3);
        action("coalesce_latest_while_releasing", session.update(latest), Action.NONE);
        action("wrong_release_ack_ignored", session.released(preparing.token + 1), Action.NONE);
        Command next = session.released(preparing.token);
        action("only_after_release_prepare_latest", next, Action.PREPARE);
        check("latest_owner_and_asset", next.request.same(latest) && next.token != preparing.token);
        action("old_prepared_ignored", session.prepared(preparing.token), Action.NONE);
        action("decoder_failure_releases", session.failed(next.token), Action.RELEASE);
        action("failure_release_does_not_loop", session.released(next.token), Action.NONE);
        check("failure_is_latched", session.state() == State.FAILED);
        action("repeated_reconcile_no_retry_loop", session.update(latest), Action.NONE);
        Command retry = session.retry();
        action("explicit_retry_new_generation", retry, Action.PREPARE);
        action("screen_off_during_prepare_releases", session.update(null), Action.RELEASE);
        action("late_prepared_after_screen_off_ignored", session.prepared(retry.token), Action.NONE);
        action("off_release_stays_idle", session.released(retry.token), Action.NONE);
        check("off_is_idle", session.state() == State.IDLE);
        Command resumed = session.update(first);
        Request recreated = request(Owner.DESKTOP, "local:a", 99);
        action("new_surface_for_same_asset_releases", session.update(recreated), Action.RELEASE);
        action("new_surface_waits_for_release", session.released(resumed.token), Action.PREPARE);
        boolean rejected = false;
        try { request(Owner.DESKTOP, "../../private", 1); } catch (IllegalArgumentException e) { rejected = true; }
        check("asset_is_not_file_path", rejected);
        trace();
        weather();
        System.out.println("OK " + passed + " ambient/weather foundation checks");
    }
    private static void trace() {
        BackgroundPlaybackSession s = new BackgroundPlaybackSession();
        java.util.Random random = new java.util.Random(20261103);
        long allocated = 0;
        Command c = s.update(request(Owner.DESKTOP, "a", 1));
        for (int i = 0; i < 2000; i++) {
            if (c.action == Action.PREPARE) {
                if (allocated != 0) throw new AssertionError("overlapping decoder lease");
                allocated = c.token;
            }
            if (c.action == Action.RELEASE && c.token != allocated) throw new AssertionError("wrong lease release");
            int event = random.nextInt(6);
            if (s.state() == State.RELEASING && event < 2) {
                long old = allocated;
                allocated = 0; // Fake backend acknowledges actual disposal, not request-to-dispose.
                c = s.released(old);
            } else if (event == 0) c = s.prepared(allocated);
            else if (event == 1) c = s.firstFrame(allocated);
            else if (event == 2) c = s.failed(allocated);
            else if (event == 3) c = s.update(null);
            else if (event == 4) c = s.update(request(Owner.values()[random.nextInt(3)], "v" + random.nextInt(3), i + 1));
            else c = s.firstFrame(-1);
        }
        check("2000_adversarial_events_keep_single_lease", true);
    }
    private static void weather() {
        WeatherRefreshPolicy w = new WeatherRefreshPolicy();
        check("no_city_no_request", w.begin(true, true, true, 0, false, false) == 0);
        w.select("qweather:city-a");
        check("off_no_request", w.begin(false, true, true, 0, false, false) == 0);
        check("background_no_request", w.begin(true, false, true, 0, false, false) == 0);
        check("offline_no_request", w.begin(true, true, false, 0, false, false) == 0);
        check("fresh_cache_no_automatic_request", w.begin(true, true, true, 0, true, false) == 0);
        long a = w.begin(true, true, true, 0, false, false);
        check("confirmed_city_fetches", a > 0);
        check("duplicate_request_blocked", w.begin(true, true, true, 1, false, true) == 0);
        w.select("qweather:city-b");
        check("old_city_response_rejected", !w.complete(a, 2, true, 0));
        long b = w.begin(true, true, true, 2, false, false);
        check("new_city_request_allowed", b > a);
        check("rate_limit_response_accepted", w.complete(b, 3, false, 120000));
        check("manual_cannot_bypass_retry_after", w.begin(true, true, true, 120002, false, true) == 0);
        long retry = w.begin(true, true, true, 120003, false, true);
        check("retry_deadline_allows_request", retry > b);
        w.cancel();
        check("disabled_or_hidden_callback_rejected_after_cancel", !w.complete(retry, 120004, true, 0));
        w.select(null);
        check("clearing_city_stops_requests", w.begin(true, true, true, 300000, false, true) == 0);
        w.select("qweather:city-c");
        long c = w.begin(true, true, true, 200000, false, false);
        check("success_accepted", w.complete(c, 200001, true, 0));
        check("duplicate_success_ignored", !w.complete(c, 200002, true, 0));
        check("manual_refresh_has_min_interval", w.begin(true, true, true, 200002, true, true) == 0);
        check("old_provider_data_does_not_poll_each_minute", w.begin(true, true, true, 260001, false, false) == 0);
        check("elapsed_clock_rollback_does_not_lock_forever", w.begin(true, true, true, 10, false, false) > 0);
        WeatherRefreshPolicy throttled = new WeatherRefreshPolicy();
        throttled.select("qweather:city-d");
        long limited = throttled.begin(true, true, true, 0, false, false);
        throttled.complete(limited, 1, false, 24 * 60 * 60 * 1000L);
        check("long_retry_after_is_not_shortened", throttled.begin(true, true, true, 7 * 60 * 60 * 1000L, false, true) == 0);
        long now = 100000000;
        check("fresh_data", WeatherRefreshPolicy.freshness(now, now - 1000) == WeatherRefreshPolicy.Freshness.FRESH);
        check("exact_ttl_stale", WeatherRefreshPolicy.freshness(now, now - WeatherRefreshPolicy.REFRESH_MS) == WeatherRefreshPolicy.Freshness.STALE);
        check("six_hours_stale", WeatherRefreshPolicy.freshness(now, now - WeatherRefreshPolicy.MAX_STALE_MS) == WeatherRefreshPolicy.Freshness.STALE);
        check("old_data_expired", WeatherRefreshPolicy.freshness(now, now - WeatherRefreshPolicy.MAX_STALE_MS - 1) == WeatherRefreshPolicy.Freshness.EXPIRED);
        check("missing_data_unknown", WeatherRefreshPolicy.freshness(now, 0) == WeatherRefreshPolicy.Freshness.UNKNOWN);
        check("future_data_unknown", WeatherRefreshPolicy.freshness(now, now + 300001) == WeatherRefreshPolicy.Freshness.UNKNOWN);
        check("small_clock_skew_tolerated", WeatherRefreshPolicy.freshness(now, now + 1000) == WeatherRefreshPolicy.Freshness.FRESH);
    }
}
