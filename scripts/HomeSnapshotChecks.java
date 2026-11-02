// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;
import android.content.*;
import android.content.pm.*;

/** Checks the real recovery-point code with an in-memory package/settings service. */
public final class HomeSnapshotChecks {
    static int passed;
    static Context c = new Context();
    static void check(String name,boolean ok){if(!ok)throw new AssertionError(name);passed++;}
    static void reset(){
        HomeSetup.p = new SharedPreferences(); HomeSetup.service=false;
        SystemIntegration.nativeHome=false;SystemIntegration.secure=true;
        SystemIntegration.current="com.vendor/.Home";SystemIntegration.removed=0;
        BuildEdition.RESTRICTED=false;c.pm.alias=2;c.pm.failAlias=false;
        c.pm.homes.clear();
        ResolveInfo r=new ResolveInfo();r.activityInfo=new ActivityInfo();
        r.activityInfo.packageName="com.vendor";r.activityInfo.name="com.vendor.Home";
        r.activityInfo.enabled=true;r.activityInfo.exported=true;r.activityInfo.applicationInfo=new ApplicationInfo();r.activityInfo.applicationInfo.enabled=true;
        c.pm.homes.add(r);
    }
    static boolean saved(){return HomeSetup.p.getBoolean(HomeSetupSnapshot.PREFIX+"saved",false);}
    public static void main(String[] args){
        reset();HomeSetupSnapshot.capture(c);
        check("only_real_enabled_exported_home_is_recorded",HomeSetupSnapshot.target(c).equals("com.vendor/.Home"));
        check("capture_does_not_change_alias_or_service",c.pm.alias==2&&!HomeSetup.service);
        SystemIntegration.current="com.other/.Home";HomeSetupSnapshot.capture(c);
        check("retry_does_not_overwrite_original",HomeSetupSnapshot.target(c).equals("com.vendor/.Home"));
        reset();SystemIntegration.nativeHome=true;HomeSetupSnapshot.capture(c);
        check("miui_fallback_not_recorded_when_already_own_home",HomeSetupSnapshot.target(c).isEmpty());
        reset();c.pm.homes.get(0).activityInfo.exported=false;HomeSetupSnapshot.capture(c);
        check("unexported_home_is_not_restore_target",HomeSetupSnapshot.target(c).isEmpty());
        reset();c.pm.homes.get(0).activityInfo.applicationInfo.enabled=false;HomeSetupSnapshot.capture(c);
        check("disabled_application_is_not_restore_target",HomeSetupSnapshot.target(c).isEmpty());
        reset();HomeSetupSnapshot.capture(c);c.pm.homes.clear();
        check("removed_home_is_rechecked_before_restore",HomeSetupSnapshot.target(c).isEmpty());
        reset();HomeSetupSnapshot.capture(c);
        HomeSetup.service=true;HomeSetup.p.edit().putBoolean("home_compat",true).putBoolean("boot_home",true).putBoolean("adb_restore",true).putString("unrelated","keep").commit();
        check("successful_local_rollback",HomeSetupSnapshot.restoreLocal(c));
        check("only_new_own_service_removed",SystemIntegration.removed==1&&!HomeSetup.service);
        check("own_feature_flags_restored",!HomeSetup.p.getBoolean("home_compat",true)&&!HomeSetup.p.getBoolean("boot_home",true));
        check("adb_and_unrelated_settings_retained",HomeSetup.p.getBoolean("adb_restore",false)&&HomeSetup.p.getString("unrelated","").equals("keep"));
        check("alias_state_restored",c.pm.alias==2);
        HomeSetupSnapshot.clear(c);check("clear_only_removes_recovery_fields",!saved()&&HomeSetup.p.getBoolean("adb_restore",false));
        reset();HomeSetup.service=true;HomeSetupSnapshot.capture(c);HomeSetupSnapshot.restoreLocal(c);
        check("preexisting_service_not_removed",HomeSetup.service&&SystemIntegration.removed==0);
        reset();HomeSetupSnapshot.capture(c);HomeSetup.service=true;SystemIntegration.secure=false;
        check("missing_permission_keeps_recovery_record",!HomeSetupSnapshot.restoreLocal(c)&&saved()&&HomeSetup.service);
        reset();HomeSetupSnapshot.capture(c);c.pm.failAlias=true;
        check("alias_failure_is_not_success",!HomeSetupSnapshot.restoreLocal(c)&&saved());
        reset();BuildEdition.RESTRICTED=true;HomeSetupSnapshot.capture(c);c.pm.failAlias=true;
        check("restricted_restore_never_touches_missing_alias",HomeSetupSnapshot.restoreLocal(c));
        reset();HomeSetup.p.edit().putBoolean("setup_paused",true).putString("setup_pause_reason","before").commit();
        HomeSetupSnapshot.capture(c);HomeSetup.p.edit().putString("setup_pause_reason","after").commit();HomeSetupSnapshot.restoreLocal(c);
        check("prior_pause_reason_preserved",HomeSetup.p.getBoolean("setup_paused",false)&&HomeSetup.p.getString("setup_pause_reason","").equals("before"));
        reset();SystemIntegration.current="com.vendor/com.vendor.Home";
        check("component_short_and_long_forms_match",HomeSetupSnapshot.matches(c,"com.vendor/.Home"));
        System.out.println("OK "+passed+" recovery snapshot checks (in-memory Android services)");
    }
}
class BuildEdition {static boolean RESTRICTED;}
class HomeSetup {
    static SharedPreferences p=new SharedPreferences();static boolean service;
    static SharedPreferences prefs(Context c){return p;}
    static boolean serviceEnabled(Context c){return service;}
    static void reconcile(Context c){}
}
class SystemIntegration {
    static String current;static boolean nativeHome,secure;static int removed;
    static String currentHome(Context c){return current;}
    static boolean homeIsOurs(Context c){return nativeHome;}
    static boolean authorized(Context c){return secure;}
    static boolean removeOwnHomeService(Context c){removed++;HomeSetup.service=false;return true;}
    static void startRecovery(Context c){}
}
