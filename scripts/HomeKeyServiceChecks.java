// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import java.util.*;

public final class HomeKeyServiceChecks {
    static int passed;

    static void check(String name, boolean value) {
        if (!value) throw new AssertionError(name);
        passed++;
        System.out.println("PASS " + name);
    }

    static ResolveInfo homeEntry(String pkg, String cls) {
        ResolveInfo r = new ResolveInfo();
        r.activityInfo = new ActivityInfo();
        r.activityInfo.packageName = pkg;
        r.activityInfo.name = cls;
        r.activityInfo.enabled = true;
        r.activityInfo.exported = true;
        r.activityInfo.applicationInfo.enabled = true;
        return r;
    }

    static HomeKeyService freshService() {
        BuildEdition.RESTRICTED = false;
        Build.MANUFACTURER = "Xiaomi";
        Build.BRAND = "Xiaomi";
        Build.MODEL = "MiTV-ASTP0";
        Build.DISPLAY = "22.8.30.1385";
        Build.VERSION.SDK_INT = 28;
        Build.VERSION.RELEASE = "9";
        SystemClock.elapsed = 20000L;
        Handler.clearDelayed();
        HomeSetup.bootIdOverride = "boot-uuid-1";
        HomeSetup.resetProcessSessionForTest();
        SystemIntegration.resetRebindStateForTest();
        Settings.Secure.failNextPutStrings = 0;
        Settings.Secure.failNextPutInts = 0;
        Diagnostics.events.clear();
        Settings.Global.ints.clear();
        Settings.Secure.ints.clear();
        Settings.Secure.strings.clear();
        Settings.Global.ints.put("boot_count", 4);
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                "com.xiaomi.voicecontrol/.VoiceAccessibilityService:" + HomeSetup.SERVICE);

