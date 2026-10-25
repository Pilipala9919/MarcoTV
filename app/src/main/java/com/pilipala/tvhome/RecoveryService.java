// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.app.*;
import android.content.*;
import android.database.ContentObserver;
import android.os.*;
import android.provider.Settings;
import android.util.Log;

/** Local settings recovery only: no shell, root, unauthenticated listener, or ADB keys. */
public final class RecoveryService extends Service {
    static volatile boolean running;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int retries;
    private final Runnable recover = () -> SystemIntegration.recover(this);
    private final Runnable startupCheck = new Runnable() {
        @Override public void run() {
            if (!SystemIntegration.needsRecovery(RecoveryService.this)) { stopSelf(); return; }
            SystemIntegration.recover(RecoveryService.this);
            if (++retries < 12) handler.postDelayed(this, 10000);
        }
    };
    private final ContentObserver observer = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) {
            handler.removeCallbacks(recover);
            handler.postDelayed(recover, 250);
        }
    };
    @Override public void onCreate() {
        super.onCreate();running=true;Diagnostics.event("RECOVERY_SERVICE_STARTED");
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel(
                "system_recovery", "开机恢复", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, SystemIntegration.homeIntent(this),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "system_recovery") : new Notification.Builder(this);
        startForeground(31, builder.setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle("Marco TV 自动恢复已开启")
                .setContentText("管理已启用的 ADB 或 Home 键兼容功能，可在桌面设置中关闭。")
                .setContentIntent(open).setOngoing(true).build());
        getContentResolver().registerContentObserver(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false, observer);
        getContentResolver().registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer);
        getContentResolver().registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED), false, observer);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!SystemIntegration.needsRecovery(this)) { stopSelf(); return START_NOT_STICKY; }
        handler.removeCallbacks(startupCheck); retries = 0; handler.post(startupCheck);
        Log.i("MarcoTVSystem", "Recovery service started");
        return START_STICKY;
    }
    @Override public void onDestroy() {
        running=false;handler.removeCallbacksAndMessages(null);
        getContentResolver().unregisterContentObserver(observer);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
