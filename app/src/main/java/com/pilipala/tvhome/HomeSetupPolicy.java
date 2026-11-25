// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

/** Platform-free decisions shared by setup, recovery and regression checks. */
final class HomeSetupPolicy {
    enum Route { NATIVE_READY, SYSTEM_PICKER, COMPAT_READY, ACCESSIBILITY, ENHANCED, PAUSED }
    enum StartAction { VERIFY_NATIVE, VERIFY_COMPAT, REQUEST_PICKER, COMPATIBILITY, LOCAL_AUTHORIZATION }
    enum HomeReturnSource { NONE, SYSTEM_HOME, COMPAT_KEY, COMPAT_BROADCAST, WINDOW_FALLBACK }
    enum GuardMode {
        WINDOW_PULLBACK("window"),
        KEY_FILTER("key_filter"),
        NATIVE_KEYS_ONLY("native_only");

        final String prefValue;
        GuardMode(String prefValue) { this.prefValue = prefValue; }
        boolean requestsKeyFilter() { return this == KEY_FILTER; }
        boolean handlesDirectHomeKey() { return this == KEY_FILTER; }
        boolean handlesHomeBroadcast() { return this != NATIVE_KEYS_ONLY; }
        boolean handlesWindowFallback() { return this != NATIVE_KEYS_ONLY; }
        boolean handlesWakeFallback() { return this != NATIVE_KEYS_ONLY; }
    }

    enum DesktopChoice {
        NATIVE_HOME("native"),
        WINDOW_PULLBACK("window"),
        KEY_FILTER("key_filter"),
        NATIVE_KEYS_ONLY("native_only"),
        OFF("off");

        final String prefValue;
        DesktopChoice(String prefValue) { this.prefValue = prefValue; }
    }

    /** Projection of user selection, active runtime behavior, and degradation reason for UI & diagnostics. */
    static final class DesktopState {
        final DesktopChoice userChoice;
        final String userChoiceLabel;
        final String effectiveModeLabel;
        final String fallbackReason;
        final boolean extraHomeInterceptionStopped;
        final boolean keyFilterExpected;
        final boolean volumeBridgeAssistExpected;
        final boolean volumeYieldMode;

        DesktopState(DesktopChoice userChoice, String userChoiceLabel, String effectiveModeLabel,
                     String fallbackReason, boolean extraHomeInterceptionStopped,
                     boolean keyFilterExpected, boolean volumeBridgeAssistExpected,
                     boolean volumeYieldMode) {
            this.userChoice = userChoice;
            this.userChoiceLabel = userChoiceLabel;
            this.effectiveModeLabel = effectiveModeLabel;
            this.fallbackReason = fallbackReason;
            this.extraHomeInterceptionStopped = extraHomeInterceptionStopped;
            this.keyFilterExpected = keyFilterExpected;
            this.volumeBridgeAssistExpected = volumeBridgeAssistExpected;
            this.volumeYieldMode = volumeYieldMode;
        }
    }

    static DesktopChoice resolveDesktopChoice(String savedChoice, boolean paused,
                                              boolean nativeHome, boolean compatEnabled,
                                              GuardMode guardMode) {
        if (DesktopChoice.OFF.prefValue.equals(savedChoice)) return DesktopChoice.OFF;
        if (DesktopChoice.NATIVE_KEYS_ONLY.prefValue.equals(savedChoice)) return DesktopChoice.NATIVE_KEYS_ONLY;
        if (DesktopChoice.KEY_FILTER.prefValue.equals(savedChoice)) return DesktopChoice.KEY_FILTER;
        if (DesktopChoice.WINDOW_PULLBACK.prefValue.equals(savedChoice)) return DesktopChoice.WINDOW_PULLBACK;
        if (DesktopChoice.NATIVE_HOME.prefValue.equals(savedChoice)) return DesktopChoice.NATIVE_HOME;
        if (paused) return DesktopChoice.OFF;
        if (nativeHome) return DesktopChoice.NATIVE_HOME;
        if (guardMode == GuardMode.NATIVE_KEYS_ONLY) return DesktopChoice.NATIVE_KEYS_ONLY;
        if (compatEnabled) {
            return guardMode == GuardMode.KEY_FILTER
                    ? DesktopChoice.KEY_FILTER : DesktopChoice.WINDOW_PULLBACK;
        }
        return DesktopChoice.OFF;
    }

