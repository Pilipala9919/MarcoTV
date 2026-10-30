// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.provider.Settings;

/** Setup state is local, not backed up, and never equates a request with verified success. */
final class HomeSetup {
    static final String OWN = "com.pilipala.tvhome/.HomeActivity";
    static final String SERVICE = "com.pilipala.tvhome/.HomeKeyService";
    private HomeSetup() { }
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
    static HomeSetupPolicy.Route route(Context c) {
        return HomeSetupPolicy.route(paused(c), SystemIntegration.homeIsOurs(c),
                roleAvailable(c) || canOpen(c, Settings.ACTION_HOME_SETTINGS),
                prefs(c).getBoolean("home_compat", false), serviceEnabled(c),
                canOpen(c, Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }
    /** Explicit user action only. Existing ADB settings are deliberately untouched. */
    static void begin(Context c) {
        SystemIntegration.rememberHome(c);
        SystemIntegration.enableHome(c, true);
        prefs(c).edit().putBoolean("setup_requested", true).putBoolean("setup_paused", false)
                .remove("setup_pause_reason").remove("setup_native_seen").remove("setup_home_seen")
                .remove("setup_home_deadline").remove("setup_accessibility_pending")
                .remove("setup_boot_observed").remove("setup_boot_confirmed")
                .putInt("setup_boot_armed", bootCount(c)).commit();
    }
    static void acceptNative(Context c) {
        if (!prefs(c).getBoolean("setup_requested", false) || paused(c) || !SystemIntegration.homeIsOurs(c)) return;
        prefs(c).edit().putBoolean("setup_native_seen", true).putBoolean("boot_home", true).apply();
    }
    static void requestCompatibility(Context c) {
        begin(c);
        prefs(c).edit().putBoolean("home_compat", true).putBoolean("boot_home", true)
                .putBoolean("setup_accessibility_pending", true).commit();
    }
    static void finishCompatibilityConsent(Context c) {
        if (!prefs(c).getBoolean("setup_accessibility_pending", false)) return;
        boolean enabled = serviceEnabled(c);
        prefs(c).edit().remove("setup_accessibility_pending")
                .putBoolean("home_compat", enabled)
                .putBoolean("boot_home", enabled || SystemIntegration.homeIsOurs(c)).commit();
    }
    static void pause(Context c, String reason) {
        prefs(c).edit().putBoolean("setup_paused", true).putString("setup_pause_reason", reason)
                .putBoolean("home_compat", false).putBoolean("boot_home", false)
                .putBoolean("managed_home", false).remove("setup_home_deadline")
                .remove("setup_accessibility_pending").remove("setup_home_seen")
                .remove("setup_boot_confirmed").commit();
        // Never infer that an external change was necessarily the user's action.
        TaskBridgeClient.sync(c);
    }
    static void reconcile(Context c) {
        SharedPreferences p = prefs(c);
        if (!unlocked(c) || paused(c)) return;
        if (p.getBoolean("home_compat", false) && !p.getBoolean("setup_accessibility_pending", false) && !serviceEnabled(c)) {
            pause(c, "Home 服务不可用，需确认后继续恢复。");
            return;
        }
        if (!p.getBoolean("setup_requested", false)) return;
        if (p.getBoolean("setup_native_seen", false) && !SystemIntegration.homeIsOurs(c)) {
            pause(c, "默认桌面已变化；请确认是否继续使用 Marco TV。不会自动覆盖新的选择。");
        } else acceptNative(c);
    }
    static void armHomeTest(Context c) {
        prefs(c).edit().putInt("setup_home_boot", bootCount(c))
                .putLong("setup_home_deadline", SystemClock.elapsedRealtime() + 90000)
                .remove("setup_home_seen").commit();
    }
    static void observeHomeIntent(Context c, Intent i) {
        if (i == null || paused(c)) return;
        SharedPreferences p = prefs(c);
        boolean home = Intent.ACTION_MAIN.equals(i.getAction()) && i.hasCategory(Intent.CATEGORY_HOME)
                || i.getBooleanExtra("home_press_handled", false);
        int boot = bootCount(c); long now = SystemClock.elapsedRealtime();
        if (HomeSetupPolicy.homeEvidence(p.contains("setup_home_deadline"), p.getInt("setup_home_boot", -1),
                boot, p.getLong("setup_home_deadline", 0), now, home))
            p.edit().putLong("setup_home_seen", System.currentTimeMillis()).remove("setup_home_deadline").apply();
        if (p.getBoolean("setup_requested", false) && HomeSetupPolicy.bootEvidence(
                p.getInt("setup_boot_armed", -1), boot, now, home || i.getBooleanExtra("setup_boot_return", false)))
            p.edit().putInt("setup_boot_observed", boot).apply();
    }
    static void confirmBoot(Context c) {
        SharedPreferences p = prefs(c);
        int boot = bootCount(c);
        if (!paused(c) && boot >= 0 && p.getInt("setup_boot_observed", -1) == boot
                && boot > p.getInt("setup_boot_armed", Integer.MAX_VALUE))
            p.edit().putInt("setup_boot_confirmed", boot).apply();
    }
    static boolean shouldOpenAfterBoot(Context c, boolean unlocked) {
        return HomeSetupPolicy.bootFallback(prefs(c).getBoolean("boot_home", false), paused(c),
                SystemIntegration.homeIsOurs(c), prefs(c).getBoolean("home_compat", false), serviceEnabled(c), unlocked);
    }
    static String summary(Context c) {
        SharedPreferences p = prefs(c);
        boolean nativeHome = SystemIntegration.homeIsOurs(c);
        String mode = nativeHome ? "系统默认桌面已选择 Marco TV" :
                p.getBoolean("home_compat", false) && serviceEnabled(c) ? "Home 兼容模式（不是系统默认桌面）" : "尚未完成";
        String boot = "尚未配置";
        if (nativeHome || p.getBoolean("boot_home", false)) boot = "已配置，等待实际重启验证";
        if (p.contains("setup_boot_observed")) boot = "记录到重启后返回，等待你确认";
        if (p.contains("setup_boot_confirmed")) boot = "已确认一次重启返回；系统升级后仍需复核";
        if (paused(c)) boot = "已暂停自动恢复";
        return "桌面方式：" + mode + "\nHome 测试：" + (p.contains("setup_home_seen") ? "已记录 Home 返回" : "尚未验证")
                + "\n开机恢复：" + boot + "\n系统设置授权：" + (SystemIntegration.authorized(c) ? "已获得" : "未获得；标准设置无需此权限")
                + (paused(c) ? "\n已暂停恢复：" + p.getString("setup_pause_reason", "请重新确认") : "");
    }
}
