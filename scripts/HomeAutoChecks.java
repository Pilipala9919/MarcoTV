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
        check("selection_rejection_falls_back_to_compat", !HomeSetup.accepted && HomeSetup.service && HomeSetup.compat);
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
        reset(false); HomeSetupSnapshot.saved=true; HomeSetupSnapshot.original=""; SystemIntegration.nativeHome=true; run(true);
        check("missing_target_never_guessed", !selected() && HomeSetupSnapshot.saved);
        reset(false); HomeSetupSnapshot.saved=true; HomeSetupSnapshot.beforeNative=true; HomeSetupSnapshot.original=""; SystemIntegration.nativeHome=true; run(true);
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
        System.out.println("OK "+passed+" automatic HOME execution checks (simulated firmware, not device proof)");
    }
}
class BuildEdition { static boolean RESTRICTED; }
class HomeKeyService { static boolean connected; }
class Diagnostics { static void event(String value){} }
class SystemIntegration {
    static boolean secure,nativeHome;
    static boolean connectionAllowed;static int removed;
    static boolean authorized(Context c){return secure;}
    static boolean homeIsOurs(Context c){return nativeHome;}
    static void recover(Context c){HomeSetup.service=HomeSetup.serviceAllowed;HomeKeyService.connected=HomeSetup.service&&connectionAllowed;}
    static boolean removeOwnHomeService(Context c){removed++;HomeSetup.service=false;return true;}
    static void startRecovery(Context c){}
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
    static boolean saved,beforeNative; static String original; static int localRestores;
    static void capture(Context c){if(!saved){beforeNative=SystemIntegration.nativeHome;saved=true;}}
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
