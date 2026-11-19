// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;

/** Access token for active media sessions. Ordinary notifications are never read or stored. */
public final class PlaybackListenerService extends NotificationListenerService {
    static final String CHANGED="com.pilipala.tvhome.PLAYBACK_ACCESS_CHANGED";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable recover = this::recoverAfterStop;
    @Override public void onListenerConnected() {
        sendBroadcast(new Intent(CHANGED).setPackage(getPackageName()));
        // Wait for standby screen-off and AccessibilityManagerService.onHandleForceStop()
        // to settle before inspecting accessibility state or starting RecoveryService.
        handler.removeCallbacks(recover);
        handler.postDelayed(recover, 1500);
    }
    @Override public void onListenerDisconnected() {
        handler.removeCallbacks(recover);
        sendBroadcast(new Intent(CHANGED).setPackage(getPackageName()));
    }
    @Override public void onDestroy() {
        handler.removeCallbacks(recover);
        super.onDestroy();
    }
    private void recoverAfterStop() {
        if (!HomeSetup.unlocked(this)) return;
        if (SystemIntegration.restoreCompatAfterStandbyKill(this)) return;
        android.content.SharedPreferences p = SystemIntegration.prefs(this);
        boolean compatReadyOrOff = !p.getBoolean("home_compat", false) || HomeSetup.serviceEnabled(this);
        if (SystemIntegration.needsRecovery(this) && !RecoveryService.running
                && SystemIntegration.authorized(this) && !HomeSetup.compatResetPending(this)
                && compatReadyOrOff) {
            SystemIntegration.startRecovery(this);
        }
    }
}
