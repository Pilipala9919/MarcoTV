// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.provider.Settings;
import java.util.*;
import org.json.JSONObject;

/**
 * End-to-end production logic checks for Tasks 1, 2 and 3.
 * Compiles and executes real DefaultHomePanel, HomeSetup, HomeSetupPolicy, HomeDeviceGuide,
 * SystemIntegration, HomeSetupSnapshot, HomeShellSetup, BootReceiver, StatusSnapshot,
 * TaskBridgeClient and HomePressTracker against in-memory Android services.
 */
public final class HomeFlowChecks {
    static int passed;

    static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
        passed++;
        System.out.println("PASS " + name);
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
        HomeSetup.bootIdOverride = "boot-uuid-1";
        HomeSetup.resetProcessSessionForTest();
        HomeKeyService.connected = false;
        RecoveryService.running = false;
        Diagnostics.events.clear();
        LocalHomeAuthorizer.starts = 0;
        LocalHomeAuthorizer.restores = 0;
        LocalHomeAuthorizer.cancels = 0;
        Settings.Global.ints.clear();
        Settings.Secure.ints.clear();
        Settings.Secure.strings.clear();
        Settings.Global.ints.put("boot_count", 3);
        Settings.Global.ints.put("adb_enabled", 0);
        MainActivity host = new MainActivity();
        host.foreground = true;
        host.pm.homes.add(homeEntry("com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode", true, true, true, true));
        host.pm.homes.add(homeEntry("com.pilipala.tvhome", "com.pilipala.tvhome.HomeActivity", true, true, true, false));
        host.pm.currentHome = "com.mitv.tvhome/com.mitv.tvhome.MainActivityUserMode";
        host.pm.actions.put(Settings.ACTION_HOME_SETTINGS, homeEntry("com.android.tv.settings", "com.android.tv.settings.HomePicker", true, true, true, true));
        host.pm.actions.put(Settings.ACTION_ACCESSIBILITY_SETTINGS, homeEntry("com.android.tv.settings", "com.android.tv.settings.AccSettings", true, true, true, true));
        host.pm.actions.put(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, homeEntry("com.android.tv.settings", "com.android.tv.settings.DevSettings", true, true, true, true));
        host.pm.actions.put(Settings.ACTION_SETTINGS, homeEntry("com.android.tv.settings", "com.android.tv.settings.MainSettings", true, true, true, true));
        host.defaultHome = new DefaultHomePanel(host);
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
                && LocalHomeAuthorizer.starts == 0);
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
                host.isPage("home_compat_consent") && LocalHomeAuthorizer.starts == 0);
        host.dialogs.active().clickPositive();
        check("t1_accessibility_confirm_opens_settings_without_adb",
                host.startedActivities.size() == 1
                && Settings.ACTION_ACCESSIBILITY_SETTINGS.equals(host.startedActivities.get(0).getAction())
                && LocalHomeAuthorizer.starts == 0);
        // Allowed AND bound/connected -> verification!
        setService(true, true);
        host.defaultHome.resumed();
        check("t1_accessibility_connected_finishes_without_adb",
                host.isPage("home_verification")
                && HomeSetup.route(host) == HomeSetupPolicy.Route.COMPAT_READY
                && LocalHomeAuthorizer.starts == 0);

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
                host.isPage("default_home") && !host.toasts.isEmpty() && LocalHomeAuthorizer.starts == 0);

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
        host.defaultHome.deviceGuide(HomeDeviceGuide.Issue.NONE);
        host.dialogs.active().clickItem(0); // "打开调试设置，返回后自动继续"
        host.defaultHome.resumed();
        check("t1_explicit_guide_return_continues_once", LocalHomeAuthorizer.starts == 1);
        host.defaultHome.resumed();
        check("t1_explicit_guide_return_never_repeats", LocalHomeAuthorizer.starts == 1);

        // Leave by pressing Home (closeMenus -> cancelPending + menuEpoch++)
        host = freshEnv();
        host.defaultHome.deviceGuide(HomeDeviceGuide.Issue.NONE);
        host.dialogs.active().clickItem(0);
        host.closeMenus();
        host.defaultHome.resumed();
        check("t1_home_leave_cancels_guide_continuation", LocalHomeAuthorizer.starts == 0);

        // Leave by pressing Back out of deviceGuide (onLeave cancels guideReturn)
        host = freshEnv();
        host.defaultHome.deviceGuide(HomeDeviceGuide.Issue.NONE);
        host.dialogs.active().clickItem(0);
        host.dialogs.back();
        host.defaultHome.resumed();
        check("t1_back_cancel_cancels_guide_continuation", LocalHomeAuthorizer.starts == 0);

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
        check("t1_activity_recreation_cancels_continuation", LocalHomeAuthorizer.starts == 0);

        // Help -> "打开电视系统设置" does NOT arm automatic ADB continuation
        host = freshEnv();
        host.defaultHome.help();
        host.dialogs.active().clickItem(0);
        host.defaultHome.resumed();
        check("t1_help_settings_never_auto_runs_adb", host.isPage("default_home_help") && LocalHomeAuthorizer.starts == 0);
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

    public static void main(String[] args) throws Exception {
        testTask1Routing();
        testTask2Attribution();
        testTask3TrackedHomeAndSnapshot();
        System.out.println("OK " + passed + " end-to-end home setup flow, attribution and persistence checks");
        System.exit(0);
    }
}

