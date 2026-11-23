// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.provider.Settings;
import java.io.*;
import java.security.SecureRandom;

/** Setup state is local, not backed up, and never equates a request with verified success. */
final class HomeSetup {
    static final String OWN = "com.pilipala.tvhome/.HomeActivity";
    static final String SERVICE = "com.pilipala.tvhome/.HomeKeyService";
    static final String EXTRA_EVENT_TOKEN = "marco_home_event_token";
    static final String SOURCE_SYSTEM_HOME = "system_home";
    static final String SOURCE_COMPAT_KEY = "compat_key";
    static final String SOURCE_COMPAT_BROADCAST = "compat_broadcast";
    static final String SOURCE_WINDOW_FALLBACK = "window_fallback";
    static final String BOOT_STATE_REQUESTED = "requested";
    static final String BOOT_STATE_LAUNCH_DENIED = "launch_denied";
    static final String BOOT_STATE_RECOVERY_ARRIVED = "recovery_arrived";
    static final String BOOT_STATE_NATIVE_UNCONFIRMED = "native_home_unconfirmed";
    private static final long HOME_TEST_WINDOW_MS = 90000L;
    private static final long PENDING_HOME_TTL_MS = 15000L;
    private static final long BOOT_REQUEST_TTL_MS = 60000L;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static volatile long processSessionId = nextSessionId();
    private static volatile String cachedBootId;
    static volatile String bootIdOverride;

    private HomeSetup() { }

    private static long nextSessionId() {
        long value = RANDOM.nextLong();
        return value == 0L ? 1L : value;
    }

    static void resetProcessSessionForTest() {
        processSessionId = nextSessionId();
    }