        HomeKeyService service = new HomeKeyService();
        service.pm.homes.add(homeEntry("com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode"));
        service.pm.homes.add(homeEntry("com.pilipala.tvhome", "com.pilipala.tvhome.HomeActivity"));
        service.pm.currentHome = "com.mitv.tvhome/com.mitv.tvhome.MainActivityUserMode";
        service.sysPrefs.edit()
                .putBoolean("home_compat", true)
                .putString("original_home", "com.mitv.tvhome/com.mitv.tvhome.MainActivityUserMode")
                .commit();
        service.homePrefs.edit()
                .putBoolean("recent_enabled", true)
                .putInt("recent_double_ms", 400)
                .putBoolean("recent_wake", true)
                .commit();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        // Simulate Android 9 carrying over stale key-filter flag from an older install.
        info.flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS | 1;
        info.capabilities = 8;
        service.serviceInfo = info;
        return service;
    }

    public static void main(String[] args) {
        HomeKeyService service = freshService();
        service.onServiceConnected();
        check("connect_default_window_mode_clears_stale_key_filter_bit",
                HomeKeyService.connected
                && !HomeKeyService.runtimeKeyFilterRequested
                && HomeKeyService.runtimeFlags == 1
                && service.serviceInfo.flags == 1
                && service.registeredReceivers.size() == 1);

        check("window_mode_passes_volume_dpad_and_home_keys",
                !service.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
                && !service.onKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP))
                && !service.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN))
                && !service.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
                && !service.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME))
                && !service.onKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME))
                && service.startedActivities.isEmpty());

        // OEM launcher window triggers 60ms window pullback to Marco desktop.
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode"));
        SystemClock.elapsed += 30;
        Handler.runDue();
        check("window_pullback_coalesces_before_60ms", service.startedActivities.isEmpty());
        SystemClock.elapsed += 35;
        Handler.runDue();
        check("window_pullback_returns_to_marco_within_60ms",
                service.startedActivities.size() == 1
                && !service.startedActivities.get(0).getBooleanExtra("recent_apps", false)
                && Diagnostics.events.contains("HOME_WINDOW_FALLBACK"));

        // If Marco appears before 60ms, pending fallback is cancelled without duplicate launch.
        service.startedActivities.clear();
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode"));
        SystemClock.elapsed += 20;
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.pilipala.tvhome", "com.pilipala.tvhome.MainActivity"));
        SystemClock.elapsed += 80;
        Handler.runDue();
        check("window_pullback_cancelled_when_marco_appears", service.startedActivities.isEmpty());

        // Short-press HOME broadcast returns desktop; 2nd press within double-tap window opens Recents;
        // trailing OEM launcher window within 800ms pulls back while preserving recent_apps=true.
        service.startedActivities.clear();
        Diagnostics.events.clear();
        SystemClock.elapsed += 1000;
        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        check("homekey_broadcast_returns_desktop",
                service.startedActivities.size() == 1
                && !service.startedActivities.get(0).getBooleanExtra("recent_apps", true)
                && Diagnostics.events.contains("HOME_BROADCAST_HANDLED"));

        SystemClock.elapsed += 180;
        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        check("homekey_double_broadcast_opens_recents",
                service.startedActivities.size() == 2
                && service.startedActivities.get(1).getBooleanExtra("recent_apps", false));

        SystemClock.elapsed += 40;
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode"));
        SystemClock.elapsed += 70;
        Handler.runDue();
        check("trailing_oem_window_after_double_home_preserves_recents",
                service.startedActivities.size() == 3
                && service.startedActivities.get(2).getBooleanExtra("recent_apps", false)
                && service.startedActivities.get(2).getBooleanExtra("home_press_handled", false));

        // OEM recentapps/assist suppresses window pullback for 10s; explicit homekey clears suppression.
        service.startedActivities.clear();
        SystemClock.elapsed += 2000;
        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "recentapps"));
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.RecentsOverlay"));
        SystemClock.elapsed += 100;
        Handler.runDue();
        check("recentapps_suppresses_window_pullback", service.startedActivities.isEmpty());

        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "assist"));
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.VoiceOverlay"));
        SystemClock.elapsed += 100;
        Handler.runDue();
        check("assist_suppresses_window_pullback", service.startedActivities.isEmpty());

        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        check("explicit_homekey_exits_suppressed_overlay",
                service.startedActivities.size() == 1
                && !service.startedActivities.get(0).getBooleanExtra("recent_apps", true));

        // P2 #3B repro: open Assistant -> Home back to Marco -> 300ms later open System Recents ->
        // Marco MUST NOT clobber the newly opened System Recents!
        Diagnostics.events.clear();
        SystemClock.elapsed += 50;
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.pilipala.tvhome", "com.pilipala.tvhome.MainActivity"));
        SystemClock.elapsed += 250;
        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "recentapps"));
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.RecentsOverlay"));
        SystemClock.elapsed += 120;
        Handler.runDue();
        check("p2_3b_assist_then_home_then_300ms_recents_never_clobbered",
                service.startedActivities.size() == 1
                && !Diagnostics.events.contains("HOME_WINDOW_FALLBACK")
                && !Diagnostics.events.contains("HOME_BROADCAST_RETRY"));

        // P2 #3B variant without intermediate Marco window event: assist -> homekey -> 300ms recentapps
        // still honors the new System Recents (assist never arms dismissingRecentAppsUntil).
        service.startedActivities.clear();
        Diagnostics.events.clear();
        SystemClock.elapsed += 15000;
        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "assist"));
        SystemClock.elapsed += 100;
        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        SystemClock.elapsed += 300;
        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "recentapps"));
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.RecentsOverlay"));
        SystemClock.elapsed += 250;
        Handler.runDue();
        check("p2_3b_assist_then_home_then_300ms_recents_without_marco_event_never_clobbered",
                service.startedActivities.size() == 1
                && !Diagnostics.events.contains("HOME_WINDOW_FALLBACK")
                && !Diagnostics.events.contains("HOME_BROADCAST_RETRY"));

        // Exiting an already-open Recents overlay via Home ignores its closing "recentapps" echo (+30ms)
        // while Marco hasn't regained focus yet, and attributes the safety-net retry to COMPAT_BROADCAST.
        service.startedActivities.clear();
        Diagnostics.events.clear();
        SystemClock.elapsed += 15000;
        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "recentapps"));
        SystemClock.elapsed += 100;
        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        SystemClock.elapsed += 30;
        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "recentapps"));
        SystemClock.elapsed += 430;
        Handler.runDue();
        check("trailing_recentapps_after_homekey_still_runs_broadcast_retry",
                service.startedActivities.size() == 2
                && Diagnostics.events.contains("HOME_BROADCAST_RETRY")
                && !Diagnostics.events.contains("HOME_WINDOW_FALLBACK")
                && HomeSetup.SOURCE_COMPAT_BROADCAST.equals(service.sysPrefs.getString("pending_home_source", "")));

        // Once Marco regains focus after exiting Recents, a fresh Recents 300ms later is honored!
        service.startedActivities.clear();
        Diagnostics.events.clear();
        SystemClock.elapsed += 15000;
        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "recentapps"));
        SystemClock.elapsed += 100;
        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        SystemClock.elapsed += 50;
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.pilipala.tvhome", "com.pilipala.tvhome.MainActivity"));
        SystemClock.elapsed += 250;
        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "recentapps"));
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.RecentsOverlay"));
        SystemClock.elapsed += 200;
        Handler.runDue();
        check("p2_3b_recents_home_marco_then_300ms_new_recents_never_clobbered",
                service.startedActivities.size() == 1
                && !Diagnostics.events.contains("HOME_WINDOW_FALLBACK"));

        // Observed OEM launcher window after "homekey" cancels retry and runs true WINDOW_FALLBACK.
        service.startedActivities.clear();
        Diagnostics.events.clear();
        SystemClock.elapsed += 15000;
        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        SystemClock.elapsed += 40;
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode"));
        Handler.runDue();
        SystemClock.elapsed += 500;
        Handler.runDue();
        check("observed_oem_window_after_homekey_records_window_fallback_not_retry",
                service.startedActivities.size() == 2
                && Diagnostics.events.contains("HOME_WINDOW_FALLBACK")
                && !Diagnostics.events.contains("HOME_BROADCAST_RETRY")
                && HomeSetup.SOURCE_WINDOW_FALLBACK.equals(service.sysPrefs.getString("pending_home_source", "")));

        // P2 #3A repro: OEM Launcher A -> Marco regains focus -> 40ms later OEM Launcher B appears ->
        // MUST NOT drop the second pullback!
        service.startedActivities.clear();
        Diagnostics.events.clear();
        SystemClock.elapsed += 15000;
        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        SystemClock.elapsed += 10;
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode"));
        Handler.runDue();
        SystemClock.elapsed += 15;
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.pilipala.tvhome", "com.pilipala.tvhome.MainActivity"));
        SystemClock.elapsed += 25; // total 40ms between Launcher A and Launcher B!
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.xiaomi.eetv.litehome", "com.xiaomi.eetv.litehome.Launcher"));
        Handler.runDue();
        check("p2_3a_oem_a_then_marco_then_40ms_oem_b_triggers_second_pullback",
                service.startedActivities.size() == 3);

        // P2 #3A variant: same OEM Launcher A -> Marco regains focus -> 40ms later OEM Launcher A again ->
        // MUST pull back again because Marco already regained focus in between!
        service.startedActivities.clear();
        SystemClock.elapsed += 15000;
        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        SystemClock.elapsed += 10;
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode"));
        Handler.runDue();
        SystemClock.elapsed += 20;
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.pilipala.tvhome", "com.pilipala.tvhome.MainActivity"));
        SystemClock.elapsed += 20; // 40ms between first A and second A!
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode"));
        Handler.runDue();
        check("p2_3a_oem_a_then_marco_then_40ms_oem_a_triggers_second_pullback",
                service.startedActivities.size() == 3);

        // P2 #4 repro A: assistant app window taking foreground 50ms after "homekey" cancels retry.
        service.startedActivities.clear();
        Diagnostics.events.clear();
        SystemClock.elapsed += 15000;
        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        SystemClock.elapsed += 50;
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.xiaomi.voicecontrol", "com.xiaomi.voicecontrol.VoiceActivity"));
        SystemClock.elapsed += 500;
        Handler.runDue();
        check("p2_4_assistant_app_window_at_50ms_cancels_broadcast_retry",
                service.startedActivities.size() == 1
                && !Diagnostics.events.contains("HOME_BROADCAST_RETRY")
                && !Diagnostics.events.contains("HOME_WINDOW_FALLBACK"));

        // P2 #4 repro B: system overlay assistant (SystemUI / MiTV SystemUI) at 50ms after "homekey" cancels retry.
        service.startedActivities.clear();
        Diagnostics.events.clear();
        SystemClock.elapsed += 15000;
        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        SystemClock.elapsed += 50;
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.xiaomi.mitv.systemui", "com.xiaomi.mitv.systemui.AssistantOverlay"));
        SystemClock.elapsed += 500;
        Handler.runDue();
        check("p2_4_system_overlay_assistant_at_50ms_cancels_broadcast_retry",
                service.startedActivities.size() == 1
                && !Diagnostics.events.contains("HOME_BROADCAST_RETRY")
                && !Diagnostics.events.contains("HOME_WINDOW_FALLBACK"));

        // P2 #4 repro C: "assist" broadcast 50ms after "homekey" is NEVER ignored and cancels retry + suppresses pullback.
        service.startedActivities.clear();
        Diagnostics.events.clear();
        SystemClock.elapsed += 15000;
        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        SystemClock.elapsed += 50;
        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "assist"));
        SystemClock.elapsed += 500;
        Handler.runDue();
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode"));
        SystemClock.elapsed += 100;
        Handler.runDue();
        check("p2_4_assist_broadcast_at_50ms_cancels_retry_and_suppresses_pullback",
                service.startedActivities.size() == 1
                && !Diagnostics.events.contains("HOME_BROADCAST_RETRY")
                && !Diagnostics.events.contains("HOME_WINDOW_FALLBACK"));

        // Fresh "recentapps" opened 50ms after normal "homekey" (not dismissing prior recents) also cancels retry.
        service.startedActivities.clear();
        Diagnostics.events.clear();
        SystemClock.elapsed += 15000;
        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        SystemClock.elapsed += 50;
        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "recentapps"));
        SystemClock.elapsed += 500;
        Handler.runDue();
        check("p2_4_fresh_recentapps_at_50ms_cancels_broadcast_retry",
                service.startedActivities.size() == 1
                && !Diagnostics.events.contains("HOME_BROADCAST_RETRY"));

        // When Marco settles promptly after "homekey", follow-up is cancelled without extra launch.
        service.startedActivities.clear();
        SystemClock.elapsed += 15000;
        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        SystemClock.elapsed += 50;
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.pilipala.tvhome", "com.pilipala.tvhome.MainActivity"));
        SystemClock.elapsed += 500;
        Handler.runDue();
        check("homekey_followup_cancelled_when_marco_settles", service.startedActivities.size() == 1);

        // Standby wake recovery works in WINDOW_PULLBACK mode.
        service.startedActivities.clear();
        Diagnostics.events.clear();
        SystemClock.elapsed += 15000;
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode"));
        Handler.clearDelayed();
        service.powerManager.interactive = false;
        service.dispatchBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        SystemClock.elapsed += 5000;
        service.powerManager.interactive = true;
        service.dispatchBroadcast(new Intent(Intent.ACTION_SCREEN_ON));
        SystemClock.elapsed += 950;
        Handler.runDue();
        check("window_mode_retains_standby_wake_recovery",
                service.startedActivities.size() == 1
                && service.startedActivities.get(0).getBooleanExtra("wake_recovery", false)
                && Diagnostics.events.contains("HOME_WAKE_RECOVERY"));

        // Explicit live switch to KEY_FILTER updates runtime flags immediately and persists across reconnect.
        service.startedActivities.clear();
        Diagnostics.events.clear();
        SystemIntegration.setGuardMode(service, HomeSetupPolicy.GuardMode.KEY_FILTER);
        check("live_switch_to_key_filter_sets_runtime_flag",
                HomeKeyService.runtimeKeyFilterRequested
                && (HomeKeyService.runtimeFlags & AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0
                && (service.serviceInfo.flags & AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0);
        check("key_filter_mode_intercepts_home_but_passes_volume_and_dpad",
                !service.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
                && !service.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
                && service.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME))
                && service.onKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME))
                && service.startedActivities.size() == 1
                && Diagnostics.events.contains("HOME_DIRECT_HANDLED"));

        service.onUnbind(new Intent());
        check("unbind_resets_runtime_state",
                !HomeKeyService.connected && !HomeKeyService.runtimeKeyFilterRequested && HomeKeyService.runtimeFlags == 0);
        service.serviceInfo.flags = 1;
        service.onServiceConnected();
        check("reconnect_preserves_persisted_key_filter_mode",
                HomeKeyService.connected
                && HomeKeyService.runtimeKeyFilterRequested
                && (service.serviceInfo.flags & AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0);

        // P1 #2 repro on real HomeKeyService: manual accessibility only (no WRITE_SECURE_SETTINGS),
        // KEY_FILTER selected, then Home compat disabled/paused -> key filter revoked IMMEDIATELY and
        // stays revoked across service reconnect while paused=true or home_compat=false.
        service.secureAuthorized = false;
        SystemIntegration.setGuardMode(service, HomeSetupPolicy.GuardMode.KEY_FILTER);
        check("p1_2_key_filter_active_before_pause",
                HomeKeyService.runtimeKeyFilterRequested
                && (service.serviceInfo.flags & AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0);
        HomeSetup.pause(service, "你已关闭 Home 兼容；重新开启前不会恢复。");
        check("p1_2_pause_immediately_revokes_key_filter_without_secure_settings",
                HomeSetup.paused(service)
                && !service.sysPrefs.getBoolean("home_compat", true)
                && !HomeKeyService.runtimeKeyFilterRequested
                && (service.serviceInfo.flags & AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) == 0
                && !service.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME)));
        service.onUnbind(new Intent());
        service.serviceInfo.flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS | 1;
        service.onServiceConnected();
        check("p1_2_reconnect_while_paused_keeps_key_filter_off",
                HomeKeyService.connected
                && !HomeKeyService.runtimeKeyFilterRequested
                && service.serviceInfo.flags == 1);
        service.sysPrefs.edit().putBoolean("setup_paused", false).putBoolean("home_compat", false).commit();
        service.onUnbind(new Intent());
        service.serviceInfo.flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS | 1;
        service.onServiceConnected();
        check("p1_2_reconnect_with_compat_off_keeps_key_filter_off",
                HomeKeyService.connected
                && !HomeKeyService.runtimeKeyFilterRequested
                && service.serviceInfo.flags == 1);
        // Re-enabling compat restores persisted KEY_FILTER mode cleanly.
        service.sysPrefs.edit().putBoolean("home_compat", true).putBoolean("setup_paused", false).commit();
        HomeKeyService.syncRuntimeKeyFilter(service);
        check("p1_2_reenabling_compat_restores_key_filter_mode",
                HomeKeyService.runtimeKeyFilterRequested
                && (service.serviceInfo.flags & AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0);

        // Live switch back to WINDOW_PULLBACK clears key filter and persists across reconnect.
        SystemIntegration.setGuardMode(service, HomeSetupPolicy.GuardMode.WINDOW_PULLBACK);
        check("live_switch_back_to_window_clears_runtime_flag",
                !HomeKeyService.runtimeKeyFilterRequested
                && (service.serviceInfo.flags & AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) == 0);
        service.onUnbind(new Intent());
        service.serviceInfo.flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS | 1;
        service.onServiceConnected();
        check("reconnect_preserves_persisted_window_mode",
                HomeKeyService.connected
                && !HomeKeyService.runtimeKeyFilterRequested
                && service.serviceInfo.flags == 1);

        // P2 #3 repro on real HomeKeyService: NATIVE_KEYS_ONLY disables key filter, homekey broadcast,
        // window pullback, wake recovery, AND TaskBridgeClient.shouldEnableWake().
        service.startedActivities.clear();
        Diagnostics.events.clear();
        SystemIntegration.setGuardMode(service, HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY);
        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode"));
        SystemClock.elapsed += 200;
        Handler.runDue();
        service.powerManager.interactive = false;
        service.dispatchBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        SystemClock.elapsed += 5000;
        service.powerManager.interactive = true;
        service.dispatchBroadcast(new Intent(Intent.ACTION_SCREEN_ON));
        SystemClock.elapsed += 950;
        Handler.runDue();
        check("p2_3_native_keys_only_disables_filter_broadcast_window_wake_and_bridge",
                !HomeKeyService.runtimeKeyFilterRequested
                && !service.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME))
                && !TaskBridgeClient.shouldEnableWake(service)
                && service.startedActivities.isEmpty());

        // P2 #2 repro on real HomeKeyService: upgrade rebind writes Marco back into Settings.Secure,
        // stays "已写回、等待连接" with revision=1 until real onServiceConnected() applies guard mode.
        HomeKeyService rebindSvc = freshService();
        rebindSvc.secureAuthorized = true;
        rebindSvc.sysPrefs.edit().putInt("home_filter_revision", 1).commit();
        rebindSvc.onServiceConnected();
        SystemIntegration.refreshHomeService(rebindSvc);
        rebindSvc.onUnbind(new Intent());
        Diagnostics.events.clear();
        SystemClock.elapsed += 310;
        Handler.runDue();
        check("p2_2_real_service_written_awaits_connect_before_marking_complete",
                HomeSetup.serviceEnabled(rebindSvc)
                && !HomeKeyService.connected
                && !HomeKeyService.isGuardModeApplied(rebindSvc)
                && rebindSvc.sysPrefs.getInt("home_filter_revision", 0) == 1
                && SystemIntegration.REBIND_STATE_WRITTEN.equals(rebindSvc.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, ""))
                && Diagnostics.events.contains("HOME_SERVICE_REBIND_AWAITING_CONNECT")
                && !Diagnostics.events.contains("HOME_SERVICE_REBOUND")
                && "已写回、等待连接".equals(SystemIntegration.rebindStatusLabel(rebindSvc)));
        rebindSvc.serviceInfo.flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS | 1;
        rebindSvc.onServiceConnected();
        check("p2_2_real_service_connect_applies_mode_and_marks_rebind_complete",
                HomeKeyService.connected
                && HomeKeyService.isGuardModeApplied(rebindSvc)
                && !HomeKeyService.runtimeKeyFilterRequested
                && rebindSvc.serviceInfo.flags == 1
                && rebindSvc.sysPrefs.getInt("home_filter_revision", 0) == SystemIntegration.HOME_FILTER_REVISION
                && rebindSvc.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, "").isEmpty()
                && Diagnostics.events.contains("HOME_SERVICE_REBOUND")
                && "已连接并应用模式".equals(SystemIntegration.rebindStatusLabel(rebindSvc)));
        rebindSvc.onDestroy();

        // Round 5 P2 #3 on real HomeKeyService: old connection stays bound past write-back (delayed onUnbind);
        // MUST NOT complete rebind until a NEW connection arrives AND readback confirms the selected guard mode!
        HomeKeyService oldSvc = freshService();
        oldSvc.secureAuthorized = true;
        oldSvc.sysPrefs.edit().putBoolean("boot_home", true).putInt("home_filter_revision", 1).commit();
        oldSvc.onServiceConnected();
        long oldConnGen = HomeKeyService.connectionGeneration;
        Diagnostics.events.clear();
        SystemIntegration.refreshHomeService(oldSvc);
        // Old instance does NOT receive onUnbind() until AFTER service list is written back at +310ms!
        SystemClock.elapsed += 310;
        Handler.runDue();
        check("r5_p2_real_old_connection_cannot_complete_rebind_on_writeback",
                HomeSetup.serviceEnabled(oldSvc)
                && HomeKeyService.connected
                && HomeKeyService.connectionGeneration == oldConnGen
                && oldSvc.sysPrefs.getInt("home_filter_revision", 0) == 1
                && SystemIntegration.REBIND_STATE_WRITTEN.equals(oldSvc.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, ""))
                && !SystemIntegration.isGuardModeReady(oldSvc)
                && "已写回、等待连接".equals(SystemIntegration.rebindStatusLabel(oldSvc))
                && Diagnostics.events.contains("HOME_SERVICE_REBIND_AWAITING_CONNECT")
                && !Diagnostics.events.contains("HOME_SERVICE_REBOUND"));
        // Delayed unbind of old instance arrives AFTER write-back.
        oldSvc.onUnbind(new Intent());
        check("r5_p2_real_delayed_unbind_keeps_written_state",
                !HomeKeyService.connected
                && oldSvc.sysPrefs.getInt("home_filter_revision", 0) == 1
                && SystemIntegration.REBIND_STATE_WRITTEN.equals(oldSvc.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, "")));

        // New instance 1 connects, but getServiceInfo() readback returns null -> MUST NOT complete!
        HomeKeyService newNullReadback = new HomeKeyService();
        newNullReadback.sysPrefs = oldSvc.sysPrefs;
        newNullReadback.homePrefs = oldSvc.homePrefs;
        newNullReadback.pm = oldSvc.pm;
        newNullReadback.secureAuthorized = true;
        newNullReadback.serviceInfo.flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS | 1;
        newNullReadback.nullServiceInfoAfterSet = true;
        newNullReadback.onServiceConnected();
        check("r5_p2_real_null_readback_cannot_complete_rebind",
                HomeKeyService.connected
                && !HomeKeyService.isGuardModeApplied(newNullReadback)
                && newNullReadback.sysPrefs.getInt("home_filter_revision", 0) == 1
                && SystemIntegration.REBIND_STATE_WRITTEN.equals(newNullReadback.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, ""))
                && !Diagnostics.events.contains("HOME_SERVICE_REBOUND"));
        newNullReadback.onUnbind(new Intent());
        newNullReadback.onDestroy();

        // New instance 2 connects, but setServiceInfo is ignored so readback still has stale key filter -> MUST NOT complete!
        HomeKeyService newStaleFlags = new HomeKeyService();
        newStaleFlags.sysPrefs = oldSvc.sysPrefs;
        newStaleFlags.homePrefs = oldSvc.homePrefs;
        newStaleFlags.pm = oldSvc.pm;
        newStaleFlags.secureAuthorized = true;
        newStaleFlags.serviceInfo.flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS | 1;
        newStaleFlags.ignoreSetServiceInfo = true;
        newStaleFlags.onServiceConnected();
        check("r5_p2_real_mismatched_readback_cannot_complete_rebind",
                HomeKeyService.connected
                && HomeKeyService.runtimeKeyFilterRequested
                && !HomeKeyService.isGuardModeApplied(newStaleFlags)
                && newStaleFlags.sysPrefs.getInt("home_filter_revision", 0) == 1
                && SystemIntegration.REBIND_STATE_WRITTEN.equals(newStaleFlags.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, ""))
                && !Diagnostics.events.contains("HOME_SERVICE_REBOUND"));
        newStaleFlags.onUnbind(new Intent());
        newStaleFlags.onDestroy();

        // New instance 3 connects cleanly and readback verifies WINDOW_PULLBACK (no key filter) -> completes!
        HomeKeyService newOkSvc = new HomeKeyService();
        newOkSvc.sysPrefs = oldSvc.sysPrefs;
        newOkSvc.homePrefs = oldSvc.homePrefs;
        newOkSvc.pm = oldSvc.pm;
        newOkSvc.secureAuthorized = true;
        newOkSvc.serviceInfo.flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS | 1;
        newOkSvc.onServiceConnected();
        check("r5_p2_real_new_connect_with_verified_readback_completes_rebind",
                HomeKeyService.connected
                && HomeKeyService.connectionGeneration > oldConnGen
                && HomeKeyService.isGuardModeApplied(newOkSvc)
                && !HomeKeyService.runtimeKeyFilterRequested
                && newOkSvc.serviceInfo.flags == 1
                && newOkSvc.sysPrefs.getInt("home_filter_revision", 0) == SystemIntegration.HOME_FILTER_REVISION
                && newOkSvc.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, "").isEmpty()
                && SystemIntegration.isGuardModeReady(newOkSvc)
                && "已连接并应用模式".equals(SystemIntegration.rebindStatusLabel(newOkSvc))
                && Diagnostics.events.contains("HOME_SERVICE_REBOUND"));
        // Late destroy/unbind on the dead old instance MUST NOT clobber the active new instance or compat_service_active!
        oldSvc.onUnbind(new Intent());
        oldSvc.onDestroy();
        check("r5_p2_real_late_old_unbind_destroy_never_clobbers_new_instance",
                HomeKeyService.connected
                && HomeKeyService.isGuardModeApplied(newOkSvc)
                && newOkSvc.sysPrefs.getBoolean("compat_service_active", false));
        newOkSvc.onDestroy();

        // Round 5 P2 #2 on real HomeKeyService: process dies BEFORE onUnbind() during rebind ->
        // real startup order (markCompatResetIfInterrupted -> refreshHomeService -> reconcile) recovers cleanly!
        HomeKeyService preUnbindSvc = freshService();
        preUnbindSvc.secureAuthorized = true;
        preUnbindSvc.sysPrefs.edit().putBoolean("boot_home", true).putInt("home_filter_revision", 1).commit();
        preUnbindSvc.onServiceConnected();
        SystemIntegration.refreshHomeService(preUnbindSvc);
        // Simulate abrupt process death BEFORE onUnbind() runs (compat_service_active stays true).
        Handler.clearDelayed();
        SystemIntegration.resetRebindStateForTest();
        HomeKeyService.connected = false;
        HomeKeyService.runtimeModeApplied = false;
        HomeKeyService.runtimeKeyFilterRequested = false;
        HomeKeyService.runtimeFlags = 0;
        Diagnostics.events.clear();
        check("r5_p2_real_pre_unbind_exit_has_active_marker_on_disk",
                preUnbindSvc.sysPrefs.getBoolean("compat_service_active", false)
                && !HomeSetup.serviceEnabled(preUnbindSvc));
        boolean markedReset = HomeSetup.markCompatResetIfInterrupted(preUnbindSvc);
        SystemIntegration.refreshHomeService(preUnbindSvc);
        HomeSetup.reconcile(preUnbindSvc);
        check("r5_p2_real_pre_unbind_exit_startup_order_restores_without_false_reset",
                !markedReset
                && !HomeSetup.compatResetPending(preUnbindSvc)
                && !HomeSetup.paused(preUnbindSvc)
                && preUnbindSvc.sysPrefs.getBoolean("home_compat", false)
                && preUnbindSvc.sysPrefs.getBoolean("boot_home", false)
                && HomeSetup.serviceEnabled(preUnbindSvc)
                && SystemIntegration.REBIND_STATE_WRITTEN.equals(preUnbindSvc.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, ""))
                && preUnbindSvc.sysPrefs.getInt("home_filter_revision", 0) == 1);
        HomeKeyService restartedSvc = new HomeKeyService();
        restartedSvc.sysPrefs = preUnbindSvc.sysPrefs;
        restartedSvc.homePrefs = preUnbindSvc.homePrefs;
        restartedSvc.pm = preUnbindSvc.pm;
        restartedSvc.secureAuthorized = true;
        restartedSvc.serviceInfo.flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS | 1;
        restartedSvc.onServiceConnected();
        check("r5_p2_real_pre_unbind_exit_completes_on_new_service_connected",
                HomeKeyService.connected
                && restartedSvc.sysPrefs.getInt("home_filter_revision", 0) == SystemIntegration.HOME_FILTER_REVISION
                && Diagnostics.events.contains("HOME_SERVICE_REBOUND"));
        restartedSvc.onDestroy();

        service.onDestroy();
        check("destroy_cleans_up_state", !HomeKeyService.connected && !HomeKeyService.runtimeKeyFilterRequested && !HomeKeyService.runtimeModeApplied);
        System.out.println("OK " + passed + " HomeKeyService runtime guard mode checks");
        System.exit(0);
    }
}

