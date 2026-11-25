// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.Context;
import java.util.*;
import java.util.concurrent.*;

/** Executes production orchestration against a deterministic firmware/service simulator. */
public final class HomeAutoChecks {
    static int passed;
    static HomeDeviceGuide.Issue lastIssue;
    static Context context = new Context();
    static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
        passed++;
    }
    static void reset(boolean restricted) {
        BuildEdition.RESTRICTED = restricted;
        SystemIntegration.secure = false; SystemIntegration.nativeHome = false;
        HomeSetup.service = false; HomeSetup.compat = false; HomeSetup.paused = false;
        HomeSetup.begins = 0; HomeSetup.accepted = false; HomeKeyService.connected = false;
        HomeSetupSnapshot.saved = false; HomeSetupSnapshot.original = "com.vendor.launcher/.Home";
        HomeSetupSnapshot.recorded = "com.vendor.launcher/.Home";
        HomeSetupSnapshot.beforeNative = false; HomeSetupSnapshot.localRestores = 0;
        LocalAdbWire.commands.clear(); LocalAdbWire.connects = 0;
        LocalAdbWire.grant = true; LocalAdbWire.select = true; LocalAdbWire.uid = "2000";
        LocalAdbWire.modern = true; LocalAdbWire.legacy = false;
        LocalAdbWire.current = "com.vendor.launcher/.Home";
        LocalAdbWire.hook = null; LocalAdbWire.closed = false;
        LocalAdbWire.connectFails = false;
        LocalAdbWire.authorizationFails = false;
        HomeSetup.serviceAllowed = true;
        SystemIntegration.connectionAllowed=true;SystemIntegration.removed=0;
    }
    static String run(boolean restore) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        String[] outcome = {null};
        LocalHomeAuthorizer a = new LocalHomeAuthorizer(context);
        LocalHomeAuthorizer.Result result = value -> {outcome[0] = value; done.countDown();};
        if (restore) a.restore(result); else a.start(result);
        if (!done.await(4, TimeUnit.SECONDS)) { a.cancel(); throw new AssertionError("callback timed out"); }
        lastIssue = a.issue();
        return outcome[0];
    }
    static boolean selected() { return LocalAdbWire.commands.toString().contains(" set-home-activity "); }
    public static void main(String[] args) throws Exception {
        reset(false); String connectedResult=run(false);
        check("successful_connection_has_no_transport_issue",lastIssue==HomeDeviceGuide.Issue.NONE);
        check("result_distinguishes_authenticated_transport",connectedResult.contains("本机调试连接已授权"));
        check("native_confirmed_after_real_readback", SystemIntegration.nativeHome && HomeSetup.accepted && !HomeSetup.compat);
        check("snapshot_precedes_mutation", HomeSetupSnapshot.saved && !HomeSetupSnapshot.beforeNative);
        check("current_android_user_not_hardcoded_zero", LocalAdbWire.commands.toString().contains("--user 10"));
        reset(false);SystemIntegration.nativeHome=true;run(false);
        check("explicit_setup_repairs_existing_home_preference",selected());
        reset(false); LocalAdbWire.grant=false; run(false);
        check("grant_denial_does_not_block_native_home", HomeSetup.accepted && !SystemIntegration.secure);
        reset(false); LocalAdbWire.select=false; run(false);
        check("selection_rejection_falls_back_to_compat", !HomeSetup.accepted && HomeSetup.service && HomeSetup.compat
                && LocalAdbWire.commands.stream().noneMatch(c -> c.contains("disable-user")));
        reset(false);LocalAdbWire.select=false;HomeSetup.service=true;run(false);
        check("enabled_but_dead_service_is_rebound",SystemIntegration.removed==1&&HomeKeyService.connected);
        reset(false);LocalAdbWire.select=false;HomeSetup.service=true;HomeKeyService.connected=true;run(false);
        check("connected_service_not_disrupted",SystemIntegration.removed==0&&HomeKeyService.connected);
        reset(false);LocalAdbWire.select=false;SystemIntegration.connectionAllowed=false;String unbound=run(false);
        check("allowed_without_connection_not_reported_ready",HomeSetup.service&&!HomeKeyService.connected&&unbound.contains("尚未连接"));
        reset(false); LocalAdbWire.select=false; LocalAdbWire.grant=false; run(false);
        check("both_denied_not_success", !HomeSetup.accepted && !HomeSetup.service);
        reset(false); LocalAdbWire.select=false; HomeSetup.serviceAllowed=false; String refused=run(false);
        check("service_write_denied_reported", !HomeSetup.service && refused.contains("未允许"));
        reset(false); LocalAdbWire.modern=false; LocalAdbWire.legacy=true; run(false);
        check("older_pm_command_selected_by_help", LocalAdbWire.commands.toString().contains("pm set-home-activity"));
        reset(false); LocalAdbWire.modern=false; run(false);
        check("unsupported_command_never_written", !selected() && HomeSetup.compat);
        reset(true); run(false);
        check("restricted_never_sets_home", !selected() && HomeSetup.compat && HomeSetup.service);
        reset(true); SystemIntegration.secure=true; run(false);
        check("authorized_restricted_needs_no_adb", LocalAdbWire.connects==0 && HomeSetup.compat);
        reset(false);SystemIntegration.secure=true;LocalAdbWire.connectFails=true;String fallbackResult=run(false);
        check("existing_permission_recovers_when_adb_is_off",HomeSetup.compat&&HomeSetup.service&&!selected());
        check("result_distinguishes_existing_permission_fallback",fallbackResult.contains("本机调试暂不可用"));
        check("permission_fallback_clears_connection_issue",lastIssue==HomeDeviceGuide.Issue.NONE);
        reset(false);LocalAdbWire.connectFails=true;run(false);
        check("connection_failure_is_actionable",lastIssue==HomeDeviceGuide.Issue.CONNECTION);
        check("connection_failure_has_no_writes",LocalAdbWire.commands.isEmpty());
        reset(false);LocalAdbWire.authorizationFails=true;run(false);
        check("authorization_failure_is_distinct",lastIssue==HomeDeviceGuide.Issue.AUTHORIZATION);
        check("authorization_failure_has_no_writes",LocalAdbWire.commands.isEmpty());
        reset(false); LocalAdbWire.uid="10000"; run(false);
        check("wrong_identity_has_no_mutations", LocalAdbWire.commands.size()==1 && !HomeSetupSnapshot.saved && HomeSetup.begins==0);
        reset(false); LocalAdbWire.hook=command->{ if(command.equals("id -u")) android.os.Handler.fireTimeout(); }; run(false);
        check("deadline_prevents_followup_commands", LocalAdbWire.commands.size()==1 && !HomeSetupSnapshot.saved);
        check("deadline_has_timeout_issue",lastIssue==HomeDeviceGuide.Issue.TIMEOUT);
        reset(false);
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        LocalAdbWire.hook=command->{if(command.equals("id -u")){entered.countDown();release.await();}};
        LocalHomeAuthorizer cancelled=new LocalHomeAuthorizer(context);
        boolean[] callback={false}; cancelled.start(value->callback[0]=true);
        check("cancel_test_reaches_auth", entered.await(2,TimeUnit.SECONDS));
        cancelled.cancel(); release.countDown(); Thread.sleep(80);
        check("cancel_stops_mutation_and_callback", !callback[0] && !HomeSetupSnapshot.saved && LocalAdbWire.commands.size()==1);
        reset(false); HomeSetupSnapshot.saved=true; SystemIntegration.nativeHome=true; LocalAdbWire.current=HomeSetup.OWN; run(true);
        check("restore_selects_recorded_home_and_clears_snapshot", HomeSetupSnapshot.localRestores==1 && !HomeSetupSnapshot.saved && !SystemIntegration.nativeHome);
        reset(false); HomeSetupSnapshot.saved=true; SystemIntegration.nativeHome=true; LocalAdbWire.current=HomeSetup.OWN; LocalAdbWire.select=false; run(true);
        check("restore_failure_keeps_snapshot_and_pauses", HomeSetupSnapshot.saved && HomeSetupSnapshot.localRestores==0 && HomeSetup.paused);
        reset(false); HomeSetupSnapshot.saved=true; run(true);
        check("already_original_restores_without_adb", LocalAdbWire.connects==0 && HomeSetupSnapshot.localRestores==1);
        reset(false); HomeSetupSnapshot.saved=true; HomeSetupSnapshot.original=""; HomeSetupSnapshot.recorded=""; SystemIntegration.nativeHome=true; run(true);
        check("missing_target_never_guessed", !selected() && HomeSetupSnapshot.saved);
        // Fix #7: Snapshot has recorded target A, A is temporarily disabled (target()=="" while recordedComponent()==A),
        // current HOME is B (not Marco) -> restore must NOT clear snapshot locally! Then re-enable A -> restore switches back to A!
        reset(false); HomeSetupSnapshot.saved=true; HomeSetupSnapshot.recorded="com.vendor.launcher/.Home"; HomeSetupSnapshot.original="";
        SystemIntegration.nativeHome=false; LocalAdbWire.current="com.other.b/.Home";
        String disabledRestoreMsg = run(true);
        check("disabled_recorded_target_preserves_snapshot_and_allows_retry",
                HomeSetupSnapshot.saved && HomeSetupSnapshot.localRestores==0 && LocalAdbWire.connects==0
                && !selected() && disabledRestoreMsg.contains("已停用或不可用") && disabledRestoreMsg.contains("恢复快照已保留"));
        HomeSetupSnapshot.original="com.vendor.launcher/.Home";
        String reenabledRestoreMsg = run(true);
        check("reenabled_recorded_target_restores_back_to_A_and_clears_snapshot",
                !HomeSetupSnapshot.saved && HomeSetupSnapshot.localRestores==1
                && "com.vendor.launcher/.Home".equals(LocalAdbWire.current)
                && reenabledRestoreMsg.contains("已恢复本次设置前的桌面配置"));
        reset(false); HomeSetupSnapshot.saved=true; HomeSetupSnapshot.beforeNative=true; HomeSetupSnapshot.original=""; HomeSetupSnapshot.recorded=""; SystemIntegration.nativeHome=true; run(true);
        check("previously_marco_remains_marco", LocalAdbWire.connects==0 && SystemIntegration.nativeHome);
        check("reject_shell_injection", !HomeShellSetup.validComponent("com.vendor/.Home;reboot"));
        check("reject_newline", !HomeShellSetup.validComponent("com.vendor/.Home\nreboot"));
        check("reject_quotes", !HomeShellSetup.validComponent("com.vendor/.Ho'me"));
        check("help_error_mention_not_capability", !HomeShellSetup.supports("Error: unsupported set-home-activity"));
        check("brand_detection_is_case_insensitive", HomeShellSetup.brand("TCL", "tcl").equals("TCL"));
        check("unknown_brand_stays_generic", HomeShellSetup.brand("unknown", "unknown").equals("其他品牌"));
        List<String> commands=new ArrayList<>();
        HomeShellSetup shell=new HomeShellSetup(cmd->{commands.add(cmd);return cmd.equals("id -u")?"2000":"  set-home-activity [--user USER_ID] TARGET";},0);
        try {shell.selectHome("com.vendor/.Home");throw new AssertionError("unauthenticated mutation");}catch(java.io.IOException expected){}
        check("unauthed_runner_never_calls_shell",commands.isEmpty());
        shell.authenticate();shell.selectHome("com.vendor/.Home$Inner");
        check("component_dollar_is_quoted",commands.get(commands.size()-1).endsWith("'com.vendor/.Home$Inner'"));
        check("apk_path_validation",HomeShellSetup.validApkPath("/data/app/com.pilipala.tvhome-1A_b==/base.apk")
                &&HomeShellSetup.validApkPath("/data/app/~~aBcD12+xYz==/com.pilipala.tvhome-9876AbCd==/base.apk")
                &&HomeShellSetup.validApkPath("/mnt/expand/01234567-89ab-cdef/app/~~aBcD12+xYz==/com.pilipala.tvhome-1/base.apk")
                &&!HomeShellSetup.validApkPath("/data/local/tmp/base.apk")
                &&!HomeShellSetup.validApkPath("/data/app/../base.apk")
                &&!HomeShellSetup.validApkPath("/mnt/expand/../data/app/base.apk")
                &&!HomeShellSetup.validApkPath("/data/app/com.pilipala.tvhome/base.apk;id"));
        shell.startTaskBridge("/data/app/com.pilipala.tvhome-1/base.apk",10059);
        String launchCmd=commands.get(commands.size()-1);
        check("bridge_start_command_issued",launchCmd.contains("com.pilipala.tvhome.TaskBridgeServer 10059 0"));
        check("bridge_start_ignores_sighup",launchCmd.contains("trap '' HUP"));
        check("bridge_start_detaches_with_setsid",launchCmd.contains("setsid"));
        List<String> restartCmds=new ArrayList<>();
        HomeShellSetup restartShell=new HomeShellSetup(cmd->{
            restartCmds.add(cmd);
            if(cmd.equals("id -u"))return "2000";
            if(cmd.startsWith("ps -A "))return "USER           PID  PPID     VSZ    RSS WCHAN            ADDR S NAME\n"
                    +"root             1     0   12345   2345 0                   0 S init\n"
                    +"shell         4321     1 1345678  45678 0                   0 S marcotv-task-bridge\n";
            if(cmd.startsWith("tr ")&&cmd.contains("/proc/4321/"))return "marcotv-task-bridge";
            return "";
        },0);
        restartShell.authenticate();
        restartShell.startTaskBridge("/data/app/com.pilipala.tvhome-1/base.apk",10059);
        check("bridge_restart_kills_old_and_waits",restartCmds.contains("kill 4321 2>/dev/null")
                &&restartCmds.stream().anyMatch(c->c.startsWith("sleep 0.3"))
                &&restartCmds.stream().noneMatch(c->c.contains("/proc/1/"))
                &&restartCmds.stream().noneMatch(c->c.startsWith("pidof ")));
        List<String> legacyRestartCmds=new ArrayList<>();
        HomeShellSetup legacyShell=new HomeShellSetup(cmd->{
            legacyRestartCmds.add(cmd);
            if(cmd.equals("id -u"))return "2000";
            if(cmd.startsWith("ps -A "))return "bad pid '-A'";
            if(cmd.equals("ps 2>/dev/null"))return "USER      PID   PPID  VSIZE  RSS   WCHAN              PC  NAME\n"
                    +"root      1     0     1420   804   SyS_epoll_ 00000000 S /init\n"
                    +"shell     17574 1     1523480 48204 SyS_epoll_ 00000000 S marcotv-task-bridge\n";
            if(cmd.startsWith("tr ")&&cmd.contains("/proc/17574/"))return "marcotv-task-bridge";
            return "";
        },0);
        legacyShell.authenticate();
        legacyShell.startTaskBridge("/data/app/com.pilipala.tvhome-1/base.apk",10059);
        check("bridge_restart_api23_ps_fallback",legacyRestartCmds.contains("ps 2>/dev/null")
                &&legacyRestartCmds.contains("kill 17574 2>/dev/null")
                &&legacyRestartCmds.stream().noneMatch(c->c.contains("/proc/1/"))
                &&legacyRestartCmds.stream().noneMatch(c->c.startsWith("pidof ")));
        check("bridge_ps_parses_busybox_pid_column",
                Collections.singletonList("9001").equals(HomeShellSetup.parseBridgePids(
                        "  PID USER       VSZ STAT COMMAND\n    1 root      1420 S    /init\n 9001 shell    48204 S    marcotv-task-br\n")));
        String mitv4HeaderOnly = "USER     PID   PPID  VSIZE  RSS     WCHAN    PC         NAME\n";
        String validNoBridge = mitv4HeaderOnly
                + "root      1     0     1420   804   SyS_epoll_ 00000000 S /init\n"
                + "system    921   1     98760  14200 SyS_epoll_ 00000000 S system_server\n";
        check("mitv4_header_only_rejected_as_valid_listing",
                HomeShellSetup.hasPsHeader(mitv4HeaderOnly) && !HomeShellSetup.hasValidPsListing(mitv4HeaderOnly)
                && HomeShellSetup.parseBridgePids(mitv4HeaderOnly).isEmpty());
        check("valid_system_process_list_without_bridge_accepted",
                HomeShellSetup.hasValidPsListing(validNoBridge) && HomeShellSetup.parseBridgePids(validNoBridge).isEmpty());
        check("similar_cmdline_identity_rejected",
                HomeShellSetup.isBridgeCmdline("marcotv-task-bridge\0")
                && HomeShellSetup.isBridgeCmdline("app_process /system/bin com.pilipala.tvhome.TaskBridgeServer 10059 0")
                && !HomeShellSetup.isBridgeCmdline("marcotv-task-bridge-other")
                && !HomeShellSetup.isBridgeCmdline("app_process /system/bin com.pilipala.tvhome.TaskBridgeServerFake 10059 0"));
        List<String> mitv4Cmds = new ArrayList<>();
        HomeShellSetup mitv4Shell = new HomeShellSetup(cmd -> {
            mitv4Cmds.add(cmd);
            if (cmd.equals("id -u")) return "2000";
            if (cmd.startsWith("ps -A ")) return mitv4HeaderOnly;
            if (cmd.equals("ps 2>/dev/null")) return validNoBridge
                    + "shell     17574 1     1523480 48204 SyS_epoll_ 00000000 S marcotv-task-bridge\n";
            if (cmd.startsWith("tr ") && cmd.contains("/proc/17574/")) return "marcotv-task-bridge";
            return "";
        }, 0);
        mitv4Shell.authenticate();
        HomeShellSetup.ProcessListStatus mitv4Status = mitv4Shell.startTaskBridge("/data/app/com.pilipala.tvhome-1/base.apk", 10059);
        check("mitv4_header_only_falls_back_to_plain_ps",
                mitv4Status == HomeShellSetup.ProcessListStatus.VERIFIED
                && mitv4Cmds.contains("ps 2>/dev/null")
                && mitv4Cmds.contains("kill 17574 2>/dev/null"));
        List<String> validEmptyCmds = new ArrayList<>();
        HomeShellSetup validNoBridgeShell = new HomeShellSetup(cmd -> {
            validEmptyCmds.add(cmd);
            if (cmd.equals("id -u")) return "2000";
            if (cmd.startsWith("ps -A ")) return validNoBridge;
            return "";
        }, 0);
        validNoBridgeShell.authenticate();
        HomeShellSetup.ProcessListStatus emptyStatus = validNoBridgeShell.startTaskBridge("/data/app/com.pilipala.tvhome-1/base.apk", 10059);
        check("valid_list_without_bridge_does_not_fallback_or_kill",
                emptyStatus == HomeShellSetup.ProcessListStatus.VERIFIED
                && !validEmptyCmds.contains("ps 2>/dev/null")
                && validEmptyCmds.stream().noneMatch(c -> c.startsWith("kill ")));
        List<String> unusableCmds = new ArrayList<>();
        HomeShellSetup unusableShell = new HomeShellSetup(cmd -> {
            unusableCmds.add(cmd);
            if (cmd.equals("id -u")) return "2000";
            if (cmd.startsWith("ps -A ")) return mitv4HeaderOnly;
            if (cmd.equals("ps 2>/dev/null")) return "";
            return "";
        }, 0);
        unusableShell.authenticate();
        HomeShellSetup.ProcessListStatus unusableStatus = unusableShell.startTaskBridge("/data/app/com.pilipala.tvhome-1/base.apk", 10059);
        check("both_ps_unusable_reports_unverifiable",
                unusableStatus == HomeShellSetup.ProcessListStatus.UNVERIFIABLE
                && unusableCmds.contains("ps 2>/dev/null")
                && unusableCmds.stream().noneMatch(c -> c.startsWith("kill ")));
        List<String> fakeNameCmds = new ArrayList<>();
        HomeShellSetup fakeNameShell = new HomeShellSetup(cmd -> {
            fakeNameCmds.add(cmd);
            if (cmd.equals("id -u")) return "2000";
            if (cmd.startsWith("ps -A ")) return mitv4HeaderOnly
                    + "root 1 0 1420 804 0 0 S /init\n"
                    + "shell 18888 1 1523480 48204 0 0 S marcotv-task-br\n"
                    + "shell 18889 1 1523480 48204 0 0 S com.pilipala.tvhome.TaskBridgeServerFake\n";
            if (cmd.startsWith("tr ") && cmd.contains("/proc/18888/")) return "marcotv-task-bridge-other";
            return "";
        }, 0);
        fakeNameShell.authenticate();
        HomeShellSetup.ProcessProbe fakeProbe = fakeNameShell.probeBridgeProcesses();
        check("similar_name_wrong_identity_never_matched_or_killed",
                fakeProbe.status == HomeShellSetup.ProcessListStatus.VERIFIED
                && fakeProbe.bridgePids.isEmpty()
                && fakeNameCmds.stream().noneMatch(c -> c.startsWith("kill ")));
        int[] exitPoll = {0};
        HomeShellSetup exitingShell = new HomeShellSetup(cmd -> {
            if (cmd.equals("id -u")) return "2000";
            if (cmd.startsWith("ps -A ")) {
                exitPoll[0]++;
                return exitPoll[0] == 1
                        ? validNoBridge + "shell 19999 1 1523480 48204 0 0 S marcotv-task-bridge\n"
                        : validNoBridge;
            }
            if (cmd.startsWith("tr ") && cmd.contains("/proc/19999/")) return "marcotv-task-bridge";
            return "";
        }, 0);
        exitingShell.authenticate();
        HomeShellSetup.ProcessProbe firstPoll = exitingShell.probeBridgeProcesses();
        HomeShellSetup.ProcessProbe secondPoll = exitingShell.probeBridgeProcesses();
        check("service_exiting_after_start_not_seen_on_recheck",
                firstPoll.status == HomeShellSetup.ProcessListStatus.VERIFIED && firstPoll.bridgePids.equals(Collections.singletonList("19999"))
                && secondPoll.status == HomeShellSetup.ProcessListStatus.VERIFIED && secondPoll.bridgePids.isEmpty());
        // Codex #3C: HomeShellSetup OEM enable/restore strictly uses --user <user> with NO un-scoped fallback!
        List<String> oemCmds = new ArrayList<>();
        HomeShellSetup oemShell = new HomeShellSetup(cmd -> { oemCmds.add(cmd); return "id -u".equals(cmd) ? "2000" : ""; }, 10);
        oemShell.authenticate();
        oemCmds.clear();
        oemShell.enableOemPackage("com.oem.home");
        oemShell.restoreOemPackageState("com.oem.home", 0);
        oemShell.restoreOemPackageState("com.oem.home", 1);
        oemShell.restoreOemPackageState("com.oem.home", 2);
        oemShell.restoreOemPackageState("com.oem.home", 3);
        oemShell.restoreOemPackageState("com.oem.home", 4);
        boolean badStateRejected = false;
        try { oemShell.restoreOemPackageState("com.oem.home", 99); } catch (IllegalArgumentException expected) { badStateRejected = true; }
        check("oem_shell_restore_strictly_user_scoped_no_unscoped_fallback",
                oemCmds.equals(Arrays.asList(
                        "pm enable --user 10 'com.oem.home'",
                        "pm default-state --user 10 'com.oem.home'",
                        "pm enable --user 10 'com.oem.home'",
                        "pm disable --user 10 'com.oem.home'",
                        "pm disable-user --user 10 'com.oem.home'",
                        "pm disable-until-used --user 10 'com.oem.home'"))
                && oemCmds.stream().noneMatch(c -> c.contains("||"))
                && badStateRejected);
        System.out.println("OK "+passed+" automatic HOME execution checks (simulated firmware, not device proof)");
    }
}
class BuildEdition { static boolean RESTRICTED; }
class HomeKeyService { static boolean connected; }
class Diagnostics { static void event(String value){} }
class SystemIntegration {
    static boolean secure,nativeHome;
    static boolean connectionAllowed;static int removed;
    static final class OemExperimentSnapshot {
        final int userId; final String priorHome, oemPackage; final int priorState;
        OemExperimentSnapshot(int u,String h,String p,int s){userId=u;priorHome=h;oemPackage=p;priorState=s;}
    }
    static final class OemDisablePlan {
        final String blockedMessage; final OemExperimentSnapshot snapshot;
        OemDisablePlan(String b,OemExperimentSnapshot s){blockedMessage=b;snapshot=s;}
        boolean allowed(){return blockedMessage==null&&snapshot!=null;}
    }
    static int currentUserId(){return android.os.Process.myUid()/100000;}
    static boolean authorized(Context c){return secure;}
    static boolean homeIsOurs(Context c){return nativeHome;}
    static void recover(Context c){HomeSetup.service=HomeSetup.serviceAllowed;HomeKeyService.connected=HomeSetup.service&&connectionAllowed;}
    static boolean removeOwnHomeService(Context c){removed++;HomeSetup.service=false;return true;}
    static void startRecovery(Context c){}
    static OemExperimentSnapshot readValidOemExperimentSnapshot(Context c,int userId){return null;}
    static OemDisablePlan prepareOemExperimentDisable(Context c,int u,String m,String f,java.util.List<OemLauncherExperimentPolicy.VerifiedDevice> a){return new OemDisablePlan("blocked",null);}
    static void beginOemExperimentDisableLocal(Context c,OemExperimentSnapshot s){}
    static String verifyOemExperimentDisableLocal(Context c,Boolean ov){return null;}
    static String finalizeOemExperimentDisableSuccessLocal(Context c,OemExperimentSnapshot s){return "ok";}
    static String finalizeOemExperimentDisableRollbackLocal(Context c,OemExperimentSnapshot s,String r){return r;}
    static String finalizeOemExperimentRestoreLocal(Context c,OemExperimentSnapshot s){return "restored";}
}
class HomeSetup {
    static final String OWN="com.pilipala.tvhome/.HomeActivity";
    static boolean service,compat,paused,accepted,serviceAllowed; static int begins;
    static void begin(Context c){begins++;paused=false;}
    static void acceptNative(Context c){accepted=true;}
    static void requestCompatibility(Context c){begin(c);compat=true;}
    static void finishCompatibilityConsent(Context c){compat=service;}
    static boolean serviceEnabled(Context c){return service;}
    static boolean paused(Context c){return paused;}
    static void pause(Context c,String reason){paused=true;compat=false;}
    static Prefs prefs(Context c){return new Prefs();}
    static class Prefs {
        boolean getBoolean(String key,boolean fallback){
            if(key.equals(HomeSetupSnapshot.PREFIX+"saved"))return HomeSetupSnapshot.saved;
            if(key.equals(HomeSetupSnapshot.PREFIX+"native"))return HomeSetupSnapshot.beforeNative;
            return fallback;
        }
    }
}
class HomeSetupSnapshot {
    static final String PREFIX="auto_home_before_";
    static boolean saved,beforeNative; static String original,recorded; static int localRestores;
    static void capture(Context c){if(!saved){beforeNative=SystemIntegration.nativeHome;saved=true;}}
    static String recordedComponent(Context c){return recorded==null?"":recorded;}
    static boolean hasRecordedTarget(Context c){return recorded!=null&&!recorded.isEmpty();}
    static String target(Context c){return original;}
    static boolean matches(Context c,String target){return target.equals(LocalAdbWire.current);}
    static boolean restoreLocal(Context c){localRestores++;return true;}
    static void clear(Context c){saved=false;}
}
class LocalAdbWire {
    interface Hook {void run(String command)throws Exception;}
    static Hook hook;
    static List<String> commands=Collections.synchronizedList(new ArrayList<>());
    static boolean grant,select,modern,legacy,connectFails,authorizationFails; static volatile boolean closed;
    static int connects; static String uid,current;
    interface Status{void accept(String text);}
    static java.security.KeyPair key(java.io.File f){return null;}
    void connect(String host,int port,java.security.KeyPair key,Status status)throws Exception{
        connects++;
        if(connectFails)throw new java.io.IOException("unavailable");
        if(authorizationFails){status.accept("confirm");throw new java.io.IOException("unconfirmed");}
    }
    String shell(String command)throws Exception{
        commands.add(command); if(hook!=null)hook.run(command);
        if(closed)throw new java.io.IOException("closed");
        if(command.equals("id -u"))return uid;
        if(command.startsWith("pm grant")){SystemIntegration.secure=grant;return grant?"":"SecurityException";}
        if(command.equals("cmd package help"))return modern?"  set-home-activity [--user USER_ID] TARGET":"Unknown command";
        if(command.equals("pm help"))return legacy?"  set-home-activity [--user USER_ID] TARGET":"Unknown command";
        if(command.contains(" set-home-activity ")&&select){
            current=command.substring(command.indexOf('\'')+1,command.lastIndexOf('\''));
            SystemIntegration.nativeHome=current.equals(HomeSetup.OWN);
        }
        return "";
    }
    void close(){closed=true;}
}
