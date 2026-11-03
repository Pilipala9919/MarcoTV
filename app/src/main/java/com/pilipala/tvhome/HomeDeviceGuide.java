// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import java.util.Locale;

/** Bundled guidance only: community hints never select privileged vendor commands. */
final class HomeDeviceGuide {
    enum Issue { NONE, CONNECTION, AUTHORIZATION, TIMEOUT, SYSTEM }
    final String brand, steps, source, evidence;

    private HomeDeviceGuide(String brand, String steps, String source, String evidence) {
        this.brand = brand;
        this.steps = steps;
        this.source = source;
        this.evidence = evidence;
    }

    static String family(String manufacturer, String brand) {
        String m = normalized(manufacturer), b = normalized(brand);
        if (one(m,b,"xiaomi","redmi","mitv")) return "小米 / Redmi";
        if (one(m,b,"tcl","ffalcon","ffalcontv","falcon")) return "TCL / 雷鸟";
        if (one(m,b,"hisense","vidda","vidaa")) return "海信 / Vidda";
        if (one(m,b,"skyworth","coocaa")) return "创维 / 酷开";
        if (one(m,b,"huawei","honor")) return "华为 / 荣耀";
        if (one(m,b,"sony")) return "索尼";
        return "其他品牌";
    }

    private static String normalized(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }
    private static boolean one(String m, String b, String... names) {
        for (String n : names) if (m.equals(n) || b.equals(n)) return true;
        return false;
    }

    static HomeDeviceGuide find(String manufacturer, String brand) {
        String family = family(manufacturer,brand);
        String community = "社区入口参考 · 尚未核实当前型号与固件";
        String source = "https://www.znds.com/tv-1270973-1-1.html";
        if (family.equals("小米 / Redmi")) return new HomeDeviceGuide(family,
                "在原厂设置查找「账号与安全 → ADB 调试」，选择允许。若没有该项，先在「关于 → 产品型号」连续确认至开发者提示。不同固件菜单可能不同。",
                "https://dev.mi.com/docs/mitv/user_guide/", "官方旧版入口参考 · 新固件以本机菜单为准");
        if (family.equals("TCL / 雷鸟")) return new HomeDeviceGuide(family,
                "国内部分固件：设置 → 系统 → 系统信息／本机信息，在该页依次按上、下、左、右，查看是否出现 ADB 开关。海外 Google TV 请在关于页激活开发者选项。找不到时不要套用其他型号的工程参数。",
                source, community);
        if (family.equals("创维 / 酷开")) return new HomeDeviceGuide(family,
                "部分酷开系统：设置 → 关于本机 → 本机信息 → 更多信息，在该页依次按上、上、下、下、左、右、左、右，查看通用设置中的 ADB 开关。只查看调试项，其他工程参数保持原值；当前固件可能没有此入口。",
                "https://www.znds.com/tv-1239756-1-1.html", community);
        if (family.equals("海信 / Vidda")) return new HomeDeviceGuide(family,
                "先在原厂设置 → 关于／系统信息核对具体系统。若有开发者选项，查找 USB／网络调试。国内聚好看与海外 Google TV、VIDAA 的入口不同；没有调试项时先用手机向导核对型号，不需要修改工厂模式或清空电视。",
                source, community);
        if (family.equals("华为 / 荣耀")) return new HomeDeviceGuide(family,
                "第三方应用安装和默认桌面是两项能力。请在本机系统设置查找实际提供的开发人员选项与调试入口；未提供时可尝试辅助功能兼容。仅凭 HarmonyOS 版本不能确认 ADB 可用。",
                "https://consumer.huawei.com/cn/support/content/zh-cn15773624/", "官方安装资料 · 不代表开放 ADB 或默认桌面");
        return new HomeDeviceGuide(family,
                "设置 → 系统／设备偏好设置 → 关于 → Build／内部版本号，连续确认至开发者提示，再返回开发者选项查看调试开关。仅提供无线配对码时，请使用手机／电脑助手路线。",
                "https://developer.android.com/tools/adb", "Android 通用入口 · 以本机实际能力为准");
    }

    static String explanation(Issue issue) {
        switch(issue) {
            case CONNECTION: return "尚未连接电视自身的网络调试，请先开启本机提供的调试开关。";
            case AUTHORIZATION: return "调试连接尚未授权，请在电视系统弹窗中选择允许。";
            case TIMEOUT: return "等待已结束。准备好调试开关和系统确认后，可以重新继续。";
            case SYSTEM: return "已连接调试，但配置尚未完成。请查看实际 Home 与服务状态，或尝试兼容方式。";
            default: return "按本机提供的入口开启调试，再继续自动设置。";
        }
    }

    /** One explicit settings visit allows one continuation; stale/Home exits never resume it. */
    static final class ReturnGate {
        private int epoch;
        private boolean armed;
        void arm(int value) { epoch=value; armed=true; }
        boolean take(int value, boolean foreground) {
            boolean allowed=armed && epoch==value && foreground;
            armed=false;
            return allowed;
        }
        void cancel() { armed=false; }
    }
}
