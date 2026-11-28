// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Full-screen Apple TV-style two-column settings hub: 5 categories on left, grouped settings on right. */
final class SettingsScreen extends LinearLayout {
    private static final ArgbEvaluator COLORS = new ArgbEvaluator();
    private static final int CANVAS_BG = 0xFF0E121A;
    private static final int GROUP_BG = 0xFF161B24;
    private static final int GROUP_STROKE = 0x1AFFFFFF;
    private static final int FOCUS_BG = 0xFFF4F6FA;
    private static final int FOCUS_INK = 0xFF101318;
    private static final int FOCUS_SUB = 0xFF3E4857;
    private static final int REST_TEXT = 0xFFEEF2F7;
    private static final int REST_SUB = 0xFF8A96A8;
    private static final int SIDEBAR_SELECTED_BG = 0xFF212835;
    private static final int SIDEBAR_SELECTED_STROKE = 0x3A667892;
    private static final int SIDEBAR_ACCENT = 0xFF9E7DF3;

    private final MainActivity host;
    private TvDialog dialog;
    private int activeCategory = SettingsTaxonomy.GENERAL;

    private final CategoryTab[] tabs = new CategoryTab[SettingsTaxonomy.CATEGORIES.length];
    private final LinearLayout[] pages = new LinearLayout[SettingsTaxonomy.CATEGORIES.length];
    private final List<RowHolder>[] categoryRows;
    private final Map<String, RowHolder> rowsByKey = new HashMap<>();

    private MotionScrollView rightScroll;
    private FrameLayout pagesHost;
    private TextView rightTitle;
    private TextView statusBadge;
    private TextView infoTitle;
    private TextView infoBody;
    private TextView remoteHint;

    private static final class CategoryTab {
        final int index;
        final LinearLayout view;
        final View accentBar;
        final TextView title;
        final SettingsIconDrawable.Chevron chevron;
        final ImageView chevronView;
        final GradientDrawable background;
        final UiMotion focusMotion;
        float focusProgress;
        CategoryTab(int index, LinearLayout view, View accentBar, TextView title,
                    SettingsIconDrawable.Chevron chevron, ImageView chevronView,
                    GradientDrawable background, UiMotion focusMotion) {
            this.index = index;
            this.view = view;
            this.accentBar = accentBar;
            this.title = title;
            this.chevron = chevron;
            this.chevronView = chevronView;
            this.background = background;
            this.focusMotion = focusMotion;
        }
    }

    private static final class RowHolder {
        final SettingsTaxonomy.Item item;
        final LinearLayout view;
        final TextView title;
        final TextView value;
        final ImageView toggle;
        final SettingsIconDrawable.Chevron chevron;
        RowHolder(SettingsTaxonomy.Item item, LinearLayout view, TextView title, TextView value,
                  ImageView toggle, SettingsIconDrawable.Chevron chevron) {
            this.item = item;
            this.view = view;
            this.title = title;
            this.value = value;
            this.toggle = toggle;
            this.chevron = chevron;
        }
    }

    @SuppressWarnings("unchecked")
    SettingsScreen(MainActivity host) {
        super(host);
        this.host = host;
        this.categoryRows = new List[SettingsTaxonomy.CATEGORIES.length];
        for (int i = 0; i < categoryRows.length; i++) categoryRows[i] = new ArrayList<>();

        setOrientation(HORIZONTAL);
        setClipChildren(true);
        setClipToPadding(true);
        setPadding(dp(38), dp(28), dp(38), dp(24));
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);

