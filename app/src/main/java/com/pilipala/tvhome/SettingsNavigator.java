// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.Context;
import android.os.Build;
import android.text.InputFilter;
import android.view.WindowManager;
import android.widget.EditText;
import java.util.ArrayList;
import java.util.Collections;

/** Builds and navigates every settings panel; the activity only hosts dialogs. */
final class SettingsNavigator {
    private final MainActivity host;
    SettingsNavigator(MainActivity host) { this.host = host; }

    void settings(){host.showDialog(settingsDialog());}
    TvDialog settingsDialog() {
        int idleSeconds = host.preferences.getInt("idle_seconds", 30);
        String idleLabel = idleSeconds == 0 ? "不隐藏" : idleSeconds + " 秒";
        String[] options = {"时钟与屏保", "壁纸图库", "更换本地壁纸", "设备设置", "关于 Marco TV", "开机与默认桌面", "卡片外观", "恢复隐藏的卡片"};
        TvDialog dialog = new TvDialog.Builder(host).setTitle("桌面设置")
                .setOrder("main","clock","wallpapers","local_wallpaper","device","about","startup","cards","hidden")
                .setItems(options, (d, which) -> {
                    switch (which) {
                        case 0: host.handler.post(this::ambientSettings); break;
                        case 1: host.handler.post(this::wallpaperGallery); break;
                        case 2:
                            host.cover.pendingId = "__wallpaper__"; host.handler.post(host.cover::chooseCoverImage);
                            break;
                        case 3: host.handler.post(this::deviceSettings); break;
                        case 4: host.handler.post(this::about); break;
                        case 5: host.handler.post(this::systemPanel); break;
                        case 6: host.handler.post(this::appearanceSettings); break;
                        case 7: host.handler.post(this::hiddenCards); break;
                    }
                }).setNegativeButton("返回", null).create();
        return dialog;
    }

    void about(){
        host.showDialog(new TvDialog.Builder(host).setTitle("Marco TV")
                .setMessage(ProjectInfo.about())
                .setPositiveButton("返回",null)
                .setNeutralButton("开源许可",(d,w)->host.handler.post(()->host.showDialog(new TvDialog.Builder(host)
                        .setTitle("开源许可")
                        .setMessage(ProjectInfo.licenses(host))
                        .setPositiveButton("返回上一级",null).create())))
                .create());
    }

    void slider(String title, String key, int min, int max, int fallback, String unit) {
        ContinuousSlider slider = new ContinuousSlider(host, title, unit, min, max, host.preferences.getInt(key, fallback), value -> {
            host.preferences.edit().putInt(key, value).apply(); host.home.refreshAppearance(); host.home.invalidate();
            if(key.equals("ambient_transition"))host.home.replayPreview();
        });
        slider.compact();
        TvDialog.Builder builder=new TvDialog.Builder(host).setTitle(title).setPreview(key)
                .setView(slider).setPositiveButton("返回上一级",null);
        if(key.equals("ambient_transition"))builder.setNeutralButton("重播预览",(d,w)->host.handler.post(()->slider(title,key,min,max,fallback,unit)));
        host.showDialog(builder.create());
    }

    void wallpaperGallery() {
        String[] labels = {"雾蓝海岸", "暖沙丘", "极夜山脊", "峡谷", "壁纸亮度"};
        host.showDialog(new TvDialog.Builder(host).setTitle("壁纸图库").setMessage("四张风景壁纸 · 选择后立即预览")
                .setItems(labels, (d,w) -> {
                    if (w == 4) host.handler.post(() -> slider("壁纸压暗", "wallpaper_dim", 0, 90, 10, "%"));
                    else { host.preferences.edit().remove("wallpaper").putInt("wallpaper_builtin", w).apply(); host.loadWallpaper(); }
                }).setNegativeButton("返回", null).create());
    }

