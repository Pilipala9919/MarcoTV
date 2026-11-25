// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import java.net.*;
import java.text.DateFormat;
import java.util.*;

final class SystemIntegration {
    interface DialogHost {
        void show(TvDialog dialog);
    }
    private static final String TAG = "MarcoTVSystem";
    private static final String OWN_SERVICE = "com.pilipala.tvhome/.HomeKeyService";
    static final String PREF_GUARD_MODE = "home_guard_mode";
    static final String PREF_USER_DESKTOP_CHOICE = "home_desktop_choice";
    static final String PREF_OEM_EXP_PREFIX = "oem_exp_";
    static final String PREF_REBIND_STATE = "home_rebind_state";
    static final String REBIND_STATE_REMOVED = "removed";
    static final String REBIND_STATE_WRITTEN = "written";
    private static final String PREF_REBIND_EXPECTED_SERVICES = "home_rebind_expected_services";
    private static final String PREF_REBIND_EXPECTED_ENABLED = "home_rebind_expected_enabled";
    static final int HOME_FILTER_REVISION = 2;

    static HomeSetupPolicy.GuardMode guardMode(Context context) {
        SharedPreferences p = prefs(context);
        return HomeSetupPolicy.resolveGuardMode(
                p.getString(PREF_GUARD_MODE, ""), p.getBoolean("home_native_keys", false));
    }
    static HomeSetupPolicy.DesktopChoice desktopChoice(Context context) {
        SharedPreferences p = prefs(context);
        return HomeSetupPolicy.resolveDesktopChoice(
                p.getString(PREF_USER_DESKTOP_CHOICE, ""),
                HomeSetup.paused(context),
                homeIsOurs(context),
                p.getBoolean("home_compat", false),
                guardMode(context));
    }
    static void setDesktopChoice(Context context, HomeSetupPolicy.DesktopChoice choice) {
        if (choice == null) return;
        prefs(context).edit().putString(PREF_USER_DESKTOP_CHOICE, choice.prefValue).commit();
    }
    static HomeSetupPolicy.DesktopState desktopState(Context context) {
        SharedPreferences p = prefs(context);
        return HomeSetupPolicy.resolveDesktopState(
                desktopChoice(context),
                BuildEdition.RESTRICTED,
                HomeSetup.paused(context),
                homeIsOurs(context),
                currentHome(context),
                p.getBoolean("home_compat", false),
                HomeSetup.serviceEnabled(context),
                HomeKeyService.connected,
                guardMode(context),
                TaskBridgeClient.connected(),
                TaskBridgeClient.volumeAssistActive(),
                HomeKeyService.runtimeVolumeYieldActive,
                HomeKeyService.runtimeKeyFilterRequested,
                HomeKeyService.runtimeModeApplied,
                HomeKeyService.runtimeVolumeYieldFailed);
    }
    static void setGuardMode(Context context, HomeSetupPolicy.GuardMode mode) {
        HomeSetupPolicy.GuardMode target = mode == null ? HomeSetupPolicy.GuardMode.WINDOW_PULLBACK : mode;
        HomeSetupPolicy.DesktopChoice choice = target == HomeSetupPolicy.GuardMode.KEY_FILTER
                ? HomeSetupPolicy.DesktopChoice.KEY_FILTER
                : target == HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY
                        ? HomeSetupPolicy.DesktopChoice.NATIVE_KEYS_ONLY
                        : HomeSetupPolicy.DesktopChoice.WINDOW_PULLBACK;
        prefs(context).edit()
                .putString(PREF_GUARD_MODE, target.prefValue)
                .putString(PREF_USER_DESKTOP_CHOICE, choice.prefValue)
                .putBoolean("home_native_keys", target == HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY)
                .commit();
        if (target != HomeSetupPolicy.GuardMode.KEY_FILTER) TaskBridgeClient.clearPendingVolumeAssistRetry();
        HomeKeyService.syncRuntimeKeyFilter(context);
        TaskBridgeClient.sync(context);
        Diagnostics.event("HOME_GUARD_MODE_" + target.name());
    }
    /** Switches runtime guard mode without overwriting user's preferred desktop choice (e.g. NATIVE_HOME fallback). */
    static void setGuardModePreservingChoice(Context context, HomeSetupPolicy.GuardMode mode) {
        HomeSetupPolicy.GuardMode target = mode == null ? HomeSetupPolicy.GuardMode.WINDOW_PULLBACK : mode;
        prefs(context).edit()
                .putString(PREF_GUARD_MODE, target.prefValue)
                .putBoolean("home_native_keys", target == HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY)
                .commit();
        if (target != HomeSetupPolicy.GuardMode.KEY_FILTER) TaskBridgeClient.clearPendingVolumeAssistRetry();
        HomeKeyService.syncRuntimeKeyFilter(context);
        TaskBridgeClient.sync(context);
        Diagnostics.event("HOME_GUARD_FALLBACK_" + target.name());
    }
    static String guardModeLabel(HomeSetupPolicy.GuardMode mode) {
        if (mode == HomeSetupPolicy.GuardMode.KEY_FILTER) return "快速 Home（请求按键过滤）";
        if (mode == HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY) return "原厂按键（不自动回拉）";
        return "窗口回拉（兼容模式，不拦截按键）";
    }
    static boolean isRebindPending(Context context) {
        String state = prefs(context).getString(PREF_REBIND_STATE, "");
        return REBIND_STATE_REMOVED.equals(state) || REBIND_STATE_WRITTEN.equals(state);
    }
    static final class OemExperimentSnapshot {
        final int userId;
        final String priorHome;
        final String oemPackage;
        final int priorState;
        OemExperimentSnapshot(int userId, String priorHome, String oemPackage, int priorState) {
            this.userId = userId;
            this.priorHome = priorHome;
            this.oemPackage = oemPackage;
            this.priorState = priorState;
        }
    }
    static final class OemDisablePlan {
        final String blockedMessage;
        final OemExperimentSnapshot snapshot;
        OemDisablePlan(String blockedMessage, OemExperimentSnapshot snapshot) {
            this.blockedMessage = blockedMessage;
            this.snapshot = snapshot;
        }
        boolean allowed() { return blockedMessage == null && snapshot != null; }
    }
    static int currentUserId() {
        return android.os.Process.myUid() / 100000;
    }
    static boolean isSupportedOemEnabledState(int state) {
        return state >= PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                && state <= PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
    }
    static OemExperimentSnapshot readValidOemExperimentSnapshot(Context context, int userId) {
        SharedPreferences p = prefs(context);
        if (!p.getBoolean(PREF_OEM_EXP_PREFIX + "active", false)) return null;
        if (!p.contains(PREF_OEM_EXP_PREFIX + "user") || !p.contains(PREF_OEM_EXP_PREFIX + "prior_state")) return null;
        int savedUser = p.getInt(PREF_OEM_EXP_PREFIX + "user", -1);
        if (savedUser != userId) return null;
        String oemPkg = p.getString(PREF_OEM_EXP_PREFIX + "package", "");
        String priorHome = p.getString(PREF_OEM_EXP_PREFIX + "prior_home", "");
        int priorState = p.getInt(PREF_OEM_EXP_PREFIX + "prior_state", -1);
        if (!OemLauncherExperimentPolicy.validOemPackage(oemPkg)) return null;
        if (!HomeShellSetup.validComponent(priorHome)) return null;
        if (OemLauncherExperimentPolicy.isFallbackOrBrokenHome(priorHome)) return null;
        if (priorHome.startsWith(context.getPackageName() + "/")) return null;
        if (!isSupportedOemEnabledState(priorState)) return null;
        return new OemExperimentSnapshot(savedUser, priorHome, oemPkg, priorState);
    }
    static boolean hasValidOemExperimentSnapshot(Context context) {
        return readValidOemExperimentSnapshot(context, currentUserId()) != null;
    }
    static boolean hasValidOemExperimentSnapshot(Context context, int userId) {
        return readValidOemExperimentSnapshot(context, userId) != null;
    }
    static boolean hasDisabledOemExperiment(Context context) {
        return hasValidOemExperimentSnapshot(context);
    }
    static String disabledOemExperimentPackage(Context context) {
        OemExperimentSnapshot snap = readValidOemExperimentSnapshot(context, currentUserId());
        return snap == null ? "" : snap.oemPackage;
    }
    static String oemExperimentCandidatePackage(Context context) {
        String disabled = disabledOemExperimentPackage(context);
        if (!disabled.isEmpty()) return disabled;
        String current = currentExplicitOtherHome(context);
        if (current.isEmpty()) current = trackedHome(context);
        ComponentName cn = ComponentName.unflattenFromString(current);
        return cn == null ? "" : cn.getPackageName();
    }
    static boolean isOemPackageEnabled(Context context, String pkg) {
        if (!OemLauncherExperimentPolicy.validOemPackage(pkg)) return false;
        try {
            List<ResolveInfo> homes = context.getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
            if (homes != null) {
                for (ResolveInfo r : homes) {
                    ActivityInfo a = r.activityInfo;
                    if (a != null && pkg.equals(a.packageName) && a.enabled && a.exported
                            && a.applicationInfo != null && a.applicationInfo.enabled) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException ignored) { }
        return false;
    }
    static final int APPLICATION_ENABLED_STATE_UNKNOWN = -1;
    static int readApplicationEnabledState(Context context, String pkg) {
        if (!OemLauncherExperimentPolicy.validOemPackage(pkg)) return APPLICATION_ENABLED_STATE_UNKNOWN;
        try {
            return context.getPackageManager().getApplicationEnabledSetting(pkg);
        } catch (RuntimeException ignored) {
            return APPLICATION_ENABLED_STATE_UNKNOWN;
        }
    }
    static boolean isDisabledOemEnabledState(int state) {
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
    }
    static boolean isOemExperimentRestoreVerified(Context context, OemExperimentSnapshot snap) {
        if (snap == null) return false;
        int actualState = readApplicationEnabledState(context, snap.oemPackage);
        if (actualState == APPLICATION_ENABLED_STATE_UNKNOWN || actualState != snap.priorState) return false;
        boolean expectEnabled = snap.priorState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                || snap.priorState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        if (expectEnabled && !isOemPackageEnabled(context, snap.oemPackage)) return false;
        String resolved = currentHome(context);
        if (OemLauncherExperimentPolicy.isFallbackOrBrokenHome(resolved)) return false;
        if (homeIsOurs(context)) return false;
        ComponentName expectedCn = ComponentName.unflattenFromString(snap.priorHome);
        ComponentName actualCn = ComponentName.unflattenFromString(resolved);
        return expectedCn != null && expectedCn.equals(actualCn);
    }
    static boolean hasIndependentRecoveryPath(Context context) {
        // Require a genuine rescue path independent of Marco TV being able to launch (ADB or System Settings).
        boolean adbOn = Settings.Global.getInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
        return adbOn || HomeSetup.canOpen(context, Settings.ACTION_SETTINGS);
    }
    static void saveOemExperimentSnapshot(Context context, int userId, String priorHome,
                                          String oemPackage, int priorState) {
        prefs(context).edit()
                .putBoolean(PREF_OEM_EXP_PREFIX + "active", true)
                .putInt(PREF_OEM_EXP_PREFIX + "user", userId)
                .putString(PREF_OEM_EXP_PREFIX + "prior_home", priorHome == null ? "" : priorHome)
                .putString(PREF_OEM_EXP_PREFIX + "package", oemPackage == null ? "" : oemPackage)
                .putInt(PREF_OEM_EXP_PREFIX + "prior_state", priorState)
                .commit();
    }
    static void clearOemExperimentSnapshot(Context context) {
        prefs(context).edit()
                .remove(PREF_OEM_EXP_PREFIX + "active")
                .remove(PREF_OEM_EXP_PREFIX + "user")
                .remove(PREF_OEM_EXP_PREFIX + "prior_home")
                .remove(PREF_OEM_EXP_PREFIX + "package")
                .remove(PREF_OEM_EXP_PREFIX + "prior_state")
                .commit();
    }
    static boolean canLaunchMarcoForeground(Context context) {
        try {
            Intent intent = homeIntent(context);
            ResolveInfo info = context.getPackageManager().resolveActivity(intent, 0);
            return info != null && info.activityInfo != null
                    && context.getPackageName().equals(info.activityInfo.packageName)
                    && info.activityInfo.enabled
                    && (info.activityInfo.applicationInfo == null || info.activityInfo.applicationInfo.enabled);
        } catch (RuntimeException ignored) {
            return false;
        }
    }
    static OemDisablePlan prepareOemExperimentDisable(Context context, int userId,
                                                      String model, String firmware,
                                                      List<OemLauncherExperimentPolicy.VerifiedDevice> allowlist) {
        String priorHome = currentExplicitOtherHome(context);
        if (priorHome.isEmpty()) priorHome = trackedHome(context);
        String oemPkg = oemExperimentCandidatePackage(context);
        OemLauncherExperimentPolicy.Decision decision = OemLauncherExperimentPolicy.evaluate(
                BuildEdition.RESTRICTED, model, firmware, oemPkg, allowlist);
        if (!decision.allowed()) {
            Diagnostics.event("OEM_EXP_BLOCKED_" + decision.eligibility.name());
            return new OemDisablePlan("已阻止执行停用原厂桌面：\n" + decision.reason, null);
        }
        if (!HomeShellSetup.validComponent(priorHome)
                || OemLauncherExperimentPolicy.isFallbackOrBrokenHome(priorHome)
                || priorHome.startsWith(context.getPackageName() + "/")) {
            Diagnostics.event("OEM_EXP_BLOCKED_NO_PRIOR_HOME");
            return new OemDisablePlan("未能读取到有效的原默认桌面组件，已阻止执行停用原厂桌面。", null);
        }
        if (!hasIndependentRecoveryPath(context)) {
            Diagnostics.event("OEM_EXP_BLOCKED_NO_RECOVERY");
            return new OemDisablePlan("未检测到独立的恢复途径（ADB 调试或系统设置入口不可用），为避免无法恢复，已阻止执行停用原厂桌面。", null);
        }
        int priorState = readApplicationEnabledState(context, oemPkg);
        if (!isSupportedOemEnabledState(priorState)) {
            Diagnostics.event("OEM_EXP_BLOCKED_UNSUPPORTED_STATE");
            return new OemDisablePlan("无法可靠读取原厂桌面当前的启用状态（" + priorState + "），已阻止执行停用原厂桌面。", null);
        }
        return new OemDisablePlan(null, new OemExperimentSnapshot(userId, priorHome, oemPkg, priorState));
    }
    static void beginOemExperimentDisableLocal(Context context, OemExperimentSnapshot snap) {
        saveOemExperimentSnapshot(context, snap.userId, snap.priorHome, snap.oemPackage, snap.priorState);
        enableHome(context, true);
    }
    static String verifyOemExperimentDisableLocal(Context context, OemExperimentSnapshot snap, Boolean foregroundOkOverride) {
        int actualState = snap == null ? APPLICATION_ENABLED_STATE_UNKNOWN : readApplicationEnabledState(context, snap.oemPackage);
        boolean pkgActuallyDisabled = snap != null
                && isDisabledOemEnabledState(actualState)
                && !isOemPackageEnabled(context, snap.oemPackage);
        if (!pkgActuallyDisabled) {
            return "停用命令未生效：原厂桌面包（" + (snap == null ? "未知" : snap.oemPackage)
                    + "）当前状态=" + actualState + "，仍处于启用或无法核实停用状态";
        }
        String resolved = currentHome(context);
        boolean validHome = OemLauncherExperimentPolicy.isValidPostDisableHome(resolved, context.getPackageName());
        boolean staticLaunchable = canLaunchMarcoForeground(context);
        if (!validHome) {
            return "停用后系统未将 HOME 解析为 Marco TV（当前解析："
                    + (resolved.isEmpty() ? "空 / 解析异常" : resolved) + "）";
        }
        if (!staticLaunchable) {
            return "停用后 Marco TV 无法进入前台";
        }
        if (!Boolean.TRUE.equals(foregroundOkOverride)) {
            return "停用后仅观察到静态 HOME 解析，未核实 Marco TV 实际进入前台与可返回桌面（开放首个机型前须补齐实机前台核验）";
        }
        return null;
    }
    static String finalizeOemExperimentDisableSuccessLocal(Context context, OemExperimentSnapshot snap) {
        setDesktopChoice(context, HomeSetupPolicy.DesktopChoice.NATIVE_HOME);
        HomeSetup.begin(context);
        HomeSetup.acceptNative(context);
        HomeKeyService.syncRuntimeKeyFilter(context);
        TaskBridgeClient.sync(context);
        Diagnostics.event("OEM_EXP_DISABLE_VERIFIED");
        return "已停用原厂桌面（" + snap.oemPackage + "）并回读确认系统 HOME 解析为 Marco TV，已停止额外 Home 拦截。\n"
                + "已记录恢复快照（用户 " + snap.userId + "，原 HOME：" + snap.priorHome + "）；如需切回，请随时使用「恢复原厂桌面」。";
    }
    static String finalizeOemExperimentDisableRollbackLocal(Context context, OemExperimentSnapshot snap,
                                                            String failureReason) {
        boolean restoredOk = isOemExperimentRestoreVerified(context, snap);
        if (restoredOk) {
            clearOemExperimentSnapshot(context);
            Diagnostics.event("OEM_EXP_AUTO_ROLLBACK_OK");
            return "实验未通过，已自动恢复原厂桌面（" + snap.oemPackage + "）并核实原 HOME（" + snap.priorHome + "）可用。\n原因：" + failureReason;
        }
        Diagnostics.event("OEM_EXP_AUTO_ROLLBACK_INCOMPLETE");
        return "实验未通过，自动回滚未能完整核实原厂桌面状态（期望 HOME：" + snap.priorHome + "，当前："
                + (currentHome(context).isEmpty() ? "空" : currentHome(context)) + "）。\n原因：" + failureReason
                + "\n快照已保留，请立即点击「恢复原厂桌面」或在电脑执行：adb shell pm enable --user " + snap.userId + " " + snap.oemPackage;
    }
    static String finalizeOemExperimentExternalRestoreLocal(Context context, OemExperimentSnapshot snap) {
        String resolved = currentHome(context);
        clearOemExperimentSnapshot(context);
        Diagnostics.event("OEM_EXP_RESTORED_EXTERNALLY");
        return "检测到原厂桌面（" + snap.oemPackage + "，状态=" + snap.priorState
                + "）与原 HOME（" + resolved + "）已在外部恢复，已就地结束实验并清理快照（未执行 ADB 写入）。";
    }
    static String finalizeOemExperimentRestoreLocal(Context context, OemExperimentSnapshot snap) {
        String resolved = currentHome(context);
        int actualState = readApplicationEnabledState(context, snap.oemPackage);
        if (isOemExperimentRestoreVerified(context, snap)) {
            clearOemExperimentSnapshot(context);
            Diagnostics.event("OEM_EXP_RESTORED");
            return "已按快照恢复原厂桌面（" + snap.oemPackage + "，状态=" + snap.priorState
                    + "）并核实当前系统 HOME 为原记录（" + resolved + "）。";
        }
        Diagnostics.event("OEM_EXP_RESTORE_UNVERIFIED");
        return "恢复原厂桌面未通过核验：快照仍保留，可重试恢复。\n"
                + "目标包：" + snap.oemPackage + "（期望状态=" + snap.priorState
                + "，实际=" + (actualState == APPLICATION_ENABLED_STATE_UNKNOWN ? "读取失败" : String.valueOf(actualState)) + "）\n"
                + "期望 HOME：" + snap.priorHome + "\n"
                + "当前系统 HOME：" + (resolved.isEmpty() ? "空 / 解析异常" : resolved);
    }
    /**
     * Standard-edition-only experimental OEM launcher disable with immediate readback and auto-rollback.
     * Excluded from automatic fallback; requires verified allowlist, independent recovery check, and explicit confirmation.
     */
    static String executeOemExperimentDisable(Context context, HomeShellSetup shell, int userId,
                                              String model, String firmware,
                                              List<OemLauncherExperimentPolicy.VerifiedDevice> allowlist,
                                              Boolean foregroundOkOverride) throws Exception {
        OemDisablePlan plan = prepareOemExperimentDisable(context, userId, model, firmware, allowlist);
        if (!plan.allowed()) return plan.blockedMessage;
        OemExperimentSnapshot snap = plan.snapshot;
        beginOemExperimentDisableLocal(context, snap);
        String failureReason = null;
        try {
            shell.disableOemPackage(snap.oemPackage);
            shell.selectHome(HomeSetup.OWN);
            failureReason = verifyOemExperimentDisableLocal(context, snap, foregroundOkOverride);
        } catch (Exception e) {
            failureReason = "执行过程出现异常（" + e.getClass().getSimpleName() + "）";
        }
        if (failureReason != null) {
            try {
                shell.restoreOemPackageState(snap.oemPackage, snap.priorState);
                shell.selectHome(snap.priorHome);
            } catch (Exception ignored) { }
            return finalizeOemExperimentDisableRollbackLocal(context, snap, failureReason);
        }
        return finalizeOemExperimentDisableSuccessLocal(context, snap);
    }
    static String executeOemExperimentRestore(Context context, HomeShellSetup shell) throws Exception {
        return executeOemExperimentRestore(context, shell, currentUserId());
    }
    static String executeOemExperimentRestore(Context context, HomeShellSetup shell, int userId) throws Exception {
        OemExperimentSnapshot snap = readValidOemExperimentSnapshot(context, userId);
        if (snap == null) {
            Diagnostics.event("OEM_EXP_RESTORE_NO_SNAPSHOT");
            return "当前用户没有有效的停用原厂桌面实验快照，未执行任何修改。";
        }
        if (isOemExperimentRestoreVerified(context, snap)) {
            return finalizeOemExperimentExternalRestoreLocal(context, snap);
        }
        shell.restoreOemPackageState(snap.oemPackage, snap.priorState);
        shell.selectHome(snap.priorHome);
        return finalizeOemExperimentRestoreLocal(context, snap);
    }

    static SharedPreferences prefs(Context context) {
        Context storage = Build.VERSION.SDK_INT >= 24 ? context.createDeviceProtectedStorageContext() : context;
        return storage.getSharedPreferences("system_integration", Context.MODE_PRIVATE);
    }
    static boolean authorized(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }
    static Intent homeIntent(Context context) {
        return new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }
    static final long ADB_RESTORE_WINDOW = 24L * 60 * 60 * 1000;
    /** adb_restore_until 0 or missing = long-term (explicit choice, or enabled before time limits existed). */
    static String adbRestoreLabel(Context context) {
        expireAdbRestore(context);
        SharedPreferences p = prefs(context);
        if (!p.getBoolean("adb_restore", false)) return "未开启";
        long until = p.getLong("adb_restore_until", 0);
        if (until <= 0) return "已开启（长期）";
        long hours = Math.max(1, (until - System.currentTimeMillis() + 3599999) / 3600000);
        return "已开启（约 " + hours + " 小时后自动停止）";
    }
    /** Ends a timed ADB restore window. The current debugging switch is left as it is. */
    static boolean expireAdbRestore(Context context) {
        SharedPreferences p = prefs(context);
        long until = p.getLong("adb_restore_until", 0);
        if (!p.getBoolean("adb_restore", false) || until <= 0 || System.currentTimeMillis() < until) return false;
        p.edit().putBoolean("adb_restore", false).remove("adb_restore_until")
                .putString("last_notice", "ADB 自动恢复已到设定时长并自动停止；调试开关保持当前状态，可在开发者选项中手动关闭。").apply();
        Diagnostics.event("ADB_RESTORE_EXPIRED");
        return true;
    }
    static boolean needsRecovery(Context context) {
        expireAdbRestore(context);
        SharedPreferences p = prefs(context);
        return p.getBoolean("adb_restore", false) || p.getBoolean("home_compat", false) || p.getBoolean("managed_home", false);
    }
    static void startRecovery(Context context) {
        TaskBridgeClient.sync(context);
        Intent intent = new Intent(context, RecoveryService.class);
        if (!needsRecovery(context) || !authorized(context)) {
            context.stopService(intent);
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (RuntimeException e) {
            prefs(context).edit().putString("last_error", "系统阻止后台恢复，请打开 Marco TV 后重试。").apply();
            Log.w(TAG, "Cannot start recovery", e);
        }
    }
    static String currentHome(Context context) {
        // CATEGORY_DEFAULT is required: OEM firmware keeps its launcher as the fallback
        // answer without it, even when another home is the registered preference.
        ResolveInfo result = context.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME).addCategory(Intent.CATEGORY_DEFAULT), PackageManager.MATCH_DEFAULT_ONLY);
        return result == null || result.activityInfo == null ? "" : new ComponentName(result.activityInfo.packageName, result.activityInfo.name)
                .flattenToString();
    }
    static boolean homeIsOurs(Context context) {
        if(BuildEdition.RESTRICTED)return false;
        // A stored preference is not enough: OEM firmware may still route HOME elsewhere.
        return currentHome(context).startsWith(context.getPackageName() + "/");
    }
    static boolean homePreferenceIsOurs(Context context) {
        if(BuildEdition.RESTRICTED)return false;
        if (homeIsOurs(context)) return true;
        ArrayList<IntentFilter> filters = new ArrayList<>();
        ArrayList<ComponentName> names = new ArrayList<>();
        try {
            context.getPackageManager().getPreferredActivities(filters, names, null);
        }
        catch (RuntimeException e) {
            return false;
        }
        for (int i=0;i<names.size()&&i<filters.size();i++) if (names.get(i).getPackageName().equals(context.getPackageName())&&filters.get(i)
                .hasAction(Intent.ACTION_MAIN)&&filters.get(i).hasCategory(Intent.CATEGORY_HOME)) return true;
        return false;
    }
    static boolean isExcludedHomeComponent(Context context, ComponentName component) {
        if (component == null) return true;
        String pkg = component.getPackageName();
        String cls = component.getClassName();
        if (pkg == null || pkg.isEmpty()) return true;
        if (pkg.equals(context.getPackageName())
                || pkg.equals("android")
                || pkg.equals("com.android.settings")
                || pkg.equals("com.android.tv.settings")) return true;
        return cls != null && (cls.endsWith("EmptyStubActivity")
                || cls.endsWith("FallbackHome")
                || cls.endsWith("ResolverActivity"));
    }
    static boolean isValidTrackedHome(Context context, String value) {
        if (!HomeShellSetup.validComponent(value)) return false;
        ComponentName target = ComponentName.unflattenFromString(value);
        if (isExcludedHomeComponent(context, target)) return false;
        try {
            List<ResolveInfo> homes = context.getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
            if (homes == null) return false;
            for (ResolveInfo r : homes) {
                ActivityInfo a = r.activityInfo;
                if (a != null && a.enabled && a.exported && a.applicationInfo != null && a.applicationInfo.enabled
                        && target.equals(new ComponentName(a.packageName, a.name))) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }
    static String currentExplicitOtherHome(Context context) {
        String home = currentHome(context);
        return isValidTrackedHome(context, home) ? home : "";
    }
    private static String discoverUniqueOtherHome(Context context) {
        try {
            List<ResolveInfo> homes = context.getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
            if (homes == null) return "";
            String candidate = "";
            String candidatePkg = "";
            for (ResolveInfo r : homes) {
                ActivityInfo a = r.activityInfo;
                if (a == null || !a.enabled || !a.exported || a.applicationInfo == null || !a.applicationInfo.enabled) continue;
                ComponentName cn = new ComponentName(a.packageName, a.name);
                if (isExcludedHomeComponent(context, cn)) continue;
                String flat = cn.flattenToString();
                if (candidate.isEmpty()) {
                    candidate = flat;
                    candidatePkg = a.packageName;
                } else if (!candidatePkg.equals(a.packageName)) {
                    return "";
                }
            }
            return candidate;
        } catch (RuntimeException ignored) {
            return "";
        }
    }
    /** Passive read-only access to the validated compatibility target. Never mutates prefs. */
    static String trackedHome(Context context) {
        String saved = prefs(context).getString("original_home", "");
        return isValidTrackedHome(context, saved) ? saved : "";
    }
    static boolean matchesTrackedHomePackage(Context context, String shown) {
        if (shown == null || context.getPackageName().equals(shown)) return false;
        String raw = prefs(context).getString("original_home", "");
        ComponentName original = ComponentName.unflattenFromString(raw);
        if (original == null || context.getPackageName().equals(original.getPackageName())) return false;
        boolean candidateMatch = original.getPackageName().equals(shown)
                || (original.getPackageName().equals("com.mitv.tvhome") && shown.equals("com.xiaomi.eetv.litehome"));
        return candidateMatch && isValidTrackedHome(context, raw);
    }
    static boolean trackedHomeInvalid(Context context) {
        SharedPreferences p = prefs(context);
        String saved = p.getString("original_home", "");
        if (!saved.isEmpty()) return !isValidTrackedHome(context, saved);
        return p.contains("original_home_invalid");
    }
    static String trackedHomeInvalidName(Context context) {
        SharedPreferences p = prefs(context);
        String saved = p.getString("original_home", "");
        if (!saved.isEmpty() && !isValidTrackedHome(context, saved)) return saved;
        return p.getString("original_home_invalid", "");
    }
    /**
     * First-time discovery and passive lifecycle maintenance.
     * Never overwrites a still-valid stored target on normal app open or status refresh.
     * Stops relying on an uninstalled/disabled target and syncs the bridge.
     */
    static void rememberHome(Context context) {
        SharedPreferences p = prefs(context);
        String saved = p.getString("original_home", "");
        if (!saved.isEmpty()) {
            if (isValidTrackedHome(context, saved)) return;
            String replacement = discoverUniqueOtherHome(context);
            SharedPreferences.Editor e = p.edit();
            if (!replacement.isEmpty()) e.putString("original_home", replacement).remove("original_home_invalid");
            else e.remove("original_home").putString("original_home_invalid", saved);
            e.apply();
            TaskBridgeClient.sync(context);
            return;
        }
        if (p.contains("original_home_invalid")) return;
        String candidate = currentExplicitOtherHome(context);
        if (candidate.isEmpty()) candidate = discoverUniqueOtherHome(context);
        if (!candidate.isEmpty()) {
            p.edit().putString("original_home", candidate).remove("original_home_invalid").apply();
            TaskBridgeClient.sync(context);
        }
    }
    /**
     * Explicit user enable / re-enable path, called BEFORE changing system HOME.
     * Allows a real third-party launcher currently selected by the system; never guesses
     * self, chooser, settings placeholder, disabled, or ambiguous candidates.
     */
    static String refreshTrackedHomeForSetup(Context context) {
        SharedPreferences p = prefs(context);
        String saved = p.getString("original_home", "");
        String current = currentExplicitOtherHome(context);
        if (!current.isEmpty()) {
            boolean changed = !current.equals(saved) || p.contains("original_home_invalid");
            if (changed) {
                p.edit().putString("original_home", current).remove("original_home_invalid").apply();
                TaskBridgeClient.sync(context);
            }
            return current;
        }
        if (!saved.isEmpty() && isValidTrackedHome(context, saved)) {
            if (p.contains("original_home_invalid")) p.edit().remove("original_home_invalid").apply();
            return saved;
        }
        String unique = discoverUniqueOtherHome(context);
        if (!unique.isEmpty()) {
            p.edit().putString("original_home", unique).remove("original_home_invalid").apply();
            TaskBridgeClient.sync(context);
            return unique;
        }
        if (!saved.isEmpty()) {
            p.edit().remove("original_home").putString("original_home_invalid", saved).apply();
            TaskBridgeClient.sync(context);
        }
        return "";
    }
    /** Updates tracked target to an explicit pre-captured launcher B (used when system HOME just switched to Marco). */
    static void adoptExplicitTrackedHome(Context context, String candidate) {
        if (candidate == null || candidate.isEmpty() || !isValidTrackedHome(context, candidate)) return;
        SharedPreferences p = prefs(context);
        String saved = p.getString("original_home", "");
        if (!candidate.equals(saved) || p.contains("original_home_invalid")) {
            p.edit().putString("original_home", candidate).remove("original_home_invalid").apply();
            TaskBridgeClient.sync(context);
        }
    }
    static int homeAliasState(Context context) {
        if (BuildEdition.RESTRICTED) return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        try {
            return context.getPackageManager().getComponentEnabledSetting(
                    new ComponentName(context, "com.pilipala.tvhome.HomeActivity"));
        } catch (RuntimeException e) {
            return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        }
    }
    static void setHomeAliasState(Context context, int state) {
        if (BuildEdition.RESTRICTED) return;
        try {
            context.getPackageManager().setComponentEnabledSetting(
                    new ComponentName(context, "com.pilipala.tvhome.HomeActivity"),
                    state, PackageManager.DONT_KILL_APP);
        } catch (RuntimeException ignored) { }
    }
    static void syncTrackedHomeAfterPackageChange(Context context) {
        SharedPreferences p = prefs(context);
        String saved = p.getString("original_home", "");
        if (saved.isEmpty() || isValidTrackedHome(context, saved)) return;
        String replacement = discoverUniqueOtherHome(context);
        SharedPreferences.Editor e = p.edit();
        if (!replacement.isEmpty()) e.putString("original_home", replacement).remove("original_home_invalid");
        else e.remove("original_home").putString("original_home_invalid", saved);
        e.apply();
        TaskBridgeClient.sync(context);
    }
    private static void persistRebindActualSnapshot(Context context) {
        String actualServices = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        int actualEnabled = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        prefs(context).edit()
                .putString(PREF_REBIND_STATE, REBIND_STATE_REMOVED)
                .putString(PREF_REBIND_EXPECTED_SERVICES, actualServices == null ? "" : actualServices)
                .putInt(PREF_REBIND_EXPECTED_ENABLED, actualEnabled)
                .commit();
    }
    private static boolean ownServiceListed(Context context) {
        String raw = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        for (String s : services(raw)) if (own(s)) return true;
        return false;
    }
    private enum RebindGate {
        NONE, OWN_READY, OWN_WAIT, EXTERNAL_ABORT
    }
    private static void abortPendingRebindForExternalChange(Context context) {
        clearPersistedRebind(context);
        writtenServices = null;
        writtenEnabled = -1;
        respectExternalHomeServiceOff(context, false);
    }
    /**
     * Unified migration-record gate shared by foreground, boot, and standby recovery paths
     * before any write to Settings.Secure. Preserves external/user changes instead of re-enabling
     * Marco or flipping ACCESSIBILITY_ENABLED back to 1.
     */
    private static RebindGate verifyPendingRebindBeforeWrite(Context context) {
        SharedPreferences p = prefs(context);
        String state = p.getString(PREF_REBIND_STATE, "");
        if (state.isEmpty()) return RebindGate.NONE;
        if (HomeSetup.paused(context) || !p.getBoolean("home_compat", false)) {
            clearPersistedRebind(context);
            return RebindGate.EXTERNAL_ABORT;
        }
        if (REBIND_STATE_REMOVED.equals(state)) {
            if (HomeSetup.serviceEnabled(context)) {
                rebindMinGeneration = Math.max(rebindMinGeneration, HomeKeyService.connectionGeneration);
                p.edit().putString(PREF_REBIND_STATE, REBIND_STATE_WRITTEN)
                        .remove(PREF_REBIND_EXPECTED_SERVICES)
                        .remove(PREF_REBIND_EXPECTED_ENABLED)
                        .commit();
                markHomeServiceRebindConnected(context);
                return RebindGate.NONE;
            }
            if (!authorized(context) || !matchesPersistedRebindRemoved(context)) {
                abortPendingRebindForExternalChange(context);
                return RebindGate.EXTERNAL_ABORT;
            }
            return android.os.SystemClock.elapsedRealtime() < rebindUntil
                    ? RebindGate.OWN_WAIT : RebindGate.OWN_READY;
        }
        if (REBIND_STATE_WRITTEN.equals(state)) {
            if (HomeSetup.serviceEnabled(context)) return RebindGate.NONE;
            abortPendingRebindForExternalChange(context);
            return RebindGate.EXTERNAL_ABORT;
        }
        clearPersistedRebind(context);
        return RebindGate.NONE;
    }
    private static void reconcileRebindStateAfterRestoreWrite(Context context, boolean rebindRemoved, boolean updateCompatSeen) {
        SharedPreferences p = prefs(context);
        boolean restoredNow = HomeSetup.serviceEnabled(context);
        if (updateCompatSeen && (restoredNow || !rebindRemoved)) {
            p.edit().putBoolean("compat_seen_present", restoredNow).apply();
        }
        if (!rebindRemoved) return;
        if (restoredNow) {
            p.edit().putString(PREF_REBIND_STATE, REBIND_STATE_WRITTEN)
                    .remove(PREF_REBIND_EXPECTED_SERVICES)
                    .remove(PREF_REBIND_EXPECTED_ENABLED)
                    .commit();
        } else if (ownServiceListed(context)) {
            // Partial write on restore (whether putInt returned false or threw SecurityException):
            // service list contains Marco while global ACCESSIBILITY_ENABLED stayed 0. Record the
            // actual on-disk snapshot so retries and page re-entry preserve user choices.
            persistRebindActualSnapshot(context);
        }
    }
    static void resumePendingHomeServiceRebind(Context context) {
        String state = prefs(context).getString(PREF_REBIND_STATE, "");
        if (REBIND_STATE_REMOVED.equals(state) || REBIND_STATE_WRITTEN.equals(state)) {
            refreshHomeService(context);
        }
    }
    static void refreshHomeService(Context context) {
        HomeKeyService.syncRuntimeKeyFilter(context);
        SharedPreferences p = prefs(context);
        String rebindState = p.getString(PREF_REBIND_STATE, "");
        if (REBIND_STATE_REMOVED.equals(rebindState) || REBIND_STATE_WRITTEN.equals(rebindState)) {
            RebindGate gate = verifyPendingRebindBeforeWrite(context);
            if (gate == RebindGate.OWN_READY) {
                completeHomeServiceRebind(context, 0);
            } else if (gate == RebindGate.NONE && HomeSetup.serviceEnabled(context)) {
                markHomeServiceRebindConnected(context);
            }
            return;
        }
        if (p.getInt("home_filter_revision", 0) >= HOME_FILTER_REVISION
                || !p.getBoolean("home_compat", false)
                || HomeSetup.paused(context)
                || !authorized(context)
                || !HomeSetup.serviceEnabled(context)) return;
        // Android 9 retains old accessibility XML flags across an APK upgrade.
        // Rebind only our service once so updated XML & runtime flags take effect while
        // preserving XiaoAi and every other enabled accessibility service.
        Set<String> current = services(Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
        for (String s : new ArrayList<>(current)) if (own(s)) current.remove(s);
        String expectedServices = join(current);
        int expectedEnabled = current.isEmpty() ? 0 : 1;
        try {
            rebindMinGeneration = HomeKeyService.connectionGeneration;
            p.edit().putBoolean("compat_seen_present", true)
                    .putString(PREF_REBIND_STATE, REBIND_STATE_REMOVED)
                    .putString(PREF_REBIND_EXPECTED_SERVICES, expectedServices)
                    .putInt(PREF_REBIND_EXPECTED_ENABLED, expectedEnabled)
                    .commit();
            writeServices(context, current);
        } catch (RuntimeException e) {
            Log.w(TAG, "Home key service refresh write failed", e);
        }
        if (HomeSetup.serviceEnabled(context)) {
            clearPersistedRebind(context);
            return;
        }
        // Record the exact snapshot left on disk so even a partial write (whether putInt returned
        // false or threw SecurityException) remains recognizable and recoverable.
        persistRebindActualSnapshot(context);
        rebindUntil = android.os.SystemClock.elapsedRealtime() + 250;
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> completeHomeServiceRebind(context, 0), 300);
    }
    private static void completeHomeServiceRebind(Context context, int attempt) {
        SharedPreferences p = prefs(context);
        rebindUntil = 0;
        if (!REBIND_STATE_REMOVED.equals(p.getString(PREF_REBIND_STATE, ""))) return;
        if (p.getBoolean("setup_compat_reset_pending", false)) {
            clearPersistedRebind(context);
            return;
        }
        if (verifyPendingRebindBeforeWrite(context) != RebindGate.OWN_READY) return;
        // Any connection that existed BEFORE we write the service back is stale and cannot finish this round.
        rebindMinGeneration = Math.max(rebindMinGeneration, HomeKeyService.connectionGeneration);
        recover(context);
        if (HomeSetup.serviceEnabled(context)) {
            p.edit().putString(PREF_REBIND_STATE, REBIND_STATE_WRITTEN)
                    .remove(PREF_REBIND_EXPECTED_SERVICES)
                    .remove(PREF_REBIND_EXPECTED_ENABLED)
                    .commit();
            HomeKeyService.syncRuntimeKeyFilter(context);
            TaskBridgeClient.sync(context);
            if (!markHomeServiceRebindConnected(context)) {
                Diagnostics.event("HOME_SERVICE_REBIND_AWAITING_CONNECT");
            }
            return;
        }
        if (attempt < 2 && authorized(context) && matchesPersistedRebindRemoved(context)) {
            rebindUntil = android.os.SystemClock.elapsedRealtime() + 250;
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> completeHomeServiceRebind(context, attempt + 1), 300);
        } else {
            // Keep persisted "removed" marker (with actual partial-write snapshot if any) so user's
            // home_compat/boot_home survive retries or restart.
            Diagnostics.event("HOME_SERVICE_REBIND_FAILED");
        }
    }
    static boolean isGuardModeReady(Context context) {
        String state = prefs(context).getString(PREF_REBIND_STATE, "");
        if (REBIND_STATE_REMOVED.equals(state)) return false;
        if (REBIND_STATE_WRITTEN.equals(state)) return HomeKeyService.isGuardModeApplied(context, rebindMinGeneration);
        return HomeKeyService.isGuardModeApplied(context);
    }
    static boolean markHomeServiceRebindConnected(Context context) {
        SharedPreferences p = prefs(context);
        String state = p.getString(PREF_REBIND_STATE, "");
        if (!REBIND_STATE_WRITTEN.equals(state) && !REBIND_STATE_REMOVED.equals(state)) return false;
        if (HomeSetup.paused(context) || !p.getBoolean("home_compat", false)
                || !HomeSetup.serviceEnabled(context)
                || !HomeKeyService.isGuardModeApplied(context, rebindMinGeneration)) return false;
        rebindMinGeneration = 0;
        p.edit().putInt("home_filter_revision", HOME_FILTER_REVISION)
                .remove(PREF_REBIND_STATE)
                .remove(PREF_REBIND_EXPECTED_SERVICES)
                .remove(PREF_REBIND_EXPECTED_ENABLED)
                .commit();
        TaskBridgeClient.sync(context);
        Diagnostics.event("HOME_SERVICE_REBOUND");
        return true;
    }
    static String rebindStatusLabel(Context context) {
        SharedPreferences p = prefs(context);
        String state = p.getString(PREF_REBIND_STATE, "");
        if (REBIND_STATE_REMOVED.equals(state)) return "升级重绑中（待写回启用列表）";
        if (REBIND_STATE_WRITTEN.equals(state) && !HomeKeyService.isGuardModeApplied(context, rebindMinGeneration)) {
            return "已写回、等待连接";
        }
        if (HomeKeyService.isGuardModeApplied(context)) return "已连接并应用模式";
        if (HomeKeyService.connected) return "已连接、等待应用模式";
        if (HomeSetup.serviceEnabled(context)) return "已写回、等待连接";
        return "未连接";
    }
    private static Set<String> services(String value) {
        Set<String> out = new LinkedHashSet<>();
        if (value != null) for (String entry : value.split(":")) if (ComponentName.unflattenFromString(entry) != null) out.add(entry);
        return out;
    }
    private static boolean competing(String value) {
        ComponentName name = ComponentName.unflattenFromString(value);
        return name != null && (name.getPackageName().equals("com.spocky.projengmenu") || name.getPackageName()
                .equals("com.maric.adbkeeper") || name.getPackageName().equals("com.terence.accessibilitykeeper"));
    }
    private static boolean own(String value) {
        ComponentName name = ComponentName.unflattenFromString(value);
        return name != null && name.equals(ComponentName.unflattenFromString(OWN_SERVICE));
    }
    private static String join(Set<String> values) {
        StringBuilder s = new StringBuilder();
        for (String value : values) {
            if (s.length() > 0) s.append(':');
            s.append(value);
        }
        return s.toString();
    }
    /** Last accessibility state written by Marco TV in this process, to tell our writes from the user's. */
    private static volatile String writtenServices;
    private static volatile int writtenEnabled = -1;
    private static volatile long rebindUntil;
    private static volatile long rebindMinGeneration;
    static void resetRebindStateForTest() {
        writtenServices = null;
        writtenEnabled = -1;
        rebindUntil = 0;
        rebindMinGeneration = 0;
    }
    static void clearPersistedRebind(Context context) {
        rebindUntil = 0;
        rebindMinGeneration = 0;
        if (context == null) return;
        SharedPreferences p = prefs(context);
        if (p.contains(PREF_REBIND_STATE) || p.contains(PREF_REBIND_EXPECTED_SERVICES)
                || p.contains(PREF_REBIND_EXPECTED_ENABLED)) {
            p.edit().remove(PREF_REBIND_STATE)
                    .remove(PREF_REBIND_EXPECTED_SERVICES)
                    .remove(PREF_REBIND_EXPECTED_ENABLED)
                    .commit();
        }
    }
    static void cancelOwnRebind(Context context) {
        clearPersistedRebind(context);
    }
    private static boolean matchesPersistedRebindRemoved(Context context) {
        SharedPreferences p = prefs(context);
        if (!REBIND_STATE_REMOVED.equals(p.getString(PREF_REBIND_STATE, ""))) return false;
        if (!p.contains(PREF_REBIND_EXPECTED_SERVICES) || !p.contains(PREF_REBIND_EXPECTED_ENABLED)) return false;
        String expectedServices = p.getString(PREF_REBIND_EXPECTED_SERVICES, "");
        int expectedEnabled = p.getInt(PREF_REBIND_EXPECTED_ENABLED, -1);
        String services = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        int enabled = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        String normalized = services == null ? "" : services;
        return expectedServices.equals(normalized) && expectedEnabled == enabled;
    }
    static boolean isOwnRebindInProgress(Context context) {
        SharedPreferences p = prefs(context);
        if (!REBIND_STATE_REMOVED.equals(p.getString(PREF_REBIND_STATE, ""))) return false;
        if (HomeSetup.paused(context) || !authorized(context)
                || !p.getBoolean("home_compat", false)) {
            clearPersistedRebind(context);
            return false;
        }
        if (HomeSetup.serviceEnabled(context)) return false;
        if (!matchesPersistedRebindRemoved(context)) {
            clearPersistedRebind(context);
            return false;
        }
        return true;
    }
    private static boolean writeServices(Context context, Set<String> wanted) {
        String current = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String normalizedCurrent = current == null ? "" : current;
        int currentEnabled = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        String next = join(wanted);
        int enabled = wanted.isEmpty() ? 0 : 1;
        boolean servicesOk = next.equals(normalizedCurrent);
        if (!servicesOk) {
            boolean putOk = Settings.Secure.putString(
                    context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            String after = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String normalizedAfter = after == null ? "" : after;
            servicesOk = putOk && next.equals(normalizedAfter);
            if (!normalizedAfter.equals(normalizedCurrent)) {
                writtenServices = normalizedAfter;
                writtenEnabled = currentEnabled;
            }
        }
        if (!servicesOk) return false;
        writtenServices = next;
        boolean enabledOk = currentEnabled == enabled;
        int afterEnabled = currentEnabled;
        if (!enabledOk) {
            try {
                boolean putOk = Settings.Secure.putInt(
                        context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, enabled);
                afterEnabled = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
                enabledOk = putOk && afterEnabled == enabled;
            } finally {
                afterEnabled = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
                writtenEnabled = afterEnabled;
            }
        } else {
            writtenEnabled = afterEnabled;
        }
        return enabledOk;
    }
    static boolean removeOwnHomeService(Context context) {
        try {
            Set<String> wanted = services(Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
            for (String service : new ArrayList<>(wanted)) if (own(service)) wanted.remove(service);
            writeServices(context, wanted);
            return !HomeSetup.serviceEnabled(context);
        } catch (RuntimeException e) {
            return false;
        }
    }
    static void recover(Context context) {
        SharedPreferences p = prefs(context);
        if (!authorized(context)) {
            p.edit().putString("last_error", "尚未获得一次性系统设置授权").apply();
            return;
        }
        expireAdbRestore(context);
        boolean unlocked = HomeSetup.unlocked(context);
        if (unlocked) respectExternalHomeServiceOff(context, false);
        boolean rebindRemoved = false;
        try {
            if (p.getBoolean("adb_restore", false) && Settings.Global.getInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 0) != 1) {
                boolean written = Settings.Global.putInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 1);
                if (!written) throw new IllegalStateException("ADB setting rejected");
                p.edit().putLong("last_adb_restore", System.currentTimeMillis()).apply();
                Diagnostics.event("ADB_SWITCH_RESTORED");
                Log.i(TAG, "Restored adb_enabled=1");
            }
            if (unlocked && !HomeSetup.paused(context) && p.getBoolean("home_compat", false)
                    && !p.getBoolean("setup_compat_reset_pending", false)
                    && android.os.SystemClock.elapsedRealtime() >= rebindUntil) {
                RebindGate gate = p.getBoolean("setup_accessibility_pending", false)
                        ? RebindGate.NONE : verifyPendingRebindBeforeWrite(context);
                if (gate != RebindGate.EXTERNAL_ABORT && gate != RebindGate.OWN_WAIT) {
                    rebindRemoved = (gate == RebindGate.OWN_READY);
                    Set<String> wanted = services(Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
                    for (String service : new ArrayList<>(wanted)) if (own(service)) wanted.remove(service);
                    wanted.add(OWN_SERVICE);
                    if (rebindRemoved) {
                        rebindMinGeneration = Math.max(rebindMinGeneration, HomeKeyService.connectionGeneration);
                    }
                    writeServices(context, wanted);
                    reconcileRebindStateAfterRestoreWrite(context, rebindRemoved, true);
                }
            }
            p.edit().remove("last_error").putLong("last_check", System.currentTimeMillis()).apply();
        } catch (RuntimeException e) {
            if (rebindRemoved) {
                reconcileRebindStateAfterRestoreWrite(context, true, false);
            }
            p.edit().putString("last_error", "恢复失败：" + e.getClass().getSimpleName()).apply();
            Diagnostics.event("SYSTEM_RECOVERY_FAILED");
            Log.w(TAG, "Settings recovery failed", e);
        }
    }
    /**
     * External changes cannot identify the actor. Pause rather than guess user versus OEM.
     * Only our own in-process rebind or an explicit in-progress consent may restore a service.
     */
    static boolean respectExternalHomeServiceOff(Context context, boolean observedChange) {
        SharedPreferences p = prefs(context);
        if (!HomeSetup.unlocked(context) || !p.getBoolean("home_compat", false) || HomeSetup.paused(context)
                || p.getBoolean("setup_accessibility_pending", false)
                || p.getBoolean("setup_compat_reset_pending", false)) return false;
        String services = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        int enabled = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        String normalized = services == null ? "" : services;
        boolean ours = (writtenServices != null && writtenServices.equals(normalized) && writtenEnabled == enabled)
                || matchesPersistedRebindRemoved(context);
        boolean present = false;
        for (String s : services(services)) if (own(s)) present = true;
        if (!HomeSetupPolicy.pauseForExternalChange(true, true, ours, present && enabled == 1)) return false;
        clearPersistedRebind(context);
        HomeSetup.pause(context, "Home 服务已被关闭，来源无法确定。自动恢复已暂停，请在桌面启用向导确认后继续。");
        p.edit().putString("last_notice", "Home 服务状态发生变化，已暂停恢复，等待你确认。").apply();
        Diagnostics.event("HOME_SERVICE_CHANGE_NEEDS_CONFIRMATION");
        Log.i(TAG, "Home service unavailable; recovery paused pending consent");
        return true;
    }
    /**
     * BOOT_COMPLETED only, before reconcile(). Some firmware clears third-party
     * accessibility services while booting. Restore only our own entry, only when the
     * user consented to compatibility, recovery is not paused, and the previous session
     * last saw the service present (a runtime removal pauses and clears that marker).
     */
    static boolean restoreCompatAfterBoot(Context context) {
        SharedPreferences p = prefs(context);
        if (!authorized(context) || !HomeSetup.unlocked(context) || HomeSetup.paused(context)
                || !p.getBoolean("home_compat", false) || p.getBoolean("setup_accessibility_pending", false)
                || p.getBoolean("setup_compat_reset_pending", false)
                || !p.getBoolean("compat_seen_present", false) || HomeSetup.serviceEnabled(context)) return false;
        RebindGate gate = verifyPendingRebindBeforeWrite(context);
        if (gate == RebindGate.EXTERNAL_ABORT || gate == RebindGate.OWN_WAIT) return false;
        if (HomeSetup.serviceEnabled(context)) return false;
        boolean rebindRemoved = (gate == RebindGate.OWN_READY);
        try {
            if (rebindRemoved) {
                rebindMinGeneration = Math.max(rebindMinGeneration, HomeKeyService.connectionGeneration);
            }
            Set<String> wanted = services(Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
            for (String service : new ArrayList<>(wanted)) if (own(service)) wanted.remove(service);
            wanted.add(OWN_SERVICE);
            writeServices(context, wanted);
            boolean restored = HomeSetup.serviceEnabled(context);
            if (restored) {
                p.edit().remove("setup_compat_reset_pending").remove("standby_wake_return")
                        .putString("last_notice", "电视重启时 Home 服务被系统关闭，已按之前的设置恢复，其他辅助服务未改动。")
                        .apply();
            }
            if (rebindRemoved) {
                reconcileRebindStateAfterRestoreWrite(context, true, false);
            }
            Diagnostics.event(restored ? "HOME_SERVICE_RESTORED_AFTER_BOOT" : "HOME_SERVICE_BOOT_RESTORE_REJECTED");
            return restored;
        } catch (RuntimeException e) {
            if (rebindRemoved) {
                reconcileRebindStateAfterRestoreWrite(context, true, false);
            }
            Log.w(TAG, "Boot restore of home service failed", e);
            return false;
        }
    }
    private static boolean isInteractive(Context context) {
        android.os.PowerManager power = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return power == null || power.isInteractive();
    }
    /**
     * Called when a system-bound service restarts our process while the TV is asleep
     * after firmware force-stopped Marco TV on standby (clearing accessibility via
     * AccessibilityManagerService.onHandleForceStop). Restores only our own entry.
     */
    static boolean restoreCompatAfterStandbyKill(Context context) {
        SharedPreferences p = prefs(context);
        if (!HomeSetupPolicy.restoreCompatAfterStandby(authorized(context), HomeSetup.unlocked(context),
                HomeSetup.paused(context), p.getBoolean("home_compat", false),
                p.getBoolean("setup_accessibility_pending", false),
                p.getBoolean("compat_seen_present", false),
                p.getBoolean("compat_service_active", false),
                p.getBoolean("setup_compat_reset_pending", false),
                HomeSetup.serviceEnabled(context), isInteractive(context))) return false;
        RebindGate gate = verifyPendingRebindBeforeWrite(context);
        if (gate == RebindGate.EXTERNAL_ABORT || gate == RebindGate.OWN_WAIT) return false;
        if (HomeSetup.serviceEnabled(context)) return false;
        boolean rebindRemoved = (gate == RebindGate.OWN_READY);
        try {
            if (rebindRemoved) {
                rebindMinGeneration = Math.max(rebindMinGeneration, HomeKeyService.connectionGeneration);
            }
            Set<String> wanted = services(Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
            for (String service : new ArrayList<>(wanted)) if (own(service)) wanted.remove(service);
            wanted.add(OWN_SERVICE);
            writeServices(context, wanted);
            boolean restored = HomeSetup.serviceEnabled(context);
            if (restored) {
                p.edit().putBoolean("compat_seen_present", true)
                        .putBoolean("standby_wake_return", true)
                        .putString("last_notice", "电视待机时 Home 服务被系统关闭，已按之前的设置恢复，其他辅助服务未改动。")
                        .apply();
                if (rebindRemoved) {
                    reconcileRebindStateAfterRestoreWrite(context, true, false);
                }
                Diagnostics.event("HOME_SERVICE_RESTORED_AFTER_STANDBY");
                Log.i(TAG, "Restored home service after standby force-stop");
                startRecovery(context);
            } else {
                if (rebindRemoved) {
                    reconcileRebindStateAfterRestoreWrite(context, true, false);
                }
                Diagnostics.event("HOME_SERVICE_STANDBY_RESTORE_REJECTED");
                HomeSetup.markCompatResetIfInterrupted(context);
            }
            return restored;
        } catch (RuntimeException e) {
            if (rebindRemoved) {
                reconcileRebindStateAfterRestoreWrite(context, true, false);
            }
            Log.w(TAG, "Standby restore of home service failed", e);
            return false;
        }
    }
    static boolean restoreCompatOnUserConfirm(Context context) {
        HomeSetup.clearCompatReset(context);
        if (!authorized(context) || !HomeSetup.unlocked(context)) return false;
        HomeSetupSnapshot.capture(context);
        refreshTrackedHomeForSetup(context);
        SharedPreferences p = prefs(context);
        p.edit().putBoolean("home_compat", true).putBoolean("boot_home", true)
                .putBoolean("setup_paused", false).remove("setup_pause_reason")
                .remove("setup_accessibility_pending").remove("standby_wake_return").commit();
        try {
            Set<String> wanted = services(Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
            for (String service : new ArrayList<>(wanted)) if (own(service)) wanted.remove(service);
            wanted.add(OWN_SERVICE);
            writeServices(context, wanted);
            boolean restored = HomeSetup.serviceEnabled(context);
            p.edit().putBoolean("compat_seen_present", restored).apply();
            if (restored) {
                p.edit().putString("last_notice", "已按你的确认重新启用 Home 兼容服务，其他辅助服务未改动。").apply();
                Diagnostics.event("HOME_SERVICE_RESTORED_BY_USER");
                HomeKeyService.syncRuntimeKeyFilter(context);
                startRecovery(context);
            }
            return restored;
        } catch (RuntimeException e) {
            Log.w(TAG, "User-confirmed restore of home service failed", e);
            return false;
        }
    }
    static boolean hasStandbyWakeReturn(Context context) {
        return prefs(context).getBoolean("standby_wake_return", false);
    }
    static boolean consumeStandbyWakeReturn(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.getBoolean("standby_wake_return", false)) return false;
        p.edit().remove("standby_wake_return").apply();
        return true;
    }
    static void clearStandbyWakeReturn(Context context) {
        SharedPreferences p = prefs(context);
        if (p.getBoolean("standby_wake_return", false)) p.edit().remove("standby_wake_return").apply();
    }
    static void enableHome(Context context, boolean enabled) {
        if(BuildEdition.RESTRICTED)return;
        context.getPackageManager().setComponentEnabledSetting(new ComponentName(context, "com.pilipala.tvhome.HomeActivity"),
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }
    private static void prepareHome(Context context) {
        rememberHome(context);
        SharedPreferences p = prefs(context);
        if (!p.contains("saved_competing_services")) {
            Set<String> saved = new LinkedHashSet<>();
            for (String service : services(Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)))
                    if (competing(service)) saved.add(service);
            p.edit().putString("saved_competing_services", join(saved)).apply();
        }
        p.edit().putBoolean("managed_home", true).putBoolean("boot_home", true).apply();
        enableHome(context, true);
        recover(context);
        startRecovery(context);
    }
    private static void permissionHelp(Activity activity, DialogHost host) {
        host.show(new TvDialog.Builder(activity).setTitle("此功能需要系统允许")
                .setMessage("ADB 自动恢复属于高级功能，需要一次系统授权。\n\n如果只想让 Home 回到 Marco TV，先使用「桌面启用」，标准桌面选择或手动辅助功能无需这项权限。\n\n需要高级功能时，可在更多设置方式中尝试本机普通网络调试（5555 端口）；仅提供无线配对码时，请在电脑使用官方 Android Platform Tools 按完整说明操作（安装助手 1.0.1 仅用于普通网络 ADB 安装和打开应用）。")
                .setPositiveButton("打开桌面启用", (d,w)->((MainActivity)activity).defaultHome.show())
                .setNeutralButton("手机查看向导", (d,w)->((MainActivity)activity).defaultHome.assistant())
                .setNegativeButton("稍后", null).create());
    }
    static void disableHomeCompat(Activity activity) {
        HomeSetup.pause(activity, "你已关闭 Home 兼容；重新开启前不会恢复。");
        Set<String> wanted = services(Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
        for (String service : new ArrayList<>(wanted)) if (own(service)) wanted.remove(service);
        if (authorized(activity)) {
            try {
                writeServices(activity, wanted);
            } catch (RuntimeException e) {
                Log.w(TAG, "Disable home compat write failed", e);
            }
        }
        startRecovery(activity);
    }
    static void toggleAdbRestore(Activity activity, DialogHost host, Runnable onChanged) {
        SharedPreferences p = prefs(activity);
        boolean next = !p.getBoolean("adb_restore", false);
        if (next && !authorized(activity)) {
            permissionHelp(activity, host);
            return;
        }
        if (next) host.show(new TvDialog.Builder(activity).setTitle("开启 ADB 自动恢复")
                .setMessage("开机后恢复调试开关，运行期间被关闭时也会恢复。\n\n调试开关打开期间，同一网络中的设备可以请求连接电视（仍需在电视上确认授权）。建议只在需要时开启：默认 24 小时后自动停止恢复，调试开关保持当时状态。\n\n网络 ADB 还需系统保留端口配置；本功能不会关闭设备授权验证。")
                .setPositiveButton("开启 24 小时", (dialog, button) -> {
                    p.edit().putBoolean("adb_restore", true).putLong("adb_restore_until", System.currentTimeMillis() + ADB_RESTORE_WINDOW)
                            .remove("last_notice").apply();
                    recover(activity);
                    startRecovery(activity);
                    if (onChanged != null) onChanged.run();
                })
                .setNeutralButton("长期开启", (dialog, button) -> {
                    p.edit().putBoolean("adb_restore", true).putLong("adb_restore_until", 0).remove("last_notice")
                            .apply();
                    recover(activity);
                    startRecovery(activity);
                    if (onChanged != null) onChanged.run();
                })
                .setNegativeButton("返回", null).create());
        else {
            p.edit().putBoolean("adb_restore", false).remove("adb_restore_until").apply();
            startRecovery(activity);
            if (onChanged != null) onChanged.run();
        }
    }
    static void confirmRecoverNow(Activity activity, DialogHost host) {
        host.show(new TvDialog.Builder(activity).setPageId("recovery_confirm").setTitle("执行已启用的恢复？")
                .setMessage("仅执行当前已启用的 ADB / Home 恢复设置，可能重新打开调试开关或恢复自身 Home 服务；不新增授权。唤醒恢复另外需要 Home 兼容和已连接的任务辅助服务。")
                .setPositiveButton("执行恢复", (dialog, button) -> {
                    recover(activity);
                    startRecovery(activity);
                    status(activity, host);
                })
                .setNegativeButton("取消", null).create());
    }
    static void showDebugHelp(Activity activity, DialogHost host) {
        host.show(new TvDialog.Builder(activity).setTitle("调试与授权")
                .setMessage("可在「桌面启用」中优先使用系统默认桌面选择或手动辅助功能（无需调试）。需要系统授权时，可在电视开启普通网络调试（5555 端口）并在本机尝试；若电视仅提供无线配对码，请改用手动辅助功能，或在电脑使用官方 Android Platform Tools（adb pair / adb connect）按完整说明操作。\n\n手机／Windows 安装助手 1.0.1 仅负责通过普通网络 ADB 安装和打开应用，不支持配对码。卸载应用或恢复出厂设置后可能需要重新授权；持续调试恢复需另行开启，端口能否恢复取决于电视固件。")
                .setPositiveButton("查看授权方法", (dialog, button) -> permissionHelp(activity, host))
                .setNegativeButton("返回", null).create());
    }
    static void show(Activity activity, DialogHost host) {
        SharedPreferences p = prefs(activity);
        rememberHome(activity);
        boolean ownHome = homeIsOurs(activity);
        String[] items = {
                "ADB 自动恢复：" + adbRestoreLabel(activity),
                "状态中心", "桌面启用与检查",
                "开机打开 Marco TV：" + (p.getBoolean("boot_home", false) ? "已开启" : "未开启"),
                "Home 键兼容模式：" + (p.getBoolean("home_compat", false) ? "已开启" : "未开启"),
                "恢复设置前的桌面", "唤醒后恢复桌面：" + (activity.getSharedPreferences("home",0).getBoolean("recent_wake",true)?"已开启":"未开启"), "立即执行已启用的恢复", "调试与授权说明",
                "Home 长按与原厂兼容"};
        host.show(new TvDialog.Builder(activity).setPageId("startup").setTitle("系统与启动")
                .setMessage("当前默认桌面：" + (BuildEdition.RESTRICTED ? "受限兼容版不含系统默认桌面声明" : ownHome ? "Marco TV" : "厂商或其他桌面")
                + "\n唤醒恢复还需开启 Home 兼容并连接任务辅助服务；只打开偏好不代表已经生效。"
                + (ownHome ? "" : "\n需要设置或修复时，选择「桌面启用与检查」，按本机检测结果继续。"))
                .setOrder("startup","adb","status","home","boot","home_key","restore","wake","recover","help","home_gestures")
                .setItems(items, (d, which) ->
                activity.getWindow().getDecorView().post(() -> {
            switch (which) {
                case 0:
                    toggleAdbRestore(activity, host, () -> show(activity, host));
                    break;
                case 1:
                    status(activity, host);
                    break;
                case 2:
                    ((MainActivity)activity).defaultHome.show();
                    break;
                case 3:
                    p.edit().putBoolean("boot_home", !p.getBoolean("boot_home", false)).apply();
                    show(activity,host);
                    break;
                case 4:
                    if (p.getBoolean("home_compat", false)) {
                        disableHomeCompat(activity);
                        show(activity,host);
                    } else ((MainActivity)activity).defaultHome.compatibility();
                    break;
                case 5:
                    ((MainActivity)activity).defaultHome.restore();
                    break;
                case 6:
                    activity.getSharedPreferences("home",0).edit().putBoolean("recent_wake",!activity.getSharedPreferences("home",0)
                            .getBoolean("recent_wake",true)).apply();
                    TaskBridgeClient.sync(activity);
                    show(activity,host);
                    break;
                case 7:
                    confirmRecoverNow(activity, host);
                    break;
                case 9:
                    homeGestures(activity,host);
                    break;
                case 8:
                    showDebugHelp(activity, host);
                    break;
            }
        })).setNegativeButton("返回", null).create());
    }
    static String[] homeGesturesItems(Activity activity) {
        SharedPreferences p = prefs(activity);
        HomeSetupPolicy.GuardMode mode = guardMode(activity);
        HomeSetupPolicy.DesktopState state = desktopState(activity);
        String action = p.getString("home_long_action", "system");
        boolean nativeActive = !BuildEdition.RESTRICTED && homeIsOurs(activity);
        boolean bridgeConnected = TaskBridgeClient.connected();
        boolean bridgeOld = bridgeConnected && "bridge_update_required".equals(TaskBridgeClient.volumeAssistReason());
        boolean bridgeWriteFailed = bridgeConnected && TaskBridgeClient.volumeAudioWriteFailed();
        boolean bridgeVolumeVerified = TaskBridgeClient.volumeAssistActive()
                || (TaskBridgeClient.volumeAudioReady() && TaskBridgeClient.volumeInputDeviceCount() > 0);
        boolean bridgeConnectedUnverified = bridgeConnected && !bridgeOld && !bridgeWriteFailed && !bridgeVolumeVerified;
        String fastHomeSuffix = mode == HomeSetupPolicy.GuardMode.KEY_FILTER && !nativeActive && !HomeSetup.paused(activity)
                ? (!HomeKeyService.connected || !HomeSetup.serviceEnabled(activity) ? "（等待服务连接）"
                        : (!HomeKeyService.runtimeKeyFilterRequested && !HomeKeyService.runtimeVolumeYieldActive ? "（按键过滤未生效）"
                                : (HomeKeyService.runtimeVolumeYieldFailed ? "（无桥让渡受限）"
                                        : (TaskBridgeClient.volumeAssistActive() ? "（任务桥辅助已验证） ✓"
                                                : (HomeKeyService.runtimeVolumeYieldActive ? "（无桥让渡中） ✓"
                                                        : (bridgeOld ? "（旧版任务桥 · 无桥让渡） ✓"
                                                                : (bridgeWriteFailed ? "（任务桥调音受限 · 无桥让渡） ✓"
                                                                        : (bridgeConnected ? "（任务桥已连接 · 音量辅助未就绪，无桥让渡） ✓" : "（无桥让渡） ✓"))))))))
                : (bridgeVolumeVerified
                        ? "（任务桥音量辅助已验证 · 推荐）"
                        : (bridgeConnectedUnverified
                                ? "（任务桥已连接，切换快速 Home 后验证音量辅助）"
                                : (bridgeOld
                                        ? "（旧版任务桥：需重启桥启用音量辅助）"
                                        : (bridgeWriteFailed
                                                ? "（任务桥已连接 · 上次调音写入受限，切换可重试）"
                                                : "（未连桥：无桥需先点一下再长按）"))));
        String windowLabel = bridgeVolumeVerified
                ? "窗口回拉（兼容模式，部分固件可能短暂闪过原厂桌面）"
                : "窗口回拉（兼容模式，无桥推荐）";
        String[] base = new String[]{
                (BuildEdition.RESTRICTED ? "系统默认桌面（受限版不适用）" : "系统默认桌面" + (nativeActive ? " ✓" : "")),
                windowLabel + (!nativeActive && !HomeSetup.paused(activity) && mode == HomeSetupPolicy.GuardMode.WINDOW_PULLBACK && !state.keyFilterStillActiveUnexpectedly ? " ✓" : ""),
                "快速 Home" + fastHomeSuffix,
                "原厂按键" + (!nativeActive && !HomeSetup.paused(activity) && mode == HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY && !state.keyFilterStillActiveUnexpectedly ? " ✓" : ""),
                "快速 Home 长按：系统最近任务" + (action.equals("system") ? " ✓" : ""),
                "快速 Home 长按：Marco 最近任务" + (action.equals("marco") ? " ✓" : ""),
                "快速 Home 长按：不执行操作" + (action.equals("none") ? " ✓" : ""),
                "双击响应间隔"
        };
        if (!state.keyFilterStillActiveUnexpectedly) return base;
        String[] withRelease = new String[base.length + 1];
        System.arraycopy(base, 0, withRelease, 0, base.length);
        withRelease[base.length] = "仅关闭 Marco 辅助服务（释放按键过滤）";
        return withRelease;
    }
    static boolean releaseStuckKeyFilterService(Activity activity) {
        HomeSetupPolicy.DesktopState current = desktopState(activity);
        if (!current.keyFilterStillActiveUnexpectedly) {
            android.widget.Toast.makeText(activity, "按键过滤异常已恢复，已保留当前桌面守护与开机回桌设置", android.widget.Toast.LENGTH_LONG).show();
            TaskBridgeClient.notifyStateChanged();
            return false;
        }
        disableHomeCompat(activity);
        if (HomeSetup.serviceEnabled(activity)) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                android.widget.Toast.makeText(activity, "请在辅助功能中仅关闭 Marco TV（保留其他辅助服务）", android.widget.Toast.LENGTH_LONG).show();
            } catch (RuntimeException e) {
                android.widget.Toast.makeText(activity, "请前往系统辅助功能仅关闭 Marco TV", android.widget.Toast.LENGTH_LONG).show();
            }
        } else {
            android.widget.Toast.makeText(activity, "已仅关闭 Marco TV 辅助服务并释放按键过滤，其他辅助服务未改动", android.widget.Toast.LENGTH_LONG).show();
        }
        return true;
    }
    static void homeGestures(Activity activity,DialogHost host){
        SharedPreferences p=prefs(activity);
        HomeSetupPolicy.DesktopState state=desktopState(activity);
        String runtime=homeGesturesRuntimeText(activity);
        TvDialog dialog=new TvDialog.Builder(activity).setPageId("home_gestures").setTitle("桌面与遥控器兼容模式")
                .setMessage(homeGesturesMessage(activity,state,runtime))
                .setItems(homeGesturesItems(activity),(d,w)->{
            if(w==8){releaseStuckKeyFilterService(activity);homeGestures(activity,host);return;}
            if(w==7){((MainActivity)activity).navigator.personalization("recent_double_ms");return;}
            if(w==0){
                if(BuildEdition.RESTRICTED){
                    android.widget.Toast.makeText(activity,"受限兼容版不含系统默认桌面声明，请使用窗口回拉或快速 Home",android.widget.Toast.LENGTH_LONG).show();
                    homeGestures(activity,host);
                    return;
                }
                setDesktopChoice(activity,HomeSetupPolicy.DesktopChoice.NATIVE_HOME);
                if(activity instanceof MainActivity)((MainActivity)activity).defaultHome.request();
                return;
            }
            if(w==1)setGuardMode(activity,HomeSetupPolicy.GuardMode.WINDOW_PULLBACK);
            else if(w==2){setGuardMode(activity,HomeSetupPolicy.GuardMode.KEY_FILTER);TaskBridgeClient.retryVolumeAssist(activity);}
            else if(w==3)setGuardMode(activity,HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY);
            else p.edit().putString("home_long_action",new String[]{"system","marco","none"}[w-4]).apply();
            homeGestures(activity,host);
        }).setNegativeButton("返回",null).create();
        final Runnable refresh=()->{
            dialog.updateMessage(homeGesturesMessage(activity,desktopState(activity),homeGesturesRuntimeText(activity)));
            dialog.updateItems(homeGesturesItems(activity));
        };
        final Runnable refreshListener=()->{
            if(dialog.isShowing())refresh.run();
        };
        dialog.onReturn(refresh);
        TaskBridgeClient.addStateListener(refreshListener);
        dialog.onLeave(()->TaskBridgeClient.removeStateListener(refreshListener));
        host.show(dialog);
    }
    private static String homeGesturesRuntimeText(Activity activity){
        HomeSetupPolicy.DesktopState state=desktopState(activity);
        if(state.keyFilterStillActiveUnexpectedly)return "系统拒绝撤销按键过滤（flags 回读仍开启）";
        boolean nativeActive=!BuildEdition.RESTRICTED&&homeIsOurs(activity);
        if(nativeActive){
            return HomeSetup.paused(activity)?"原生默认桌面仍生效 · 自动恢复守护已暂停":"原生默认桌面已生效 · 已停止额外 Home 拦截";
        }
        if(!isGuardModeReady(activity)){
            if(HomeKeyService.connected&&guardMode(activity)==HomeSetupPolicy.GuardMode.KEY_FILTER
                    &&!HomeKeyService.runtimeKeyFilterRequested&&!HomeKeyService.runtimeVolumeYieldActive){
                return "按键过滤未生效（系统拒绝请求）";
            }
            return rebindStatusLabel(activity);
        }
        if(HomeKeyService.runtimeVolumeYieldFailed)return "系统拒绝撤销按键过滤 · 无桥让渡不可用";
        if(HomeKeyService.runtimeVolumeYieldActive)return "快速 Home 无桥模式 · 音量临时让渡 5 秒中";
        if(HomeKeyService.runtimeKeyFilterRequested){
            if(TaskBridgeClient.volumeAssistActive())return "已连接并应用模式 · 快速 Home＋任务桥音量辅助";
            if("bridge_update_required".equals(TaskBridgeClient.volumeAssistReason()))return "已连接旧版任务桥 · 需重启任务桥启用音量辅助（最近任务正常）";
            if(TaskBridgeClient.volumeAudioWriteFailed())return "已连接并应用模式 · 任务桥调音写入受限，当前为无桥模式";
            if(TaskBridgeClient.connected())return "已连接并应用模式 · 任务桥已连接（音量辅助未就绪），当前为无桥模式";
            return "已连接并应用模式 · 快速 Home（无桥模式）";
        }
        if(TaskBridgeClient.connected()){
            if("bridge_update_required".equals(TaskBridgeClient.volumeAssistReason()))return "已连接并应用模式 · 未请求按键过滤（已连接旧版任务桥）";
            if(TaskBridgeClient.volumeAudioWriteFailed())return "已连接并应用模式 · 未请求按键过滤（任务桥调音写入曾被拒）";
            if(TaskBridgeClient.volumeAudioReady()&&TaskBridgeClient.volumeInputDeviceCount()>0)return "已连接并应用模式 · 未请求按键过滤（任务桥音量辅助已验证）";
            return "已连接并应用模式 · 未请求按键过滤（任务桥已连接，切换快速 Home 后验证音量辅助）";
        }
        return "已连接并应用模式 · 未请求按键过滤";
    }
    static boolean isFastHomeActive(Context context) {
        boolean nativeActive = !BuildEdition.RESTRICTED && homeIsOurs(context);
        return !nativeActive
                && !HomeSetup.paused(context)
                && prefs(context).getBoolean("home_compat", false)
                && guardMode(context) == HomeSetupPolicy.GuardMode.KEY_FILTER
                && HomeSetup.serviceEnabled(context)
                && HomeKeyService.connected
                && (HomeKeyService.runtimeKeyFilterRequested || HomeKeyService.runtimeVolumeYieldActive);
    }
    static String bridgeStatusLabel(Context context) {
        if (!TaskBridgeClient.connected()) return "未连接";
        if ("bridge_update_required".equals(TaskBridgeClient.volumeAssistReason())) {
            return "已连接旧版任务桥（最近任务正常，需重启更新以启用音量辅助）";
        }
        if (TaskBridgeClient.volumeAudioWriteFailed()) {
            return "已连接 · 上次调音写入被系统拒绝（切换快速 Home 可重试）";
        }
        if (TaskBridgeClient.volumeAssistActive()
                || (TaskBridgeClient.volumeAudioReady() && TaskBridgeClient.volumeInputDeviceCount() > 0)) {
            return "已连接 · 音量辅助已验证";
        }
        if (isFastHomeActive(context)) {
            return "已连接 · 未检测到可用音量输入源（当前按无桥让渡运行）";
        }
        return "任务桥已连接，切换快速 Home 后验证音量辅助";
    }
    private static String homeGesturesMessage(Activity activity,HomeSetupPolicy.DesktopState state,String runtime){
        return "用户选择："+state.userChoiceLabel
                +"\n当前实际生效："+state.effectiveModeLabel
                +"\n降级原因："+state.fallbackReason
                +"\n服务状态："+runtime
                +"\n任务桥状态："+bridgeStatusLabel(activity)
                +"\n\n「窗口回拉（兼容模式）」无需任务桥，音量和方向键交由系统处理（部分固件回桌可能短暂闪过原厂桌面）；「快速 Home」直接拦截实体 Home（在支持的固件上可减少切回原厂桌面；是否完全无闪屏以具体固件与实机画面录像为准），配合任务桥可尝试首次长按音量连调（无任务桥时请先点一下音量、松手后再长按，让渡 5 秒后自动恢复）。";
    }
    private static void status(Activity activity, DialogHost host) {
        if(activity instanceof MainActivity)((MainActivity)activity).health.status();
    }
}
