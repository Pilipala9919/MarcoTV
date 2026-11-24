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

        // Trailing "recentapps" emitted by an already-open OEM recents overlay while dismissing
        // itself after "homekey" must not suppress our broadcast retry, AND broadcast retry must be
        // attributed to COMPAT_BROADCAST / HOME_BROADCAST_RETRY (not HOME_WINDOW_FALLBACK).
        Diagnostics.events.clear();
        SystemClock.elapsed += 30;
        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "recentapps"));
        SystemClock.elapsed += 140;
        Handler.runDue();
        check("trailing_recentapps_after_homekey_still_runs_broadcast_retry",
                service.startedActivities.size() == 2
                && Diagnostics.events.contains("HOME_BROADCAST_RETRY")
                && !Diagnostics.events.contains("HOME_WINDOW_FALLBACK")
                && HomeSetup.SOURCE_COMPAT_BROADCAST.equals(service.sysPrefs.getString("pending_home_source", "")));

        // Observed OEM launcher window after "homekey" cancels retry and runs true WINDOW_FALLBACK.
        service.startedActivities.clear();
        Diagnostics.events.clear();
        SystemClock.elapsed += 1500;
        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        SystemClock.elapsed += 40;
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode"));
        Handler.runDue();
        SystemClock.elapsed += 200;
        Handler.runDue();
        check("observed_oem_window_after_homekey_records_window_fallback_not_retry",
                service.startedActivities.size() == 2
                && Diagnostics.events.contains("HOME_WINDOW_FALLBACK")
                && !Diagnostics.events.contains("HOME_BROADCAST_RETRY")
                && HomeSetup.SOURCE_WINDOW_FALLBACK.equals(service.sysPrefs.getString("pending_home_source", "")));

        // P2 #4 repro A: assistant app window taking foreground 50ms after "homekey" cancels 150ms retry.
        service.startedActivities.clear();
        Diagnostics.events.clear();
        SystemClock.elapsed += 1500;
        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        SystemClock.elapsed += 50;
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.xiaomi.voicecontrol", "com.xiaomi.voicecontrol.VoiceActivity"));
        SystemClock.elapsed += 200;
        Handler.runDue();
        check("p2_4_assistant_app_window_at_50ms_cancels_broadcast_retry",
                service.startedActivities.size() == 1
                && !Diagnostics.events.contains("HOME_BROADCAST_RETRY")
                && !Diagnostics.events.contains("HOME_WINDOW_FALLBACK"));

        // P2 #4 repro B: system overlay assistant (SystemUI / MiTV SystemUI) at 50ms after "homekey" cancels retry.
        service.startedActivities.clear();
        Diagnostics.events.clear();
        SystemClock.elapsed += 1500;
        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        SystemClock.elapsed += 50;
        service.onAccessibilityEvent(new AccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.xiaomi.mitv.systemui", "com.xiaomi.mitv.systemui.AssistantOverlay"));
        SystemClock.elapsed += 200;
        Handler.runDue();
        check("p2_4_system_overlay_assistant_at_50ms_cancels_broadcast_retry",
                service.startedActivities.size() == 1
                && !Diagnostics.events.contains("HOME_BROADCAST_RETRY")
                && !Diagnostics.events.contains("HOME_WINDOW_FALLBACK"));

        // P2 #4 repro C: "assist" broadcast 50ms after "homekey" is NEVER ignored and cancels retry + suppresses pullback.
        service.startedActivities.clear();
        Diagnostics.events.clear();
        SystemClock.elapsed += 1500;
        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        SystemClock.elapsed += 50;
        service.dispatchBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "assist"));
        SystemClock.elapsed += 150;
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
        SystemClock.elapsed += 200;
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
        SystemClock.elapsed += 200;
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

        service.onDestroy();
        check("destroy_cleans_up_state", !HomeKeyService.connected && !HomeKeyService.runtimeKeyFilterRequested);
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