    static String desktopChoiceLabel(DesktopChoice choice) {
        if (choice == null) return "未开启守护";
        switch (choice) {
            case NATIVE_HOME: return "系统默认桌面";
            case WINDOW_PULLBACK: return "窗口回拉（兼容模式）";
            case KEY_FILTER: return "快速 Home";
            case NATIVE_KEYS_ONLY: return "原厂按键";
            default: return "已关闭守护";
        }
    }

    static DesktopState resolveDesktopState(DesktopChoice userChoice, boolean restricted,
                                            boolean paused, boolean nativeHome,
                                            String currentHomeComponent, boolean compatEnabled,
                                            boolean serviceEnabled, boolean serviceConnected,
                                            GuardMode guardMode, boolean bridgeConnected,
                                            boolean bridgeVolumeAssistActive,
                                            boolean volumeYieldActive) {
        DesktopChoice choice = userChoice == null ? DesktopChoice.OFF : userChoice;
        GuardMode mode = guardMode == null ? GuardMode.WINDOW_PULLBACK : guardMode;
        String choiceLabel = desktopChoiceLabel(choice);
        String resolvedTarget = (currentHomeComponent == null || currentHomeComponent.isEmpty())
                ? "原厂桌面" : currentHomeComponent;

        if (!restricted && nativeHome && !paused) {
            String reason = choice == DesktopChoice.NATIVE_HOME
                    ? "无（原生默认桌面已生效）"
                    : "系统当前已将 HOME 解析为 Marco TV，已按原生优先停止额外 Home 拦截";
            return new DesktopState(choice, choiceLabel,
                    "系统默认桌面（原生 HOME 已生效，已停止额外 Home 拦截）",
                    reason, true, false, false, false);
        }
        if (paused || choice == DesktopChoice.OFF) {
            String reason = paused
                    ? "自动恢复已暂停，已保留用户选择"
                    : (restricted
                            ? "受限兼容版不含系统默认桌面声明；默认推荐使用窗口回拉（兼容模式）"
                            : "尚未启用桌面守护；若系统不支持原生默认桌面，默认推荐使用窗口回拉（兼容模式）");
            return new DesktopState(choice, choiceLabel,
                    "未接管（交由原厂桌面与按键处理）",
                    reason, true, false, false, false);
        }
        if (mode == GuardMode.NATIVE_KEYS_ONLY || choice == DesktopChoice.NATIVE_KEYS_ONLY) {
            return new DesktopState(DesktopChoice.NATIVE_KEYS_ONLY,
                    desktopChoiceLabel(DesktopChoice.NATIVE_KEYS_ONLY),
                    "原厂按键（不自动回拉，音量和方向键交由系统处理）",
                    "无（已按用户选择保留原厂按键）", true, false, false, false);
        }
        if (!compatEnabled || !serviceEnabled || !serviceConnected) {
            String eff = !compatEnabled ? "未生效（Home 兼容未开启）"
                    : (!serviceEnabled ? "未生效（辅助服务未启用）" : "已写回、等待服务连接");
            String prefix = restricted
                    ? "受限兼容版不含系统默认桌面声明；"
                    : (choice == DesktopChoice.NATIVE_HOME
                            ? "原生 HOME 未生效（系统当前解析：" + resolvedTarget + "）；"
                            : "");
            String detail = !compatEnabled ? "请先开启 Home 兼容或选择窗口回拉（兼容模式）"
                    : (!serviceEnabled ? "请在系统辅助功能中允许 Marco TV 服务"
                            : "服务尚未收到系统连接回调，生效前不会标记就绪");
            return new DesktopState(choice, choiceLabel, eff, prefix + detail,
                    false, false, false, false);
        }
        if (mode == GuardMode.WINDOW_PULLBACK) {
            String reason;
            if (choice == DesktopChoice.NATIVE_HOME) {
                reason = restricted
                        ? "受限兼容版不含系统默认桌面声明，已降级为窗口回拉（兼容模式）"
                        : "原生 HOME 不可用（系统当前解析：" + resolvedTarget + "），已降级为窗口回拉（兼容模式）";
            } else {
                reason = "无（按窗口回拉兼容模式生效）";
            }
            return new DesktopState(choice, choiceLabel,
                    "窗口回拉（兼容模式，音量和方向键交由系统处理）",
                    reason, false, false, false, false);
        }
        // GuardMode.KEY_FILTER with compatEnabled && serviceEnabled && serviceConnected
        if (bridgeVolumeAssistActive) {
            String reason = choice == DesktopChoice.NATIVE_HOME
                    ? "原生 HOME 不可用（系统当前解析：" + resolvedTarget + "），当前以快速 Home＋任务桥生效"
                    : "无（快速 Home 与任务桥音量长按辅助均已生效）";
            return new DesktopState(choice, choiceLabel,
                    "快速 Home ＋ 任务桥（直接拦截 Home，任务桥辅助音量长按）",
                    reason, false, true, true, false);
        }
        String eff = volumeYieldActive
                ? "快速 Home（无桥模式 · 音量临时让渡 5 秒中）"
                : "快速 Home（无桥模式：先点一下音量，松手后再长按）";
        String reason = bridgeConnected
                ? "任务桥已连接但输入/音频辅助未就绪，已降级为无桥临时让渡模式（先点一下音量，松手后再长按；也可改用窗口回拉）"
                : "任务桥未连接，当前运行在无桥临时让渡模式（先点一下音量，松手后再长按；若固件在音量上限/下限不触发让渡，可改用窗口回拉）";
        return new DesktopState(choice, choiceLabel, eff, reason,
                false, true, false, true);
    }

