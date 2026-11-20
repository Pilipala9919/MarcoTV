// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Collapsible Magic Desktop parameter card: displays title + live value at rest,
 * lazily creates and expands its embedded ContinuousSlider only when clicked,
 * and auto-collapses when focus leaves or Back/OK is pressed.
 */
final class ParameterCard extends LinearLayout {
    interface Listener {
        void changed(int value);
    }

    private static final ArgbEvaluator COLORS = new ArgbEvaluator();
    private final String title, unit, hint;
    private final int min, max;
    private final Listener listener;
    private final Runnable onFocused;
    private final Runnable onBeforeExpand;

    private final GradientDrawable cardBg;
    private final LinearLayout header;
    private final TextView titleView;
    private final TextView valueView;
    private final TextView chevronView;
    private final LinearLayout drawer;
    private ContinuousSlider slider;
    private int value;
    private boolean expanded;
    private float focusProgress;
    private final UiMotion focusMotion;

    ParameterCard(Context context, String title, String unit, int min, int max, int initial,
                  String hint, Listener listener, Runnable onFocused, Runnable onBeforeExpand) {
        super(context);
        this.title = title;
        this.unit = unit;
        this.min = min;
        this.max = max;
        this.value = Math.max(min, Math.min(max, initial));
        this.hint = hint;
        this.listener = listener;
        this.onFocused = onFocused;
        this.onBeforeExpand = onBeforeExpand;

        setOrientation(VERTICAL);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        cardBg = TvDialog.background(0xFF232C37, dp(12));
        cardBg.setStroke(dp(1), 0x14FFFFFF);
        setBackground(cardBg);

        header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(46));
        header.setPadding(dp(12), dp(9), dp(12), dp(9));
        header.setFocusable(true);
        header.setFocusableInTouchMode(true);
        header.setClickable(true);

        titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(0xFFF0F4F8);
        titleView.setTextSize(13.5f);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setIncludeFontPadding(false);
        header.addView(titleView, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        valueView = new TextView(context);
        valueView.setTextColor(0xFFA9DCE2);
        valueView.setTextSize(13f);
        valueView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        valueView.setSingleLine(true);
        valueView.setIncludeFontPadding(false);
        LayoutParams valLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        valLp.leftMargin = dp(8);
        header.addView(valueView, valLp);

        chevronView = new TextView(context);
        chevronView.setText("▾");
        chevronView.setTextColor(0xFF8CA4B8);
        chevronView.setTextSize(11f);
        chevronView.setIncludeFontPadding(false);
        LayoutParams chevLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        chevLp.leftMargin = dp(6);
        header.addView(chevronView, chevLp);

        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        drawer = new LinearLayout(context);
        drawer.setOrientation(VERTICAL);
        drawer.setVisibility(GONE);
        drawer.setPadding(dp(2), 0, dp(2), dp(6));
        addView(drawer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        focusMotion = new UiMotion(this, 0, 30, progress -> {
            focusProgress = progress;
            applySurfaceStyle();
        });

        header.setOnFocusChangeListener((v, hasFocus) -> {
            updateFocusVisual();
            if (hasFocus) {
                if (onFocused != null) onFocused.run();
            } else {
                scheduleAutoCollapseIfUnfocused();
            }
        });
        header.setOnClickListener(v -> toggle());
        header.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                v.requestFocus();
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                v.performClick();
                return true;
            }
            return true;
        });

        refreshLabel();
        applySurfaceStyle();
    }

    private int dp(float n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    int getValue() {
        return value;
    }

    void setValue(int next) {
        setValue(next, true);
    }

    void setValue(int next, boolean notify) {
        int clamped = Math.max(min, Math.min(max, next));
        boolean changed = clamped != value;
        value = clamped;
        refreshLabel();
        if (slider != null) slider.setValue(clamped);
        if (changed && notify && listener != null) listener.changed(value);
    }

    void focusHeader() {
        header.requestFocus();
    }

    boolean isExpanded() {
        return expanded;
    }

    void toggle() {
        if (expanded) collapse(true);
        else expand();
    }

    void expand() {
        if (expanded) {
            if (slider != null) slider.requestFocus();
            return;
        }
        if (onBeforeExpand != null) onBeforeExpand.run();
        ensureSlider();
        expanded = true;
        drawer.setVisibility(VISIBLE);
        chevronView.setText("▴");
        applySurfaceStyle();
        slider.requestFocus();
        post(() -> requestRectangleOnScreen(new Rect(0, 0, getWidth(), getHeight()), false));
    }

    void collapse(boolean refocusHeader) {
        if (!expanded) return;
        expanded = false;
        drawer.setVisibility(GONE);
        chevronView.setText("▾");
        if (refocusHeader) header.requestFocus();
        updateFocusVisual();
    }

    private void ensureSlider() {
        if (slider != null) return;
        slider = new ContinuousSlider(getContext(), title, unit, min, max, value, next -> {
            value = next;
            refreshLabel();
            if (listener != null) listener.changed(next);
        });
        slider.embedded(() -> collapse(true));
        slider.setOnFocusChangeListener((v, hasFocus) -> {
            updateFocusVisual();
            if (hasFocus) {
                if (onFocused != null) onFocused.run();
            } else {
                scheduleAutoCollapseIfUnfocused();
            }
        });
        drawer.addView(slider, new LayoutParams(LayoutParams.MATCH_PARENT, dp(44)));
        if (hint != null && !hint.isEmpty()) {
            TextView tip = new TextView(getContext());
            tip.setText(hint);
            tip.setTextColor(0xFF8EA7B8);
            tip.setTextSize(10.5f);
            tip.setPadding(dp(10), 0, dp(10), dp(2));
            drawer.addView(tip, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }
    }

    private void scheduleAutoCollapseIfUnfocused() {
        if (!expanded) return;
        post(() -> {
            if (expanded && !hasFocus()) collapse(false);
        });
    }

    private void updateFocusVisual() {
        boolean active = header.hasFocus() || (slider != null && slider.hasFocus());
        focusMotion.to(active ? 1f : 0f);
        applySurfaceStyle();
    }

    private void applySurfaceStyle() {
        int restBg = expanded ? 0xFF1F2B38 : 0xFF232C37;
        int focusBg = expanded ? 0xFF2A3E50 : 0xFF334A5E;
        int restStroke = expanded ? 0x66A9DCE2 : 0x14FFFFFF;
        int focusStroke = expanded ? 0xFFA9DCE2 : 0x55FFFFFF;
        cardBg.setColor((Integer) COLORS.evaluate(focusProgress, restBg, focusBg));
        cardBg.setStroke(dp(1), (Integer) COLORS.evaluate(focusProgress, restStroke, focusStroke));
    }

    private String formattedValue() {
        return title.equals("进入屏保等待") && value == 0 ? "不自动进入" : value + unit;
    }

    private void refreshLabel() {
        String text = formattedValue();
        valueView.setText(text);
        header.setContentDescription(title + "，" + text + (expanded ? "，已展开滑动条" : "，按确定展开调整"));
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (expanded && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) collapse(true);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
