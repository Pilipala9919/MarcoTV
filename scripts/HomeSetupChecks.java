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
        check("guard_mode_window_capabilities",
                !HomeSetupPolicy.GuardMode.WINDOW_PULLBACK.requestsKeyFilter()
                &&!HomeSetupPolicy.GuardMode.WINDOW_PULLBACK.handlesDirectHomeKey()
                &&HomeSetupPolicy.GuardMode.WINDOW_PULLBACK.handlesHomeBroadcast()
                &&HomeSetupPolicy.GuardMode.WINDOW_PULLBACK.handlesWindowFallback()
                &&HomeSetupPolicy.GuardMode.WINDOW_PULLBACK.handlesWakeFallback());
        check("guard_mode_key_filter_capabilities",
                HomeSetupPolicy.GuardMode.KEY_FILTER.requestsKeyFilter()
                &&HomeSetupPolicy.GuardMode.KEY_FILTER.handlesDirectHomeKey()
                &&HomeSetupPolicy.GuardMode.KEY_FILTER.handlesHomeBroadcast()
                &&HomeSetupPolicy.GuardMode.KEY_FILTER.handlesWindowFallback()
                &&HomeSetupPolicy.GuardMode.KEY_FILTER.handlesWakeFallback());
        check("guard_mode_native_only_capabilities",
                !HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY.requestsKeyFilter()
                &&!HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY.handlesDirectHomeKey()
                &&!HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY.handlesHomeBroadcast()
                &&!HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY.handlesWindowFallback()
                &&!HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY.handlesWakeFallback());
        check("resolve_guard_mode_defaults_to_window_pullback",
                HomeSetupPolicy.resolveGuardMode("",false)==HomeSetupPolicy.GuardMode.WINDOW_PULLBACK
                &&HomeSetupPolicy.resolveGuardMode(null,false)==HomeSetupPolicy.GuardMode.WINDOW_PULLBACK
                &&HomeSetupPolicy.resolveGuardMode("unknown",false)==HomeSetupPolicy.GuardMode.WINDOW_PULLBACK);
        check("resolve_guard_mode_preserves_legacy_native_keys",
                HomeSetupPolicy.resolveGuardMode("",true)==HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY
                &&HomeSetupPolicy.resolveGuardMode("unknown",true)==HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY);
        check("resolve_guard_mode_honors_explicit_choice",
                HomeSetupPolicy.resolveGuardMode("window",true)==HomeSetupPolicy.GuardMode.WINDOW_PULLBACK
                &&HomeSetupPolicy.resolveGuardMode("key_filter",false)==HomeSetupPolicy.GuardMode.KEY_FILTER
                &&HomeSetupPolicy.resolveGuardMode("key_filter",true)==HomeSetupPolicy.GuardMode.KEY_FILTER
                &&HomeSetupPolicy.resolveGuardMode("native_only",false)==HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY);
        check("should_request_key_filter_requires_compat_unpaused_and_key_filter",
                HomeSetupPolicy.shouldRequestKeyFilter(true,false,HomeSetupPolicy.GuardMode.KEY_FILTER)
                &&!HomeSetupPolicy.shouldRequestKeyFilter(false,false,HomeSetupPolicy.GuardMode.KEY_FILTER)
                &&!HomeSetupPolicy.shouldRequestKeyFilter(true,true,HomeSetupPolicy.GuardMode.KEY_FILTER)
                &&!HomeSetupPolicy.shouldRequestKeyFilter(false,true,HomeSetupPolicy.GuardMode.KEY_FILTER)
                &&!HomeSetupPolicy.shouldRequestKeyFilter(true,false,HomeSetupPolicy.GuardMode.WINDOW_PULLBACK)
                &&!HomeSetupPolicy.shouldRequestKeyFilter(true,false,HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY));
        check("apply_key_filter_flag_clears_or_sets_only_filter_bit",
                HomeSetupPolicy.applyKeyFilterFlag(0x25,0x20,HomeSetupPolicy.GuardMode.WINDOW_PULLBACK)==0x05
                &&HomeSetupPolicy.applyKeyFilterFlag(0x25,0x20,HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY)==0x05
                &&HomeSetupPolicy.applyKeyFilterFlag(0x05,0x20,HomeSetupPolicy.GuardMode.KEY_FILTER)==0x25
                &&HomeSetupPolicy.applyKeyFilterFlag(0x25,0x20,false,false,HomeSetupPolicy.GuardMode.KEY_FILTER)==0x05
                &&HomeSetupPolicy.applyKeyFilterFlag(0x25,0x20,true,true,HomeSetupPolicy.GuardMode.KEY_FILTER)==0x05
                &&HomeSetupPolicy.applyKeyFilterFlag(0x25,0x20,null)==0x05);
        check("wake_fallback_matches_guard_mode_policy",
                HomeSetupPolicy.wakeFallback(true,false,false,true,
                        !HomeSetupPolicy.GuardMode.WINDOW_PULLBACK.handlesWakeFallback(),true,true,false,true)
                &&HomeSetupPolicy.wakeFallback(true,false,false,true,
                        !HomeSetupPolicy.GuardMode.KEY_FILTER.handlesWakeFallback(),true,true,false,true)
                &&!HomeSetupPolicy.wakeFallback(true,false,false,true,
                        !HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY.handlesWakeFallback(),true,true,false,true));
        // Native HOME priority stops key filtering & extra Home interception
        check("native_home_stops_key_filter_and_compat_interception",
                !HomeSetupPolicy.shouldRequestKeyFilter(true,false,true,HomeSetupPolicy.GuardMode.KEY_FILTER)
                &&HomeSetupPolicy.shouldRequestKeyFilter(true,false,false,HomeSetupPolicy.GuardMode.KEY_FILTER)
                &&!HomeSetupPolicy.shouldInterceptHomeCompat(true,false,true,HomeSetupPolicy.GuardMode.WINDOW_PULLBACK)
                &&!HomeSetupPolicy.shouldInterceptHomeCompat(true,false,true,HomeSetupPolicy.GuardMode.KEY_FILTER)
                &&HomeSetupPolicy.shouldInterceptHomeCompat(true,false,false,HomeSetupPolicy.GuardMode.WINDOW_PULLBACK)
                &&!HomeSetupPolicy.shouldInterceptHomeCompat(true,false,false,HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY)
                &&HomeSetupPolicy.applyKeyFilterFlag(0x25,0x20,true,false,true,HomeSetupPolicy.GuardMode.KEY_FILTER)==0x05);
        // 3B no-bridge volume yield only applies to opt-in KEY_FILTER without bridge assist
        check("volume_yield_only_for_key_filter_without_bridge_assist",
                HomeSetupPolicy.shouldUseVolumeYield(true,false,false,HomeSetupPolicy.GuardMode.KEY_FILTER,false)
                &&!HomeSetupPolicy.shouldUseVolumeYield(true,false,false,HomeSetupPolicy.GuardMode.KEY_FILTER,true)
                &&!HomeSetupPolicy.shouldUseVolumeYield(true,false,true,HomeSetupPolicy.GuardMode.KEY_FILTER,false)
                &&!HomeSetupPolicy.shouldUseVolumeYield(true,false,false,HomeSetupPolicy.GuardMode.WINDOW_PULLBACK,false)
                &&!HomeSetupPolicy.shouldUseVolumeYield(true,false,false,HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY,false)
                &&!HomeSetupPolicy.shouldUseVolumeYield(false,false,false,HomeSetupPolicy.GuardMode.KEY_FILTER,false)
                &&!HomeSetupPolicy.shouldUseVolumeYield(true,true,false,HomeSetupPolicy.GuardMode.KEY_FILTER,false));
        // Unified DesktopState projection: user choice, effective mode, and degradation reason
        HomeSetupPolicy.DesktopState nativeState=HomeSetupPolicy.resolveDesktopState(
                HomeSetupPolicy.DesktopChoice.NATIVE_HOME,false,false,true,
                "com.pilipala.tvhome/com.pilipala.tvhome.HomeActivity",true,true,true,
                HomeSetupPolicy.GuardMode.KEY_FILTER,true,true,false);
        check("desktop_state_native_priority_stops_extra_interception",
                "系统默认桌面".equals(nativeState.userChoiceLabel)
                &&nativeState.effectiveModeLabel.contains("已停止额外 Home 拦截")
                &&nativeState.extraHomeInterceptionStopped
                &&!nativeState.keyFilterExpected);
        HomeSetupPolicy.DesktopState fallbackWindow=HomeSetupPolicy.resolveDesktopState(
                HomeSetupPolicy.DesktopChoice.NATIVE_HOME,false,false,false,
                "com.mitv.tvhome/.MainActivityUserMode",true,true,true,
                HomeSetupPolicy.GuardMode.WINDOW_PULLBACK,false,false,false);
        check("desktop_state_native_unavailable_reports_window_degradation",
                "系统默认桌面".equals(fallbackWindow.userChoiceLabel)
                &&fallbackWindow.effectiveModeLabel.contains("窗口回拉")
                &&fallbackWindow.fallbackReason.contains("com.mitv.tvhome"));
        HomeSetupPolicy.DesktopState fastBridge=HomeSetupPolicy.resolveDesktopState(
                HomeSetupPolicy.DesktopChoice.KEY_FILTER,false,false,false,
                "com.mitv.tvhome/.MainActivityUserMode",true,true,true,
                HomeSetupPolicy.GuardMode.KEY_FILTER,true,true,false);
        check("desktop_state_fast_home_with_bridge_reports_3a",
                "快速 Home".equals(fastBridge.userChoiceLabel)
                &&fastBridge.effectiveModeLabel.contains("快速 Home ＋ 任务桥")
                &&fastBridge.volumeBridgeAssistExpected&&!fastBridge.volumeYieldMode);
        HomeSetupPolicy.DesktopState fastNoBridge=HomeSetupPolicy.resolveDesktopState(
                HomeSetupPolicy.DesktopChoice.KEY_FILTER,false,false,false,
                "com.mitv.tvhome/.MainActivityUserMode",true,true,true,
                HomeSetupPolicy.GuardMode.KEY_FILTER,false,false,false);
        check("desktop_state_fast_home_without_bridge_reports_3b_instruction",
                fastNoBridge.effectiveModeLabel.contains("先点一下音量，松手后再长按")
                &&fastNoBridge.fallbackReason.contains("先点一下音量，松手后再长按")
                &&fastNoBridge.volumeYieldMode&&!fastNoBridge.volumeBridgeAssistExpected);
        // Codex rework policy regressions:
        // 1. Native HOME is Marco + guard paused -> STILL displays system HOME = Marco TV & guard paused!
        HomeSetupPolicy.DesktopState nativePaused=HomeSetupPolicy.resolveDesktopState(
                HomeSetupPolicy.DesktopChoice.NATIVE_HOME,false,true,true,
                "com.pilipala.tvhome/com.pilipala.tvhome.HomeActivity",false,false,false,
                HomeSetupPolicy.GuardMode.WINDOW_PULLBACK,false,false,false);
        check("desktop_state_native_home_while_paused_shows_marco_and_paused",
                nativePaused.effectiveModeLabel.contains("系统默认桌面")
                &&nativePaused.effectiveModeLabel.contains("Marco TV")
                &&nativePaused.effectiveModeLabel.contains("已暂停")
                &&!nativePaused.effectiveModeLabel.contains("原厂桌面")
                &&nativePaused.extraHomeInterceptionStopped);
        // 2. System refused enabling KEY_FILTER -> never claims 快速 Home or 3A/3B active!
        HomeSetupPolicy.DesktopState filterRejected=HomeSetupPolicy.resolveDesktopState(
                HomeSetupPolicy.DesktopChoice.KEY_FILTER,false,false,false,
                "com.mitv.tvhome/.MainActivityUserMode",true,true,true,
                HomeSetupPolicy.GuardMode.KEY_FILTER,true,true,false,
                false,false,false);
        check("desktop_state_key_filter_rejected_reports_window_fallback",
                filterRejected.effectiveModeLabel.contains("系统拒绝开启按键过滤")
                &&!filterRejected.keyFilterExpected
                &&!filterRejected.volumeBridgeAssistExpected
                &&!filterRejected.volumeYieldMode);
        // 3. System refused revoking KEY_FILTER during 3B yield -> never claims yielding 5s!
        HomeSetupPolicy.DesktopState yieldRejected=HomeSetupPolicy.resolveDesktopState(
                HomeSetupPolicy.DesktopChoice.KEY_FILTER,false,false,false,
                "com.mitv.tvhome/.MainActivityUserMode",true,true,true,
                HomeSetupPolicy.GuardMode.KEY_FILTER,false,false,false,
                true,true,true);
        check("desktop_state_yield_rejected_never_claims_yielding",
                yieldRejected.effectiveModeLabel.contains("系统拒绝临时撤销按键过滤")
                &&!yieldRejected.effectiveModeLabel.contains("让渡 5 秒中")
                &&!yieldRejected.volumeYieldMode);
        check("oem_exp_production_allowlist_stays_empty",
                OemLauncherExperimentPolicy.VERIFIED_ALLOWLIST.isEmpty());
        // Experimental OEM launcher disable policy gating
        check("oem_exp_blocks_restricted_edition",
                OemLauncherExperimentPolicy.evaluate(true,"VerifiedTV","1.0","com.vendor.home",
                        java.util.Collections.singletonList(new OemLauncherExperimentPolicy.VerifiedDevice("VerifiedTV","1.0","com.vendor.home","ok")))
                        .eligibility==OemLauncherExperimentPolicy.Eligibility.BLOCKED_RESTRICTED);
        check("oem_exp_blocks_mitv_astp0_1385_and_mitv_mftr0",
                OemLauncherExperimentPolicy.evaluate(false,"MiTV-ASTP0","22.8.30.1385","com.mitv.tvhome").eligibility
                        ==OemLauncherExperimentPolicy.Eligibility.BLOCKED_KNOWN_UNSAFE
                &&OemLauncherExperimentPolicy.evaluate(false,"MiTV-MFTR0","any.firmware","com.mitv.tvhome").eligibility
                        ==OemLauncherExperimentPolicy.Eligibility.BLOCKED_KNOWN_UNSAFE);
        check("oem_exp_blocks_unverified_and_allows_explicit_verified_entry",
                OemLauncherExperimentPolicy.evaluate(false,"UnknownTV","1.0","com.vendor.home").eligibility
                        ==OemLauncherExperimentPolicy.Eligibility.BLOCKED_UNVERIFIED
                &&OemLauncherExperimentPolicy.evaluate(false,"VerifiedTV","1.0","com.vendor.home",
                        java.util.Collections.singletonList(new OemLauncherExperimentPolicy.VerifiedDevice("VerifiedTV","1.0","com.vendor.home","ok")))
                        .allowed());
        check("oem_exp_rejects_fallback_home_and_accepts_marco",
                !OemLauncherExperimentPolicy.isValidPostDisableHome("com.android.tv.settings/.system.FallbackHome","com.pilipala.tvhome")
                &&!OemLauncherExperimentPolicy.isValidPostDisableHome("android/com.android.internal.app.ResolverActivity","com.pilipala.tvhome")
                &&!OemLauncherExperimentPolicy.isValidPostDisableHome("","com.pilipala.tvhome")
                &&OemLauncherExperimentPolicy.isValidPostDisableHome("com.pilipala.tvhome/com.pilipala.tvhome.HomeActivity","com.pilipala.tvhome"));
        System.out.println("OK "+passed+" home setup policy checks");
    }
}