    /**
     * Resolves the effective Home guard mode from persisted device-protected preferences.
     *
     * <p>Fresh installs and upgrades from versions without {@code home_guard_mode} default to
     * {@link GuardMode#WINDOW_PULLBACK} (no key-event filtering) so Bluetooth remote volume and
     * D-pad repeats stay native, while preserving an explicit legacy {@code home_native_keys=true}.
     */
    static GuardMode resolveGuardMode(String savedMode, boolean legacyNativeKeys) {
        if (GuardMode.KEY_FILTER.prefValue.equals(savedMode)) return GuardMode.KEY_FILTER;
        if (GuardMode.WINDOW_PULLBACK.prefValue.equals(savedMode)) return GuardMode.WINDOW_PULLBACK;
        if (GuardMode.NATIVE_KEYS_ONLY.prefValue.equals(savedMode)) return GuardMode.NATIVE_KEYS_ONLY;
        return legacyNativeKeys ? GuardMode.NATIVE_KEYS_ONLY : GuardMode.WINDOW_PULLBACK;
    }

    /** Whether HomeKeyService should actively request key-event filtering right now. */
    static boolean shouldRequestKeyFilter(boolean compatEnabled, boolean paused, GuardMode mode) {
        return shouldRequestKeyFilter(compatEnabled, paused, false, mode);
    }

    /** Native HOME takes priority: once system HOME resolves to Marco, stop key filtering and extra Home interception. */
    static boolean shouldRequestKeyFilter(boolean compatEnabled, boolean paused,
                                          boolean nativeHome, GuardMode mode) {
        return !nativeHome && compatEnabled && !paused && mode != null && mode.requestsKeyFilter();
    }

    /** Whether compatibility Home interception (broadcast / window pullback / direct key) should stay active. */
    static boolean shouldInterceptHomeCompat(boolean compatEnabled, boolean paused,
                                             boolean nativeHome, GuardMode mode) {
        return !nativeHome && compatEnabled && !paused && mode != null && mode != GuardMode.NATIVE_KEYS_ONLY;
    }

