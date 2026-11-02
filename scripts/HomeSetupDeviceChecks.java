// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.WindowManager;
import java.lang.reflect.*;
import java.util.*;

/** Bounded setup checks; restores preferences, never selects HOME or changes system services. */
public final class HomeSetupDeviceChecks extends Instrumentation {
    MainActivity host; int passed;
    void check(String name, boolean ok) { if(!ok)throw new AssertionError(name);passed++;Bundle b=new Bundle();b.putString("stream","PASS "+name+"\n");sendStatus(0,b); }
    Object field(Object o,String key)throws Exception { Field f=o.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(o); }
    void ui(Runnable r){ final Throwable[] fail={null};runOnMainSync(()->{try{r.run();}catch(Throwable e){fail[0]=e;}});if(fail[0]!=null)throw new AssertionError(fail[0]); }
    void restore(SharedPreferences p,Map<String,?> saved){SharedPreferences.Editor e=p.edit().clear();for(Map.Entry<String,?> x:saved.entrySet()){Object v=x.getValue();if(v instanceof Boolean)e.putBoolean(x.getKey(),(Boolean)v);else if(v instanceof Integer)e.putInt(x.getKey(),(Integer)v);else if(v instanceof Long)e.putLong(x.getKey(),(Long)v);else if(v instanceof Float)e.putFloat(x.getKey(),(Float)v);else if(v instanceof String)e.putString(x.getKey(),(String)v);else if(v instanceof Set)e.putStringSet(x.getKey(),(Set<String>)v);}e.commit();}
    public void onCreate(Bundle b){super.onCreate(b);start();}
    @Override public void callActivityOnResume(Activity activity){
        super.callActivityOnResume(activity);
        if(activity instanceof MainActivity)host=(MainActivity)activity;
    }
    public void onStart(){Bundle output=new Bundle();Map<String,?> system=null,home=null;String originalDefault=null;
        try {
            host=(MainActivity)startActivitySync(new Intent().setClassName("com.pilipala.tvhome","com.pilipala.tvhome.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Thread.sleep(700);system=new HashMap<>(HomeSetup.prefs(host).getAll());home=new HashMap<>(host.preferences.getAll());originalDefault=SystemIntegration.currentHome(host);
            final Map<String,?> before=system;
            final String originalDefaultForTest=originalDefault;
            ui(()->{
                host.exitMenus();host.defaultHome.show();
                check("opening_setup_is_read_only",before.equals(HomeSetup.prefs(host).getAll()));
                HomeSetup.route(host);HomeSetup.summary(host);
                check("capability_and_status_queries_are_read_only",before.equals(HomeSetup.prefs(host).getAll()));
                try {
                    TvDialog root=(TvDialog)field(host.dialogs,"activeDialog");
                    if(root==null)throw new IllegalStateException("Setup dialog missing: destroyed="+host.isDestroyed()+", finishing="+host.isFinishing()+", foreground="+host.foreground);
                    check("setup_root_has_two_choices",((String[])field(field(root,"spec"),"items")).length==2);
                    host.defaultHome.localAuthorization();
                    check("auto_consent_opens_without_mutation",host.isPage("home_local_consent")&&before.equals(HomeSetup.prefs(host).getAll()));
                    TvDialog progress=new TvDialog.Builder(host).setPageId("test_progress").setTitle("test").create();
                    final boolean[] left={false};progress.onLeave(()->left[0]=true);host.showDialog(progress);
                    host.dialogs.replace(progress,new TvDialog.Builder(host).setPageId("test_result").setTitle("test").create());
                    check("completed_operation_releases_progress",left[0]&&host.isPage("test_result"));
                    host.panelWindow().onBackPressed();
                    check("back_does_not_reopen_completed_operation",host.isPage("home_local_consent"));
                    host.panelWindow().onBackPressed();
                    HomeSetupSnapshot.clear(host);HomeSetupSnapshot.capture(host);
                    String savedTarget=HomeSetupSnapshot.target(host);
                    check("snapshot_capture_preserves_system_home",originalDefaultForTest.equals(SystemIntegration.currentHome(host)));
                    HomeSetupSnapshot.capture(host);
                    check("snapshot_retry_keeps_first_target",savedTarget.equals(HomeSetupSnapshot.target(host)));
                    HomeSetupSnapshot.clear(host);
                    check("snapshot_clear_removes_only_snapshot",!HomeSetup.prefs(host).contains(HomeSetupSnapshot.PREFIX+"saved"));
                    restore(HomeSetup.prefs(host),before);
                    host.defaultHome.help();
                    TvDialog more=(TvDialog)field(host.dialogs,"activeDialog");
                    check("advanced_choices_live_in_more_page",((String[])field(field(more,"spec"),"items")).length==8);
                    host.defaultHome.assistant();
                    TvDialog assistant=(TvDialog)field(host.dialogs,"activeDialog");
                    Object content=field(field(assistant,"spec"),"view");
                    check("phone_guide_has_qr_and_instructions",content instanceof android.widget.LinearLayout&&((android.widget.LinearLayout)content).getChildCount()==2);
                    host.panelWindow().onBackPressed();check("assistant_back_returns_to_more",host.isPage("default_home_help"));
                    host.panelWindow().onBackPressed();check("more_back_returns_to_setup",host.isPage("default_home"));
                } catch(Exception e){throw new RuntimeException(e);}
                SharedPreferences p=HomeSetup.prefs(host);
                p.edit().putBoolean("setup_accessibility_pending",true).commit();
                HomeSetup.finishCompatibilityConsent(host);
                check("consent_return_clears_pending",!p.getBoolean("setup_accessibility_pending",false));
                check("boot_choice_requires_available_route",p.getBoolean("boot_home",false)==(HomeSetup.serviceEnabled(host)||SystemIntegration.homeIsOurs(host)));
                p.edit().putBoolean("setup_requested",true).putBoolean("setup_paused",false).remove("setup_home_seen").remove("setup_boot_observed").remove("setup_boot_confirmed").commit();
                HomeSetup.armHomeTest(host);
                HomeSetup.observeHomeIntent(host,new Intent().setClass(host,MainActivity.class));
                check("normal_launch_does_not_pass_home_test",!p.contains("setup_home_seen"));
                HomeSetup.observeHomeIntent(host,new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
                check("home_evidence_requires_available_boot_counter",p.contains("setup_home_seen")== (HomeSetup.bootCount(host)>=0));
                p.edit().remove("setup_home_seen").putLong("setup_home_deadline",0).commit();
                HomeSetup.observeHomeIntent(host,new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
                check("expired_test_does_not_pass",!p.contains("setup_home_seen"));
                p.edit().putInt("setup_boot_armed",HomeSetup.bootCount(host)).commit();HomeSetup.confirmBoot(host);
                check("cannot_confirm_unobserved_reboot",!p.contains("setup_boot_confirmed"));
                p.edit().putBoolean("adb_restore",true).putLong("adb_restore_until",0).commit();
                HomeSetup.pause(host,"test");
                check("pause_blocks_home_and_boot",HomeSetup.paused(host)&&!p.getBoolean("home_compat",true)&&!p.getBoolean("boot_home",true));
                check("pause_does_not_change_adb_choice",p.getBoolean("adb_restore",false));
                check("pause_survives_reconcile",pauseRemains());
                check("paused_boot_does_not_launch",!HomeSetup.shouldOpenAfterBoot(host,true));
                p.edit().putLong("adb_restore_until",System.currentTimeMillis()-1).commit();
                check("expired_adb_restore_stops",SystemIntegration.expireAdbRestore(host)&&!p.getBoolean("adb_restore",true));
                check("expiration_is_idempotent",!SystemIntegration.expireAdbRestore(host));
                p.edit().putBoolean("adb_restore",true).putLong("adb_restore_until",0).commit();
                check("legacy_long_term_is_preserved",!SystemIntegration.expireAdbRestore(host)&&p.getBoolean("adb_restore",false));
                check("locked_boot_never_launches_home",!HomeSetup.shouldOpenAfterBoot(host,false));
                try {
                    check("local_authorizer_rejects_remote",rejectRemote());
                    check("local_authorizer_allows_loopback",LocalAdbWire.address("127.0.0.1").isLoopbackAddress());
                    host.preferences.edit().putBoolean("ambient_enabled",true).putInt("ambient_sleep_minutes",30).commit();
                    host.screensaver.changed(true);
                    check("idle_starts_with_keep_screen",(host.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)!=0);
                    ((Runnable)field(host.screensaver,"releaseScreen")).run();
                    check("idle_handoff_releases_keep_screen",(host.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)==0);
                    host.screensaver.changed(false);
                    check("leaving_idle_restores_keep_screen",(host.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)!=0);
                    boolean bounds=true;for(long ms=0;ms<77*60000L;ms+=1000)bounds&=Math.abs(ClockScene.driftX(ms))<=36.01f&&Math.abs(ClockScene.driftY(ms))<=22.01f;
                    check("clock_drift_bounded_over_full_cycle",bounds);
                } catch(Exception e){throw new RuntimeException(e);}
            });
            check("test_did_not_replace_default_home",originalDefault.equals(SystemIntegration.currentHome(host)));
            output.putString("stream","OK "+passed+" home setup device checks\n");
        }catch(Throwable e){output.putString("stream","FAIL "+android.util.Log.getStackTraceString(e)+"\n");}
        finally {
            if(host!=null&&system!=null){final Map<String,?> s=system,h=home;ui(()->{host.exitMenus();restore(HomeSetup.prefs(host),s);restore(host.preferences,h);host.screensaver.pause();host.screensaver.resume();host.armIdle();SystemIntegration.startRecovery(host);});}
        }
        finish(-1,output);
    }
    boolean pauseRemains(){HomeSetup.reconcile(host);return HomeSetup.paused(host);}
    boolean rejectRemote(){try{LocalAdbWire.address("8.8.8.8");return false;}catch(Exception e){return true;}}
}
