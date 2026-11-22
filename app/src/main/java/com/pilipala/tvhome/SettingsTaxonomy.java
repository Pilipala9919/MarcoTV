// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

/** Pure Apple TV-style settings taxonomy: 5 flat top-level categories with grouped rows. */
final class SettingsTaxonomy {
    static final int GENERAL = 0;
    static final int APPS = 1;
    static final int HOME = 2;
    static final int NETWORK = 3;
    static final int SYSTEM = 4;

    static final class Category {
        final String id;
        final String title;
        final String subtitle;
        final String summary;
        final Section[] sections;
        Category(String id, String title, String subtitle, String summary, Section... sections) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.summary = summary;
            this.sections = sections;
        }
    }

    static final class Section {
        final String title;
        final Item[] items;
        Section(String title, Item... items) {
            this.title = title;
            this.items = items;
        }
    }

    static final class Item {
        static final int DRILL = 0;
        static final int TOGGLE = 1;
        static final int CYCLE = 2;
        static final int ACTION = 3;

        final String key;
        final String title;
        final int kind;
        final String hint;

        Item(String key, String title, int kind, String hint) {
            this.key = key;
            this.title = title;
            this.kind = kind;
            this.hint = hint;
        }
    }

    static final Category[] CATEGORIES = {
        new Category("general", "通用与桌面", "外观、壁纸与屏保",
                "管理桌面卡片样式、时钟字体、风景壁纸以及闲置屏保与休眠策略。",
                new Section("桌面外观",
                        new Item("magic_desktop", "Magic Desktop 个性化", Item.DRILL,
                                "实时 3D 预览并调整卡片高度、位置、圆角、时钟字体、壁纸压暗与动效时长，修改后自动保存。"),
                        new Item("wallpaper", "桌面壁纸", Item.DRILL,
                                "在「我的壁纸」中通过缩略图切换静态或动态壁纸，或通过手机「扫码导入壁纸」上传图片与 MP4 动态壁纸。")),
                new Section("屏保与休眠",
                        new Item("ambient_enabled", "Marco TV 屏保", Item.TOGGLE,
                                "桌面闲置后进入低干扰屏保，静态时钟会缓慢移动以减少烧屏风险。"),
                        new Item("ambient_pure_wallpaper", "闲置纯净壁纸模式", Item.TOGGLE,
                                "进入屏保或桌面按返回键时隐藏时钟并取消压暗，纯净欣赏动态或静态壁纸，按任意键恢复桌面。"),
                        new Item("ambient_sleep", "屏保后允许电视休眠", Item.CYCLE,
                                "到达设定时长后释放亮屏锁定并停止动态壁纸循环，交还电视自身的待机休眠设置。按确定切换时长。"),
                        new Item("ambient_preview", "立即预览屏保", Item.DRILL,
                                "立即查看当前屏保样式与渐变动画效果。"))),
        new Category("apps", "应用与媒体", "排序、卡片与正在播放",
                "整理桌面应用入口、卡片排序、收藏置顶与左上角正在播放媒体卡片。",
                new Section("应用与排序",
                        new Item("add_app", "添加应用到桌面", Item.DRILL,
                                "浏览已安装应用并手动添加到桌面，适合未自动显示的 TV 或手机应用。"),
                        new Item("all_apps", "全部应用", Item.DRILL,
                                "以网格查看全部可启动应用，按确定打开，长按可管理、卸载或添加到桌面。"),
                        new Item("app_manager", "应用管理与卸载", Item.DRILL,
                                "集中查看全部已安装应用（支持按可卸载/已隐藏/系统筛选），直接执行系统确认卸载、恢复显示或打开系统应用信息。"),
                        new Item("tv_sort", "应用排序", Item.DRILL,
                                "选择上次打开优先、首字母排序或自定义顺序；置顶卡片始终靠前。")),
                new Section("卡片管理",
                        new Item("pinned_cards", "管理置顶卡片", Item.DRILL,
                                "查看或取消已置顶的应用卡片；在桌面长按卡片也可单独调整。"),
                        new Item("favorite_cards", "管理收藏卡片", Item.DRILL,
                                "集中管理收藏行中的应用卡片。"),
                        new Item("hidden_cards", "恢复隐藏的桌面卡片", Item.DRILL,
                                "恢复之前从桌面隐藏的卡片；隐藏不会卸载应用本体。")),
                new Section("正在播放卡片",
                        new Item("now_playing", "桌面播放卡片", Item.TOGGLE,
                                "在桌面左上展示当前播放的封面与标题；仅读取系统媒体会话，不保存普通通知。"),
                        new Item("playback_source", "选择播放来源", Item.DRILL,
                                "同时存在多个播放器时，指定桌面优先显示的播放来源。"),
                        new Item("playback_permission", "通知使用权", Item.DRILL,
                                "媒体会话读取需要系统通知使用权；未提供设置页的电视可按说明通过 ADB 授权。"),
                        new Item("playback_restore", "恢复暂时隐藏的播放卡片", Item.ACTION,
                                "立即恢复本次运行期间暂时隐藏或暂停超时的媒体播放卡片。"))),
        new Category("home", "遥控器与多任务", "默认桌面、Home 与最近任务",
                "管理默认桌面启用、Home 键兼容与手势、开机唤醒恢复以及最近任务视图。",
                new Section("默认桌面与 Home 键",
                        new Item("default_home", "桌面启用与检查", Item.DRILL,
                                "按本机固件能力一键设置默认桌面或 Home 兼容，并检查 Home 返回与重启效果。"),
                        new Item("home_compat", "Home 键兼容模式", Item.TOGGLE,
                                "适用于限制替换默认桌面的厂商电视。仅管理 Marco TV 自身服务，保留其他辅助服务。"),
                        new Item("home_gestures", "Home 长按与原厂按键", Item.DRILL,
                                "设置 Home 长按动作、原厂按键放行模式以及双击响应间隔。"),
                        new Item("restore_home", "恢复设置前的桌面", Item.DRILL,
                                "停用 Marco TV 桌面接管并尝试切回设置前的原厂桌面。")),
                new Section("开机与待机唤醒",
                        new Item("boot_home", "开机打开 Marco TV", Item.TOGGLE,
                                "电视重启解锁后自动打开 Marco TV；实际效果取决于电视固件是否允许自启。"),
                        new Item("recent_wake", "唤醒后恢复桌面", Item.TOGGLE,
                                "从待机唤醒后自动回到 Marco TV；需开启 Home 兼容，任务服务可进一步辅助恢复。")),
                new Section("最近任务",
                        new Item("recent_enabled", "双击 Home 打开最近任务", Item.TOGGLE,
                                "连续按两次 Home 键呼出 Marco 最近任务卡片，上键或上滑可清除所选任务。"),
                        new Item("recent_open", "立即打开最近任务", Item.DRILL,
                                "立即打开系统最近任务视图，快速切换或清理后台应用。"),
                        new Item("recent_style", "任务卡片与动画", Item.DRILL,
                                "在 Magic Desktop 中调整最近任务卡片的大小、间距、倾斜角度、圆角与数量。"))),
        new Category("network", "网络与互联", "Wi-Fi、投屏与声音",
                "管理无线网络、视频 App DLNA 投屏、手机扫码 Marco 互联与媒体音量。",
                new Section("网络连接",
                        new Item("wifi", "Wi-Fi 与网络", Item.DRILL,
                                "查看当前网络状态、开关 Wi-Fi、连接附近无线网络或跳转电视系统网络设置。")),
                new Section("投屏与手机互联",
                        new Item("cast", "无线投屏与镜像", Item.DRILL,
                                "支持手机视频 App DLNA 投屏、扫码快投照片视频，以及启动系统无线镜像。"),
                        new Item("phone", "Marco 互联", Item.DRILL,
                                "同局域网手机扫码即可在浏览器整理卡片、上传壁纸与快投媒体，无需安装 App。")),
                new Section("声音",
                        new Item("sound", "媒体音量", Item.DRILL,
                                "调整当前电视媒体输出音量；若系统限制接口，请使用电视遥控器音量键。"))),
        new Category("system", "系统与关于", "更新、状态、备份与本机信息",
                "检查软件更新、查看只读状态中心、管理本地备份与脱敏诊断，或了解本机信息。",
                new Section("更新与备份",
                        new Item("updates", "软件更新", Item.DRILL,
                                "检查、下载并校验 Marco TV 新版，或调整自动检查方式与稳定/体验渠道。"),
                        new Item("health_status", "状态中心", Item.DRILL,
                                "只读查看默认桌面、Home 服务、系统授权、播放会话、存储与任务服务状态。"),
                        new Item("backup", "备份与恢复", Item.DRILL,
                                "导出设置与自定义封面到 U 盘或电脑，或从本机快照一键恢复（不上传云端）。")),
                new Section("诊断与高级恢复",
                        new Item("diagnostics", "本地诊断与问题反馈", Item.DRILL,
                                "查看、导出或清除本机脱敏异常记录，或预览后提交问题反馈。"),
                        new Item("adb_restore", "ADB 自动恢复", Item.DRILL,
                                "高级功能：开机或运行期间自动恢复调试开关，建议按需开启 24 小时后自动停止。"),
                        new Item("recover_now", "立即执行已启用的恢复", Item.DRILL,
                                "仅执行当前已启用的 ADB / Home 恢复项，不会新增任何未确认权限。"),
                        new Item("debug_help", "调试与授权说明", Item.DRILL,
                                "查看网络调试授权方式、不同电视固件差异与卸载后的重新授权说明。")),
                new Section("帮助与关于",
                        new Item("guide", "首次使用引导", Item.DRILL,
                                "重新浏览 5 步快速上手引导，了解桌面导航、默认桌面与播放卡片设置。"),
                        new Item("device_info", "设备信息", Item.DRILL,
                                "查看电视品牌、型号、Android 与固件版本（不读取或显示设备序列号）。"),
                        new Item("about", "关于 Marco TV", Item.DRILL,
                                "查看版本、作者署名、免费开源声明以及完整的第三方开源许可协议。")))
    };

    static int indexOfCategory(String id) {
        if (id == null) return -1;
        for (int i = 0; i < CATEGORIES.length; i++) if (CATEGORIES[i].id.equals(id)) return i;
        return -1;
    }

    static Item findItem(String key) {
        if (key == null) return null;
        for (Category c : CATEGORIES) {
            for (Section s : c.sections) {
                for (Item item : s.items) if (item.key.equals(key)) return item;
            }
        }
        return null;
    }

    static int totalItems() {
        int count = 0;
        for (Category c : CATEGORIES) for (Section s : c.sections) count += s.items.length;
        return count;
    }
}