    /** Whether 3B no-bridge transient volume yield applies right now (only user-opted KEY_FILTER without 3A bridge assist). */
    static boolean shouldUseVolumeYield(boolean compatEnabled, boolean paused,
                                        boolean nativeHome, GuardMode mode,
                                        boolean bridgeVolumeAssistActive) {
        return shouldRequestKeyFilter(compatEnabled, paused, nativeHome, mode)
                && !bridgeVolumeAssistActive;
    }

    /** Applies only the key-event filtering flag bit according to guard state, native HOME state, and chosen mode. */
    static int applyKeyFilterFlag(int currentFlags, int filterKeyEventsBit,
                                  boolean compatEnabled, boolean paused,
                                  boolean nativeHome, GuardMode mode) {
        if (shouldRequestKeyFilter(compatEnabled, paused, nativeHome, mode)) return currentFlags | filterKeyEventsBit;
        return currentFlags & ~filterKeyEventsBit;
    }

    /** Applies only the key-event filtering flag bit according to guard state and chosen mode. */
    static int applyKeyFilterFlag(int currentFlags, int filterKeyEventsBit,
                                  boolean compatEnabled, boolean paused, GuardMode mode) {
        return applyKeyFilterFlag(currentFlags, filterKeyEventsBit, compatEnabled, paused, false, mode);
    }

    /** Applies only the key-event filtering flag bit according to the chosen guard mode. */
    static int applyKeyFilterFlag(int currentFlags, int filterKeyEventsBit, GuardMode mode) {
        return applyKeyFilterFlag(currentFlags, filterKeyEventsBit, true, false, false, mode);
    }

