// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import java.io.IOException;
import java.util.Locale;

/** Fixed, capability-selected commands. Device names never become shell input. */
final class HomeShellSetup {
    interface Shell { String run(String command) throws Exception; }
    private final Shell shell;
    private final int user;
    private String tool;
    private boolean authenticated;

    HomeShellSetup(Shell shell, int user) {
        if (user < 0) throw new IllegalArgumentException("user");
        this.shell = shell;
        this.user = user;
    }

    void authenticate() throws Exception {
        String uid = shell.run("id -u").trim();
        if (!"2000".equals(uid) && !"0".equals(uid))
            throw new IOException("Not an ADB shell");
        authenticated = true;
    }

    void grantSettings() throws Exception {
        if (!authenticated) throw new IOException("Authorization required");
        shell.run("pm grant --user " + user
                + " com.pilipala.tvhome android.permission.WRITE_SECURE_SETTINGS");
    }

    boolean selectHome(String component) throws Exception {
        if (!authenticated) throw new IOException("Authorization required");
        if (!validComponent(component)) throw new IllegalArgumentException("HOME component");
        // Probe the actual firmware rather than assuming every device of a brand is alike.
        if (tool == null) {
            if (supports(shell.run("cmd package help"))) tool = "cmd package";
            else if (supports(shell.run("pm help"))) tool = "pm";
            else tool = "";
        }
        if (tool.length() == 0) return false;
        shell.run(tool + " set-home-activity --user " + user + " '" + component + "'");
        return true; // Issued, NOT verified. Caller must read actual system state.
    }

    static boolean supports(String help) {
        return help != null && java.util.regex.Pattern.compile("(?m)^\\s*set-home-activity(?:\\s|$)")
                .matcher(help).find();
    }

    static boolean validComponent(String name) {
        return name != null && name.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+/[A-Za-z_.$][A-Za-z0-9_.$]*");
    }

    static boolean validApkPath(String path) {
        return path != null && path.startsWith("/data/app/") && path.endsWith(".apk")
                && !path.contains("..") && path.matches("/data/app/[A-Za-z0-9_./=-]+\\.apk");
    }

    void startTaskBridge(String apkPath, int appUid) throws Exception {
        if (!authenticated) throw new IOException("Authorization required");
        if (!validApkPath(apkPath) || appUid <= 0) throw new IllegalArgumentException("Bridge arguments");
        for (String token : shell.run("pidof marcotv-task-bridge 2>/dev/null").split("\\s+")) {
            if (!token.matches("[0-9]+")) continue;
            String cmdline = shell.run("tr '\\0' ' ' </proc/" + token + "/cmdline 2>/dev/null");
            if (cmdline.contains("com.pilipala.tvhome.TaskBridgeServer") || cmdline.trim().equals("marcotv-task-bridge"))
                shell.run("kill " + token + " 2>/dev/null");
        }
        shell.run("CLASSPATH='" + apkPath + "' app_process /system/bin --nice-name=marcotv-task-bridge "
                + "com.pilipala.tvhome.TaskBridgeServer " + appUid + " " + user
                + " </dev/null >/data/local/tmp/marcotv-task-bridge.log 2>&1 &");
    }

    static String brand(String manufacturer, String brand) {
        String m = manufacturer == null ? "" : manufacturer.trim().toLowerCase(Locale.ROOT);
        String b = brand == null ? "" : brand.trim().toLowerCase(Locale.ROOT);
        if (m.equals("xiaomi") || b.equals("xiaomi") || b.equals("redmi") || b.equals("mitv")) return "小米 / Redmi";
        if (m.equals("tcl") || b.equals("tcl")) return "TCL";
        if (m.equals("huawei") || b.equals("huawei") || b.equals("honor")) return "华为 / 荣耀";
        if (m.equals("hisense") || b.equals("hisense")) return "海信";
        if (m.equals("skyworth") || b.equals("skyworth") || b.equals("coocaa")) return "创维 / 酷开";
        if (m.equals("sony") || b.equals("sony")) return "索尼";
        return "其他品牌";
    }
}
