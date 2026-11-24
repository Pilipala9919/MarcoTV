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
        return compatEnabled && !paused && mode != null && mode.requestsKeyFilter();
    }

    /** Applies only the key-event filtering flag bit according to guard state and chosen mode. */
    static int applyKeyFilterFlag(int currentFlags, int filterKeyEventsBit,
                                  boolean compatEnabled, boolean paused, GuardMode mode) {
        if (shouldRequestKeyFilter(compatEnabled, paused, mode)) return currentFlags | filterKeyEventsBit;
        return currentFlags & ~filterKeyEventsBit;
    }

    /** Applies only the key-event filtering flag bit according to the chosen guard mode. */
    static int applyKeyFilterFlag(int currentFlags, int filterKeyEventsBit, GuardMode mode) {
        return applyKeyFilterFlag(currentFlags, filterKeyEventsBit, true, false, mode);
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