    static Route route(boolean paused, boolean nativeHome, boolean picker, boolean compat,
                       boolean serviceEnabled, boolean accessibilityPage) {
        if (paused) return Route.PAUSED;
        if (nativeHome) return Route.NATIVE_READY;
        if (compat && serviceEnabled) return Route.COMPAT_READY;
        if (picker) return Route.SYSTEM_PICKER;
        return accessibilityPage ? Route.ACCESSIBILITY : Route.ENHANCED;
    }
    static Route effectiveStartRoute(Route current, boolean nativeHome, boolean picker,
                                     boolean compat, boolean serviceEnabled, boolean accessibilityPage) {
        return current == Route.PAUSED
                ? route(false, nativeHome, picker, compat, serviceEnabled, accessibilityPage)
                : current;
    }
    static StartAction startAction(Route effective) {
        if (effective == null) return StartAction.LOCAL_AUTHORIZATION;
        switch (effective) {
            case NATIVE_READY: return StartAction.VERIFY_NATIVE;
            case COMPAT_READY: return StartAction.VERIFY_COMPAT;
            case SYSTEM_PICKER: return StartAction.REQUEST_PICKER;
            case ACCESSIBILITY: return StartAction.COMPATIBILITY;
            default: return StartAction.LOCAL_AUTHORIZATION;
        }
    }
    static boolean bootFallback(boolean requested, boolean paused, boolean nativeHome,
                                boolean compat, boolean serviceEnabled, boolean unlocked) {
        return unlocked && requested && !paused && !nativeHome && compat && serviceEnabled;
    }
    static boolean homeEvidence(boolean armed, int armedBoot, int currentBoot,
                                long deadline, long elapsed, boolean homeIntent) {
        return homeEvidence(armed, armedBoot, currentBoot, 0L, deadline, elapsed, true,
                homeIntent ? HomeReturnSource.SYSTEM_HOME : HomeReturnSource.NONE);
    }
    static boolean homeEvidence(boolean armed, int armedBoot, int currentBoot,
                                long armedElapsed, long deadline, long elapsed,
                                boolean sessionValid, HomeReturnSource source) {
        if (!armed || !sessionValid || source == null || source == HomeReturnSource.NONE) return false;
        if (armedElapsed < 0 || deadline < armedElapsed || elapsed < armedElapsed || elapsed > deadline) return false;
        if (armedBoot >= 0 || currentBoot >= 0) return armedBoot >= 0 && armedBoot == currentBoot;
        return true;
    }
    static boolean bootChanged(int armedBoot, int currentBoot, String armedBootId, String currentBootId) {
        if (armedBoot >= 0 && currentBoot >= 0) return currentBoot > armedBoot;
        return armedBootId != null && !armedBootId.isEmpty()
                && currentBootId != null && !currentBootId.isEmpty()
                && !armedBootId.equals(currentBootId);
    }
    static boolean bootRecoveryEvidence(boolean setupRequested, boolean paused, boolean pendingRequestValid,
                                        int armedBoot, int requestBoot, int currentBoot,
                                        long requestElapsed, long deadline, long elapsed) {
        if (!setupRequested || paused || !pendingRequestValid) return false;
        if (requestElapsed < 0 || deadline < requestElapsed || elapsed < requestElapsed || elapsed > deadline) return false;
        if (currentBoot >= 0 && requestBoot >= 0 && currentBoot != requestBoot) return false;
        if (armedBoot >= 0 && currentBoot >= 0 && currentBoot <= armedBoot) return false;
        return true;
    }
    static boolean nativeBootObservation(boolean setupRequested, boolean paused, boolean nativeHome,
                                         int armedBoot, int currentBoot, long elapsed,
                                         HomeReturnSource source, boolean alreadyObservedThisBoot,
                                         boolean bootSessionInterrupted) {
        return nativeBootObservation(setupRequested, paused, nativeHome,
                armedBoot >= 0 && currentBoot > armedBoot, elapsed,
                source, alreadyObservedThisBoot, bootSessionInterrupted);
    }
    static boolean nativeBootObservation(boolean setupRequested, boolean paused, boolean nativeHome,
                                         boolean isNewBoot, long elapsed,
                                         HomeReturnSource source, boolean alreadyObservedThisBoot,
                                         boolean bootSessionInterrupted) {
        if (!setupRequested || paused || !nativeHome || !isNewBoot) return false;
        if (source != HomeReturnSource.SYSTEM_HOME) return false;
        if (alreadyObservedThisBoot || bootSessionInterrupted) return false;
        return elapsed >= 0 && elapsed < 180000L;
    }
    static boolean bootEvidence(int armedBoot, int currentBoot, long elapsed, boolean homeIntent) {
        return armedBoot >= 0 && currentBoot > armedBoot && elapsed >= 0 && elapsed < 180000 && homeIntent;
    }
    static boolean pauseForExternalChange(boolean enabled, boolean hasEvidence,
                                         boolean ownWrite, boolean servicePresent) {
        return enabled && hasEvidence && !ownWrite && !servicePresent;
    }
    static boolean restoreCompatAfterStandby(boolean authorized, boolean unlocked, boolean paused,
                                             boolean compat, boolean accessibilityPending,
                                             boolean seenPresent, boolean serviceWasActive,
                                             boolean compatResetPending, boolean serviceEnabled,
                                             boolean interactive) {
        return authorized && unlocked && !paused && compat && !accessibilityPending
                && seenPresent && serviceWasActive && !compatResetPending
                && !serviceEnabled && !interactive;
    }
    static boolean compatResetAfterStop(boolean unlocked, boolean paused, boolean compat,
                                        boolean accessibilityPending, boolean seenPresent,
                                        boolean serviceWasActive, boolean serviceEnabled,
                                        boolean serviceConnected) {
        return unlocked && !paused && compat && !accessibilityPending
                && seenPresent && serviceWasActive && !serviceEnabled && !serviceConnected;
    }
    static boolean wakeFallback(boolean wakeEnabled, boolean paused, boolean nativeHome,
                                boolean compat, boolean nativeKeys, boolean unlocked,
                                boolean canRedirect, boolean otherAppSeen,
                                boolean originalHomeOrStandbyReturn) {
        return wakeEnabled && !paused && !nativeHome && compat && !nativeKeys
                && unlocked && canRedirect && !otherAppSeen && originalHomeOrStandbyReturn;
    }
}
