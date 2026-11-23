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
    static final String BOOT_CONFIRM_MANUAL_ONLY = "manual_observation_no_boot_id";
    private static final long HOME_TEST_WINDOW_MS = 90000L;
    private static final long PENDING_HOME_TTL_MS = 15000L;
    private static final long BOOT_REQUEST_TTL_MS = 60000L;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static volatile long processSessionId = nextSessionId();
    private static volatile String cachedBootId;
    static volatile String bootIdOverride;

    /** In-memory staged arrival; consumed once only when the desktop is visibly in the foreground. */
    static final class StagedArrival {
        final Intent intent;
        final boolean openRecents;
        final boolean recreated;
        boolean consumed;
        StagedArrival(Intent intent, boolean openRecents, boolean recreated) {
            this.intent = intent;
            this.openRecents = openRecents;
            this.recreated = recreated;
        }
    }

    /**
     * Activity arrival lifecycle gate shared by MainActivity and tests.
     * Stages onCreate / onNewIntent intents and consumes them at most once only
     * when the desktop window is actually visible and focused in the foreground.
     */
    static final class ArrivalGate {
        private boolean foreground;
        private boolean windowVisible;
        private StagedArrival staged;

        void onCreateIntent(Intent intent, boolean openRecents, boolean recreated) {
            stage(intent, openRecents, recreated);
        }

        void onNewIntent(Intent intent, boolean openRecents) {
            stage(intent, openRecents, false);
        }

        private void stage(Intent intent, boolean openRecents, boolean recreated) {
            if (openRecents || (intent != null && intent.getBooleanExtra("recent_apps", false))) {
                cancelPendingShortPress();
                staged = null;
                return;
            }
            staged = stageIntent(intent, false, recreated);
        }

        boolean onResume(Context c, boolean hasWindowFocus, boolean panelWindowFocused,
                         boolean finishing, boolean destroyed) {
            foreground = true;
            if (hasWindowFocus) windowVisible = true;
            return flush(c, hasWindowFocus, panelWindowFocused, finishing, destroyed);
        }

        boolean onWindowFocusChanged(Context c, boolean hasFocus, boolean panelWindowFocused,
                                     boolean finishing, boolean destroyed) {
            windowVisible = hasFocus;
            if (!hasFocus) return false;
            return flush(c, hasFocus, panelWindowFocused, finishing, destroyed);
        }

        void onPause() {
            foreground = false;
            windowVisible = false;
            staged = null;
        }

        void onDestroy() {
            foreground = false;
            windowVisible = false;
            staged = null;
        }

        boolean isDesktopVisible(boolean hasWindowFocus, boolean panelWindowFocused,
                                 boolean finishing, boolean destroyed) {
            return foreground && !finishing && !destroyed
                    && (windowVisible || hasWindowFocus || panelWindowFocused);
        }

        boolean hasPendingArrival() {
            return staged != null && !staged.consumed;
        }

        boolean flush(Context c, boolean hasWindowFocus, boolean panelWindowFocused,
                      boolean finishing, boolean destroyed) {
            if (staged == null || !isDesktopVisible(hasWindowFocus, panelWindowFocused, finishing, destroyed)) {
                return false;
            }
            StagedArrival pending = staged;
            staged = null;
            return consumeStagedArrival(c, pending, true);
        }
    }

    /** Deferred short-press test proof so a double-press opening recents never passes the short-press check. */
    private static final class PendingShortPress {
        final Context app;
        final HomeSetupPolicy.HomeReturnSource source;
        final int armedBoot;
        final long armedStarted;
        final long deadline;
        final long sessionId;
        final String armedBootId;
        final long pressElapsed;
        final long commitAfterElapsed;
        final long observedWall;
        Runnable scheduled;
        PendingShortPress(Context app, HomeSetupPolicy.HomeReturnSource source, int armedBoot,
                          long armedStarted, long deadline, long sessionId, String armedBootId,
                          long pressElapsed, long commitAfterElapsed, long observedWall) {
            this.app = app;
            this.source = source;
            this.armedBoot = armedBoot;
            this.armedStarted = armedStarted;
            this.deadline = deadline;
            this.sessionId = sessionId;
            this.armedBootId = armedBootId;
            this.pressElapsed = pressElapsed;
            this.commitAfterElapsed = commitAfterElapsed;
            this.observedWall = observedWall;
        }
    }

    private static final Object SHORT_PRESS_LOCK = new Object();
    private static PendingShortPress pendingShortPress;
    private static Handler shortPressHandler;

    private HomeSetup() { }

    private static long nextSessionId() {
        long value = RANDOM.nextLong();
        return value == 0L ? 1L : value;
    }

    static void resetProcessSessionForTest() {
        cancelPendingShortPress();
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

    static String currentBootRoundKey(Context c) {
        int boot = bootCount(c);
        if (boot >= 0) return "bc:" + boot;
        String id = currentBootId();
        if (!id.isEmpty()) return "id:" + id;
        return "proc:" + processSessionId;
    }

    static boolean canIdentifyBootAutomatically(Context c) {
        return bootCount(c) >= 0 || !currentBootId().isEmpty();
    }

    private static boolean isSameBootRound(Context c, String storedRound, int storedBoot,
                                           String storedBootId, long storedElapsed) {
        int boot = bootCount(c);
        if (boot >= 0 && storedBoot >= 0) return boot == storedBoot;
        String id = currentBootId();
        if (!id.isEmpty() && storedBootId != null && !storedBootId.isEmpty()) return id.equals(storedBootId);
        if (storedRound == null || storedRound.isEmpty()) return false;
        if (storedRound.equals("proc:" + processSessionId)) {
            return storedElapsed < 0 || SystemClock.elapsedRealtime() >= storedElapsed;
        }
        return false;
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
        cancelPendingShortPress();
        SystemIntegration.refreshTrackedHomeForSetup(c);
        SystemIntegration.enableHome(c, true);
        prefs(c).edit().putBoolean("setup_requested", true).putBoolean("setup_paused", false)
                .remove("setup_pause_reason").remove("setup_native_seen")
                .remove("setup_home_seen").remove("setup_home_source")
                .remove("setup_home_deadline").remove("setup_home_started")
                .remove("setup_home_boot").remove("setup_home_boot_id").remove("setup_home_session")
                .remove("setup_accessibility_pending").remove("setup_accessibility_deferred")
                .remove("setup_boot_observed").remove("setup_boot_state").remove("setup_boot_source")
                .remove("setup_boot_round").remove("setup_boot_observed_time").remove("setup_boot_observed_boot_id")
                .remove("setup_boot_observed_elapsed")
                .remove("setup_boot_history_state").remove("setup_boot_history_boot")
                .remove("setup_boot_confirmed").remove("setup_boot_confirmed_source")
                .remove("setup_boot_confirmed_round").remove("setup_boot_confirmed_time")
                .remove("boot_req_token").remove("boot_req_state").remove("boot_req_boot")
                .remove("boot_req_round").remove("boot_req_boot_id").remove("boot_req_elapsed").remove("boot_req_deadline").remove("boot_req_time")
                .remove("boot_handled_round").remove("boot_handled_boot").remove("boot_handled_boot_id").remove("boot_handled_elapsed")
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
    /**
     * Persists an in-flight system HOME picker transaction so it survives both
     * direct Home/firmware return and process kill/recreation while the picker is open.
     */
    static void beginPendingPicker(Context c, int priorAlias, String priorHome) {
        prefs(c).edit().putBoolean("picker_pending", true)
                .putInt("picker_prior_alias", priorAlias)
                .putString("picker_prior_home", priorHome == null ? "" : priorHome)
                .commit();
    }
    static boolean hasPendingPicker(Context c) {
        return prefs(c).getBoolean("picker_pending", false);
    }
    /**
     * Resolves any persisted pending system HOME picker against actual system HOME readback.
     * Commits only when Marco is actually selected; otherwise restores the exact prior alias
     * without disturbing prior pause state or verification records.
     */
    static boolean resolvePendingPicker(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("picker_pending", false)) return false;
        int priorAlias = p.getInt("picker_prior_alias", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
        String priorHome = p.getString("picker_prior_home", "");
        p.edit().remove("picker_pending").remove("picker_prior_alias").remove("picker_prior_home").commit();
        if (SystemIntegration.homeIsOurs(c)) {
            SystemIntegration.adoptExplicitTrackedHome(c, priorHome);
            begin(c);
            acceptNative(c);
            TaskBridgeClient.sync(c);
            return true;
        }
        SystemIntegration.setHomeAliasState(c, priorAlias);
        return false;
    }
    static void acceptNative(Context c) {
        if (!prefs(c).getBoolean("setup_requested", false) || paused(c) || !SystemIntegration.homeIsOurs(c)) return;
        prefs(c).edit().putBoolean("setup_native_seen", true).putBoolean("boot_home", true).apply();
    }
    /** Programmatic/authorized compatibility entry where service recovery runs immediately. */
    static void requestCompatibility(Context c) {
        begin(c);
        prefs(c).edit().putBoolean("home_compat", true).putBoolean("boot_home", true)
                .putBoolean("setup_accessibility_pending", true)
                .remove("setup_accessibility_deferred").commit();
        // While consent is pending, bridge wake recovery must stay off until enabled.
        TaskBridgeClient.sync(c);
    }
    /**
     * Transactional manual accessibility consent entry: defers unpausing, alias changes,
     * and evidence reset until the user actually grants the accessibility service.
     */
    static void beginPendingCompatibility(Context c) {
        prefs(c).edit().putBoolean("setup_accessibility_pending", true)
                .putBoolean("setup_accessibility_deferred", true).commit();
        // While consent is pending, bridge wake recovery must stay off until enabled.
        TaskBridgeClient.sync(c);
    }
    static void finishCompatibilityConsent(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("setup_accessibility_pending", false)) return;
        boolean deferred = p.getBoolean("setup_accessibility_deferred", false);
        boolean enabled = serviceEnabled(c);
        p.edit().remove("setup_accessibility_pending").remove("setup_accessibility_deferred").commit();
        if (enabled) {
            if (deferred) begin(c);
            p.edit().putBoolean("home_compat", true).putBoolean("boot_home", true).commit();
        } else if (deferred) {
            // Cancelled without grant: preserve prior pause state, alias, and verification records.
            boolean keepBoot = !paused(c) && SystemIntegration.homeIsOurs(c);
            p.edit().putBoolean("home_compat", false).putBoolean("boot_home", keepBoot).commit();
        } else {
            p.edit().putBoolean("home_compat", false)
                    .putBoolean("boot_home", !paused(c) && SystemIntegration.homeIsOurs(c)).commit();
        }
        TaskBridgeClient.sync(c);
    }
    static void pause(Context c, String reason) {
        cancelPendingShortPress();
        prefs(c).edit().putBoolean("setup_paused", true).putString("setup_pause_reason", reason)
                .putBoolean("home_compat", false).putBoolean("boot_home", false)
                .putBoolean("managed_home", false)
                .remove("setup_home_deadline").remove("setup_home_started")
                .remove("setup_home_boot").remove("setup_home_boot_id").remove("setup_home_session")
                .remove("setup_accessibility_pending").remove("setup_accessibility_deferred")
                .remove("setup_home_seen").remove("setup_home_source")
                .remove("setup_boot_confirmed").remove("setup_boot_confirmed_source")
                .remove("setup_boot_confirmed_round").remove("setup_boot_confirmed_time")
                .remove("boot_req_token").remove("pending_home_token").remove("pending_home_source")
                .remove("setup_native_reset_pending")
                .remove("setup_compat_reset_pending")
                .remove("compat_seen_present")
                .remove("compat_service_active")
                .remove("standby_wake_return").apply();
        // Never infer that an external change was necessarily the user's action.
        TaskBridgeClient.sync(c);
    }
    /**
     * For Android 6 (missing boot_count) users upgrading from 0.21.2 whose legacy prefs have
     * no setup_boot_armed_boot_id, establish the baseline on the first readable boot_id without
     * altering any existing settings or verification records. Subsequent boots then correlate normally.
     */
    static boolean ensureBootIdBaseline(Context c) {
        if (bootCount(c) >= 0 || paused(c)) return false;
        String nowBootId = currentBootId();
        if (nowBootId.isEmpty()) return false;
        SharedPreferences p = prefs(c);
        if (!p.getString("setup_boot_armed_boot_id", "").isEmpty()) return false;
        if (!p.getBoolean("setup_requested", false) && !SystemIntegration.homeIsOurs(c)
                && !p.getBoolean("boot_home", false)) return false;
        p.edit().putString("setup_boot_armed_boot_id", nowBootId).apply();
        return true;
    }
    static void reconcile(Context c) {
        reconcile(c, true);
    }
    static void reconcileFromBoot(Context c) {
        reconcile(c, false);
    }
    private static void reconcile(Context c, boolean allowBaseline) {
        SharedPreferences p = prefs(c);
        if (!unlocked(c) || paused(c)) return;
        if (allowBaseline) ensureBootIdBaseline(c);
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

    static void cancelPendingShortPress() {
        synchronized (SHORT_PRESS_LOCK) {
            if (pendingShortPress != null && pendingShortPress.scheduled != null && shortPressHandler != null) {
                shortPressHandler.removeCallbacks(pendingShortPress.scheduled);
            }
            pendingShortPress = null;
        }
    }

    /** Commits a deferred short-press Home test observation once the double-press window has elapsed. */
    static boolean flushPendingShortPress(Context c) {
        PendingShortPress candidate;
        long now = SystemClock.elapsedRealtime();
        synchronized (SHORT_PRESS_LOCK) {
            candidate = pendingShortPress;
            if (candidate == null || now < candidate.commitAfterElapsed) return false;
            if (candidate.scheduled != null && shortPressHandler != null) {
                shortPressHandler.removeCallbacks(candidate.scheduled);
            }
            pendingShortPress = null;
        }
        Context target = c != null ? c : candidate.app;
        if (target == null || paused(target)) return false;
        SharedPreferences p = prefs(target);
        if (!p.contains("setup_home_deadline")) return false;
        int boot = bootCount(target);
        String nowBootId = currentBootId();
        boolean bootIdOk = candidate.armedBootId.isEmpty() || nowBootId.isEmpty() || candidate.armedBootId.equals(nowBootId);
        boolean sessionValid = bootIdOk
                && ((candidate.armedBoot >= 0 && boot >= 0) || candidate.sessionId == processSessionId);
        if (!HomeSetupPolicy.homeEvidence(true, candidate.armedBoot, boot, candidate.armedStarted,
                candidate.deadline, candidate.pressElapsed, sessionValid, candidate.source)) return false;
        if (now < candidate.pressElapsed) return false;
        p.edit().putLong("setup_home_seen", candidate.observedWall)
                .putString("setup_home_source", sourceKey(candidate.source))
                .remove("setup_home_deadline").remove("setup_home_started")
                .remove("setup_home_boot").remove("setup_home_boot_id").remove("setup_home_session").apply();
        Diagnostics.event("HOME_TEST_OBSERVED_" + candidate.source.name());
        return true;
    }

    private static void scheduleShortPressCommit(Context c, HomeSetupPolicy.HomeReturnSource source,
                                                 int armedBoot, long armedStarted, long deadline,
                                                 long sessionId, String armedBootId, long now, int doubleWindowMs) {
        cancelPendingShortPress();
        long commitAt = now + Math.max(100, doubleWindowMs);
        PendingShortPress entry = new PendingShortPress(c.getApplicationContext(), source, armedBoot,
                armedStarted, deadline, sessionId, armedBootId, now, commitAt, System.currentTimeMillis());
        synchronized (SHORT_PRESS_LOCK) {
            pendingShortPress = entry;
            if (shortPressHandler == null) {
                try { shortPressHandler = new Handler(Looper.getMainLooper()); }
                catch (RuntimeException ignored) { }
            }
            if (shortPressHandler != null) {
                entry.scheduled = () -> flushPendingShortPress(entry.app);
                shortPressHandler.postDelayed(entry.scheduled, Math.max(100, doubleWindowMs) + 40L);
            }
        }
    }

    static void invalidateHomeTest(Context c) {
        cancelPendingShortPress();
        prefs(c).edit().remove("setup_home_deadline").remove("setup_home_started")
                .remove("setup_home_boot").remove("setup_home_boot_id").remove("setup_home_session")
                .remove("pending_home_token").remove("pending_home_source")
                .remove("pending_home_boot").remove("pending_home_started").remove("pending_home_deadline").apply();
    }

    static void armHomeTest(Context c) {
        cancelPendingShortPress();
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

    private static boolean isArmedHomeTestFromCurrentBoot(Context c, SharedPreferences p) {
        if (!p.contains("setup_home_deadline")) return false;
        int boot = bootCount(c);
        int armedBoot = p.getInt("setup_home_boot", -1);
        if (boot >= 0 && armedBoot >= 0) return armedBoot == boot;
        String nowBootId = currentBootId();
        String armedBootId = p.getString("setup_home_boot_id", "");
        if (!nowBootId.isEmpty() && !armedBootId.isEmpty()) return nowBootId.equals(armedBootId);
        long armedStarted = p.getLong("setup_home_started", -1L);
        return p.getLong("setup_home_session", 0L) == processSessionId
                && armedStarted >= 0 && SystemClock.elapsedRealtime() >= armedStarted;
    }

    private static void invalidateStaleHomeTestFromPriorBoot(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.contains("setup_home_deadline")) return;
        if (!isArmedHomeTestFromCurrentBoot(c, p)) invalidateHomeTest(c);
    }

    /**
     * Begins at most one boot recovery request per boot round.
     * Returns null when ANY request already exists for this exact boot round — whether
     * arrived, active, expired, or launch-denied — preserving round state and any active
     * current-boot Home key test.
     */
    static String tryBeginBootRecoveryRequest(Context c) {
        SharedPreferences p = prefs(c);
        long now = SystemClock.elapsedRealtime();
        int boot = bootCount(c);
        String bootId = currentBootId();
        String roundKey = currentBootRoundKey(c);

        String existingReqRound = p.getString("boot_req_round", "");
        int existingReqBoot = p.getInt("boot_req_boot", -1);
        String existingReqBootId = p.getString("boot_req_boot_id", "");
        long existingReqElapsed = p.getLong("boot_req_elapsed", -1L);
        String existingReqState = p.getString("boot_req_state", "");
        boolean sameReqRound = !existingReqState.isEmpty()
                && isSameBootRound(c, existingReqRound, existingReqBoot, existingReqBootId, existingReqElapsed);
        if (sameReqRound) {
            return null;
        }

        // Archive previous boot round's success so it can never mask this round's failure or non-arrival.
        SharedPreferences.Editor e = p.edit();
        String prevState = p.getString("setup_boot_state", "");
        boolean prevIsSameRound = !prevState.isEmpty() && isSameBootRound(c,
                p.getString("setup_boot_round", ""), p.getInt("setup_boot_observed", -1),
                p.getString("setup_boot_observed_boot_id", ""), p.getLong("setup_boot_observed_elapsed", -1L));
        if (!prevState.isEmpty() && !prevIsSameRound) {
            e.putString("setup_boot_history_state", prevState)
                    .putInt("setup_boot_history_boot", p.getInt("setup_boot_observed", -1))
                    .remove("setup_boot_state").remove("setup_boot_source")
                    .remove("setup_boot_round").remove("setup_boot_observed")
                    .remove("setup_boot_observed_boot_id").remove("setup_boot_observed_elapsed");
        }

        invalidateStaleHomeTestFromPriorBoot(c);
        String token = "b-" + Long.toHexString(nextSessionId()) + "-" + now;
        e.putString("boot_req_token", token)
                .putString("boot_req_round", roundKey)
                .putInt("boot_req_boot", boot)
                .putString("boot_req_boot_id", bootId)
                .putLong("boot_req_elapsed", now)
                .putLong("boot_req_deadline", now + BOOT_REQUEST_TTL_MS)
                .putString("boot_req_state", BOOT_STATE_REQUESTED)
                .putLong("boot_req_time", System.currentTimeMillis())
                .commit();
        return token;
    }

    static String beginBootRecoveryRequest(Context c) {
        String token = tryBeginBootRecoveryRequest(c);
        return token != null ? token : prefs(c).getString("boot_req_token", "");
    }

    static void markBootRecoveryLaunchDenied(Context c) {
        prefs(c).edit().remove("boot_req_token")
                .putString("boot_req_round", currentBootRoundKey(c))
                .putInt("boot_req_boot", bootCount(c))
                .putString("boot_req_boot_id", currentBootId())
                .putLong("boot_req_elapsed", SystemClock.elapsedRealtime())
                .putString("boot_req_state", BOOT_STATE_LAUNCH_DENIED)
                .putLong("boot_req_time", System.currentTimeMillis()).commit();
        Diagnostics.event("BOOT_RECOVERY_LAUNCH_DENIED");
    }

    /**
     * Deduplicates ACTION_BOOT_COMPLETED to at most one handling pass per boot round,
     * preserving current-round failure/expiry states and any in-progress 90s Home test.
     * Returns false when an identical boot-round ACTION_BOOT_COMPLETED was already processed.
     */
    static boolean consumeBootBroadcast(Context c, String action) {
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            cancelPendingShortPress();
            markBootSessionInterrupted(c);
            ensureBootIdBaseline(c);
            prefs(c).edit().remove("boot_req_token").remove("pending_home_token").remove("pending_home_source")
                    .remove("setup_home_deadline").remove("setup_home_started").remove("setup_home_session").apply();
            return true;
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            SharedPreferences p = prefs(c);
            String handledRound = p.getString("boot_handled_round", "");
            boolean alreadyHandled = !handledRound.isEmpty() && isSameBootRound(c, handledRound,
                    p.getInt("boot_handled_boot", -1), p.getString("boot_handled_boot_id", ""),
                    p.getLong("boot_handled_elapsed", -1L));
            if (alreadyHandled) return false;
            p.edit().putString("boot_handled_round", currentBootRoundKey(c))
                    .putInt("boot_handled_boot", bootCount(c))
                    .putString("boot_handled_boot_id", currentBootId())
                    .putLong("boot_handled_elapsed", SystemClock.elapsedRealtime()).commit();
            invalidateStaleHomeTestFromPriorBoot(c);
            return true;
        }
        // LOCKED_BOOT_COMPLETED: clear only prior-boot stale Home test, never current-boot test.
        invalidateStaleHomeTestFromPriorBoot(c);
        return true;
    }

    static void onBootBroadcast(Context c, String action) {
        consumeBootBroadcast(c, action);
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
        String armedBootId = p.getString("setup_boot_armed_boot_id", "");
        boolean notSameArmedApi23Boot = currentBoot >= 0 || armedBootId.isEmpty() || nowBootId.isEmpty()
                || !armedBootId.equals(nowBootId);
        boolean valid = bootIdOk && notSameArmedApi23Boot && HomeSetupPolicy.bootRecoveryEvidence(
                p.getBoolean("setup_requested", false), paused(c), true,
                armedBoot, reqBoot, currentBoot, reqElapsed, deadline, now);
        if (valid) {
            String roundKey = currentBootRoundKey(c);
            p.edit().putString("boot_req_state", BOOT_STATE_RECOVERY_ARRIVED)
                    .putString("boot_req_round", roundKey)
                    .putString("setup_boot_state", BOOT_STATE_RECOVERY_ARRIVED)
                    .putString("setup_boot_source", BOOT_STATE_RECOVERY_ARRIVED)
                    .putString("setup_boot_round", roundKey)
                    .putInt("setup_boot_observed", currentBoot)
                    .putString("setup_boot_observed_boot_id", nowBootId)
                    .putLong("setup_boot_observed_elapsed", now)
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

    static StagedArrival stageIntent(Intent i, boolean openRecents, boolean recreated) {
        if (i == null) return null;
        return new StagedArrival(i, openRecents, recreated);
    }

    static boolean consumeStagedArrival(Context c, StagedArrival staged, boolean desktopVisible) {
        if (staged == null || staged.consumed || !desktopVisible) return false;
        staged.consumed = true;
        observeHomeIntent(c, staged.intent, staged.openRecents, staged.recreated, true);
        return true;
    }

    private static boolean contextDesktopVisible(Context c) {
        if (c instanceof MainActivity) return ((MainActivity) c).isDesktopVisible();
        return true;
    }

    static void observeHomeIntent(Context c, Intent i) {
        observeHomeIntent(c, i, false, false, contextDesktopVisible(c));
    }

    static void observeHomeIntent(Context c, Intent i, boolean openRecents, boolean recreated) {
        observeHomeIntent(c, i, openRecents, recreated, contextDesktopVisible(c));
    }

    static void observeHomeIntent(Context c, Intent i, boolean openRecents, boolean recreated, boolean desktopVisible) {
        if (i == null) return;
        boolean isRecents = openRecents || i.getBooleanExtra("recent_apps", false);
        if (isRecents) {
            // Double-press / long-press opening recents cancels any in-flight Tap-1 short-press proof immediately.
            cancelPendingShortPress();
            return;
        }
        if (!desktopVisible) return;
        SharedPreferences p = prefs(c);
        int boot = bootCount(c);
        long now = SystemClock.elapsedRealtime();
        boolean fromHistory = (i.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0;
        if (recreated || fromHistory) return;
        if (i.getBooleanExtra("wake_recovery", false)) {
            noteStandbyWake(c);
            return;
        }
        if (i.getBooleanExtra("ambient", false)) return;

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
            long armedSession = p.getLong("setup_home_session", 0L);
            String armedBootId = p.getString("setup_home_boot_id", "");
            String nowBootId = currentBootId();
            boolean bootIdOk = armedBootId.isEmpty() || nowBootId.isEmpty() || armedBootId.equals(nowBootId);
            boolean sessionValid = bootIdOk
                    && ((armedBoot >= 0 && boot >= 0) || armedSession == processSessionId);
            if (HomeSetupPolicy.homeEvidence(true, armedBoot, boot, armedStarted, deadline, now, sessionValid, source)) {
                boolean deferForDoublePress = source != HomeSetupPolicy.HomeReturnSource.WINDOW_FALLBACK
                        && unlocked(c)
                        && c.getSharedPreferences("home", 0).getBoolean("recent_enabled", false);
                if (deferForDoublePress) {
                    int doubleWindow = c.getSharedPreferences("home", 0).getInt("recent_double_ms", 400);
                    scheduleShortPressCommit(c, source, armedBoot, armedStarted, deadline, armedSession, armedBootId, now, doubleWindow);
                } else {
                    cancelPendingShortPress();
                    p.edit().putLong("setup_home_seen", System.currentTimeMillis())
                            .putString("setup_home_source", sourceKey(source))
                            .remove("setup_home_deadline").remove("setup_home_started")
                            .remove("setup_home_boot").remove("setup_home_boot_id").remove("setup_home_session").apply();
                    Diagnostics.event("HOME_TEST_OBSERVED_" + source.name());
                }
            } else if (!sessionValid || now > deadline || now < armedStarted
                    || (armedBoot >= 0 && boot >= 0 && armedBoot != boot)) {
                invalidateHomeTest(c);
            }
        }

        if (source == HomeSetupPolicy.HomeReturnSource.SYSTEM_HOME) {
            ensureBootIdBaseline(c);
            String armedBootId = p.getString("setup_boot_armed_boot_id", "");
            String nowBootId = currentBootId();
            boolean isNewBoot = HomeSetupPolicy.bootChanged(p.getInt("setup_boot_armed", -1), boot, armedBootId, nowBootId);
            boolean alreadyObservedThisBoot = !p.getString("setup_boot_state", "").isEmpty()
                    && isSameBootRound(c, p.getString("setup_boot_round", ""),
                            p.getInt("setup_boot_observed", -1),
                            p.getString("setup_boot_observed_boot_id", ""),
                            p.getLong("setup_boot_observed_elapsed", -1L));
            boolean interrupted = isBootSessionInterrupted(p, boot);
            if (HomeSetupPolicy.nativeBootObservation(p.getBoolean("setup_requested", false), false,
                    SystemIntegration.homeIsOurs(c), isNewBoot, now,
                    source, alreadyObservedThisBoot, interrupted)) {
                String roundKey = currentBootRoundKey(c);
                p.edit().putInt("setup_boot_observed", boot)
                        .putString("setup_boot_state", BOOT_STATE_NATIVE_UNCONFIRMED)
                        .putString("setup_boot_source", BOOT_STATE_NATIVE_UNCONFIRMED)
                        .putString("setup_boot_round", roundKey)
                        .putString("setup_boot_observed_boot_id", nowBootId)
                        .putLong("setup_boot_observed_elapsed", now)
                        .putLong("setup_boot_observed_time", System.currentTimeMillis()).apply();
                Diagnostics.event("BOOT_NATIVE_HOME_UNCONFIRMED");
            }
        } else {
            // Manual compat key / broadcast / window fallback within boot window must never create a boot entry.
            markBootSessionInterrupted(c);
        }
    }

    static boolean hasCurrentRoundBootArrival(Context c) {
        SharedPreferences p = prefs(c);
        if (paused(c)) return false;
        String reqState = p.getString("boot_req_state", "");
        boolean reqCurrent = !reqState.isEmpty() && isSameBootRound(c,
                p.getString("boot_req_round", ""), p.getInt("boot_req_boot", -1),
                p.getString("boot_req_boot_id", ""), p.getLong("boot_req_elapsed", -1L));
        if (reqCurrent && BOOT_STATE_RECOVERY_ARRIVED.equals(reqState)) return true;
        String state = p.getString("setup_boot_state", "");
        boolean stateCurrent = !state.isEmpty() && isSameBootRound(c,
                p.getString("setup_boot_round", ""), p.getInt("setup_boot_observed", -1),
                p.getString("setup_boot_observed_boot_id", ""), p.getLong("setup_boot_observed_elapsed", -1L));
        return stateCurrent && (BOOT_STATE_RECOVERY_ARRIVED.equals(state) || BOOT_STATE_NATIVE_UNCONFIRMED.equals(state));
    }

    private static boolean hasCurrentRoundBootFailureOrPending(Context c, SharedPreferences p) {
        String reqState = p.getString("boot_req_state", "");
        boolean reqIsCurrentRound = !reqState.isEmpty() && isSameBootRound(c,
                p.getString("boot_req_round", ""), p.getInt("boot_req_boot", -1),
                p.getString("boot_req_boot_id", ""), p.getLong("boot_req_elapsed", -1L));
        return reqIsCurrentRound && (BOOT_STATE_LAUNCH_DENIED.equals(reqState) || BOOT_STATE_REQUESTED.equals(reqState));
    }

    static void confirmBoot(Context c) {
        SharedPreferences p = prefs(c);
        if (paused(c) || hasCurrentRoundBootFailureOrPending(c, p)) return;
        int boot = bootCount(c);
        String nowBootId = currentBootId();
        String armedBootId = p.getString("setup_boot_armed_boot_id", "");
        String state = p.getString("setup_boot_state", "");
        boolean stateIsCurrentRound = !state.isEmpty() && isSameBootRound(c,
                p.getString("setup_boot_round", ""), p.getInt("setup_boot_observed", -1),
                p.getString("setup_boot_observed_boot_id", ""), p.getLong("setup_boot_observed_elapsed", -1L));
        boolean hasBootCountObs = boot >= 0 && p.contains("setup_boot_observed")
                && p.getInt("setup_boot_observed", -1) == boot
                && boot > p.getInt("setup_boot_armed", Integer.MAX_VALUE);
        boolean hasApi23IdObs = boot < 0 && stateIsCurrentRound
                && (BOOT_STATE_RECOVERY_ARRIVED.equals(state)
                    || (BOOT_STATE_NATIVE_UNCONFIRMED.equals(state)
                        && HomeSetupPolicy.bootChanged(-1, -1, armedBootId, nowBootId)));
        if (hasBootCountObs || hasApi23IdObs) {
            p.edit().putInt("setup_boot_confirmed", boot >= 0 ? boot : 1)
                    .putString("setup_boot_confirmed_source", state.isEmpty() ? "legacy" : state)
                    .putString("setup_boot_confirmed_round", currentBootRoundKey(c))
                    .putLong("setup_boot_confirmed_time", System.currentTimeMillis()).apply();
        }
    }

    /**
     * Explicit human-observation fallback ONLY when the system exposes neither boot_count
     * nor a readable boot_id. Never fakes automatic correlation or native HOME status.
     */
    static boolean recordManualBootObservation(Context c) {
        SharedPreferences p = prefs(c);
        if (paused(c) || canIdentifyBootAutomatically(c) || hasCurrentRoundBootFailureOrPending(c, p)) return false;
        if (!p.getBoolean("setup_requested", false)) return false;
        if (!SystemIntegration.homeIsOurs(c) && !p.getBoolean("boot_home", false)) return false;
        p.edit().putInt("setup_boot_confirmed", 1)
                .putString("setup_boot_confirmed_source", BOOT_CONFIRM_MANUAL_ONLY)
                .putString("setup_boot_confirmed_round", currentBootRoundKey(c))
                .putLong("setup_boot_confirmed_time", System.currentTimeMillis()).apply();
        return true;
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
        flushPendingShortPress(c);
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

        String reqState = p.getString("boot_req_state", "");
        boolean reqCurrent = !reqState.isEmpty() && isSameBootRound(c,
                p.getString("boot_req_round", ""), p.getInt("boot_req_boot", -1),
                p.getString("boot_req_boot_id", ""), p.getLong("boot_req_elapsed", -1L));
        String histNote = BOOT_STATE_RECOVERY_ARRIVED.equals(p.getString("setup_boot_history_state", ""))
                ? "（上一轮曾成功，不代表本次生效）" : "";
        if (reqCurrent) {
            if (BOOT_STATE_RECOVERY_ARRIVED.equals(reqState)) return "已记录开机恢复到达（已关联本轮开机恢复请求）";
            if (BOOT_STATE_LAUNCH_DENIED.equals(reqState)) return "本轮已发起开机恢复请求，但系统拒绝后台启动（未自动进入）" + histNote;
            if (BOOT_STATE_REQUESTED.equals(reqState)) return "本轮已发起开机恢复请求，尚未观察到前台到达" + histNote;
        }

        String state = p.getString("setup_boot_state", "");
        boolean stateCurrent = !state.isEmpty() && isSameBootRound(c,
                p.getString("setup_boot_round", ""), p.getInt("setup_boot_observed", -1),
                p.getString("setup_boot_observed_boot_id", ""), p.getLong("setup_boot_observed_elapsed", -1L));
        if (stateCurrent) {
            if (BOOT_STATE_RECOVERY_ARRIVED.equals(state)) return "已记录开机恢复到达（已关联本轮开机恢复请求）";
            if (BOOT_STATE_NATIVE_UNCONFIRMED.equals(state)) return "观察到开机后原生 HOME 到达（来源待确认）";
        }

        if (!reqState.isEmpty()) {
            if (BOOT_STATE_LAUNCH_DENIED.equals(reqState)) return "最近一次开机恢复请求被系统拒绝后台启动（未自动进入）" + histNote;
            if (BOOT_STATE_REQUESTED.equals(reqState)) return "最近一次开机恢复请求尚未观察到前台到达" + histNote;
            if (BOOT_STATE_RECOVERY_ARRIVED.equals(reqState)) return "历史轮次曾记录开机恢复到达（本轮尚未观察到新请求）";
        }
        if (BOOT_STATE_NATIVE_UNCONFIRMED.equals(state)) return "历史轮次曾观察到开机后原生 HOME 到达（来源待确认）";
        if (p.contains("setup_boot_observed")) return "已记录历史开机观察（旧版记录，来源未知）";
        if (BOOT_CONFIRM_MANUAL_ONLY.equals(p.getString("setup_boot_confirmed_source", ""))) {
            return "系统缺少启动计数与启动标识，无法自动关联本轮重启（仅保留人工观察记录）";
        }
        if (SystemIntegration.homeIsOurs(c) || p.getBoolean("boot_home", false)) {
            return canIdentifyBootAutomatically(c)
                    ? "已配置，等待实际重启或断电冷启动验证"
                    : "已配置；本机缺少启动计数与启动标识，重启后可手动记录人工观察";
        }
        return "尚未配置";
    }

    static String bootConfirmationStatus(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.contains("setup_boot_confirmed")) return "";
        String src = p.getString("setup_boot_confirmed_source", "");
        if (BOOT_CONFIRM_MANUAL_ONLY.equals(src)) {
            return "人工观察记录：用户确认已重启返回（系统缺少启动标识，未经自动关联；完整重启与断电冷启动请现场分别记录）";
        }
        if (src.isEmpty() || "legacy".equals(src)) return "已保留旧版人工确认（来源未知）";
        int boot = bootCount(c);
        int confirmedBoot = p.getInt("setup_boot_confirmed", -1);
        String confirmedRound = p.getString("setup_boot_confirmed_round", "");
        if (boot >= 0 && confirmedBoot >= 0 && confirmedBoot != boot) {
            return "历史轮次曾人工确认重启返回（非本次启动）";
        }
        if (!confirmedRound.isEmpty() && !confirmedRound.equals(currentBootRoundKey(c))) {
            return "历史轮次曾人工确认重启返回（非本次启动）";
        }
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
