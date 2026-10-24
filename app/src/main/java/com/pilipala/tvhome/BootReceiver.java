// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        SystemIntegration.prefs(context).edit().putString("last_boot_action", action)
                .putLong("last_boot_time", System.currentTimeMillis()).apply();
        SystemIntegration.startRecovery(context);
        // Home preferences are credential-protected; ADB recovery can run before unlock.
        if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) AmbientIntegration.configure(context);
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) SystemIntegration.refreshHomeService(context);
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                && SystemIntegration.prefs(context).getBoolean("boot_home", false)) {
            try { context.startActivity(SystemIntegration.homeIntent(context)); }
            catch (RuntimeException e) { Log.w("MarcoTVSystem", "Boot activity unavailable", e); }
        }
    }
}