    void ambientSettings() {
        boolean enabled = AmbientIntegration.enabled(host);
        String[] labels = {"Marco TV 屏保：" + (enabled ? "已开启" : "未开启"), "进入屏保等待", "桌面时间大小", "屏保时间大小", "屏保时间亮度", "屏保背景压暗", "渐变时长", "立即预览屏保"};
        host.showDialog(new TvDialog.Builder(host).setTitle("时钟与屏保")
                .setOrder("clock","enabled","wait","normal_size","ambient_size","brightness","dim","duration","preview")
                .setMessage("闲置时，右上角时间慢慢放大，背景逐渐暗下。按方向键返回桌面。")
                .setItems(labels, (d,w) -> host.handler.post(() -> {
                    switch(w) {
                        case 0: host.preferences.edit().putBoolean("ambient_enabled", !enabled).apply();
                            AmbientIntegration.configure(host);
                            if (!enabled) host.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                            else host.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                            host.handler.post(this::ambientSettings); break;
                        case 1: slider("进入屏保等待", "idle_seconds", 0, 600, 30, " 秒"); break;
                        case 2: slider("桌面时间大小", "clock_size", 36, 160, 96, ""); break;
                        case 3: slider("屏保时间大小", "ambient_clock_size", 100, 280, 176, ""); break;
                        case 4: slider("屏保时间亮度", "ambient_clock_brightness", 40, 100, 100, "%"); break;
                        case 5: slider("屏保背景压暗", "ambient_dim", 0, 100, 72, "%"); break;
                        case 6: slider("渐变时长", "ambient_transition", 1, 30, 3, " 秒"); break;
                        case 7: previewAmbient(); break;
                    }
                })).setNegativeButton("返回", null).create());
    }

    void previewAmbient(){
        host.showDialog(new TvDialog.Builder(host).setTitle("屏保预览").setPreview("ambient_transition")
                .setPositiveButton("返回上一级",null)
                .setNeutralButton("重播预览",(d,w)->host.handler.post(this::previewAmbient)).create());
    }

    void deviceSettings() {
        host.showDialog(new TvDialog.Builder(host).setTitle("设备设置")
                .setOrder("device","network","sound","info","startup")
                .setMessage("常用设置，在桌面里完成")
                .setItems(new String[]{"网络与 Wi-Fi", "声音", "设备信息", "开机与默认桌面"}, (d, w) -> host.handler.post(() -> {
                    if (w == 0) host.network.show();
                    else if (w == 1) soundSettings();
                    else if (w == 2) host.showDialog(new TvDialog.Builder(host).setTitle("设备信息")
                            .setMessage(Build.MANUFACTURER + " · " + Build.MODEL + "\nAndroid " + Build.VERSION.RELEASE
                                    + "\nMarco TV " + ProjectInfo.VERSION + "\n作者 " + ProjectInfo.AUTHOR)
                            .setPositiveButton("返回", (dialog, which) -> host.handler.post(this::deviceSettings)).create());
                    else systemPanel();
                })).setNegativeButton("返回", (d,w) -> host.handler.post(this::settings)).create());
    }

