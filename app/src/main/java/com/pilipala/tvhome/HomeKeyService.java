// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.util.Log;
import android.view.KeyEvent;

/** Optional fallback, limited to the saved original HOME package's window events. */
public final class HomeKeyService extends AccessibilityService {
    private long lastOpen;
    private boolean handlingHome;
    private boolean receiverRegistered;
    private final BroadcastReceiver homeBroadcast = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (!SystemIntegration.prefs(c).getBoolean("home_compat", false)) return;
            if (!"homekey".equals(i.getStringExtra("reason"))) return;
            handler.removeCallbacks(fallback);
            try { startActivity(SystemIntegration.homeIntent(c)); Log.i("MarcoTVSystem", "HOME broadcast handled"); }
            catch (RuntimeException e) { Log.w("MarcoTVSystem", "Home broadcast unavailable", e); }
        }
    };
    @Override protected void onServiceConnected() {
        AccessibilityServiceInfo info = getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        setServiceInfo(info);
        if (!receiverRegistered) { registerReceiver(homeBroadcast, new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)); receiverRegistered = true; }
        Log.i("MarcoTVSystem", "HOME key filter ready; capabilities=" + info.getCapabilities() + "; flags=" + info.flags);
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable fallback = () -> {
        if (!SystemIntegration.prefs(this).getBoolean("home_compat", false)) return;
        try { startActivity(SystemIntegration.homeIntent(this)); Log.i("MarcoTVSystem", "HOME window fallback"); }
        catch (RuntimeException e) { Log.w("MarcoTVSystem", "Home redirection failed", e); }
    };
    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) handlingHome = SystemIntegration.prefs(this).getBoolean("home_compat", false);
            if (handlingHome && event.getRepeatCount() == 0) {
                handler.removeCallbacks(fallback);
                lastOpen = SystemClock.elapsedRealtime();
                try { startActivity(SystemIntegration.homeIntent(this)); Log.i("MarcoTVSystem", "HOME intercepted before launcher"); }
                catch (RuntimeException e) { handlingHome = false; Log.w("MarcoTVSystem", "Direct HOME failed", e); }
            }
            return handlingHome;
        }
        boolean consumed = handlingHome || SystemIntegration.prefs(this).getBoolean("home_compat", false);
        handlingHome = false; return consumed;
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.getPackageName() == null) return;
        String shown = event.getPackageName().toString();
        if (AmbientIntegration.enabled(this) && shown.equals("com.mitv.screensaver")
                && event.getClassName() != null && event.getClassName().toString().equals("com.mitv.screensaver.RenderActivity")) {
            // Xiaomi launches a proprietary activity instead of Android's selected dream.
            // Only its actual full-screen saver is replaced, never its settings page.
            try { startActivity(SystemIntegration.homeIntent(this).putExtra("ambient", true)); Log.i("MarcoTVSystem", "Vendor screensaver replaced"); }
            catch (RuntimeException e) { Log.w("MarcoTVSystem", "Vendor ambient unavailable", e); }
            return;
        }
        if (!SystemIntegration.prefs(this).getBoolean("home_compat", false)) return;
        ComponentName original = ComponentName.unflattenFromString(SystemIntegration.prefs(this).getString("original_home", ""));
        if (original == null || event.getPackageName() == null || getPackageName().equals(original.getPackageName())) return;
        // This Xiaomi firmware dispatches HOME through tvhome into its separate lite launcher.
        boolean originalWindow = original.getPackageName().equals(shown)
                || (original.getPackageName().equals("com.mitv.tvhome") && shown.equals("com.xiaomi.eetv.litehome"));
        if (!originalWindow) return;
        // OEM HOME can open a second launcher activity immediately afterwards.
        // Coalesce these events instead of discarding the second one for 1.2 s.
        handler.removeCallbacks(fallback); handler.postDelayed(fallback, 60);
    }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) unregisterReceiver(homeBroadcast);
        super.onDestroy();
    }
    @Override public void onInterrupt() { }
}