class BuildEdition { static boolean RESTRICTED; }
class HomeKeyService { static volatile boolean connected; }
class RecoveryService { static volatile boolean running; }
class AmbientIntegration { static void configure(Context c) { } }
class Diagnostics { static final List<String> events = new ArrayList<>(); static void event(String e) { events.add(e); } }
class TaskBridgeServer { static final String DESCRIPTOR = "com.pilipala.tvhome.TaskBridge.v1"; }
class HealthPanel {
    static String date(long s) { return s == 0 ? "尚无记录" : String.valueOf(s); }
    void status() { }
}
class SettingsNavigator { void personalization(String key) { } }
class LocalHomeAuthorizer {
    interface Result { void complete(String text); }
    static int starts, restores, cancels;
    HomeDeviceGuide.Issue issue = HomeDeviceGuide.Issue.NONE;
    LocalHomeAuthorizer(Context c) { }
    HomeDeviceGuide.Issue issue() { return issue; }
    void start(Result cb) { starts++; }
    void restore(Result cb) { restores++; }
    void cancel() { cancels++; }
}
class TvDialog {
    final String pageId, title, message;
    final String[] items;
    final DialogInterface.OnClickListener itemClick, positive, neutral, negative;
    Runnable leaveAction;
    boolean showing;
    TvDialog(Builder b) {
        pageId = b.pageId != null ? b.pageId : b.title;
        title = b.title; message = b.message; items = b.items; itemClick = b.itemClick;
        positive = b.positive; neutral = b.neutral; negative = b.negative;
    }
    String pageKey() { return pageId; }
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
    TvDialog activeDialog;
    final List<TvDialog> history = new ArrayList<>();
    boolean open() { return activeDialog != null; }
    TvDialog active() { return activeDialog; }
    boolean isPage(String id) { return activeDialog != null && id.equals(activeDialog.pageKey()); }
    void show(TvDialog d) { activeDialog = d; d.showing = true; history.add(d); }
    void replace(TvDialog old, TvDialog next) {
        if (activeDialog == old && !history.isEmpty()) {
            history.remove(history.size() - 1);
            old.leave();
            show(next);
        }
    }
    void back() {
        if (history.isEmpty()) return;
        TvDialog leaving = history.remove(history.size() - 1);
        activeDialog = history.isEmpty() ? null : history.get(history.size() - 1);
        leaving.leave();
    }
    void close() {
        List<TvDialog> leaving = new ArrayList<>(history);
        history.clear();
        activeDialog = null;
        for (int i = leaving.size() - 1; i >= 0; i--) leaving.get(i).leave();
    }
}
class MainActivity extends Activity {
    int menuEpoch;
    boolean foreground;
    final Handler handler = new Handler(Looper.getMainLooper());
    final DialogStack dialogs = new DialogStack();
    DefaultHomePanel defaultHome;
    HealthPanel health = new HealthPanel();
    SettingsNavigator navigator = new SettingsNavigator();
    final List<String> toasts = new ArrayList<>();
    void showDialog(TvDialog d) { dialogs.show(d); }
    boolean isPage(String id) { return dialogs.isPage(id); }
    void toast(String s) { toasts.add(s); }
    void exitMenus() { closeMenus(); }
    void closeMenus() {
        if (defaultHome != null) defaultHome.cancelPending();
        menuEpoch++;
        dialogs.close();
    }
}
