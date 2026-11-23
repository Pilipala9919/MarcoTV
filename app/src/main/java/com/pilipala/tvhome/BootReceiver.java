// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        SystemIntegration.prefs(context).edit().putString("last_boot_action", action)
                .putLong("last_boot_time", System.currentTimeMillis()).apply();
        HomeSetup.onBootBroadcast(context, action);
        Diagnostics.event("BOOT_RECOVERY_REQUESTED");
        boolean nativeReset = false;
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            // Firmware boot resets must be handled before reconcile(), which would
            // otherwise read them as an external change and switch boot opening off.
            SystemIntegration.clearStandbyWakeReturn(context);
            SystemIntegration.restoreCompatAfterBoot(context);
            nativeReset = HomeSetup.markNativeResetAtBoot(context);
            HomeSetup.reconcile(context);
        }
        SystemIntegration.startRecovery(context);
        // Home preferences are credential-protected; ADB recovery can run before unlock.
        if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) AmbientIntegration.configure(context);
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) SystemIntegration.refreshHomeService(context);
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                && (nativeReset || HomeSetup.shouldOpenAfterBoot(context, true))) {
            String token = HomeSetup.beginBootRecoveryRequest(context);
            try {
                context.startActivity(SystemIntegration.homeIntent(context)
                        .putExtra(HomeSetup.EXTRA_EVENT_TOKEN, token));
            }
            catch (RuntimeException e) {
                HomeSetup.markBootRecoveryLaunchDenied(context);
                Log.w("MarcoTVSystem", "Boot activity unavailable", e);
            }
        }
    }
}