    static String currentBootId() {
        String override = bootIdOverride;
        if (override != null) return override;
        String cached = cachedBootId;
        if (cached != null) return cached;
        String value = "";
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/sys/kernel/random/boot_id"))) {
            String line = reader.readLine();
            if (line != null) value = line.trim();
        } catch (Exception ignored) { }
        cachedBootId = value;
        return value;
    }

    static SharedPreferences prefs(Context c) { return SystemIntegration.prefs(c); }
    static int bootCount(Context c) {
        try { return Settings.Global.getInt(c.getContentResolver(), "boot_count", -1); }
        catch (RuntimeException e) { return -1; }
    }
    static boolean paused(Context c) { return prefs(c).getBoolean("setup_paused", false); }
    static boolean unlocked(Context c) {
        return Build.VERSION.SDK_INT < 24 || ((UserManager)c.getSystemService(Context.USER_SERVICE)).isUserUnlocked();
    }
    static boolean serviceEnabled(Context c) {
        try {
            if (Settings.Secure.getInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false;
            String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (list == null) return false;
            ComponentName own = ComponentName.unflattenFromString(SERVICE);
            for (String entry : list.split(":")) if (own.equals(ComponentName.unflattenFromString(entry))) return true;
        } catch (RuntimeException ignored) { }
        return false;
    }
    static boolean canOpen(Context c, String action) {
        try {
            ResolveInfo r = c.getPackageManager().resolveActivity(new Intent(action), 0);
            return r != null && r.activityInfo != null && r.activityInfo.exported
                    && !r.activityInfo.name.endsWith("EmptyStubActivity");
        } catch (RuntimeException e) { return false; }
    }
    static boolean roleAvailable(Context c) {
        if (Build.VERSION.SDK_INT < 29) return false;
        try {
            Object role = c.getSystemService("role");
            return role != null && (Boolean)role.getClass().getMethod("isRoleAvailable", String.class)
                    .invoke(role, "android.app.role.HOME");
        } catch (Exception e) { return false; }
    }
    static boolean hasPicker(Context c) {
        return !BuildEdition.RESTRICTED && (roleAvailable(c) || canOpen(c, Settings.ACTION_HOME_SETTINGS));
    }
    static boolean hasAccessibilityEntry(Context c) {
        return canOpen(c, Settings.ACTION_ACCESSIBILITY_SETTINGS);
    }
    static HomeSetupPolicy.Route route(Context c) {
        return HomeSetupPolicy.route(paused(c), SystemIntegration.homeIsOurs(c),
                hasPicker(c), prefs(c).getBoolean("home_compat", false),
                serviceEnabled(c) && HomeKeyService.connected, hasAccessibilityEntry(c));
    }
    /** Active capability route ignoring pause; used only after the user chooses to resume. */
    static HomeSetupPolicy.Route capabilityRoute(Context c) {
        boolean serviceReady = serviceEnabled(c) && HomeKeyService.connected;
        return HomeSetupPolicy.route(false, SystemIntegration.homeIsOurs(c),
                hasPicker(c), prefs(c).getBoolean("home_compat", false),
                serviceReady, hasAccessibilityEntry(c) || serviceReady);
    }
    /** Explicit user action only. Refreshes tracked HOME before mutating system HOME. */
    static void begin(Context c) {
        SystemIntegration.refreshTrackedHomeForSetup(c);
        SystemIntegration.enableHome(c, true);
        prefs(c).edit().putBoolean("setup_requested", true).putBoolean("setup_paused", false)
                .remove("setup_pause_reason").remove("setup_native_seen")
                .remove("setup_home_seen").remove("setup_home_source")
                .remove("setup_home_deadline").remove("setup_home_started")
                .remove("setup_home_boot").remove("setup_home_boot_id").remove("setup_home_session")
                .remove("setup_accessibility_pending")
                .remove("setup_boot_observed").remove("setup_boot_state").remove("setup_boot_source")
                .remove("setup_boot_observed_time").remove("setup_boot_observed_boot_id")
                .remove("setup_boot_confirmed").remove("setup_boot_confirmed_source").remove("setup_boot_confirmed_time")
                .remove("boot_req_token").remove("boot_req_state").remove("boot_req_boot")
                .remove("boot_req_boot_id").remove("boot_req_elapsed").remove("boot_req_deadline").remove("boot_req_time")
                .remove("pending_home_token").remove("pending_home_source").remove("pending_home_boot")
                .remove("pending_home_started").remove("pending_home_deadline")
                .remove("boot_session_interrupted_boot").remove("boot_session_interrupted_id")
                .remove("setup_native_reset_pending")
                .remove("setup_compat_reset_pending")
                .remove("compat_service_active")
                .remove("standby_wake_return")
                .putInt("setup_boot_armed", bootCount(c))
                .putString("setup_boot_armed_boot_id", currentBootId()).apply();
    }
    /**
     * Firmware on several TVs resets a third-party HOME during boot. That is not
     * distinguishable from a user choice, so never re-apply HOME silently; instead keep
     * boot opening on and ask once. Called only from BOOT_COMPLETED, before reconcile().
     */
    static boolean markNativeResetAtBoot(Context c) {
        SharedPreferences p = prefs(c);
        if (!unlocked(c) || paused(c) || !p.getBoolean("setup_requested", false)
                || !p.getBoolean("setup_native_seen", false) || !p.getBoolean("boot_home", false)
                || SystemIntegration.homeIsOurs(c)) return false;
        p.edit().putBoolean("setup_native_reset_pending", true).apply();
        Diagnostics.event("HOME_NATIVE_RESET_AT_BOOT");
        return true;
    }
    static boolean nativeResetPending(Context c) {
        return prefs(c).getBoolean("setup_native_reset_pending", false) && !paused(c);
    }
    static void clearNativeReset(Context c) {
        prefs(c).edit().remove("setup_native_reset_pending").apply();
    }
    /**
     * Some firmware force-stops foreground apps on standby, causing Android's
     * AccessibilityManagerService.onHandleForceStop() to remove our service while
     * the process is dead. Ask once when reopened instead of silently pausing.
     */
    static boolean markCompatResetIfInterrupted(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getBoolean("setup_compat_reset_pending", false)) return false;
        if (!HomeSetupPolicy.compatResetAfterStop(unlocked(c), paused(c),
                p.getBoolean("home_compat", false), p.getBoolean("setup_accessibility_pending", false),
                p.getBoolean("compat_seen_present", false), p.getBoolean("compat_service_active", false),
                serviceEnabled(c), HomeKeyService.connected)) return false;
        p.edit().putBoolean("setup_compat_reset_pending", true)
                .putBoolean("compat_service_active", false)
                .remove("standby_wake_return").apply();
        Diagnostics.event("HOME_COMPAT_RESET_AFTER_STOP");
        return true;
    }
    static boolean compatResetPending(Context c) {
        return prefs(c).getBoolean("setup_compat_reset_pending", false) && !paused(c);
    }
    static void clearCompatReset(Context c) {
        prefs(c).edit().remove("setup_compat_reset_pending").apply();
    }
    static void acceptNative(Context c) {
        if (!prefs(c).getBoolean("setup_requested", false) || paused(c) || !SystemIntegration.homeIsOurs(c)) return;
        prefs(c).edit().putBoolean("setup_native_seen", true).putBoolean("boot_home", true).apply();
    }
    static void requestCompatibility(Context c) {
        begin(c);
        prefs(c).edit().putBoolean("home_compat", true).putBoolean("boot_home", true)
                .putBoolean("setup_accessibility_pending", true).apply();
    }
    static void finishCompatibilityConsent(Context c) {
        if (!prefs(c).getBoolean("setup_accessibility_pending", false)) return;
        boolean enabled = serviceEnabled(c);
        prefs(c).edit().remove("setup_accessibility_pending")
                .putBoolean("home_compat", enabled)
                .putBoolean("boot_home", enabled || SystemIntegration.homeIsOurs(c)).apply();
    }
    static void pause(Context c, String reason) {
        prefs(c).edit().putBoolean("setup_paused", true).putString("setup_pause_reason", reason)
                .putBoolean("home_compat", false).putBoolean("boot_home", false)
                .putBoolean("managed_home", false)
                .remove("setup_home_deadline").remove("setup_home_started")
                .remove("setup_home_boot").remove("setup_home_boot_id").remove("setup_home_session")
                .remove("setup_accessibility_pending").remove("setup_home_seen").remove("setup_home_source")
                .remove("setup_boot_confirmed").remove("setup_boot_confirmed_source").remove("setup_boot_confirmed_time")
                .remove("boot_req_token").remove("pending_home_token").remove("pending_home_source")
                .remove("setup_native_reset_pending")
                .remove("setup_compat_reset_pending")
                .remove("compat_seen_present")
                .remove("compat_service_active")
                .remove("standby_wake_return").apply();
        // Never infer that an external change was necessarily the user's action.
        TaskBridgeClient.sync(c);
    }
    static void reconcile(Context c) {
        SharedPreferences p = prefs(c);
        if (!unlocked(c) || paused(c)) return;
        if (p.getBoolean("setup_compat_reset_pending", false)) {
            if (serviceEnabled(c)) clearCompatReset(c);
            return;
        }
        if (p.getBoolean("home_compat", false) && !p.getBoolean("setup_accessibility_pending", false) && !serviceEnabled(c)) {
            pause(c, "Home 服务不可用，需确认后继续恢复。");
            return;
        }
        if (!p.getBoolean("setup_requested", false)) return;
        // A boot-time reset waits for the user's answer instead of pausing silently.
        if (p.getBoolean("setup_native_reset_pending", false)) {
            if (SystemIntegration.homeIsOurs(c)) clearNativeReset(c);
            return;
        }
        if (p.getBoolean("setup_native_seen", false) && !SystemIntegration.homeIsOurs(c)) {
            pause(c, "默认桌面已变化；请确认是否继续使用 Marco TV。不会自动覆盖新的选择。");
        } else acceptNative(c);
    }

    static void invalidateHomeTest(Context c) {
        prefs(c).edit().remove("setup_home_deadline").remove("setup_home_started")
                .remove("setup_home_boot").remove("setup_home_boot_id").remove("setup_home_session")
                .remove("pending_home_token").remove("pending_home_source")
                .remove("pending_home_boot").remove("pending_home_started").remove("pending_home_deadline").apply();
    }

    static void armHomeTest(Context c) {
        long now = SystemClock.elapsedRealtime();
        prefs(c).edit().putInt("setup_home_boot", bootCount(c))
                .putString("setup_home_boot_id", currentBootId())
                .putLong("setup_home_started", now)
                .putLong("setup_home_deadline", now + HOME_TEST_WINDOW_MS)
                .putLong("setup_home_session", processSessionId)
                .remove("setup_home_seen").remove("setup_home_source").apply();
    }

    /** Single-use in-app token linking a HomeKeyService action to MainActivity arrival. */
    static String issueHomeEvent(Context c, String source) {
        if (source == null || source.isEmpty()) return "";
        long now = SystemClock.elapsedRealtime();
        String token = "h-" + Long.toHexString(nextSessionId()) + "-" + now;
        prefs(c).edit().putString("pending_home_token", token)
                .putString("pending_home_source", source)
                .putInt("pending_home_boot", bootCount(c))
                .putLong("pending_home_started", now)
                .putLong("pending_home_deadline", now + PENDING_HOME_TTL_MS).apply();
        return token;
    }

    static void clearPendingHomeEvent(Context c) {
        prefs(c).edit().remove("pending_home_token").remove("pending_home_source")
                .remove("pending_home_boot").remove("pending_home_started").remove("pending_home_deadline").apply();
    }

    /** Single-use in-app token linking a real BOOT_COMPLETED recovery launch to arrival. */
    static String beginBootRecoveryRequest(Context c) {
        long now = SystemClock.elapsedRealtime();
        String token = "b-" + Long.toHexString(nextSessionId()) + "-" + now;
        prefs(c).edit().putString("boot_req_token", token)
                .putInt("boot_req_boot", bootCount(c))
                .putString("boot_req_boot_id", currentBootId())
                .putLong("boot_req_elapsed", now)
                .putLong("boot_req_deadline", now + BOOT_REQUEST_TTL_MS)
                .putString("boot_req_state", BOOT_STATE_REQUESTED)
                .putLong("boot_req_time", System.currentTimeMillis())
                .remove("setup_home_deadline").remove("setup_home_started")
                .remove("setup_home_boot").remove("setup_home_boot_id").remove("setup_home_session")
                .commit();
        return token;
    }

    static void markBootRecoveryLaunchDenied(Context c) {
        prefs(c).edit().remove("boot_req_token")
                .putString("boot_req_state", BOOT_STATE_LAUNCH_DENIED)
                .putLong("boot_req_time", System.currentTimeMillis()).commit();
        Diagnostics.event("BOOT_RECOVERY_LAUNCH_DENIED");
    }

    static void onBootBroadcast(Context c, String action) {
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            markBootSessionInterrupted(c);
            prefs(c).edit().remove("boot_req_token").remove("pending_home_token").remove("pending_home_source")
                    .remove("setup_home_deadline").remove("setup_home_started").remove("setup_home_session").apply();
            return;
        }
        // Real reboot invalidates any previous session's Home test without wiping an
        // earlier native HOME observation that already arrived during this same boot.
        invalidateHomeTest(c);
    }

    static void noteStandbyWake(Context c) {
        markBootSessionInterrupted(c);
    }

    private static void markBootSessionInterrupted(Context c) {
        prefs(c).edit().putInt("boot_session_interrupted_boot", bootCount(c))
                .putString("boot_session_interrupted_id", currentBootId()).apply();
    }

    private static boolean isBootSessionInterrupted(SharedPreferences p, int currentBoot) {
        if (currentBoot >= 0) return p.getInt("boot_session_interrupted_boot", -1) == currentBoot;
        String id = currentBootId();
        return !id.isEmpty() && id.equals(p.getString("boot_session_interrupted_id", ""));
    }

    private static HomeSetupPolicy.HomeReturnSource parseSource(String raw) {
        if (SOURCE_SYSTEM_HOME.equals(raw)) return HomeSetupPolicy.HomeReturnSource.SYSTEM_HOME;
        if (SOURCE_COMPAT_KEY.equals(raw)) return HomeSetupPolicy.HomeReturnSource.COMPAT_KEY;
        if (SOURCE_COMPAT_BROADCAST.equals(raw)) return HomeSetupPolicy.HomeReturnSource.COMPAT_BROADCAST;
        if (SOURCE_WINDOW_FALLBACK.equals(raw)) return HomeSetupPolicy.HomeReturnSource.WINDOW_FALLBACK;
        return HomeSetupPolicy.HomeReturnSource.NONE;
    }

    private static String sourceKey(HomeSetupPolicy.HomeReturnSource source) {
        switch (source) {
            case SYSTEM_HOME: return SOURCE_SYSTEM_HOME;
            case COMPAT_KEY: return SOURCE_COMPAT_KEY;
            case COMPAT_BROADCAST: return SOURCE_COMPAT_BROADCAST;
            case WINDOW_FALLBACK: return SOURCE_WINDOW_FALLBACK;
            default: return "";
        }
    }

    private static boolean consumePendingBootToken(Context c, SharedPreferences p, String token, int currentBoot, long now) {
        String stored = p.getString("boot_req_token", "");
        if (token == null || token.isEmpty() || stored.isEmpty() || !token.equals(stored)) return false;
        int reqBoot = p.getInt("boot_req_boot", -1);
        String reqBootId = p.getString("boot_req_boot_id", "");
        long reqElapsed = p.getLong("boot_req_elapsed", -1L);
        long deadline = p.getLong("boot_req_deadline", -1L);
        // Single-use: always consume immediately before evaluating.
        p.edit().remove("boot_req_token").commit();
        String nowBootId = currentBootId();
        boolean bootIdOk = reqBootId.isEmpty() || nowBootId.isEmpty() || reqBootId.equals(nowBootId);
        int armedBoot = p.getInt("setup_boot_armed", -1);
        boolean valid = bootIdOk && HomeSetupPolicy.bootRecoveryEvidence(
                p.getBoolean("setup_requested", false), paused(c), true,
                armedBoot, reqBoot, currentBoot, reqElapsed, deadline, now);
        if (valid) {
            p.edit().putString("boot_req_state", BOOT_STATE_RECOVERY_ARRIVED)
                    .putString("setup_boot_state", BOOT_STATE_RECOVERY_ARRIVED)
                    .putString("setup_boot_source", BOOT_STATE_RECOVERY_ARRIVED)
                    .putInt("setup_boot_observed", currentBoot)
                    .putString("setup_boot_observed_boot_id", nowBootId)
                    .putLong("setup_boot_observed_time", System.currentTimeMillis()).commit();
            Diagnostics.event("BOOT_RECOVERY_ARRIVED");
        }
        return true;
    }

    private static HomeSetupPolicy.HomeReturnSource consumePendingHomeSource(SharedPreferences p, String token, int currentBoot, long now) {
        String stored = p.getString("pending_home_token", "");
        if (token == null || token.isEmpty() || stored.isEmpty() || !token.equals(stored)) {
            long deadline = p.getLong("pending_home_deadline", 0L);
            if (!stored.isEmpty() && (now > deadline || now < p.getLong("pending_home_started", 0L))) {
                p.edit().remove("pending_home_token").remove("pending_home_source")
                        .remove("pending_home_boot").remove("pending_home_started").remove("pending_home_deadline").apply();
            }
            return HomeSetupPolicy.HomeReturnSource.NONE;
        }
        String rawSource = p.getString("pending_home_source", "");
        int issuedBoot = p.getInt("pending_home_boot", -1);
        long started = p.getLong("pending_home_started", -1L);
        long deadline = p.getLong("pending_home_deadline", -1L);
        // Single-use: always consume immediately so duplicate delivery cannot replay it.
        p.edit().remove("pending_home_token").remove("pending_home_source")
                .remove("pending_home_boot").remove("pending_home_started").remove("pending_home_deadline").apply();
        if (started < 0 || now < started || now > deadline) return HomeSetupPolicy.HomeReturnSource.NONE;
        if (issuedBoot >= 0 && currentBoot >= 0 && issuedBoot != currentBoot) return HomeSetupPolicy.HomeReturnSource.NONE;
        return parseSource(rawSource);
    }

    static void observeHomeIntent(Context c, Intent i) {
        observeHomeIntent(c, i, false, false);
    }

    static void observeHomeIntent(Context c, Intent i, boolean openRecents, boolean recreated) {
        if (i == null) return;
        SharedPreferences p = prefs(c);
        int boot = bootCount(c);
        long now = SystemClock.elapsedRealtime();
        boolean fromHistory = (i.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0;
        if (recreated || fromHistory) return;
        if (i.getBooleanExtra("wake_recovery", false)) {
            noteStandbyWake(c);
            return;
        }
        if (i.getBooleanExtra("ambient", false) || openRecents || i.getBooleanExtra("recent_apps", false)) return;

        String token = i.getStringExtra(EXTRA_EVENT_TOKEN);
        if (consumePendingBootToken(c, p, token, boot, now)) return;

        HomeSetupPolicy.HomeReturnSource source = consumePendingHomeSource(p, token, boot, now);
        if (source == HomeSetupPolicy.HomeReturnSource.NONE
                && Intent.ACTION_MAIN.equals(i.getAction()) && i.hasCategory(Intent.CATEGORY_HOME)) {
            source = HomeSetupPolicy.HomeReturnSource.SYSTEM_HOME;
        }
        if (source == HomeSetupPolicy.HomeReturnSource.NONE) {
            markBootSessionInterrupted(c);
            return;
        }
        if (paused(c)) return;

        p.edit().putString("last_home_return_source", sourceKey(source))
                .putLong("last_home_return_time", System.currentTimeMillis())
                .putInt("last_home_return_boot", boot).apply();

        boolean armed = p.contains("setup_home_deadline");
        if (armed) {
            int armedBoot = p.getInt("setup_home_boot", -1);
            long armedStarted = p.getLong("setup_home_started", 0L);
            long deadline = p.getLong("setup_home_deadline", 0L);
            String armedBootId = p.getString("setup_home_boot_id", "");
            String nowBootId = currentBootId();
            boolean bootIdOk = armedBootId.isEmpty() || nowBootId.isEmpty() || armedBootId.equals(nowBootId);
            boolean sessionValid = bootIdOk
                    && ((armedBoot >= 0 && boot >= 0) || p.getLong("setup_home_session", 0L) == processSessionId);
            if (HomeSetupPolicy.homeEvidence(true, armedBoot, boot, armedStarted, deadline, now, sessionValid, source)) {
                p.edit().putLong("setup_home_seen", System.currentTimeMillis())
                        .putString("setup_home_source", sourceKey(source))
                        .remove("setup_home_deadline").remove("setup_home_started")
                        .remove("setup_home_boot").remove("setup_home_boot_id").remove("setup_home_session").apply();
                Diagnostics.event("HOME_TEST_OBSERVED_" + source.name());
            } else if (!sessionValid || now > deadline || now < armedStarted
                    || (armedBoot >= 0 && boot >= 0 && armedBoot != boot)) {
                invalidateHomeTest(c);
            }
        }

        if (source == HomeSetupPolicy.HomeReturnSource.SYSTEM_HOME) {
            boolean alreadyObservedThisBoot = boot >= 0 && p.getInt("setup_boot_observed", -1) == boot;
            boolean interrupted = isBootSessionInterrupted(p, boot);
            if (HomeSetupPolicy.nativeBootObservation(p.getBoolean("setup_requested", false), false,
                    SystemIntegration.homeIsOurs(c), p.getInt("setup_boot_armed", -1), boot, now,
                    source, alreadyObservedThisBoot, interrupted)) {
                p.edit().putInt("setup_boot_observed", boot)
                        .putString("setup_boot_state", BOOT_STATE_NATIVE_UNCONFIRMED)
                        .putString("setup_boot_source", BOOT_STATE_NATIVE_UNCONFIRMED)
                        .putString("setup_boot_observed_boot_id", currentBootId())
                        .putLong("setup_boot_observed_time", System.currentTimeMillis()).apply();
                Diagnostics.event("BOOT_NATIVE_HOME_UNCONFIRMED");
            }
        } else {
            // Manual compat key / broadcast / window fallback within boot window must never create a boot entry.
            markBootSessionInterrupted(c);
        }
    }

    static void confirmBoot(Context c) {
        SharedPreferences p = prefs(c);
        if (paused(c)) return;
        int boot = bootCount(c);
        String state = p.getString("setup_boot_state", "");
        boolean hasBootCountObs = boot >= 0 && p.contains("setup_boot_observed")
                && p.getInt("setup_boot_observed", -1) == boot
                && boot > p.getInt("setup_boot_armed", Integer.MAX_VALUE);
        boolean hasApi23RecoveryObs = boot < 0 && BOOT_STATE_RECOVERY_ARRIVED.equals(state)
                && (currentBootId().isEmpty() || currentBootId().equals(p.getString("setup_boot_observed_boot_id", "")));
        if (hasBootCountObs || hasApi23RecoveryObs) {
            p.edit().putInt("setup_boot_confirmed", boot >= 0 ? boot : 1)
                    .putString("setup_boot_confirmed_source", state.isEmpty() ? "legacy" : state)
                    .putLong("setup_boot_confirmed_time", System.currentTimeMillis()).apply();
        }
    }

    static boolean shouldOpenAfterBoot(Context c, boolean unlocked) {
        return HomeSetupPolicy.bootFallback(prefs(c).getBoolean("boot_home", false), paused(c),
                SystemIntegration.homeIsOurs(c), prefs(c).getBoolean("home_compat", false), serviceEnabled(c), unlocked);
    }

    static String sourceLabel(String key) {
        if (SOURCE_SYSTEM_HOME.equals(key)) return "系统原生 HOME";
        if (SOURCE_COMPAT_KEY.equals(key)) return "Home 兼容按键";
        if (SOURCE_COMPAT_BROADCAST.equals(key)) return "Home 兼容广播";
        if (SOURCE_WINDOW_FALLBACK.equals(key)) return "原桌面窗口回拉";
        return "";
    }

    static String serviceStatusLabel(Context c) {
        boolean compat = prefs(c).getBoolean("home_compat", false);
        boolean enabled = serviceEnabled(c);
        if (enabled && HomeKeyService.connected) return "已允许并已连接（已绑定）" + (compat ? "" : " · 兼容模式未开启");
        if (enabled) return "已允许，但尚未连接（未绑定）";
        if (compat) return "已开启兼容模式，但系统服务未启用";
        return "未启用";
    }

    static String homeReturnStatus(Context c) {
        SharedPreferences p = prefs(c);
        if (p.contains("setup_home_seen")) {
            String src = p.getString("setup_home_source", "");
            if (SOURCE_WINDOW_FALLBACK.equals(src)) return "已回到 Marco（来源：原桌面窗口回拉；物理遥控器触发请按需人工确认）";
            String label = sourceLabel(src);
            if (!label.isEmpty()) return "已回到 Marco（来源：" + label + "）";
            return "已记录返回（旧版记录，来源未知）";
        }
        String lastSrc = p.getString("last_home_return_source", "");
        String lastLabel = sourceLabel(lastSrc);
        if (!lastLabel.isEmpty()) return "等待 90 秒检查（近期已观察到：" + lastLabel + "）";
        return "等待检查";
    }

    static String bootStatus(Context c) {
        SharedPreferences p = prefs(c);
        if (paused(c)) return "已暂停自动恢复";
        if (nativeResetPending(c)) return "本次开机时默认桌面被系统改回，等待你确认";
        if (compatResetPending(c)) return "待机或系统停止时 Home 服务被关闭，等待你确认";
        String state = p.getString("setup_boot_state", "");
        String reqState = p.getString("boot_req_state", "");
        if (BOOT_STATE_RECOVERY_ARRIVED.equals(state)) return "已记录开机恢复到达（已关联本轮开机恢复请求）";
        if (BOOT_STATE_LAUNCH_DENIED.equals(reqState)) return "已发起开机恢复请求，但系统拒绝后台启动（未自动进入）";
        if (BOOT_STATE_REQUESTED.equals(reqState)) return "已发起开机恢复请求，尚未观察到前台到达";
        if (BOOT_STATE_NATIVE_UNCONFIRMED.equals(state)) return "观察到开机后原生 HOME 到达（来源待确认）";
        if (p.contains("setup_boot_observed")) return "已记录历史开机观察（旧版记录，来源未知）";
        if (SystemIntegration.homeIsOurs(c) || p.getBoolean("boot_home", false)) return "已配置，等待实际重启或断电冷启动验证";
        return "尚未配置";
    }

    static String bootConfirmationStatus(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.contains("setup_boot_confirmed")) return "";
        String src = p.getString("setup_boot_confirmed_source", "");
        if (src.isEmpty() || "legacy".equals(src)) return "已保留旧版人工确认（来源未知）";
        return "已人工确认一次重启返回（完整重启与断电冷启动请现场分别记录）";
    }

    static String summary(Context c) {
        SharedPreferences p = prefs(c);
        boolean nativeHome = SystemIntegration.homeIsOurs(c);
        String current = SystemIntegration.currentHome(c);
        String nativeLine = BuildEdition.RESTRICTED
                ? "不适用（受限兼容版不含系统默认桌面声明）"
                : nativeHome
                        ? "已生效（系统当前解析为 Marco TV）"
                        : "未生效（系统当前解析：" + (current.isEmpty() ? "未指定或原厂桌面" : current) + "）"
                                + (SystemIntegration.homePreferenceIsOurs(c) ? "；偏好表曾记录 Marco 但实际仍指向其他桌面" : "");
        StringBuilder out = new StringBuilder();
        if (BuildEdition.RESTRICTED) out.append("受限系统兼容版 · 无系统默认桌面入口\n");
        out.append("原生默认桌面  ·  ").append(nativeLine);
        out.append("\nHome 兼容服务  ·  ").append(serviceStatusLabel(c));
        String tracked = SystemIntegration.trackedHome(c);
        if (SystemIntegration.trackedHomeInvalid(c)) {
            out.append("\n兼容回拉目标  ·  原记录桌面已失效且无唯一可靠替代，已停止依赖该目标的窗口回拉");
        } else if (!tracked.isEmpty() && (p.getBoolean("home_compat", false) || serviceEnabled(c))) {
            out.append("\n兼容回拉目标  ·  ").append(tracked);
        }
        out.append("\nHome 返回  ·  ").append(homeReturnStatus(c));
        out.append("\n开机进入  ·  ").append(bootStatus(c));
        String confirmed = bootConfirmationStatus(c);
        if (!confirmed.isEmpty()) out.append("\n开机人工确认  ·  ").append(confirmed);
        if (paused(c)) out.append("\n已暂停恢复：").append(p.getString("setup_pause_reason", "请重新确认"));
        return out.toString();
    }
}
