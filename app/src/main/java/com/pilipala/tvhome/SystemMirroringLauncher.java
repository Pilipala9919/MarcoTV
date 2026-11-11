// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.*;

/**
 * Discovers and launches built-in OEM or Android system wireless display / Miracast components.
 * Marco TV itself does not implement Miracast or proprietary screen-mirroring protocols.
 */
final class SystemMirroringLauncher {
    private static final class MirrorTarget {
        final String label;
        final Intent intent;

        MirrorTarget(String label, Intent intent) {
            this.label = label;
            this.intent = intent;
        }
    }

    private SystemMirroringLauncher() {}

    static void open(MainActivity host, Runnable onOpenWaiting, Runnable onBackToCast) {
        PackageManager pm = host.getPackageManager();
        ArrayList<MirrorTarget> targets = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();

        String[][] knownPackages = {
                {"com.xiaomi.mitv.smartshare", "小米无线投屏（Miracast / 米联）"},
                {"com.duokan.airkan.tvbox", "小米多看投屏组件"},
                {"com.xiaomi.mitv.miracast", "小米无线显示（Miracast）"},
                {"com.hpplay.happycast", "乐播投屏"},
                {"com.tcl.wfd", "TCL 无线投屏"},
                {"com.hisense.multiscreen", "海信多屏互动"},
                {"com.skyworth.miracast", "创维无线投屏"},
                {"com.konka.miracast", "康佳无线投屏"},
                {"com.changhong.miracast", "长虹无线投屏"}
        };
        for (String[] pair : knownPackages) {
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pair[0]);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pair[0]);
            if (launch != null && seen.add(pair[0])) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                targets.add(new MirrorTarget(pair[1], launch));
            }
        }

        for (ApplicationInfo app : pm.getInstalledApplications(0)) {
            if (app.packageName.equals(host.getPackageName()) || seen.contains(app.packageName)) continue;
            String label = String.valueOf(app.loadLabel(pm));
            String lower = (label + " " + app.packageName).toLowerCase(Locale.ROOT);
            if (lower.contains("miracast") || lower.contains("smartshare") || lower.contains("airkan")
                    || label.contains("投屏") || label.contains("无线显示") || label.contains("多屏互动") || label.contains("屏幕镜像")) {
                Intent launch = pm.getLeanbackLaunchIntentForPackage(app.packageName);
                if (launch == null) launch = pm.getLaunchIntentForPackage(app.packageName);
                if (launch != null && seen.add(app.packageName)) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    targets.add(new MirrorTarget(label, launch));
                }
            }
        }

        String[][] settingsActions = {
                {"android.settings.WIFI_DISPLAY_SETTINGS", "系统无线显示设置（Miracast）"},
                {"android.settings.CAST_SETTINGS", "系统投射设置（Cast）"}
        };
        for (String[] action : settingsActions) {
            Intent intent = new Intent(action[0]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ResolveInfo info = pm.resolveActivity(intent, 0);
            if (info != null && info.activityInfo != null && info.activityInfo.exported
                    && !info.activityInfo.name.endsWith("EmptyStubActivity")
                    && seen.add(info.activityInfo.packageName + "/" + info.activityInfo.name)) {
                targets.add(new MirrorTarget(action[1], intent));
            }
        }

        if (targets.isEmpty()) {
            host.showDialog(new TvDialog.Builder(host).setPageId("cast_mirror_none").setTitle("未检测到系统无线镜像入口")
                    .setMessage("当前电视固件未开放独立的 Miracast / 无线显示页面。\n\n你仍然可以直接使用：\n"
                            + "1. 视频 App 投屏（Marco TV 已内置 DLNA 接收器，无需外部软件）\n"
                            + "2. Marco 互联手机扫码快投（直接投射手机相册照片、视频和直播链接）")
                    .setPositiveButton("打开等待投屏页", (d, w) -> onOpenWaiting.run())
                    .setNegativeButton("返回", null).create());
            return;
        }
        if (targets.size() == 1) {
            try {
                host.startActivity(targets.get(0).intent);
                return;
            } catch (RuntimeException ignored) {}
        }
        String[] names = new String[targets.size()];
        for (int i = 0; i < targets.size(); i++) names[i] = targets.get(i).label;
        host.showDialog(new TvDialog.Builder(host).setPageId("cast_mirror_list").setTitle("选择系统无线镜像入口")
                .setMessage("整屏无线镜像由电视内置无线显示或第三方镜像组件提供；视频 App 投屏可直接留在 Marco TV 内使用。")
                .setItems(names, (d, w) -> {
                    try {
                        host.startActivity(targets.get(w).intent);
                    } catch (RuntimeException e) {
                        host.toast("系统未允许直接打开该镜像页面");
                    }
                }).setNegativeButton("返回", (d, w) -> onBackToCast.run()).create());
    }
}
