// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit, cancellable, local-only operation. No boot reconnect or arbitrary shell endpoint. */
final class LocalHomeAuthorizer {
    interface Result { void complete(String text); }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean cancelled;
    private volatile LocalAdbWire connection;
    private final Runnable timeout = () -> {
        LocalAdbWire wire = connection;
        if (wire != null) wire.close();
    };
    LocalHomeAuthorizer(Context c) { context = c.getApplicationContext(); }
    void start(Result callback) {
        main.postDelayed(timeout, 90000);
        worker.execute(() -> {
            String result;
            try {
                File folder = new File(context.getNoBackupFilesDir(), "home-setup");
                if (!folder.isDirectory() && !folder.mkdirs()) throw new java.io.IOException("无法创建本机授权存储");
                LocalAdbWire wire = new LocalAdbWire();
                connection = wire;
                if (cancelled) return;
                wire.connect("127.0.0.1", 5555, LocalAdbWire.key(folder), status -> { });
                if (cancelled) return;
                String uid = wire.shell("id -u").trim();
                if (!"2000".equals(uid) && !"0".equals(uid)) throw new java.io.IOException("此服务不具备系统调试权限");
                int user = android.os.Process.myUid() / 100000;
                if (cancelled) return;
                String granted = wire.shell("pm grant --user " + user + " com.pilipala.tvhome android.permission.WRITE_SECURE_SETTINGS");
                if (cancelled) return;
                // WRITE_SECURE_SETTINGS alone does not select the default HOME. Use the
                // explicitly authorized shell session for that separate action.
                String selected = wire.shell("cmd package set-home-activity --user " + user + " " + HomeSetup.OWN);
                if (cancelled) return;
                boolean nativeHome = SystemIntegration.homeIsOurs(context);
                boolean secure = SystemIntegration.authorized(context);
                if (nativeHome) HomeSetup.acceptNative(context);
                result = "本机调试连接：已授权\n系统设置权限：" + (secure ? "已获得" : "系统未授予")
                        + "\n默认桌面：" + (nativeHome ? "系统已选择 Marco TV" : "系统未确认选择，请使用标准设置或 Home 兼容")
                        + "\n当前仍需测试实体 Home 与重启。";
                Diagnostics.event(nativeHome ? "HOME_LOCAL_SETUP_SELECTED" : "HOME_LOCAL_SETUP_PARTIAL");
            } catch (Exception e) {
                result = "本机设置未完成：" + e.getClass().getSimpleName()
                        + "。请确认普通网络调试与系统授权；此路线只支持本机标准端口，不支持无线配对码。可返回选择其他路线。";
                Diagnostics.event("HOME_LOCAL_SETUP_UNAVAILABLE");
            } finally {
                main.removeCallbacks(timeout);
                LocalAdbWire wire = connection;
                if (wire != null) wire.close();
                worker.shutdown();
            }
            final String outcome = result;
            if (!cancelled) main.post(() -> { if (!cancelled) callback.complete(outcome); });
        });
    }
    void cancel() {
        cancelled = true;
        main.removeCallbacks(timeout);
        LocalAdbWire wire = connection;
        if (wire != null) wire.close();
        worker.shutdownNow();
    }
}