        addView(buildSidebar(), sidebarLayoutParams());
        addView(buildRightPane(), new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        for (int i = 0; i < SettingsTaxonomy.CATEGORIES.length; i++) {
            pages[i] = buildCategoryPage(i);
            pages[i].setVisibility(i == activeCategory ? VISIBLE : GONE);
            pagesHost.addView(pages[i], new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        wireSidebarNavigation();
        selectCategory(activeCategory, false);
        refresh();
    }

    private final Runnable bridgeStateListener = this::refresh;

    void bindDialog(TvDialog owner) {
        this.dialog = owner;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        TaskBridgeClient.addStateListener(bridgeStateListener);
    }

    @Override protected void onDetachedFromWindow() {
        TaskBridgeClient.removeStateListener(bridgeStateListener);
        super.onDetachedFromWindow();
    }

    private LayoutParams sidebarLayoutParams() {
        LayoutParams lp = new LayoutParams(dp(294), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.rightMargin = dp(26);
        return lp;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private View buildSidebar() {
        LinearLayout sidebar = new LinearLayout(host);
        sidebar.setOrientation(VERTICAL);
        sidebar.setPadding(dp(6), 0, dp(6), 0);
        sidebar.setClipChildren(false);
        sidebar.setClipToPadding(false);

        // Apple TV style Settings title block
        LinearLayout header = new LinearLayout(host);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), dp(2), dp(4), dp(18));

        ImageView emblem = new ImageView(host);
        SettingsIconDrawable headerIcon = new SettingsIconDrawable(SettingsIconDrawable.HEADER);
        emblem.setImageDrawable(headerIcon);
        header.addView(emblem, new LayoutParams(dp(38), dp(38)));

        LinearLayout titleBox = new LinearLayout(host);
        titleBox.setOrientation(VERTICAL);
        titleBox.setPadding(dp(12), 0, 0, 0);

        TextView heading = label("设置", 24, Color.WHITE, true);
        titleBox.addView(heading);

        TextView version = label("Marco TV  ·  " + ProjectInfo.VERSION, 11.5f, 0xFF7D899C, false);
        version.setPadding(0, dp(3), 0, 0);
        titleBox.addView(version);

        header.addView(titleBox, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sidebar.addView(header);

        // 5 reorganized top-level categories
        LinearLayout rail = new LinearLayout(host);
        rail.setOrientation(VERTICAL);
        rail.setClipChildren(false);
        rail.setClipToPadding(false);
        for (int i = 0; i < SettingsTaxonomy.CATEGORIES.length; i++) {
            tabs[i] = createCategoryTab(i, SettingsTaxonomy.CATEGORIES[i]);
            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(5);
            rail.addView(tabs[i].view, lp);
        }
        sidebar.addView(rail);

        // Flexible spacer pushes contextual info card neatly to bottom-left
        View spacer = new View(host);
        sidebar.addView(spacer, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Contextual Apple TV info card
        LinearLayout infoCard = new LinearLayout(host);
        infoCard.setOrientation(VERTICAL);
        infoCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        infoCard.setMinimumHeight(dp(92));
        GradientDrawable infoBg = TvDialog.background(0xFF141923, dp(14));
        infoBg.setStroke(dp(1), 0x1AFFFFFF);
        infoCard.setBackground(infoBg);

        infoTitle = label("", 12.5f, 0xFFE3EAF4, true);
        infoTitle.setSingleLine(true);
        infoTitle.setEllipsize(TextUtils.TruncateAt.END);
        infoCard.addView(infoTitle);

        infoBody = label("", 11.5f, 0xFF8C98AA, false);
        infoBody.setLineSpacing(dp(3), 1f);
        infoBody.setMaxLines(4);
        infoBody.setEllipsize(TextUtils.TruncateAt.END);
        infoBody.setPadding(0, dp(5), 0, 0);
        infoCard.addView(infoBody);

        LayoutParams cardLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(10);
        sidebar.addView(infoCard, cardLp);

        remoteHint = label("上下切分类  ·  右键进入  ·  返回退出", 10.5f, 0xFF697588, false);
        remoteHint.setSingleLine(true);
        remoteHint.setEllipsize(TextUtils.TruncateAt.END);
        remoteHint.setPadding(dp(6), dp(9), dp(4), 0);
        sidebar.addView(remoteHint);

        return sidebar;
    }

    private CategoryTab createCategoryTab(final int index, final SettingsTaxonomy.Category category) {
        final LinearLayout row = new LinearLayout(host);
        row.setId(View.generateViewId());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(44));
        row.setPadding(dp(8), dp(8), dp(12), dp(8));
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setClickable(true);
        row.setContentDescription(category.title + "，" + category.subtitle);

        final GradientDrawable bg = TvDialog.background(Color.TRANSPARENT, dp(13));
        row.setBackground(bg);

        final View accent = new View(host);
        accent.setBackground(TvDialog.background(SIDEBAR_ACCENT, dp(2)));
        accent.setVisibility(INVISIBLE);
        LayoutParams accentLp = new LayoutParams(dp(3), dp(18));
        accentLp.rightMargin = dp(7);
        row.addView(accent, accentLp);

        ImageView icon = new ImageView(host);
        icon.setImageDrawable(new SettingsIconDrawable(index));
        row.addView(icon, new LayoutParams(dp(26), dp(26)));

        final TextView title = label(category.title, 15, 0xFFB6C0D0, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LayoutParams titleLp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.leftMargin = dp(11);
        row.addView(title, titleLp);

        final SettingsIconDrawable.Chevron chevron = new SettingsIconDrawable.Chevron();
        final ImageView chevronView = new ImageView(host);
        chevronView.setImageDrawable(chevron);
        chevronView.setVisibility(INVISIBLE);
        row.addView(chevronView, new LayoutParams(dp(14), dp(14)));

        final CategoryTab[] ref = {null};
        UiMotion motion = new UiMotion(row, 0, 32, progress -> {
            if (ref[0] == null) return;
            ref[0].focusProgress = progress;
            applyTabStyle(ref[0]);
        });
        CategoryTab tab = new CategoryTab(index, row, accent, title, chevron, chevronView, bg, motion);
        ref[0] = tab;

        row.setOnFocusChangeListener((v, hasFocus) -> {
            tab.focusMotion.to(hasFocus ? 1 : 0);
            if (hasFocus) {
                selectCategory(index, true);
                updateInfo(category.title, category.summary, false);
            } else {
                applyTabStyle(tab);
            }
        });
        row.setOnClickListener(v -> {
            selectCategory(index, false);
            focusRightFirst();
        });
        attachTouchClick(row);
        return tab;
    }

    private void applyTabStyle(CategoryTab tab) {
        boolean selected = tab.index == activeCategory;
        float p = tab.focusProgress;
        int restBg = selected ? SIDEBAR_SELECTED_BG : 0x00212835;
        int restStroke = selected ? SIDEBAR_SELECTED_STROKE : 0x00FFFFFF;
        int restText = selected ? Color.WHITE : 0xFFB6C0D0;
        tab.background.setColor((Integer) COLORS.evaluate(p, restBg, FOCUS_BG));
        tab.background.setStroke(dp(1), (Integer) COLORS.evaluate(p, restStroke, 0xFFFFFFFF));
        tab.title.setTextColor((Integer) COLORS.evaluate(p, restText, FOCUS_INK));
        tab.accentBar.setVisibility(selected && p < 0.25f ? VISIBLE : INVISIBLE);
        tab.chevronView.setVisibility(selected || p > 0.1f ? VISIBLE : INVISIBLE);
        tab.chevron.setTintColor((Integer) COLORS.evaluate(p, 0xFF7B889C, FOCUS_INK));
        float scale = 1f + 0.02f * p;
        tab.view.setScaleX(scale);
        tab.view.setScaleY(scale);
    }

    private View buildRightPane() {
        LinearLayout right = new LinearLayout(host);
        right.setOrientation(VERTICAL);
        right.setClipChildren(true);
        right.setClipToPadding(true);

        LinearLayout header = new LinearLayout(host);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(4), dp(12), dp(12));

        rightTitle = label("", 21, Color.WHITE, true);
        rightTitle.setSingleLine(true);
        header.addView(rightTitle, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        statusBadge = label("", 11.5f, 0xFFB9C6D8, true);
        statusBadge.setSingleLine(true);
        statusBadge.setPadding(dp(11), dp(4), dp(11), dp(4));
        header.addView(statusBadge, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        right.addView(header);

        rightScroll = new MotionScrollView(host);
        rightScroll.setFadeColor(CANVAS_BG);
        rightScroll.setFillViewport(false);
        rightScroll.setClipChildren(true);
        rightScroll.setClipToPadding(true);
        rightScroll.setVerticalScrollBarEnabled(false);

        pagesHost = new FrameLayout(host);
        pagesHost.setClipChildren(true);
        pagesHost.setClipToPadding(true);
        rightScroll.addView(pagesHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        right.addView(rightScroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return right;
    }

    private LinearLayout buildCategoryPage(final int catIndex) {
        SettingsTaxonomy.Category category = SettingsTaxonomy.CATEGORIES[catIndex];
        LinearLayout page = new LinearLayout(host);
        page.setOrientation(VERTICAL);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        page.setPadding(dp(10), dp(6), dp(10), dp(24));

        List<RowHolder> list = categoryRows[catIndex];
        for (int s = 0; s < category.sections.length; s++) {
            SettingsTaxonomy.Section section = category.sections[s];
            TextView secLabel = label(section.title, 12, 0xFF818EA2, true);
            secLabel.setLetterSpacing(0.03f);
            secLabel.setPadding(dp(14), dp(s == 0 ? 2 : 16), dp(10), dp(7));
            page.addView(secLabel);

            LinearLayout group = new LinearLayout(host);
            group.setOrientation(VERTICAL);
            group.setClipChildren(false);
            group.setClipToPadding(false);
            group.setPadding(dp(4), dp(4), dp(4), dp(4));
            GradientDrawable groupBg = TvDialog.background(GROUP_BG, dp(16));
            groupBg.setStroke(dp(1), GROUP_STROKE);
            group.setBackground(groupBg);

            for (int r = 0; r < section.items.length; r++) {
                if (r > 0) {
                    View divider = new View(host);
                    divider.setBackgroundColor(0x14FFFFFF);
                    LayoutParams divLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.75f)));
                    divLp.leftMargin = dp(14);
                    divLp.rightMargin = dp(14);
                    group.addView(divider, divLp);
                }
                RowHolder holder = createRow(catIndex, section.items[r]);
                list.add(holder);
                rowsByKey.put(section.items[r].key, holder);
                group.addView(holder.view, new LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            page.addView(group, new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // Chain vertical focus cleanly inside the category and lock DPAD_LEFT back to its sidebar tab
        int sidebarId = tabs[catIndex].view.getId();
        for (int i = 0; i < list.size(); i++) {
            View rowView = list.get(i).view;
            rowView.setNextFocusLeftId(sidebarId);
            rowView.setNextFocusRightId(rowView.getId());
            if (i > 0) rowView.setNextFocusUpId(list.get(i - 1).view.getId());
            else rowView.setNextFocusUpId(rowView.getId());
            if (i + 1 < list.size()) rowView.setNextFocusDownId(list.get(i + 1).view.getId());
            else rowView.setNextFocusDownId(rowView.getId());
        }
        return page;
    }

    private RowHolder createRow(final int catIndex, final SettingsTaxonomy.Item item) {
        final LinearLayout row = new LinearLayout(host);
        row.setId(View.generateViewId());
        row.setTag(item.key);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(45));
        row.setPadding(dp(14), dp(9), dp(14), dp(9));
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setClickable(true);

        final GradientDrawable fill = TvDialog.background(Color.TRANSPARENT, dp(12));
        row.setBackground(fill);

        final TextView titleView = label(item.title, 15, REST_TEXT, true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(titleView, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView valueView = label("", 13.5f, REST_SUB, false);
        valueView.setSingleLine(true);
        valueView.setEllipsize(TextUtils.TruncateAt.END);
        valueView.setMaxWidth(dp(220));
        LayoutParams valLp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valLp.leftMargin = dp(12);
        row.addView(valueView, valLp);

        ImageView toggleView = null;
        SettingsIconDrawable.Chevron chevron = null;
        if (item.kind == SettingsTaxonomy.Item.TOGGLE) {
            toggleView = new ImageView(host);
            toggleView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LayoutParams tLp = new LayoutParams(dp(44), dp(26));
            tLp.leftMargin = dp(10);
            row.addView(toggleView, tLp);
        } else if (item.kind == SettingsTaxonomy.Item.DRILL) {
            chevron = new SettingsIconDrawable.Chevron();
            ImageView chevView = new ImageView(host);
            chevView.setImageDrawable(chevron);
            LayoutParams cLp = new LayoutParams(dp(15), dp(15));
            cLp.leftMargin = dp(6);
            row.addView(chevView, cLp);
        }

        final SettingsIconDrawable.Chevron chevRef = chevron;
        UiMotion focusMotion = new UiMotion(row, 0, 32, progress -> {
            fill.setColor((Integer) COLORS.evaluate(progress, 0x00F4F6FA, FOCUS_BG));
            titleView.setTextColor((Integer) COLORS.evaluate(progress, REST_TEXT, FOCUS_INK));
            valueView.setTextColor((Integer) COLORS.evaluate(progress, REST_SUB, FOCUS_SUB));
            if (chevRef != null) chevRef.setTintColor((Integer) COLORS.evaluate(progress, 0xFF637085, FOCUS_INK));
            float scale = 1f + 0.015f * progress;
            row.setScaleX(scale);
            row.setScaleY(scale);
        });

        row.setOnFocusChangeListener((v, hasFocus) -> {
            focusMotion.to(hasFocus ? 1 : 0);
            if (hasFocus) {
                if (activeCategory != catIndex) selectCategory(catIndex, false);
                updateInfo(item.title, item.hint, true);
                row.requestRectangleOnScreen(new android.graphics.Rect(0, -dp(18), row.getWidth(), row.getHeight() + dp(18)), false);
            }
        });
        row.setOnClickListener(v -> {
            if (dialog != null) dialog.setSavedFocus(row);
            invokeItem(item.key);
        });
        attachTouchClick(row);
        return new RowHolder(item, row, titleView, valueView, toggleView, chevron);
    }

    private void wireSidebarNavigation() {
        for (int i = 0; i < tabs.length; i++) {
            View v = tabs[i].view;
            v.setNextFocusLeftId(v.getId());
            if (i > 0) v.setNextFocusUpId(tabs[i - 1].view.getId());
            else v.setNextFocusUpId(v.getId());
            if (i + 1 < tabs.length) v.setNextFocusDownId(tabs[i + 1].view.getId());
            else v.setNextFocusDownId(v.getId());
            if (!categoryRows[i].isEmpty()) {
                v.setNextFocusRightId(categoryRows[i].get(0).view.getId());
            }
        }
    }

    private void selectCategory(int index, boolean resetScroll) {
        if (index < 0 || index >= SettingsTaxonomy.CATEGORIES.length) return;
        boolean changed = activeCategory != index;
        activeCategory = index;
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == activeCategory ? VISIBLE : GONE);
            applyTabStyle(tabs[i]);
        }
        rightTitle.setText(SettingsTaxonomy.CATEGORIES[activeCategory].title);
        if (changed || resetScroll) rightScroll.snapTop();
        refreshCategory(activeCategory);
        updateCategoryBadge();
    }

    private void updateInfo(String title, String body, boolean inRight) {
        infoTitle.setText(title);
        infoBody.setText(body);
        remoteHint.setText(inRight
                ? "左键 / 返回切分类  ·  确定修改  ·  Home 回桌面"
                : "上下切分类  ·  右键进入  ·  返回回桌面");
    }

    private void focusRightFirst() {
        List<RowHolder> list = categoryRows[activeCategory];
        if (!list.isEmpty()) list.get(0).view.requestFocus();
    }

    private boolean isFocusInRightPane() {
        View focused = findFocus();
        if (focused == null || rightScroll == null) return false;
        ViewParent parent = focused.getParent();
        while (parent != null) {
            if (parent == rightScroll) return true;
            parent = parent.getParent();
        }
        return false;
    }

    private boolean isFocusInSidebar() {
        View focused = findFocus();
        if (focused == null) return false;
        for (CategoryTab tab : tabs) if (tab.view == focused) return true;
        return false;
    }

    boolean handleKey(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT && isFocusInSidebar()) {
            focusRightFirst();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT && isFocusInRightPane()) {
            tabs[activeCategory].view.requestFocus();
            return true;
        }
        return false;
    }

    boolean handleBack() {
        if (isFocusInRightPane()) {
            tabs[activeCategory].view.requestFocus();
            return true;
        }
        return false;
    }

    /** Refreshes all live status badges, values, and toggles in-place without destroying views. */
    void refresh() {
        for (int i = 0; i < SettingsTaxonomy.CATEGORIES.length; i++) refreshCategory(i);
        updateCategoryBadge();
        View focused = findFocus();
        if (focused != null && focused.getTag() instanceof String) {
            SettingsTaxonomy.Item item = SettingsTaxonomy.findItem((String) focused.getTag());
            if (item != null) updateInfo(item.title, item.hint, true);
        } else {
            SettingsTaxonomy.Category c = SettingsTaxonomy.CATEGORIES[activeCategory];
            updateInfo(c.title, c.summary, false);
        }
    }

    private void refreshCategory(int catIndex) {
        for (RowHolder holder : categoryRows[catIndex]) refreshRow(holder);
    }

    private void refreshRow(RowHolder holder) {
        String key = holder.item.key;
        if (holder.item.kind == SettingsTaxonomy.Item.TOGGLE) {
            boolean state = readToggle(key);
            holder.value.setText(state ? "已开启" : "已关闭");
            if (holder.toggle != null) {
                ToggleDrawable td = new ToggleDrawable(state);
                td.setBounds(0, 0, dp(44), dp(26));
                holder.toggle.setImageDrawable(td);
            }
            holder.view.setContentDescription(holder.item.title + "，" + (state ? "已开启" : "已关闭"));
        } else {
            String value = readValue(key);
            holder.value.setText(value);
            holder.value.setVisibility(value.isEmpty() ? GONE : VISIBLE);
            holder.view.setContentDescription(value.isEmpty() ? holder.item.title : holder.item.title + "，" + value);
        }
    }

    private void updateCategoryBadge() {
        String text = "";
        int fg = 0xFFB8C5D8;
        int bgColor = 0x227D8CA3;
        int stroke = 0x447D8CA3;

        switch (activeCategory) {
            case SettingsTaxonomy.GENERAL:
                text = host.preferences.getBoolean("ambient_enabled", true) ? "屏保已开启" : "屏保已关闭";
                if (host.preferences.getBoolean("ambient_enabled", true)) {
                    fg = 0xFF7FD4FF; bgColor = 0x200A84FF; stroke = 0x550A84FF;
                }
                break;
            case SettingsTaxonomy.APPS:
                text = sortLabel();
                fg = 0xFFB5B4FF; bgColor = 0x245E5CE6; stroke = 0x555E5CE6;
                break;
            case SettingsTaxonomy.HOME: {
                HomeSetupPolicy.DesktopState ds = SystemIntegration.desktopState(host);
                if (ds.keyFilterStillActiveUnexpectedly) {
                    text = "● 按键过滤未撤销";
                    fg = 0xFFFFB86C; bgColor = 0x22FFB86C; stroke = 0x66FFB86C;
                } else if (SystemIntegration.homeIsOurs(host)) {
                    text = HomeSetup.paused(host) ? "● 系统默认桌面（守护已暂停）" : "● 系统默认桌面";
                    fg = HomeSetup.paused(host) ? 0xFFFFD166 : 0xFF63E69A;
                    bgColor = HomeSetup.paused(host) ? 0x22FFD166 : 0x2234C759;
                    stroke = HomeSetup.paused(host) ? 0x66FFD166 : 0x6634C759;
                } else if (HomeSetup.paused(host)) {
                    text = "● 已暂停接管";
                    fg = 0xFFFFD166; bgColor = 0x22FFD166; stroke = 0x66FFD166;
                } else if (HomeKeyService.connected) {
                    HomeSetupPolicy.GuardMode bm = SystemIntegration.guardMode(host);
                    if (bm == HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY) {
                        text = "● 原厂按键";
                        fg = 0xFF63E69A; bgColor = 0x2234C759; stroke = 0x6634C759;
                    } else if (bm == HomeSetupPolicy.GuardMode.KEY_FILTER) {
                        if (!HomeKeyService.runtimeKeyFilterRequested && !HomeKeyService.runtimeVolumeYieldActive) {
                            text = "● 按键过滤未生效";
                            fg = 0xFFFFD166; bgColor = 0x22FFD166; stroke = 0x66FFD166;
                        } else if (HomeKeyService.runtimeVolumeYieldFailed) {
                            text = "● 快速 Home · 让渡受限";
                            fg = 0xFFFFD166; bgColor = 0x22FFD166; stroke = 0x66FFD166;
                        } else {
                            text = TaskBridgeClient.volumeAssistActive()
                                    ? "● 快速 Home · 任务桥"
                                    : (HomeKeyService.runtimeVolumeYieldActive ? "● 快速 Home · 让渡中" : "● 快速 Home · 无桥");
                            fg = 0xFF63E69A; bgColor = 0x2234C759; stroke = 0x6634C759;
                        }
                    } else {
                        text = "● 窗口回拉（兼容模式）";
                        fg = 0xFF63E69A; bgColor = 0x2234C759; stroke = 0x6634C759;
                    }
                } else if (HomeSetup.serviceEnabled(host)) {
                    text = "● Home 服务未绑定";
                    fg = 0xFFFFD166; bgColor = 0x22FFD166; stroke = 0x66FFD166;
                } else {
                    text = BuildEdition.RESTRICTED ? "● 受限兼容版" : "● 原厂桌面";
                }
                break;
            }
            case SettingsTaxonomy.NETWORK:
                String net = networkShortStatus();
                if (net.startsWith("已连接") || net.equals("有线网络")) {
                    text = "● " + net;
                    fg = 0xFF63E69A; bgColor = 0x2234C759; stroke = 0x6634C759;
                } else {
                    text = "● 未连接网络";
                    fg = 0xFFFFB86C; bgColor = 0x22FFB86C; stroke = 0x66FFB86C;
                }
                break;
            case SettingsTaxonomy.SYSTEM:
                if (host.updates.updates.available != null) {
                    text = "● 发现新版 " + host.updates.updates.available.version;
                    fg = 0xFF63E69A; bgColor = 0x2234C759; stroke = 0x6634C759;
                } else {
                    text = "v" + ProjectInfo.VERSION;
                }
                break;
        }
        statusBadge.setText(text);
        statusBadge.setTextColor(fg);
        GradientDrawable pill = TvDialog.background(bgColor, dp(12));
        pill.setStroke(dp(1), stroke);
        statusBadge.setBackground(pill);
    }

    private boolean readToggle(String key) {
        SharedPreferences sys = SystemIntegration.prefs(host);
        switch (key) {
            case "ambient_enabled": return host.preferences.getBoolean("ambient_enabled", true);
            case "ambient_pure_wallpaper": return host.preferences.getBoolean("ambient_pure_wallpaper", false);
            case "now_playing": return host.playback.enabled();
            case "home_compat": return sys.getBoolean("home_compat", false);
            case "boot_home": return sys.getBoolean("boot_home", false);
            case "recent_wake": return host.preferences.getBoolean("recent_wake", true);
            case "recent_enabled": return host.recents.enabled();
            default: return false;
        }
    }

    private String readValue(String key) {
        SharedPreferences sys = SystemIntegration.prefs(host);
        switch (key) {
            case "magic_desktop":
                return "实时 3D 预览";
            case "wallpaper":
                return wallpaperLabel();
            case "weather": {
                if (!host.preferences.getBoolean("weather_enabled", true)) return "已关闭";
                if (host.weather != null) {
                    WeatherSnapshot snap = host.weather.current();
                    if (snap != null && snap.tempC != null) {
                        return (snap.city.isEmpty() ? WeatherSnapshot.conditionLabel(snap.condition) : snap.city) + " " + snap.tempC + "°";
                    }
                    return host.weather.cityLabel();
                }
                return "已开启";
            }
            case "ambient_sleep":
                return SettingsNavigator.sleepLabel(host.preferences.getInt("ambient_sleep_minutes", IdleController.DEFAULT_SLEEP_MINUTES));
            case "ambient_preview":
                return "立即查看";
            case "add_app":
                return "查找入口";
            case "all_apps":
                return "全部已安装";
            case "app_manager":
                return "筛选与卸载";
            case "tv_sort":
                return sortLabel();
            case "pinned_cards":
                return countLabel(countCards("pinned"), "暂无");
            case "favorite_cards":
                return countLabel(countCards("favorites"), "暂无");
            case "hidden_cards":
                return countLabel(countCards("hidden"), "无隐藏");
            case "playback_source": {
                int avail = host.playback.availableCount();
                return avail > 0 ? avail + " 个会话" : "自动选择";
            }
            case "playback_permission":
                return host.playback.authorized() ? "已允许" : "未允许";
            case "playback_restore":
                return "点击恢复";
            case "default_home": {
                HomeSetupPolicy.DesktopState ds = SystemIntegration.desktopState(host);
                if (ds.keyFilterStillActiveUnexpectedly) return "按键过滤未撤销";
                if (SystemIntegration.homeIsOurs(host)) return HomeSetup.paused(host) ? "系统默认桌面 · 守护已暂停" : "系统默认桌面";
                if (HomeSetup.paused(host)) return "已暂停";
                if (HomeKeyService.connected) {
                    HomeSetupPolicy.GuardMode dm = SystemIntegration.guardMode(host);
                    if (dm == HomeSetupPolicy.GuardMode.KEY_FILTER) {
                        if (!HomeKeyService.runtimeKeyFilterRequested && !HomeKeyService.runtimeVolumeYieldActive) return "按键过滤未生效";
                        if (HomeKeyService.runtimeVolumeYieldFailed) return "快速 Home · 让渡受限";
                        return TaskBridgeClient.volumeAssistActive()
                                ? "快速 Home · 任务桥"
                                : (HomeKeyService.runtimeVolumeYieldActive ? "快速 Home · 让渡中" : "快速 Home · 无桥");
                    }
                    if (dm == HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY) return "原厂按键";
                    return "窗口回拉";
                }
                if (HomeSetup.serviceEnabled(host)) return "已允许未连接";
                return BuildEdition.RESTRICTED ? "受限兼容 · 未启用" : "未启用";
            }
            case "home_gestures": {
                HomeSetupPolicy.DesktopState ds = SystemIntegration.desktopState(host);
                if (ds.keyFilterStillActiveUnexpectedly) return "按键过滤未撤销";
                if (SystemIntegration.homeIsOurs(host)) return HomeSetup.paused(host) ? "系统默认桌面 · 守护已暂停" : "系统默认桌面";
                HomeSetupPolicy.GuardMode mode = SystemIntegration.guardMode(host);
                if (mode == HomeSetupPolicy.GuardMode.NATIVE_KEYS_ONLY) return "原厂按键";
                if (mode == HomeSetupPolicy.GuardMode.WINDOW_PULLBACK) return "窗口回拉";
                if (HomeKeyService.connected && !HomeKeyService.runtimeKeyFilterRequested && !HomeKeyService.runtimeVolumeYieldActive) {
                    return "按键过滤未生效";
                }
                if (HomeKeyService.runtimeVolumeYieldFailed) return "快速 Home · 让渡受限";
                return TaskBridgeClient.volumeAssistActive()
                        ? "快速 Home · 任务桥"
                        : (HomeKeyService.runtimeVolumeYieldActive ? "快速 Home · 让渡中" : "快速 Home · 无桥");
            }
            case "restore_home":
                return "切回原厂";
            case "recent_open":
                return TaskBridgeClient.connected() ? "服务已连接" : "未连接 · 点击恢复";
            case "recent_style":
                return "Magic Desktop";
            case "wifi":
                return networkShortStatus();
            case "cast": {
                int m = host.cast.mode();
                return m == CastManager.MODE_OFF ? "DLNA 已关闭" : m == CastManager.MODE_ALWAYS ? "始终可发现" : "打开页可发现";
            }
            case "phone":
                return host.phone.active() ? "服务运行中" : "扫码连接";
            case "sound":
                return volumeLabel();
            case "updates":
                return host.updates.updates.available != null ? "新版 " + host.updates.updates.available.version : "v" + ProjectInfo.VERSION;
            case "health_status":
                return SystemIntegration.authorized(host) ? "已授权 · 只读检查" : "只读检查";
            case "backup":
                return "本地快照与导出";
            case "diagnostics":
                return Diagnostics.enabled(host) ? "自动记录已开" : "自动记录已关";
            case "adb_restore":
                return SystemIntegration.adbRestoreLabel(host);
            case "recover_now":
                return "仅执行已开项";
            case "debug_help":
                return "查看说明";
            case "contact_author":
                return ProjectInfo.SOCIAL_ACCOUNT + " · 交流群";
            case "guide":
                return "5 步上手";
            case "device_info":
                return Build.MODEL + " · Android " + Build.VERSION.RELEASE;
            case "about":
                return "v" + ProjectInfo.VERSION;
            default:
                return "";
        }
    }

    private void invokeItem(String key) {
        SharedPreferences sys = SystemIntegration.prefs(host);
        switch (key) {
            case "magic_desktop":
                host.navigator.personalization("cards");
                break;
            case "wallpaper":
                host.navigator.wallpaperSettings();
                break;
            case "weather":
                new WeatherPanel(host).show();
                break;
            case "ambient_enabled": {
                boolean next = !host.preferences.getBoolean("ambient_enabled", true);
                host.preferences.edit().putBoolean("ambient_enabled", next).apply();
                AmbientIntegration.configure(host);
                host.screensaver.resume();
                refreshCategory(SettingsTaxonomy.GENERAL);
                updateCategoryBadge();
                break;
            }
            case "ambient_pure_wallpaper": {
                boolean next = !host.preferences.getBoolean("ambient_pure_wallpaper", false);
                host.preferences.edit().putBoolean("ambient_pure_wallpaper", next).apply();
                host.home.invalidate();
                refreshCategory(SettingsTaxonomy.GENERAL);
                break;
            }
            case "ambient_sleep": {
                int current = host.preferences.getInt("ambient_sleep_minutes", IdleController.DEFAULT_SLEEP_MINUTES);
                int next = SettingsNavigator.SLEEP_CHOICES[0];
                for (int i = 0; i < SettingsNavigator.SLEEP_CHOICES.length; i++) {
                    if (SettingsNavigator.SLEEP_CHOICES[i] == current) {
                        next = SettingsNavigator.SLEEP_CHOICES[(i + 1) % SettingsNavigator.SLEEP_CHOICES.length];
                        break;
                    }
                }
                host.preferences.edit().putInt("ambient_sleep_minutes", next).apply();
                refreshCategory(SettingsTaxonomy.GENERAL);
                break;
            }
            case "ambient_preview":
                host.navigator.previewAmbient();
                break;
            case "add_app":
                host.library.show();
                break;
            case "all_apps":
                host.drawer.show();
                break;
            case "app_manager":
                host.navigator.appManager();
                break;
            case "tv_sort":
                host.navigator.sorting();
                break;
            case "pinned_cards":
                host.navigator.managedCards("pinned", "置顶卡片");
                break;
            case "favorite_cards":
                host.navigator.managedCards("favorites", "收藏卡片");
                break;
            case "hidden_cards":
                host.navigator.hiddenCards();
                break;
            case "now_playing":
                host.preferences.edit().putBoolean("now_playing", !host.playback.enabled()).apply();
                host.playback.refresh();
                refreshCategory(SettingsTaxonomy.APPS);
                break;
            case "playback_source":
                host.playback.sources();
                break;
            case "playback_permission":
                try {
                    host.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                } catch (RuntimeException error) {
                    host.toast("电视未提供此授权页面，请按安装说明使用 ADB 授权");
                }
                break;
            case "playback_restore":
                host.playback.restoreHidden();
                host.toast("已恢复暂时隐藏的播放卡片");
                refreshCategory(SettingsTaxonomy.APPS);
                break;
            case "default_home":
                host.defaultHome.show();
                break;
            case "home_compat":
                if (sys.getBoolean("home_compat", false)) {
                    SystemIntegration.disableHomeCompat(host);
                    refreshCategory(SettingsTaxonomy.HOME);
                    updateCategoryBadge();
                } else {
                    host.defaultHome.compatibility();
                }
                break;
            case "home_gestures":
                SystemIntegration.homeGestures(host, host::showDialog);
                break;
            case "restore_home":
                host.defaultHome.restore();
                break;
            case "boot_home":
                sys.edit().putBoolean("boot_home", !sys.getBoolean("boot_home", false)).apply();
                refreshCategory(SettingsTaxonomy.HOME);
                break;
            case "recent_wake":
                host.preferences.edit().putBoolean("recent_wake", !host.preferences.getBoolean("recent_wake", true)).apply();
                TaskBridgeClient.sync(host);
                refreshCategory(SettingsTaxonomy.HOME);
                break;
            case "recent_enabled":
                host.preferences.edit().putBoolean("recent_enabled", !host.recents.enabled()).apply();
                refreshCategory(SettingsTaxonomy.HOME);
                break;
            case "recent_open":
                host.recents.show();
                break;
            case "recent_style":
                host.recents.styleSettings();
                break;
            case "wifi":
                host.network.show();
                break;
            case "cast":
                host.cast.show();
                break;
            case "phone":
                host.phone.show();
                break;
            case "sound":
                host.navigator.soundSettings();
                break;
            case "updates":
                host.updates.show();
                break;
            case "health_status":
                host.health.status();
                break;
            case "backup":
                host.backups.show();
                break;
            case "diagnostics":
                host.health.diagnostics();
                break;
            case "adb_restore":
                SystemIntegration.toggleAdbRestore(host, host::showDialog, () -> {
                    refreshCategory(SettingsTaxonomy.SYSTEM);
                    updateCategoryBadge();
                });
                break;
            case "recover_now":
                SystemIntegration.confirmRecoverNow(host, host::showDialog);
                break;
            case "debug_help":
                SystemIntegration.showDebugHelp(host, host::showDialog);
                break;
            case "contact_author":
                host.navigator.contactAuthor();
                break;
            case "guide":
                host.health.guide(0);
                break;
            case "device_info":
                host.navigator.deviceInfo();
                break;
            case "about":
                host.navigator.about();
                break;
        }
    }

    private String wallpaperLabel() {
        String video = host.preferences.getString("wallpaper_video", "");
        String custom = host.preferences.getString("wallpaper_label", "");
        if (video != null && !video.isEmpty()) {
            return (custom != null && !custom.isEmpty()) ? "动态 · " + custom : "动态壁纸";
        }
        String uri = host.preferences.getString("wallpaper", "");
        if (uri != null && !uri.isEmpty()) {
            return custom != null && !custom.isEmpty() ? custom : "自定义壁纸";
        }
        int idx = Math.max(0, Math.min(WallpaperSource.TITLES.length - 1, host.preferences.getInt("wallpaper_builtin", 0)));
        return WallpaperSource.TITLES[idx];
    }

    private String sortLabel() {
        String mode = AppOrder.mode(host.preferences);
        int idx = Arrays.asList(AppOrder.MODES).indexOf(mode);
        return idx >= 0 && idx < AppOrder.LABELS.length ? AppOrder.LABELS[idx] : AppOrder.LABELS[0];
    }

    private int countCards(String key) {
        int count = 0;
        for (Tile t : host.allTiles) if (host.cards.contains(key, t.id)) count++;
        return count;
    }

    private static String countLabel(int count, String empty) {
        return count <= 0 ? empty : count + " 个";
    }

    private String networkShortStatus() {
        try {
            ConnectivityManager cm = (ConnectivityManager) host.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo active = cm == null ? null : cm.getActiveNetworkInfo();
            if (active == null || !active.isConnected()) return "未连接";
            if (active.getType() == ConnectivityManager.TYPE_ETHERNET) return "有线网络";
            WifiManager wifi = (WifiManager) host.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
            if (info != null && info.getNetworkId() >= 0) {
                String ssid = info.getSSID();
                if (ssid != null && ssid.length() > 1 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                    ssid = ssid.substring(1, ssid.length() - 1);
                }
                if (ssid != null && !ssid.isEmpty() && !ssid.equals("<unknown ssid>")) {
                    return "已连接 · " + ssid;
                }
            }
            return "已连接 Wi-Fi";
        } catch (RuntimeException ignored) {
            return "查看网络";
        }
    }

    private String volumeLabel() {
        try {
            AudioManager audio = (AudioManager) host.getSystemService(Context.AUDIO_SERVICE);
            int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            int cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            return Math.round(cur * 100f / max) + "%";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private TextView label(String text, float sizeSp, int color, boolean medium) {
        TextView t = new TextView(host);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setFontFeatureSettings("kern");
        if (medium) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private static void attachTouchClick(View v) {
        v.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                view.requestFocus();
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                view.performClick();
                return true;
            }
            return true;
        });
    }
}
