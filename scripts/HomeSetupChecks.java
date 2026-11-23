// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

public final class HomeSetupChecks {
    static int passed;
    static void check(String name, boolean value) {
        if (!value) throw new AssertionError(name);
        passed++; System.out.println("PASS " + name);
    }
    public static void main(String[] args) {
        check("paused_wins_over_available_routes",HomeSetupPolicy.route(true,true,true,true,true,true)==HomeSetupPolicy.Route.PAUSED);
        check("native_needs_no_accessibility",HomeSetupPolicy.route(false,true,true,false,false,false)==HomeSetupPolicy.Route.NATIVE_READY);
        check("active_compat_does_not_repeat_picker",HomeSetupPolicy.route(false,false,true,true,true,true)==HomeSetupPolicy.Route.COMPAT_READY);
        check("standard_picker_before_extra_permission",HomeSetupPolicy.route(false,false,true,false,false,true)==HomeSetupPolicy.Route.SYSTEM_PICKER);
        check("manual_accessibility_without_adb",HomeSetupPolicy.route(false,false,false,false,false,true)==HomeSetupPolicy.Route.ACCESSIBILITY);
        check("blocked_standard_routes_use_enhanced_help",HomeSetupPolicy.route(false,false,false,false,false,false)==HomeSetupPolicy.Route.ENHANCED);
        check("disabled_service_not_ready",HomeSetupPolicy.route(false,false,false,true,false,false)==HomeSetupPolicy.Route.ENHANCED);
        check("start_action_routes_accessibility_to_compatibility",
                HomeSetupPolicy.startAction(HomeSetupPolicy.Route.ACCESSIBILITY)==HomeSetupPolicy.StartAction.COMPATIBILITY);
        check("start_action_routes_enhanced_to_local_authorization",
                HomeSetupPolicy.startAction(HomeSetupPolicy.Route.ENHANCED)==HomeSetupPolicy.StartAction.LOCAL_AUTHORIZATION);
        check("paused_resume_reroutes_to_picker",
                HomeSetupPolicy.effectiveStartRoute(HomeSetupPolicy.Route.PAUSED,false,true,false,false,true)==HomeSetupPolicy.Route.SYSTEM_PICKER);
        check("paused_resume_reroutes_to_accessibility",
                HomeSetupPolicy.effectiveStartRoute(HomeSetupPolicy.Route.PAUSED,false,false,false,false,true)==HomeSetupPolicy.Route.ACCESSIBILITY);
        check("paused_resume_reroutes_to_native_ready",
                HomeSetupPolicy.effectiveStartRoute(HomeSetupPolicy.Route.PAUSED,true,true,false,false,true)==HomeSetupPolicy.Route.NATIVE_READY);
        for (int flags=0;flags<64;flags++) {
            boolean requested=(flags&1)!=0,paused=(flags&2)!=0,nativeHome=(flags&4)!=0,compat=(flags&8)!=0,service=(flags&16)!=0,unlocked=(flags&32)!=0;
            check("boot_combination_"+flags,HomeSetupPolicy.bootFallback(requested,paused,nativeHome,compat,service,unlocked)
                    ==(requested&&!paused&&!nativeHome&&compat&&service&&unlocked));
        }
        check("home_arrival_in_window",HomeSetupPolicy.homeEvidence(true,3,3,10000,100000,20000,true,HomeSetupPolicy.HomeReturnSource.SYSTEM_HOME));
        check("window_fallback_counts_in_home_test",HomeSetupPolicy.homeEvidence(true,3,3,10000,100000,20000,true,HomeSetupPolicy.HomeReturnSource.WINDOW_FALLBACK));
        check("compat_key_counts_in_home_test",HomeSetupPolicy.homeEvidence(true,3,3,10000,100000,20000,true,HomeSetupPolicy.HomeReturnSource.COMPAT_KEY));
        check("compat_broadcast_counts_in_home_test",HomeSetupPolicy.homeEvidence(true,3,3,10000,100000,20000,true,HomeSetupPolicy.HomeReturnSource.COMPAT_BROADCAST));
        check("ordinary_app_launch_not_home",!HomeSetupPolicy.homeEvidence(true,3,3,10000,100000,20000,true,HomeSetupPolicy.HomeReturnSource.NONE));
        check("expired_home_test",!HomeSetupPolicy.homeEvidence(true,3,3,10000,100000,100001,true,HomeSetupPolicy.HomeReturnSource.SYSTEM_HOME));
        check("home_test_does_not_survive_reboot",!HomeSetupPolicy.homeEvidence(true,3,4,10000,100000,20000,true,HomeSetupPolicy.HomeReturnSource.SYSTEM_HOME));
        check("api23_missing_boot_count_verifies_in_session",HomeSetupPolicy.homeEvidence(true,-1,-1,10000,100000,25000,true,HomeSetupPolicy.HomeReturnSource.COMPAT_KEY));
        check("api23_invalidated_session_cannot_verify",!HomeSetupPolicy.homeEvidence(true,-1,-1,10000,100000,25000,false,HomeSetupPolicy.HomeReturnSource.COMPAT_KEY));
        check("api23_monotonic_regression_cannot_verify",!HomeSetupPolicy.homeEvidence(true,-1,-1,50000,140000,10000,true,HomeSetupPolicy.HomeReturnSource.SYSTEM_HOME));
        check("mixed_known_and_unknown_boot_cannot_verify",
                !HomeSetupPolicy.homeEvidence(true,-1,4,10000,100000,20000,true,HomeSetupPolicy.HomeReturnSource.SYSTEM_HOME)
                &&!HomeSetupPolicy.homeEvidence(true,3,-1,10000,100000,20000,true,HomeSetupPolicy.HomeReturnSource.SYSTEM_HOME));
        check("unarmed_home_not_verified",!HomeSetupPolicy.homeEvidence(false,3,3,10000,100000,20000,true,HomeSetupPolicy.HomeReturnSource.SYSTEM_HOME));
        check("boot_recovery_requires_pending_request",
                HomeSetupPolicy.bootRecoveryEvidence(true,false,true,3,4,4,5000,65000,12000)
                &&!HomeSetupPolicy.bootRecoveryEvidence(true,false,false,3,4,4,5000,65000,12000));
        check("boot_recovery_works_on_api23_with_valid_request",
                HomeSetupPolicy.bootRecoveryEvidence(true,false,true,-1,-1,-1,5000,65000,12000));
        check("boot_recovery_rejects_same_boot_or_expired",
                !HomeSetupPolicy.bootRecoveryEvidence(true,false,true,3,3,3,5000,65000,12000)
                &&!HomeSetupPolicy.bootRecoveryEvidence(true,false,true,3,4,4,5000,65000,65001));
        check("native_boot_observation_only_for_first_native_home",
                HomeSetupPolicy.nativeBootObservation(true,false,true,3,4,15000,HomeSetupPolicy.HomeReturnSource.SYSTEM_HOME,false,false)
                &&!HomeSetupPolicy.nativeBootObservation(true,false,false,3,4,15000,HomeSetupPolicy.HomeReturnSource.SYSTEM_HOME,false,false)
                &&!HomeSetupPolicy.nativeBootObservation(true,false,true,3,4,15000,HomeSetupPolicy.HomeReturnSource.COMPAT_KEY,false,false)
                &&!HomeSetupPolicy.nativeBootObservation(true,false,true,3,4,15000,HomeSetupPolicy.HomeReturnSource.WINDOW_FALLBACK,false,false)
                &&!HomeSetupPolicy.nativeBootObservation(true,false,true,3,4,15000,HomeSetupPolicy.HomeReturnSource.SYSTEM_HOME,true,false)
                &&!HomeSetupPolicy.nativeBootObservation(true,false,true,3,4,15000,HomeSetupPolicy.HomeReturnSource.SYSTEM_HOME,false,true));
        check("boot_changed_supports_boot_count_and_api23_boot_id",
                HomeSetupPolicy.bootChanged(3,4,"","")
                &&!HomeSetupPolicy.bootChanged(3,3,"a","b")
                &&HomeSetupPolicy.bootChanged(-1,-1,"boot-1","boot-2")
                &&!HomeSetupPolicy.bootChanged(-1,-1,"boot-1","boot-1")
                &&!HomeSetupPolicy.bootChanged(-1,-1,"","boot-2")
                &&!HomeSetupPolicy.bootChanged(-1,-1,"",""));
        check("native_boot_observation_accepts_api23_boot_id_change",
                HomeSetupPolicy.nativeBootObservation(true,false,true,
                        HomeSetupPolicy.bootChanged(-1,-1,"boot-1","boot-2"),15000,
                        HomeSetupPolicy.HomeReturnSource.SYSTEM_HOME,false,false)
                &&!HomeSetupPolicy.nativeBootObservation(true,false,true,
                        HomeSetupPolicy.bootChanged(-1,-1,"boot-1","boot-1"),15000,
                        HomeSetupPolicy.HomeReturnSource.SYSTEM_HOME,false,false));
        check("new_boot_arrival_recordable",HomeSetupPolicy.bootEvidence(3,4,10000,true));
        check("same_boot_not_reboot",!HomeSetupPolicy.bootEvidence(3,3,10000,true));
        check("manual_launch_not_auto_boot",!HomeSetupPolicy.bootEvidence(3,4,10000,false));
        check("late_launch_not_auto_boot",!HomeSetupPolicy.bootEvidence(3,4,180000,true));
        check("unknown_boot_not_reboot",!HomeSetupPolicy.bootEvidence(-1,4,10000,true));
        check("external_service_removal_pauses",HomeSetupPolicy.pauseForExternalChange(true,true,false,false));
        check("own_service_rebind_allowed",!HomeSetupPolicy.pauseForExternalChange(true,true,true,false));
        check("other_accessibility_change_ignored",!HomeSetupPolicy.pauseForExternalChange(true,true,false,true));
        check("disabled_feature_not_reactivated",!HomeSetupPolicy.pauseForExternalChange(false,true,false,false));
        check("standby_kill_restores_when_asleep",HomeSetupPolicy.restoreCompatAfterStandby(true,true,false,true,false,true,true,false,false,false));
        check("standby_kill_never_restores_while_awake",!HomeSetupPolicy.restoreCompatAfterStandby(true,true,false,true,false,true,true,false,false,true));
        check("standby_kill_never_restores_without_auth",!HomeSetupPolicy.restoreCompatAfterStandby(false,true,false,true,false,true,true,false,false,false));
        check("standby_kill_never_restores_when_locked",!HomeSetupPolicy.restoreCompatAfterStandby(true,false,false,true,false,true,true,false,false,false));
        check("standby_kill_never_restores_when_paused",!HomeSetupPolicy.restoreCompatAfterStandby(true,true,true,true,false,true,true,false,false,false));
        check("standby_kill_never_restores_when_compat_off",!HomeSetupPolicy.restoreCompatAfterStandby(true,true,false,false,false,true,true,false,false,false));
        check("standby_kill_never_restores_during_consent",!HomeSetupPolicy.restoreCompatAfterStandby(true,true,false,true,true,true,true,false,false,false));
        check("standby_kill_requires_prior_seen_present",!HomeSetupPolicy.restoreCompatAfterStandby(true,true,false,true,false,false,true,false,false,false));
        check("standby_kill_skips_clean_unbind",!HomeSetupPolicy.restoreCompatAfterStandby(true,true,false,true,false,true,false,false,false,false));
        check("standby_kill_respects_pending_prompt",!HomeSetupPolicy.restoreCompatAfterStandby(true,true,false,true,false,true,true,true,false,false));
        check("standby_kill_noop_when_already_enabled",!HomeSetupPolicy.restoreCompatAfterStandby(true,true,false,true,false,true,true,false,true,false));
        check("abrupt_stop_prompts_instead_of_silent_pause",HomeSetupPolicy.compatResetAfterStop(true,false,true,false,true,true,false,false));
        check("clean_unbind_does_not_prompt",!HomeSetupPolicy.compatResetAfterStop(true,false,true,false,true,false,false,false));
        check("paused_does_not_prompt_compat_reset",!HomeSetupPolicy.compatResetAfterStop(true,true,true,false,true,true,false,false));
        check("connected_service_does_not_prompt",!HomeSetupPolicy.compatResetAfterStop(true,false,true,false,true,true,false,true));
        check("enabled_service_does_not_prompt",!HomeSetupPolicy.compatResetAfterStop(true,false,true,false,true,true,true,false));
        check("wake_fallback_returns_from_oem_home",HomeSetupPolicy.wakeFallback(true,false,false,true,false,true,true,false,true));
        check("wake_fallback_respects_user_wake_off",!HomeSetupPolicy.wakeFallback(false,false,false,true,false,true,true,false,true));
        check("wake_fallback_respects_pause",!HomeSetupPolicy.wakeFallback(true,true,false,true,false,true,true,false,true));
        check("wake_fallback_skips_native_home",!HomeSetupPolicy.wakeFallback(true,false,true,true,false,true,true,false,true));
        check("wake_fallback_requires_compat",!HomeSetupPolicy.wakeFallback(true,false,false,false,false,true,true,false,true));
        check("wake_fallback_respects_native_keys",!HomeSetupPolicy.wakeFallback(true,false,false,true,true,true,true,false,true));
        check("wake_fallback_requires_unlocked",!HomeSetupPolicy.wakeFallback(true,false,false,true,false,false,true,false,true));
        check("wake_fallback_requires_settled_gate",!HomeSetupPolicy.wakeFallback(true,false,false,true,false,true,false,false,true));
        check("wake_fallback_keeps_user_app",!HomeSetupPolicy.wakeFallback(true,false,false,true,false,true,true,true,true));
        check("wake_fallback_requires_home_or_standby_signal",!HomeSetupPolicy.wakeFallback(true,false,false,true,false,true,true,false,false));
        HomeInputGate gate=new HomeInputGate();
        gate.screen(false,1000);
        check("input_gate_tracks_screen_off",gate.isScreenOff()&&gate.remainingSettleMs(1000)==Long.MAX_VALUE);
        gate.screen(true,2000);
        check("input_gate_screen_on_settle_window",!gate.isScreenOff()&&gate.remainingSettleMs(2100)==600&&gate.remainingSettleMs(2700)==0);
        gate.power(2200);
        check("input_gate_power_extends_settle_window",gate.remainingSettleMs(2900)==1100&&gate.remainingSettleMs(4000)==0);
        System.out.println("OK "+passed+" home setup policy checks");
    }
}