class BuildEdition { static boolean RESTRICTED; }
class RecoveryService { static volatile boolean running; }
class AmbientIntegration { static boolean enabled(Context c) { return false; } }
class Diagnostics {
    static final List<String> events = Collections.synchronizedList(new ArrayList<>());
    static void event(String e) { events.add(e); }
}
class TaskBridgeServer { static final String DESCRIPTOR = "com.pilipala.tvhome.TaskBridge.v1"; }
class MainActivity extends android.app.Activity {
    DefaultHomePanel defaultHome;
    HealthPanel health = new HealthPanel();
    SettingsNavigator navigator = new SettingsNavigator();
    boolean isDesktopVisible() { return false; }
}
class DefaultHomePanel {
    void show() {}
    void assistant() {}
    void compatibility() {}
    void restore() {}
}
class HealthPanel { void status() {} }
class SettingsNavigator { void personalization(String key) {} }
class TvDialog {
    static class Builder {
        Builder(Context c) {}
        Builder setPageId(String id) { return this; }
        Builder setTitle(String t) { return this; }
        Builder setMessage(String m) { return this; }
        Builder setOrder(String... k) { return this; }
        Builder setItems(String[] i, android.content.DialogInterface.OnClickListener l) { return this; }
        Builder setPositiveButton(String t, android.content.DialogInterface.OnClickListener l) { return this; }
        Builder setNeutralButton(String t, android.content.DialogInterface.OnClickListener l) { return this; }
        Builder setNegativeButton(String t, android.content.DialogInterface.OnClickListener l) { return this; }
        TvDialog create() { return new TvDialog(); }
    }
}
