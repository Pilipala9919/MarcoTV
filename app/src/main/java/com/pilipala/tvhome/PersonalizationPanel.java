// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.animation.ArgbEvaluator;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Magic Desktop live editor: collapsible parameter cards with instant auto-save,
 * GPU-accelerated 3D preview on the left, and a guarded Reset Defaults action.
 */
final class PersonalizationPanel extends LinearLayout {
    private static final ArgbEvaluator COLORS = new ArgbEvaluator();
    private final MainActivity host;
    private final Map<String, ParameterCard> controls = new LinkedHashMap<>();
    private final Map<String, Integer> defaults = new LinkedHashMap<>();
    private final Map<String, View> groups = new LinkedHashMap<>();
    private TvDialog page;
    private ChoiceMenu clockStyle;
    private ImageView pureToggleIcon;
    private TextView pureToggleValue;
    private String scene = "personalization";
    private boolean recentPreviewLoaded;

    PersonalizationPanel(MainActivity host) {
        super(host);
        this.host = host;
        setOrientation(VERTICAL);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);

        section("cards", "桌面卡片与动效");
        slider("卡片行上下位置", "card_row_position", 0, 100, 75, "%", "0% 靠上 · 100% 靠下；默认 75%");
        slider("卡片高度", "card_height", 70, 130, 100, "%", null);
        slider("卡片圆角", "card_radius", 0, 90, 32, "", null);
        slider("选中放大", "focus_scale", 0, 30, 16, "%", null);
        slider("未选中名称亮度", "label_opacity", 0, 100, 0, "%", null);
        slider("卡片质感", "card_surface", 0, 100, 55, "%", null);
        slider("界面动效时长", "motion_duration", 120, 500, 260, " ms", null);

        section("clock", "时间与屏保");
        clockStyle = new ChoiceMenu(host, "时钟字体", ClockScene.STYLES, host.preferences.getInt("clock_style", 0), value -> {
            host.preferences.edit().putInt("clock_style", value).apply();
            updateClockControls();
            host.home.refreshAppearance();
            host.home.invalidate();
        }, () -> selectScene("clock_style"));
        clockStyle.setOnBeforeOpen(() -> collapseAllExcept(null));
        LinearLayout.LayoutParams fontParams = new LinearLayout.LayoutParams(-1, -2);
        fontParams.bottomMargin = dp(6);
        addView(clockStyle, fontParams);

        slider("圆体倾斜幅度", "clock_tilt", 0, 14, 10, "°", "倾斜幅度仅用于「柔圆错倾」字体");
        slider("桌面时间大小", "clock_size", 36, 160, 96, "", null);
        slider("进入屏保等待", "idle_seconds", 0, 600, 30, " 秒", null);
        pureWallpaperToggle();
        slider("屏保时间大小", "ambient_clock_size", 100, 280, 176, "", null);
        slider("屏保时间亮度", "ambient_clock_brightness", 40, 100, 100, "%", null);
        slider("屏保背景压暗", "ambient_dim", 0, 100, 72, "%", null);
        slider("屏保渐变时长", "ambient_transition", 1, 30, 3, " 秒", null);
        action("屏保开关与系统接管…", () -> host.navigator.ambientSettings(), "personalization");

        section("wallpaper", "壁纸");
        slider("桌面壁纸压暗", "wallpaper_dim", 0, 100, 10, "%", null);

        section("recent", "最近任务卡片");
        slider("任务卡片大小", "recent_size", 60, 120, 100, "%", null);
        slider("任务卡片间距", "recent_spacing", 80, 360, 210, "", null);
        slider("任务卡片倾斜", "recent_tilt", 0, 18, 7, "°", null);
        slider("任务卡片圆角", "recent_radius", 8, 80, 36, "", null);
        slider("任务背景压暗", "recent_dim", 0, 90, 65, "%", null);
        slider("任务动画时长", "recent_duration", 120, 700, host.preferences.getInt("motion_duration", 260), " ms", null);
        slider("任务显示数量", "recent_limit", 3, 20, 10, " 个", null);
        slider("Home 双击间隔", "recent_double_ms", 200, 700, 400, " ms", null);

