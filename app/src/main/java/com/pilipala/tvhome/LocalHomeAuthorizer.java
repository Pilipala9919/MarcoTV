// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.*;

/** Explicit local-only setup. No arbitrary commands, boot ADB reconnect or OEM guesses. */
final class LocalHomeAuthorizer {
    interface Result { void complete(String text); }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean cancelled;
    private volatile boolean expired;
    private volatile LocalAdbWire connection;
    private long deadline;
    private final Runnable timeout = () -> {
        expired = true;
        LocalAdbWire wire = connection;
        if (wire != null) wire.close();
    };

    LocalHomeAuthorizer(Context c) { context = c.getApplicationContext(); }
    void start(Result callback) { start(false, callback); }
    void restore(Result callback) { start(true, callback); }

    private void check() throws IOException {
        if (cancelled || expired || SystemClock.elapsedRealtime() >= deadline)
            throw new IOException("Operation ended");
    }

    private <T> T onMain(Callable<T> operation) throws Exception {
        FutureTask<T> task = new FutureTask<>(() -> { check(); return operation.call(); });
        main.post(task);
        try { return task.get(10, TimeUnit.SECONDS); }
        catch (Exception e) { task.cancel(false); throw e; }
    }

    private void start(boolean restore, Result callback) {
        deadline = SystemClock.elapsedRealtime() + 90000;
        main.postDelayed(timeout, 90000);
        worker.execute(() -> {
            String result;
            try {
                check();
                // Existing authorization can configure the restricted edition without ADB.
                boolean localOnly = onMain(() -> restore ? canRestoreLocally()
                        : BuildEdition.RESTRICTED && SystemIntegration.authorized(context));
                if (localOnly) result = restore ? onMain(this::restoreLocalResult) : "使用已有系统设置权限。\n" + finishSetup();
                else {
                    File folder = new File(context.getNoBackupFilesDir(), "home-setup");
                    if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot save local key");
                    LocalAdbWire wire = new LocalAdbWire();
                    connection = wire;
                    check();
                    java.security.KeyPair key = LocalAdbWire.key(folder);
                    check();
                    wire.connect("127.0.0.1", 5555, key, status -> { });
                    check();
                    HomeShellSetup commands = new HomeShellSetup(command -> {
                        check();
                        String output = wire.shell(command);
                        check();
                        return output;
                    }, android.os.Process.myUid() / 100000);
                    commands.authenticate();
                    Diagnostics.event("HOME_AUTO_ADB_AUTHENTICATED");
                    if (restore) result = restore(commands);
                    else {
                        onMain(() -> { HomeSetupSnapshot.capture(context); HomeSetup.begin(context); return null; });
                        commands.grantSettings();
                        // An explicit repair re-applies HOME even if a stale OEM preference
                        // table still lists us. A read-only status query never comes here.
                        if (!BuildEdition.RESTRICTED) commands.selectHome(HomeSetup.OWN);
                        result = "本机调试连接已授权。\n" + finishSetup();
                    }
                }
            } catch (Exception e) {
                result = expired ? "设置等待超时，请检查电视的调试授权后重试。"
                        : "自动设置未完成。请检查本机网络调试是否已打开，并在电视弹窗中允许连接；也可使用其他设置方式。";
                result += "\n已执行的步骤可能保留，请查看当前状态或恢复设置。普通网络调试仅支持本机 5555 端口，暂不支持配对码。";
                Diagnostics.event(restore ? "HOME_AUTO_RESTORE_INCOMPLETE" : "HOME_AUTO_SETUP_INCOMPLETE");
                // A previously granted settings permission survives ADB being switched off.
                // Only an explicit, still-live setup may use it; timeout/cancel never does.
                if (!restore && !cancelled && !expired) try {
                    String fallback = onMain(() -> SystemIntegration.authorized(context)) ? finishSetup() : null;
                    if (fallback != null) {
                        Diagnostics.event("HOME_AUTO_EXISTING_PERMISSION_FALLBACK");
                        result = "本机调试暂不可用，使用已有系统设置权限。\n" + fallback;
                    }
                } catch (Exception ignored) { }
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

    /** Worker orchestration; each settings mutation is serialized on the main thread. */
    private String finishSetup() throws Exception {
        String ready = onMain(() -> {
            HomeSetupSnapshot.capture(context);
            if (!HomeSetup.prefs(context).getBoolean("setup_requested", false) || HomeSetup.paused(context))
                HomeSetup.begin(context);
            if (SystemIntegration.homeIsOurs(context)) {
                HomeSetup.acceptNative(context);
                Diagnostics.event("HOME_AUTO_NATIVE_SELECTED");
                return "系统当前 Home 入口已选择 Marco TV。\n请继续检查实体 Home 返回及实际重启效果。";
            }
            if (!SystemIntegration.authorized(context))
                return "电视未确认默认桌面，也未授予兼容设置权限。请使用系统桌面选择或辅助功能设置。";
            return null;
        });
        if (ready != null) return ready;
        boolean rebind = onMain(() -> {
            HomeSetup.requestCompatibility(context);
            // An allowed-but-dead binding survives some reinstalls/firmware restarts.
            // Only this explicit setup may toggle our own service; other entries stay.
            if (HomeSetup.serviceEnabled(context) && !HomeKeyService.connected) {
                Diagnostics.event("HOME_AUTO_SERVICE_REBIND_REQUESTED");
                return SystemIntegration.removeOwnHomeService(context);
            }
            return false;
        });
        if (rebind) {
            // Let AccessibilityManager observe the removal before adding our entry back.
            Thread.sleep(700);
            check();
        }
        onMain(() -> {
            SystemIntegration.recover(context);
            HomeSetup.finishCompatibilityConsent(context);
            SystemIntegration.startRecovery(context);
            return null;
        });
        for (int i=0; i<30; i++) {
            check();
            if (HomeKeyService.connected || !onMain(() -> HomeSetup.serviceEnabled(context))) break;
            Thread.sleep(100);
        }
        return onMain(() -> {
            boolean enabled = HomeSetup.serviceEnabled(context);
            Diagnostics.event(enabled && HomeKeyService.connected ? "HOME_AUTO_COMPAT_CONNECTED" : "HOME_AUTO_COMPAT_UNCONNECTED");
            return enabled ? (HomeKeyService.connected ? "Home 兼容服务已连接。" : "系统已允许 Home 服务，但尚未连接；还不能确认按键接管生效。")
                    + "\n系统实际 Home 入口未改为 Marco TV，使用兼容方式返回；仍需检查实体 Home 和实际重启。"
                    : "已获得设置授权，但电视未允许启用 Home 服务。请在其他设置方式中打开系统辅助功能。";
        });
    }

    private boolean canRestoreLocally() {
        if (!HomeSetup.prefs(context).getBoolean(HomeSetupSnapshot.PREFIX + "saved", false)) return true;
        String target = HomeSetupSnapshot.target(context);
        boolean nativeBefore = HomeSetup.prefs(context).getBoolean(HomeSetupSnapshot.PREFIX + "native", false);
        return target.length() > 0 && HomeSetupSnapshot.matches(context, target)
                || nativeBefore && SystemIntegration.homeIsOurs(context)
                || target.length() == 0 && !SystemIntegration.homeIsOurs(context);
    }

    private String restore(HomeShellSetup commands) throws Exception {
        String target = onMain(() -> HomeSetupSnapshot.target(context));
        if (target.length() == 0) return "没有可核实的原桌面记录，请在其他设置方式中打开系统桌面选择。恢复记录仍保留。";
        // Stop fallback interception before returning HOME to the previous launcher.
        onMain(() -> { HomeSetup.pause(context, "正在恢复设置前的桌面。"); SystemIntegration.startRecovery(context); return null; });
        boolean issued = commands.selectHome(target);
        boolean verified = issued && onMain(() -> HomeSetupSnapshot.matches(context, target));
        if (!verified) return "系统未确认切回原桌面。自动恢复已暂停；请打开系统桌面选择。恢复记录仍保留，可重试。";
        return onMain(this::restoreLocalResult);
    }

    private String restoreLocalResult() {
        if (!HomeSetup.prefs(context).getBoolean(HomeSetupSnapshot.PREFIX + "saved", false)) return "没有本次一键设置的恢复记录。";
        String target = HomeSetupSnapshot.target(context);
        boolean verifiedHome = target.length() > 0 && HomeSetupSnapshot.matches(context, target)
                || HomeSetup.prefs(context).getBoolean(HomeSetupSnapshot.PREFIX + "native", false)
                && SystemIntegration.homeIsOurs(context);
        if (!HomeSetupSnapshot.restoreLocal(context)) return "桌面恢复尚未全部完成。请在系统辅助功能中关闭本次启用的 Marco TV 服务，再重试恢复。";
        HomeSetupSnapshot.clear(context);
        Diagnostics.event("HOME_AUTO_RESTORED");
        return (verifiedHome ? "已恢复本次设置前的桌面配置；请按 Home 检查。"
                : "已撤回本次 Home 兼容配置。没有可核实的原默认桌面，请通过系统桌面选择检查。")
                + "\n调试授权与系统设置权限保留，其他应用的辅助服务没有修改。";
    }

    void cancel() {
        cancelled = true;
        main.removeCallbacks(timeout);
        LocalAdbWire wire = connection;
        if (wire != null) wire.close();
        worker.shutdownNow();
    }
}