    void soundSettings() {
        android.media.AudioManager audio = (android.media.AudioManager) host.getSystemService(Context.AUDIO_SERVICE);
        int value = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        int max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        ContinuousSlider slider = new ContinuousSlider(host, "媒体音量", "", 0, Math.max(1,max), value, v -> {
            try { audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0); }
            catch (RuntimeException e) { host.toast("系统限制音量调整，请使用遥控器音量键"); }
        });
        host.showDialog(new TvDialog.Builder(host).setTitle("声音").setMessage("左右调整媒体音量")
                .setView(slider).setPositiveButton("完成", null).create());
    }

    void tileOptions() {
        if (host.home.row < 0 || host.home.rows.isEmpty()) return;
        Row row = host.home.rows.get(host.home.row);
        if (row.tiles.isEmpty()) return;
        Tile tile = row.tiles.get(row.selected);
        boolean favorite = host.cards.contains("favorites", tile.id);
        String[] options = {"向左移动", "向右移动", "重命名", "更换封面", favorite ? "取消收藏" : "加入收藏", "隐藏卡片", "恢复默认外观", tile.kind == 1 ? "打开输入源" : "应用信息"};
        host.showDialog(new TvDialog.Builder(host).setTitle(tile.label).setItems(options, (d, which) -> {
            if (which < 2) {
                int next = row.selected + (which == 0 ? -1 : 1);
                if (next >= 0 && next < row.tiles.size()) {
                    Collections.swap(row.tiles, row.selected, next); row.selected = next;
                    StringBuilder order = new StringBuilder();
                    for (Tile t : row.tiles) { if (order.length() > 0) order.append('|'); order.append(t.id); }
                    host.preferences.edit().putString(row.orderKey, order.toString()).apply();
                    host.home.ensureVisible(); host.home.animateFocus(); host.home.describeFocus();
                }
            } else host.handler.post(() -> {
                switch (which) {
                    case 2: renameCard(tile); break;
                    case 3: coverOptions(tile); break;
                    case 4: host.cards.toggle("favorites", tile.id, !favorite); host.loadApplications(); host.toast(favorite ? "已取消收藏" : "已加入收藏，按上键可到收藏分组"); break;
                    case 5:
                        host.showDialog(new TvDialog.Builder(host).setTitle("隐藏「" + tile.label + "」？")
                                .setMessage("从桌面与收藏中隐藏，不卸载应用。可在桌面设置中恢复。")
                                .setPositiveButton("隐藏", (dialog, button) -> { host.cards.toggle("hidden", tile.id, true); host.loadApplications(); })
                                .setNegativeButton("返回", null).create()); break;
                    case 6: host.cards.reset(tile.id); host.loadApplications(); break;
                    case 7:
                        if (tile.kind == 1) host.launch(tile);
                        else {
                            String version = "";
                            try { version = host.getPackageManager().getPackageInfo(tile.id, 0).versionName; } catch (Exception ignored) { }
                            host.showDialog(new TvDialog.Builder(host).setTitle(tile.label).setMessage("版本 " + version + "\n" + tile.id)
                                    .setPositiveButton("打开应用", (dialog, button) -> host.launch(tile))
                                    .setNegativeButton("返回", null).create());
                        }
                        break;
                }
            });
        }).setNegativeButton("返回", null).create());
    }

    void renameCard(Tile tile) {
        EditText input = new EditText(host);
        input.setSingleLine(true); input.setText(tile.label); input.selectAll();
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        input.setHint("输入卡片名称"); input.setContentDescription("卡片名称");
        int padding = Math.round(24 * host.getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding / 2, padding, padding / 2);
        host.showDialog(new TvDialog.Builder(host).setTitle("重命名卡片").setView(input)
                .setPositiveButton("保存", (d, w) -> { host.cards.setName(tile.id, input.getText().toString()); host.loadApplications(); })
                .setNeutralButton("原始名称", (d, w) -> { host.cards.setName(tile.id, ""); host.loadApplications(); })
                .setNegativeButton("返回", null).create());
    }

    void coverOptions(Tile tile) {
        String[] names = {"原始图标或横幅", "本地图片…", "午夜蓝", "森林绿", "暖琥珀", "雾紫"};
        String[] styles = {"original", "image", "blue", "green", "amber", "purple"};
        host.showDialog(new TvDialog.Builder(host).setTitle("更换封面 · " + tile.label)
                .setItems(names, (d, which) -> {
                    if (which == 1) { host.cover.pendingId = tile.id; host.handler.post(host.cover::chooseCoverImage); }
                    else { host.cards.cover(tile.id, styles[which]); host.loadApplications(); }
                }).setNegativeButton("返回", null).create());
    }

    void hiddenCards() {
        ArrayList<Tile> hidden = new ArrayList<>();
        for (Tile tile : host.allTiles) if (host.cards.contains("hidden", tile.id)) hidden.add(tile);
        if (hidden.isEmpty()) { host.toast("没有隐藏的卡片"); return; }
        String[] labels = new String[hidden.size()];
        for (int i = 0; i < hidden.size(); i++) labels[i] = hidden.get(i).label;
        host.showDialog(new TvDialog.Builder(host).setTitle("选择要恢复的卡片").setItems(labels, (d, which) -> {
            host.cards.toggle("hidden", hidden.get(which).id, false); host.loadApplications();
        }).setNeutralButton("全部恢复", (d, w) -> {
            for (String id : host.cards.set("hidden")) host.cards.toggle("hidden", id, false); host.loadApplications();
        }).setNegativeButton("返回", null).create());
    }

    void appearanceSettings() {
        host.showDialog(new TvDialog.Builder(host).setTitle("卡片外观")
                .setOrder("cards","radius","zoom","labels","reset")
                .setItems(new String[]{"卡片圆角", "选中放大幅度", "未选中名称亮度", "恢复默认外观设置"}, (d,w) -> host.handler.post(() -> {
                    switch(w) {
                        case 0: slider("卡片圆角", "card_radius", 0, 90, 32, ""); break;
                        case 1: slider("选中放大幅度", "focus_scale", 0, 30, 16, "%"); break;
                        case 2: slider("未选中名称亮度", "label_opacity", 0, 100, 0, "%"); break;
                        case 3: host.preferences.edit().remove("card_radius").remove("focus_scale").remove("label_opacity").apply(); host.home.refreshAppearance(); host.home.invalidate(); break;
                    }
                })).setNegativeButton("返回", null).create());
    }

    void systemPanel(){
        final int epoch=host.menuEpoch;
        SystemIntegration.show(host,d->{if(epoch==host.menuEpoch)host.showDialog(d);});
    }
}
