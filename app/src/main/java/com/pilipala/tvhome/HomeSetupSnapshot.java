// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.*;
import android.content.pm.*;
import java.util.List;

/** A local recovery point. Never restores another app's permissions or service list. */
final class HomeSetupSnapshot {
    static final String PREFIX = "auto_home_before_";
    private static final String[] FLAGS = {"home_compat", "boot_home", "managed_home",
            "setup_requested", "setup_paused", "setup_native_seen"};

    static void capture(Context c) {
        SharedPreferences p = HomeSetup.prefs(c);
        if (p.getBoolean(PREFIX + "saved", false)) return;
        boolean nativeHome = SystemIntegration.homeIsOurs(c);
        String original = nativeHome ? "" : SystemIntegration.currentHome(c);
        if (!eligible(c, original)) original = "";
        SharedPreferences.Editor e = p.edit().putString(PREFIX + "component", original)
                .putBoolean(PREFIX + "service", HomeSetup.serviceEnabled(c))
                .putBoolean(PREFIX + "native", nativeHome)
                .putString(PREFIX + "reason", p.getString("setup_pause_reason", ""));
        if (!BuildEdition.RESTRICTED) e.putInt(PREFIX + "alias", c.getPackageManager().getComponentEnabledSetting(
                new ComponentName(c, "com.pilipala.tvhome.HomeActivity")));
        for (String flag : FLAGS) e.putBoolean(PREFIX + flag, p.getBoolean(flag, false));
        if (!e.putBoolean(PREFIX + "saved", true).commit())
            throw new IllegalStateException("Cannot save recovery point");
    }

    static String target(Context c) {
        String value = HomeSetup.prefs(c).getString(PREFIX + "component", "");
        return eligible(c, value) ? value : "";
    }

    static boolean eligible(Context c, String value) {
        if (!HomeShellSetup.validComponent(value)) return false;
        ComponentName name = ComponentName.unflattenFromString(value);
        if (name == null || name.getPackageName().equals(c.getPackageName()) || name.getPackageName().equals("android")) return false;
        try {
            List<ResolveInfo> homes = c.getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
            for (ResolveInfo r : homes) {
                ActivityInfo a = r.activityInfo;
                if (a != null && a.enabled && a.exported && a.applicationInfo.enabled
                        && name.equals(new ComponentName(a.packageName, a.name))) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    static boolean matches(Context c, String value) {
        ComponentName wanted = ComponentName.unflattenFromString(value);
        ComponentName current = ComponentName.unflattenFromString(SystemIntegration.currentHome(c));
        return wanted != null && wanted.equals(current);
    }

    static boolean restoreLocal(Context c) {
        SharedPreferences p = HomeSetup.prefs(c);
        if (!p.getBoolean(PREFIX + "saved", false)) return false;
        // Do not re-enable a service that the user/firmware removed during this session.
        boolean before = p.getBoolean(PREFIX + "service", false);
        if (!before && HomeSetup.serviceEnabled(c)) {
            if (!SystemIntegration.authorized(c)) return false;
            if (!SystemIntegration.removeOwnHomeService(c)) return false;
        }
        SharedPreferences.Editor e = p.edit();
        for (String flag : FLAGS) e.putBoolean(flag, p.getBoolean(PREFIX + flag, false));
        e.remove("setup_accessibility_pending").remove("setup_home_seen").remove("setup_home_deadline")
                .remove("setup_boot_observed").remove("setup_boot_confirmed")
                .putString("setup_pause_reason", p.getString(PREFIX + "reason", ""));
        if (!e.commit()) return false;
        if (!BuildEdition.RESTRICTED && p.contains(PREFIX + "alias")) {
            try {
                c.getPackageManager().setComponentEnabledSetting(new ComponentName(c, "com.pilipala.tvhome.HomeActivity"),
                        p.getInt(PREFIX + "alias", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT), PackageManager.DONT_KILL_APP);
            } catch (RuntimeException e1) { return false; }
        }
        HomeSetup.reconcile(c);
        SystemIntegration.startRecovery(c);
        return true;
    }

    static void clear(Context c) {
        SharedPreferences p = HomeSetup.prefs(c);
        SharedPreferences.Editor e = p.edit();
        for (String key : p.getAll().keySet()) if (key.startsWith(PREFIX)) e.remove(key);
        e.commit();
    }
}
