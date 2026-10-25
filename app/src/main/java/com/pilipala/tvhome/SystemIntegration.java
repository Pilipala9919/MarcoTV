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
    interface DialogHost { void show(TvDialog dialog); }
    private static final String TAG = "MarcoTVSystem";
    private static final String OWN_SERVICE = "com.pilipala.tvhome/.HomeKeyService";

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
    static boolean needsRecovery(Context context) {
        SharedPreferences p = prefs(context);
        return p.getBoolean("adb_restore", false) || p.getBoolean("home_compat", false) || p.getBoolean("managed_home", false);
    }
    static void startRecovery(Context context) {
        TaskBridgeClient.sync(context);
        Intent intent = new Intent(context, RecoveryService.class);
        if (!needsRecovery(context)) { context.stopService(intent); return; }
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent); else context.startService(intent);
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
        return result == null || result.activityInfo == null ? "" : new ComponentName(result.activityInfo.packageName, result.activityInfo.name).flattenToString();
    }
    static boolean homeIsOurs(Context context) {
        // MIUI answers resolveActivity with its own launcher even when we hold the HOME
        // preference, so read the preference table directly instead.
        if (currentHome(context).startsWith(context.getPackageName() + "/")) return true;
        ArrayList<IntentFilter> filters = new ArrayList<>();
        ArrayList<ComponentName> names = new ArrayList<>();
        try { context.getPackageManager().getPreferredActivities(filters, names, null); }
        catch (RuntimeException e) { return false; }
        for (ComponentName name : names) if (name.getPackageName().equals(context.getPackageName())) return true;
        return false;
    }
    static void rememberHome(Context context) {
        if (prefs(context).contains("original_home")) return;
        String home = currentHome(context);
        ComponentName component = ComponentName.unflattenFromString(home);
        if (component != null && !component.getPackageName().equals(context.getPackageName())
                && !component.getPackageName().equals("android")) prefs(context).edit().putString("original_home", home).apply();
    }
    static void refreshHomeService(Context context) {
        SharedPreferences p = prefs(context);
        if (p.getInt("home_filter_revision", 0) >= 1 || !p.getBoolean("home_compat", false) || !authorized(context)) return;
        // Android 9 retains old accessibility metadata across an APK upgrade.
        // Rebind only our service once; preserve every other enabled service.
        Set<String> current = services(Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
        for (String s : new ArrayList<>(current)) if (own(s)) current.remove(s);
        try {
            writeServices(context, current);
            p.edit().putInt("home_filter_revision", 1).apply();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> recover(context), 300);
        } catch (RuntimeException e) { Log.w(TAG, "Home key service refresh failed", e); }
    }
    private static Set<String> services(String value) {
        Set<String> out = new LinkedHashSet<>();
        if (value != null) for (String entry : value.split(":")) if (ComponentName.unflattenFromString(entry) != null) out.add(entry);
        return out;
    }
    private static boolean competing(String value) {
        ComponentName name = ComponentName.unflattenFromString(value);
        return name != null && (name.getPackageName().equals("com.spocky.projengmenu") || name.getPackageName().equals("com.maric.adbkeeper") || name.getPackageName().equals("com.terence.accessibilitykeeper"));
    }
    private static boolean own(String value) {
        ComponentName name = ComponentName.unflattenFromString(value);
        return name != null && name.equals(ComponentName.unflattenFromString(OWN_SERVICE));
    }
    private static String join(Set<String> values) {
        StringBuilder s = new StringBuilder();
        for (String value : values) { if (s.length() > 0) s.append(':'); s.append(value); }
        return s.toString();
    }
    private static void writeServices(Context context, Set<String> wanted) {
        String current = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String next = join(wanted);
        if (!next.equals(current)) Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
        int enabled = wanted.isEmpty() ? 0 : 1;
        if (Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) != enabled)
            Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, enabled);
    }
    static void recover(Context context) {
        SharedPreferences p = prefs(context);
        if (!authorized(context)) {
            p.edit().putString("last_error", "尚未获得一次性系统设置授权").apply(); return;
        }
        try {
            if (p.getBoolean("adb_restore", false) && Settings.Global.getInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 0) != 1) {
                boolean written = Settings.Global.putInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 1);
                if (!written) throw new IllegalStateException("ADB setting rejected");
                p.edit().putLong("last_adb_restore", System.currentTimeMillis()).apply();
                Diagnostics.event("ADB_SWITCH_RESTORED");Log.i(TAG, "Restored adb_enabled=1");
            }
            if (p.getBoolean("managed_home", false) || p.getBoolean("home_compat", false)) {
                Set<String> wanted = services(Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
                for (String service : new ArrayList<>(wanted)) if (competing(service)) wanted.remove(service);
                if (p.getBoolean("home_compat", false)) {
                    for (String service : new ArrayList<>(wanted)) if (own(service)) wanted.remove(service);
                    wanted.add(OWN_SERVICE);
                }
                writeServices(context, wanted);
            }
            p.edit().remove("last_error").putLong("last_check", System.currentTimeMillis()).apply();
        } catch (RuntimeException e) {
            p.edit().putString("last_error", "恢复失败：" + e.getClass().getSimpleName()).apply();
            Diagnostics.event("SYSTEM_RECOVERY_FAILED");
            Log.w(TAG, "Settings recovery failed", e);
        }
    }
    private static void enableHome(Context context, boolean enabled) {
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
            p.edit().putString("saved_competing_services", join(saved)).commit();
        }
        p.edit().putBoolean("managed_home", true).putBoolean("boot_home", true).commit();
        enableHome(context, true); recover(context); startRecovery(context);
    }
    private static void permissionHelp(Activity activity, DialogHost host) {
        host.show(new TvDialog.Builder(activity).setTitle("首次需要电脑授权")
                .setMessage("连接电视 ADB 后，在电脑执行一次下列命令。授权通常会保留到卸载应用或恢复出厂设置。\n\nadb shell pm grant com.pilipala.tvhome android.permission.WRITE_SECURE_SETTINGS\n\n然后返回此页开启功能。部分厂商仍会限制后台运行。")
                .setPositiveButton("知道了", null).create());
    }
    static void show(Activity activity, DialogHost host) {
        SharedPreferences p = prefs(activity); rememberHome(activity);
        boolean ownHome = homeIsOurs(activity);
        String[] items = {
                "ADB 自动恢复：" + (p.getBoolean("adb_restore", false) ? "已开启" : "未开启"),
                "立即检查 ADB", "设为默认桌面",
                "开机打开 Marco TV：" + (p.getBoolean("boot_home", false) ? "已开启" : "未开启"),
                "Home 键兼容模式：" + (p.getBoolean("home_compat", false) ? "已开启" : "未开启"),
                "恢复原桌面", "查看运行状态", "调试与授权说明"};
        host.show(new TvDialog.Builder(activity).setTitle("开机与默认桌面")
                .setMessage("当前默认桌面：" + (ownHome ? "Marco TV" : "厂商或其他桌面")
                        + (ownHome ? "" : "\n部分电视在 Marco TV 更新后会重置此项，回到本页选择「设为默认桌面」即可恢复。"))
                .setOrder("startup","adb","check_adb","home","boot","home_key","restore","status","help").setItems(items, (d, which) ->
                activity.getWindow().getDecorView().post(() -> {
                    switch (which) {
                        case 0:
                            boolean next = !p.getBoolean("adb_restore", false);
                            if (next && !authorized(activity)) { permissionHelp(activity, host); break; }
                            if (next) host.show(new TvDialog.Builder(activity).setTitle("开启 ADB 自动恢复")
                                    .setMessage("开机后恢复调试开关，运行期间被关闭时也会恢复。使用完成后可在此关闭自动恢复。\n\n网络 ADB 还需系统保留端口配置；本功能不会关闭设备授权验证。")
                                    .setPositiveButton("开启", (dialog, button) -> { p.edit().putBoolean("adb_restore", true).commit(); recover(activity); startRecovery(activity); show(activity,host); })
                                    .setNegativeButton("返回", null).create());
                            else { p.edit().putBoolean("adb_restore", false).commit(); startRecovery(activity); show(activity,host); }
                            break;
                        case 1: recover(activity); status(activity, host); break;
                        case 2:
                            host.show(new TvDialog.Builder(activity).setTitle("使用 Marco TV 桌面")
                                    .setMessage("将开放 Marco TV 的默认桌面入口，并开启开机打开。已授权时会暂停已识别的其他桌面辅助服务，保留其他无障碍服务。可通过“恢复原桌面”撤销。")
                                    .setPositiveButton("继续", (dialog, button) -> {
                                        prepareHome(activity);
                                        Intent choose = new Intent(Settings.ACTION_HOME_SETTINGS);
                                        ResolveInfo info = activity.getPackageManager().resolveActivity(choose, 0);
                                        if (info != null && !info.activityInfo.name.endsWith("EmptyStubActivity")) {
                                            try { activity.startActivity(choose); return; } catch (RuntimeException ignored) { }
                                        }
                                        activity.getWindow().getDecorView().post(() -> host.show(new TvDialog.Builder(activity)
                                                .setTitle("这台电视隐藏了默认桌面选择")
                                                .setMessage("可用电脑设置，或返回开启 Home 键兼容模式。\n\nadb shell cmd package set-home-activity --user 0 com.pilipala.tvhome/.HomeActivity")
                                                .setPositiveButton("知道了", null).create()));
                                    }).setNegativeButton("返回", null).create()); break;
                        case 3: p.edit().putBoolean("boot_home", !p.getBoolean("boot_home", false)).commit(); show(activity,host); break;
                        case 4:
                            if (p.getBoolean("home_compat", false)) {
                                p.edit().putBoolean("home_compat", false).commit();
                                Set<String> wanted = services(Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
                                for (String service : new ArrayList<>(wanted)) if (own(service)) wanted.remove(service);
                                if (authorized(activity)) writeServices(activity, wanted);
                                startRecovery(activity);
                                show(activity,host);
                            } else if (!authorized(activity)) { permissionHelp(activity, host);
                            } else if (!p.contains("original_home")) {
                                host.show(new TvDialog.Builder(activity).setTitle("未找到原桌面").setMessage("请先在系统中选择原桌面，再打开 Marco TV 设置兼容模式。")
                                        .setPositiveButton("知道了", null).create());
                            } else host.show(new TvDialog.Builder(activity).setTitle("开启 Home 键兼容模式")
                                    .setMessage("优先直接响应遥控器 Home 键；系统未提供按键时，使用桌面窗口检测恢复 Marco TV。不读取屏幕内容。将暂停其他桌面辅助应用的恢复，保留其他无障碍服务。")
                                    .setPositiveButton("开启", (dialog, button) -> { prepareHome(activity); p.edit().putBoolean("home_compat", true).commit(); recover(activity); startRecovery(activity); show(activity,host); })
                                    .setNegativeButton("返回", null).create()); break;
                        case 5:
                            host.show(new TvDialog.Builder(activity).setTitle("恢复原桌面")
                                    .setMessage("关闭 Marco TV 的默认桌面入口、开机打开与 Home 键兼容，恢复此前暂停的桌面辅助服务。ADB 自动恢复保持当前选择。")
                                    .setPositiveButton("恢复", (dialog, button) -> restoreHome(activity))
                                    .setNegativeButton("返回", null).create()); break;
                        case 6: status(activity, host); break;
                        case 7:
                            host.show(new TvDialog.Builder(activity).setTitle("调试与授权")
                                    .setMessage("首次安装需在电视系统中打开 ADB，并通过电脑完成一次授权。授权后可在 Marco TV 内开启自动恢复。\n\n卸载应用或恢复出厂设置后，需要重新授权。网络端口是否能恢复还取决于电视固件。")
                                    .setPositiveButton("查看授权方法", (dialog, button) -> permissionHelp(activity, host))
                                    .setNegativeButton("返回", null).create()); break;
                    }
                })).setNegativeButton("返回", null).create());
    }
    private static void restoreHome(Activity activity) {
        SharedPreferences p = prefs(activity);
        p.edit().putBoolean("managed_home", false).putBoolean("home_compat", false).putBoolean("boot_home", false).commit();
        if (authorized(activity)) {
            Set<String> wanted = services(Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
            for (String service : new ArrayList<>(wanted)) if (own(service)) wanted.remove(service);
            for (String saved : services(p.getString("saved_competing_services", ""))) {
                try { activity.getPackageManager().getServiceInfo(ComponentName.unflattenFromString(saved), 0); wanted.add(saved); }
                catch (PackageManager.NameNotFoundException ignored) { }
            }
            writeServices(activity, wanted);
        }
        activity.getSharedPreferences("home",0).edit().putBoolean("ambient_enabled",false).apply();
        AmbientIntegration.configure(activity);
        enableHome(activity, false); startRecovery(activity);
        ComponentName original = ComponentName.unflattenFromString(p.getString("original_home", ""));
        try {
            if (original != null) {
                ActivityInfo info = activity.getPackageManager().getActivityInfo(original, 0);
                if (info.permission == null || activity.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED)
                    activity.startActivity(new Intent(Intent.ACTION_MAIN).setComponent(original).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            else activity.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        } catch (Exception e) { Log.w(TAG, "Original HOME requires the system Home key", e); }
        // Some OEM launchers require a signature permission. Let the system expose HOME
        // by finishing our task instead of attempting to bypass that permission.
        activity.finishAndRemoveTask();
    }
    private static void status(Activity activity, DialogHost host) {
        String base = "系统设置授权：" + (authorized(activity) ? "已授权" : "未授权")
                + "\nADB 调试开关：" + (Settings.Global.getInt(activity.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1 ? "已开启" : "未开启")
                + "\n系统默认桌面：" + (homeIsOurs(activity) ? "Marco TV" : "厂商或其他桌面")
                + "\nHome 键兼容：" + (prefs(activity).getBoolean("home_compat", false) ? "已开启" : "未开启")
                + "\n最近开机恢复：" + stamp(prefs(activity).getLong("last_boot_time", 0))
                + "\n最近恢复 ADB：" + stamp(prefs(activity).getLong("last_adb_restore", 0))
                + "\n" + prefs(activity).getString("last_error", "");
        new Thread(() -> {
            boolean reachable = false;
            try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", 5555), 1000); reachable = true; }
            catch (Exception ignored) { }
            String message = base + "\n本机 5555 端口：" + (reachable ? "可连接" : "未监听或不支持")
                    + "\n\n端口检查不等于局域网连接成功；其他电视可能还需要厂商设置或系统权限。";
            activity.runOnUiThread(() -> { if (!activity.isFinishing() && !activity.isDestroyed()) host.show(new TvDialog.Builder(activity)
                    .setTitle("运行状态").setMessage(message).setPositiveButton("知道了", null).create()); });
        }, "Marco TV-port-check").start();
    }
    private static String stamp(long millis) { return millis == 0 ? "尚无记录" : DateFormat.getDateTimeInstance().format(new Date(millis)); }
}