        updateClockControls();
    }

    private void updateClockControls() {
        ParameterCard tilt = controls.get("clock_tilt");
        int visibility = host.preferences.getInt("clock_style", 0) == 2 ? VISIBLE : GONE;
        if (tilt != null) {
            if (visibility != VISIBLE && tilt.isExpanded()) tilt.collapse(false);
            tilt.setVisibility(visibility);
        }
    }

    private void collapseAllExcept(ParameterCard keep) {
        if (clockStyle != null && clockStyle.isExpanded()) clockStyle.closeMenu(false);
        for (ParameterCard card : controls.values()) {
            if (card != keep && card.isExpanded()) card.collapse(false);
        }
    }

    private boolean handleBack() {
        if (clockStyle != null && clockStyle.isExpanded()) {
            clockStyle.closeMenu(true);
            return true;
        }
        for (ParameterCard card : controls.values()) {
            if (card.isExpanded()) {
                card.collapse(true);
                return true;
            }
        }
        return false;
    }

    private int dp(float n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    private void section(String id, String title) {
        TextView label = new TextView(host);
        label.setText(title);
        label.setTextColor(0xFFB7CDD9);
        label.setTextSize(13);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setPadding(dp(4), dp(14), 0, dp(7));
        addView(label);
        groups.put(id, label);
    }

    private void slider(String title, String key, int min, int max, int fallback, String unit, String hint) {
        final ParameterCard[] ref = {null};
        ParameterCard card = new ParameterCard(
                host, title, unit, min, max,
                host.preferences.getInt(key, fallback),
                hint,
                value -> {
                    host.preferences.edit().putInt(key, value).apply();
                    if (key.equals("clock_style")) updateClockControls();
                    host.home.refreshAppearance();
                    host.home.invalidate();
                    if (key.equals("ambient_transition")) host.home.replayPreview();
                    if (key.equals("idle_seconds")) host.screensaver.resume();
                },
                () -> selectScene(key),
                () -> collapseAllExcept(ref[0])
        );
        ref[0] = card;
        controls.put(key, card);
        defaults.put(key, fallback);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(6);
        addView(card, params);
    }

    private void pureWallpaperToggle() {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(46));
        row.setPadding(dp(12), dp(8), dp(12), dp(8));
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setClickable(true);
        GradientDrawable bg = TvDialog.background(0xFF232C37, dp(12));
        bg.setStroke(dp(1), 0x14FFFFFF);
        row.setBackground(bg);

        LinearLayout texts = new LinearLayout(host);
        texts.setOrientation(VERTICAL);
        TextView title = new TextView(host);
        title.setText("闲置纯净壁纸模式");
        title.setTextColor(0xFFF0F4F8);
        title.setTextSize(13.5f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setIncludeFontPadding(false);
        texts.addView(title);

        pureToggleValue = new TextView(host);
        pureToggleValue.setTextSize(10.5f);
        pureToggleValue.setTextColor(0xFF9AB2C0);
        pureToggleValue.setIncludeFontPadding(false);
        pureToggleValue.setPadding(0, dp(3), 0, 0);
        texts.addView(pureToggleValue);
        row.addView(texts, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        pureToggleIcon = new ImageView(host);
        pureToggleIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LayoutParams tLp = new LayoutParams(dp(40), dp(24));
        tLp.leftMargin = dp(8);
        row.addView(pureToggleIcon, tLp);

        UiMotion focus = new UiMotion(row, 0, 30, value -> {
            bg.setColor((Integer) COLORS.evaluate(value, 0xFF232C37, 0xFF334A5E));
            bg.setStroke(dp(1), (Integer) COLORS.evaluate(value, 0x14FFFFFF, 0x55FFFFFF));
        });
        row.setOnFocusChangeListener((v, focused) -> {
            focus.to(focused ? 1 : 0);
            if (focused) selectScene("ambient_pure_wallpaper");
        });
        row.setOnClickListener(v -> {
            collapseAllExcept(null);
            boolean next = !host.preferences.getBoolean("ambient_pure_wallpaper", false);
            host.preferences.edit().putBoolean("ambient_pure_wallpaper", next).apply();
            refreshPureToggle();
            host.home.invalidate();
        });
        row.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { v.requestFocus(); return true; }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) { v.performClick(); return true; }
            return true;
        });
        refreshPureToggle();
        LayoutParams lp = new LayoutParams(-1, -2);
        lp.bottomMargin = dp(6);
        addView(row, lp);
    }

    private void refreshPureToggle() {
        boolean on = host.preferences.getBoolean("ambient_pure_wallpaper", false);
        if (pureToggleValue != null) {
            pureToggleValue.setText(on ? "已开启 · 屏保隐藏时钟且不压暗" : "已关闭 · 屏保显示时钟");
        }
        if (pureToggleIcon != null) {
            ToggleDrawable td = new ToggleDrawable(on);
            td.setBounds(0, 0, dp(40), dp(24));
            pureToggleIcon.setImageDrawable(td);
        }
    }

    private void selectScene(String key) {
        scene = key;
        if (page != null) page.setPreviewScene(key);
        if (key.startsWith("recent_") && !recentPreviewLoaded) {
            recentPreviewLoaded = true;
            host.recents.load();
        }
    }

    private void action(String label, Runnable action, String key) {
        TextView row = new TextView(host);
        row.setText(label);
        row.setTextColor(Color.WHITE);
        row.setTextSize(13.5f);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        GradientDrawable surface = TvDialog.background(0xFF232C37, dp(12));
        surface.setStroke(dp(1), 0x14FFFFFF);
        row.setBackground(surface);
        UiMotion focus = new UiMotion(row, 0, 30, value -> {
            surface.setColor((Integer) COLORS.evaluate(value, 0xFF232C37, 0xFF334A5E));
            surface.setStroke(dp(1), (Integer) COLORS.evaluate(value, 0x14FFFFFF, 0x55FFFFFF));
        });
        row.setOnFocusChangeListener((v, focused) -> {
            focus.to(focused ? 1 : 0);
            if (focused) selectScene(key);
        });
        row.setOnClickListener(v -> {
            collapseAllExcept(null);
            action.run();
        });
        LayoutParams lp = new LayoutParams(-1, -2);
        lp.bottomMargin = dp(6);
        addView(row, lp);
    }

    private void confirmResetDefaults() {
        collapseAllExcept(null);
        host.showDialog(new TvDialog.Builder(host)
                .setPageId("personalization_reset")
                .setTitle("恢复默认值？")
                .setPreview(scene)
                .setMessage("将恢复所有桌面卡片、时钟与屏保、壁纸压暗及最近任务参数为默认值，不影响已导入的壁纸和桌面应用顺序。")
                .setPositiveButton("恢复默认", (d, w) -> applyDefaults())
                .setNegativeButton("取消", null)
                .create());
    }

    private void applyDefaults() {
        SharedPreferences.Editor editor = host.preferences.edit();
        editor.putInt("clock_style", 0);
        editor.putBoolean("ambient_pure_wallpaper", false);
        for (Map.Entry<String, Integer> entry : defaults.entrySet()) {
            int def = "recent_duration".equals(entry.getKey()) ? 260 : entry.getValue();
            editor.putInt(entry.getKey(), def);
        }
        editor.apply();
        clockStyle.setValue(0);
        clockStyle.closeMenu(false);
        refreshPureToggle();
        for (Map.Entry<String, ParameterCard> entry : controls.entrySet()) {
            int def = "recent_duration".equals(entry.getKey()) ? 260 : defaults.get(entry.getKey());
            entry.getValue().setValue(def, false);
        }
        updateClockControls();
        host.home.refreshAppearance();
        host.home.invalidate();
        host.screensaver.resume();
        host.toast("已恢复默认值");
    }

    void show(String group) {
        if (host.phone != null) host.phone.clearPreview();
        if (host.recents.visible) host.recents.close();
        if (host.appearanceSession != null) host.appearanceSession.finish(false);
        page = new TvDialog.Builder(host)
                .setPageId("personalization")
                .setTitle("Magic Desktop")
                .setPreview(scene)
                .setView(this)
                .setBackHandler(this::handleBack)
                .setInlineButton("恢复默认值", (d, w) -> confirmResetDefaults())
                .create();
        page.onReturn(() -> {
            refreshPureToggle();
            updateClockControls();
        });
        page.onLeave(() -> {
            if (recentPreviewLoaded && !host.recents.visible) host.recents.cancelLoads();
        });
        host.showDialog(page);
        post(() -> {
            if (controls.containsKey(group)) {
                controls.get(group).focusHeader();
                return;
            }
            if ("clock".equals(group)) {
                clockStyle.focusHeading();
                return;
            }
            View heading = groups.get(group);
            int at = heading == null ? 0 : indexOfChild(heading) + 1;
            if (at < getChildCount()) {
                View target = getChildAt(at);
                if (target instanceof ParameterCard) ((ParameterCard) target).focusHeader();
                else target.requestFocus();
            }
        });
    }
}
