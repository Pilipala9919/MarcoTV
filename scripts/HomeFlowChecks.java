// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.provider.Settings;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/**
 * End-to-end production logic checks for Tasks 1, 2, 3 and Round-2 regressions.
 * Compiles and executes real DefaultHomePanel, LocalHomeAuthorizer, HomeSetup,
 * HomeSetupPolicy, HomeDeviceGuide, SystemIntegration, HomeSetupSnapshot,
 * HomeShellSetup, BootReceiver, StatusSnapshot, TaskBridgeClient and HomePressTracker
 * against in-memory Android services.
 */
public final class HomeFlowChecks {
    static int passed;

    static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
        passed++;
        System.out.println("PASS " + name);
    }

    static void drainBridgeSync() throws Exception {
        Field f = TaskBridgeClient.class.getDeclaredField("worker");
        f.setAccessible(true);
        ExecutorService ex = (ExecutorService) f.get(null);
        ex.submit(() -> { }).get(5, TimeUnit.SECONDS);
    }

    static ResolveInfo homeEntry(String pkg, String cls, boolean enabled, boolean exported, boolean appEnabled, boolean system) {
        ResolveInfo r = new ResolveInfo();
        r.activityInfo = new ActivityInfo();
        r.activityInfo.packageName = pkg;
        r.activityInfo.name = cls;
        r.activityInfo.enabled = enabled;
        r.activityInfo.exported = exported;
        r.activityInfo.applicationInfo = new ApplicationInfo();
        r.activityInfo.applicationInfo.enabled = appEnabled;
        r.activityInfo.applicationInfo.flags = system ? ApplicationInfo.FLAG_SYSTEM : 0;
        return r;
    }

    static MainActivity freshEnv() {
        BuildEdition.RESTRICTED = false;
        Build.MANUFACTURER = "Xiaomi";
        Build.BRAND = "Xiaomi";
        Build.MODEL = "MiTV-ASTP0";
        Build.DISPLAY = "PPR1.180610.011";
        Build.VERSION.SDK_INT = 28;
        Build.VERSION.RELEASE = "9";
        SystemClock.elapsed = 10000L;
        Handler.clearDelayed();
        HomeSetup.bootIdOverride = "boot-uuid-1";
        HomeSetup.resetProcessSessionForTest();
        SystemIntegration.resetRebindStateForTest();
        Settings.Secure.failNextPutStrings = 0;
        Settings.Secure.failNextPutInts = 0;
        Settings.Secure.throwNextPutInts = 0;
        HomeKeyService.resetForTest();
        RecoveryService.running = false;
        Diagnostics.events.clear();
        LocalAdbWire.reset();
        Settings.Global.ints.clear();
        Settings.Secure.ints.clear();
        Settings.Secure.strings.clear();
        Settings.Global.ints.put("boot_count", 3);
        Settings.Global.ints.put("adb_enabled", 0);
        MainActivity host = new MainActivity();
        LocalAdbWire.activeHost = host;
        host.pm.homes.add(homeEntry("com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode", true, true, true, true));
        host.pm.homes.add(homeEntry("com.pilipala.tvhome", "com.pilipala.tvhome.HomeActivity", true, true, true, false));
        host.pm.currentHome = "com.mitv.tvhome/com.mitv.tvhome.MainActivityUserMode";
        host.pm.actions.put(Settings.ACTION_HOME_SETTINGS, homeEntry("com.android.tv.settings", "com.android.tv.settings.HomePicker", true, true, true, true));
        host.pm.actions.put(Settings.ACTION_ACCESSIBILITY_SETTINGS, homeEntry("com.android.tv.settings", "com.android.tv.settings.AccSettings", true, true, true, true));
        host.pm.actions.put(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, homeEntry("com.android.tv.settings", "com.android.tv.settings.DevSettings", true, true, true, true));
        host.pm.actions.put(Settings.ACTION_SETTINGS, homeEntry("com.android.tv.settings", "com.android.tv.settings.MainSettings", true, true, true, true));
        host.defaultHome = new DefaultHomePanel(host);
        host.foreground = true;
        host.windowFocus = true;
        host.arrivalGate.onResume(host, true, false, false, false);
        return host;
    }

    static void setService(boolean enabled, boolean connected) {
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, enabled ? 1 : 0);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                enabled ? HomeSetup.SERVICE : "");
        HomeKeyService.connected = connected;
    }

    static void testTask1Routing() {
        // 1a. Standard edition with RoleManager (API 29+) -> enables HOME alias, launches RoleManager, succeeds on readback.
        MainActivity host = freshEnv();
        Build.VERSION.SDK_INT = 29;
        host.roleAvailable = true;
        host.defaultHome.show();
        check("t1_standard_picker_label", host.dialogs.active().items[0].equals("一键启用 Marco TV"));
        host.dialogs.active().clickItem(0);
        check("t1_standard_picker_enables_home_and_requests_role",
                host.pm.alias == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                && host.startedActivities.size() == 1
                && host.startedReqCodes.get(0) == 812);
        host.pm.currentHome = "com.pilipala.tvhome/com.pilipala.tvhome.HomeActivity";
        host.defaultHome.onPickerResult(Activity.RESULT_OK);
        host.defaultHome.resumed();
        check("t1_standard_picker_readback_ready_opens_verification",
                host.isPage("home_verification") && SystemIntegration.homeIsOurs(host)
                && host.sysPrefs.getBoolean("setup_native_seen", false));

        // 1b. Standard picker cancelled or OEM keeps HOME -> re-reads HOME, offers Home compat next, never auto-launches ADB.
        host = freshEnv();
        Build.VERSION.SDK_INT = 29;
        host.roleAvailable = true;
        host.defaultHome.show();
        host.dialogs.active().clickItem(0);
        host.defaultHome.onPickerResult(Activity.RESULT_CANCELED);
        host.defaultHome.resumed();
        check("t1_picker_cancel_offers_compat_next_no_adb",
                host.isPage("home_picker_next")
                && host.dialogs.active().message.contains("取消")
                && host.dialogs.active().items[0].contains("Home 兼容")
                && LocalAdbWire.connects.get() == 0);
        // Even if RoleManager reported RESULT_OK, OEM keeping its launcher must NOT mark Marco as default.
        host = freshEnv();
        Build.VERSION.SDK_INT = 29;
        host.roleAvailable = true;
        host.pm.actions.remove(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        host.defaultHome.show();
        host.dialogs.active().clickItem(0);
        host.defaultHome.onPickerResult(Activity.RESULT_OK);
        host.defaultHome.resumed();
        check("t1_picker_oem_keeps_home_offers_debug_when_no_acc",
                host.isPage("home_picker_next")
                && !SystemIntegration.homeIsOurs(host)
                && host.dialogs.active().items[0].contains("调试与手动指引"));

        // 1c. No picker + Accessibility available -> main button goes straight to compatibility (NO ADB!).
        host = freshEnv();
        host.pm.actions.remove(Settings.ACTION_HOME_SETTINGS);
        host.defaultHome.show();
        host.dialogs.active().clickItem(0);
        check("t1_accessibility_main_button_enters_compat_consent_directly",
                host.isPage("home_compat_consent") && LocalAdbWire.connects.get() == 0);
        host.dialogs.active().clickPositive();
        check("t1_accessibility_confirm_opens_settings_without_adb",
                host.startedActivities.size() == 1
                && Settings.ACTION_ACCESSIBILITY_SETTINGS.equals(host.startedActivities.get(0).getAction())
                && LocalAdbWire.connects.get() == 0);
        // Allowed AND bound/connected -> verification!
        setService(true, true);
        host.defaultHome.resumed();
        check("t1_accessibility_connected_finishes_without_adb",
                host.isPage("home_verification")
                && HomeSetup.route(host) == HomeSetupPolicy.Route.COMPAT_READY
                && LocalAdbWire.connects.get() == 0);

        // 1d. Allowed but UNBOUND -> distinct from connected, does not claim ready, does not jump to ADB guide!
        host = freshEnv();
        host.pm.actions.remove(Settings.ACTION_HOME_SETTINGS);
        host.defaultHome.show();
        host.dialogs.active().clickItem(0);
        host.dialogs.active().clickPositive();
        setService(true, false);
        host.defaultHome.resumed();
        check("t1_accessibility_allowed_unbound_distinct_and_not_ready",
                host.isPage("home_compat_unbound")
                && host.dialogs.active().message.contains("尚未绑定连接")
                && HomeSetup.route(host) != HomeSetupPolicy.Route.COMPAT_READY
                && !host.isPage("home_device_guide"));

        // 1e. Accessibility cancelled by user (not enabled) -> stays clean, never jumps to ADB guide.
        host = freshEnv();
        host.pm.actions.remove(Settings.ACTION_HOME_SETTINGS);
        host.defaultHome.show();
        host.dialogs.active().clickItem(0);
        host.dialogs.active().clickPositive();
        setService(false, false);
        host.defaultHome.resumed();
        check("t1_accessibility_cancel_does_not_jump_to_adb",
                host.isPage("default_home") && !host.toasts.isEmpty() && LocalAdbWire.connects.get() == 0);

        // 1f. Restricted edition -> skips HOME picker even if system has one, no "system default" promise in UI.
        host = freshEnv();
        BuildEdition.RESTRICTED = true;
        Build.VERSION.SDK_INT = 29;
        host.roleAvailable = true;
        host.defaultHome.show();
        check("t1_restricted_show_has_no_default_promise",
                host.dialogs.active().message.contains("不含系统默认桌面声明"));
        host.dialogs.active().clickItem(0);
        check("t1_restricted_routes_directly_to_compat",
                host.isPage("home_compat_consent") && host.startedActivities.isEmpty());
        host.defaultHome.help();
        boolean hasPickerOption = false;
        for (String item : host.dialogs.active().items) if (item.contains("系统默认桌面选择")) hasPickerOption = true;
        check("t1_restricted_help_omits_system_default_picker",
                !hasPickerOption && host.dialogs.active().items.length == 7);

        // 1g. Both system entries unavailable -> explains probe results, offers normal network ADB & manual guide.
        host = freshEnv();
        host.pm.actions.remove(Settings.ACTION_HOME_SETTINGS);
        host.pm.actions.remove(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        host.defaultHome.show();
        host.dialogs.active().clickItem(0);
        check("t1_enhanced_explains_probe_and_pairing_boundary",
                host.isPage("home_local_consent")
                && host.dialogs.active().message.contains("未检测到可用默认桌面选择页")
                && host.dialogs.active().message.contains("未检测到直接辅助功能入口")
                && host.dialogs.active().message.contains("连接失败不等于电视只支持配对码"));
        host.defaultHome.deviceGuide(HomeDeviceGuide.Issue.CONNECTION);
        check("t1_device_guide_unified_pairing_guidance",
                host.dialogs.active().message.contains("Android Platform Tools")
                && host.dialogs.active().message.contains("辅助功能")
                && !host.dialogs.active().message.contains("仅有配对码时请用助手"));
        SystemIntegration.showDebugHelp(host, host::showDialog);
        check("t1_system_debug_help_unified_pairing_guidance",
                host.dialogs.active().message.contains("Android Platform Tools")
                && host.dialogs.active().message.contains("1.0.1")
                && !host.dialogs.active().message.contains("可使用手机或电脑助手"));

        // 1h. Ready -> verification; Paused -> user resumes and re-routes by current capability.
        host = freshEnv();
        host.pm.currentHome = "com.pilipala.tvhome/com.pilipala.tvhome.HomeActivity";
        host.defaultHome.show();
        check("t1_ready_offers_verification", host.dialogs.active().items[0].equals("检查使用效果"));
        host.dialogs.active().clickItem(0);
        check("t1_ready_enters_verification", host.isPage("home_verification"));

        host = freshEnv();
        host.pm.actions.remove(Settings.ACTION_HOME_SETTINGS);
        HomeSetup.pause(host, "paused by user");
        host.defaultHome.show();
        check("t1_paused_label", host.dialogs.active().items[0].equals("继续一键设置"));
        host.dialogs.active().clickItem(0);
        check("t1_paused_resume_reroutes_to_accessibility", host.isPage("home_compat_consent"));

        host = freshEnv();
        HomeSetup.pause(host, "paused by user");
        host.defaultHome.show();
        host.dialogs.active().clickItem(0);
        check("t1_paused_resume_reroutes_to_picker",
                host.startedActivities.size() == 1
                && Settings.ACTION_HOME_SETTINGS.equals(host.startedActivities.get(0).getAction()));

        // 1i. Cancel, Home leave, Activity recreation cancel pending continuation; only explicit guide return continues once.
        host = freshEnv();
        LocalAdbWire.connectFails = true;
        host.dialogs.armResultLatch();
        host.defaultHome.deviceGuide(HomeDeviceGuide.Issue.NONE);
        host.dialogs.active().clickItem(0); // "打开调试设置，返回后自动继续"
        host.defaultHome.resumed();
        check("t1_explicit_guide_return_continues_once",
                host.dialogs.awaitResult() && LocalAdbWire.connects.get() == 1);
        host.defaultHome.resumed();
        check("t1_explicit_guide_return_never_repeats", LocalAdbWire.connects.get() == 1);

        // Leave by pressing Home (closeMenus -> cancelPending + menuEpoch++)
        host = freshEnv();
        host.defaultHome.deviceGuide(HomeDeviceGuide.Issue.NONE);
        host.dialogs.active().clickItem(0);
        host.closeMenus();
        host.defaultHome.resumed();
        check("t1_home_leave_cancels_guide_continuation",
                !host.isPage("home_local_authorize") && LocalAdbWire.connects.get() == 0);

        // Leave by pressing Back out of deviceGuide (onLeave cancels guideReturn)
        host = freshEnv();
        host.defaultHome.deviceGuide(HomeDeviceGuide.Issue.NONE);
        host.dialogs.active().clickItem(0);
        host.dialogs.back();
        host.defaultHome.resumed();
        check("t1_back_cancel_cancels_guide_continuation",
                !host.isPage("home_local_authorize") && LocalAdbWire.connects.get() == 0);

        // Activity recreation creates fresh DefaultHomePanel -> no stale continuation
        host = freshEnv();
        host.defaultHome.deviceGuide(HomeDeviceGuide.Issue.NONE);
        host.dialogs.active().clickItem(0);
        MainActivity recreated = new MainActivity();
        recreated.foreground = true;
        recreated.sysPrefs = host.sysPrefs;
        recreated.pm = host.pm;
        recreated.defaultHome = new DefaultHomePanel(recreated);
        recreated.defaultHome.resumed();
        check("t1_activity_recreation_cancels_continuation",
                !recreated.isPage("home_local_authorize") && LocalAdbWire.connects.get() == 0);

        // Help -> "打开电视系统设置" does NOT arm automatic ADB continuation
        host = freshEnv();
        host.defaultHome.help();
        host.dialogs.active().clickItem(0);
        host.defaultHome.resumed();
        check("t1_help_settings_never_auto_runs_adb",
                host.isPage("default_home_help") && LocalAdbWire.connects.get() == 0);
    }

    static void testTask2Attribution() {
        // 2a. ASTP0 real state: OEM is HOME, pref table had Marco, accessibility bound, window fallback happened.
        MainActivity host = freshEnv();
        host.pm.preferredHomeIsOurs = true;
        HomeSetup.begin(host);
        host.sysPrefs.edit().putBoolean("home_compat", true).apply();
        setService(true, true);
        String wfToken = HomeSetup.issueHomeEvent(host, HomeSetup.SOURCE_WINDOW_FALLBACK);
        HomeSetup.observeHomeIntent(host, SystemIntegration.homeIntent(host).putExtra(HomeSetup.EXTRA_EVENT_TOKEN, wfToken));
        String astp0Summary = HomeSetup.summary(host);
        String astp0Status = StatusSnapshot.local(host);
        check("t2_native_home_never_faked_by_pref_or_intent",
                !SystemIntegration.homeIsOurs(host) && astp0Summary.contains("未生效") && astp0Summary.contains("偏好表曾记录 Marco"));
        check("t2_astp0_shows_all_three_facts",
                astp0Summary.contains("com.mitv.tvhome")
                && astp0Summary.contains("已允许并已连接（已绑定）")
                && astp0Summary.contains("原桌面窗口回拉")
                && astp0Status.contains("原桌面窗口回拉"));

        // 2b. 90s Home test on API 23 (missing boot_count and no boot_id): distinguishes all 4 sources!
        host = freshEnv();
        Build.VERSION.SDK_INT = 23;
        Settings.Global.ints.put("boot_count", -1);
        HomeSetup.bootIdOverride = "";
        SystemClock.elapsed = 20000L;
        HomeSetup.begin(host);
        HomeSetup.armHomeTest(host);
        // Ordinary launch must NOT pass
        HomeSetup.observeHomeIntent(host, new Intent(host, MainActivity.class));
        check("t2_ordinary_launch_never_passes_home_test", !host.sysPrefs.contains("setup_home_seen"));
        // Forged raw extras without in-app token must NOT pass
        HomeSetup.observeHomeIntent(host, SystemIntegration.homeIntent(host).putExtra("home_press_handled", true));
        check("t2_forged_home_extra_without_token_rejected", !host.sysPrefs.contains("setup_home_seen"));
        // Double-press / long-press opening recents must NOT pass short-press Home test
        HomeSetup.observeHomeIntent(host, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).putExtra("recent_apps", true), true, false);
        check("t2_double_or_long_press_recents_preserves_semantics", !host.sysPrefs.contains("setup_home_seen"));
        // Window fallback passes and shows exact source
        SystemClock.elapsed = 35000L;
        String tWf = HomeSetup.issueHomeEvent(host, HomeSetup.SOURCE_WINDOW_FALLBACK);
        HomeSetup.observeHomeIntent(host, SystemIntegration.homeIntent(host).putExtra(HomeSetup.EXTRA_EVENT_TOKEN, tWf));
        check("t2_api23_window_fallback_passes_with_source",
                host.sysPrefs.contains("setup_home_seen")
                && HomeSetup.SOURCE_WINDOW_FALLBACK.equals(host.sysPrefs.getString("setup_home_source", ""))
                && HomeSetup.summary(host).contains("原桌面窗口回拉"));
        // Compat key passes with exact source
        HomeSetup.armHomeTest(host);
        SystemClock.elapsed = 40000L;
        String tKey = HomeSetup.issueHomeEvent(host, HomeSetup.SOURCE_COMPAT_KEY);
        HomeSetup.observeHomeIntent(host, SystemIntegration.homeIntent(host).putExtra("home_press_handled", true).putExtra(HomeSetup.EXTRA_EVENT_TOKEN, tKey));
        check("t2_api23_compat_key_passes_with_source",
                HomeSetup.SOURCE_COMPAT_KEY.equals(host.sysPrefs.getString("setup_home_source", ""))
                && HomeSetup.summary(host).contains("Home 兼容按键"));
        // Compat broadcast passes with exact source
        HomeSetup.armHomeTest(host);
        SystemClock.elapsed = 45000L;
        String tBc = HomeSetup.issueHomeEvent(host, HomeSetup.SOURCE_COMPAT_BROADCAST);
        HomeSetup.observeHomeIntent(host, SystemIntegration.homeIntent(host).putExtra("home_press_handled", true).putExtra(HomeSetup.EXTRA_EVENT_TOKEN, tBc));
        check("t2_api23_compat_broadcast_passes_with_source",
                HomeSetup.SOURCE_COMPAT_BROADCAST.equals(host.sysPrefs.getString("setup_home_source", ""))
                && HomeSetup.summary(host).contains("Home 兼容广播"));
        // System HOME passes with exact source
        HomeSetup.armHomeTest(host);
        SystemClock.elapsed = 50000L;
        HomeSetup.observeHomeIntent(host, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        check("t2_api23_system_home_passes_with_source",
                HomeSetup.SOURCE_SYSTEM_HOME.equals(host.sysPrefs.getString("setup_home_source", ""))
                && HomeSetup.summary(host).contains("系统原生 HOME"));
        // API 23 timeout fails
        HomeSetup.armHomeTest(host);
        SystemClock.elapsed += 90001L;
        HomeSetup.observeHomeIntent(host, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        check("t2_api23_timeout_fails", !host.sysPrefs.contains("setup_home_seen"));
        // API 23 process recreation invalidates armed session
        HomeSetup.armHomeTest(host);
        HomeSetup.resetProcessSessionForTest();
        SystemClock.elapsed += 5000L;
        HomeSetup.observeHomeIntent(host, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        check("t2_api23_process_recreation_invalidates_home_test", !host.sysPrefs.contains("setup_home_seen"));
        // API 23 monotonic clock regression (reboot) fails
        HomeSetup.armHomeTest(host);
        SystemClock.elapsed = 1000L;
        HomeSetup.observeHomeIntent(host, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        check("t2_api23_reboot_clock_regression_fails", !host.sysPrefs.contains("setup_home_seen"));

        // Verify HomePressTracker double-press semantics remain unchanged
        HomePressTracker tracker = new HomePressTracker();
        check("t2_double_press_semantics_preserved",
                !tracker.press(1000L, 400) && tracker.press(1250L, 400) && !tracker.press(1350L, 400));

        // 2c. API 24+ normal boot_count + recreated / history / duplicate token guards
        host = freshEnv();
        HomeSetup.begin(host);
        HomeSetup.armHomeTest(host);
        // Recreated Activity or history relaunch must NOT verify
        HomeSetup.observeHomeIntent(host, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), false, true);
        HomeSetup.observeHomeIntent(host, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY), false, false);
        check("t2_recreated_and_history_intents_ignored", !host.sysPrefs.contains("setup_home_seen"));
        // Different boot fails
        Settings.Global.ints.put("boot_count", 4);
        HomeSetup.observeHomeIntent(host, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        check("t2_api24_different_boot_fails_home_test", !host.sysPrefs.contains("setup_home_seen"));

        // 2d. Manual Home press within 3 minutes after boot MUST NOT record automatic boot entry!
        host = freshEnv();
        Settings.Global.ints.put("boot_count", 3);
        HomeSetup.begin(host);
        host.sysPrefs.edit().putBoolean("home_compat", true).putBoolean("boot_home", true).apply();
        setService(true, true);
        // Reboot occurs (boot=4), user waits 30s and manually presses Home!
        Settings.Global.ints.put("boot_count", 4);
        HomeSetup.bootIdOverride = "boot-uuid-2";
        SystemClock.elapsed = 30000L;
        String manualKeyToken = HomeSetup.issueHomeEvent(host, HomeSetup.SOURCE_COMPAT_KEY);
        HomeSetup.observeHomeIntent(host, SystemIntegration.homeIntent(host).putExtra(HomeSetup.EXTRA_EVENT_TOKEN, manualKeyToken));
        // Forged exported setup_boot_return extra also MUST NOT record boot entry!
        HomeSetup.observeHomeIntent(host, SystemIntegration.homeIntent(host).putExtra("setup_boot_return", true));
        check("t2_manual_home_after_boot_and_forged_extra_never_record_boot",
                !host.sysPrefs.contains("setup_boot_observed")
                && !HomeSetup.BOOT_STATE_RECOVERY_ARRIVED.equals(host.sysPrefs.getString("setup_boot_state", "")));

        // 2e. Genuine BootReceiver recovery request followed by foreground arrival + single-use duplicate rejection!
        host = freshEnv();
        Settings.Global.ints.put("boot_count", 3);
        HomeSetup.begin(host);
        host.sysPrefs.edit().putBoolean("home_compat", true).putBoolean("boot_home", true).apply();
        setService(true, true);
        Settings.Global.ints.put("boot_count", 4);
        HomeSetup.bootIdOverride = "boot-uuid-3";
        SystemClock.elapsed = 12000L;
        BootReceiver receiver = new BootReceiver();
        receiver.onReceive(host, new Intent(Intent.ACTION_BOOT_COMPLETED));
        check("t2_boot_receiver_issues_correlated_launch",
                host.startedActivities.size() == 1
                && HomeSetup.BOOT_STATE_REQUESTED.equals(host.sysPrefs.getString("boot_req_state", ""))
                && HomeSetup.summary(host).contains("尚未观察到前台到达"));
        // Before arrival, confirmBoot must refuse!
        HomeSetup.confirmBoot(host);
        check("t2_unarrived_boot_request_cannot_confirm", !host.sysPrefs.contains("setup_boot_confirmed"));
        // Now MainActivity actually arrives with the issued Intent!
        Intent bootLaunch = host.startedActivities.get(0);
        SystemClock.elapsed = 14000L;
        HomeSetup.observeHomeIntent(host, bootLaunch);
        int diagCountAfterArrival = Diagnostics.events.size();
        check("t2_correlated_boot_arrival_recorded",
                HomeSetup.BOOT_STATE_RECOVERY_ARRIVED.equals(host.sysPrefs.getString("setup_boot_state", ""))
                && host.sysPrefs.getInt("setup_boot_observed", -1) == 4
                && HomeSetup.summary(host).contains("已记录开机恢复到达"));
        // Duplicate delivery of the exact same Intent must be a no-op (single-use token consumed!)
        HomeSetup.observeHomeIntent(host, bootLaunch);
        check("t2_duplicate_boot_intent_is_noop", Diagnostics.events.size() == diagCountAfterArrival);
        // Manual confirmBoot records separate manual confirmation line without turning native HOME true!
        HomeSetup.confirmBoot(host);
        check("t2_manual_boot_confirm_separate_and_never_fakes_native",
                host.sysPrefs.contains("setup_boot_confirmed")
                && HomeSetup.summary(host).contains("开机人工确认")
                && !SystemIntegration.homeIsOurs(host));

        // 2f. BootReceiver launch denied by system -> only request failure recorded, NOT arrived!
        host = freshEnv();
        Settings.Global.ints.put("boot_count", 3);
        HomeSetup.begin(host);
        host.sysPrefs.edit().putBoolean("home_compat", true).putBoolean("boot_home", true).apply();
        setService(true, true);
        Settings.Global.ints.put("boot_count", 4);
        HomeSetup.bootIdOverride = "boot-uuid-4";
        SystemClock.elapsed = 10000L;
        host.startActivityThrows = true;
        new BootReceiver().onReceive(host, new Intent(Intent.ACTION_BOOT_COMPLETED));
        check("t2_boot_launch_denied_recorded_without_arrival",
                HomeSetup.BOOT_STATE_LAUNCH_DENIED.equals(host.sysPrefs.getString("boot_req_state", ""))
                && !host.sysPrefs.contains("boot_req_token")
                && !host.sysPrefs.contains("setup_boot_observed")
                && HomeSetup.summary(host).contains("拒绝后台启动"));

        // 2g. Native HOME arrives BEFORE BootReceiver -> preserved as unconfirmed; BootReceiver does NOT re-launch!
        host = freshEnv();
        Settings.Global.ints.put("boot_count", 3);
        host.pm.currentHome = "com.pilipala.tvhome/com.pilipala.tvhome.HomeActivity";
        HomeSetup.begin(host);
        HomeSetup.acceptNative(host);
        Settings.Global.ints.put("boot_count", 4);
        HomeSetup.bootIdOverride = "boot-uuid-5";
        SystemClock.elapsed = 15000L;
        HomeSetup.observeHomeIntent(host, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        check("t2_native_home_before_receiver_marked_unconfirmed",
                HomeSetup.BOOT_STATE_NATIVE_UNCONFIRMED.equals(host.sysPrefs.getString("setup_boot_state", ""))
                && HomeSetup.summary(host).contains("来源待确认"));
        new BootReceiver().onReceive(host, new Intent(Intent.ACTION_BOOT_COMPLETED));
        check("t2_receiver_after_native_home_preserves_observation_and_does_not_relaunch",
                host.startedActivities.isEmpty()
                && HomeSetup.BOOT_STATE_NATIVE_UNCONFIRMED.equals(host.sysPrefs.getString("setup_boot_state", "")));

        // 2h. Standby wake and MY_PACKAGE_REPLACED upgrade never create boot entry success!
        host = freshEnv();
        Settings.Global.ints.put("boot_count", 3);
        host.pm.currentHome = "com.pilipala.tvhome/com.pilipala.tvhome.HomeActivity";
        HomeSetup.begin(host);
        HomeSetup.acceptNative(host);
        Settings.Global.ints.put("boot_count", 4);
        HomeSetup.bootIdOverride = "boot-uuid-6";
        SystemClock.elapsed = 20000L;
        HomeSetup.observeHomeIntent(host, SystemIntegration.homeIntent(host).putExtra("wake_recovery", true));
        HomeSetup.observeHomeIntent(host, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        check("t2_standby_wake_blocks_false_boot_observation", !host.sysPrefs.contains("setup_boot_observed"));

        host = freshEnv();
        Settings.Global.ints.put("boot_count", 3);
        host.pm.currentHome = "com.pilipala.tvhome/com.pilipala.tvhome.HomeActivity";
        HomeSetup.begin(host);
        HomeSetup.acceptNative(host);
        Settings.Global.ints.put("boot_count", 4);
        HomeSetup.bootIdOverride = "boot-uuid-7";
        SystemClock.elapsed = 20000L;
        new BootReceiver().onReceive(host, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        HomeSetup.observeHomeIntent(host, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        check("t2_apk_upgrade_never_records_boot_entry",
                host.startedActivities.isEmpty() && !host.sysPrefs.contains("setup_boot_observed"));

        // 2i. Legacy 0.21.2 preferences upgrade compatibility
        host = freshEnv();
        host.sysPrefs.edit().putBoolean("setup_requested", true)
                .putLong("setup_home_seen", 1700000000000L)
                .putInt("setup_boot_observed", 2)
                .putInt("setup_boot_confirmed", 2).apply();
        String legacySummary = HomeSetup.summary(host);
        check("t2_legacy_prefs_preserved_with_unknown_source",
                legacySummary.contains("旧版记录，来源未知")
                && legacySummary.contains("历史开机观察（旧版记录，来源未知）")
                && legacySummary.contains("已保留旧版人工确认（来源未知）"));
    }

    static void testTask3TrackedHomeAndSnapshot() throws Exception {
        // Attach a fake TaskBridge Binder to verify real TaskBridgeClient.configure JSON payload!
        final Thread testThread = Thread.currentThread();
        final List<JSONObject> bridgeRequests = Collections.synchronizedList(new ArrayList<>());
        IBinder fakeBridge = new IBinder() {
            @Override public boolean isBinderAlive() { return true; }
            @Override public void linkToDeath(DeathRecipient r, int flags) { }
            @Override public boolean transact(int code, Parcel data, Parcel reply, int flags) {
                data.readInterfaceToken();
                try {
                    JSONObject req = new JSONObject(data.readString());
                    if (Thread.currentThread() == testThread && "configure".equals(req.optString("action")))
                        bridgeRequests.add(req);
                    reply.writeNoException();
                    reply.writeString(new JSONObject().put("ok", true).put("wake", req.optBoolean("wake")).toString());
                    return true;
                } catch (Exception e) { throw new RuntimeException(e); }
            }
        };
        TaskBridgeClient.attach(fakeBridge);

        MainActivity host = freshEnv();
        host.pm.homes.clear();
        ResolveInfo launcherA = homeEntry("com.oem.a", "com.oem.a.Home", true, true, true, true);
        ResolveInfo launcherB = homeEntry("com.third.b", "com.third.b.Launcher", true, true, true, false); // third-party!
        ResolveInfo launcherC = homeEntry("com.third.c", "com.third.c.Home", true, true, true, false);
        ResolveInfo marcoHome = homeEntry("com.pilipala.tvhome", "com.pilipala.tvhome.HomeActivity", true, true, true, false);
        host.pm.homes.add(launcherA);
        host.pm.homes.add(launcherB);
        host.pm.homes.add(marcoHome);
        host.pm.currentHome = "com.oem.a/com.oem.a.Home";
        host.secureAuthorized = true;

        // First discovery captures A; snapshot captures A once for this round.
        SystemIntegration.rememberHome(host);
        HomeSetupSnapshot.capture(host);
        check("t3_first_discovery_records_A",
                "com.oem.a/com.oem.a.Home".equals(SystemIntegration.trackedHome(host))
                && "com.oem.a/com.oem.a.Home".equals(HomeSetupSnapshot.target(host)));

        // Scenario 1: A -> B, then ONLY open app / view settings / refresh status -> keeps A!
        host.pm.currentHome = "com.third.b/com.third.b.Launcher";
        SystemIntegration.rememberHome(host);
        HomeSetup.summary(host);
        StatusSnapshot.local(host);
        check("t3_a_to_b_passive_open_keeps_valid_A_and_snapshot_A",
                "com.oem.a/com.oem.a.Home".equals(SystemIntegration.trackedHome(host))
                && "com.oem.a/com.oem.a.Home".equals(HomeSetupSnapshot.target(host)));

        // Scenario 2: A -> B, then explicit user re-enable BEFORE mutating HOME -> updates tracked target to third-party B, keeps snapshot A!
        HomeSetupSnapshot.capture(host);
        HomeSetup.begin(host);
        host.sysPrefs.edit().putBoolean("home_compat", true).apply();
        bridgeRequests.clear();
        TaskBridgeClient.configure(host);
        JSONObject lastCfgB = bridgeRequests.isEmpty() ? null : bridgeRequests.get(bridgeRequests.size() - 1);
        check("t3_explicit_reenable_updates_target_to_third_party_B_and_syncs_bridge_and_window",
                "com.third.b/com.third.b.Launcher".equals(SystemIntegration.trackedHome(host))
                && SystemIntegration.matchesTrackedHomePackage(host, "com.third.b")
                && !SystemIntegration.matchesTrackedHomePackage(host, "com.oem.a")
                && "com.third.b".equals(TaskBridgeClient.configuredHomePackage(host))
                && lastCfgB != null && "com.third.b".equals(lastCfgB.optString("home")));
        check("t3_retry_in_same_round_preserves_snapshot_A",
                "com.oem.a/com.oem.a.Home".equals(HomeSetupSnapshot.target(host)));

        // Scenario 3: Repeated enable after system HOME already switched to Marco -> keeps B and snapshot A!
        host.pm.currentHome = "com.pilipala.tvhome/com.pilipala.tvhome.HomeActivity";
        HomeSetupSnapshot.capture(host);
        HomeSetup.begin(host);
        check("t3_repeated_enable_with_self_home_preserves_B_and_snapshot_A",
                "com.third.b/com.third.b.Launcher".equals(SystemIntegration.trackedHome(host))
                && "com.oem.a/com.oem.a.Home".equals(HomeSetupSnapshot.target(host)));

        // Scenario 4: Ambiguous candidate (ResolverActivity / Settings placeholder with multiple launchers) -> preserves valid B; never guesses when empty!
        host.pm.homes.add(launcherC);
        host.pm.currentHome = "android/com.android.internal.app.ResolverActivity";
        HomeSetup.begin(host);
        check("t3_ambiguous_resolver_keeps_existing_valid_B",
                "com.third.b/com.third.b.Launcher".equals(SystemIntegration.trackedHome(host)));
        host.pm.currentHome = "com.android.settings/com.android.settings.FallbackHome";
        HomeSetup.begin(host);
        check("t3_settings_placeholder_keeps_existing_valid_B",
                "com.third.b/com.third.b.Launcher".equals(SystemIntegration.trackedHome(host)));

        MainActivity ambiguousFresh = freshEnv();
        ambiguousFresh.pm.homes.clear();
        ambiguousFresh.pm.homes.add(launcherB);
        ambiguousFresh.pm.homes.add(launcherC);
        ambiguousFresh.pm.homes.add(marcoHome);
        ambiguousFresh.pm.currentHome = "android/com.android.internal.app.ResolverActivity";
        SystemIntegration.rememberHome(ambiguousFresh);
        HomeSetup.begin(ambiguousFresh);
        check("t3_ambiguous_fresh_never_guesses_target",
                SystemIntegration.trackedHome(ambiguousFresh).isEmpty());

        // Scenario 5: Tracked target B uninstalled with ambiguous remaining launchers (A and C) -> stops fallback, syncs empty to bridge, shows status warning!
        host.pm.homes.remove(launcherB);
        host.pm.currentHome = "com.pilipala.tvhome/com.pilipala.tvhome.HomeActivity";
        SystemIntegration.syncTrackedHomeAfterPackageChange(host);
        bridgeRequests.clear();
        host.sysPrefs.edit().putBoolean("home_compat", true).apply();
        TaskBridgeClient.configure(host);
        JSONObject lastCfgDead = bridgeRequests.isEmpty() ? null : bridgeRequests.get(bridgeRequests.size() - 1);
        String invalidSummary = HomeSetup.summary(host);
        String invalidStatus = StatusSnapshot.local(host);
        check("t3_dead_target_stops_fallback_and_reports_warning",
                SystemIntegration.trackedHome(host).isEmpty()
                && SystemIntegration.trackedHomeInvalid(host)
                && !SystemIntegration.matchesTrackedHomePackage(host, "com.third.b")
                && TaskBridgeClient.configuredHomePackage(host).isEmpty()
                && lastCfgDead != null && lastCfgDead.optString("home").isEmpty()
                && !lastCfgDead.optBoolean("wake")
                && invalidSummary.contains("原记录桌面已失效")
                && invalidStatus.contains("已停止窗口回拉"));

        // Snapshot target A uninstalled -> HomeSetupSnapshot.target returns empty (never guesses), snapshot preserved!
        host.pm.homes.remove(launcherA);
        check("t3_uninstalled_snapshot_A_rejected_before_restore",
                HomeSetupSnapshot.target(host).isEmpty()
                && host.sysPrefs.getBoolean(HomeSetupSnapshot.PREFIX + "saved", false));

        // Scenario 6: Restore failure preserves snapshot A, retry succeeds while keeping pre-existing service, authorization and prior pause!
        host = freshEnv();
        host.pm.homes.clear();
        host.pm.homes.add(launcherA);
        host.pm.homes.add(marcoHome);
        host.pm.currentHome = "com.oem.a/com.oem.a.Home";
        host.secureAuthorized = true;
        setService(true, true); // pre-existing accessibility service!
        host.sysPrefs.edit().putBoolean("setup_paused", true).putString("setup_pause_reason", "prior pause").apply();
        HomeSetupSnapshot.capture(host);
        host.sysPrefs.edit().putBoolean("setup_paused", false).putString("setup_pause_reason", "mutated").putBoolean("home_compat", true).apply();
        host.pm.failAlias = true;
        check("t3_restore_failure_keeps_snapshot_and_target_A",
                !HomeSetupSnapshot.restoreLocal(host)
                && host.sysPrefs.getBoolean(HomeSetupSnapshot.PREFIX + "saved", false)
                && "com.oem.a/com.oem.a.Home".equals(HomeSetupSnapshot.target(host)));
        host.pm.failAlias = false;
        check("t3_restore_retry_succeeds_and_preserves_service_auth_and_pause",
                HomeSetupSnapshot.restoreLocal(host)
                && HomeSetup.serviceEnabled(host)
                && SystemIntegration.authorized(host)
                && HomeSetup.paused(host)
                && "prior pause".equals(host.sysPrefs.getString("setup_pause_reason", "")));
    }

    static void testRound2Regressions() throws Exception {
        // Attach a dedicated TaskBridge Binder recording EVERY real configure IPC (sync + async).
        drainBridgeSync();
        final List<JSONObject> bridgeConfigs = Collections.synchronizedList(new ArrayList<>());
        IBinder round2Bridge = new IBinder() {
            @Override public boolean isBinderAlive() { return true; }
            @Override public void linkToDeath(DeathRecipient r, int flags) { }
            @Override public boolean transact(int code, Parcel data, Parcel reply, int flags) {
                data.readInterfaceToken();
                try {
                    JSONObject req = new JSONObject(data.readString());
                    if ("configure".equals(req.optString("action"))) bridgeConfigs.add(req);
                    reply.writeNoException();
                    reply.writeString(new JSONObject().put("ok", true).put("wake", req.optBoolean("wake")).toString());
                    return true;
                } catch (Exception e) { throw new RuntimeException(e); }
            }
        };
        TaskBridgeClient.attach(round2Bridge);

        // =====================================================================
        // Fix #1: Per-boot-round tracking — (a) succeed then denied, (b) succeed then no arrival, (c) duplicate broadcast
        // =====================================================================
        // 1a. Succeed in boot #4, then denied in boot #5 -> panel MUST show current-round denied, NOT current success!
        MainActivity host = freshEnv();
        Settings.Global.ints.put("boot_count", 3);
        HomeSetup.begin(host);
        host.sysPrefs.edit().putBoolean("home_compat", true).putBoolean("boot_home", true).apply();
        setService(true, true);
        // Boot #4 succeeds
        Settings.Global.ints.put("boot_count", 4);
        HomeSetup.bootIdOverride = "boot-r2-4";
        SystemClock.elapsed = 12000L;
        new BootReceiver().onReceive(host, new Intent(Intent.ACTION_BOOT_COMPLETED));
        Intent boot4Launch = host.startedActivities.get(0);
        host.simulateOnPause();
        host.simulateOnNewIntent(boot4Launch);
        host.simulateOnResume(true);
        HomeSetup.confirmBoot(host);
        check("r2_1a_boot4_initial_success_and_confirm",
                HomeSetup.BOOT_STATE_RECOVERY_ARRIVED.equals(host.sysPrefs.getString("boot_req_state", ""))
                && HomeSetup.bootStatus(host).contains("已记录开机恢复到达")
                && host.sysPrefs.getInt("setup_boot_confirmed", -1) == 4);
        // Boot #5 launch denied by system
        Settings.Global.ints.put("boot_count", 5);
        HomeSetup.bootIdOverride = "boot-r2-5";
        SystemClock.elapsed = 11000L;
        host.startActivityThrows = true;
        new BootReceiver().onReceive(host, new Intent(Intent.ACTION_BOOT_COMPLETED));
        String statusBoot5Denied = HomeSetup.bootStatus(host);
        String confirmBoot5Denied = HomeSetup.bootConfirmationStatus(host);
        HomeSetup.confirmBoot(host); // must NOT confirm boot #5!
        check("r2_1a_boot_succeed_then_denied_shows_current_round_denied",
                HomeSetup.BOOT_STATE_LAUNCH_DENIED.equals(host.sysPrefs.getString("boot_req_state", ""))
                && statusBoot5Denied.contains("本轮已发起开机恢复请求，但系统拒绝后台启动")
                && !statusBoot5Denied.contains("已记录开机恢复到达")
                && confirmBoot5Denied.contains("历史轮次")
                && host.sysPrefs.getInt("setup_boot_confirmed", -1) == 4);

        // 1b. Succeed in boot #4, then boot #5 launches but NEVER arrives -> panel MUST show current-round pending!
        host = freshEnv();
        Settings.Global.ints.put("boot_count", 3);
        HomeSetup.begin(host);
        host.sysPrefs.edit().putBoolean("home_compat", true).putBoolean("boot_home", true).apply();
        setService(true, true);
        Settings.Global.ints.put("boot_count", 4);
        HomeSetup.bootIdOverride = "boot-r2-4b";
        SystemClock.elapsed = 12000L;
        new BootReceiver().onReceive(host, new Intent(Intent.ACTION_BOOT_COMPLETED));
        Intent boot4bLaunch = host.startedActivities.get(0);
        host.simulateOnPause();
        host.simulateOnNewIntent(boot4bLaunch);
        host.simulateOnResume(true);
        // Boot #5 launches, Activity never becomes visible
        Settings.Global.ints.put("boot_count", 5);
        HomeSetup.bootIdOverride = "boot-r2-5b";
        SystemClock.elapsed = 13000L;
        new BootReceiver().onReceive(host, new Intent(Intent.ACTION_BOOT_COMPLETED));
        String statusBoot5NoArrival = HomeSetup.bootStatus(host);
        HomeSetup.confirmBoot(host);
        check("r2_1b_boot_succeed_then_no_arrival_shows_current_round_pending",
                HomeSetup.BOOT_STATE_REQUESTED.equals(host.sysPrefs.getString("boot_req_state", ""))
                && statusBoot5NoArrival.contains("本轮已发起开机恢复请求，尚未观察到前台到达")
                && !statusBoot5NoArrival.contains("已记录开机恢复到达")
                && !host.sysPrefs.contains("setup_boot_confirmed"));

        // 1c. Duplicate BOOT_COMPLETED in the SAME boot round (both before arrival and after arrival)
        host = freshEnv();
        Settings.Global.ints.put("boot_count", 3);
        HomeSetup.begin(host);
        host.sysPrefs.edit().putBoolean("home_compat", true).putBoolean("boot_home", true).apply();
        setService(true, true);
        Settings.Global.ints.put("boot_count", 4);
        HomeSetup.bootIdOverride = "boot-r2-dup";
        SystemClock.elapsed = 12000L;
        BootReceiver dupReceiver = new BootReceiver();
        dupReceiver.onReceive(host, new Intent(Intent.ACTION_BOOT_COMPLETED));
        String firstToken = host.sysPrefs.getString("boot_req_token", "");
        // Second broadcast arrives in same boot #4 BEFORE Activity becomes visible
        SystemClock.elapsed = 12500L;
        dupReceiver.onReceive(host, new Intent(Intent.ACTION_BOOT_COMPLETED));
        check("r2_1c_duplicate_boot_broadcast_before_arrival_keeps_token_and_no_relaunch",
                host.startedActivities.size() == 1
                && firstToken.equals(host.sysPrefs.getString("boot_req_token", "")));
        // Original Activity launch now becomes visible and succeeds with firstToken
        Intent dupLaunch = host.startedActivities.get(0);
        host.simulateOnPause();
        host.simulateOnNewIntent(dupLaunch);
        host.simulateOnResume(true);
        check("r2_1c_first_launch_arrives_after_duplicate_broadcast",
                HomeSetup.BOOT_STATE_RECOVERY_ARRIVED.equals(host.sysPrefs.getString("boot_req_state", ""))
                && HomeSetup.bootStatus(host).contains("已记录开机恢复到达"));
        // Third broadcast in the same boot #4 AFTER arrival must NOT relaunch or downgrade!
        SystemClock.elapsed = 15000L;
        dupReceiver.onReceive(host, new Intent(Intent.ACTION_BOOT_COMPLETED));
        check("r2_1c_duplicate_boot_broadcast_after_arrival_never_downgrades",
                host.startedActivities.size() == 1
                && HomeSetup.BOOT_STATE_RECOVERY_ARRIVED.equals(host.sysPrefs.getString("boot_req_state", ""))
                && HomeSetup.BOOT_STATE_RECOVERY_ARRIVED.equals(host.sysPrefs.getString("setup_boot_state", ""))
                && HomeSetup.bootStatus(host).contains("已记录开机恢复到达"));

        // =====================================================================
        // Fix #2: Staged arrival in real HomeSetup.ArrivalGate (create-only interrupted, existing occluded, normal visible)
        // =====================================================================
        // 2a. Create-only interrupted ("仅创建便中断") -> onCreate stages intent, then destroyed/paused without focus -> NEVER recorded!
        host = freshEnv();
        Settings.Global.ints.put("boot_count", 3);
        HomeSetup.begin(host);
        host.sysPrefs.edit().putBoolean("home_compat", true).putBoolean("boot_home", true).apply();
        setService(true, true);
        Settings.Global.ints.put("boot_count", 4);
        HomeSetup.bootIdOverride = "boot-r2-vis1";
        SystemClock.elapsed = 10000L;
        new BootReceiver().onReceive(host, new Intent(Intent.ACTION_BOOT_COMPLETED));
        Intent visIntent1 = host.startedActivities.get(0);
        MainActivity createOnly = new MainActivity();
        createOnly.sysPrefs = host.sysPrefs;
        createOnly.homePrefs = host.homePrefs;
        createOnly.pm = host.pm;
        createOnly.defaultHome = new DefaultHomePanel(createOnly);
        createOnly.simulateOnCreate(visIntent1, false);
        check("r2_2a_onCreate_alone_does_not_record_arrival",
                createOnly.arrivalGate.hasPendingArrival()
                && !host.sysPrefs.contains("setup_boot_state")
                && HomeSetup.BOOT_STATE_REQUESTED.equals(host.sysPrefs.getString("boot_req_state", "")));
        createOnly.simulateOnDestroy();
        check("r2_2a_create_then_interrupted_never_records_arrival",
                !createOnly.arrivalGate.hasPendingArrival()
                && !host.sysPrefs.contains("setup_boot_state")
                && HomeSetup.bootStatus(host).contains("尚未观察到前台到达"));

        // 2b. Existing instance still occluded ("已有实例仍被遮挡") -> onNewIntent + onResume(focus=false) -> NOT recorded while occluded!
        host = freshEnv();
        Settings.Global.ints.put("boot_count", 3);
        HomeSetup.begin(host);
        host.sysPrefs.edit().putBoolean("home_compat", true).putBoolean("boot_home", true).apply();
        setService(true, true);
        Settings.Global.ints.put("boot_count", 4);
        HomeSetup.bootIdOverride = "boot-r2-vis2";
        SystemClock.elapsed = 10000L;
        new BootReceiver().onReceive(host, new Intent(Intent.ACTION_BOOT_COMPLETED));
        Intent visIntent2 = host.startedActivities.get(0);
        host.simulateOnPause(); // existing instance was paused behind system overlay
        host.simulateOnNewIntent(visIntent2);
        boolean consumedWhileOccluded = host.simulateOnResume(false);
        check("r2_2b_existing_instance_occluded_does_not_record_arrival",
                !consumedWhileOccluded
                && host.arrivalGate.hasPendingArrival()
                && !host.sysPrefs.contains("setup_boot_state")
                && HomeSetup.bootStatus(host).contains("尚未观察到前台到达"));
        // If paused while still occluded, staged arrival is dropped and NEVER recorded!
        host.simulateOnPause();
        check("r2_2b_occluded_then_paused_drops_staged_arrival",
                !host.arrivalGate.hasPendingArrival()
                && !host.sysPrefs.contains("setup_boot_state")
                && HomeSetup.BOOT_STATE_REQUESTED.equals(host.sysPrefs.getString("boot_req_state", "")));

        // 2c. Normal entry to visible desktop ("正常进入桌面") -> consumes ONCE on focus gain!
        host = freshEnv();
        Settings.Global.ints.put("boot_count", 3);
        HomeSetup.begin(host);
        host.sysPrefs.edit().putBoolean("home_compat", true).putBoolean("boot_home", true).apply();
        setService(true, true);
        Settings.Global.ints.put("boot_count", 4);
        HomeSetup.bootIdOverride = "boot-r2-vis3";
        SystemClock.elapsed = 10000L;
        new BootReceiver().onReceive(host, new Intent(Intent.ACTION_BOOT_COMPLETED));
        Intent visIntent3 = host.startedActivities.get(0);
        MainActivity normalHost = new MainActivity();
        normalHost.sysPrefs = host.sysPrefs;
        normalHost.homePrefs = host.homePrefs;
        normalHost.pm = host.pm;
        normalHost.defaultHome = new DefaultHomePanel(normalHost);
        Diagnostics.events.clear();
        normalHost.simulateOnCreate(visIntent3, false);
        boolean beforeFocus = normalHost.simulateOnResume(false);
        check("r2_2c_normal_entry_waits_for_window_focus",
                !beforeFocus && normalHost.arrivalGate.hasPendingArrival()
                && !host.sysPrefs.contains("setup_boot_state"));
        boolean onFocus = normalHost.simulateOnWindowFocusChanged(true);
        int arrivedEvents = 0;
        for (String e : Diagnostics.events) if ("BOOT_RECOVERY_ARRIVED".equals(e)) arrivedEvents++;
        boolean secondFocus = normalHost.simulateOnWindowFocusChanged(true);
        int arrivedEventsAfter = 0;
        for (String e : Diagnostics.events) if ("BOOT_RECOVERY_ARRIVED".equals(e)) arrivedEventsAfter++;
        check("r2_2c_normal_visible_entry_consumes_once",
                onFocus && !secondFocus
                && !normalHost.arrivalGate.hasPendingArrival()
                && HomeSetup.BOOT_STATE_RECOVERY_ARRIVED.equals(host.sysPrefs.getString("setup_boot_state", ""))
                && HomeSetup.bootStatus(host).contains("已记录开机恢复到达")
                && arrivedEvents == 1 && arrivedEventsAfter == 1);

        // =====================================================================
        // Fix #4: Cancel system HOME picker from paused state preserves pause, alias state, and prior evidence
        // =====================================================================
        host = freshEnv();
        Build.VERSION.SDK_INT = 29;
        host.roleAvailable = true;
        host.pm.alias = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        HomeSetup.pause(host, "用户主动暂停");
        // Seed prior verification evidence that must survive a cancelled picker attempt
        host.sysPrefs.edit()
                .putLong("setup_home_seen", 1700001111000L)
                .putString("setup_home_source", HomeSetup.SOURCE_COMPAT_KEY)
                .putInt("setup_boot_observed", 3)
                .putString("setup_boot_state", HomeSetup.BOOT_STATE_RECOVERY_ARRIVED)
                .putString("setup_boot_round", "bc:3")
                .apply();
        host.defaultHome.show();
        check("r2_4_paused_entry_shows_resume_button", host.dialogs.active().items[0].equals("继续一键设置"));
        host.dialogs.active().clickItem(0); // opens RoleManager picker
        check("r2_4_picker_enables_alias_only_while_pending",
                host.pm.alias == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                && host.startedReqCodes.get(0) == 812);
        host.defaultHome.onPickerResult(Activity.RESULT_CANCELED);
        host.defaultHome.resumed();
        check("r2_4_cancel_role_picker_preserves_pause_alias_and_evidence",
                HomeSetup.paused(host)
                && "用户主动暂停".equals(host.sysPrefs.getString("setup_pause_reason", ""))
                && host.pm.alias == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                && host.sysPrefs.getLong("setup_home_seen", 0L) == 1700001111000L
                && HomeSetup.SOURCE_COMPAT_KEY.equals(host.sysPrefs.getString("setup_home_source", ""))
                && host.sysPrefs.getInt("setup_boot_observed", -1) == 3
                && HomeSetup.BOOT_STATE_RECOVERY_ARRIVED.equals(host.sysPrefs.getString("setup_boot_state", "")));
        // Also test ACTION_HOME_SETTINGS cancelled via Home / closeMenus()
        host.roleAvailable = false;
        host.defaultHome.show();
        host.dialogs.active().clickItem(0);
        host.closeMenus();
        check("r2_4_cancel_settings_picker_via_closeMenus_preserves_all_state",
                HomeSetup.paused(host)
                && host.pm.alias == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                && host.sysPrefs.getLong("setup_home_seen", 0L) == 1700001111000L);
        // When user DOES accept Marco in picker after switching A -> B, it updates tracked home to B and unpauses!
        ResolveInfo thirdB = homeEntry("com.third.b", "com.third.b.Launcher", true, true, true, false);
        host.pm.homes.add(thirdB);
        host.pm.currentHome = "com.third.b/com.third.b.Launcher";
        host.defaultHome.show();
        host.dialogs.active().clickItem(0);
        host.pm.currentHome = "com.pilipala.tvhome/com.pilipala.tvhome.HomeActivity";
        host.defaultHome.resumed();
        check("r2_4_accepted_picker_commits_tracked_B_and_unpauses",
                !HomeSetup.paused(host)
                && SystemIntegration.homeIsOurs(host)
                && "com.third.b/com.third.b.Launcher".equals(SystemIntegration.trackedHome(host))
                && host.isPage("home_verification"));

        // =====================================================================
        // Fix #5: Accessibility settings exited without grant -> app AND TaskBridge wake OFF in BOTH orderings
        // =====================================================================
        // Order 1: Bridge previously had wake=true, service becomes unbound/ungranted, user enters Accessibility Settings,
        //          bridge sync runs FIRST (while consent pending), then consent finishes without grant.
        host = freshEnv();
        host.pm.actions.remove(Settings.ACTION_HOME_SETTINGS);
        SystemIntegration.rememberHome(host);
        host.sysPrefs.edit().putBoolean("home_compat", true).putBoolean("boot_home", true).apply();
        setService(true, true);
        drainBridgeSync();
        bridgeConfigs.clear();
        TaskBridgeClient.sync(host);
        drainBridgeSync();
        check("r2_5_precondition_bridge_wake_enabled",
                !bridgeConfigs.isEmpty() && bridgeConfigs.get(bridgeConfigs.size() - 1).optBoolean("wake"));
        // Service is unbound (or not yet enabled) so compatibility() opens Accessibility Settings with pending consent.
        setService(true, false);
        bridgeConfigs.clear();
        host.defaultHome.compatibility();
        host.dialogs.active().clickPositive();
        drainBridgeSync();
        check("r2_5_order1_opened_accessibility_settings_pending",
                host.defaultHome.compatibilityPending
                && host.sysPrefs.getBoolean("setup_accessibility_pending", false)
                && host.startedActivities.size() == 1
                && Settings.ACTION_ACCESSIBILITY_SETTINGS.equals(host.startedActivities.get(0).getAction()));
        // Order 1a: Bridge sync runs FIRST while consent is still pending -> MUST send wake=false even if serviceEnabled was still true!
        TaskBridgeClient.sync(host);
        drainBridgeSync();
        JSONObject duringPending = bridgeConfigs.isEmpty() ? null : bridgeConfigs.get(bridgeConfigs.size() - 1);
        check("r2_5_order1_bridge_sync_first_receives_wake_false",
                duringPending != null && !duringPending.optBoolean("wake"));
        // User exits Accessibility Settings WITHOUT granting the service -> resumed() finishes consent and syncs bridge!
        setService(false, false);
        bridgeConfigs.clear();
        host.defaultHome.resumed();
        drainBridgeSync();
        JSONObject order1Final = bridgeConfigs.isEmpty() ? null : bridgeConfigs.get(bridgeConfigs.size() - 1);
        check("r2_5_order1_final_app_and_bridge_wake_off",
                !host.sysPrefs.getBoolean("home_compat", true)
                && !host.sysPrefs.getBoolean("setup_accessibility_pending", false)
                && !TaskBridgeClient.shouldEnableWake(host)
                && order1Final != null && !order1Final.optBoolean("wake"));

        // Order 2: Permission result returns FIRST (finishCompatibilityConsent runs before MainActivity.onResume TaskBridgeClient.sync)
        host = freshEnv();
        host.pm.actions.remove(Settings.ACTION_HOME_SETTINGS);
        SystemIntegration.rememberHome(host);
        host.sysPrefs.edit().putBoolean("home_compat", true).putBoolean("boot_home", true).apply();
        setService(true, true);
        TaskBridgeClient.configure(host);
        setService(false, false);
        host.defaultHome.compatibility();
        host.dialogs.active().clickPositive();
        drainBridgeSync();
        bridgeConfigs.clear();
        // Simulate MainActivity.onResume ordering: finishCompatibilityConsent -> reconcile -> TaskBridgeClient.sync -> defaultHome.resumed
        host.simulateOnResume(true);
        drainBridgeSync();
        JSONObject order2Final = bridgeConfigs.isEmpty() ? null : bridgeConfigs.get(bridgeConfigs.size() - 1);
        check("r2_5_order2_consent_first_then_sync_final_bridge_wake_off",
                !host.sysPrefs.getBoolean("home_compat", true)
                && !host.sysPrefs.getBoolean("setup_accessibility_pending", false)
                && !TaskBridgeClient.shouldEnableWake(host)
                && order2Final != null && !order2Final.optBoolean("wake"));

        // =====================================================================
        // Fix #6: After A -> B switch, clicking "重新启用 Home 兼容" in compatReset tracks B while snapshot keeps A
        // =====================================================================
        // 6a. Authorized path: compatReset -> item 0 -> SystemIntegration.restoreCompatOnUserConfirm
        host = freshEnv();
        host.pm.homes.clear();
        ResolveInfo oemA = homeEntry("com.oem.a", "com.oem.a.Home", true, true, true, true);
        ResolveInfo launcherB6 = homeEntry("com.third.b", "com.third.b.Launcher", true, true, true, false);
        ResolveInfo marcoH6 = homeEntry("com.pilipala.tvhome", "com.pilipala.tvhome.HomeActivity", true, true, true, false);
        host.pm.homes.add(oemA);
        host.pm.homes.add(launcherB6);
        host.pm.homes.add(marcoH6);
        host.pm.currentHome = "com.oem.a/com.oem.a.Home";
        host.secureAuthorized = true;
        SystemIntegration.rememberHome(host);
        HomeSetupSnapshot.capture(host);
        // Simulate compat mode active, then user switched HOME A -> B and firmware stopped HomeKeyService
        host.sysPrefs.edit().putBoolean("home_compat", true).putBoolean("compat_seen_present", true)
                .putBoolean("compat_service_active", true).apply();
        host.pm.currentHome = "com.third.b/com.third.b.Launcher";
        setService(false, false);
        check("r2_6a_compat_reset_marked", HomeSetup.markCompatResetIfInterrupted(host));
        drainBridgeSync();
        bridgeConfigs.clear();
        host.defaultHome.compatReset();
        host.dialogs.active().clickItem(0); // "重新启用 Home 兼容"
        drainBridgeSync();
        JSONObject cfg6a = bridgeConfigs.isEmpty() ? null : bridgeConfigs.get(bridgeConfigs.size() - 1);
        check("r2_6a_compat_reset_reenable_tracks_B_syncs_bridge_and_preserves_snapshot_A",
                "com.third.b/com.third.b.Launcher".equals(SystemIntegration.trackedHome(host))
                && SystemIntegration.matchesTrackedHomePackage(host, "com.third.b")
                && !SystemIntegration.matchesTrackedHomePackage(host, "com.oem.a")
                && cfg6a != null && "com.third.b".equals(cfg6a.optString("home")) && cfg6a.optBoolean("wake")
                && "com.oem.a/com.oem.a.Home".equals(HomeSetupSnapshot.target(host)));

        // 6b. Unauthorized path: compatReset -> item 0 -> compatibility consent -> Continue -> tracks B & preserves snapshot A
        host = freshEnv();
        host.pm.homes.clear();
        host.pm.homes.add(oemA);
        host.pm.homes.add(launcherB6);
        host.pm.homes.add(marcoH6);
        host.pm.currentHome = "com.oem.a/com.oem.a.Home";
        host.secureAuthorized = false;
        SystemIntegration.rememberHome(host);
        HomeSetupSnapshot.capture(host);
        host.sysPrefs.edit().putBoolean("home_compat", true).putBoolean("compat_seen_present", true)
                .putBoolean("compat_service_active", true).apply();
        host.pm.currentHome = "com.third.b/com.third.b.Launcher";
        setService(false, false);
        HomeSetup.markCompatResetIfInterrupted(host);
        host.defaultHome.compatReset();
        host.dialogs.active().clickItem(0); // opens compatibility consent
        host.dialogs.active().clickPositive(); // Continue -> HomeSetup.requestCompatibility -> begin -> refreshTrackedHomeForSetup
        setService(true, true);
        drainBridgeSync();
        bridgeConfigs.clear();
        host.defaultHome.resumed();
        drainBridgeSync();
        JSONObject cfg6b = bridgeConfigs.isEmpty() ? null : bridgeConfigs.get(bridgeConfigs.size() - 1);
        check("r2_6b_compat_reset_unauth_reenable_tracks_B_and_preserves_snapshot_A",
                "com.third.b/com.third.b.Launcher".equals(SystemIntegration.trackedHome(host))
                && SystemIntegration.matchesTrackedHomePackage(host, "com.third.b")
                && !SystemIntegration.matchesTrackedHomePackage(host, "com.oem.a")
                && cfg6b != null && "com.third.b".equals(cfg6b.optString("home")) && cfg6b.optBoolean("wake")
                && "com.oem.a/com.oem.a.Home".equals(HomeSetupSnapshot.target(host)));

        // =====================================================================
        // Fix #7: Real DefaultHomePanel.restore() -> Real LocalHomeAuthorizer -> Real HomeSetupSnapshot
        //         when snapshot A is temporarily disabled (current HOME = B), then re-enabled -> retry restores A!
        // =====================================================================
        host = freshEnv();
        host.pm.homes.clear();
        ResolveInfo snapA = homeEntry("com.oem.a", "com.oem.a.Home", true, true, true, true);
        ResolveInfo snapB = homeEntry("com.third.b", "com.third.b.Launcher", true, true, true, false);
        ResolveInfo snapMarco = homeEntry("com.pilipala.tvhome", "com.pilipala.tvhome.HomeActivity", true, true, true, false);
        host.pm.homes.add(snapA);
        host.pm.homes.add(snapB);
        host.pm.homes.add(snapMarco);
        host.pm.currentHome = "com.oem.a/com.oem.a.Home";
        host.pm.alias = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        host.secureAuthorized = true;
        HomeSetupSnapshot.capture(host);
        // Switch current HOME to B and temporarily disable A
        host.pm.currentHome = "com.third.b/com.third.b.Launcher";
        host.pm.alias = PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        snapA.activityInfo.enabled = false;
        host.sysPrefs.edit().putBoolean("home_compat", true).putBoolean("boot_home", true).apply();
        setService(true, true);
        // Click actual restore entry while A is disabled
        host.dialogs.armResultLatch();
        host.defaultHome.restore();
        check("r2_7_restore_entry_opens_confirmation", host.isPage("home_auto_restore"));
        host.dialogs.active().clickPositive(); // "恢复" -> runAutomatic(true) -> real LocalHomeAuthorizer.restore()
        check("r2_7_disabled_A_completes_without_clearing_snapshot",
                host.dialogs.awaitResult()
                && host.isPage("home_local_result")
                && host.dialogs.active().message.contains("已停用或不可用")
                && host.dialogs.active().message.contains("恢复快照已保留")
                && host.dialogs.active().items[0].equals("重试恢复")
                && host.sysPrefs.getBoolean(HomeSetupSnapshot.PREFIX + "saved", false)
                && "com.oem.a/com.oem.a.Home".equals(HomeSetupSnapshot.recordedComponent(host))
                && LocalAdbWire.connects.get() == 0);
        // Now re-enable A and retry from the ACTUAL "重试恢复" button!
        snapA.activityInfo.enabled = true;
        host.dialogs.active().clickItem(0); // "重试恢复" -> opens home_auto_restore
        check("r2_7_retry_button_reopens_restore_confirmation", host.isPage("home_auto_restore"));
        host.dialogs.armResultLatch();
        host.dialogs.active().clickPositive(); // "恢复" -> real LocalHomeAuthorizer -> set-home-activity -> A
        check("r2_7_reenabled_A_restores_back_to_A_and_clears_snapshot",
                host.dialogs.awaitResult()
                && host.isPage("home_local_result")
                && host.dialogs.active().message.contains("已恢复本次设置前的桌面配置")
                && host.dialogs.active().items[0].equals("检查使用效果")
                && "com.oem.a/com.oem.a.Home".equals(SystemIntegration.currentHome(host))
                && !host.sysPrefs.getBoolean(HomeSetupSnapshot.PREFIX + "saved", false)
                && host.pm.alias == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                && !HomeSetup.serviceEnabled(host)
                && LocalAdbWire.commands.toString().contains("'com.oem.a/com.oem.a.Home'"));

        // =====================================================================
        // Fix #8: Double-press Home opening RecentApps must NOT pass short-press 90s test; single-press stays instant
        // =====================================================================
        // 8a. Native HOME double-press sequence with recent_enabled=true, double window=400ms
        host = freshEnv();
        host.homePrefs.edit().putBoolean("recent_enabled", true).putInt("recent_double_ms", 400).apply();
        HomeSetup.begin(host);
        HomeSetup.armHomeTest(host);
        // Tap 1 at t=11000: returns to desktop immediately (no navigation delay), test proof deferred!
        SystemClock.elapsed = 11000L;
        host.simulateOnPause();
        boolean tap1Recents = host.simulateOnNewIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        boolean tap1Visible = host.simulateOnResume(true);
        check("r2_8a_native_tap1_returns_immediately_without_premature_short_press_pass",
                !tap1Recents && tap1Visible && !host.sysPrefs.contains("setup_home_seen"));
        // Tap 2 at t=11250 (within 400ms): HomePressTracker returns true -> opens RecentApps -> cancels pending short press!
        SystemClock.elapsed = 11250L;
        host.simulateOnPause();
        boolean tap2Recents = host.simulateOnNewIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        host.simulateOnResume(true);
        // Advance well past double-press window and flush due Handler callbacks
        SystemClock.elapsed = 12000L;
        Handler.runDue();
        String statusAfterDouble = HomeSetup.homeReturnStatus(host);
        check("r2_8a_native_double_press_never_passes_short_press_test",
                tap2Recents && !host.sysPrefs.contains("setup_home_seen") && !statusAfterDouble.contains("已回到 Marco"));
        // Now single press at t=15000 (no Tap 2) -> commits after 400ms with SYSTEM_HOME!
        SystemClock.elapsed = 15000L;
        host.simulateOnPause();
        boolean singleRecents = host.simulateOnNewIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        host.simulateOnResume(true);
        SystemClock.elapsed = 15200L;
        Handler.runDue();
        boolean committedEarly = host.sysPrefs.contains("setup_home_seen");
        SystemClock.elapsed = 15500L;
        Handler.runDue();
        check("r2_8a_native_single_press_commits_after_double_window",
                !singleRecents && !committedEarly
                && host.sysPrefs.contains("setup_home_seen")
                && HomeSetup.SOURCE_SYSTEM_HOME.equals(host.sysPrefs.getString("setup_home_source", "")));

        // 8b. Compat-key two-tap sequence (HomeKeyService token on Tap 1, recent_apps=true on Tap 2)
        host = freshEnv();
        host.homePrefs.edit().putBoolean("recent_enabled", true).putInt("recent_double_ms", 400).apply();
        HomeSetup.begin(host);
        HomeSetup.armHomeTest(host);
        HomePressTracker compatTracker = new HomePressTracker();
        SystemClock.elapsed = 20000L;
        boolean compatTap1Twice = compatTracker.press(SystemClock.elapsed, 400);
        String compatToken1 = HomeSetup.issueHomeEvent(host, HomeSetup.SOURCE_COMPAT_KEY);
        host.simulateOnPause();
        host.simulateOnNewIntent(SystemIntegration.homeIntent(host).putExtra("home_press_handled", true)
                .putExtra("recent_apps", compatTap1Twice).putExtra(HomeSetup.EXTRA_EVENT_TOKEN, compatToken1));
        host.simulateOnResume(true);
        // Tap 2 at t=20250 opens RecentApps
        SystemClock.elapsed = 20250L;
        boolean compatTap2Twice = compatTracker.press(SystemClock.elapsed, 400);
        HomeSetup.cancelPendingShortPress();
        HomeSetup.clearPendingHomeEvent(host);
        host.simulateOnPause();
        host.simulateOnNewIntent(SystemIntegration.homeIntent(host).putExtra("home_press_handled", true)
                .putExtra("recent_apps", compatTap2Twice));
        host.simulateOnResume(true);
        SystemClock.elapsed = 21000L;
        Handler.runDue();
        check("r2_8b_compat_double_press_never_passes_short_press_test",
                !compatTap1Twice && compatTap2Twice && !host.sysPrefs.contains("setup_home_seen"));
        // Single compat press with recent_enabled=false commits IMMEDIATELY (0ms delay)!
        host.homePrefs.edit().putBoolean("recent_enabled", false).apply();
        SystemClock.elapsed = 22000L;
        String compatTokenSingle = HomeSetup.issueHomeEvent(host, HomeSetup.SOURCE_COMPAT_KEY);
        host.simulateOnPause();
        host.simulateOnNewIntent(SystemIntegration.homeIntent(host).putExtra("home_press_handled", true)
                .putExtra(HomeSetup.EXTRA_EVENT_TOKEN, compatTokenSingle));
        host.simulateOnResume(true);
        check("r2_8b_recent_disabled_single_press_commits_immediately",
                host.sysPrefs.contains("setup_home_seen")
                && HomeSetup.SOURCE_COMPAT_KEY.equals(host.sysPrefs.getString("setup_home_source", "")));

        // =====================================================================
        // Fix #9: Android 6 (API 23) missing boot_count — (a) reliable boot_id correlation, (b) explicit manual observation
        // =====================================================================
        // 9a. API 23 with reliable boot_id ("api23-boot-1" -> "api23-boot-2") correlates native HOME arrival!
        host = freshEnv();
        Build.VERSION.SDK_INT = 23;
        Settings.Global.ints.put("boot_count", -1);
        HomeSetup.bootIdOverride = "api23-boot-1";
        host.pm.currentHome = "com.pilipala.tvhome/com.pilipala.tvhome.HomeActivity";
        HomeSetup.begin(host);
        HomeSetup.acceptNative(host);
        // Same boot_id must NOT record boot arrival
        SystemClock.elapsed = 15000L;
        HomeSetup.observeHomeIntent(host, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        check("r2_9a_api23_same_boot_id_does_not_record_boot", !host.sysPrefs.contains("setup_boot_state"));
        // Reboot with new boot_id
        HomeSetup.bootIdOverride = "api23-boot-2";
        SystemClock.elapsed = 15000L;
        HomeSetup.observeHomeIntent(host, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        check("r2_9a_api23_new_boot_id_correlates_native_home_arrival",
                HomeSetup.canIdentifyBootAutomatically(host)
                && HomeSetup.BOOT_STATE_NATIVE_UNCONFIRMED.equals(host.sysPrefs.getString("setup_boot_state", ""))
                && HomeSetup.bootStatus(host).contains("观察到开机后原生 HOME 到达（来源待确认）"));
        // Verification page button confirms via confirmBoot
        host.defaultHome.verification();
        check("r2_9a_api23_with_boot_id_uses_normal_confirm_button",
                host.dialogs.active().items[1].equals("我已重启，确认开机效果"));
        host.dialogs.active().clickItem(1);
        check("r2_9a_api23_with_boot_id_confirms_boot",
                host.sysPrefs.contains("setup_boot_confirmed")
                && HomeSetup.BOOT_STATE_NATIVE_UNCONFIRMED.equals(host.sysPrefs.getString("setup_boot_confirmed_source", "")));

        // 9b. API 23 missing BOTH boot_count AND boot_id -> explicit human-observation button (never stuck, never fakes native/auto!)
        host = freshEnv();
        Build.VERSION.SDK_INT = 23;
        Settings.Global.ints.put("boot_count", -1);
        HomeSetup.bootIdOverride = "";
        HomeSetup.begin(host);
        host.sysPrefs.edit().putBoolean("home_compat", true).putBoolean("boot_home", true).apply();
        setService(true, true);
        check("r2_9b_api23_no_boot_id_explains_manual_observation_instead_of_stuck_wait",
                !HomeSetup.canIdentifyBootAutomatically(host)
                && HomeSetup.bootStatus(host).contains("缺少启动计数与启动标识")
                && HomeSetup.bootStatus(host).contains("手动记录人工观察"));
        // Automatic confirmBoot still refuses when nothing was automatically observed!
        HomeSetup.confirmBoot(host);
        check("r2_9b_confirmBoot_still_refuses_without_automatic_observation",
                !host.sysPrefs.contains("setup_boot_confirmed"));
        // Actual verification panel button offers explicit manual observation and labels it clearly!
        host.defaultHome.verification();
        check("r2_9b_verification_button_clearly_labeled_manual_observation",
                host.dialogs.active().items[1].equals("记录人工重启观察（系统无启动标识）"));
        host.dialogs.active().clickItem(1);
        String manualSummary = HomeSetup.summary(host);
        check("r2_9b_manual_observation_recorded_without_faking_auto_or_native",
                HomeSetup.BOOT_CONFIRM_MANUAL_ONLY.equals(host.sysPrefs.getString("setup_boot_confirmed_source", ""))
                && !host.sysPrefs.contains("setup_boot_state")
                && !host.sysPrefs.contains("setup_boot_observed")
                && !SystemIntegration.homeIsOurs(host)
                && manualSummary.contains("人工观察记录")
                && manualSummary.contains("未经自动关联"));
    }

    static void testRound3Counterexamples() throws Exception {
        drainBridgeSync();
        final List<JSONObject> bridgeConfigs = Collections.synchronizedList(new ArrayList<>());
        IBinder r3Bridge = new IBinder() {
            @Override public boolean isBinderAlive() { return true; }
            @Override public void linkToDeath(DeathRecipient r, int flags) { }
            @Override public boolean transact(int code, Parcel data, Parcel reply, int flags) {
                data.readInterfaceToken();
                try {
                    JSONObject req = new JSONObject(data.readString());
                    if ("configure".equals(req.optString("action"))) bridgeConfigs.add(req);
                    reply.writeNoException();
                    reply.writeString(new JSONObject().put("ok", true).put("wake", req.optBoolean("wake")).toString());
                    return true;
                } catch (Exception e) { throw new RuntimeException(e); }
            }
        };
        TaskBridgeClient.attach(r3Bridge);

        // =====================================================================
        // Round-3 Fix #1: System picker selects Marco -> (a) normal return, (b) direct remote Home press,
        //                 (c) firmware directly launches new HOME launcher!
        // =====================================================================
        // 1a. Normal return from RoleManager/Settings picker (paused, old tracked A, current HOME B -> Marco)
        MainActivity host = freshEnv();
        host.pm.homes.clear();
        host.pm.homes.add(homeEntry("com.oem.a", "com.oem.a.Home", true, true, true, true));
        host.pm.homes.add(homeEntry("com.third.b", "com.third.b.Launcher", true, true, true, false));
        host.pm.homes.add(homeEntry("com.pilipala.tvhome", "com.pilipala.tvhome.HomeActivity", true, true, true, false));
        host.pm.currentHome = "com.oem.a/com.oem.a.Home";
        host.pm.alias = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        SystemIntegration.rememberHome(host); // old tracked A
        HomeSetup.pause(host, "用户已暂停");
        host.pm.currentHome = "com.third.b/com.third.b.Launcher"; // system switched A -> B
        host.defaultHome.show();
        host.dialogs.active().clickItem(0); // "继续一键设置" -> opens picker
        host.pm.currentHome = "com.pilipala.tvhome/com.pilipala.tvhome.HomeActivity";
        host.simulateOnResume(true); // normal return
        check("r3_1a_picker_normal_return_commits_native_unpauses_and_tracks_B",
                !HomeSetup.paused(host)
                && SystemIntegration.homeIsOurs(host)
                && host.sysPrefs.getBoolean("setup_native_seen", false)
                && host.sysPrefs.getBoolean("boot_home", false)
                && "com.third.b/com.third.b.Launcher".equals(SystemIntegration.trackedHome(host))
                && !HomeSetup.hasPendingPicker(host)
                && host.isPage("home_verification"));

        // 1b. Direct remote Home press right after picking Marco (onNewIntent -> closeMenus -> cancelPending -> onResume)
        host = freshEnv();
        host.pm.homes.clear();
        host.pm.homes.add(homeEntry("com.oem.a", "com.oem.a.Home", true, true, true, true));
        host.pm.homes.add(homeEntry("com.third.b", "com.third.b.Launcher", true, true, true, false));
        host.pm.homes.add(homeEntry("com.pilipala.tvhome", "com.pilipala.tvhome.HomeActivity", true, true, true, false));
        host.pm.currentHome = "com.oem.a/com.oem.a.Home";
        host.pm.alias = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        SystemIntegration.rememberHome(host); // old tracked A
        HomeSetup.pause(host, "用户已暂停");
        host.pm.currentHome = "com.third.b/com.third.b.Launcher";
        host.defaultHome.show();
        host.dialogs.active().clickItem(0); // opens picker
        // User selects Marco in picker and presses remote Home directly!
        host.pm.currentHome = "com.pilipala.tvhome/com.pilipala.tvhome.HomeActivity";
        host.simulateOnPause();
        host.simulateOnNewIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)); // calls closeMenus -> cancelPending!
        host.simulateOnResume(true);
        drainBridgeSync();
        check("r3_1b_picker_direct_home_key_commits_native_unpauses_and_tracks_B",
                !HomeSetup.paused(host)
                && SystemIntegration.homeIsOurs(host)
                && host.sysPrefs.getBoolean("setup_native_seen", false)
                && host.sysPrefs.getBoolean("boot_home", false)
                && HomeSetup.route(host) == HomeSetupPolicy.Route.NATIVE_READY
                && "com.third.b/com.third.b.Launcher".equals(SystemIntegration.trackedHome(host))
                && !HomeSetup.hasPendingPicker(host));

        // 1c. Firmware directly launches new HOME launcher on a fresh MainActivity instance (cold launch / recreate)!
        host = freshEnv();
        host.pm.homes.clear();
        host.pm.homes.add(homeEntry("com.oem.a", "com.oem.a.Home", true, true, true, true));
        host.pm.homes.add(homeEntry("com.third.b", "com.third.b.Launcher", true, true, true, false));
        host.pm.homes.add(homeEntry("com.pilipala.tvhome", "com.pilipala.tvhome.HomeActivity", true, true, true, false));
        host.pm.currentHome = "com.oem.a/com.oem.a.Home";
        host.pm.alias = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        SystemIntegration.rememberHome(host);
        HomeSetup.pause(host, "用户已暂停");
        host.pm.currentHome = "com.third.b/com.third.b.Launcher";
        host.defaultHome.show();
        host.dialogs.active().clickItem(0);
        // Firmware destroys old instance and directly launches new MainActivity with CATEGORY_HOME!
        host.simulateOnPause();
        host.simulateOnDestroy();
        host.pm.currentHome = "com.pilipala.tvhome/com.pilipala.tvhome.HomeActivity";
        MainActivity firmwareLaunched = new MainActivity();
        firmwareLaunched.sysPrefs = host.sysPrefs;
        firmwareLaunched.homePrefs = host.homePrefs;
        firmwareLaunched.pm = host.pm;
        firmwareLaunched.defaultHome = new DefaultHomePanel(firmwareLaunched);
        firmwareLaunched.simulateOnCreate(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), false);
        firmwareLaunched.simulateOnResume(true);
        check("r3_1c_firmware_direct_launch_commits_native_unpauses_and_tracks_B",
                !HomeSetup.paused(firmwareLaunched)
                && SystemIntegration.homeIsOurs(firmwareLaunched)
                && firmwareLaunched.sysPrefs.getBoolean("setup_native_seen", false)
                && firmwareLaunched.sysPrefs.getBoolean("boot_home", false)
                && "com.third.b/com.third.b.Launcher".equals(SystemIntegration.trackedHome(firmwareLaunched))
                && !HomeSetup.hasPendingPicker(firmwareLaunched));

        // =====================================================================
        // Round-3 Fix #2: Paused user enters Accessibility Settings -> (a) cancel without grant preserves pause,
        //                 alias, and old verification records while keeping bridge wake OFF; (b) grant commits!
        // =====================================================================
        host = freshEnv();
        host.pm.homes.clear();
        host.pm.homes.add(homeEntry("com.oem.a", "com.oem.a.Home", true, true, true, true));
        host.pm.homes.add(homeEntry("com.third.b", "com.third.b.Launcher", true, true, true, false));
        host.pm.homes.add(homeEntry("com.pilipala.tvhome", "com.pilipala.tvhome.HomeActivity", true, true, true, false));
        host.pm.currentHome = "com.oem.a/com.oem.a.Home";
        host.pm.actions.remove(Settings.ACTION_HOME_SETTINGS); // route -> ACCESSIBILITY
        host.pm.alias = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        SystemIntegration.rememberHome(host);
        HomeSetup.pause(host, "暂停态原原因");
        host.sysPrefs.edit()
                .putLong("setup_home_seen", 1700002222000L)
                .putString("setup_home_source", HomeSetup.SOURCE_COMPAT_KEY)
                .putInt("setup_boot_observed", 3)
                .putString("setup_boot_state", HomeSetup.BOOT_STATE_RECOVERY_ARRIVED)
                .putString("setup_boot_round", "bc:3")
                .apply();
        host.pm.currentHome = "com.third.b/com.third.b.Launcher";
        drainBridgeSync();
        bridgeConfigs.clear();
        host.defaultHome.show();
        check("r3_2a_paused_opens_accessibility_route", host.dialogs.active().items[0].equals("继续一键设置"));
        host.dialogs.active().clickItem(0); // opens home_compat_consent
        host.dialogs.active().clickPositive(); // "继续" -> opens Accessibility Settings (pending, deferred!)
        drainBridgeSync();
        // While user is still in Accessibility Settings: paused, alias, and old records MUST NOT have been mutated yet!
        check("r3_2a_while_in_accessibility_settings_state_is_not_prematurely_mutated",
                HomeSetup.paused(host)
                && host.pm.alias == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                && host.sysPrefs.getLong("setup_home_seen", 0L) == 1700002222000L
                && !bridgeConfigs.isEmpty() && !bridgeConfigs.get(bridgeConfigs.size() - 1).optBoolean("wake"));
        // User exits Accessibility Settings WITHOUT granting permission!
        setService(false, false);
        bridgeConfigs.clear();
        host.simulateOnResume(true);
        drainBridgeSync();
        JSONObject r3_2aCfg = bridgeConfigs.isEmpty() ? null : bridgeConfigs.get(bridgeConfigs.size() - 1);
        check("r3_2a_accessibility_cancel_from_paused_preserves_pause_alias_evidence_and_bridge_off",
                HomeSetup.paused(host)
                && "暂停态原原因".equals(host.sysPrefs.getString("setup_pause_reason", ""))
                && host.pm.alias == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                && host.sysPrefs.getLong("setup_home_seen", 0L) == 1700002222000L
                && HomeSetup.SOURCE_COMPAT_KEY.equals(host.sysPrefs.getString("setup_home_source", ""))
                && host.sysPrefs.getInt("setup_boot_observed", -1) == 3
                && HomeSetup.BOOT_STATE_RECOVERY_ARRIVED.equals(host.sysPrefs.getString("setup_boot_state", ""))
                && !host.sysPrefs.getBoolean("home_compat", true)
                && !host.sysPrefs.getBoolean("boot_home", true)
                && !host.sysPrefs.getBoolean("setup_accessibility_pending", false)
                && r3_2aCfg != null && !r3_2aCfg.optBoolean("wake"));
        // Now user re-enters Accessibility Settings and ACTUALLY grants the service -> commits unpause, tracked B, alias, compat!
        host.defaultHome.show();
        host.dialogs.active().clickItem(0);
        host.dialogs.active().clickPositive();
        setService(true, true);
        bridgeConfigs.clear();
        host.simulateOnResume(true);
        drainBridgeSync();
        JSONObject r3_2bCfg = bridgeConfigs.isEmpty() ? null : bridgeConfigs.get(bridgeConfigs.size() - 1);
        check("r3_2b_accessibility_grant_commits_unpause_compat_tracked_B_and_bridge_wake_on",
                !HomeSetup.paused(host)
                && host.sysPrefs.getBoolean("home_compat", false)
                && host.sysPrefs.getBoolean("boot_home", false)
                && host.pm.alias == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                && "com.third.b/com.third.b.Launcher".equals(SystemIntegration.trackedHome(host))
                && r3_2bCfg != null && r3_2bCfg.optBoolean("wake") && "com.third.b".equals(r3_2bCfg.optString("home"))
                && host.isPage("home_verification"));

        // =====================================================================
        // Round-3 Fix #3: Process killed/recreated while system picker is open ->
        //                 (a) cancel restores disabled HOME alias & pause/evidence; (b) select commits Marco + B!
        // =====================================================================
        // 3a. RoleManager picker -> process killed (onDestroy) -> user cancels -> recreated MainActivity restores DISABLED alias!
        host = freshEnv();
        Build.VERSION.SDK_INT = 29;
        host.roleAvailable = true;
        host.pm.alias = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        HomeSetup.pause(host, "低内存测试暂停");
        host.sysPrefs.edit().putLong("setup_home_seen", 1700004444000L)
                .putString("setup_home_source", HomeSetup.SOURCE_SYSTEM_HOME).apply();
        host.defaultHome.show();
        host.dialogs.active().clickItem(0); // enables alias temporarily + persists picker_pending
        host.simulateOnPause();
        host.simulateOnDestroy(); // OS kills background Activity while RoleManager is still on screen!
        check("r3_3a_background_destroy_keeps_alias_and_persisted_picker_while_picker_open",
                host.pm.alias == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                && HomeSetup.hasPendingPicker(host));
        // User cancels RoleManager; Android recreates MainActivity, delivers RESULT_CANCELED, and resumes!
        MainActivity recreatedCancel = new MainActivity();
        recreatedCancel.sysPrefs = host.sysPrefs;
        recreatedCancel.homePrefs = host.homePrefs;
        recreatedCancel.pm = host.pm;
        recreatedCancel.defaultHome = new DefaultHomePanel(recreatedCancel);
        recreatedCancel.simulateOnCreate(new Intent(recreatedCancel, MainActivity.class), true);
        recreatedCancel.defaultHome.onPickerResult(Activity.RESULT_CANCELED);
        recreatedCancel.simulateOnResume(true);
        check("r3_3a_recreated_cancel_restores_disabled_alias_pause_and_evidence",
                recreatedCancel.pm.alias == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                && !HomeSetup.hasPendingPicker(recreatedCancel)
                && HomeSetup.paused(recreatedCancel)
                && "低内存测试暂停".equals(recreatedCancel.sysPrefs.getString("setup_pause_reason", ""))
                && recreatedCancel.sysPrefs.getLong("setup_home_seen", 0L) == 1700004444000L
                && recreatedCancel.isPage("home_picker_next")
                && recreatedCancel.dialogs.active().message.contains("取消"));

        // 3b. ACTION_HOME_SETTINGS (no onActivityResult) -> process killed -> user cancels -> recreated MainActivity still restores alias!
        host = freshEnv();
        host.roleAvailable = false;
        host.pm.alias = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        HomeSetup.pause(host, "设置页暂停");
        host.defaultHome.show();
        host.dialogs.active().clickItem(0); // opens ACTION_HOME_SETTINGS
        host.simulateOnPause();
        host.simulateOnDestroy();
        MainActivity recreatedSettingsCancel = new MainActivity();
        recreatedSettingsCancel.sysPrefs = host.sysPrefs;
        recreatedSettingsCancel.homePrefs = host.homePrefs;
        recreatedSettingsCancel.pm = host.pm;
        recreatedSettingsCancel.defaultHome = new DefaultHomePanel(recreatedSettingsCancel);
        recreatedSettingsCancel.simulateOnCreate(new Intent(recreatedSettingsCancel, MainActivity.class), true);
        recreatedSettingsCancel.simulateOnResume(true);
        check("r3_3b_recreated_settings_picker_cancel_restores_disabled_alias_and_pause",
                recreatedSettingsCancel.pm.alias == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                && !HomeSetup.hasPendingPicker(recreatedSettingsCancel)
                && HomeSetup.paused(recreatedSettingsCancel));

        // =====================================================================
        // Round-3 Fix #4: Android 6 configured 0.21.2 user upgrade (missing setup_boot_armed_boot_id)
        // =====================================================================
        // 4a. Readable boot_id: first boot establishes baseline only; second boot correlates native HOME and confirms!
        host = freshEnv();
        Build.VERSION.SDK_INT = 23;
        Settings.Global.ints.put("boot_count", -1);
        HomeSetup.bootIdOverride = "api23-0212-boot-1";
        host.pm.currentHome = "com.pilipala.tvhome/com.pilipala.tvhome.HomeActivity";
        // Simulate legacy 0.21.2 persisted preferences (NO setup_boot_armed_boot_id!)
        host.sysPrefs.edit()
                .putBoolean("setup_requested", true)
                .putBoolean("setup_native_seen", true)
                .putBoolean("boot_home", true)
                .putInt("setup_boot_armed", -1)
                .putLong("setup_home_seen", 1700003333000L)
                .apply();
        // First boot after upgrade: reconcile + native HOME arrival establishes baseline ONLY, does NOT falsely confirm!
        SystemClock.elapsed = 15000L;
        HomeSetup.reconcile(host);
        HomeSetup.observeHomeIntent(host, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        HomeSetup.confirmBoot(host);
        check("r3_4a_api23_upgrade_first_boot_sets_baseline_without_false_confirm_and_preserves_legacy_records",
                "api23-0212-boot-1".equals(host.sysPrefs.getString("setup_boot_armed_boot_id", ""))
                && !host.sysPrefs.contains("setup_boot_state")
                && !host.sysPrefs.contains("setup_boot_confirmed")
                && host.sysPrefs.getLong("setup_home_seen", 0L) == 1700003333000L
                && host.sysPrefs.getBoolean("setup_native_seen", false));
        // Second boot after upgrade (new boot_id): correlates native HOME arrival and confirms cleanly!
        HomeSetup.bootIdOverride = "api23-0212-boot-2";
        SystemClock.elapsed = 15000L;
        HomeSetup.observeHomeIntent(host, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        host.defaultHome.verification();
        host.dialogs.active().clickItem(1); // "我已重启，确认开机效果"
        check("r3_4a_api23_upgrade_next_boot_correlates_and_confirms_cleanly",
                HomeSetup.BOOT_STATE_NATIVE_UNCONFIRMED.equals(host.sysPrefs.getString("setup_boot_state", ""))
                && host.sysPrefs.contains("setup_boot_confirmed")
                && HomeSetup.BOOT_STATE_NATIVE_UNCONFIRMED.equals(host.sysPrefs.getString("setup_boot_confirmed_source", ""))
                && host.sysPrefs.getLong("setup_home_seen", 0L) == 1700003333000L);

        // 4b. Unreadable boot_id on upgraded 0.21.2 Android 6 user: explicit manual observation preserves legacy records!
        host = freshEnv();
        Build.VERSION.SDK_INT = 23;
        Settings.Global.ints.put("boot_count", -1);
        HomeSetup.bootIdOverride = "";
        host.pm.currentHome = "com.pilipala.tvhome/com.pilipala.tvhome.HomeActivity";
        host.sysPrefs.edit()
                .putBoolean("setup_requested", true)
                .putBoolean("setup_native_seen", true)
                .putBoolean("boot_home", true)
                .putInt("setup_boot_armed", -1)
                .putLong("setup_home_seen", 1700003333000L)
                .apply();
        HomeSetup.reconcile(host);
        host.defaultHome.verification();
        check("r3_4b_api23_upgrade_no_boot_id_offers_manual_observation",
                host.dialogs.active().items[1].equals("记录人工重启观察（系统无启动标识）"));
        host.dialogs.active().clickItem(1);
        check("r3_4b_api23_upgrade_manual_observation_preserves_legacy_settings_and_records",
                HomeSetup.BOOT_CONFIRM_MANUAL_ONLY.equals(host.sysPrefs.getString("setup_boot_confirmed_source", ""))
                && host.sysPrefs.getLong("setup_home_seen", 0L) == 1700003333000L
                && host.sysPrefs.getBoolean("setup_native_seen", false)
                && !host.sysPrefs.contains("setup_boot_state"));

        // =====================================================================
        // Round-3 Fix #5: Same-round duplicate BOOT_COMPLETED after launch-denied OR request-expired
        //                 must NOT relaunch, overwrite round state, or cancel active 90s Home test!
        // =====================================================================
        // 5a. First boot broadcast launch denied -> user arms 90s Home test -> duplicate broadcast in same boot #4
        host = freshEnv();
        Settings.Global.ints.put("boot_count", 3);
        HomeSetup.begin(host);
        host.sysPrefs.edit().putBoolean("home_compat", true).putBoolean("boot_home", true).apply();
        setService(true, true);
        Settings.Global.ints.put("boot_count", 4);
        HomeSetup.bootIdOverride = "boot-r3-dup-denied";
        SystemClock.elapsed = 12000L;
        host.startActivityThrows = true;
        BootReceiver r3Receiver = new BootReceiver();
        r3Receiver.onReceive(host, new Intent(Intent.ACTION_BOOT_COMPLETED));
        check("r3_5a_initial_boot_launch_denied",
                HomeSetup.BOOT_STATE_LAUNCH_DENIED.equals(host.sysPrefs.getString("boot_req_state", ""))
                && host.startedActivities.isEmpty());
        // User opens Marco and starts the 90s Home test; later a duplicate BOOT_COMPLETED arrives (even if launches wouldn't throw now)
        SystemClock.elapsed = 20000L;
        HomeSetup.armHomeTest(host);
        host.startActivityThrows = false;
        SystemClock.elapsed = 25000L;
        r3Receiver.onReceive(host, new Intent(Intent.ACTION_BOOT_COMPLETED));
        String directRetryDenied = HomeSetup.tryBeginBootRecoveryRequest(host);
        check("r3_5a_duplicate_after_denied_does_not_relaunch_or_cancel_home_test",
                directRetryDenied == null
                && host.startedActivities.isEmpty()
                && HomeSetup.BOOT_STATE_LAUNCH_DENIED.equals(host.sysPrefs.getString("boot_req_state", ""))
                && host.sysPrefs.contains("setup_home_deadline"));
        // Verify the armed 90s Home test STILL passes when user presses Home!
        SystemClock.elapsed = 30000L;
        String t5a = HomeSetup.issueHomeEvent(host, HomeSetup.SOURCE_COMPAT_KEY);
        HomeSetup.observeHomeIntent(host, SystemIntegration.homeIntent(host).putExtra(HomeSetup.EXTRA_EVENT_TOKEN, t5a));
        check("r3_5a_armed_home_test_still_passes_after_duplicate_broadcast",
                host.sysPrefs.contains("setup_home_seen")
                && HomeSetup.SOURCE_COMPAT_KEY.equals(host.sysPrefs.getString("setup_home_source", "")));

        // 5b. First boot request expires (>60s without arrival) -> user arms 90s Home test -> duplicate broadcast in same boot #4
        host = freshEnv();
        Settings.Global.ints.put("boot_count", 3);
        HomeSetup.begin(host);
        host.sysPrefs.edit().putBoolean("home_compat", true).putBoolean("boot_home", true).apply();
        setService(true, true);
        Settings.Global.ints.put("boot_count", 4);
        HomeSetup.bootIdOverride = "boot-r3-dup-expired";
        SystemClock.elapsed = 10000L;
        BootReceiver expReceiver = new BootReceiver();
        expReceiver.onReceive(host, new Intent(Intent.ACTION_BOOT_COMPLETED));
        String expiredToken = host.sysPrefs.getString("boot_req_token", "");
        // Advance past 60s request TTL without Activity arrival, then user starts 90s Home test
        SystemClock.elapsed = 80000L;
        HomeSetup.armHomeTest(host);
        SystemClock.elapsed = 85000L;
        expReceiver.onReceive(host, new Intent(Intent.ACTION_BOOT_COMPLETED));
        String directRetryExpired = HomeSetup.tryBeginBootRecoveryRequest(host);
        check("r3_5b_duplicate_after_expired_does_not_relaunch_or_cancel_home_test",
                directRetryExpired == null
                && host.startedActivities.size() == 1
                && expiredToken.equals(host.sysPrefs.getString("boot_req_token", ""))
                && HomeSetup.BOOT_STATE_REQUESTED.equals(host.sysPrefs.getString("boot_req_state", ""))
                && host.sysPrefs.contains("setup_home_deadline"));
        // Next real reboot (boot #5) DOES invalidate stale boot #4 Home test and launches boot #5 recovery!
        Settings.Global.ints.put("boot_count", 5);
        HomeSetup.bootIdOverride = "boot-r3-dup-boot5";
        SystemClock.elapsed = 11000L;
        expReceiver.onReceive(host, new Intent(Intent.ACTION_BOOT_COMPLETED));
        check("r3_5b_new_boot_round_invalidates_prior_home_test_and_launches_once",
                host.startedActivities.size() == 2
                && !host.sysPrefs.contains("setup_home_deadline")
                && host.sysPrefs.getInt("boot_req_boot", -1) == 5);
    }

    static void testRound4EdgeCases() throws Exception {
        drainBridgeSync();
        final List<JSONObject> bridgeConfigs = Collections.synchronizedList(new ArrayList<>());
        IBinder r4Bridge = new IBinder() {
            @Override public boolean isBinderAlive() { return true; }
            @Override public void linkToDeath(DeathRecipient r, int flags) { }
            @Override public boolean transact(int code, Parcel data, Parcel reply, int flags) {
                data.readInterfaceToken();
                try {
                    JSONObject req = new JSONObject(data.readString());
                    if ("configure".equals(req.optString("action"))) bridgeConfigs.add(req);
                    reply.writeNoException();
                    reply.writeString(new JSONObject().put("ok", true).put("wake", req.optBoolean("wake")).toString());
                    return true;
                } catch (Exception e) { throw new RuntimeException(e); }
            }
        };
        TaskBridgeClient.attach(r4Bridge);

        // =====================================================================
        // Round-4 Fix #1: Marco is ALREADY default HOME, user paused, opens system picker from "更多设置方式"
        //                 and cancels -> MUST stay paused, keep boot_home=false, and preserve old records!
        // =====================================================================
        // 1a. RoleManager cancel when Marco was already HOME before opening picker
        MainActivity host = freshEnv();
        Build.VERSION.SDK_INT = 29;
        host.roleAvailable = true;
        host.pm.currentHome = "com.pilipala.tvhome/com.pilipala.tvhome.HomeActivity";
        host.pm.alias = PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        HomeSetup.pause(host, "用户主动暂停");
        host.sysPrefs.edit()
                .putLong("setup_home_seen", 1700005555000L)
                .putString("setup_home_source", HomeSetup.SOURCE_SYSTEM_HOME)
                .putInt("setup_boot_observed", 3)
                .putString("setup_boot_state", HomeSetup.BOOT_STATE_RECOVERY_ARRIVED)
                .putString("setup_boot_round", "bc:3")
                .apply();
        host.defaultHome.help(); // "更多设置方式"
        host.dialogs.active().clickItem(5); // "系统默认桌面选择（无需调试）" -> request()
        host.defaultHome.onPickerResult(Activity.RESULT_CANCELED);
        host.simulateOnResume(true);
        check("r4_1a_already_marco_paused_cancel_role_picker_preserves_pause_boot_off_and_records",
                HomeSetup.paused(host)
                && "用户主动暂停".equals(host.sysPrefs.getString("setup_pause_reason", ""))
                && !host.sysPrefs.getBoolean("boot_home", true)
                && host.sysPrefs.getLong("setup_home_seen", 0L) == 1700005555000L
                && host.sysPrefs.getInt("setup_boot_observed", -1) == 3
                && HomeSetup.BOOT_STATE_RECOVERY_ARRIVED.equals(host.sysPrefs.getString("setup_boot_state", ""))
                && !HomeSetup.hasPendingPicker(host)
                && host.isPage("default_home"));

        // 1b. ACTION_HOME_SETTINGS cancel (normal Back AND direct Home key) when Marco was already HOME
        host = freshEnv();
        host.roleAvailable = false;
        host.pm.currentHome = "com.pilipala.tvhome/com.pilipala.tvhome.HomeActivity";
        host.pm.alias = PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        HomeSetup.pause(host, "设置页暂停");
        host.sysPrefs.edit().putLong("setup_home_seen", 1700006666000L).apply();
        host.defaultHome.help();
        host.dialogs.active().clickItem(5); // opens ACTION_HOME_SETTINGS
        host.simulateOnResume(true); // Back without changing HOME
        check("r4_1b_already_marco_paused_cancel_settings_picker_preserves_pause_and_records",
                HomeSetup.paused(host)
                && !host.sysPrefs.getBoolean("boot_home", true)
                && host.sysPrefs.getLong("setup_home_seen", 0L) == 1700006666000L
                && host.isPage("default_home"));
        // Also via direct Home key without changing HOME
        host.defaultHome.help();
        host.dialogs.active().clickItem(5);
        host.simulateOnPause();
        host.simulateOnNewIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        host.simulateOnResume(true);
        check("r4_1b_already_marco_paused_home_key_preserves_pause_and_records",
                HomeSetup.paused(host)
                && !host.sysPrefs.getBoolean("boot_home", true)
                && host.sysPrefs.getLong("setup_home_seen", 0L) == 1700006666000L);

        // 1c. Switching from another launcher B to Marco and pressing Home directly MUST STILL commit!
        host = freshEnv();
        host.pm.homes.clear();
        host.pm.homes.add(homeEntry("com.oem.a", "com.oem.a.Home", true, true, true, true));
        host.pm.homes.add(homeEntry("com.third.b", "com.third.b.Launcher", true, true, true, false));
        host.pm.homes.add(homeEntry("com.pilipala.tvhome", "com.pilipala.tvhome.HomeActivity", true, true, true, false));
        host.pm.currentHome = "com.oem.a/com.oem.a.Home";
        SystemIntegration.rememberHome(host);
        HomeSetup.pause(host, "切换前暂停");
        host.pm.currentHome = "com.third.b/com.third.b.Launcher";
        host.defaultHome.help();
        host.dialogs.active().clickItem(5);
        host.pm.currentHome = "com.pilipala.tvhome/com.pilipala.tvhome.HomeActivity";
        host.simulateOnPause();
        host.simulateOnNewIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        host.simulateOnResume(true);
        check("r4_1c_other_home_to_marco_direct_home_key_still_commits",
                !HomeSetup.paused(host)
                && SystemIntegration.homeIsOurs(host)
                && host.sysPrefs.getBoolean("boot_home", false)
                && host.sysPrefs.getBoolean("setup_native_seen", false)
                && "com.third.b/com.third.b.Launcher".equals(SystemIntegration.trackedHome(host)));

        // =====================================================================
        // Round-4 Fix #2: Cancelling Accessibility Settings preserves independent "开机打开 Marco TV" (boot_home)
        //                 for BOTH initial values: true AND false!
        // =====================================================================
        // 2a. OEM is HOME, user manually toggled boot_home = true -> enters Accessibility Settings -> cancels without grant
        host = freshEnv();
        host.pm.actions.remove(Settings.ACTION_HOME_SETTINGS);
        host.sysPrefs.edit().putBoolean("boot_home", true)
                .putLong("setup_home_seen", 1700007777000L).apply();
        setService(false, false);
        drainBridgeSync();
        bridgeConfigs.clear();
        host.defaultHome.compatibility();
        host.dialogs.active().clickPositive(); // opens Accessibility Settings
        host.simulateOnResume(true); // returned without granting
        drainBridgeSync();
        JSONObject r4_2aCfg = bridgeConfigs.isEmpty() ? null : bridgeConfigs.get(bridgeConfigs.size() - 1);
        check("r4_2a_accessibility_cancel_preserves_independent_boot_home_true",
                host.sysPrefs.getBoolean("boot_home", false)
                && !host.sysPrefs.getBoolean("home_compat", true)
                && !host.sysPrefs.getBoolean("setup_accessibility_pending", false)
                && host.sysPrefs.getLong("setup_home_seen", 0L) == 1700007777000L
                && r4_2aCfg != null && !r4_2aCfg.optBoolean("wake")
                && host.toasts.stream().anyMatch(t -> t.contains("尚未启用 Home 兼容服务")));

        // 2b. OEM is HOME, boot_home = false -> enters Accessibility Settings -> cancels without grant
        host = freshEnv();
        host.pm.actions.remove(Settings.ACTION_HOME_SETTINGS);
        host.sysPrefs.edit().putBoolean("boot_home", false).apply();
        setService(false, false);
        drainBridgeSync();
        bridgeConfigs.clear();
        host.defaultHome.compatibility();
        host.dialogs.active().clickPositive();
        host.simulateOnResume(true);
        drainBridgeSync();
        JSONObject r4_2bCfg = bridgeConfigs.isEmpty() ? null : bridgeConfigs.get(bridgeConfigs.size() - 1);
        check("r4_2b_accessibility_cancel_preserves_independent_boot_home_false",
                !host.sysPrefs.getBoolean("boot_home", true)
                && !host.sysPrefs.getBoolean("home_compat", true)
                && !host.sysPrefs.getBoolean("setup_accessibility_pending", false)
                && r4_2bCfg != null && !r4_2bCfg.optBoolean("wake"));

        // =====================================================================
        // Round-4 Fix #3: Same boot round handled once -> user re-enables compat (begin()) ->
        //                 duplicate broadcast in same boot MUST NOT launch a second time or cancel 90s Home test!
        // =====================================================================
        host = freshEnv();
        Settings.Global.ints.put("boot_count", 3);
        HomeSetup.begin(host);
        host.sysPrefs.edit().putBoolean("home_compat", true).putBoolean("boot_home", true).apply();
        setService(true, true);
        // Boot #4 first broadcast launches once
        Settings.Global.ints.put("boot_count", 4);
        HomeSetup.bootIdOverride = "boot-r4-3-b4";
        SystemClock.elapsed = 12000L;
        BootReceiver r4Boot = new BootReceiver();
        r4Boot.onReceive(host, new Intent(Intent.ACTION_BOOT_COMPLETED));
        check("r4_3_boot4_first_broadcast_launches_once", host.startedActivities.size() == 1);
        // User re-enables compat mode in settings (calls HomeSetup.begin!) and starts 90s Home test
        SystemClock.elapsed = 20000L;
        HomeSetup.begin(host);
        host.sysPrefs.edit().putBoolean("home_compat", true).putBoolean("boot_home", true).apply();
        HomeSetup.armHomeTest(host);
        // Duplicate BOOT_COMPLETED arrives in SAME Boot #4 (same boot_count=4, same boot_id)
        SystemClock.elapsed = 25000L;
        r4Boot.onReceive(host, new Intent(Intent.ACTION_BOOT_COMPLETED));
        check("r4_3_duplicate_after_reenable_in_same_boot_does_not_launch_twice_or_cancel_home_test",
                host.startedActivities.size() == 1
                && host.sysPrefs.contains("setup_home_deadline"));
        // Armed 90s Home test still succeeds
        SystemClock.elapsed = 30000L;
        String tR4_3 = HomeSetup.issueHomeEvent(host, HomeSetup.SOURCE_COMPAT_KEY);
        HomeSetup.observeHomeIntent(host, SystemIntegration.homeIntent(host).putExtra(HomeSetup.EXTRA_EVENT_TOKEN, tR4_3));
        check("r4_3_armed_home_test_still_succeeds_after_same_boot_duplicate",
                host.sysPrefs.contains("setup_home_seen")
                && HomeSetup.SOURCE_COMPAT_KEY.equals(host.sysPrefs.getString("setup_home_source", "")));
        // Next real reboot (Boot #5) DOES launch normally
        Settings.Global.ints.put("boot_count", 5);
        HomeSetup.bootIdOverride = "boot-r4-3-b5";
        SystemClock.elapsed = 11000L;
        r4Boot.onReceive(host, new Intent(Intent.ACTION_BOOT_COMPLETED));
        check("r4_3_next_real_reboot_launches_normally",
                host.startedActivities.size() == 2 && host.sysPrefs.getInt("boot_req_boot", -1) == 5);

        // =====================================================================
        // Round-4 Fix #4: Android 6 legacy 0.21.2 compat user's FIRST boot_id read happens during boot recovery ->
        //                 MUST record RECOVERY_ARRIVED after window focus, while ordinary native HOME still only sets baseline!
        // =====================================================================
        // 4a. Legacy 0.21.2 Android 6 compat user (no setup_boot_armed_boot_id) receives BOOT_COMPLETED -> window focus
        host = freshEnv();
        Build.VERSION.SDK_INT = 23;
        Settings.Global.ints.put("boot_count", -1);
        HomeSetup.bootIdOverride = "api23-legacy-compat-first";
        host.sysPrefs.edit()
                .putBoolean("setup_requested", true)
                .putBoolean("home_compat", true)
                .putBoolean("boot_home", true)
                .putInt("setup_boot_armed", -1)
                .apply(); // NO setup_boot_armed_boot_id!
        setService(true, true);
        SystemClock.elapsed = 12000L;
        new BootReceiver().onReceive(host, new Intent(Intent.ACTION_BOOT_COMPLETED));
        check("r4_4a_boot_receiver_issued_recovery_launch", host.startedActivities.size() == 1);
        Intent api23RecoveryLaunch = host.startedActivities.get(0);
        // Simulate MainActivity cold start -> onResume(focus=false) [calls reconcile BEFORE focus!] -> onWindowFocusChanged(true)
        MainActivity api23Act = new MainActivity();
        api23Act.sysPrefs = host.sysPrefs;
        api23Act.homePrefs = host.homePrefs;
        api23Act.pm = host.pm;
        api23Act.defaultHome = new DefaultHomePanel(api23Act);
        api23Act.simulateOnCreate(api23RecoveryLaunch, false);
        api23Act.simulateOnResume(false); // reconcile runs here before window focus!
        boolean focusedArrived = api23Act.simulateOnWindowFocusChanged(true);
        HomeSetup.confirmBoot(api23Act);
        check("r4_4a_api23_legacy_compat_first_boot_id_records_recovery_arrival",
                focusedArrived
                && HomeSetup.BOOT_STATE_RECOVERY_ARRIVED.equals(host.sysPrefs.getString("boot_req_state", ""))
                && HomeSetup.BOOT_STATE_RECOVERY_ARRIVED.equals(host.sysPrefs.getString("setup_boot_state", ""))
                && HomeSetup.bootStatus(api23Act).contains("已记录开机恢复到达")
                && "api23-legacy-compat-first".equals(host.sysPrefs.getString("setup_boot_armed_boot_id", ""))
                && host.sysPrefs.contains("setup_boot_confirmed"));

        // 4b. Legacy 0.21.2 Android 6 native HOME user on FIRST boot_id read STILL only establishes baseline (no false boot arrival!)
        host = freshEnv();
        Build.VERSION.SDK_INT = 23;
        Settings.Global.ints.put("boot_count", -1);
        HomeSetup.bootIdOverride = "api23-legacy-native-first";
        host.pm.currentHome = "com.pilipala.tvhome/com.pilipala.tvhome.HomeActivity";
        host.sysPrefs.edit()
                .putBoolean("setup_requested", true)
                .putBoolean("setup_native_seen", true)
                .putBoolean("boot_home", true)
                .putInt("setup_boot_armed", -1)
                .apply();
        SystemClock.elapsed = 15000L;
        host.simulateOnPause();
        host.simulateOnNewIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        host.simulateOnResume(true);
        check("r4_4b_api23_legacy_native_first_boot_id_only_sets_baseline",
                "api23-legacy-native-first".equals(host.sysPrefs.getString("setup_boot_armed_boot_id", ""))
                && !host.sysPrefs.contains("setup_boot_state"));

        // Follow-up #2: When Marco is already native default HOME and not paused,
        // manually turning OFF "开机打开 Marco TV" (boot_home) MUST survive routine reconcile()
        // (returning to desktop / onResume) AND clicking "检查使用效果", while resuming from pause
        // still turns boot_home back ON on initial re-acceptance.
        host = freshEnv();
        host.pm.currentHome = "com.pilipala.tvhome/com.pilipala.tvhome.HomeActivity";
        HomeSetup.begin(host);
        HomeSetup.acceptNative(host);
        check("followup2_initial_native_accept_enables_boot_home",
                host.sysPrefs.getBoolean("setup_native_seen", false)
                && host.sysPrefs.getBoolean("boot_home", false));
        // User manually toggles "开机打开 Marco TV" OFF in Settings.
        host.sysPrefs.edit().putBoolean("boot_home", false).apply();
        // Routine foreground return triggers MainActivity.onResume() -> HomeSetup.reconcile().
        host.simulateOnPause();
        host.simulateOnResume(true);
        HomeSetup.reconcile(host);
        check("followup2_routine_reconcile_preserves_user_disabled_boot_home",
                host.sysPrefs.getBoolean("setup_native_seen", false)
                && !host.sysPrefs.getBoolean("boot_home", true)
                && !HomeSetup.paused(host));
        // Opening "检查使用效果" via DefaultHomePanel.start() when already ready also preserves boot_home=false.
        host.defaultHome.start();
        check("followup2_verification_button_preserves_user_disabled_boot_home",
                !host.sysPrefs.getBoolean("boot_home", true));
        // Explicitly pausing and resuming via "继续一键设置" runs begin() + acceptNative() -> re-enables boot_home=true.
        HomeSetup.pause(host, "manual");
        host.defaultHome.start();
        check("followup2_resume_from_pause_re_enables_boot_home",
                !HomeSetup.paused(host)
                && host.sysPrefs.getBoolean("setup_native_seen", false)
                && host.sysPrefs.getBoolean("boot_home", false));
    }

    static void testGuardModeAndFilterRefresh() throws Exception {
        MainActivity host = freshEnv();
        host.secureAuthorized = true;
        host.sysPrefs.edit().putBoolean("home_compat", true).putBoolean("boot_home", true).commit();
        check("guard_mode_default_is_window_pullback",
                SystemIntegration.guardMode(host) == HomeSetupPolicy.GuardMode.WINDOW_PULLBACK);

        // Legacy home_native_keys=true without home_guard_mode migrates to NATIVE_KEYS_ONLY.
        host.sysPrefs.edit().putBoolean("home_native_keys", true).commit();
        check("guard_mode_legacy_native_migrates",
                SystemIntegration.guardMode(host) == HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY);

        // Switching guard modes persists home_guard_mode, syncs legacy flag and live runtime filter.
        HomeKeyService.connected = true;
        SystemIntegration.setGuardMode(host, HomeSetupPolicy.GuardMode.KEY_FILTER);
        check("guard_mode_key_filter_persists_and_syncs",
                SystemIntegration.guardMode(host) == HomeSetupPolicy.GuardMode.KEY_FILTER
                && "key_filter".equals(host.sysPrefs.getString(SystemIntegration.PREF_GUARD_MODE, ""))
                && !host.sysPrefs.getBoolean("home_native_keys", true)
                && HomeKeyService.runtimeKeyFilterRequested
                && HomeKeyService.runtimeFlags == 32
                && StatusSnapshot.local(host).contains("快速 Home（请求按键过滤）")
                && StatusSnapshot.local(host).contains("按键过滤：已请求"));

        SystemIntegration.setGuardMode(host, HomeSetupPolicy.GuardMode.WINDOW_PULLBACK);
        check("guard_mode_window_pullback_clears_runtime_filter",
                SystemIntegration.guardMode(host) == HomeSetupPolicy.GuardMode.WINDOW_PULLBACK
                && "window".equals(host.sysPrefs.getString(SystemIntegration.PREF_GUARD_MODE, ""))
                && !host.sysPrefs.getBoolean("home_native_keys", true)
                && !HomeKeyService.runtimeKeyFilterRequested
                && HomeKeyService.runtimeFlags == 0
                && StatusSnapshot.local(host).contains("窗口回拉（兼容模式，不拦截按键）")
                && StatusSnapshot.local(host).contains("按键过滤：未请求"));

        // P1 #1 + P2 #2 repro: upgrade unbinds Marco; 50ms later reconcile() runs (e.g. settings resume) and
        // MUST recognize own rebind without pausing home_compat/boot_home; 310ms later Marco is written back
        // (state="written", awaiting connect -> revision STILL 1); only AFTER HomeKeyService connects and
        // applies the chosen guard mode is home_filter_revision committed to HOME_FILTER_REVISION.
        String xiaoAi = "com.xiaomi.voicecontrol/.VoiceAccessibilityService";
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, xiaoAi + ":" + HomeSetup.SERVICE);
        host.sysPrefs.edit().putInt("home_filter_revision", 1).putBoolean("home_compat", true).putBoolean("boot_home", true).commit();
        int beforeSync = HomeKeyService.syncCalls.get();
        SystemIntegration.refreshHomeService(host);
        // Simulate Android unbinding the service when Marco removed itself from ENABLED_ACCESSIBILITY_SERVICES.
        HomeKeyService.connected = false;
        HomeKeyService.runtimeModeApplied = false;
        String duringRebind = Settings.Secure.strings.get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        check("p1_1_upgrade_rebind_removes_only_own_and_defers_revision_commit",
                xiaoAi.equals(duringRebind)
                && SystemIntegration.isOwnRebindInProgress(host)
                && host.sysPrefs.getInt("home_filter_revision", 0) == 1
                && HomeKeyService.syncCalls.get() == beforeSync + 1);

        // At +50ms: settings/panel resumes -> HomeSetup.reconcile() + premature recover() run.
        SystemClock.elapsed += 50;
        HomeSetup.reconcile(host);
        SystemIntegration.recover(host);
        check("p1_1_reconcile_at_50ms_preserves_compat_and_boot_and_no_premature_readd",
                !HomeSetup.paused(host)
                && host.sysPrefs.getBoolean("home_compat", false)
                && host.sysPrefs.getBoolean("boot_home", false)
                && xiaoAi.equals(Settings.Secure.strings.get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
                && host.sysPrefs.getInt("home_filter_revision", 0) == 1);

        // At +310ms: delayed rebind writes Marco back, but service has NOT connected yet ->
        // MUST report "已写回、等待连接" and MUST NOT mark home_filter_revision complete yet!
        Diagnostics.events.clear();
        SystemClock.elapsed += 260;
        Handler.runDue();
        String afterRebind = Settings.Secure.strings.get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        check("p2_2_written_before_connect_awaits_binding_and_defers_revision",
                !HomeSetup.paused(host)
                && host.sysPrefs.getBoolean("home_compat", false)
                && host.sysPrefs.getBoolean("boot_home", false)
                && !SystemIntegration.isOwnRebindInProgress(host)
                && SystemIntegration.REBIND_STATE_WRITTEN.equals(host.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, ""))
                && afterRebind != null && afterRebind.contains(xiaoAi) && afterRebind.contains(HomeSetup.SERVICE)
                && host.sysPrefs.getInt("home_filter_revision", 0) == 1
                && Diagnostics.events.contains("HOME_SERVICE_REBIND_AWAITING_CONNECT")
                && !Diagnostics.events.contains("HOME_SERVICE_REBOUND")
                && StatusSnapshot.local(host).contains("已写回、等待连接"));

        // Calling refreshHomeService again while awaiting connect must NOT unbind again.
        SystemIntegration.refreshHomeService(host);
        check("p2_2_refresh_while_awaiting_connect_does_not_reunbind",
                HomeSetup.serviceEnabled(host)
                && SystemIntegration.REBIND_STATE_WRITTEN.equals(host.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, ""))
                && host.sysPrefs.getInt("home_filter_revision", 0) == 1);

        // Once HomeKeyService connects and applies the chosen guard mode, revision=2 commits cleanly.
        HomeKeyService.simulateConnect(host);
        check("p1_1_rebind_commits_revision_only_after_service_connects_and_applies_mode",
                host.sysPrefs.getInt("home_filter_revision", 0) == SystemIntegration.HOME_FILTER_REVISION
                && host.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, "").isEmpty()
                && Diagnostics.events.contains("HOME_SERVICE_REBOUND")
                && StatusSnapshot.local(host).contains("已连接并应用模式"));

        // Second refresh at current revision does not unbind again.
        SystemIntegration.refreshHomeService(host);
        check("refresh_home_service_noop_after_revision_current",
                Settings.Secure.strings.get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).contains(HomeSetup.SERVICE));

        // P1 #1b: transient restore failure retries automatically and commits revision once connected.
        MainActivity retryHost = freshEnv();
        retryHost.secureAuthorized = true;
        retryHost.sysPrefs.edit().putBoolean("home_compat", true).putBoolean("boot_home", true).putInt("home_filter_revision", 1).commit();
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, xiaoAi + ":" + HomeSetup.SERVICE);
        SystemIntegration.refreshHomeService(retryHost);
        Settings.Secure.failNextPutStrings = 1;
        SystemClock.elapsed += 300;
        Handler.runDue();
        check("p1_1b_failed_restore_defers_revision_and_schedules_retry",
                !HomeSetup.serviceEnabled(retryHost)
                && retryHost.sysPrefs.getInt("home_filter_revision", 0) == 1
                && SystemIntegration.isOwnRebindInProgress(retryHost));
        SystemClock.elapsed += 100;
        HomeSetup.reconcile(retryHost);
        check("p1_1b_reconcile_during_retry_window_does_not_pause",
                !HomeSetup.paused(retryHost) && retryHost.sysPrefs.getBoolean("home_compat", false));
        SystemClock.elapsed += 210;
        Handler.runDue();
        check("p1_1b_retry_writes_back_and_awaits_connect",
                HomeSetup.serviceEnabled(retryHost)
                && Settings.Secure.strings.get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).contains(xiaoAi)
                && retryHost.sysPrefs.getInt("home_filter_revision", 0) == 1);
        HomeKeyService.simulateConnect(retryHost);
        check("p1_1b_retry_commits_revision_after_connect",
                retryHost.sysPrefs.getInt("home_filter_revision", 0) == SystemIntegration.HOME_FILTER_REVISION);

        // P1 #1f (process exit mid-rebind): temporary unbind -> process dies -> reopen app ->
        // home_compat & boot_home MUST stay ON, service written back, revision committed on connect!
        MainActivity crashHost = freshEnv();
        crashHost.secureAuthorized = true;
        crashHost.sysPrefs.edit().putBoolean("home_compat", true).putBoolean("boot_home", true).putInt("home_filter_revision", 1).commit();
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, xiaoAi + ":" + HomeSetup.SERVICE);
        SystemIntegration.refreshHomeService(crashHost);
        // Simulate process kill right after temporary unbind!
        Handler.clearDelayed();
        SystemIntegration.resetRebindStateForTest();
        HomeKeyService.resetForTest();
        // Fresh process opens app: reconcile() then refreshHomeService()!
        HomeSetup.reconcile(crashHost);
        check("p1_1f_process_exit_reopen_reconcile_preserves_compat_and_boot",
                !HomeSetup.paused(crashHost)
                && crashHost.sysPrefs.getBoolean("home_compat", false)
                && crashHost.sysPrefs.getBoolean("boot_home", false)
                && SystemIntegration.isOwnRebindInProgress(crashHost));
        SystemIntegration.refreshHomeService(crashHost);
        check("p1_1f_process_exit_reopen_restores_service_and_awaits_connect",
                !HomeSetup.paused(crashHost)
                && crashHost.sysPrefs.getBoolean("home_compat", false)
                && crashHost.sysPrefs.getBoolean("boot_home", false)
                && HomeSetup.serviceEnabled(crashHost)
                && Settings.Secure.strings.get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).contains(xiaoAi)
                && crashHost.sysPrefs.getInt("home_filter_revision", 0) == 1);
        HomeKeyService.simulateConnect(crashHost);
        check("p1_1f_process_exit_reopen_commits_revision_after_connect",
                crashHost.sysPrefs.getInt("home_filter_revision", 0) == SystemIntegration.HOME_FILTER_REVISION);

        // P1 #1c (3 consecutive write-back failures -> writes recover -> app re-enters):
        // preserves user's home_compat/boot_home, never marks revision done early, and finishes on re-entry!
        MainActivity failHost = freshEnv();
        failHost.secureAuthorized = true;
        failHost.sysPrefs.edit().putBoolean("home_compat", true).putBoolean("boot_home", true).putInt("home_filter_revision", 1).commit();
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, xiaoAi + ":" + HomeSetup.SERVICE);
        SystemIntegration.refreshHomeService(failHost);
        Settings.Secure.failNextPutStrings = 5;
        for (int i = 0; i < 4; i++) { SystemClock.elapsed += 310; Handler.runDue(); }
        Settings.Secure.failNextPutStrings = 0;
        check("p1_1c_exhausted_rebind_retries_preserve_settings_and_never_mark_revision_done",
                !HomeSetup.serviceEnabled(failHost)
                && !HomeSetup.paused(failHost)
                && failHost.sysPrefs.getBoolean("home_compat", false)
                && failHost.sysPrefs.getBoolean("boot_home", false)
                && failHost.sysPrefs.getInt("home_filter_revision", 0) == 1
                && Diagnostics.events.contains("HOME_SERVICE_REBIND_FAILED"));
        // Writes recover & user re-opens app (even after a process restart!):
        SystemIntegration.resetRebindStateForTest();
        HomeKeyService.resetForTest();
        HomeSetup.reconcile(failHost);
        SystemIntegration.refreshHomeService(failHost);
        check("p1_1c_reentry_after_writes_recover_preserves_settings_and_restores_service",
                !HomeSetup.paused(failHost)
                && failHost.sysPrefs.getBoolean("home_compat", false)
                && failHost.sysPrefs.getBoolean("boot_home", false)
                && HomeSetup.serviceEnabled(failHost)
                && Settings.Secure.strings.get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).contains(xiaoAi)
                && failHost.sysPrefs.getInt("home_filter_revision", 0) == 1);
        HomeKeyService.simulateConnect(failHost);
        check("p1_1c_reentry_commits_revision_after_connect",
                failHost.sysPrefs.getInt("home_filter_revision", 0) == SystemIntegration.HOME_FILTER_REVISION);

        // P1 #1g (partial write path): Marco is the ONLY enabled accessibility service; system rejects
        // putString("") -> global ACCESSIBILITY_ENABLED MUST stay 1 and not be shut off!
        MainActivity partialHost = freshEnv();
        partialHost.secureAuthorized = true;
        partialHost.sysPrefs.edit().putBoolean("home_compat", true).putBoolean("boot_home", true).putInt("home_filter_revision", 1).commit();
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, HomeSetup.SERVICE);
        Settings.Secure.failNextPutStrings = 1;
        SystemIntegration.refreshHomeService(partialHost);
        check("p1_1g_rejected_service_list_update_never_disables_global_accessibility",
                Settings.Secure.ints.get(Settings.Secure.ACCESSIBILITY_ENABLED) == 1
                && HomeSetup.SERVICE.equals(Settings.Secure.strings.get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
                && partialHost.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, "").isEmpty()
                && partialHost.sysPrefs.getInt("home_filter_revision", 0) == 1);
        Settings.Secure.failNextPutStrings = 1;
        boolean removedWhenRejected = SystemIntegration.removeOwnHomeService(partialHost);
        check("p1_1g_remove_own_rejected_never_disables_global_accessibility",
                !removedWhenRejected
                && Settings.Secure.ints.get(Settings.Secure.ACCESSIBILITY_ENABLED) == 1
                && HomeSetup.SERVICE.equals(Settings.Secure.strings.get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)));

        // P1 #1d: active user revocation during rebind (turning accessibility off externally) IS respected,
        // and user revocation AFTER "written" state (before connect) is ALSO respected!
        MainActivity revokeHost = freshEnv();
        revokeHost.secureAuthorized = true;
        revokeHost.sysPrefs.edit().putBoolean("home_compat", true).putBoolean("boot_home", true).putInt("home_filter_revision", 1).commit();
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, xiaoAi + ":" + HomeSetup.SERVICE);
        SystemIntegration.refreshHomeService(revokeHost);
        SystemClock.elapsed += 50;
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        HomeSetup.reconcile(revokeHost);
        SystemClock.elapsed += 260;
        Handler.runDue();
        check("p1_1d_user_revocation_during_rebind_pauses_and_never_restores",
                HomeSetup.paused(revokeHost)
                && !revokeHost.sysPrefs.getBoolean("home_compat", true)
                && !revokeHost.sysPrefs.getBoolean("boot_home", true)
                && !HomeSetup.serviceEnabled(revokeHost)
                && revokeHost.sysPrefs.getInt("home_filter_revision", 0) == 1);

        MainActivity revokeWrittenHost = freshEnv();
        revokeWrittenHost.secureAuthorized = true;
        revokeWrittenHost.sysPrefs.edit().putBoolean("home_compat", true).putBoolean("boot_home", true).putInt("home_filter_revision", 1).commit();
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, xiaoAi + ":" + HomeSetup.SERVICE);
        SystemIntegration.refreshHomeService(revokeWrittenHost);
        SystemClock.elapsed += 310;
        Handler.runDue();
        // Now in "written" state (awaiting connect); user removes Marco externally.
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, xiaoAi);
        HomeSetup.reconcile(revokeWrittenHost);
        check("p1_1d_user_revocation_after_written_state_pauses_cleanly",
                HomeSetup.paused(revokeWrittenHost)
                && !revokeWrittenHost.sysPrefs.getBoolean("home_compat", true)
                && !revokeWrittenHost.sysPrefs.getBoolean("boot_home", true)
                && revokeWrittenHost.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, "").isEmpty());

        // P1 #1e: paused user is untouched by refreshHomeService.
        HomeSetup.pause(host, "manual");
        host.sysPrefs.edit().putInt("home_filter_revision", 0).commit();
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, xiaoAi + ":" + HomeSetup.SERVICE);
        SystemIntegration.refreshHomeService(host);
        check("refresh_home_service_skips_when_paused",
                (xiaoAi + ":" + HomeSetup.SERVICE).equals(Settings.Secure.strings.get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
                && host.sysPrefs.getInt("home_filter_revision", 0) == 0);

        // P1 #2 repro: manual accessibility only (no WRITE_SECURE_SETTINGS) + KEY_FILTER -> disableHomeCompat
        // immediately clears runtime key filter even while service remains bound in Settings.Secure.
        MainActivity manualHost = freshEnv();
        manualHost.secureAuthorized = false;
        manualHost.sysPrefs.edit().putBoolean("home_compat", true).commit();
        setService(true, true);
        SystemIntegration.setGuardMode(manualHost, HomeSetupPolicy.GuardMode.KEY_FILTER);
        check("p1_2_manual_key_filter_enabled_before_disable",
                HomeKeyService.runtimeKeyFilterRequested && HomeKeyService.runtimeFlags == 32);
        SystemIntegration.disableHomeCompat(manualHost);
        check("p1_2_disable_compat_without_secure_settings_revokes_key_filter_immediately",
                HomeSetup.paused(manualHost)
                && !manualHost.sysPrefs.getBoolean("home_compat", true)
                && HomeSetup.serviceEnabled(manualHost)
                && !HomeKeyService.runtimeKeyFilterRequested
                && HomeKeyService.runtimeFlags == 0);

        // P2 #3 repro: NATIVE_KEYS_ONLY disables TaskBridge wake and syncs a running bridge immediately on switch.
        MainActivity bridgeHost = freshEnv();
        bridgeHost.sysPrefs.edit()
                .putBoolean("home_compat", true)
                .putString("original_home", "com.mitv.tvhome/com.mitv.tvhome.MainActivityUserMode")
                .commit();
        bridgeHost.homePrefs.edit().putBoolean("recent_wake", true).commit();
        setService(true, true);
        final List<JSONObject> modeBridgeConfigs = Collections.synchronizedList(new ArrayList<>());
        TaskBridgeClient.attach(new IBinder() {
            @Override public boolean isBinderAlive() { return true; }
            @Override public void linkToDeath(DeathRecipient r, int flags) { }
            @Override public boolean transact(int code, Parcel data, Parcel reply, int flags) {
                data.readInterfaceToken();
                try {
                    JSONObject req = new JSONObject(data.readString());
                    if ("configure".equals(req.optString("action"))) modeBridgeConfigs.add(req);
                    reply.writeNoException();
                    reply.writeString(new JSONObject().put("ok", true).put("wake", req.optBoolean("wake")).toString());
                    return true;
                } catch (Exception e) { throw new RuntimeException(e); }
            }
        });
        check("p2_3_window_mode_allows_bridge_wake", TaskBridgeClient.shouldEnableWake(bridgeHost));
        SystemIntegration.setGuardMode(bridgeHost, HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY);
        long deadline = System.currentTimeMillis() + 2000;
        while (modeBridgeConfigs.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10);
        check("p2_3_native_keys_only_disables_and_syncs_bridge_wake_off",
                !TaskBridgeClient.shouldEnableWake(bridgeHost)
                && !modeBridgeConfigs.isEmpty()
                && !modeBridgeConfigs.get(modeBridgeConfigs.size() - 1).optBoolean("wake", true));

        int beforeRestoreCfg = modeBridgeConfigs.size();
        SystemIntegration.setGuardMode(bridgeHost, HomeSetupPolicy.GuardMode.WINDOW_PULLBACK);
        deadline = System.currentTimeMillis() + 2000;
        while (modeBridgeConfigs.size() <= beforeRestoreCfg && System.currentTimeMillis() < deadline) Thread.sleep(10);
        check("p2_3_switching_back_to_window_syncs_bridge_wake_on",
                TaskBridgeClient.shouldEnableWake(bridgeHost)
                && modeBridgeConfigs.size() > beforeRestoreCfg
                && modeBridgeConfigs.get(modeBridgeConfigs.size() - 1).optBoolean("wake", false));

        // === Round 5 Upgrade Rebind Regressions ===
        // 1A (P1): Only Marco enabled; removal write puts services="" OK but putInt(0) FAILS ->
        // disk is ("", 1). Re-entering page preserves home_compat & boot_home, recovers to (Marco, 1),
        // and still respects active user revocation (turning ACCESSIBILITY_ENABLED=0 externally).
        MainActivity partialRemoveHost = freshEnv();
        partialRemoveHost.secureAuthorized = true;
        partialRemoveHost.sysPrefs.edit()
                .putBoolean("home_compat", true).putBoolean("boot_home", true)
                .putInt("home_filter_revision", 1).commit();
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, HomeSetup.SERVICE);
        HomeKeyService.simulateConnect(partialRemoveHost);
        Settings.Secure.failNextPutInts = 1;
        SystemIntegration.refreshHomeService(partialRemoveHost);
        check("r5_p1_partial_removal_write_keeps_rebind_in_progress",
                !HomeSetup.serviceEnabled(partialRemoveHost)
                && "".equals(Settings.Secure.strings.get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
                && Settings.Secure.ints.get(Settings.Secure.ACCESSIBILITY_ENABLED) == 1
                && SystemIntegration.isOwnRebindInProgress(partialRemoveHost));
        SystemClock.elapsed += 50;
        partialRemoveHost.simulateOnResume(true);
        check("r5_p1_partial_removal_reentry_preserves_compat_and_boot",
                !HomeSetup.paused(partialRemoveHost)
                && partialRemoveHost.sysPrefs.getBoolean("home_compat", false)
                && partialRemoveHost.sysPrefs.getBoolean("boot_home", false)
                && partialRemoveHost.sysPrefs.getInt("home_filter_revision", 0) == 1);
        SystemClock.elapsed += 260;
        Handler.runDue();
        check("r5_p1_partial_removal_recovers_service_and_awaits_new_connect",
                HomeSetup.serviceEnabled(partialRemoveHost)
                && !HomeSetup.paused(partialRemoveHost)
                && partialRemoveHost.sysPrefs.getBoolean("home_compat", false)
                && partialRemoveHost.sysPrefs.getBoolean("boot_home", false)
                && SystemIntegration.REBIND_STATE_WRITTEN.equals(partialRemoveHost.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, ""))
                && partialRemoveHost.sysPrefs.getInt("home_filter_revision", 0) == 1);
        HomeKeyService.simulateUnbind(partialRemoveHost);
        HomeKeyService.simulateConnect(partialRemoveHost);
        check("r5_p1_partial_removal_completes_on_new_connect",
                partialRemoveHost.sysPrefs.getInt("home_filter_revision", 0) == SystemIntegration.HOME_FILTER_REVISION
                && partialRemoveHost.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, "").isEmpty());

        // 1A-restart: Process exits right after partial removal write ("", 1) -> cold start recovers cleanly!
        MainActivity partialRemoveCrash = freshEnv();
        partialRemoveCrash.secureAuthorized = true;
        partialRemoveCrash.sysPrefs.edit()
                .putBoolean("home_compat", true).putBoolean("boot_home", true)
                .putInt("home_filter_revision", 1).commit();
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, HomeSetup.SERVICE);
        HomeKeyService.simulateConnect(partialRemoveCrash);
        Settings.Secure.failNextPutInts = 1;
        SystemIntegration.refreshHomeService(partialRemoveCrash);
        Handler.clearDelayed();
        SystemIntegration.resetRebindStateForTest();
        HomeKeyService.resetForTest();
        MainActivity partialRemoveRestart = new MainActivity();
        partialRemoveRestart.sysPrefs = partialRemoveCrash.sysPrefs;
        partialRemoveRestart.homePrefs = partialRemoveCrash.homePrefs;
        partialRemoveRestart.pm = partialRemoveCrash.pm;
        partialRemoveRestart.secureAuthorized = true;
        partialRemoveRestart.defaultHome = new DefaultHomePanel(partialRemoveRestart);
        partialRemoveRestart.simulateRealColdStart(true);
        check("r5_p1_partial_removal_process_exit_recovers_on_cold_start",
                !HomeSetup.compatResetPending(partialRemoveRestart)
                && !HomeSetup.paused(partialRemoveRestart)
                && partialRemoveRestart.sysPrefs.getBoolean("home_compat", false)
                && partialRemoveRestart.sysPrefs.getBoolean("boot_home", false)
                && HomeSetup.serviceEnabled(partialRemoveRestart)
                && SystemIntegration.REBIND_STATE_WRITTEN.equals(partialRemoveRestart.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, "")));

        // 1A-revoke: Active user turning off global ACCESSIBILITY_ENABLED during partial removal ("", 1) -> pauses!
        MainActivity partialRemoveRevoke = freshEnv();
        partialRemoveRevoke.secureAuthorized = true;
        partialRemoveRevoke.sysPrefs.edit()
                .putBoolean("home_compat", true).putBoolean("boot_home", true)
                .putInt("home_filter_revision", 1).commit();
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, HomeSetup.SERVICE);
        Settings.Secure.failNextPutInts = 1;
        SystemIntegration.refreshHomeService(partialRemoveRevoke);
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        partialRemoveRevoke.simulateOnResume(true);
        SystemClock.elapsed += 310;
        Handler.runDue();
        check("r5_p1_partial_removal_user_revocation_still_pauses",
                HomeSetup.paused(partialRemoveRevoke)
                && !partialRemoveRevoke.sysPrefs.getBoolean("home_compat", true)
                && !partialRemoveRevoke.sysPrefs.getBoolean("boot_home", true)
                && !HomeSetup.serviceEnabled(partialRemoveRevoke));

        // 1B (P1): Only Marco enabled; removal writes ("", 0) cleanly, then restore puts services=Marco OK
        // but putInt(1) FAILS across all retries -> disk is (Marco, 0). Re-entering page preserves both switches
        // while still failing, recovers once putInt works again, and still honors active user removal!
        MainActivity partialRestoreHost = freshEnv();
        partialRestoreHost.secureAuthorized = true;
        partialRestoreHost.sysPrefs.edit()
                .putBoolean("home_compat", true).putBoolean("boot_home", true)
                .putInt("home_filter_revision", 1).commit();
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, HomeSetup.SERVICE);
        HomeKeyService.simulateConnect(partialRestoreHost);
        SystemIntegration.refreshHomeService(partialRestoreHost);
        HomeKeyService.simulateUnbind(partialRestoreHost);
        Settings.Secure.failNextPutInts = 10;
        for (int i = 0; i < 4; i++) { SystemClock.elapsed += 310; Handler.runDue(); }
        check("r5_p1_partial_restore_exhausted_keeps_switches_and_snapshot",
                HomeSetup.SERVICE.equals(Settings.Secure.strings.get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
                && Settings.Secure.ints.get(Settings.Secure.ACCESSIBILITY_ENABLED) == 0
                && !HomeSetup.serviceEnabled(partialRestoreHost)
                && !HomeSetup.paused(partialRestoreHost)
                && partialRestoreHost.sysPrefs.getBoolean("home_compat", false)
                && partialRestoreHost.sysPrefs.getBoolean("boot_home", false)
                && SystemIntegration.isOwnRebindInProgress(partialRestoreHost)
                && Diagnostics.events.contains("HOME_SERVICE_REBIND_FAILED"));
        // Re-entering page while putInt still fails keeps both switches ON.
        partialRestoreHost.simulateOnResume(true);
        check("r5_p1_partial_restore_reentry_while_failing_preserves_switches",
                !HomeSetup.paused(partialRestoreHost)
                && partialRestoreHost.sysPrefs.getBoolean("home_compat", false)
                && partialRestoreHost.sysPrefs.getBoolean("boot_home", false));
        // Once putInt recovers and user re-enters page, service restores to (Marco, 1) and completes on connect!
        Settings.Secure.failNextPutInts = 0;
        SystemClock.elapsed += 310;
        partialRestoreHost.simulateOnResume(true);
        check("r5_p1_partial_restore_reentry_recovers_once_putint_succeeds",
                !HomeSetup.paused(partialRestoreHost)
                && partialRestoreHost.sysPrefs.getBoolean("home_compat", false)
                && partialRestoreHost.sysPrefs.getBoolean("boot_home", false)
                && HomeSetup.serviceEnabled(partialRestoreHost)
                && Settings.Secure.ints.get(Settings.Secure.ACCESSIBILITY_ENABLED) == 1
                && SystemIntegration.REBIND_STATE_WRITTEN.equals(partialRestoreHost.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, ""))
                && partialRestoreHost.sysPrefs.getInt("home_filter_revision", 0) == 1);
        HomeKeyService.simulateConnect(partialRestoreHost);
        check("r5_p1_partial_restore_completes_on_new_connect",
                partialRestoreHost.sysPrefs.getInt("home_filter_revision", 0) == SystemIntegration.HOME_FILTER_REVISION
                && partialRestoreHost.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, "").isEmpty());

        // 1B-revoke: While stuck in partial restore (Marco, 0), user removes Marco from list -> disk ("", 0) -> pauses!
        MainActivity partialRestoreRevoke = freshEnv();
        partialRestoreRevoke.secureAuthorized = true;
        partialRestoreRevoke.sysPrefs.edit()
                .putBoolean("home_compat", true).putBoolean("boot_home", true)
                .putInt("home_filter_revision", 1).commit();
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, HomeSetup.SERVICE);
        SystemIntegration.refreshHomeService(partialRestoreRevoke);
        Settings.Secure.failNextPutInts = 10;
        for (int i = 0; i < 4; i++) { SystemClock.elapsed += 310; Handler.runDue(); }
        Settings.Secure.failNextPutInts = 0;
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
        partialRestoreRevoke.simulateOnResume(true);
        check("r5_p1_partial_restore_user_revocation_pauses_and_never_restores",
                HomeSetup.paused(partialRestoreRevoke)
                && !partialRestoreRevoke.sysPrefs.getBoolean("home_compat", true)
                && !partialRestoreRevoke.sysPrefs.getBoolean("boot_home", true)
                && !HomeSetup.serviceEnabled(partialRestoreRevoke)
                && partialRestoreRevoke.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, "").isEmpty());

        // 2 (P2): Process exits BEFORE onUnbind() (compat_service_active=true still on disk) AND AFTER onUnbind(),
        // restarted in exact real MainActivity.onCreate() -> onResume() order.
        MainActivity preUnbindOld = freshEnv();
        preUnbindOld.secureAuthorized = true;
        preUnbindOld.sysPrefs.edit()
                .putBoolean("home_compat", true).putBoolean("boot_home", true)
                .putInt("home_filter_revision", 1).commit();
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, xiaoAi + ":" + HomeSetup.SERVICE);
        HomeKeyService.simulateConnect(preUnbindOld);
        check("r5_p2_pre_unbind_active_marker_present",
                preUnbindOld.sysPrefs.getBoolean("compat_service_active", false)
                && preUnbindOld.sysPrefs.getBoolean("compat_seen_present", false));
        SystemIntegration.refreshHomeService(preUnbindOld);
        // Process dies BEFORE onUnbind() runs! compat_service_active stays true on disk.
        Handler.clearDelayed();
        SystemIntegration.resetRebindStateForTest();
        HomeKeyService.resetForTest();
        Diagnostics.events.clear();
        MainActivity preUnbindRestart = new MainActivity();
        preUnbindRestart.sysPrefs = preUnbindOld.sysPrefs;
        preUnbindRestart.homePrefs = preUnbindOld.homePrefs;
        preUnbindRestart.pm = preUnbindOld.pm;
        preUnbindRestart.secureAuthorized = true;
        preUnbindRestart.defaultHome = new DefaultHomePanel(preUnbindRestart);
        preUnbindRestart.simulateRealColdStart(true);
        check("r5_p2_exit_before_unbind_real_startup_restores_without_false_reset",
                !HomeSetup.compatResetPending(preUnbindRestart)
                && !Diagnostics.events.contains("HOME_COMPAT_RESET_AFTER_STOP")
                && !preUnbindRestart.isPage("home_compat_reset")
                && !HomeSetup.paused(preUnbindRestart)
                && preUnbindRestart.sysPrefs.getBoolean("home_compat", false)
                && preUnbindRestart.sysPrefs.getBoolean("boot_home", false)
                && HomeSetup.serviceEnabled(preUnbindRestart)
                && Settings.Secure.strings.get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).contains(xiaoAi)
                && SystemIntegration.REBIND_STATE_WRITTEN.equals(preUnbindRestart.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, ""))
                && preUnbindRestart.sysPrefs.getInt("home_filter_revision", 0) == 1);
        HomeKeyService.simulateConnect(preUnbindRestart);
        check("r5_p2_exit_before_unbind_commits_revision_on_new_connect",
                preUnbindRestart.sysPrefs.getInt("home_filter_revision", 0) == SystemIntegration.HOME_FILTER_REVISION
                && preUnbindRestart.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, "").isEmpty()
                && Diagnostics.events.contains("HOME_SERVICE_REBOUND"));

        // 2B (P2): Process exits AFTER onUnbind() (compat_service_active=false on disk), same real startup order.
        MainActivity postUnbindOld = freshEnv();
        postUnbindOld.secureAuthorized = true;
        postUnbindOld.sysPrefs.edit()
                .putBoolean("home_compat", true).putBoolean("boot_home", true)
                .putInt("home_filter_revision", 1).commit();
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, xiaoAi + ":" + HomeSetup.SERVICE);
        HomeKeyService.simulateConnect(postUnbindOld);
        SystemIntegration.refreshHomeService(postUnbindOld);
        HomeKeyService.simulateUnbind(postUnbindOld);
        check("r5_p2_post_unbind_active_marker_cleared",
                !postUnbindOld.sysPrefs.getBoolean("compat_service_active", true));
        Handler.clearDelayed();
        SystemIntegration.resetRebindStateForTest();
        HomeKeyService.resetForTest();
        Diagnostics.events.clear();
        MainActivity postUnbindRestart = new MainActivity();
        postUnbindRestart.sysPrefs = postUnbindOld.sysPrefs;
        postUnbindRestart.homePrefs = postUnbindOld.homePrefs;
        postUnbindRestart.pm = postUnbindOld.pm;
        postUnbindRestart.secureAuthorized = true;
        postUnbindRestart.defaultHome = new DefaultHomePanel(postUnbindRestart);
        postUnbindRestart.simulateRealColdStart(true);
        check("r5_p2_exit_after_unbind_real_startup_restores_cleanly",
                !HomeSetup.compatResetPending(postUnbindRestart)
                && !HomeSetup.paused(postUnbindRestart)
                && postUnbindRestart.sysPrefs.getBoolean("home_compat", false)
                && postUnbindRestart.sysPrefs.getBoolean("boot_home", false)
                && HomeSetup.serviceEnabled(postUnbindRestart)
                && Settings.Secure.strings.get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).contains(xiaoAi)
                && SystemIntegration.REBIND_STATE_WRITTEN.equals(postUnbindRestart.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, ""))
                && postUnbindRestart.sysPrefs.getInt("home_filter_revision", 0) == 1);
        HomeKeyService.simulateConnect(postUnbindRestart);
        check("r5_p2_exit_after_unbind_commits_revision_on_new_connect",
                postUnbindRestart.sysPrefs.getInt("home_filter_revision", 0) == SystemIntegration.HOME_FILTER_REVISION
                && Diagnostics.events.contains("HOME_SERVICE_REBOUND"));

        // 2C (contrast): Real external abrupt stop (NOT our rebind) still triggers markCompatResetIfInterrupted.
        MainActivity realStopHost = freshEnv();
        realStopHost.secureAuthorized = true;
        realStopHost.sysPrefs.edit()
                .putBoolean("home_compat", true).putBoolean("boot_home", true)
                .putBoolean("compat_seen_present", true).putBoolean("compat_service_active", true)
                .putInt("home_filter_revision", SystemIntegration.HOME_FILTER_REVISION).commit();
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
        check("r5_p2_non_rebind_abrupt_stop_still_marks_compat_reset",
                HomeSetup.markCompatResetIfInterrupted(realStopHost)
                && HomeSetup.compatResetPending(realStopHost));

        // 3 (P2): Old connection stays bound past write-back (delayed onUnbind); stale connection MUST NOT
        // mark rebind complete until a brand-new connection arrives and applies the guard mode!
        MainActivity staleConnHost = freshEnv();
        staleConnHost.secureAuthorized = true;
        staleConnHost.sysPrefs.edit()
                .putBoolean("home_compat", true).putBoolean("boot_home", true)
                .putInt("home_filter_revision", 1).commit();
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, xiaoAi + ":" + HomeSetup.SERVICE);
        HomeKeyService.simulateConnect(staleConnHost);
        long oldGen = HomeKeyService.connectionGeneration;
        Diagnostics.events.clear();
        SystemIntegration.refreshHomeService(staleConnHost);
        // Old connection does NOT unbind before write-back at +310ms!
        SystemClock.elapsed += 310;
        Handler.runDue();
        check("r5_p2_stale_connection_cannot_complete_rebind_on_writeback",
                HomeSetup.serviceEnabled(staleConnHost)
                && HomeKeyService.connected
                && HomeKeyService.connectionGeneration == oldGen
                && staleConnHost.sysPrefs.getInt("home_filter_revision", 0) == 1
                && SystemIntegration.REBIND_STATE_WRITTEN.equals(staleConnHost.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, ""))
                && !SystemIntegration.isGuardModeReady(staleConnHost)
                && "已写回、等待连接".equals(SystemIntegration.rebindStatusLabel(staleConnHost))
                && Diagnostics.events.contains("HOME_SERVICE_REBIND_AWAITING_CONNECT")
                && !Diagnostics.events.contains("HOME_SERVICE_REBOUND"));
        // Even calling syncRuntimeKeyFilter & refreshHomeService again while still on oldGen must not complete!
        HomeKeyService.syncRuntimeKeyFilter(staleConnHost);
        SystemIntegration.refreshHomeService(staleConnHost);
        check("r5_p2_stale_connection_sync_and_refresh_still_await_new_connect",
                staleConnHost.sysPrefs.getInt("home_filter_revision", 0) == 1
                && SystemIntegration.REBIND_STATE_WRITTEN.equals(staleConnHost.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, ""))
                && !Diagnostics.events.contains("HOME_SERVICE_REBOUND"));
        // Delayed unbind of old connection, then brand-new connection arrives -> NOW marks complete!
        HomeKeyService.simulateUnbind(staleConnHost);
        HomeKeyService.simulateConnect(staleConnHost);
        check("r5_p2_new_connection_after_delayed_unbind_completes_rebind",
                HomeKeyService.connectionGeneration > oldGen
                && staleConnHost.sysPrefs.getInt("home_filter_revision", 0) == SystemIntegration.HOME_FILTER_REVISION
                && staleConnHost.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, "").isEmpty()
                && SystemIntegration.isGuardModeReady(staleConnHost)
                && "已连接并应用模式".equals(SystemIntegration.rebindStatusLabel(staleConnHost))
                && Diagnostics.events.contains("HOME_SERVICE_REBOUND"));

        // === Round 6 Final Upgrade Rebind Regressions ===
        // 1A (P1 Boot - global switch off during REBIND_STATE_REMOVED): immediate reboot MUST preserve
        // ACCESSIBILITY_ENABLED=0 and MUST NOT re-add Marco!
        MainActivity bootOffHost = freshEnv();
        bootOffHost.secureAuthorized = true;
        bootOffHost.sysPrefs.edit()
                .putBoolean("home_compat", true).putBoolean("boot_home", true)
                .putBoolean("compat_seen_present", true).putInt("home_filter_revision", 1).commit();
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, xiaoAi + ":" + HomeSetup.SERVICE);
        HomeKeyService.simulateConnect(bootOffHost);
        SystemIntegration.refreshHomeService(bootOffHost);
        // User turns global accessibility switch OFF in System Settings before rebind completes, then reboots!
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        Handler.clearDelayed();
        SystemIntegration.resetRebindStateForTest();
        HomeKeyService.resetForTest();
        Settings.Global.ints.put("boot_count", 4);
        HomeSetup.bootIdOverride = "boot-uuid-r6-1a";
        new BootReceiver().onReceive(bootOffHost, new Intent(Intent.ACTION_BOOT_COMPLETED));
        check("r6_p1_boot_respects_global_switch_off_during_removed_rebind",
                Settings.Secure.ints.get(Settings.Secure.ACCESSIBILITY_ENABLED) == 0
                && xiaoAi.equals(Settings.Secure.strings.get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
                && !HomeSetup.serviceEnabled(bootOffHost)
                && HomeSetup.paused(bootOffHost)
                && !bootOffHost.sysPrefs.getBoolean("home_compat", true)
                && !bootOffHost.sysPrefs.getBoolean("boot_home", true)
                && bootOffHost.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, "").isEmpty()
                && bootOffHost.startedActivities.isEmpty());

        // 1B (P1 Boot - Marco removed from list during REBIND_STATE_WRITTEN): immediate reboot MUST preserve
        // system state without re-adding Marco or flipping global switch back to 1!
        MainActivity bootWrittenRevoke = freshEnv();
        bootWrittenRevoke.secureAuthorized = true;
        bootWrittenRevoke.sysPrefs.edit()
                .putBoolean("home_compat", true).putBoolean("boot_home", true)
                .putInt("home_filter_revision", 1).commit();
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, xiaoAi + ":" + HomeSetup.SERVICE);
        HomeKeyService.simulateConnect(bootWrittenRevoke);
        SystemIntegration.refreshHomeService(bootWrittenRevoke);
        SystemClock.elapsed += 310;
        Handler.runDue();
        check("r6_p1_boot_written_precondition",
                SystemIntegration.REBIND_STATE_WRITTEN.equals(bootWrittenRevoke.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, "")));
        // User removes Marco and turns global switch OFF before new connection completes, then reboots!
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, xiaoAi);
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        Handler.clearDelayed();
        SystemIntegration.resetRebindStateForTest();
        HomeKeyService.resetForTest();
        Settings.Global.ints.put("boot_count", 5);
        HomeSetup.bootIdOverride = "boot-uuid-r6-1b";
        new BootReceiver().onReceive(bootWrittenRevoke, new Intent(Intent.ACTION_BOOT_COMPLETED));
        check("r6_p1_boot_respects_user_disable_during_written_rebind",
                Settings.Secure.ints.get(Settings.Secure.ACCESSIBILITY_ENABLED) == 0
                && xiaoAi.equals(Settings.Secure.strings.get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
                && !HomeSetup.serviceEnabled(bootWrittenRevoke)
                && HomeSetup.paused(bootWrittenRevoke)
                && !bootWrittenRevoke.sysPrefs.getBoolean("home_compat", true)
                && !bootWrittenRevoke.sysPrefs.getBoolean("boot_home", true)
                && bootWrittenRevoke.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, "").isEmpty());

        // 1C (P1 Boot - user removes Marco from partial-restore snapshot (Marco, 0) -> ("", 0) then reboots):
        MainActivity bootPartialRevoke = freshEnv();
        bootPartialRevoke.secureAuthorized = true;
        bootPartialRevoke.sysPrefs.edit()
                .putBoolean("home_compat", true).putBoolean("boot_home", true)
                .putInt("home_filter_revision", 1).commit();
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, HomeSetup.SERVICE);
        HomeKeyService.simulateConnect(bootPartialRevoke);
        SystemIntegration.refreshHomeService(bootPartialRevoke);
        Settings.Secure.failNextPutInts = 10;
        for (int i = 0; i < 4; i++) { SystemClock.elapsed += 310; Handler.runDue(); }
        Settings.Secure.failNextPutInts = 0;
        // User removes Marco from list -> ("", 0), then reboots!
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
        Handler.clearDelayed();
        SystemIntegration.resetRebindStateForTest();
        HomeKeyService.resetForTest();
        Settings.Global.ints.put("boot_count", 6);
        HomeSetup.bootIdOverride = "boot-uuid-r6-1c";
        new BootReceiver().onReceive(bootPartialRevoke, new Intent(Intent.ACTION_BOOT_COMPLETED));
        check("r6_p1_boot_respects_user_removal_from_partial_restore_snapshot",
                Settings.Secure.ints.get(Settings.Secure.ACCESSIBILITY_ENABLED) == 0
                && "".equals(Settings.Secure.strings.get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
                && !HomeSetup.serviceEnabled(bootPartialRevoke)
                && HomeSetup.paused(bootPartialRevoke)
                && !bootPartialRevoke.sysPrefs.getBoolean("home_compat", true)
                && !bootPartialRevoke.sysPrefs.getBoolean("boot_home", true));

        // 1D (P1 Boot CONTROL GROUP - NO external change during REBIND_STATE_REMOVED -> immediate reboot):
        // MUST restore Marco AND preserve XiaoAi!
        MainActivity bootControlHost = freshEnv();
        bootControlHost.secureAuthorized = true;
        bootControlHost.sysPrefs.edit()
                .putBoolean("home_compat", true).putBoolean("boot_home", true)
                .putInt("home_filter_revision", 1).commit();
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, xiaoAi + ":" + HomeSetup.SERVICE);
        HomeKeyService.simulateConnect(bootControlHost);
        SystemIntegration.refreshHomeService(bootControlHost);
        // NO external change; immediate reboot mid-rebind!
        Handler.clearDelayed();
        SystemIntegration.resetRebindStateForTest();
        HomeKeyService.resetForTest();
        Settings.Global.ints.put("boot_count", 7);
        HomeSetup.bootIdOverride = "boot-uuid-r6-1d";
        new BootReceiver().onReceive(bootControlHost, new Intent(Intent.ACTION_BOOT_COMPLETED));
        String bootControlServices = Settings.Secure.strings.get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        check("r6_p1_boot_control_without_external_change_restores_marco_and_preserves_xiaoai",
                Settings.Secure.ints.get(Settings.Secure.ACCESSIBILITY_ENABLED) == 1
                && HomeSetup.serviceEnabled(bootControlHost)
                && bootControlServices != null && bootControlServices.contains(xiaoAi) && bootControlServices.contains(HomeSetup.SERVICE)
                && !HomeSetup.paused(bootControlHost)
                && bootControlHost.sysPrefs.getBoolean("home_compat", false)
                && bootControlHost.sysPrefs.getBoolean("boot_home", false)
                && SystemIntegration.REBIND_STATE_WRITTEN.equals(bootControlHost.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, "")));
        HomeKeyService.simulateConnect(bootControlHost);
        check("r6_p1_boot_control_commits_revision_on_connect",
                bootControlHost.sysPrefs.getInt("home_filter_revision", 0) == SystemIntegration.HOME_FILTER_REVISION);

        // 1E (P1 Standby - global switch off during REBIND_STATE_REMOVED with compat_service_active=true retained):
        MainActivity standbyOffHost = freshEnv();
        standbyOffHost.secureAuthorized = true;
        standbyOffHost.sysPrefs.edit()
                .putBoolean("home_compat", true).putBoolean("boot_home", true)
                .putInt("home_filter_revision", 1).commit();
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, xiaoAi + ":" + HomeSetup.SERVICE);
        HomeKeyService.simulateConnect(standbyOffHost); // sets compat_service_active=true!
        SystemIntegration.refreshHomeService(standbyOffHost);
        // User turns global switch OFF before onUnbind; TV sleeps and standby recovery runs in fresh process!
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        Handler.clearDelayed();
        SystemIntegration.resetRebindStateForTest();
        HomeKeyService.resetForTest();
        standbyOffHost.powerManager.interactive = false;
        boolean standbyOffRestored = SystemIntegration.restoreCompatAfterStandbyKill(standbyOffHost);
        check("r6_p1_standby_respects_global_switch_off_with_retained_active_marker",
                !standbyOffRestored
                && Settings.Secure.ints.get(Settings.Secure.ACCESSIBILITY_ENABLED) == 0
                && xiaoAi.equals(Settings.Secure.strings.get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
                && !HomeSetup.serviceEnabled(standbyOffHost)
                && HomeSetup.paused(standbyOffHost)
                && !standbyOffHost.sysPrefs.getBoolean("home_compat", true)
                && !standbyOffHost.sysPrefs.getBoolean("boot_home", true)
                && standbyOffHost.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, "").isEmpty());

        // 1F (P1 Standby - Marco removed from list during REBIND_STATE_WRITTEN with compat_service_active=true):
        MainActivity standbyWrittenRevoke = freshEnv();
        standbyWrittenRevoke.secureAuthorized = true;
        standbyWrittenRevoke.sysPrefs.edit()
                .putBoolean("home_compat", true).putBoolean("boot_home", true)
                .putInt("home_filter_revision", 1).commit();
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, xiaoAi + ":" + HomeSetup.SERVICE);
        HomeKeyService.simulateConnect(standbyWrittenRevoke);
        SystemIntegration.refreshHomeService(standbyWrittenRevoke);
        SystemClock.elapsed += 310;
        Handler.runDue(); // now REBIND_STATE_WRITTEN while compat_service_active is still true
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, xiaoAi);
        Handler.clearDelayed();
        SystemIntegration.resetRebindStateForTest();
        HomeKeyService.resetForTest();
        standbyWrittenRevoke.powerManager.interactive = false;
        boolean standbyWrittenRestored = SystemIntegration.restoreCompatAfterStandbyKill(standbyWrittenRevoke);
        check("r6_p1_standby_respects_marco_removed_during_written_rebind",
                !standbyWrittenRestored
                && xiaoAi.equals(Settings.Secure.strings.get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
                && !HomeSetup.serviceEnabled(standbyWrittenRevoke)
                && HomeSetup.paused(standbyWrittenRevoke)
                && !standbyWrittenRevoke.sysPrefs.getBoolean("home_compat", true)
                && !standbyWrittenRevoke.sysPrefs.getBoolean("boot_home", true)
                && standbyWrittenRevoke.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, "").isEmpty());

        // 1G (P1 Standby CONTROL GROUP - NO external change during REBIND_STATE_REMOVED with compat_service_active=true):
        // MUST restore Marco AND preserve XiaoAi!
        MainActivity standbyControlHost = freshEnv();
        standbyControlHost.secureAuthorized = true;
        standbyControlHost.sysPrefs.edit()
                .putBoolean("home_compat", true).putBoolean("boot_home", true)
                .putInt("home_filter_revision", 1).commit();
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, xiaoAi + ":" + HomeSetup.SERVICE);
        HomeKeyService.simulateConnect(standbyControlHost);
        SystemIntegration.refreshHomeService(standbyControlHost);
        // NO external change; process restarts on standby while compat_service_active=true!
        Handler.clearDelayed();
        SystemIntegration.resetRebindStateForTest();
        HomeKeyService.resetForTest();
        standbyControlHost.powerManager.interactive = false;
        boolean standbyControlRestored = SystemIntegration.restoreCompatAfterStandbyKill(standbyControlHost);
        String standbyControlServices = Settings.Secure.strings.get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        check("r6_p1_standby_control_without_external_change_restores_marco_and_preserves_xiaoai",
                standbyControlRestored
                && Settings.Secure.ints.get(Settings.Secure.ACCESSIBILITY_ENABLED) == 1
                && HomeSetup.serviceEnabled(standbyControlHost)
                && standbyControlServices != null && standbyControlServices.contains(xiaoAi) && standbyControlServices.contains(HomeSetup.SERVICE)
                && !HomeSetup.paused(standbyControlHost)
                && standbyControlHost.sysPrefs.getBoolean("home_compat", false)
                && standbyControlHost.sysPrefs.getBoolean("boot_home", false)
                && SystemIntegration.REBIND_STATE_WRITTEN.equals(standbyControlHost.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, "")));
        HomeKeyService.simulateConnect(standbyControlHost);
        check("r6_p1_standby_control_commits_revision_on_connect",
                standbyControlHost.sysPrefs.getInt("home_filter_revision", 0) == SystemIntegration.HOME_FILTER_REVISION);

        // 1H (P1 Foreground direct recover/refresh check when external change occurred during rebind):
        MainActivity fgExternalHost = freshEnv();
        fgExternalHost.secureAuthorized = true;
        fgExternalHost.sysPrefs.edit()
                .putBoolean("home_compat", true).putBoolean("boot_home", true)
                .putInt("home_filter_revision", 1).commit();
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, xiaoAi + ":" + HomeSetup.SERVICE);
        HomeKeyService.simulateConnect(fgExternalHost);
        SystemIntegration.refreshHomeService(fgExternalHost);
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        SystemIntegration.refreshHomeService(fgExternalHost);
        SystemIntegration.recover(fgExternalHost);
        check("r6_p1_foreground_refresh_and_recover_respect_external_disable",
                Settings.Secure.ints.get(Settings.Secure.ACCESSIBILITY_ENABLED) == 0
                && xiaoAi.equals(Settings.Secure.strings.get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
                && HomeSetup.paused(fgExternalHost)
                && !fgExternalHost.sysPrefs.getBoolean("home_compat", true)
                && !fgExternalHost.sysPrefs.getBoolean("boot_home", true));

        // 2A (P2 Removal direction - putString("") succeeds, putInt(0) throws SecurityException):
        // Re-entering page preserves home_compat & boot_home and recovers cleanly!
        MainActivity secExRemoveHost = freshEnv();
        secExRemoveHost.secureAuthorized = true;
        secExRemoveHost.sysPrefs.edit()
                .putBoolean("home_compat", true).putBoolean("boot_home", true)
                .putInt("home_filter_revision", 1).commit();
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, HomeSetup.SERVICE);
        HomeKeyService.simulateConnect(secExRemoveHost);
        Settings.Secure.throwNextPutInts = 1;
        SystemIntegration.refreshHomeService(secExRemoveHost);
        check("r6_p2_removal_security_exception_keeps_actual_snapshot_and_rebind",
                !HomeSetup.serviceEnabled(secExRemoveHost)
                && "".equals(Settings.Secure.strings.get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
                && Settings.Secure.ints.get(Settings.Secure.ACCESSIBILITY_ENABLED) == 1
                && SystemIntegration.isOwnRebindInProgress(secExRemoveHost));
        SystemClock.elapsed += 50;
        secExRemoveHost.simulateOnResume(true);
        check("r6_p2_removal_security_exception_reentry_preserves_switches",
                !HomeSetup.paused(secExRemoveHost)
                && secExRemoveHost.sysPrefs.getBoolean("home_compat", false)
                && secExRemoveHost.sysPrefs.getBoolean("boot_home", false)
                && secExRemoveHost.sysPrefs.getInt("home_filter_revision", 0) == 1);
        SystemClock.elapsed += 260;
        Handler.runDue();
        check("r6_p2_removal_security_exception_recovers_and_awaits_new_connect",
                HomeSetup.serviceEnabled(secExRemoveHost)
                && !HomeSetup.paused(secExRemoveHost)
                && secExRemoveHost.sysPrefs.getBoolean("home_compat", false)
                && secExRemoveHost.sysPrefs.getBoolean("boot_home", false)
                && SystemIntegration.REBIND_STATE_WRITTEN.equals(secExRemoveHost.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, "")));
        HomeKeyService.simulateUnbind(secExRemoveHost);
        HomeKeyService.simulateConnect(secExRemoveHost);
        check("r6_p2_removal_security_exception_completes_on_new_connect",
                secExRemoveHost.sysPrefs.getInt("home_filter_revision", 0) == SystemIntegration.HOME_FILTER_REVISION);

        // 2B (P2 Restore direction - clean removal ("", 0), then restore putString(Marco) succeeds
        // while putInt(1) throws SecurityException across retries -> disk is (Marco, 0)):
        // Re-entering page preserves home_compat & boot_home, recovers once SecurityException clears,
        // and still respects external user removal!
        MainActivity secExRestoreHost = freshEnv();
        secExRestoreHost.secureAuthorized = true;
        secExRestoreHost.sysPrefs.edit()
                .putBoolean("home_compat", true).putBoolean("boot_home", true)
                .putInt("home_filter_revision", 1).commit();
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, HomeSetup.SERVICE);
        HomeKeyService.simulateConnect(secExRestoreHost);
        SystemIntegration.refreshHomeService(secExRestoreHost);
        HomeKeyService.simulateUnbind(secExRestoreHost);
        Settings.Secure.throwNextPutInts = 10;
        for (int i = 0; i < 4; i++) { SystemClock.elapsed += 310; Handler.runDue(); }
        check("r6_p2_restore_security_exception_records_partial_snapshot_and_keeps_switches",
                HomeSetup.SERVICE.equals(Settings.Secure.strings.get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
                && Settings.Secure.ints.get(Settings.Secure.ACCESSIBILITY_ENABLED) == 0
                && !HomeSetup.serviceEnabled(secExRestoreHost)
                && !HomeSetup.paused(secExRestoreHost)
                && secExRestoreHost.sysPrefs.getBoolean("home_compat", false)
                && secExRestoreHost.sysPrefs.getBoolean("boot_home", false)
                && SystemIntegration.isOwnRebindInProgress(secExRestoreHost)
                && Diagnostics.events.contains("SYSTEM_RECOVERY_FAILED")
                && Diagnostics.events.contains("HOME_SERVICE_REBIND_FAILED"));
        // Re-entering page while putInt still throws SecurityException preserves both switches!
        secExRestoreHost.simulateOnResume(true);
        check("r6_p2_restore_security_exception_reentry_while_throwing_preserves_switches",
                !HomeSetup.paused(secExRestoreHost)
                && secExRestoreHost.sysPrefs.getBoolean("home_compat", false)
                && secExRestoreHost.sysPrefs.getBoolean("boot_home", false));
        // Once SecurityException stops and user re-enters page, service restores to (Marco, 1) and completes on connect!
        Settings.Secure.throwNextPutInts = 0;
        SystemClock.elapsed += 310;
        secExRestoreHost.simulateOnResume(true);
        check("r6_p2_restore_security_exception_recovers_on_reentry_once_allowed",
                !HomeSetup.paused(secExRestoreHost)
                && secExRestoreHost.sysPrefs.getBoolean("home_compat", false)
                && secExRestoreHost.sysPrefs.getBoolean("boot_home", false)
                && HomeSetup.serviceEnabled(secExRestoreHost)
                && Settings.Secure.ints.get(Settings.Secure.ACCESSIBILITY_ENABLED) == 1
                && SystemIntegration.REBIND_STATE_WRITTEN.equals(secExRestoreHost.sysPrefs.getString(SystemIntegration.PREF_REBIND_STATE, "")));
        HomeKeyService.simulateConnect(secExRestoreHost);
        check("r6_p2_restore_security_exception_completes_on_new_connect",
                secExRestoreHost.sysPrefs.getInt("home_filter_revision", 0) == SystemIntegration.HOME_FILTER_REVISION);

        // 2B-revoke: While stuck in (Marco, 0) after restore SecurityException, user removes Marco -> pauses on re-entry!
        MainActivity secExRestoreRevoke = freshEnv();
        secExRestoreRevoke.secureAuthorized = true;
        secExRestoreRevoke.sysPrefs.edit()
                .putBoolean("home_compat", true).putBoolean("boot_home", true)
                .putInt("home_filter_revision", 1).commit();
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, HomeSetup.SERVICE);
        SystemIntegration.refreshHomeService(secExRestoreRevoke);
        Settings.Secure.throwNextPutInts = 10;
        for (int i = 0; i < 4; i++) { SystemClock.elapsed += 310; Handler.runDue(); }
        Settings.Secure.throwNextPutInts = 0;
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
        secExRestoreRevoke.simulateOnResume(true);
        check("r6_p2_restore_security_exception_user_revocation_pauses_cleanly",
                HomeSetup.paused(secExRestoreRevoke)
                && !secExRestoreRevoke.sysPrefs.getBoolean("home_compat", true)
                && !secExRestoreRevoke.sysPrefs.getBoolean("boot_home", true)
                && !HomeSetup.serviceEnabled(secExRestoreRevoke));
    }

    static void testDesktopModesAndOemExperiment() throws Exception {
        // 1. DefaultHomePanel.show() integrates all 4 clear entries + User Choice / Effective Mode / Fallback Reason!
        MainActivity host = freshEnv();
        setService(true, true);
        HomeKeyService.simulateConnect(host);
        host.sysPrefs.edit().putBoolean("home_compat", true).commit();
        host.defaultHome.show();
        TvDialog dlg = host.dialogs.active();
        check("feat_default_home_has_four_clear_entries_and_status_lines",
                dlg.items.length == 6
                && dlg.items[1].contains("系统默认桌面")
                && dlg.items[2].contains("窗口回拉（兼容模式")
                && dlg.items[3].contains("快速 Home")
                && dlg.items[4].contains("原厂按键")
                && dlg.message.contains("用户选择")
                && dlg.message.contains("当前生效")
                && dlg.message.contains("降级原因"));

        // Selecting 快速 Home without bridge shows explicit tap-then-hold instruction + switch to 窗口回拉!
        dlg.clickItem(3);
        TvDialog fastDlg = host.dialogs.active();
        check("feat_fast_home_no_bridge_shows_tap_then_hold_and_window_fallback",
                "home_fast_mode_confirm".equals(fastDlg.pageKey())
                && fastDlg.message.contains("先点一下音量，松手后再长按")
                && fastDlg.items[1].contains("窗口回拉"));

        // 2. Selecting NATIVE_KEYS_ONLY or disabling guard survives reboot, upgrade, standby wake, and TaskBridge reconnect!
        MainActivity nativeKeysHost = freshEnv();
        nativeKeysHost.secureAuthorized = true;
        nativeKeysHost.sysPrefs.edit().putBoolean("home_compat", true).putBoolean("boot_home", true)
                .putInt("home_filter_revision", 1).commit();
        setService(true, true);
        HomeKeyService.simulateConnect(nativeKeysHost);
        SystemIntegration.setGuardMode(nativeKeysHost, HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY);
        // Upgrade broadcast
        new BootReceiver().onReceive(nativeKeysHost, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        SystemClock.elapsed += 350; Handler.runDue();
        // Reboot broadcast
        Settings.Global.ints.put("boot_count", 4);
        HomeSetup.bootIdOverride = "boot-native-keys";
        new BootReceiver().onReceive(nativeKeysHost, new Intent(Intent.ACTION_BOOT_COMPLETED));
        // Standby wake
        nativeKeysHost.powerManager.interactive = false;
        SystemIntegration.restoreCompatAfterStandbyKill(nativeKeysHost);
        nativeKeysHost.powerManager.interactive = true;
        // TaskBridge reconnect
        final List<JSONObject> nkConfigs = Collections.synchronizedList(new ArrayList<>());
        IBinder nkBridge = new IBinder() {
            @Override public boolean isBinderAlive() { return true; }
            @Override public void linkToDeath(DeathRecipient r, int flags) { }
            @Override public boolean transact(int code, Parcel data, Parcel reply, int flags) {
                data.readInterfaceToken();
                try {
                    JSONObject req = new JSONObject(data.readString());
                    if ("configure".equals(req.optString("action"))) nkConfigs.add(req);
                    reply.writeNoException();
                    reply.writeString(new JSONObject().put("ok", true).put("volumeAssistActive", false).toString());
                    return true;
                } catch (Exception e) { throw new RuntimeException(e); }
            }
        };
        TaskBridgeClient.attach(nativeKeysHost, nkBridge);
        TaskBridgeClient.configure(nativeKeysHost);
        check("feat_native_keys_only_preserved_across_upgrade_reboot_standby_bridge",
                SystemIntegration.guardMode(nativeKeysHost) == HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY
                && !HomeKeyService.runtimeKeyFilterRequested
                && !TaskBridgeClient.shouldEnableWake(nativeKeysHost)
                && !TaskBridgeClient.shouldEnableVolumeAssist(nativeKeysHost)
                && !nkConfigs.isEmpty()
                && !nkConfigs.get(nkConfigs.size() - 1).optBoolean("wake", true)
                && !nkConfigs.get(nkConfigs.size() - 1).optBoolean("volumeAssist", true));

        // Guard disabled (paused / home_compat=false) also preserved across upgrade, reboot, standby, bridge reconnect!
        MainActivity offHost = freshEnv();
        offHost.secureAuthorized = true;
        SystemIntegration.disableHomeCompat(offHost);
        new BootReceiver().onReceive(offHost, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        Settings.Global.ints.put("boot_count", 5);
        HomeSetup.bootIdOverride = "boot-off-guard";
        new BootReceiver().onReceive(offHost, new Intent(Intent.ACTION_BOOT_COMPLETED));
        offHost.powerManager.interactive = false;
        SystemIntegration.restoreCompatAfterStandbyKill(offHost);
        TaskBridgeClient.configure(offHost);
        check("feat_disabled_guard_preserved_across_upgrade_reboot_standby_bridge",
                HomeSetup.paused(offHost)
                && !offHost.sysPrefs.getBoolean("home_compat", true)
                && !HomeSetup.serviceEnabled(offHost)
                && !TaskBridgeClient.shouldEnableWake(offHost)
                && !TaskBridgeClient.shouldEnableVolumeAssist(offHost));
        TaskBridgeClient.resetForTest();

        // 3. Standard-only OEM launcher disable experiment gating, auto-rollback on FallbackHome & foreground failure, and restore!
        List<OemLauncherExperimentPolicy.VerifiedDevice> testAllowlist = Collections.singletonList(
                new OemLauncherExperimentPolicy.VerifiedDevice("TestVerifiedTV", "1.0.0", "com.oem.tvhome", "test verified"));

        // 3a. MiTV-ASTP0 / 22.8.30.1385 blocked cleanly with zero pm disable-user commands!
        MainActivity astp0 = freshEnv();
        List<String> astp0Cmds = new ArrayList<>();
        HomeShellSetup astp0Shell = new HomeShellSetup(cmd -> { astp0Cmds.add(cmd); return "id -u".equals(cmd) ? "2000" : ""; }, 0);
        astp0Shell.authenticate();
        String astp0Res = SystemIntegration.executeOemExperimentDisable(astp0, astp0Shell, 0, "MiTV-ASTP0", "22.8.30.1385", testAllowlist, true);
        check("feat_oem_exp_blocks_mitv_astp0_1385",
                astp0Res.contains("已阻止") && astp0Res.contains("FallbackHome")
                && astp0Cmds.stream().noneMatch(c -> c.contains("disable-user")));

        // 3b. Restricted edition completely blocked even if model/firmware in allowlist!
        MainActivity restHost = freshEnv();
        BuildEdition.RESTRICTED = true;
        List<String> restCmds = new ArrayList<>();
        HomeShellSetup restShell = new HomeShellSetup(cmd -> { restCmds.add(cmd); return "id -u".equals(cmd) ? "2000" : ""; }, 0);
        restShell.authenticate();
        String restRes = SystemIntegration.executeOemExperimentDisable(restHost, restShell, 0, "TestVerifiedTV", "1.0.0", testAllowlist, true);
        check("feat_oem_exp_blocks_restricted_edition",
                restRes.contains("受限兼容版") && restCmds.stream().noneMatch(c -> c.contains("disable-user")));
        BuildEdition.RESTRICTED = false;

        // 3c. Verified device whose readback falls to FallbackHome -> auto-rollbacks with exact prior_state (--user) and verifies OEM usable!
        android.os.Process.uid = 10059; // User 0
        MainActivity fbHost = freshEnv();
        fbHost.pm.homes.clear();
        ResolveInfo oemHome = homeEntry("com.oem.tvhome", "com.oem.tvhome.Main", true, true, true, true);
        fbHost.pm.homes.add(oemHome);
        fbHost.pm.homes.add(homeEntry("com.pilipala.tvhome", "com.pilipala.tvhome.HomeActivity", true, true, true, false));
        fbHost.pm.currentHome = "com.oem.tvhome/com.oem.tvhome.Main";
        fbHost.pm.appEnabledStates.put("com.oem.tvhome", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
        List<String> fbCmds = new ArrayList<>();
        HomeShellSetup fbShell = new HomeShellSetup(cmd -> {
            fbCmds.add(cmd);
            if ("id -u".equals(cmd)) return "2000";
            if ("cmd package help".equals(cmd)) return "  set-home-activity TARGET";
            if (cmd.contains("pm disable-user")) {
                oemHome.activityInfo.applicationInfo.enabled = false;
                fbHost.pm.appEnabledStates.put("com.oem.tvhome", PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER);
                fbHost.pm.currentHome = "com.android.tv.settings/com.android.tv.settings.system.FallbackHome";
            }
            if (cmd.contains("pm default-state --user 0 'com.oem.tvhome'")) {
                oemHome.activityInfo.applicationInfo.enabled = true;
                fbHost.pm.appEnabledStates.put("com.oem.tvhome", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
            }
            if (cmd.contains("set-home-activity --user 0 'com.oem.tvhome/com.oem.tvhome.Main'")) {
                fbHost.pm.currentHome = "com.oem.tvhome/com.oem.tvhome.Main";
            }
            return "";
        }, 0);
        fbShell.authenticate();
        Diagnostics.events.clear();
        String fbRes = SystemIntegration.executeOemExperimentDisable(fbHost, fbShell, 0, "TestVerifiedTV", "1.0.0", testAllowlist, true);
        check("feat_oem_exp_fallback_home_auto_rolls_back_and_verifies_oem_usable",
                fbRes.contains("已自动恢复原厂桌面")
                && fbCmds.stream().anyMatch(c -> c.equals("pm default-state --user 0 'com.oem.tvhome'"))
                && fbCmds.stream().noneMatch(c -> c.contains("|| pm enable"))
                && oemHome.activityInfo.applicationInfo.enabled
                && "com.oem.tvhome/com.oem.tvhome.Main".equals(fbHost.pm.currentHome)
                && !SystemIntegration.hasDisabledOemExperiment(fbHost)
                && Diagnostics.events.contains("OEM_EXP_AUTO_ROLLBACK_OK"));

        // 3d. Verified device where Marco resolves as HOME AND can foreground -> succeeds, stops extra interception, and explicit Restore restores exact state & HOME!
        MainActivity okHost = freshEnv();
        okHost.pm.homes.clear();
        ResolveInfo okOem = homeEntry("com.oem.tvhome", "com.oem.tvhome.Main", true, true, true, true);
        okHost.pm.homes.add(okOem);
        okHost.pm.homes.add(homeEntry("com.pilipala.tvhome", "com.pilipala.tvhome.HomeActivity", true, true, true, false));
        okHost.pm.currentHome = "com.oem.tvhome/com.oem.tvhome.Main";
        okHost.pm.appEnabledStates.put("com.oem.tvhome", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
        okHost.sysPrefs.edit().putBoolean("home_compat", true).commit();
        SystemIntegration.setGuardMode(okHost, HomeSetupPolicy.GuardMode.KEY_FILTER);
        HomeKeyService.simulateConnect(okHost);
        List<String> okCmds = new ArrayList<>();
        HomeShellSetup okShell = new HomeShellSetup(cmd -> {
            okCmds.add(cmd);
            if ("id -u".equals(cmd)) return "2000";
            if ("cmd package help".equals(cmd)) return "  set-home-activity TARGET";
            if (cmd.contains("pm disable-user")) {
                okOem.activityInfo.applicationInfo.enabled = false;
                okHost.pm.appEnabledStates.put("com.oem.tvhome", PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER);
                okHost.pm.currentHome = "com.pilipala.tvhome/com.pilipala.tvhome.HomeActivity";
            }
            if (cmd.contains("pm default-state --user 0 'com.oem.tvhome'")) {
                okOem.activityInfo.applicationInfo.enabled = true;
                okHost.pm.appEnabledStates.put("com.oem.tvhome", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
            }
            if (cmd.contains("set-home-activity --user 0 'com.oem.tvhome/com.oem.tvhome.Main'")) {
                okHost.pm.currentHome = "com.oem.tvhome/com.oem.tvhome.Main";
            }
            return "";
        }, 0);
        okShell.authenticate();
        Diagnostics.events.clear();
        String okRes = SystemIntegration.executeOemExperimentDisable(okHost, okShell, 0, "TestVerifiedTV", "1.0.0", testAllowlist, true);
        check("feat_oem_exp_verified_success_stops_key_filter_and_persists_restore_snapshot",
                okRes.contains("已停用原厂桌面")
                && SystemIntegration.homeIsOurs(okHost)
                && !HomeKeyService.runtimeKeyFilterRequested
                && SystemIntegration.hasDisabledOemExperiment(okHost)
                && Diagnostics.events.contains("OEM_EXP_DISABLE_VERIFIED"));
        String restoredRes = SystemIntegration.executeOemExperimentRestore(okHost, okShell);
        check("feat_oem_exp_explicit_restore_reenables_oem_and_clears_snapshot",
                restoredRes.contains("已按快照恢复原厂桌面")
                && okCmds.stream().anyMatch(c -> c.equals("pm default-state --user 0 'com.oem.tvhome'"))
                && okCmds.stream().noneMatch(c -> c.contains("|| pm enable"))
                && okOem.activityInfo.applicationInfo.enabled
                && "com.oem.tvhome/com.oem.tvhome.Main".equals(okHost.pm.currentHome)
                && !SystemIntegration.hasDisabledOemExperiment(okHost)
                && Diagnostics.events.contains("OEM_EXP_RESTORED"));

        // =========================================================================
        // Codex Rework Regressions: OEM restore safety/threading & Mode switches
        // =========================================================================
        // Codex #3A: No valid current-user snapshot (current HOME is 3rd-party launcher) ->
        // ZERO shell writes, ZERO ADB connects, and UI hides "恢复原厂桌面"!
        MainActivity noSnapHost = freshEnv();
        noSnapHost.pm.homes.clear();
        noSnapHost.pm.homes.add(homeEntry("com.third.launcher", "com.third.launcher.Home", true, true, true, false));
        noSnapHost.pm.homes.add(homeEntry("com.pilipala.tvhome", "com.pilipala.tvhome.HomeActivity", true, true, true, false));
        noSnapHost.pm.currentHome = "com.third.launcher/com.third.launcher.Home";
        noSnapHost.defaultHome.oemExperiment();
        boolean oemDialogHasRestore = false;
        for (String it : noSnapHost.dialogs.active().items) if (it.contains("恢复原厂桌面")) oemDialogHasRestore = true;
        noSnapHost.defaultHome.help();
        boolean helpDialogHasRestore = false;
        for (String it : noSnapHost.dialogs.active().items) if (it.contains("恢复原厂桌面")) helpDialogHasRestore = true;
        List<String> noSnapCmds = new ArrayList<>();
        HomeShellSetup noSnapShell = new HomeShellSetup(cmd -> { noSnapCmds.add(cmd); return "id -u".equals(cmd) ? "2000" : ""; }, 0);
        noSnapShell.authenticate();
        noSnapCmds.clear();
        String noSnapRes = SystemIntegration.executeOemExperimentRestore(noSnapHost, noSnapShell, 0);
        final AtomicInteger noSnapConnects = new AtomicInteger();
        final CountDownLatch noSnapLatch = new CountDownLatch(1);
        final String[] noSnapAuthRes = {null};
        LocalHomeAuthorizer noSnapAuth = new LocalHomeAuthorizer(noSnapHost, () -> new LocalHomeAuthorizer.OemShellSession() {
            @Override public HomeShellSetup connect(int userId) { noSnapConnects.incrementAndGet(); throw new AssertionError("must not connect"); }
            @Override public void close() {}
        }, OemLauncherExperimentPolicy.VERIFIED_ALLOWLIST);
        noSnapAuth.restoreOemExperiment(res -> { noSnapAuthRes[0] = res; noSnapLatch.countDown(); });
        check("codex_3a_no_snapshot_zero_writes_zero_adb_and_no_restore_button",
                !oemDialogHasRestore
                && !helpDialogHasRestore
                && noSnapCmds.isEmpty()
                && noSnapRes.contains("未执行任何修改")
                && noSnapLatch.await(5, TimeUnit.SECONDS)
                && noSnapConnects.get() == 0
                && noSnapAuthRes[0] != null && noSnapAuthRes[0].contains("未执行任何修改"));

        // Wrong user ID snapshot (saved for user 0, restore invoked as user 10) -> ZERO writes!
        MainActivity wrongUserHost = freshEnv();
        SystemIntegration.saveOemExperimentSnapshot(wrongUserHost, 0, "com.oem.tvhome/com.oem.tvhome.Main",
                "com.oem.tvhome", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
        List<String> wrongUserCmds = new ArrayList<>();
        HomeShellSetup wrongUserShell = new HomeShellSetup(cmd -> { wrongUserCmds.add(cmd); return "id -u".equals(cmd) ? "2000" : ""; }, 10);
        wrongUserShell.authenticate();
        wrongUserCmds.clear();
        String wrongUserRes = SystemIntegration.executeOemExperimentRestore(wrongUserHost, wrongUserShell, 10);
        check("codex_3a_wrong_user_snapshot_zero_writes_and_preserves_user0_snapshot",
                wrongUserCmds.isEmpty()
                && wrongUserRes.contains("未执行任何修改")
                && SystemIntegration.hasValidOemExperimentSnapshot(wrongUserHost, 0)
                && !SystemIntegration.hasValidOemExperimentSnapshot(wrongUserHost, 10));

        // Codex #3B: Valid snapshot exists, but after restore system HOME still points to Marco OR another 3rd-party launcher ->
        // MUST report failure AND KEEP snapshot!
        MainActivity stuckHomeHost = freshEnv();
        stuckHomeHost.pm.homes.clear();
        ResolveInfo stuckOem = homeEntry("com.oem.tvhome", "com.oem.tvhome.Main", true, true, true, true);
        stuckHomeHost.pm.homes.add(stuckOem);
        stuckHomeHost.pm.homes.add(homeEntry("com.other.launcher", "com.other.launcher.Home", true, true, true, false));
        stuckHomeHost.pm.homes.add(homeEntry("com.pilipala.tvhome", "com.pilipala.tvhome.HomeActivity", true, true, true, false));
        stuckHomeHost.pm.appEnabledStates.put("com.oem.tvhome", PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER);
        stuckHomeHost.pm.currentHome = "com.pilipala.tvhome/com.pilipala.tvhome.HomeActivity";
        SystemIntegration.saveOemExperimentSnapshot(stuckHomeHost, 0, "com.oem.tvhome/com.oem.tvhome.Main",
                "com.oem.tvhome", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
        HomeShellSetup stuckShell = new HomeShellSetup(cmd -> {
            if ("id -u".equals(cmd)) return "2000";
            if ("cmd package help".equals(cmd)) return "  set-home-activity TARGET";
            if (cmd.contains("pm default-state --user 0 'com.oem.tvhome'")) {
                stuckOem.activityInfo.applicationInfo.enabled = true;
                stuckHomeHost.pm.appEnabledStates.put("com.oem.tvhome", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
            }
            // Deliberately keep currentHome unchanged (Marco, then other 3rd-party)!
            return "";
        }, 0);
        stuckShell.authenticate();
        String stuckMarcoRes = SystemIntegration.executeOemExperimentRestore(stuckHomeHost, stuckShell, 0);
        check("codex_3b_restore_stuck_on_marco_fails_and_keeps_snapshot",
                stuckMarcoRes.contains("未通过核验")
                && stuckMarcoRes.contains("快照仍保留")
                && SystemIntegration.hasValidOemExperimentSnapshot(stuckHomeHost, 0));
        stuckHomeHost.pm.currentHome = "com.other.launcher/com.other.launcher.Home";
        String stuckOtherRes = SystemIntegration.executeOemExperimentRestore(stuckHomeHost, stuckShell, 0);
        check("codex_3b_restore_stuck_on_third_party_fails_and_keeps_snapshot",
                stuckOtherRes.contains("未通过核验")
                && SystemIntegration.hasValidOemExperimentSnapshot(stuckHomeHost, 0));
        // Even if currentHome matches priorHome, wrong package enabled state (ENABLED=1 instead of DEFAULT=0) fails & keeps snapshot!
        stuckHomeHost.pm.currentHome = "com.oem.tvhome/com.oem.tvhome.Main";
        stuckHomeHost.pm.appEnabledStates.put("com.oem.tvhome", PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        HomeShellSetup wrongStateShell = new HomeShellSetup(cmd -> {
            if ("id -u".equals(cmd)) return "2000";
            if ("cmd package help".equals(cmd)) return "  set-home-activity TARGET";
            return "";
        }, 0);
        wrongStateShell.authenticate();
        String wrongStateRes = SystemIntegration.executeOemExperimentRestore(stuckHomeHost, wrongStateShell, 0);
        check("codex_3b_restore_mismatched_prior_state_fails_and_keeps_snapshot",
                wrongStateRes.contains("未通过核验")
                && SystemIntegration.hasValidOemExperimentSnapshot(stuckHomeHost, 0));

        // Codex #3D: LocalHomeAuthorizer runs ALL ADB shell I/O OFF main thread (slow response + mid-flight cancel)!
        final Thread testMainThread = Thread.currentThread();
        MainActivity slowHost = freshEnv();
        slowHost.pm.homes.clear();
        ResolveInfo slowOem = homeEntry("com.oem.tvhome", "com.oem.tvhome.Main", true, true, true, true);
        slowHost.pm.homes.add(slowOem);
        slowHost.pm.appEnabledStates.put("com.oem.tvhome", PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER);
        slowHost.pm.currentHome = "com.pilipala.tvhome/com.pilipala.tvhome.HomeActivity";
        SystemIntegration.saveOemExperimentSnapshot(slowHost, 0, "com.oem.tvhome/com.oem.tvhome.Main",
                "com.oem.tvhome", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
        final boolean[] sawMainThreadInShell = {false};
        final CountDownLatch slowDone = new CountDownLatch(1);
        final String[] slowOutcome = {null};
        LocalHomeAuthorizer slowAuth = new LocalHomeAuthorizer(slowHost, () -> new LocalHomeAuthorizer.OemShellSession() {
            @Override public HomeShellSetup connect(int userId) throws Exception {
                if (Handler.inMainPost.get() || Thread.currentThread() == testMainThread) sawMainThreadInShell[0] = true;
                HomeShellSetup s = new HomeShellSetup(cmd -> {
                    if (Handler.inMainPost.get() || Thread.currentThread() == testMainThread) sawMainThreadInShell[0] = true;
                    if ("id -u".equals(cmd)) return "2000";
                    if ("cmd package help".equals(cmd)) return "  set-home-activity TARGET";
                    Thread.sleep(120); // simulate slow ADB shell response
                    if (cmd.contains("pm default-state --user 0 'com.oem.tvhome'")) {
                        slowOem.activityInfo.applicationInfo.enabled = true;
                        slowHost.pm.appEnabledStates.put("com.oem.tvhome", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
                    }
                    if (cmd.contains("set-home-activity --user 0 'com.oem.tvhome/com.oem.tvhome.Main'")) {
                        slowHost.pm.currentHome = "com.oem.tvhome/com.oem.tvhome.Main";
                    }
                    return "";
                }, userId);
                s.authenticate();
                return s;
            }
            @Override public void close() {}
        }, OemLauncherExperimentPolicy.VERIFIED_ALLOWLIST);
        slowAuth.restoreOemExperiment(res -> { slowOutcome[0] = res; slowDone.countDown(); });
        check("codex_3d_slow_oem_restore_runs_off_main_thread_and_succeeds",
                slowDone.await(5, TimeUnit.SECONDS)
                && !sawMainThreadInShell[0]
                && slowOutcome[0] != null && slowOutcome[0].contains("已按快照恢复原厂桌面")
                && !SystemIntegration.hasValidOemExperimentSnapshot(slowHost, 0));

        // Mid-flight cancellation during blocking ADB shell closes session, aborts remaining commands, keeps snapshot, suppresses callback!
        MainActivity cancelHost = freshEnv();
        SystemIntegration.saveOemExperimentSnapshot(cancelHost, 0, "com.oem.tvhome/com.oem.tvhome.Main",
                "com.oem.tvhome", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
        final CountDownLatch enteredShell = new CountDownLatch(1);
        final CountDownLatch closedLatch = new CountDownLatch(1);
        final AtomicInteger selectHomeCalls = new AtomicInteger();
        final AtomicInteger cancelCallbacks = new AtomicInteger();
        LocalHomeAuthorizer cancelAuth = new LocalHomeAuthorizer(cancelHost, () -> new LocalHomeAuthorizer.OemShellSession() {
            @Override public HomeShellSetup connect(int userId) throws Exception {
                HomeShellSetup s = new HomeShellSetup(cmd -> {
                    if ("id -u".equals(cmd)) return "2000";
                    if (cmd.contains("pm default-state")) {
                        enteredShell.countDown();
                        try { Thread.sleep(5000); } catch (InterruptedException ie) { throw new java.io.IOException("cancelled"); }
                    }
                    if (cmd.contains("set-home-activity")) selectHomeCalls.incrementAndGet();
                    return "";
                }, userId);
                s.authenticate();
                return s;
            }
            @Override public void close() { closedLatch.countDown(); }
        }, OemLauncherExperimentPolicy.VERIFIED_ALLOWLIST);
        cancelAuth.restoreOemExperiment(res -> cancelCallbacks.incrementAndGet());
        check("codex_3d_mid_flight_cancel_closes_session_keeps_snapshot_no_callback",
                enteredShell.await(3, TimeUnit.SECONDS));
        cancelAuth.cancel();
        check("codex_3d_mid_flight_cancel_verified",
                closedLatch.await(3, TimeUnit.SECONDS)
                && selectHomeCalls.get() == 0
                && cancelCallbacks.get() == 0
                && SystemIntegration.hasValidOemExperimentSnapshot(cancelHost, 0));

        // Codex #4A: Previously selected KEY_FILTER or NATIVE_KEYS_ONLY -> native HOME setup fails ->
        // clicking "改用 Home 兼容（无需调试）" switches runtime mode to WINDOW_PULLBACK while preserving NATIVE_HOME choice!
        for (HomeSetupPolicy.GuardMode prior : new HomeSetupPolicy.GuardMode[]{
                HomeSetupPolicy.GuardMode.KEY_FILTER, HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY}) {
            MainActivity modeSwitchHost = freshEnv();
            Build.VERSION.SDK_INT = 29;
            modeSwitchHost.roleAvailable = true;
            modeSwitchHost.secureAuthorized = true;
            SystemIntegration.setGuardMode(modeSwitchHost, prior);
            modeSwitchHost.defaultHome.show();
            modeSwitchHost.dialogs.active().clickItem(1); // "系统默认桌面"
            modeSwitchHost.defaultHome.onPickerResult(Activity.RESULT_CANCELED);
            modeSwitchHost.defaultHome.resumed();
            check("codex_4a_picker_failed_page_open_" + prior.name(),
                    modeSwitchHost.isPage("home_picker_next"));
            modeSwitchHost.dialogs.active().clickItem(0); // "改用 Home 兼容（无需调试）"
            modeSwitchHost.dialogs.active().clickPositive(); // confirm compat consent
            HomeKeyService.simulateConnect(modeSwitchHost);
            HomeSetupPolicy.DesktopState switchedState = SystemIntegration.desktopState(modeSwitchHost);
            check("codex_4a_compat_fallback_enters_window_pullback_from_" + prior.name(),
                    modeSwitchHost.sysPrefs.getBoolean("home_compat", false)
                    && SystemIntegration.guardMode(modeSwitchHost) == HomeSetupPolicy.GuardMode.WINDOW_PULLBACK
                    && SystemIntegration.desktopChoice(modeSwitchHost) == HomeSetupPolicy.DesktopChoice.NATIVE_HOME
                    && !HomeKeyService.runtimeKeyFilterRequested
                    && switchedState.effectiveModeLabel.contains("窗口回拉")
                    && HomeSetupPolicy.shouldInterceptHomeCompat(true, false, false, SystemIntegration.guardMode(modeSwitchHost)));
        }
        // Explicit "快速 Home" entry still activates KEY_FILTER!
        MainActivity fastExplicit = freshEnv();
        fastExplicit.secureAuthorized = true;
        fastExplicit.defaultHome.show();
        fastExplicit.dialogs.active().clickItem(3); // "快速 Home"
        fastExplicit.dialogs.active().clickItem(0); // "启用快速 Home"
        fastExplicit.dialogs.active().clickPositive(); // confirm compat consent
        check("codex_4a_explicit_fast_home_still_activates_key_filter",
                fastExplicit.sysPrefs.getBoolean("home_compat", false)
                && SystemIntegration.guardMode(fastExplicit) == HomeSetupPolicy.GuardMode.KEY_FILTER);

        // Codex #4B: Native HOME is ALREADY Marco + user pauses guard ->
        // DefaultHomePanel & desktopState STILL display system HOME = Marco TV (and guard paused), NOT OEM launcher!
        MainActivity nativePausedHost = freshEnv();
        nativePausedHost.pm.currentHome = "com.pilipala.tvhome/com.pilipala.tvhome.HomeActivity";
        HomeSetup.pause(nativePausedHost, "你已暂停自动恢复。");
        HomeSetupPolicy.DesktopState npState = SystemIntegration.desktopState(nativePausedHost);
        nativePausedHost.defaultHome.show();
        check("codex_4b_native_marco_paused_displays_system_default_marco",
                npState.effectiveModeLabel.contains("系统默认桌面")
                && npState.effectiveModeLabel.contains("Marco TV")
                && npState.effectiveModeLabel.contains("已暂停")
                && !npState.effectiveModeLabel.contains("原厂桌面")
                && nativePausedHost.dialogs.active().message.contains("系统默认桌面")
                && nativePausedHost.dialogs.active().items[1].contains("✓"));

        // =========================================================================
        // Codex 82c1c8d Rework Regressions:
        // =========================================================================
        // 3C. No-picker -> LocalHomeAuthorizer auto-setup -> OEM keeps HOME ->
        // switches prior KEY_FILTER AND prior NATIVE_KEYS_ONLY to WINDOW_PULLBACK while preserving NATIVE_HOME choice!
        for (HomeSetupPolicy.GuardMode prior : new HomeSetupPolicy.GuardMode[]{
                HomeSetupPolicy.GuardMode.KEY_FILTER, HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY}) {
            MainActivity autoFbHost = freshEnv();
            autoFbHost.roleAvailable = false;
            autoFbHost.pm.actions.remove(Settings.ACTION_HOME_SETTINGS);
            autoFbHost.pm.actions.remove(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            autoFbHost.secureAuthorized = true;
            LocalAdbWire.selectSucceeds = false;
            SystemIntegration.setGuardMode(autoFbHost, prior);
            autoFbHost.dialogs.armResultLatch();
            autoFbHost.defaultHome.show();
            autoFbHost.dialogs.active().clickItem(1); // "系统默认桌面" -> no picker -> home_local_consent
            check("codex82_3c_no_picker_enters_local_consent_" + prior.name(),
                    autoFbHost.isPage("home_local_consent"));
            autoFbHost.dialogs.active().clickPositive();
            check("codex82_3c_auto_setup_completes_" + prior.name(), autoFbHost.dialogs.awaitResult());
            HomeKeyService.simulateConnect(autoFbHost);
            HomeSetupPolicy.DesktopState afterAutoFb = SystemIntegration.desktopState(autoFbHost);
            check("codex82_3c_no_picker_auto_setup_fail_enters_window_pullback_from_" + prior.name(),
                    autoFbHost.sysPrefs.getBoolean("home_compat", false)
                    && SystemIntegration.desktopChoice(autoFbHost) == HomeSetupPolicy.DesktopChoice.NATIVE_HOME
                    && SystemIntegration.guardMode(autoFbHost) == HomeSetupPolicy.GuardMode.WINDOW_PULLBACK
                    && !HomeKeyService.runtimeKeyFilterRequested
                    && afterAutoFb.effectiveModeLabel.contains("窗口回拉")
                    && HomeSetupPolicy.shouldInterceptHomeCompat(true, false, false, SystemIntegration.guardMode(autoFbHost)));
        }

        // Bonus UI: Open DefaultHomePanel live-refreshes BOTH message AND item labels when TaskBridge disconnects!
        TaskBridgeClient.resetForTest();
        MainActivity livePanelHost = freshEnv();
        livePanelHost.sysPrefs.edit().putBoolean("home_compat", true).commit();
        setService(true, true);
        SystemIntegration.setGuardMode(livePanelHost, HomeSetupPolicy.GuardMode.KEY_FILTER);
        HomeKeyService.simulateConnect(livePanelHost);
        final String[] liveReply = {
                "{\"ok\":true,\"volumeProtocolVersion\":2,\"volumeAssist\":true,\"volumeAssistActive\":true,\"volumeAudioReady\":true,\"volumeInputDeviceCount\":1,\"volumeAssistReason\":\"ready\"}"
        };
        IBinder liveBridge = new IBinder() {
            @Override public boolean isBinderAlive() { return true; }
            @Override public void linkToDeath(DeathRecipient r, int flags) { }
            @Override public boolean transact(int code, Parcel data, Parcel reply, int flags) {
                data.readInterfaceToken();
                data.readString();
                reply.writeNoException();
                reply.writeString(liveReply[0]);
                return true;
            }
        };
        TaskBridgeClient.attach(livePanelHost, liveBridge);
        TaskBridgeClient.configure(livePanelHost);
        livePanelHost.defaultHome.show();
        TvDialog openPanel = livePanelHost.dialogs.active();
        boolean beforeDropShowedBridge = openPanel.items[3].contains("任务桥辅助") && openPanel.message.contains("任务桥");
        // Now push bridge drop / device disconnect while dialog remains open!
        TaskBridgeClient.onServerStateChanged(livePanelHost, new JSONObject(
                "{\"ok\":true,\"volumeProtocolVersion\":2,\"volumeAssist\":true,\"volumeAssistActive\":false,\"volumeAudioReady\":true,\"volumeInputDeviceCount\":0,\"volumeAssistReason\":\"no_volume_input_device\"}"));
        check("codex82_ui_open_default_home_panel_updates_items_and_message_on_bridge_drop",
                beforeDropShowedBridge
                && openPanel.items[3].contains("无桥让渡")
                && !openPanel.items[3].contains("任务桥辅助")
                && openPanel.message.contains("先点一下音量，松手后再长按"));
        TaskBridgeClient.resetForTest();

        // 3B UI: When system refuses to revoke filter bit, DefaultHomePanel shows one-click release action
        // that disables ONLY Marco's accessibility service while preserving XiaoAi!
        MainActivity stuckPanelHost = freshEnv();
        stuckPanelHost.secureAuthorized = true;
        stuckPanelHost.sysPrefs.edit().putBoolean("home_compat", true).commit();
        Settings.Secure.ints.put(Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.strings.put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                "com.xiaomi.voicecontrol/.VoiceAccessibilityService:" + HomeSetup.SERVICE);
        SystemIntegration.setGuardMode(stuckPanelHost, HomeSetupPolicy.GuardMode.WINDOW_PULLBACK);
        HomeKeyService.connected = true;
        HomeKeyService.runtimeKeyFilterRequested = true; // simulate system refused revoke!
        stuckPanelHost.defaultHome.show();
        TvDialog stuckDlg = stuckPanelHost.dialogs.active();
        boolean hasReleaseItem = stuckDlg.items.length == 7 && stuckDlg.items[6].contains("仅关闭 Marco 辅助服务");
        stuckDlg.clickItem(6);
        String remainingSvcs = Settings.Secure.strings.get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        check("codex82_3b_stuck_filter_release_button_removes_only_marco_preserves_xiaoai",
                hasReleaseItem
                && !HomeSetup.serviceEnabled(stuckPanelHost)
                && remainingSvcs != null && remainingSvcs.contains("com.xiaomi.voicecontrol/.VoiceAccessibilityService")
                && !remainingSvcs.contains("com.pilipala.tvhome")
                && Settings.Secure.ints.get(Settings.Secure.ACCESSIBILITY_ENABLED) == 1);

        // 4A. OEM pre-allowlist: getApplicationEnabledSetting throwing SecurityException MUST return UNKNOWN (-1),
        // NEVER DEFAULT (0), so restore fails truthfully and preserves snapshot!
        MainActivity readErrHost = freshEnv();
        readErrHost.pm.homes.clear();
        readErrHost.pm.homes.add(homeEntry("com.oem.tvhome", "com.oem.tvhome.Main", true, true, true, true));
        readErrHost.pm.currentHome = "com.oem.tvhome/com.oem.tvhome.Main";
        readErrHost.pm.throwOnAppEnabledState = true;
        SystemIntegration.saveOemExperimentSnapshot(readErrHost, 0, "com.oem.tvhome/com.oem.tvhome.Main",
                "com.oem.tvhome", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
        HomeShellSetup readErrShell = new HomeShellSetup(cmd -> "id -u".equals(cmd) ? "2000" : "", 0);
        readErrShell.authenticate();
        String readErrRes = SystemIntegration.executeOemExperimentRestore(readErrHost, readErrShell, 0);
        check("codex82_4a_package_state_read_exception_never_treated_as_default",
                SystemIntegration.readApplicationEnabledState(readErrHost, "com.oem.tvhome") == SystemIntegration.APPLICATION_ENABLED_STATE_UNKNOWN
                && readErrRes.contains("未通过核验")
                && SystemIntegration.hasValidOemExperimentSnapshot(readErrHost, 0));

        // 4B. OEM pre-allowlist: Already restored externally -> LocalHomeAuthorizer.restoreOemExperiment()
        // verifies locally and clears snapshot with ZERO ADB connects and ZERO shell writes!
        MainActivity extRestoredHost = freshEnv();
        extRestoredHost.pm.homes.clear();
        extRestoredHost.pm.homes.add(homeEntry("com.oem.tvhome", "com.oem.tvhome.Main", true, true, true, true));
        extRestoredHost.pm.currentHome = "com.oem.tvhome/com.oem.tvhome.Main";
        extRestoredHost.pm.appEnabledStates.put("com.oem.tvhome", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
        SystemIntegration.saveOemExperimentSnapshot(extRestoredHost, 0, "com.oem.tvhome/com.oem.tvhome.Main",
                "com.oem.tvhome", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
        final AtomicInteger extConnects = new AtomicInteger();
        final CountDownLatch extLatch = new CountDownLatch(1);
        final String[] extOutcome = {null};
        LocalHomeAuthorizer extAuth = new LocalHomeAuthorizer(extRestoredHost, () -> new LocalHomeAuthorizer.OemShellSession() {
            @Override public HomeShellSetup connect(int userId) { extConnects.incrementAndGet(); throw new AssertionError("must not connect ADB when already restored externally"); }
            @Override public void close() {}
        }, OemLauncherExperimentPolicy.VERIFIED_ALLOWLIST);
        extAuth.restoreOemExperiment(res -> { extOutcome[0] = res; extLatch.countDown(); });
        check("codex82_4b_external_restore_clears_snapshot_with_zero_adb_connects",
                extLatch.await(5, TimeUnit.SECONDS)
                && extConnects.get() == 0
                && extOutcome[0] != null && extOutcome[0].contains("未执行 ADB 写入")
                && !SystemIntegration.hasValidOemExperimentSnapshot(extRestoredHost, 0));

        // 4C. OEM pre-allowlist: Disable command refused (HOME switches to Marco yet OEM package stays enabled) ->
        // MUST fail verification and trigger rollback instead of reporting "已停用"!
        MainActivity refDisHost = freshEnv();
        refDisHost.pm.homes.clear();
        ResolveInfo refOem = homeEntry("com.oem.tvhome", "com.oem.tvhome.Main", true, true, true, true);
        refDisHost.pm.homes.add(refOem);
        refDisHost.pm.homes.add(homeEntry("com.pilipala.tvhome", "com.pilipala.tvhome.HomeActivity", true, true, true, false));
        refDisHost.pm.currentHome = "com.oem.tvhome/com.oem.tvhome.Main";
        refDisHost.pm.appEnabledStates.put("com.oem.tvhome", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
        HomeShellSetup refDisShell = new HomeShellSetup(cmd -> {
            if ("id -u".equals(cmd)) return "2000";
            if ("cmd package help".equals(cmd)) return "  set-home-activity TARGET";
            if (cmd.contains("pm disable-user")) {
                // OEM stays enabled (command refused), even though HOME temporarily resolves to Marco!
                refDisHost.pm.currentHome = "com.pilipala.tvhome/com.pilipala.tvhome.HomeActivity";
                return "SecurityException: Cannot disable protected package";
            }
            if (cmd.contains("set-home-activity --user 0 'com.oem.tvhome/com.oem.tvhome.Main'")) {
                refDisHost.pm.currentHome = "com.oem.tvhome/com.oem.tvhome.Main";
            }
            return "";
        }, 0);
        refDisShell.authenticate();
        String refDisRes = SystemIntegration.executeOemExperimentDisable(refDisHost, refDisShell, 0, "TestVerifiedTV", "1.0.0", testAllowlist, null);
        check("codex82_4c_refused_disable_when_oem_still_enabled_fails_and_rolls_back",
                !refDisRes.contains("已停用原厂桌面")
                && refDisRes.contains("已自动恢复原厂桌面")
                && "com.oem.tvhome/com.oem.tvhome.Main".equals(refDisHost.pm.currentHome)
                && !SystemIntegration.hasValidOemExperimentSnapshot(refDisHost, 0));

        // 4D. OEM pre-allowlist: Unresolvable MainActivity -> canLaunchMarcoForeground returns false (no alias fallback!)
        // and independent recovery check requires adb_enabled==1 or ACTION_SETTINGS (not WRITE_SECURE_SETTINGS alone)!
        MainActivity fgFailHost = freshEnv();
        fgFailHost.pm.homes.clear();
        ResolveInfo fgOem = homeEntry("com.oem.tvhome", "com.oem.tvhome.Main", true, true, true, true);
        fgFailHost.pm.homes.add(fgOem);
        fgFailHost.pm.homes.add(homeEntry("com.pilipala.tvhome", "com.pilipala.tvhome.HomeActivity", true, true, true, false));
        fgFailHost.pm.currentHome = "com.oem.tvhome/com.oem.tvhome.Main";
        fgFailHost.pm.appEnabledStates.put("com.oem.tvhome", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
        fgFailHost.pm.failResolveMainActivity = true;
        HomeShellSetup fgFailShell = new HomeShellSetup(cmd -> {
            if ("id -u".equals(cmd)) return "2000";
            if ("cmd package help".equals(cmd)) return "  set-home-activity TARGET";
            if (cmd.contains("pm disable-user")) {
                fgOem.activityInfo.applicationInfo.enabled = false;
                fgFailHost.pm.appEnabledStates.put("com.oem.tvhome", PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER);
                fgFailHost.pm.currentHome = "com.pilipala.tvhome/com.pilipala.tvhome.HomeActivity";
            }
            if (cmd.contains("pm default-state")) {
                fgOem.activityInfo.applicationInfo.enabled = true;
                fgFailHost.pm.appEnabledStates.put("com.oem.tvhome", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
            }
            if (cmd.contains("set-home-activity")) {
                fgFailHost.pm.currentHome = "com.oem.tvhome/com.oem.tvhome.Main";
            }
            return "";
        }, 0);
        fgFailShell.authenticate();
        String fgFailRes = SystemIntegration.executeOemExperimentDisable(fgFailHost, fgFailShell, 0, "TestVerifiedTV", "1.0.0", testAllowlist, null);
        check("codex82_4d_unresolvable_main_activity_triggers_rollback_no_alias_fallback",
                !SystemIntegration.canLaunchMarcoForeground(fgFailHost)
                && fgFailRes.contains("已自动恢复原厂桌面")
                && "com.oem.tvhome/com.oem.tvhome.Main".equals(fgFailHost.pm.currentHome));

        MainActivity noIndepHost = freshEnv();
        noIndepHost.secureAuthorized = true; // Marco's own WRITE_SECURE_SETTINGS does NOT count as independent recovery!
        Settings.Global.ints.put("adb_enabled", 0);
        noIndepHost.pm.actions.remove(Settings.ACTION_SETTINGS);
        noIndepHost.pm.homes.clear();
        noIndepHost.pm.homes.add(homeEntry("com.oem.tvhome", "com.oem.tvhome.Main", true, true, true, true));
        noIndepHost.pm.currentHome = "com.oem.tvhome/com.oem.tvhome.Main";
        SystemIntegration.OemDisablePlan noIndepPlan = SystemIntegration.prepareOemExperimentDisable(
                noIndepHost, 0, "TestVerifiedTV", "1.0.0", testAllowlist);
        check("codex82_4d_independent_recovery_rejects_write_secure_settings_alone",
                !SystemIntegration.hasIndependentRecoveryPath(noIndepHost)
                && !noIndepPlan.allowed()
                && noIndepPlan.blockedMessage != null && noIndepPlan.blockedMessage.contains("恢复途径"));

        android.os.Process.uid = 1012345; // restore default stub uid
    }

    public static void main(String[] args) throws Exception {
        testTask1Routing();
        testTask2Attribution();
        testTask3TrackedHomeAndSnapshot();
        testRound2Regressions();
        testRound3Counterexamples();
        testRound4EdgeCases();
        testGuardModeAndFilterRefresh();
        testDesktopModesAndOemExperiment();
        System.out.println("OK " + passed + " end-to-end home setup flow, attribution, persistence and round-2/3/4 regression checks");
        System.exit(0);
    }
}

class BuildEdition { static boolean RESTRICTED; }
class HomeKeyService {
    static volatile boolean connected;
    static volatile int runtimeFlags;
    static volatile boolean runtimeKeyFilterRequested;
    static volatile boolean runtimeVolumeYieldActive;
    static volatile boolean runtimeVolumeYieldFailed;
    static volatile boolean runtimeModeApplied;
    static volatile long connectionGeneration;
    static volatile long modeAppliedGeneration;
    static final AtomicInteger syncCalls = new AtomicInteger();
    static void resetForTest() {
        connected = false;
        runtimeFlags = 0;
        runtimeKeyFilterRequested = false;
        runtimeVolumeYieldActive = false;
        runtimeVolumeYieldFailed = false;
        runtimeModeApplied = false;
        connectionGeneration = 0;
        modeAppliedGeneration = 0;
        syncCalls.set(0);
    }
    static boolean isGuardModeApplied(Context c) {
        return isGuardModeApplied(c, 0L);
    }
    static boolean isGuardModeApplied(Context c, long afterGeneration) {
        if (!connected || !runtimeModeApplied || modeAppliedGeneration <= afterGeneration) return false;
        boolean compat = SystemIntegration.prefs(c).getBoolean("home_compat", false);
        boolean expected = HomeSetupPolicy.shouldRequestKeyFilter(
                compat, HomeSetup.paused(c), SystemIntegration.homeIsOurs(c), SystemIntegration.guardMode(c));
        return runtimeKeyFilterRequested == (expected && !runtimeVolumeYieldActive);
    }
    static void simulateConnect(Context c) {
        connectionGeneration++;
        connected = true;
        syncRuntimeKeyFilter(c);
        android.content.SharedPreferences p = SystemIntegration.prefs(c);
        if (p.getBoolean("home_compat", false) && !HomeSetup.paused(c)) {
            p.edit().putBoolean("compat_service_active", true).putBoolean("compat_seen_present", true).apply();
        }
    }
    static void simulateUnbind(Context c) {
        connected = false;
        runtimeFlags = 0;
        runtimeKeyFilterRequested = false;
        runtimeVolumeYieldActive = false;
        runtimeVolumeYieldFailed = false;
        runtimeModeApplied = false;
        modeAppliedGeneration = 0;
        android.content.SharedPreferences p = SystemIntegration.prefs(c);
        if (p.getBoolean("compat_service_active", false)) {
            p.edit().putBoolean("compat_service_active", false).apply();
        }
    }
    static void syncRuntimeKeyFilter(Context c) {
        syncCalls.incrementAndGet();
        if (!connected) {
            runtimeFlags = 0;
            runtimeKeyFilterRequested = false;
            runtimeVolumeYieldActive = false;
            runtimeModeApplied = false;
            modeAppliedGeneration = 0;
            return;
        }
        boolean compat = SystemIntegration.prefs(c).getBoolean("home_compat", false);
        runtimeFlags = HomeSetupPolicy.applyKeyFilterFlag(
                runtimeFlags, 32, compat, HomeSetup.paused(c), SystemIntegration.homeIsOurs(c), SystemIntegration.guardMode(c));
        runtimeKeyFilterRequested = (runtimeFlags & 32) != 0;
        runtimeModeApplied = true;
        modeAppliedGeneration = connectionGeneration;
        SystemIntegration.markHomeServiceRebindConnected(c);
    }
}
class RecoveryService { static volatile boolean running; }
class AmbientIntegration { static void configure(Context c) { } }
class Diagnostics { static final List<String> events = Collections.synchronizedList(new ArrayList<>()); static void event(String e) { events.add(e); } }
class TaskBridgeServer {
    static final String DESCRIPTOR = "com.pilipala.tvhome.TaskBridge.v1";
    static final int VOLUME_PROTOCOL_VERSION = 2;
}
class HealthPanel {
    static String date(long s) { return s == 0 ? "尚无记录" : String.valueOf(s); }
    void status() { }
}
class SettingsNavigator { void personalization(String key) { } }
class LocalAdbWire {
    interface Status { void accept(String text); }
    static final AtomicInteger connects = new AtomicInteger();
    static final List<String> commands = Collections.synchronizedList(new ArrayList<>());
    static volatile boolean connectFails;
    static volatile boolean selectSucceeds = true;
    static volatile MainActivity activeHost;
    static void reset() {
        connects.set(0);
        commands.clear();
        connectFails = false;
        selectSucceeds = true;
    }
    static java.security.KeyPair key(java.io.File folder) { return null; }
    void connect(String host, int port, java.security.KeyPair key, Status status) throws Exception {
        connects.incrementAndGet();
        if (connectFails) throw new java.io.IOException("unavailable");
    }
    String shell(String command) throws Exception {
        commands.add(command);
        if ("id -u".equals(command)) return "2000";
        if ("cmd package help".equals(command)) return "  set-home-activity [--user USER_ID] TARGET";
        if (command.startsWith("pm grant")) {
            if (activeHost != null) activeHost.secureAuthorized = true;
            return "";
        }
        if (command.contains(" set-home-activity ") && selectSucceeds && activeHost != null) {
            int first = command.indexOf('\''), last = command.lastIndexOf('\'');
            if (first >= 0 && last > first) activeHost.pm.currentHome = command.substring(first + 1, last);
        }
        return "";
    }
    void close() { }
}
class TvDialog {
    final String pageId, title;
    String message;
    String[] items;
    final DialogInterface.OnClickListener itemClick, positive, neutral, negative;
    Runnable leaveAction;
    boolean showing;
    TvDialog(Builder b) {
        pageId = b.pageId != null ? b.pageId : b.title;
        title = b.title; message = b.message; items = b.items; itemClick = b.itemClick;
        positive = b.positive; neutral = b.neutral; negative = b.negative;
    }
    String pageKey() { return pageId; }
    boolean isShowing() { return showing; }
    void updateMessage(String m) { message = m; }
    void updateItems(String[] next) { if (next != null) items = next; }
    void onLeave(Runnable r) { leaveAction = r; }
    void leave() { if (leaveAction != null) { Runnable r = leaveAction; leaveAction = null; r.run(); } }
    void clickItem(int i) { if (itemClick != null) itemClick.onClick(null, i); }
    void clickPositive() { if (positive != null) positive.onClick(null, DialogInterface.BUTTON_POSITIVE); }
    void clickNeutral() { if (neutral != null) neutral.onClick(null, DialogInterface.BUTTON_NEUTRAL); }
    void clickNegative() { if (negative != null) negative.onClick(null, DialogInterface.BUTTON_NEGATIVE); }
    static class Builder {
        String pageId, title = "", message;
        String[] items;
        DialogInterface.OnClickListener itemClick, positive, neutral, negative;
        Builder(Activity a) { }
        Builder stackedButtons() { return this; }
        Builder centered() { return this; }
        Builder setPageId(String s) { pageId = s; return this; }
        Builder setTitle(String s) { title = s; return this; }
        Builder setMessage(String s) { message = s; return this; }
        Builder setOrder(String... s) { return this; }
        Builder setView(android.view.View v) { return this; }
        Builder setItems(String[] a, DialogInterface.OnClickListener c) { items = a; itemClick = c; return this; }
        Builder setPositiveButton(String s, DialogInterface.OnClickListener c) { positive = c; return this; }
        Builder setNeutralButton(String s, DialogInterface.OnClickListener c) { neutral = c; return this; }
        Builder setNegativeButton(String s, DialogInterface.OnClickListener c) { negative = c; return this; }
        TvDialog create() { return new TvDialog(this); }
    }
}
class DialogStack {
    volatile TvDialog activeDialog;
    final List<TvDialog> history = Collections.synchronizedList(new ArrayList<>());
    private volatile CountDownLatch resultLatch;
    void armResultLatch() { resultLatch = new CountDownLatch(1); }
    boolean awaitResult() {
        CountDownLatch l = resultLatch;
        if (l == null) return true;
        try { return l.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { return false; }
    }
    boolean open() { return activeDialog != null; }
    TvDialog active() { return activeDialog; }
    boolean isPage(String id) { TvDialog d = activeDialog; return d != null && id.equals(d.pageKey()); }
    synchronized void show(TvDialog d) { activeDialog = d; d.showing = true; history.add(d); }
    synchronized void replace(TvDialog old, TvDialog next) {
        if (activeDialog == old && !history.isEmpty()) {
            history.remove(history.size() - 1);
            old.leave();
            show(next);
            CountDownLatch l = resultLatch;
            if (l != null && "home_local_result".equals(next.pageKey())) l.countDown();
        }
    }
    synchronized void back() {
        if (history.isEmpty()) return;
        TvDialog leaving = history.remove(history.size() - 1);
        activeDialog = history.isEmpty() ? null : history.get(history.size() - 1);
        leaving.leave();
    }
    synchronized void close() {
        List<TvDialog> leaving = new ArrayList<>(history);
        history.clear();
        activeDialog = null;
        for (int i = leaving.size() - 1; i >= 0; i--) leaving.get(i).leave();
    }
}
class MainActivity extends Activity {
    int menuEpoch;
    boolean foreground;
    boolean panelFocused;
    final HomeSetup.ArrivalGate arrivalGate = new HomeSetup.ArrivalGate();
    final HomePressTracker homePressTracker = new HomePressTracker();
    final Handler handler = new Handler(Looper.getMainLooper());
    final DialogStack dialogs = new DialogStack();
    DefaultHomePanel defaultHome;
    HealthPanel health = new HealthPanel();
    SettingsNavigator navigator = new SettingsNavigator();
    final List<String> toasts = Collections.synchronizedList(new ArrayList<>());
    boolean isDesktopVisible() {
        return arrivalGate.isDesktopVisible(hasWindowFocus(), panelFocused, isFinishing(), isDestroyed());
    }
    void simulateOnCreate(Intent intent, boolean recreated) {
        boolean recent = intent != null && intent.getBooleanExtra("recent_apps", false);
        arrivalGate.onCreateIntent(intent, recent, recreated);
        if (intent != null && intent.hasCategory(Intent.CATEGORY_HOME)
                && homePrefs.getBoolean("recent_enabled", false)) {
            homePressTracker.press(SystemClock.elapsedRealtime(), homePrefs.getInt("recent_double_ms", 400));
        }
    }
    boolean simulateOnNewIntent(Intent intent) {
        boolean recent = intent != null && intent.getBooleanExtra("recent_apps", false);
        if (intent != null && !intent.getBooleanExtra("home_press_handled", false)
                && intent.hasCategory(Intent.CATEGORY_HOME)
                && homePrefs.getBoolean("recent_enabled", false)) {
            recent = homePressTracker.press(SystemClock.elapsedRealtime(), homePrefs.getInt("recent_double_ms", 400));
        } else if (intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) {
            homePressTracker.reset();
        }
        arrivalGate.onNewIntent(intent, recent);
        closeMenus();
        return recent;
    }
    boolean simulateOnResume(boolean focus) {
        foreground = true;
        windowFocus = focus;
        HomeSetup.resolvePendingPicker(this);
        HomeSetup.finishCompatibilityConsent(this);
        SystemIntegration.resumePendingHomeServiceRebind(this);
        HomeSetup.reconcile(this);
        boolean consumed = arrivalGate.onResume(this, hasWindowFocus(), panelFocused, isFinishing(), isDestroyed());
        TaskBridgeClient.sync(this);
        if (defaultHome != null) defaultHome.resumed();
        return consumed;
    }
    void simulateRealColdStart(boolean focus) {
        SystemIntegration.clearStandbyWakeReturn(this);
        HomeSetup.markCompatResetIfInterrupted(this);
        SystemIntegration.rememberHome(this);
        SystemIntegration.refreshHomeService(this);
        SystemIntegration.startRecovery(this);
        simulateOnResume(focus);
    }
    boolean simulateOnWindowFocusChanged(boolean focus) {
        windowFocus = focus;
        return arrivalGate.onWindowFocusChanged(this, focus, panelFocused, isFinishing(), isDestroyed());
    }
    void simulateOnPause() {
        foreground = false;
        windowFocus = false;
        arrivalGate.onPause();
    }
    void simulateOnDestroy() {
        foreground = false;
        windowFocus = false;
        destroyed = true;
        arrivalGate.onDestroy();
        if (defaultHome != null) defaultHome.destroy();
        closeMenus(false);
    }
    void showDialog(TvDialog d) { dialogs.show(d); }
    boolean isPage(String id) { return dialogs.isPage(id); }
    void toast(String s) { toasts.add(s); }
    void exitMenus() { closeMenus(); }
    void closeMenus() {
        closeMenus(true);
    }
    void closeMenus(boolean resolvePending) {
        if (resolvePending && defaultHome != null) defaultHome.cancelPending();
        menuEpoch++;
        dialogs.close();
    }
}
