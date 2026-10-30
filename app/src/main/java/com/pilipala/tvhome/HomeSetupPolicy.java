// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

/** Platform-free decisions shared by setup, recovery and regression checks. */
final class HomeSetupPolicy {
    enum Route { NATIVE_READY, SYSTEM_PICKER, COMPAT_READY, ACCESSIBILITY, ENHANCED, PAUSED }
    static Route route(boolean paused, boolean nativeHome, boolean picker, boolean compat,
                       boolean serviceEnabled, boolean accessibilityPage) {
        if (paused) return Route.PAUSED;
        if (nativeHome) return Route.NATIVE_READY;
        if (compat && serviceEnabled) return Route.COMPAT_READY;
        if (picker) return Route.SYSTEM_PICKER;
        return accessibilityPage ? Route.ACCESSIBILITY : Route.ENHANCED;
    }
    static boolean bootFallback(boolean requested, boolean paused, boolean nativeHome,
                                boolean compat, boolean serviceEnabled, boolean unlocked) {
        return unlocked && requested && !paused && !nativeHome && compat && serviceEnabled;
    }
    static boolean homeEvidence(boolean armed, int armedBoot, int currentBoot,
                                long deadline, long elapsed, boolean homeIntent) {
        return armed && armedBoot >= 0 && armedBoot == currentBoot && elapsed <= deadline && homeIntent;
    }
    static boolean bootEvidence(int armedBoot, int currentBoot, long elapsed, boolean homeIntent) {
        return armedBoot >= 0 && currentBoot > armedBoot && elapsed < 180000 && homeIntent;
    }
    static boolean pauseForExternalChange(boolean enabled, boolean hasEvidence,
                                         boolean ownWrite, boolean servicePresent) {
        return enabled && hasEvidence && !ownWrite && !servicePresent;
    }
}
