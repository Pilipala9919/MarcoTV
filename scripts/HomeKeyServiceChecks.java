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

    static void drainBridgeSync() throws Exception {
        java.lang.reflect.Field f = TaskBridgeClient.class.getDeclaredField("worker");
        f.setAccessible(true);
        java.util.concurrent.ExecutorService ex = (java.util.concurrent.ExecutorService) f.get(null);
        ex.submit(() -> { }).get(5, java.util.concurrent.TimeUnit.SECONDS);
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
        Settings.Secure.throwNextPutInts = 0;
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

        // OEM launcher window pulls immediately (0ms) via HomeWindowPullPolicy; same-class burst
        // within 60ms schedules a bounded follow-up that cancels if Marco appears first, or fires once when due.
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode"));
        check("window_pullback_returns_immediately_at_0ms",
                service.startedActivities.size() == 1
                && !service.startedActivities.get(0).getBooleanExtra("recent_apps", false)
                && (service.startedActivities.get(0).getFlags() & Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0
                && "window_immediate".equals(service.startedActivities.get(0).getStringExtra("home_return_source"))
                && Diagnostics.events.contains("HOME_WINDOW_FALLBACK"));
        SystemClock.elapsed += 20;
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode"));
        SystemClock.elapsed += 45;
        Handler.runDue();
        check("window_pullback_followup_fires_once_when_due", service.startedActivities.size() == 2);

        // If Marco appears before bounded follow-up is due, pending follow-up is cancelled without duplicate launch.
        service.startedActivities.clear();
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.pilipala.tvhome", "com.pilipala.tvhome.MainActivity"));
        SystemClock.elapsed += 100;
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode"));
        SystemClock.elapsed += 20;
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode"));
        SystemClock.elapsed += 10;
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.pilipala.tvhome", "com.pilipala.tvhome.MainActivity"));
        SystemClock.elapsed += 80;
        Handler.runDue();
        check("window_pullback_cancelled_when_marco_appears", service.startedActivities.size() == 1);

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
        SystemClock.elapsed += 15000;
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode"));
        Handler.clearDelayed();
        service.startedActivities.clear();
        Diagnostics.events.clear();
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

        // =========================================================================
        // New Feature Checks: Native HOME gate & 3B No-Bridge 5s Volume Yield
        // =========================================================================
        // 1. Native HOME resolved to Marco -> clears KEY_FILTER bit AND stops extra Home interception!
        TaskBridgeClient.resetForTest();
        HomeKeyService nativeSvc = freshService();
        SystemIntegration.setGuardMode(nativeSvc, HomeSetupPolicy.GuardMode.KEY_FILTER);
        nativeSvc.pm.currentHome = "com.pilipala.tvhome/com.pilipala.tvhome.HomeActivity";
        nativeSvc.onServiceConnected();
        nativeSvc.startedActivities.clear();
        nativeSvc.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        nativeSvc.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode"));
        SystemClock.elapsed += 120;
        Handler.runDue();
        check("native_home_stops_key_filter_direct_home_broadcast_and_pullback",
                !HomeKeyService.runtimeKeyFilterRequested
                && (nativeSvc.serviceInfo.flags & AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) == 0
                && !nativeSvc.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME))
                && nativeSvc.startedActivities.isEmpty()
                && !TaskBridgeClient.shouldEnableWake(nativeSvc)
                && !TaskBridgeClient.shouldEnableVolumeAssist(nativeSvc));
        nativeSvc.onDestroy();

        // 2. 3B No-Bridge Mode (KEY_FILTER without TaskBridge): VOLUME_CHANGED_ACTION yields filter for 5s,
        // Home press during yield STILL returns to Marco, sliding extends timer, and timer restores KEY_FILTER!
        TaskBridgeClient.resetForTest();
        HomeKeyService yieldSvc = freshService();
        yieldSvc.sysPrefs.edit().putInt("home_filter_revision", SystemIntegration.HOME_FILTER_REVISION).commit();
        SystemIntegration.setGuardMode(yieldSvc, HomeSetupPolicy.GuardMode.KEY_FILTER);
        yieldSvc.onServiceConnected();
        Diagnostics.events.clear();
        check("3b_key_filter_starts_enabled_without_bridge",
                HomeKeyService.runtimeKeyFilterRequested && !HomeKeyService.runtimeVolumeYieldActive);

        // Unchanged volume broadcast (prev == cur, e.g. boundary) MUST NOT spuriously trigger yield.
        yieldSvc.dispatchBroadcast(new Intent(HomeKeyService.ACTION_VOLUME_CHANGED)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_TYPE, 3)
                .putExtra(HomeKeyService.EXTRA_PREV_VOLUME_STREAM_VALUE, 100)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_VALUE, 100));
        check("3b_unchanged_volume_broadcast_does_not_yield",
                HomeKeyService.runtimeKeyFilterRequested && !HomeKeyService.runtimeVolumeYieldActive);

        // Codex #4C: Missing prev, missing cur, missing both, missing stream, or unrelated audio stream MUST NOT yield!
        yieldSvc.dispatchBroadcast(new Intent(HomeKeyService.ACTION_VOLUME_CHANGED)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_TYPE, 3)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_VALUE, 31));
        yieldSvc.dispatchBroadcast(new Intent(HomeKeyService.ACTION_VOLUME_CHANGED)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_TYPE, 3)
                .putExtra(HomeKeyService.EXTRA_PREV_VOLUME_STREAM_VALUE, 30));
        yieldSvc.dispatchBroadcast(new Intent(HomeKeyService.ACTION_VOLUME_CHANGED)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_TYPE, 3));
        yieldSvc.dispatchBroadcast(new Intent(HomeKeyService.ACTION_VOLUME_CHANGED)
                .putExtra(HomeKeyService.EXTRA_PREV_VOLUME_STREAM_VALUE, 30)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_VALUE, 31));
        yieldSvc.dispatchBroadcast(new Intent(HomeKeyService.ACTION_VOLUME_CHANGED)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_TYPE, 4) // STREAM_ALARM
                .putExtra(HomeKeyService.EXTRA_PREV_VOLUME_STREAM_VALUE, 2)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_VALUE, 3));
        yieldSvc.dispatchBroadcast(new Intent(HomeKeyService.ACTION_VOLUME_CHANGED)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_TYPE, 5) // STREAM_NOTIFICATION
                .putExtra(HomeKeyService.EXTRA_PREV_VOLUME_STREAM_VALUE, 1)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_VALUE, 2));
        check("3b_missing_extras_or_unrelated_stream_never_triggers_yield",
                HomeKeyService.runtimeKeyFilterRequested
                && !HomeKeyService.runtimeVolumeYieldActive
                && !Diagnostics.events.contains("HOME_KEY_FILTER_YIELDED_FOR_VOLUME"));

        // Real STREAM_MUSIC volume change (30 -> 31) triggers 5s yield (drops FLAG_REQUEST_FILTER_KEY_EVENTS)!
        yieldSvc.dispatchBroadcast(new Intent(HomeKeyService.ACTION_VOLUME_CHANGED)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_TYPE, 3)
                .putExtra(HomeKeyService.EXTRA_PREV_VOLUME_STREAM_VALUE, 30)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_VALUE, 31));
        check("3b_volume_change_drops_key_filter_for_5s_yield",
                !HomeKeyService.runtimeKeyFilterRequested
                && (yieldSvc.serviceInfo.flags & AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) == 0
                && HomeKeyService.runtimeVolumeYieldActive
                && HomeKeyService.isGuardModeApplied(yieldSvc)
                && Diagnostics.events.contains("HOME_KEY_FILTER_YIELDED_FOR_VOLUME"));

        // Pressing Home DURING the 5s yield window STILL returns to Marco via homekey broadcast!
        yieldSvc.startedActivities.clear();
        SystemClock.elapsed += 1500;
        yieldSvc.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        check("3b_home_press_during_yield_still_returns_marco",
                yieldSvc.startedActivities.size() == 1
                && HomeKeyService.runtimeVolumeYieldActive);

        // Unrelated audio stream during active yield MUST NOT extend the 5s timer!
        SystemClock.elapsed += 1500; // t=+3000ms
        yieldSvc.dispatchBroadcast(new Intent(HomeKeyService.ACTION_VOLUME_CHANGED)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_TYPE, 4)
                .putExtra(HomeKeyService.EXTRA_PREV_VOLUME_STREAM_VALUE, 1)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_VALUE, 2));
        // Valid STREAM_MUSIC change at +3000ms slides the 5s yield window forward!
        yieldSvc.dispatchBroadcast(new Intent(HomeKeyService.ACTION_VOLUME_CHANGED)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_TYPE, 3)
                .putExtra(HomeKeyService.EXTRA_PREV_VOLUME_STREAM_VALUE, 31)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_VALUE, 32));
        SystemClock.elapsed += 3000; // 6s since first tap, only 3s since second tap -> still yielded!
        Handler.runDue();
        check("3b_subsequent_volume_change_extends_5s_window",
                HomeKeyService.runtimeVolumeYieldActive && !HomeKeyService.runtimeKeyFilterRequested);

        // After 5s from last volume change -> KEY_FILTER automatically restored!
        SystemClock.elapsed += 2100;
        Handler.runDue();
        check("3b_timer_expiry_restores_key_filter_automatically",
                !HomeKeyService.runtimeVolumeYieldActive
                && HomeKeyService.runtimeKeyFilterRequested
                && (yieldSvc.serviceInfo.flags & AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0
                && Diagnostics.events.contains("HOME_KEY_FILTER_RESTORED_AFTER_VOLUME"));

        // Screen off during yield immediately cancels yield & restores clean state.
        yieldSvc.dispatchBroadcast(new Intent(HomeKeyService.ACTION_VOLUME_CHANGED)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_TYPE, 3)
                .putExtra(HomeKeyService.EXTRA_PREV_VOLUME_STREAM_VALUE, 32)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_VALUE, 33));
        yieldSvc.powerManager.interactive = false;
        yieldSvc.dispatchBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        check("3b_screen_off_cancels_yield_immediately",
                !HomeKeyService.runtimeVolumeYieldActive && HomeKeyService.runtimeKeyFilterRequested);
        yieldSvc.powerManager.interactive = true;
        yieldSvc.dispatchBroadcast(new Intent(Intent.ACTION_SCREEN_ON));
        SystemClock.elapsed += 1000;

        // 3. When 3A bridge volume assist IS active (with audioOk + verified volume devices), 3B yield MUST NOT drop KEY_FILTER!
        final String[] fakeBridgeReply = {
                "{\"ok\":true,\"volumeProtocolVersion\":3,\"volumeAssist\":true,\"volumeAssistActive\":true,\"volumeAudioReady\":true,\"volumeInputDeviceCount\":2,\"volumeAssistReason\":\"ready\"}"
        };
        android.os.IBinder fakeBridge = new android.os.IBinder() {
            @Override public boolean isBinderAlive() { return true; }
            @Override public void linkToDeath(DeathRecipient r, int flags) { }
            @Override public boolean transact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) {
                data.readInterfaceToken();
                data.readString();
                reply.writeNoException();
                reply.writeString(fakeBridgeReply[0]);
                return true;
            }
        };
        int[] uiNotifies = {0};
        TaskBridgeClient.addStateListener(() -> uiNotifies[0]++);
        TaskBridgeClient.attach(yieldSvc, fakeBridge);
        try { TaskBridgeClient.configure(yieldSvc); } catch (Exception e) { throw new RuntimeException(e); }
        yieldSvc.dispatchBroadcast(new Intent(HomeKeyService.ACTION_VOLUME_CHANGED)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_TYPE, 3)
                .putExtra(HomeKeyService.EXTRA_PREV_VOLUME_STREAM_VALUE, 33)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_VALUE, 34));
        check("3a_bridge_assist_active_suppresses_3b_yield",
                TaskBridgeClient.volumeAssistActive()
                && TaskBridgeClient.volumeAudioReady()
                && TaskBridgeClient.volumeInputDeviceCount() == 2
                && HomeKeyService.runtimeKeyFilterRequested
                && !HomeKeyService.runtimeVolumeYieldActive);

        // Codex #2B: Live remote hot-unplug pushed by server immediately disables 3A, notifies UI, and re-enables 3B fallback!
        int beforeHotplug = uiNotifies[0];
        fakeBridgeReply[0] = "{\"ok\":true,\"volumeProtocolVersion\":3,\"volumeAssist\":true,\"volumeAssistActive\":false,\"volumeAudioReady\":true,\"volumeInputDeviceCount\":0,\"volumeAssistReason\":\"no_volume_input_device\"}";
        try {
            TaskBridgeClient.onServerStateChanged(yieldSvc, new org.json.JSONObject(fakeBridgeReply[0]));
        } catch (Exception e) { throw new RuntimeException(e); }
        HomeSetupPolicy.DesktopState afterUnplug = SystemIntegration.desktopState(yieldSvc);
        check("codex_2b_hot_unplug_pushes_state_updates_ui_and_enables_3b",
                !TaskBridgeClient.volumeAssistActive()
                && TaskBridgeClient.volumeInputDeviceCount() == 0
                && "no_volume_input_device".equals(TaskBridgeClient.volumeAssistReason())
                && uiNotifies[0] > beforeHotplug
                && afterUnplug.volumeYieldMode && !afterUnplug.volumeBridgeAssistExpected);
        // Now 3B yield activates while remote is unplugged!
        yieldSvc.dispatchBroadcast(new Intent(HomeKeyService.ACTION_VOLUME_CHANGED)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_TYPE, 3)
                .putExtra(HomeKeyService.EXTRA_PREV_VOLUME_STREAM_VALUE, 34)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_VALUE, 35));
        check("codex_2b_hot_unplug_allows_3b_yield_immediately",
                HomeKeyService.runtimeVolumeYieldActive && !HomeKeyService.runtimeKeyFilterRequested);
        // Expire yield so KEY_FILTER returns, then remote hot-plugs back -> 3A immediately recovers & notifies UI!
        SystemClock.elapsed += 5100;
        Handler.runDue();
        int beforeReplug = uiNotifies[0];
        fakeBridgeReply[0] = "{\"ok\":true,\"volumeProtocolVersion\":3,\"volumeAssist\":true,\"volumeAssistActive\":true,\"volumeAudioReady\":true,\"volumeInputDeviceCount\":1,\"volumeAssistReason\":\"ready\"}";
        try {
            TaskBridgeClient.onServerStateChanged(yieldSvc, new org.json.JSONObject(fakeBridgeReply[0]));
        } catch (Exception e) { throw new RuntimeException(e); }
        check("codex_2b_hot_replug_restores_3a_and_notifies_ui",
                TaskBridgeClient.volumeAssistActive()
                && TaskBridgeClient.volumeInputDeviceCount() == 1
                && uiNotifies[0] > beforeReplug);
        // Runtime audio capability failure pushed by server -> immediately disables 3A and notifies UI!
        int beforeAudioFail = uiNotifies[0];
        fakeBridgeReply[0] = "{\"ok\":true,\"volumeProtocolVersion\":3,\"volumeAssist\":true,\"volumeAssistActive\":false,\"volumeAudioReady\":false,\"volumeInputDeviceCount\":1,\"volumeAssistReason\":\"audio_read_unavailable\"}";
        try {
            TaskBridgeClient.onServerStateChanged(yieldSvc, new org.json.JSONObject(fakeBridgeReply[0]));
        } catch (Exception e) { throw new RuntimeException(e); }
        check("codex_2b_runtime_audio_failure_disables_3a_and_notifies_ui",
                !TaskBridgeClient.volumeAssistActive()
                && !TaskBridgeClient.volumeAudioReady()
                && "audio_read_unavailable".equals(TaskBridgeClient.volumeAssistReason())
                && uiNotifies[0] > beforeAudioFail);
        TaskBridgeClient.resetForTest();
        yieldSvc.onDestroy();

        // Codex #2C: System refuses enabling FLAG_REQUEST_FILTER_KEY_EVENTS ->
        // TaskBridgeClient.shouldEnableVolumeAssist() MUST be false, and desktopState reports failure truthfully!
        HomeKeyService rejectEnableSvc = freshService();
        rejectEnableSvc.sysPrefs.edit().putInt("home_filter_revision", SystemIntegration.HOME_FILTER_REVISION).commit();
        SystemIntegration.setGuardMode(rejectEnableSvc, HomeSetupPolicy.GuardMode.KEY_FILTER);
        rejectEnableSvc.serviceInfo.flags = 1; // filter bit OFF
        rejectEnableSvc.ignoreSetServiceInfo = true; // system ignores request to enable filter
        Diagnostics.events.clear();
        rejectEnableSvc.onServiceConnected();
        HomeSetupPolicy.DesktopState rejectedEnableState = SystemIntegration.desktopState(rejectEnableSvc);
        check("codex_2c_system_refuses_key_filter_blocks_bridge_assist_and_reports_state",
                !HomeKeyService.runtimeKeyFilterRequested
                && !HomeKeyService.runtimeModeApplied
                && !TaskBridgeClient.shouldEnableVolumeAssist(rejectEnableSvc)
                && Diagnostics.events.contains("HOME_KEY_FILTER_APPLY_REJECTED")
                && rejectedEnableState.effectiveModeLabel.contains("系统拒绝开启按键过滤")
                && !rejectedEnableState.volumeBridgeAssistExpected);
        rejectEnableSvc.onDestroy();

        // Codex #2D: System refuses revoking FLAG_REQUEST_FILTER_KEY_EVENTS during 3B yield ->
        // MUST NOT claim yielding, sets runtimeVolumeYieldFailed=true, reports yield unavailable!
        HomeKeyService rejectYieldSvc = freshService();
        rejectYieldSvc.sysPrefs.edit().putInt("home_filter_revision", SystemIntegration.HOME_FILTER_REVISION).commit();
        SystemIntegration.setGuardMode(rejectYieldSvc, HomeSetupPolicy.GuardMode.KEY_FILTER);
        rejectYieldSvc.onServiceConnected();
        check("codex_2d_initial_key_filter_enabled", HomeKeyService.runtimeKeyFilterRequested);
        rejectYieldSvc.ignoreSetServiceInfo = true; // now system refuses to drop filter bit
        Diagnostics.events.clear();
        rejectYieldSvc.dispatchBroadcast(new Intent(HomeKeyService.ACTION_VOLUME_CHANGED)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_TYPE, 3)
                .putExtra(HomeKeyService.EXTRA_PREV_VOLUME_STREAM_VALUE, 20)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_VALUE, 21));
        HomeSetupPolicy.DesktopState rejectedYieldState = SystemIntegration.desktopState(rejectYieldSvc);
        check("codex_2d_system_refuses_yield_revoke_never_claims_yielding",
                HomeKeyService.runtimeKeyFilterRequested
                && !HomeKeyService.runtimeVolumeYieldActive
                && HomeKeyService.runtimeVolumeYieldFailed
                && Diagnostics.events.contains("HOME_KEY_FILTER_YIELD_REJECTED")
                && rejectedYieldState.effectiveModeLabel.contains("系统拒绝临时撤销按键过滤")
                && !rejectedYieldState.effectiveModeLabel.contains("让渡 5 秒中"));
        rejectYieldSvc.onDestroy();

        // Codex #2E: Delayed old HomeKeyService onUnbind()/onDestroy() arriving AFTER new service started
        // and entered 3B yield MUST NOT clear new service's runtimeVolumeYieldActive or connected state!
        HomeKeyService staleOldSvc = freshService();
        staleOldSvc.sysPrefs.edit().putInt("home_filter_revision", SystemIntegration.HOME_FILTER_REVISION).commit();
        SystemIntegration.setGuardMode(staleOldSvc, HomeSetupPolicy.GuardMode.KEY_FILTER);
        staleOldSvc.onServiceConnected();
        HomeKeyService freshNewSvc = new HomeKeyService();
        freshNewSvc.sysPrefs = staleOldSvc.sysPrefs;
        freshNewSvc.homePrefs = staleOldSvc.homePrefs;
        freshNewSvc.pm = staleOldSvc.pm;
        freshNewSvc.serviceInfo.flags = 1;
        freshNewSvc.onServiceConnected();
        freshNewSvc.dispatchBroadcast(new Intent(HomeKeyService.ACTION_VOLUME_CHANGED)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_TYPE, 3)
                .putExtra(HomeKeyService.EXTRA_PREV_VOLUME_STREAM_VALUE, 10)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_VALUE, 11));
        check("codex_2e_new_service_entered_yield", HomeKeyService.connected && HomeKeyService.runtimeVolumeYieldActive);
        // Stale old service unbinds & destroys LATE!
        staleOldSvc.onUnbind(new Intent());
        staleOldSvc.onDestroy();
        check("codex_2e_late_old_unbind_destroy_preserves_new_service_yield_state",
                HomeKeyService.connected
                && HomeKeyService.runtimeVolumeYieldActive
                && !HomeKeyService.runtimeKeyFilterRequested);
        freshNewSvc.onDestroy();

        // 4. WINDOW_PULLBACK and NATIVE_KEYS_ONLY NEVER trigger 3B yield!
        HomeKeyService winSvc = freshService();
        SystemIntegration.setGuardMode(winSvc, HomeSetupPolicy.GuardMode.WINDOW_PULLBACK);
        winSvc.onServiceConnected();
        winSvc.dispatchBroadcast(new Intent(HomeKeyService.ACTION_VOLUME_CHANGED)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_TYPE, 3)
                .putExtra(HomeKeyService.EXTRA_PREV_VOLUME_STREAM_VALUE, 20)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_VALUE, 21));
        check("window_mode_never_triggers_3b_yield", !HomeKeyService.runtimeVolumeYieldActive);
        winSvc.onDestroy();

        // =========================================================================
        // Codex 82c1c8d Rework Regressions:
        // 2B. Overwrite-install old TaskBridge reconnect -> NEVER enables legacy volumeAssist, keeps recents working!
        // =========================================================================
        TaskBridgeClient.resetForTest();
        HomeKeyService oldBridgeSvc = freshService();
        oldBridgeSvc.sysPrefs.edit().putInt("home_filter_revision", SystemIntegration.HOME_FILTER_REVISION).commit();
        SystemIntegration.setGuardMode(oldBridgeSvc, HomeSetupPolicy.GuardMode.KEY_FILTER);
        oldBridgeSvc.onServiceConnected();
        final List<org.json.JSONObject> oldBridgeReqs = Collections.synchronizedList(new ArrayList<>());
        android.os.IBinder oldLegacyBridge = new android.os.IBinder() {
            @Override public boolean isBinderAlive() { return true; }
            @Override public void linkToDeath(DeathRecipient r, int flags) { }
            @Override public boolean transact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) {
                data.readInterfaceToken();
                try {
                    org.json.JSONObject req = new org.json.JSONObject(data.readString());
                    oldBridgeReqs.add(req);
                    String action = req.optString("action");
                    org.json.JSONObject ans = new org.json.JSONObject().put("ok", true);
                    if ("status".equals(action)) {
                        // Legacy bf797b5 / 0.21.2 bridge: NO volumeProtocolVersion, NO volumeAudioReady, claims volumeAssistActive=true!
                        ans.put("volumeAssist", true).put("volumeAssistActive", true);
                    } else if ("list".equals(action)) {
                        ans.put("tasks", new org.json.JSONArray().put(new org.json.JSONObject().put("id", 77).put("pkg", "com.example.app")));
                    }
                    reply.writeNoException();
                    reply.writeString(ans.toString());
                    return true;
                } catch (Exception e) { throw new RuntimeException(e); }
            }
        };
        TaskBridgeClient.attach(oldBridgeSvc, oldLegacyBridge);
        try {
            TaskBridgeClient.configure(oldBridgeSvc);
            org.json.JSONObject listAns = TaskBridgeClient.request("list");
            org.json.JSONObject lastCfg = null;
            for (org.json.JSONObject r : oldBridgeReqs) if ("configure".equals(r.optString("action"))) lastCfg = r;
            check("codex_2b_old_bridge_never_activates_volume_assist_and_keeps_recents",
                    oldBridgeReqs.size() >= 2
                    && "status".equals(oldBridgeReqs.get(0).optString("action"))
                    && lastCfg != null && !lastCfg.optBoolean("volumeAssist", true)
                    && !TaskBridgeClient.volumeAssistActive()
                    && "bridge_update_required".equals(TaskBridgeClient.volumeAssistReason())
                    && listAns.optBoolean("ok") && listAns.optJSONArray("tasks").length() == 1);
        } catch (Exception e) { throw new RuntimeException(e); }
        TaskBridgeClient.resetForTest();
        oldBridgeSvc.onDestroy();

        // 3A. Firmware refuses enabling KEY_FILTER -> homeBroadcast treats mode as window pullback
        // (cancels followup when assist or recentapps arrives 50ms after homekey, NEVER hijacks them!).
        HomeKeyService rejAssistSvc = freshService();
        rejAssistSvc.sysPrefs.edit().putInt("home_filter_revision", SystemIntegration.HOME_FILTER_REVISION).commit();
        SystemIntegration.setGuardMode(rejAssistSvc, HomeSetupPolicy.GuardMode.KEY_FILTER);
        rejAssistSvc.serviceInfo.flags = 1;
        rejAssistSvc.ignoreSetServiceInfo = true;
        rejAssistSvc.onServiceConnected();
        rejAssistSvc.startedActivities.clear();
        Diagnostics.events.clear();
        rejAssistSvc.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        int launchesAfterHome = rejAssistSvc.startedActivities.size();
        SystemClock.elapsed += 50;
        rejAssistSvc.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "assist"));
        SystemClock.elapsed += 500;
        Handler.runDue();
        check("codex_3a_filter_enable_rejected_does_not_hijack_assist",
                !HomeKeyService.runtimeKeyFilterRequested
                && launchesAfterHome == 1
                && rejAssistSvc.startedActivities.size() == 1
                && !Diagnostics.events.contains("HOME_BROADCAST_RETRY"));
        // Same check for recentapps arriving 50ms after homekey!
        rejAssistSvc.startedActivities.clear();
        Diagnostics.events.clear();
        SystemClock.elapsed += 15000;
        rejAssistSvc.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        SystemClock.elapsed += 50;
        rejAssistSvc.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "recentapps"));
        SystemClock.elapsed += 500;
        Handler.runDue();
        check("codex_3a_filter_enable_rejected_does_not_hijack_recentapps",
                rejAssistSvc.startedActivities.size() == 1
                && !Diagnostics.events.contains("HOME_BROADCAST_RETRY"));
        rejAssistSvc.onDestroy();

        // 3B. Firmware refuses revoking KEY_FILTER when switching to WINDOW_PULLBACK, NATIVE_KEYS_ONLY, or pausing ->
        // emits HOME_KEY_FILTER_REVOKE_REJECTED, keeps runtimeKeyFilterRequested==true, warns truthfully!
        HomeKeyService revokeRejSvc = freshService();
        revokeRejSvc.sysPrefs.edit().putInt("home_filter_revision", SystemIntegration.HOME_FILTER_REVISION).commit();
        SystemIntegration.setGuardMode(revokeRejSvc, HomeSetupPolicy.GuardMode.KEY_FILTER);
        revokeRejSvc.onServiceConnected();
        check("codex_3b_setup_filter_initially_on", HomeKeyService.runtimeKeyFilterRequested);
        revokeRejSvc.ignoreSetServiceInfo = true;
        Diagnostics.events.clear();
        SystemIntegration.setGuardMode(revokeRejSvc, HomeSetupPolicy.GuardMode.WINDOW_PULLBACK);
        HomeSetupPolicy.DesktopState winStuck = SystemIntegration.desktopState(revokeRejSvc);
        check("codex_3b_revoke_rejected_on_window_pullback_warns_truthfully",
                HomeKeyService.runtimeKeyFilterRequested
                && Diagnostics.events.contains("HOME_KEY_FILTER_REVOKE_REJECTED")
                && winStuck.keyFilterStillActiveUnexpectedly
                && winStuck.effectiveModeLabel.contains("过滤位仍开启")
                && !winStuck.effectiveModeLabel.contains("音量和方向键交由系统处理"));
        Diagnostics.events.clear();
        SystemIntegration.setGuardMode(revokeRejSvc, HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY);
        HomeSetupPolicy.DesktopState nativeKeysStuck = SystemIntegration.desktopState(revokeRejSvc);
        check("codex_3b_revoke_rejected_on_native_keys_warns_truthfully",
                HomeKeyService.runtimeKeyFilterRequested
                && Diagnostics.events.contains("HOME_KEY_FILTER_REVOKE_REJECTED")
                && nativeKeysStuck.keyFilterStillActiveUnexpectedly
                && !nativeKeysStuck.effectiveModeLabel.contains("音量和方向键交由系统处理"));
        Diagnostics.events.clear();
        HomeSetup.pause(revokeRejSvc, "paused");
        HomeSetupPolicy.DesktopState pausedStuck = SystemIntegration.desktopState(revokeRejSvc);
        check("codex_3b_revoke_rejected_on_pause_warns_truthfully",
                HomeKeyService.runtimeKeyFilterRequested
                && Diagnostics.events.contains("HOME_KEY_FILTER_REVOKE_REJECTED")
                && pausedStuck.keyFilterStillActiveUnexpectedly
                && pausedStuck.effectiveModeLabel.contains("过滤位仍开启"));
        revokeRejSvc.onDestroy();

        // =========================================================================
        // f0983a3 P1 [FaultCycle]: Persistent audio write failure MUST survive 5s no-bridge
        // volume yield expiry, passive reconfigure, and periodic scans; next volume broadcast
        // MUST still enter 3B no-bridge yield; only explicit retry (or confirmed write recovery) restores 3A!
        // =========================================================================
        TaskBridgeClient.resetForTest();
        HomeKeyService faultSvc = freshService();
        faultSvc.sysPrefs.edit().putInt("home_filter_revision", SystemIntegration.HOME_FILTER_REVISION).commit();
        SystemIntegration.setGuardMode(faultSvc, HomeSetupPolicy.GuardMode.KEY_FILTER);
        faultSvc.onServiceConnected();
        final boolean[] serverAssistConfigured = {false};
        final boolean[] serverAudioWriteFailed = {true}; // simulates persistent write denial while readback works
        final List<org.json.JSONObject> faultWireReqs = Collections.synchronizedList(new ArrayList<>());
        android.os.IBinder faultBridge = new android.os.IBinder() {
            @Override public boolean isBinderAlive() { return true; }
            @Override public void linkToDeath(DeathRecipient r, int flags) { }
            @Override public boolean transact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) {
                data.readInterfaceToken();
                try {
                    org.json.JSONObject req = new org.json.JSONObject(data.readString());
                    faultWireReqs.add(req);
                    String action = req.optString("action");
                    org.json.JSONObject ans = new org.json.JSONObject().put("ok", true);
                    if ("configure".equals(action)) {
                        boolean nextAssist = req.optBoolean("volumeAssist", false);
                        boolean explicitRetry = req.optBoolean("retryVolumeAssist", false);
                        serverAssistConfigured[0] = nextAssist;
                        // Mirrors TaskBridgeServer.handle("configure") exact contract:
                        if (nextAssist && explicitRetry) {
                            serverAudioWriteFailed[0] = false;
                        }
                    } else if ("status".equals(action)) {
                        boolean writeFailed = serverAudioWriteFailed[0];
                        boolean active = serverAssistConfigured[0] && !writeFailed;
                        ans.put("volumeProtocolVersion", 3)
                                .put("volumeAssist", serverAssistConfigured[0])
                                .put("volumeAssistActive", active)
                                .put("volumeAudioReady", !writeFailed)
                                .put("volumeAudioWriteFailed", writeFailed)
                                .put("volumeInputReady", active)
                                .put("volumeInputDeviceCount", active ? 2 : 0)
                                .put("volumeAssistReason", !serverAssistConfigured[0] ? "disabled" : (writeFailed ? "audio_adjust_failed" : "ready"));
                    }
                    reply.writeNoException();
                    reply.writeString(ans.toString());
                    return true;
                } catch (Exception e) { throw new RuntimeException(e); }
            }
        };
        TaskBridgeClient.attach(faultSvc, faultBridge);
        try { TaskBridgeClient.configure(faultSvc); } catch (Exception e) { throw new RuntimeException(e); }
        check("f0983a3_p1_fault_cycle_initial_degraded",
                !TaskBridgeClient.volumeAssistActive()
                && TaskBridgeClient.volumeAudioWriteFailed()
                && "audio_adjust_failed".equals(TaskBridgeClient.volumeAssistReason())
                && HomeKeyService.runtimeKeyFilterRequested);
        // Normal STREAM_MUSIC volume broadcast triggers 5s no-bridge yield!
        faultSvc.dispatchBroadcast(new Intent(HomeKeyService.ACTION_VOLUME_CHANGED)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_TYPE, 3)
                .putExtra(HomeKeyService.EXTRA_PREV_VOLUME_STREAM_VALUE, 20)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_VALUE, 21));
        try { TaskBridgeClient.configure(faultSvc); } catch (Exception e) { throw new RuntimeException(e); }
        check("f0983a3_p1_fault_cycle_enters_yield_and_preserves_write_failure",
                HomeKeyService.runtimeVolumeYieldActive
                && !HomeKeyService.runtimeKeyFilterRequested
                && !serverAssistConfigured[0]
                && serverAudioWriteFailed[0]
                && !TaskBridgeClient.volumeAssistActive());
        // 5s yield expires -> passive configure(volumeAssist=true) WITHOUT retryVolumeAssist + periodic scan!
        SystemClock.elapsed += 5100;
        Handler.runDue();
        try { TaskBridgeClient.configure(faultSvc); } catch (Exception e) { throw new RuntimeException(e); }
        // Simulate periodic scan push from server (read ok, write still denied)
        try {
            TaskBridgeClient.onServerStateChanged(faultSvc, faultBridge,
                    new org.json.JSONObject().put("volumeProtocolVersion", 3).put("volumeAssist", true)
                            .put("volumeAssistActive", false).put("volumeAudioReady", false)
                            .put("volumeAudioWriteFailed", true).put("volumeInputDeviceCount", 0)
                            .put("volumeAssistReason", "audio_adjust_failed"));
        } catch (Exception e) { throw new RuntimeException(e); }
        check("f0983a3_p1_fault_cycle_after_yield_expiry_still_degraded",
                !HomeKeyService.runtimeVolumeYieldActive
                && HomeKeyService.runtimeKeyFilterRequested
                && serverAssistConfigured[0]
                && serverAudioWriteFailed[0]
                && !TaskBridgeClient.volumeAssistActive()
                && TaskBridgeClient.volumeAudioWriteFailed()
                && "audio_adjust_failed".equals(TaskBridgeClient.volumeAssistReason()));
        // Next normal volume broadcast MUST STILL fall back to 3B 5s yield!
        faultSvc.dispatchBroadcast(new Intent(HomeKeyService.ACTION_VOLUME_CHANGED)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_TYPE, 3)
                .putExtra(HomeKeyService.EXTRA_PREV_VOLUME_STREAM_VALUE, 21)
                .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_VALUE, 22));
        check("f0983a3_p1_fault_cycle_next_volume_broadcast_still_yields",
                HomeKeyService.runtimeVolumeYieldActive && !HomeKeyService.runtimeKeyFilterRequested);
        // Expire yield, then explicit user retry restores ready!
        SystemClock.elapsed += 5100;
        Handler.runDue();
        try { TaskBridgeClient.configure(faultSvc, true); } catch (Exception e) { throw new RuntimeException(e); }
        check("f0983a3_p1_fault_cycle_explicit_retry_restores_ready",
                !serverAudioWriteFailed[0]
                && TaskBridgeClient.volumeAssistActive()
                && !TaskBridgeClient.volumeAudioWriteFailed()
                && "ready".equals(TaskBridgeClient.volumeAssistReason()));
        TaskBridgeClient.resetForTest();
        faultSvc.onDestroy();

        // =========================================================================
        // f0983a3 P2 [BridgeHandoffReview]: Peer replaced mid-handshake (v2 -> old bridge)
        // Old bridge MUST receive ZERO configure(volumeAssist=true) commands, ends bridge_update_required,
        // and still serves recent tasks ("list") cleanly; stale ONEWAY push from old v2 peer is ignored!
        // =========================================================================
        TaskBridgeClient.resetForTest();
        HomeKeyService handoffSvc = freshService();
        handoffSvc.sysPrefs.edit().putInt("home_filter_revision", SystemIntegration.HOME_FILTER_REVISION).commit();
        SystemIntegration.setGuardMode(handoffSvc, HomeSetupPolicy.GuardMode.KEY_FILTER);
        handoffSvc.onServiceConnected();
        final List<org.json.JSONObject> oldPeerReqs = Collections.synchronizedList(new ArrayList<>());
        final android.os.IBinder oldPeerBridge = new android.os.IBinder() {
            @Override public boolean isBinderAlive() { return true; }
            @Override public void linkToDeath(DeathRecipient r, int flags) { }
            @Override public boolean transact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) {
                data.readInterfaceToken();
                try {
                    org.json.JSONObject req = new org.json.JSONObject(data.readString());
                    oldPeerReqs.add(req);
                    String action = req.optString("action");
                    org.json.JSONObject ans = new org.json.JSONObject().put("ok", true);
                    if ("status".equals(action)) {
                        ans.put("volumeAssist", false).put("volumeAssistActive", false);
                    } else if ("list".equals(action)) {
                        ans.put("tasks", new org.json.JSONArray().put(new org.json.JSONObject().put("id", 99).put("pkg", "com.demo.app")));
                    }
                    reply.writeNoException();
                    reply.writeString(ans.toString());
                    return true;
                } catch (Exception e) { throw new RuntimeException(e); }
            }
        };
        final boolean[] swappedMidHandshake = {false};
        final List<org.json.JSONObject> v2PeerReqs = Collections.synchronizedList(new ArrayList<>());
        final android.os.IBinder v2PeerBridge = new android.os.IBinder() {
            @Override public boolean isBinderAlive() { return true; }
            @Override public void linkToDeath(DeathRecipient r, int flags) { }
            @Override public boolean transact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) {
                data.readInterfaceToken();
                try {
                    org.json.JSONObject req = new org.json.JSONObject(data.readString());
                    v2PeerReqs.add(req);
                    if (!swappedMidHandshake[0] && "status".equals(req.optString("action"))) {
                        swappedMidHandshake[0] = true;
                        // Simulate peer swap RIGHT AFTER v2 preStatus completes, BEFORE configure is sent!
                        TaskBridgeClient.attach(handoffSvc, oldPeerBridge);
                    }
                    org.json.JSONObject ans = new org.json.JSONObject().put("ok", true)
                            .put("volumeProtocolVersion", 3).put("volumeAssist", true).put("volumeAssistActive", true)
                            .put("volumeAudioReady", true).put("volumeInputDeviceCount", 2).put("volumeAssistReason", "ready");
                    reply.writeNoException();
                    reply.writeString(ans.toString());
                    return true;
                } catch (Exception e) { throw new RuntimeException(e); }
            }
        };
        TaskBridgeClient.attach(handoffSvc, v2PeerBridge);
        try {
            TaskBridgeClient.configure(handoffSvc);
            org.json.JSONObject listAns = TaskBridgeClient.request("list");
            int oldTrueCount = 0;
            int oldFalseCount = 0;
            for (org.json.JSONObject r : oldPeerReqs) {
                if ("configure".equals(r.optString("action"))) {
                    if (r.optBoolean("volumeAssist", false)) oldTrueCount++;
                    else oldFalseCount++;
                }
            }
            // Stale ONEWAY state push from already-replaced v2PeerBridge MUST be ignored!
            TaskBridgeClient.onServerStateChanged(handoffSvc, v2PeerBridge,
                    new org.json.JSONObject().put("volumeProtocolVersion", 3).put("volumeAssist", true)
                            .put("volumeAssistActive", true).put("volumeAudioReady", true)
                            .put("volumeInputDeviceCount", 2).put("volumeAssistReason", "ready"));
            check("f0983a3_p2_mid_handshake_peer_swap_never_enables_old_bridge_and_keeps_recents",
                    swappedMidHandshake[0]
                    && v2PeerReqs.size() == 1
                    && oldTrueCount == 0
                    && oldFalseCount == 1
                    && !TaskBridgeClient.volumeAssistActive()
                    && "bridge_update_required".equals(TaskBridgeClient.volumeAssistReason())
                    && listAns.optBoolean("ok") && listAns.optJSONArray("tasks").length() == 1);
        } catch (Exception e) { throw new RuntimeException(e); }
        TaskBridgeClient.resetForTest();
        handoffSvc.onDestroy();

        // =========================================================================
        // e33d5ae P1: f0983a3 v2 bridge (volumeProtocolVersion=2 with !prevAssist bug) MUST be rejected
        // across ALL THREE cases (stable connection, mid-handshake swap, 5s yield expiry):
        // old v2 bridge receives ZERO volumeAssist=true commands, recents still work, 3B fallback active,
        // and switching to new v3 bridge restores volume assist!
        // =========================================================================
        TaskBridgeClient.resetForTest();
        HomeKeyService v2CompatSvc = freshService();
        v2CompatSvc.sysPrefs.edit().putInt("home_filter_revision", SystemIntegration.HOME_FILTER_REVISION).commit();
        SystemIntegration.setGuardMode(v2CompatSvc, HomeSetupPolicy.GuardMode.KEY_FILTER);
        v2CompatSvc.onServiceConnected();
        final List<org.json.JSONObject> f098Reqs = Collections.synchronizedList(new ArrayList<>());
        final boolean[] f098VolAssist = {false};
        final boolean[] f098WriteFailed = {true};
        final android.os.IBinder f098OldBridge = new android.os.IBinder() {
            @Override public boolean isBinderAlive() { return true; }
            @Override public void linkToDeath(DeathRecipient r, int flags) { }
            @Override public boolean transact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) {
                data.readInterfaceToken();
                try {
                    org.json.JSONObject req = new org.json.JSONObject(data.readString());
                    f098Reqs.add(req);
                    String action = req.optString("action");
                    org.json.JSONObject ans = new org.json.JSONObject().put("ok", true);
                    if ("configure".equals(action)) {
                        boolean prev = f098VolAssist[0];
                        boolean next = req.optBoolean("volumeAssist", false);
                        boolean retry = req.optBoolean("retryVolumeAssist", false);
                        f098VolAssist[0] = next;
                        if (next && (retry || !prev)) f098WriteFailed[0] = false; // f0983a3 bug!
                    } else if ("status".equals(action)) {
                        boolean active = f098VolAssist[0] && !f098WriteFailed[0];
                        ans.put("volumeProtocolVersion", 2)
                                .put("volumeAssist", f098VolAssist[0])
                                .put("volumeAssistActive", active)
                                .put("volumeAudioReady", !f098WriteFailed[0])
                                .put("volumeAudioWriteFailed", f098WriteFailed[0])
                                .put("volumeInputReady", active)
                                .put("volumeInputDeviceCount", active ? 1 : 0)
                                .put("volumeAssistReason", !f098VolAssist[0] ? "disabled" : (f098WriteFailed[0] ? "audio_adjust_failed" : "ready"));
                    } else if ("list".equals(action)) {
                        ans.put("tasks", new org.json.JSONArray().put(new org.json.JSONObject().put("id", 55).put("pkg", "com.demo.recents")));
                    }
                    reply.writeNoException();
                    reply.writeString(ans.toString());
                    return true;
                } catch (Exception e) { throw new RuntimeException(e); }
            }
        };
        // Case 1: Stable connection to f0983a3 v2 bridge
        TaskBridgeClient.attach(v2CompatSvc, f098OldBridge);
        try {
            TaskBridgeClient.configure(v2CompatSvc);
            org.json.JSONObject recents = TaskBridgeClient.request("list");
            // Case 2: 5s no-bridge yield cycle + expiry against f0983a3 v2 bridge
            v2CompatSvc.dispatchBroadcast(new Intent(HomeKeyService.ACTION_VOLUME_CHANGED)
                    .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_TYPE, 3)
                    .putExtra(HomeKeyService.EXTRA_PREV_VOLUME_STREAM_VALUE, 10)
                    .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_VALUE, 11));
            TaskBridgeClient.configure(v2CompatSvc);
            boolean yieldedOnF098 = HomeKeyService.runtimeVolumeYieldActive && !HomeKeyService.runtimeKeyFilterRequested;
            SystemClock.elapsed += 5100;
            Handler.runDue();
            TaskBridgeClient.configure(v2CompatSvc);
            int f098TrueAfterYield = 0;
            for (org.json.JSONObject r : f098Reqs) {
                if ("configure".equals(r.optString("action")) && r.optBoolean("volumeAssist", false)) f098TrueAfterYield++;
            }
            check("e33d5ae_p1_f0983a3_v2_bridge_stable_and_5s_yield_zero_enable_keeps_recents",
                    yieldedOnF098
                    && f098TrueAfterYield == 0
                    && !TaskBridgeClient.volumeAssistActive()
                    && "bridge_update_required".equals(TaskBridgeClient.volumeAssistReason())
                    && recents.optBoolean("ok") && recents.optJSONArray("tasks").length() == 1);
            // Case 3: Mid-handshake swap from v3 bridge -> f0983a3 v2 bridge
            f098Reqs.clear();
            final boolean[] swappedToF098 = {false};
            android.os.IBinder v3BeforeSwap = new android.os.IBinder() {
                @Override public boolean isBinderAlive() { return true; }
                @Override public void linkToDeath(DeathRecipient r, int flags) { }
                @Override public boolean transact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) {
                    data.readInterfaceToken();
                    try {
                        org.json.JSONObject req = new org.json.JSONObject(data.readString());
                        if (!swappedToF098[0] && "status".equals(req.optString("action"))) {
                            swappedToF098[0] = true;
                            TaskBridgeClient.attach(v2CompatSvc, f098OldBridge);
                        }
                        org.json.JSONObject ans = new org.json.JSONObject().put("ok", true)
                                .put("volumeProtocolVersion", 3).put("volumeAssist", true).put("volumeAssistActive", true)
                                .put("volumeAudioReady", true).put("volumeInputDeviceCount", 2).put("volumeAssistReason", "ready");
                        reply.writeNoException();
                        reply.writeString(ans.toString());
                        return true;
                    } catch (Exception e) { throw new RuntimeException(e); }
                }
            };
            TaskBridgeClient.attach(v2CompatSvc, v3BeforeSwap);
            TaskBridgeClient.configure(v2CompatSvc);
            int f098TrueAfterSwap = 0;
            for (org.json.JSONObject r : f098Reqs) {
                if ("configure".equals(r.optString("action")) && r.optBoolean("volumeAssist", false)) f098TrueAfterSwap++;
            }
            check("e33d5ae_p1_f0983a3_v2_bridge_mid_handshake_swap_zero_enable",
                    swappedToF098[0]
                    && f098TrueAfterSwap == 0
                    && !TaskBridgeClient.volumeAssistActive()
                    && "bridge_update_required".equals(TaskBridgeClient.volumeAssistReason()));
            // Upgrade to real v3 bridge -> volume assist activates cleanly!
            android.os.IBinder v3Upgraded = new android.os.IBinder() {
                @Override public boolean isBinderAlive() { return true; }
                @Override public void linkToDeath(DeathRecipient r, int flags) { }
                @Override public boolean transact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) {
                    data.readInterfaceToken();
                    data.readString();
                    reply.writeNoException();
                    reply.writeString("{\"ok\":true,\"volumeProtocolVersion\":3,\"volumeAssist\":true,\"volumeAssistActive\":true,\"volumeAudioReady\":true,\"volumeInputDeviceCount\":2,\"volumeAssistReason\":\"ready\"}");
                    return true;
                }
            };
            TaskBridgeClient.attach(v2CompatSvc, v3Upgraded);
            TaskBridgeClient.configure(v2CompatSvc);
            check("e33d5ae_p1_restarted_v3_bridge_activates_volume_assist",
                    TaskBridgeClient.volumeAssistActive() && "ready".equals(TaskBridgeClient.volumeAssistReason()));
        } catch (Exception e) { throw new RuntimeException(e); }
        TaskBridgeClient.resetForTest();
        v2CompatSvc.onDestroy();

        // =========================================================================
        // e33d5ae P2 #2: Explicit user retry clicked DURING 5s no-bridge yield MUST fire ONCE
        // after yield expires and recover volume assist, while an unclicked control stays degraded!
        // =========================================================================
        TaskBridgeClient.resetForTest();
        HomeKeyService retryYieldSvc = freshService();
        retryYieldSvc.sysPrefs.edit().putInt("home_filter_revision", SystemIntegration.HOME_FILTER_REVISION).commit();
        SystemIntegration.setGuardMode(retryYieldSvc, HomeSetupPolicy.GuardMode.KEY_FILTER);
        retryYieldSvc.onServiceConnected();
        final boolean[] ryAssist = {false};
        final boolean[] ryWriteFailed = {true};
        final int[] ryRetryFlagsSent = {0};
        android.os.IBinder ryBridge = new android.os.IBinder() {
            @Override public boolean isBinderAlive() { return true; }
            @Override public void linkToDeath(DeathRecipient r, int flags) { }
            @Override public boolean transact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) {
                data.readInterfaceToken();
                try {
                    org.json.JSONObject req = new org.json.JSONObject(data.readString());
                    String action = req.optString("action");
                    org.json.JSONObject ans = new org.json.JSONObject().put("ok", true);
                    if ("configure".equals(action)) {
                        boolean next = req.optBoolean("volumeAssist", false);
                        boolean retry = req.optBoolean("retryVolumeAssist", false);
                        ryAssist[0] = next;
                        if (next && retry) {
                            ryRetryFlagsSent[0]++;
                            ryWriteFailed[0] = false;
                        }
                    } else if ("status".equals(action)) {
                        boolean active = ryAssist[0] && !ryWriteFailed[0];
                        ans.put("volumeProtocolVersion", 3)
                                .put("volumeAssist", ryAssist[0])
                                .put("volumeAssistActive", active)
                                .put("volumeAudioReady", !ryWriteFailed[0])
                                .put("volumeAudioWriteFailed", ryWriteFailed[0])
                                .put("volumeInputReady", active)
                                .put("volumeInputDeviceCount", active ? 2 : 0)
                                .put("volumeAssistReason", !ryAssist[0] ? "disabled" : (ryWriteFailed[0] ? "audio_adjust_failed" : "ready"));
                    }
                    reply.writeNoException();
                    reply.writeString(ans.toString());
                    return true;
                } catch (Exception e) { throw new RuntimeException(e); }
            }
        };
        TaskBridgeClient.attach(retryYieldSvc, ryBridge);
        try {
            TaskBridgeClient.configure(retryYieldSvc);
            // Control: enter 5s yield WITHOUT clicking retry -> expires -> stays degraded!
            retryYieldSvc.dispatchBroadcast(new Intent(HomeKeyService.ACTION_VOLUME_CHANGED)
                    .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_TYPE, 3)
                    .putExtra(HomeKeyService.EXTRA_PREV_VOLUME_STREAM_VALUE, 15)
                    .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_VALUE, 16));
            TaskBridgeClient.configure(retryYieldSvc);
            SystemClock.elapsed += 5100;
            Handler.runDue();
            drainBridgeSync();
            TaskBridgeClient.configure(retryYieldSvc);
            check("e33d5ae_p2_control_yield_without_user_retry_stays_degraded",
                    ryRetryFlagsSent[0] == 0
                    && ryWriteFailed[0]
                    && !TaskBridgeClient.volumeAssistActive());
            // Active user retry DURING 5s yield -> deferred until yield ends, then executes ONCE and recovers!
            retryYieldSvc.dispatchBroadcast(new Intent(HomeKeyService.ACTION_VOLUME_CHANGED)
                    .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_TYPE, 3)
                    .putExtra(HomeKeyService.EXTRA_PREV_VOLUME_STREAM_VALUE, 16)
                    .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_VALUE, 17));
            drainBridgeSync();
            TaskBridgeClient.configure(retryYieldSvc, true); // user clicks "启用快速 Home" during 5s yield
            boolean stillYieldingNoPrematureRetry = HomeKeyService.runtimeVolumeYieldActive
                    && ryRetryFlagsSent[0] == 0 && ryWriteFailed[0];
            SystemClock.elapsed += 5100;
            Handler.runDue();
            drainBridgeSync();
            TaskBridgeClient.configure(retryYieldSvc); // passive sync on yield expiry picks up pending one-shot retry!
            check("e33d5ae_p2_user_retry_during_yield_executes_once_on_restore_and_recovers",
                    stillYieldingNoPrematureRetry
                    && ryRetryFlagsSent[0] == 1
                    && !ryWriteFailed[0]
                    && TaskBridgeClient.volumeAssistActive()
                    && "ready".equals(TaskBridgeClient.volumeAssistReason()));
            // One-shot check: if write fails again later and another 5s yield expires without new click, no retry fires!
            ryWriteFailed[0] = true;
            TaskBridgeClient.configure(retryYieldSvc);
            retryYieldSvc.dispatchBroadcast(new Intent(HomeKeyService.ACTION_VOLUME_CHANGED)
                    .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_TYPE, 3)
                    .putExtra(HomeKeyService.EXTRA_PREV_VOLUME_STREAM_VALUE, 17)
                    .putExtra(HomeKeyService.EXTRA_VOLUME_STREAM_VALUE, 18));
            drainBridgeSync();
            TaskBridgeClient.configure(retryYieldSvc);
            SystemClock.elapsed += 5100;
            Handler.runDue();
            drainBridgeSync();
            TaskBridgeClient.configure(retryYieldSvc);
            check("e33d5ae_p2_pending_retry_is_strictly_one_shot",
                    ryRetryFlagsSent[0] == 1 && ryWriteFailed[0] && !TaskBridgeClient.volumeAssistActive());
        } catch (Exception e) { throw new RuntimeException(e); }
        TaskBridgeClient.resetForTest();
        retryYieldSvc.onDestroy();

        // =========================================================================
        // Hisense JUOS / Leading-edge Window Pull + Cross-Source Dedup + Key Timeout Regressions
        // =========================================================================
        // 1. Same short press: OEM launcher window arrives first (0ms), then homekey broadcast +50ms later.
        HomeKeyService hs1 = freshService();
        SystemClock.elapsed = 10000L;
        hs1.onServiceConnected();
        hs1.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode"));
        check("hs_svc_window_first_pulls_immediately_single_press",
                hs1.startedActivities.size() == 1
                && !hs1.startedActivities.get(0).getBooleanExtra("recent_apps", true)
                && (hs1.startedActivities.get(0).getFlags() & Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0
                && "window_immediate".equals(hs1.startedActivities.get(0).getStringExtra("home_return_source"))
                && hs1.startedActivities.get(0).getStringExtra(HomeSetup.EXTRA_EVENT_TOKEN) != null);
        SystemClock.elapsed = 10050L;
        hs1.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        SystemClock.elapsed = 10200L;
        Handler.runDue();
        check("hs_svc_window_first_then_broadcast_50ms_launches_only_once",
                hs1.startedActivities.size() == 1
                && !hs1.startedActivities.get(0).getBooleanExtra("recent_apps", true));
        hs1.onDestroy();

        // 1b. Variant where Marco already returned to front at +30ms before late +50ms broadcast.
        HomeKeyService hs1b = freshService();
        SystemClock.elapsed = 15000L;
        hs1b.onServiceConnected();
        hs1b.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode"));
        SystemClock.elapsed = 15030L;
        hs1b.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.pilipala.tvhome", "com.pilipala.tvhome.MainActivity"));
        SystemClock.elapsed = 15050L;
        hs1b.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        check("hs_svc_window_first_marco_returned_30ms_late_broadcast_50ms_still_deduped",
                hs1b.startedActivities.size() == 1
                && !hs1b.startedActivities.get(0).getBooleanExtra("recent_apps", true));
        hs1b.onDestroy();

        // 2. Two real short presses 200ms apart with Marco returned in between -> recognizes double-press (window->window & window->broadcast).
        HomeKeyService hs2 = freshService();
        SystemClock.elapsed = 20000L;
        hs2.onServiceConnected();
        hs2.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode"));
        SystemClock.elapsed = 20050L;
        hs2.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        SystemClock.elapsed = 20080L;
        hs2.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.pilipala.tvhome", "com.pilipala.tvhome.MainActivity"));
        SystemClock.elapsed = 20200L;
        hs2.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode"));
        SystemClock.elapsed = 20250L;
        hs2.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        check("hs_svc_two_real_short_presses_200ms_apart_window_then_window_recognizes_double",
                hs2.startedActivities.size() == 2
                && !hs2.startedActivities.get(0).getBooleanExtra("recent_apps", true)
                && hs2.startedActivities.get(1).getBooleanExtra("recent_apps", false));
        hs2.onDestroy();

        HomeKeyService hs2b = freshService();
        SystemClock.elapsed = 25000L;
        hs2b.onServiceConnected();
        hs2b.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode"));
        SystemClock.elapsed = 25080L;
        hs2b.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.pilipala.tvhome", "com.pilipala.tvhome.MainActivity"));
        SystemClock.elapsed = 25200L;
        hs2b.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        check("hs_svc_two_real_short_presses_200ms_apart_window_then_broadcast_recognizes_double",
                hs2b.startedActivities.size() == 2
                && !hs2b.startedActivities.get(0).getBooleanExtra("recent_apps", true)
                && hs2b.startedActivities.get(1).getBooleanExtra("recent_apps", false));
        SystemClock.elapsed = 25250L;
        hs2b.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode"));
        check("hs_svc_racing_window_after_double_broadcast_pulls_and_preserves_recent_apps",
                hs2b.startedActivities.size() == 3
                && hs2b.startedActivities.get(2).getBooleanExtra("recent_apps", false));
        hs2b.onDestroy();

        // 3. Broadcast-first order: homekey broadcast arrives first, racing OEM window +50ms later (and +30ms Marco return variant).
        HomeKeyService hs3 = freshService();
        SystemClock.elapsed = 30000L;
        hs3.onServiceConnected();
        hs3.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        SystemClock.elapsed = 30050L;
        hs3.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode"));
        check("hs_svc_broadcast_first_then_window_50ms_pulls_without_double_counting",
                hs3.startedActivities.size() == 2
                && !hs3.startedActivities.get(0).getBooleanExtra("recent_apps", true)
                && !hs3.startedActivities.get(1).getBooleanExtra("recent_apps", true));
        SystemClock.elapsed = 30090L;
        hs3.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.pilipala.tvhome", "com.pilipala.tvhome.MainActivity"));
        SystemClock.elapsed = 30200L;
        hs3.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        check("hs_svc_broadcast_first_followed_by_real_second_press_200ms_recognizes_double",
                hs3.startedActivities.size() == 3
                && hs3.startedActivities.get(2).getBooleanExtra("recent_apps", false));
        hs3.onDestroy();

        HomeKeyService hs3b = freshService();
        SystemClock.elapsed = 35000L;
        hs3b.onServiceConnected();
        hs3b.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        SystemClock.elapsed = 35030L;
        hs3b.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.pilipala.tvhome", "com.pilipala.tvhome.MainActivity"));
        SystemClock.elapsed = 35050L;
        hs3b.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode"));
        check("hs_svc_broadcast_first_marco_30ms_late_window_50ms_no_double_count",
                hs3b.startedActivities.size() == 2
                && !hs3b.startedActivities.get(0).getBooleanExtra("recent_apps", true)
                && !hs3b.startedActivities.get(1).getBooleanExtra("recent_apps", true));
        hs3b.onDestroy();

        // 4. Three independent short-press rounds; other apps/settings & nav keys unaffected.
        HomeKeyService hs4 = freshService();
        hs4.onServiceConnected();
        boolean threeRoundsOk = true;
        for (int round = 0; round < 3; round++) {
            long base = 70000L + round * 3000L;
            SystemClock.elapsed = base;
            hs4.onAccessibilityEvent(new AccessibilityEvent(
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.example.player", "com.example.player.MainActivity"));
            SystemClock.elapsed = base + 500L;
            hs4.onAccessibilityEvent(new AccessibilityEvent(
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode"));
            SystemClock.elapsed = base + 550L;
            hs4.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
            SystemClock.elapsed = base + 600L;
            hs4.onAccessibilityEvent(new AccessibilityEvent(
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.pilipala.tvhome", "com.pilipala.tvhome.MainActivity"));
            threeRoundsOk &= hs4.startedActivities.size() == round + 1
                    && !hs4.startedActivities.get(round).getBooleanExtra("recent_apps", true);
        }
        check("hs_svc_three_independent_short_press_rounds_single_return_each", threeRoundsOk);
        int launchesBeforeApps = hs4.startedActivities.size();
        SystemClock.elapsed = 82000L;
        hs4.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.android.tv.settings", "com.android.tv.settings.MainSettings"));
        hs4.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.example.player", "com.example.player.MainActivity"));
        boolean navPassedThrough = !hs4.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
        check("hs_svc_other_apps_settings_and_nav_keys_unaffected",
                hs4.startedActivities.size() == launchesBeforeApps && navPassedThrough);
        hs4.onDestroy();

        // 5. Stuck ACTION_DOWN without UP under key_filter recovers via 2500ms safety timeout.
        HomeKeyService hs5 = freshService();
        hs5.sysPrefs.edit()
                .putString(SystemIntegration.PREF_GUARD_MODE, HomeSetupPolicy.GuardMode.KEY_FILTER.prefValue)
                .putString("home_long_action", "marco")
                .commit();
        hs5.onServiceConnected();
        SystemClock.elapsed = 85000L;
        hs5.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME));
        SystemClock.elapsed = 85600L;
        Handler.runDue();
        SystemClock.elapsed = 87500L;
        Handler.runDue();
        SystemClock.elapsed = 88000L;
        hs5.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.pilipala.tvhome", "com.pilipala.tvhome.MainActivity"));
        int beforeRecoveryPress = hs5.startedActivities.size();
        SystemClock.elapsed = 89000L;
        hs5.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        check("hs_svc_stuck_home_down_recovers_after_safety_timeout",
                hs5.startedActivities.size() == beforeRecoveryPress + 1);
        hs5.onDestroy();

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
class TaskBridgeServer {
    static final String DESCRIPTOR = "com.pilipala.tvhome.TaskBridge.v1";
    static final int VOLUME_PROTOCOL_VERSION = 3;
}
class MainActivity extends android.app.Activity {
    DefaultHomePanel defaultHome;
    HealthPanel health = new HealthPanel();
    SettingsNavigator navigator = new SettingsNavigator();
    boolean isDesktopVisible() { return false; }
}
class DefaultHomePanel {
    void show() {}
    void request() {}
    void assistant() {}
    void compatibility() {}
    void restore() {}
}
class HealthPanel { void status() {} }
class SettingsNavigator { void personalization(String key) {} }
class TvDialog {
    boolean isShowing() { return false; }
    void updateMessage(String m) {}
    void updateItems(String[] next) {}
    void onLeave(Runnable r) {}
    void onReturn(Runnable r) {}
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
