// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.view.WindowManager;

/** Starts the idle clock after inactivity and hands sleep back to the TV after a while on it. */
final class IdleController {
    static final int DEFAULT_SLEEP_MINUTES = 30;
    private final MainActivity host;
    private final Runnable idle;
    /** After this long on the idle clock, let the TV follow its own sleep timer again. */
    private final Runnable releaseScreen;

    IdleController(MainActivity host) {
        this.host = host;
        idle = () -> {
            if (host.foreground && !host.dialogs.open() && !host.home.isMoving() && !host.recents.visible && (host.phone==null||!host.phone.active())) host.home.setIdle(true);
        };
        releaseScreen = () -> {
            if (!host.foreground) return;
            host.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            Diagnostics.event("IDLE_SCREEN_RELEASED");
        };
    }

    /** Restarts the inactivity countdown when the desktop is the only thing on screen. */
    void arm() {
        host.handler.removeCallbacks(idle);
        int seconds = host.preferences.getInt("idle_seconds", 30);
        if (host.foreground && !host.dialogs.open() && (host.phone==null||!host.phone.active()) && (host.recents==null||!host.recents.visible) && seconds > 0
                && AmbientIntegration.enabled(host)) host.handler.postDelayed(idle, seconds * 1000L);
    }

    void cancel() {
        host.handler.removeCallbacks(idle);
    }

    /** Called by HomeView whenever the idle clock starts or stops. */
    void changed(boolean idle) {
        host.handler.removeCallbacks(releaseScreen);
        if (!host.foreground || !AmbientIntegration.enabled(host)) return;
        host.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        int minutes = host.preferences.getInt("ambient_sleep_minutes", DEFAULT_SLEEP_MINUTES);
        if (idle && minutes > 0) host.handler.postDelayed(releaseScreen, minutes * 60000L);
    }

    void resume() {
        if (AmbientIntegration.enabled(host)) host.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else host.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    void pause() {
        host.handler.removeCallbacks(idle);
        host.handler.removeCallbacks(releaseScreen);
        host.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
