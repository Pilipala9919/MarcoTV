// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.animation.ArgbEvaluator;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.io.InterruptedIOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unified Apple TV-style wallpaper gallery combining built-in, mirror-curated, and QR-imported
 * static/live video wallpapers under a single "我的壁纸" thumbnail grid alongside "扫码导入壁纸".
 * Download and apply operations are fully decoupled, with an anchored side bubble popover on Menu key.
 */
final class WallpaperCenterDialog {
    private static final ArgbEvaluator COLORS = new ArgbEvaluator();
    private static final int COLUMNS = 3;
    private static final int CANVAS_BG = 0xFF0E121A;
    private static final int CARD_REST_BG = 0xFF171D28;
    private static final int CARD_FOCUS_BG = 0xFFF4F6FA;
    private static final int CARD_REST_STROKE = 0x1EFFFFFF;

    private final MainActivity host;
    private final OnlineWallpaperStore onlineStore;
    private final ExecutorService catalogNet = Executors.newSingleThreadExecutor();
    private final ExecutorService downloadPool = Executors.newFixedThreadPool(2);
    private final ExecutorService applyExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbs = Executors.newFixedThreadPool(3);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Map<String, Bitmap> thumbCache = new HashMap<>();

    // Per-item background download tracking (accessed on UI thread):
    private final Map<String, Integer> downloadProgress = new HashMap<>();
    private final Map<String, AtomicBoolean> downloadCancels = new HashMap<>();

    private TvDialog dialog;
    private FrameLayout overlayRoot;
    private FrameLayout bubbleLayer;
    private View bubbleAnchorCard;
    private UiMotion bubbleSlideMotion;
    private UiMotion bubbleAlphaMotion;
    private TextView subtitleView;
    private TextView scanImportBtn;
    private MotionScrollView scrollView;
    private LinearLayout gridHost;
    private TextView footerHint;

    private List<OnlineWallpaperStore.Item> onlineItems = Collections.emptyList();
    private final List<CardEntry> entries = new ArrayList<>();
    private final List<CardUi> cards = new ArrayList<>();
    private String focusedUnifiedId;
    private boolean catalogBusy;
    private boolean catalogFailed;

    private static final class CardEntry {
        static final int KIND_IMPORTED = 0;
        static final int KIND_BUILTIN = 1;
        static final int KIND_ONLINE = 2;

        final int kind;
        final String unifiedId;
        final String title;
        final String subtitle;
        final boolean video;
        final boolean ultraHd;
        final int durationSec;
        final WallpaperLibrary.Entry imported;
        final int builtinIndex;
        final OnlineWallpaperStore.Item online;

        CardEntry(WallpaperLibrary.Entry imported) {
            this.kind = KIND_IMPORTED;
            this.unifiedId = imported.unifiedId();
            this.title = imported.title;
            this.video = imported.video;
            this.ultraHd = imported.width >= 3840;
            this.durationSec = imported.durationSec;
            this.subtitle = imported.video
                    ? (imported.width + "×" + imported.height + (imported.durationSec > 0 ? " · " + imported.durationSec + " 秒动态" : " · 动态壁纸"))
                    : (imported.width + "×" + imported.height + " · " + WallpaperCatalogPolicy.formatSize(imported.size));
            this.imported = imported;
            this.builtinIndex = -1;
            this.online = null;
        }

        CardEntry(int builtinIndex) {
            this.kind = KIND_BUILTIN;
            this.unifiedId = "builtin:" + builtinIndex;
            this.title = WallpaperSource.TITLES[builtinIndex];
            this.subtitle = "内置静态壁纸";
            this.video = false;
            this.ultraHd = false;
            this.durationSec = 0;
            this.imported = null;
            this.builtinIndex = builtinIndex;
            this.online = null;
        }

        CardEntry(OnlineWallpaperStore.Item online) {
            this.kind = KIND_ONLINE;
            this.unifiedId = "online:" + online.id;
            this.title = online.title;
            this.video = online.video;
            this.ultraHd = online.width >= 3840;
            this.durationSec = online.durationSec;
            String base = online.video
                    ? (online.durationSec > 0 ? online.durationSec + " 秒动态" : "动态壁纸")
                    : (online.width + "×" + online.height);
            this.subtitle = online.subtitle.isEmpty() ? base : online.subtitle;
            this.imported = null;
            this.builtinIndex = -1;
            this.online = online;
        }
    }

    private static final class CardUi {
        final CardEntry entry;
        final LinearLayout root;
        final ImageView thumb;
        final TextView placeholder;
        final TextView videoBadge;
        final TextView stateBadge;
        final TextView title;
        final TextView sub;
        final GradientDrawable bg;
        float focusProgress;

        CardUi(CardEntry entry, LinearLayout root, ImageView thumb, TextView placeholder,
               TextView videoBadge, TextView stateBadge, TextView title, TextView sub, GradientDrawable bg) {
            this.entry = entry;
            this.root = root;
            this.thumb = thumb;
            this.placeholder = placeholder;
            this.videoBadge = videoBadge;
            this.stateBadge = stateBadge;
            this.title = title;
            this.sub = sub;
            this.bg = bg;
        }
    }

    static void show(MainActivity host) {
        new WallpaperCenterDialog(host).open();
    }

    private WallpaperCenterDialog(MainActivity host) {
        this.host = host;
        this.onlineStore = new OnlineWallpaperStore(host);
    }

    private int dp(float v) {
        return Math.round(v * host.getResources().getDisplayMetrics().density);
    }

    private void open() {
        overlayRoot = new FrameLayout(host);
        overlayRoot.setClipChildren(false);
        overlayRoot.setClipToPadding(false);

        LinearLayout root = new LinearLayout(host);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(36), dp(24), dp(36), dp(18));
        root.setClipChildren(true);
        root.setClipToPadding(true);

        // Top header bar: "我的壁纸" on left + "扫码导入壁纸" primary pill on right
        LinearLayout header = new LinearLayout(host);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(6), 0, dp(6), dp(14));

        LinearLayout titleBox = new LinearLayout(host);
        titleBox.setOrientation(LinearLayout.VERTICAL);

        TextView heading = text("我的壁纸", 23, Color.WHITE, true);
        titleBox.addView(heading);

        subtitleView = text("选择壁纸缩略图即可应用", 12f, 0xFF8C9BB0, false);
        subtitleView.setPadding(0, dp(4), 0, 0);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        titleBox.addView(subtitleView);

        header.addView(titleBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        scanImportBtn = buildScanImportButton();
        header.addView(scanImportBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scrollView = new MotionScrollView(host);
        scrollView.setFadeColor(CANVAS_BG);
        scrollView.setFillViewport(false);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setClipChildren(true);
        scrollView.setClipToPadding(true);

        gridHost = new LinearLayout(host);
        gridHost.setOrientation(LinearLayout.VERTICAL);
        gridHost.setClipChildren(false);
        gridHost.setClipToPadding(false);
        gridHost.setPadding(dp(4), dp(6), dp(4), dp(18));
        scrollView.addView(gridHost, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(scrollView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        footerHint = text("确定应用/下载  ·  菜单键弹出操作气泡  ·  上键扫码导入  ·  返回退出", 11.5f, 0xFF7B899E, false);
        footerHint.setSingleLine(true);
        footerHint.setEllipsize(TextUtils.TruncateAt.END);
        footerHint.setPadding(dp(6), dp(8), dp(6), 0);
        root.addView(footerHint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        overlayRoot.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Anchored side bubble overlay layer (hidden until Menu key or long-press on a card)
        bubbleLayer = new FrameLayout(host);
        bubbleLayer.setVisibility(View.GONE);
        bubbleLayer.setClickable(true);
        bubbleLayer.setFocusable(false);
        bubbleLayer.setOnClickListener(v -> dismissSideBubble(true));
        overlayRoot.addView(bubbleLayer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        dialog = new TvDialog.Builder(host)
                .fullscreen()
                .setPageId("wallpaper")
                .setTitle("我的壁纸")
                .setView(overlayRoot)
                .setBackHandler(this::handleBack)
                .setKeyHandler(this::handleKey)
                .create();
        dialog.onReturn(this::reloadGrid);
        dialog.onLeave(this::cleanup);

        if (host.phone != null) {
            host.phone.setWallpaperImportedListener(() -> host.handler.post(() -> {
                if (!closed.get() && !host.isDestroyed()) {
                    focusedUnifiedId = WallpaperLibrary.activeId(host.preferences);
                    reloadGrid();
                }
            }));
        }

        host.showDialog(dialog);

        OnlineWallpaperStore.Catalog cached = onlineStore.loadCachedCatalog();
        if (cached != null && !cached.items.isEmpty()) {
            onlineItems = cached.items;
        }
        focusedUnifiedId = WallpaperLibrary.activeId(host.preferences);
        reloadGrid();
        fetchOnlineCatalogInBackground();
    }

    private TextView buildScanImportButton() {
        TextView btn = text("扫码导入壁纸", 14.5f, 0xFFEAF5FF, true);
        btn.setId(View.generateViewId());
        btn.setSingleLine(true);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(18), dp(10), dp(18), dp(10));
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        btn.setClickable(true);
        btn.setContentDescription("扫码导入壁纸，支持手机上传图片与 MP4 动态壁纸");

        GradientDrawable bg = TvDialog.background(0xFF1D3552, dp(14));
        bg.setStroke(dp(1), 0x664DA3FF);
        btn.setBackground(bg);

        UiMotion motion = new UiMotion(btn, 0, 32, p -> {
            bg.setColor((Integer) COLORS.evaluate(p, 0xFF1D3552, 0xFFF4F6FA));
            bg.setStroke(dp(1), (Integer) COLORS.evaluate(p, 0x664DA3FF, 0xFFFFFFFF));
            btn.setTextColor((Integer) COLORS.evaluate(p, 0xFFEAF5FF, 0xFF101318));
            float s = 1f + 0.03f * p;
            btn.setScaleX(s);
            btn.setScaleY(s);
        });
        btn.setOnFocusChangeListener((v, focused) -> {
            motion.to(focused ? 1f : 0f);
            if (focused) {
                footerHint.setText("按确定弹出二维码，手机扫码上传图片或 MP4 动态壁纸到「我的壁纸」");
            }
        });
        btn.setOnClickListener(v -> host.phone.showWallpaperImport());
        attachTouchClick(btn);
        return btn;
    }

    private void fetchOnlineCatalogInBackground() {
        if (catalogBusy || closed.get()) return;
        catalogBusy = true;
        catalogFailed = false;
        updateSubtitle();
        catalogNet.execute(() -> {
            try {
                OnlineWallpaperStore.Catalog fresh = onlineStore.fetchCatalog(closed);
                host.handler.post(() -> {
                    catalogBusy = false;
                    catalogFailed = false;
                    if (closed.get() || host.isDestroyed()) return;
                    onlineItems = fresh.items;
                    reloadGrid();
                });
            } catch (Exception ignored) {
                host.handler.post(() -> {
                    catalogBusy = false;
                    catalogFailed = true;
                    if (!closed.get() && !host.isDestroyed()) updateSubtitle();
                });
            }
        });
    }

    private void updateSubtitle() {
        if (subtitleView == null) return;
        int videoCount = 0;
        for (CardEntry e : entries) if (e.video) videoCount++;
        int downloadingCount = downloadProgress.size();
        String summary;
        if (onlineItems.isEmpty() && catalogBusy) {
            summary = "共 " + entries.size() + " 张本地壁纸 · 正在拉取在线壁纸库…";
        } else if (onlineItems.isEmpty() && catalogFailed) {
            summary = "共 " + entries.size() + " 张本地壁纸 · 暂未连接在线壁纸库 · 上键扫码导入";
        } else {
            summary = "共 " + entries.size() + " 张壁纸"
                    + (videoCount > 0 ? "（含 " + videoCount + " 部动态壁纸）" : "")
                    + (downloadingCount > 0 ? " · 后台下载中（" + downloadingCount + "），可自由切换壁纸" : " · 菜单键打开更多操作 · 上键扫码导入");
        }
        subtitleView.setText(summary);
    }

    private void reloadGrid() {
        if (closed.get()) return;
        dismissSideBubble(false);
        entries.clear();
        List<WallpaperLibrary.Entry> imported = host.wallpaperLibrary.list();
        for (WallpaperLibrary.Entry e : imported) entries.add(new CardEntry(e));
        Set<String> seenOnline = new HashSet<>();
        for (OnlineWallpaperStore.Item item : onlineItems) {
            if (seenOnline.add(item.id)) entries.add(new CardEntry(item));
        }
        for (int i = 0; i < WallpaperSource.ASSETS.length; i++) entries.add(new CardEntry(i));

        updateSubtitle();

        gridHost.removeAllViews();
        cards.clear();

        int rowCount = (entries.size() + COLUMNS - 1) / COLUMNS;
        for (int r = 0; r < rowCount; r++) {
            LinearLayout rowView = new LinearLayout(host);
            rowView.setOrientation(LinearLayout.HORIZONTAL);
            rowView.setClipChildren(false);
            rowView.setClipToPadding(false);
            for (int c = 0; c < COLUMNS; c++) {
                int idx = r * COLUMNS + c;
                LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (c > 0) cellLp.leftMargin = dp(14);
                if (idx < entries.size()) {
                    CardUi ui = buildCard(entries.get(idx));
                    cards.add(ui);
                    rowView.addView(ui.root, cellLp);
                } else {
                    View spacer = new View(host);
                    rowView.addView(spacer, cellLp);
                }
            }
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = dp(14);
            gridHost.addView(rowView, rowLp);
        }

        wireGridFocus();
        refreshAllBadges();
        restoreGridFocus();
    }

    private CardUi buildCard(CardEntry entry) {
        LinearLayout card = new LinearLayout(host);
        card.setId(View.generateViewId());
        card.setTag(entry);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(8), dp(8), dp(8), dp(10));
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        card.setClickable(true);

        GradientDrawable bg = TvDialog.background(CARD_REST_BG, dp(15));
        bg.setStroke(dp(1), CARD_REST_STROKE);
        card.setBackground(bg);

        FrameLayout frame = new FrameLayout(host);
        GradientDrawable frameBg = TvDialog.background(0xFF0E131D, dp(10));
        frame.setBackground(frameBg);
        frame.setClipToOutline(true);
        frame.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(10));
            }
        });

        ImageView thumb = new ImageView(host);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(thumb, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView placeholder = text(entry.video ? "动态壁纸" : "加载中…", 11.5f, 0xFF6F8196, false);
        placeholder.setGravity(Gravity.CENTER);
        frame.addView(placeholder, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        String badgeText = entry.ultraHd
                ? (entry.durationSec > 0 ? "4K 动态 · " + entry.durationSec + "s" : "4K 动态")
                : (entry.durationSec > 0 ? "▶ 动态 · " + entry.durationSec + "s" : "▶ 动态");
        TextView videoBadge = text(badgeText, 10f, entry.ultraHd ? 0xFFFFE08A : 0xFFF3F8FF, true);
        videoBadge.setPadding(dp(7), dp(2.5f), dp(7), dp(2.5f));
        GradientDrawable vidBg = TvDialog.background(entry.ultraHd ? 0xDC121926 : 0xCC5B3FD9, dp(8));
        vidBg.setStroke(dp(1), entry.ultraHd ? 0x88FFD26A : 0x55FFFFFF);
        videoBadge.setBackground(vidBg);
        videoBadge.setVisibility(entry.video ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams vLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT);
        vLp.setMargins(dp(7), dp(7), 0, 0);
        frame.addView(videoBadge, vLp);

        TextView stateBadge = text("", 10f, Color.WHITE, true);
        stateBadge.setPadding(dp(8), dp(2.5f), dp(8), dp(2.5f));
        stateBadge.setVisibility(View.GONE);
        FrameLayout.LayoutParams sLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT);
        sLp.setMargins(0, dp(7), dp(7), 0);
        frame.addView(stateBadge, sLp);

        card.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(136)));

        TextView title = text(entry.title, 14f, 0xFFF2F6FB, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(4), dp(8), dp(4), 0);
        card.addView(title);

        TextView sub = text(entry.subtitle, 11f, 0xFF8C9BB0, false);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setPadding(dp(4), dp(2), dp(4), 0);
        card.addView(sub);

        CardUi ui = new CardUi(entry, card, thumb, placeholder, videoBadge, stateBadge, title, sub, bg);
        UiMotion focusMotion = new UiMotion(card, 0, 32, p -> {
            ui.focusProgress = p;
            bg.setColor((Integer) COLORS.evaluate(p, CARD_REST_BG, CARD_FOCUS_BG));
            bg.setStroke(dp(1.5f), (Integer) COLORS.evaluate(p, CARD_REST_STROKE, 0xFFFFFFFF));
            title.setTextColor(p >= 0.45f ? 0xFF101318 : 0xFFF2F6FB);
            sub.setTextColor(p >= 0.45f ? 0xFF475466 : 0xFF8C9BB0);
            float s = 1f + 0.028f * p;
            card.setScaleX(s);
            card.setScaleY(s);
        });

        card.setOnFocusChangeListener((v, focused) -> {
            focusMotion.to(focused ? 1f : 0f);
            if (focused) {
                focusedUnifiedId = entry.unifiedId;
                updateFooterHint(entry);
                card.requestRectangleOnScreen(new android.graphics.Rect(0, -dp(16), card.getWidth(), card.getHeight() + dp(16)), false);
            }
        });
        card.setOnClickListener(v -> onCardSelect(entry));
        card.setOnLongClickListener(v -> {
            showSideBubble(ui);
            return true;
        });
        attachTouchClick(card);
        loadThumbnail(ui);
        return ui;
    }

    private void wireGridFocus() {
        int scanId = scanImportBtn.getId();
        scanImportBtn.setNextFocusLeftId(scanId);
        scanImportBtn.setNextFocusRightId(scanId);
        scanImportBtn.setNextFocusUpId(scanId);
        if (!cards.isEmpty()) {
            scanImportBtn.setNextFocusDownId(cards.get(0).root.getId());
        } else {
            scanImportBtn.setNextFocusDownId(scanId);
        }

        int total = cards.size();
        for (int i = 0; i < total; i++) {
            View v = cards.get(i).root;
            int col = i % COLUMNS;
            v.setNextFocusLeftId(col > 0 ? cards.get(i - 1).root.getId() : v.getId());
            v.setNextFocusRightId((col < COLUMNS - 1 && i + 1 < total) ? cards.get(i + 1).root.getId() : v.getId());
            v.setNextFocusUpId(i < COLUMNS ? scanId : cards.get(i - COLUMNS).root.getId());
            if (i + COLUMNS < total) {
                v.setNextFocusDownId(cards.get(i + COLUMNS).root.getId());
            } else {
                int lastRowStart = ((total - 1) / COLUMNS) * COLUMNS;
                if (i < lastRowStart) {
                    v.setNextFocusDownId(cards.get(total - 1).root.getId());
                } else {
                    v.setNextFocusDownId(v.getId());
                }
            }
        }
    }

    private void restoreGridFocus() {
        if (cards.isEmpty()) {
            scanImportBtn.post(scanImportBtn::requestFocus);
            return;
        }
        int targetIndex = 0;
        if (focusedUnifiedId != null) {
            for (int i = 0; i < cards.size(); i++) {
                if (focusedUnifiedId.equals(cards.get(i).entry.unifiedId)) {
                    targetIndex = i;
                    break;
                }
            }
        }
        final CardUi target = cards.get(targetIndex);
        updateFooterHint(target.entry);
        gridHost.post(() -> {
            if (!closed.get()) target.root.requestFocus();
        });
    }

    private void updateFooterHint(CardEntry entry) {
        if (entry == null) return;
        if (entry.kind == CardEntry.KIND_IMPORTED) {
            footerHint.setText("确定立即应用  ·  菜单键弹出操作气泡（应用 / 重命名 / 删除）  ·  上键扫码导入");
        } else if (entry.kind == CardEntry.KIND_ONLINE && downloadProgress.containsKey(entry.online.id)) {
            int pct = downloadProgress.get(entry.online.id);
            footerHint.setText("后台下载中 " + pct + "%（可自由切换其他壁纸）  ·  菜单键可取消下载");
        } else if (entry.kind == CardEntry.KIND_ONLINE && onlineStore.isDownloaded(entry.online)) {
            footerHint.setText("已缓存到本地 · 确定立即应用  ·  菜单键弹出操作气泡（应用 / 重下 / 删缓存）");
        } else if (entry.kind == CardEntry.KIND_ONLINE) {
            footerHint.setText("确定开始后台下载（" + WallpaperCatalogPolicy.formatSize(entry.online.size) + "，不阻塞切壁纸）  ·  菜单键更多操作");
        } else {
            footerHint.setText("确定立即应用  ·  菜单键弹出操作气泡  ·  上键扫码导入  ·  返回退出");
        }
    }

    private void refreshAllBadges() {
        String activeId = WallpaperLibrary.activeId(host.preferences);
        for (CardUi ui : cards) {
            CardEntry e = ui.entry;
            boolean active = activeId.equals(e.unifiedId);
            Integer pct = e.kind == CardEntry.KIND_ONLINE ? downloadProgress.get(e.online.id) : null;
            if (pct != null) {
                setPill(ui.stateBadge, "下载中 " + pct + "%", 0xFF1A1400, 0xE6FFD166, 0xFFFFE599);
            } else if (active) {
                setPill(ui.stateBadge, "✓ 使用中", 0xFFFFFFFF, 0xE622A854, 0x887DFFAB);
            } else if (e.kind == CardEntry.KIND_ONLINE && onlineStore.isDownloaded(e.online)) {
                setPill(ui.stateBadge, "已下载", 0xFFC5F4D8, 0xCC133223, 0x6649D482);
            } else if (e.kind == CardEntry.KIND_ONLINE) {
                setPill(ui.stateBadge, "↓ " + WallpaperCatalogPolicy.formatSize(e.online.size), 0xFFEAF2FA, 0xB8111824, 0x44FFFFFF);
            } else {
                ui.stateBadge.setVisibility(View.GONE);
            }
            ui.root.setContentDescription(e.title + (e.video ? "，动态壁纸" : "") + (active ? "，当前使用中" : ""));
        }
        updateSubtitle();
        if (focusedUnifiedId != null) {
            for (CardEntry e : entries) {
                if (focusedUnifiedId.equals(e.unifiedId)) {
                    updateFooterHint(e);
                    break;
                }
            }
        }
    }

    private void setPill(TextView badge, String label, int fg, int bgColor, int stroke) {
        badge.setText(label);
        badge.setTextColor(fg);
        GradientDrawable d = TvDialog.background(bgColor, dp(8));
        d.setStroke(dp(1), stroke);
        badge.setBackground(d);
        badge.setVisibility(View.VISIBLE);
    }

    private void loadThumbnail(CardUi ui) {
        final String key = ui.entry.unifiedId;
        Bitmap cached = thumbCache.get(key);
        if (cached != null && !cached.isRecycled()) {
            ui.thumb.setImageBitmap(cached);
            ui.placeholder.setVisibility(View.GONE);
            return;
        }
        thumbs.execute(() -> {
            if (closed.get()) return;
            Bitmap bmp = null;
            if (ui.entry.kind == CardEntry.KIND_BUILTIN) {
                bmp = WallpaperLibrary.loadBuiltinThumb(host, ui.entry.builtinIndex);
            } else if (ui.entry.kind == CardEntry.KIND_IMPORTED) {
                File f = host.wallpaperLibrary.thumbFile(ui.entry.imported);
                if (!f.isFile()) f = host.wallpaperLibrary.posterFile(ui.entry.imported);
                bmp = WallpaperLibrary.loadFileThumb(f);
            } else if (ui.entry.kind == CardEntry.KIND_ONLINE) {
                bmp = onlineStore.loadPreviewBitmap(ui.entry.online, closed);
            }
            final Bitmap ready = bmp;
            host.handler.post(() -> {
                if (closed.get() || host.isDestroyed()) {
                    if (ready != null) ready.recycle();
                    return;
                }
                if (ready != null) {
                    Bitmap prev = thumbCache.put(key, ready);
                    if (prev != null && prev != ready) prev.recycle();
                    for (CardUi current : cards) {
                        if (key.equals(current.entry.unifiedId)) {
                            current.thumb.setImageBitmap(ready);
                            current.placeholder.setVisibility(View.GONE);
                        }
                    }
                } else {
                    for (CardUi current : cards) {
                        if (key.equals(current.entry.unifiedId)) {
                            current.placeholder.setText(current.entry.video ? "▶ 动态壁纸" : "暂无缩略图");
                        }
                    }
                }
            });
        });
    }

    /** Click action: strictly decouples local apply from background download. */
    private void onCardSelect(CardEntry entry) {
        if (entry.kind == CardEntry.KIND_BUILTIN || entry.kind == CardEntry.KIND_IMPORTED) {
            applyWallpaperEntry(entry);
            return;
        }
        final OnlineWallpaperStore.Item item = entry.online;
        if (downloadProgress.containsKey(item.id)) {
            int pct = downloadProgress.get(item.id);
            host.toast("「" + item.title + "」正在后台下载 " + pct + "%，可自由切换其他壁纸");
            return;
        }
        if (onlineStore.isDownloaded(item)) {
            applyWallpaperEntry(entry);
            return;
        }
        startBackgroundDownload(entry, false);
    }

    /** Immediately applies any local/already-downloaded wallpaper without blocking on active downloads. */
    private void applyWallpaperEntry(CardEntry entry) {
        if (entry.kind == CardEntry.KIND_BUILTIN) {
            WallpaperLibrary.applyBuiltin(host, entry.builtinIndex);
            refreshAllBadges();
            host.toast("已应用壁纸：" + entry.title);
            return;
        }
        if (entry.kind == CardEntry.KIND_IMPORTED) {
            applyExecutor.execute(() -> {
                try {
                    host.wallpaperLibrary.applyEntry(host, entry.imported);
                    host.handler.post(() -> {
                        if (closed.get() || host.isDestroyed()) return;
                        refreshAllBadges();
                        host.toast((entry.video ? "已设为动态壁纸：" : "已应用壁纸：") + entry.title);
                    });
                } catch (Exception error) {
                    host.handler.post(() -> {
                        if (!closed.get() && !host.isDestroyed()) host.toast("应用壁纸失败，请重试");
                    });
                }
            });
            return;
        }
        final OnlineWallpaperStore.Item item = entry.online;
        if (!onlineStore.isDownloaded(item)) {
            host.toast("「" + item.title + "」尚未下载，请先下载后再应用");
            return;
        }
        applyExecutor.execute(() -> {
            try {
                WallpaperLibrary.applyOnline(host, onlineStore, item);
                host.handler.post(() -> {
                    if (host.isDestroyed()) return;
                    if (!closed.get()) refreshAllBadges();
                    host.toast((item.video ? "已设为动态壁纸：" : "已应用壁纸：") + item.title);
                });
            } catch (Exception error) {
                android.util.Log.w("MarcoTV", "Apply cached wallpaper failed: " + item.id, error);
                host.handler.post(() -> {
                    if (host.isDestroyed()) return;
                    if (!closed.get()) refreshAllBadges();
                    host.toast("「" + item.title + "」本地文件校验失败，请重新下载");
                });
            }
        });
    }

    /** Downloads an online wallpaper in the background without blocking switching to other wallpapers. */
    private void startBackgroundDownload(CardEntry entry, boolean forceRedownload) {
        final OnlineWallpaperStore.Item item = entry.online;
        if (downloadProgress.containsKey(item.id)) {
            host.toast("「" + item.title + "」已在后台下载中");
            return;
        }
        final AtomicBoolean itemCancelled = new AtomicBoolean(false);
        downloadCancels.put(item.id, itemCancelled);
        downloadProgress.put(item.id, 0);
        refreshAllBadges();
        host.toast("开始后台下载「" + item.title + "」，可自由切换其他壁纸");

        downloadPool.execute(() -> {
            if (closed.get() || itemCancelled.get()) return;
            try {
                if (forceRedownload) onlineStore.deleteCachedItem(item);
                onlineStore.downloadWallpaper(item, pct -> {
                    if (closed.get()) {
                        itemCancelled.set(true);
                        return;
                    }
                    if (itemCancelled.get()) return;
                    host.handler.post(() -> {
                        if (closed.get() || itemCancelled.get() || !downloadProgress.containsKey(item.id)) return;
                        downloadProgress.put(item.id, pct);
                        refreshAllBadges();
                    });
                }, itemCancelled);
                host.handler.post(() -> {
                    downloadProgress.remove(item.id);
                    downloadCancels.remove(item.id);
                    if (closed.get() || host.isDestroyed()) return;
                    refreshAllBadges();
                    host.toast("「" + item.title + "」下载完成，按确定即可设为桌面壁纸");
                });
            } catch (InterruptedIOException ignored) {
                host.handler.post(() -> {
                    downloadProgress.remove(item.id);
                    downloadCancels.remove(item.id);
                    if (!closed.get() && !host.isDestroyed()) refreshAllBadges();
                });
            } catch (Exception error) {
                android.util.Log.w("MarcoTV", "Wallpaper download failed: " + item.id, error);
                Diagnostics.event("ONLINE_WALLPAPER_DOWNLOAD_FAILED");
                final String reason = error.getMessage() == null ? "请检查网络后重试" : error.getMessage();
                host.handler.post(() -> {
                    downloadProgress.remove(item.id);
                    downloadCancels.remove(item.id);
                    if (closed.get() || host.isDestroyed()) return;
                    refreshAllBadges();
                    host.toast("「" + item.title + "」下载失败：" + reason);
                });
            }
        });
    }

    private void cancelItemDownload(String itemId) {
        AtomicBoolean flag = downloadCancels.remove(itemId);
        if (flag != null) flag.set(true);
        downloadProgress.remove(itemId);
        onlineStore.cancelItem(itemId);
        refreshAllBadges();
    }

    private void deleteOnlineCache(CardEntry entry) {
        OnlineWallpaperStore.Item item = entry.online;
        if (downloadProgress.containsKey(item.id)) {
            cancelItemDownload(item.id);
        }
        boolean wasActive = entry.unifiedId.equals(WallpaperLibrary.activeId(host.preferences));
        long reclaimed = onlineStore.deleteCachedItem(item);
        if (wasActive) {
            WallpaperLibrary.applyBuiltin(host, 0);
        }
        refreshAllBadges();
        if (reclaimed > 0) {
            host.toast("已删除「" + item.title + "」缓存（释放 " + WallpaperCatalogPolicy.formatSize(reclaimed) + "）");
        } else {
            host.toast("「" + item.title + "」暂无本地缓存");
        }
    }

    // ---- Anchored side bubble popover (Menu key / long-press on any wallpaper card) ----

    private boolean isBubbleOpen() {
        return bubbleLayer != null && bubbleLayer.getVisibility() == View.VISIBLE;
    }

    private boolean handleBack() {
        if (isBubbleOpen()) {
            dismissSideBubble(true);
            return true;
        }
        return false;
    }

    private boolean handleKey(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        int code = event.getKeyCode();
        if (isBubbleOpen()) {
            if (code == KeyEvent.KEYCODE_MENU
                    || code == KeyEvent.KEYCODE_DPAD_LEFT
                    || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                dismissSideBubble(true);
                return true;
            }
            return false;
        }
        View focused = dialog == null ? null : dialog.getCurrentFocus();
        if (focused == null || !(focused.getTag() instanceof CardEntry)) return false;
        boolean isMenu = code == KeyEvent.KEYCODE_MENU && event.getRepeatCount() == 0;
        boolean isHoldOk = HomeView.isConfirmKey(code) && event.getRepeatCount() >= 3;
        if (isMenu || isHoldOk) {
            CardUi targetUi = findCardUi(focused);
            if (targetUi != null) {
                focused.cancelLongPress();
                showSideBubble(targetUi);
                return true;
            }
        }
        return false;
    }

    private CardUi findCardUi(View cardView) {
        for (CardUi ui : cards) {
            if (ui.root == cardView) return ui;
        }
        return null;
    }

    private static final class BubbleAction {
        final String label;
        final String hint;
        final boolean danger;
        final boolean muted;
        final Runnable onClick;

        BubbleAction(String label, String hint, boolean danger, boolean muted, Runnable onClick) {
            this.label = label;
            this.hint = hint;
            this.danger = danger;
            this.muted = muted;
            this.onClick = onClick;
        }
    }

    private void showSideBubble(CardUi ui) {
        dismissSideBubble(false);
        final CardEntry entry = ui.entry;
        bubbleAnchorCard = ui.root;

        ArrayList<BubbleAction> actions = new ArrayList<>();
        String statusLine;
        int defaultFocus = 0;

        if (entry.kind == CardEntry.KIND_ONLINE) {
            OnlineWallpaperStore.Item item = entry.online;
            boolean downloading = downloadProgress.containsKey(item.id);
            boolean downloaded = onlineStore.isDownloaded(item);
            boolean hasCache = onlineStore.hasCachedFiles(item);
            if (downloading) {
                int pct = downloadProgress.get(item.id);
                statusLine = "后台下载中 " + pct + "% · " + WallpaperCatalogPolicy.formatSize(item.size);
            } else if (downloaded) {
                statusLine = "已缓存 · " + WallpaperCatalogPolicy.formatSize(item.size);
            } else {
                statusLine = "未下载 · " + WallpaperCatalogPolicy.formatSize(item.size);
            }

            actions.add(new BubbleAction(
                    "应用此壁纸",
                    downloaded ? "" : (downloading ? "下载中" : "需先下载"),
                    false, !downloaded,
                    () -> {
                        dismissSideBubble(true);
                        applyWallpaperEntry(entry);
                    }
            ));
            if (downloading) {
                actions.add(new BubbleAction(
                        "取消下载",
                        downloadProgress.get(item.id) + "%",
                        true, false,
                        () -> {
                            dismissSideBubble(true);
                            cancelItemDownload(item.id);
                            host.toast("已取消下载「" + item.title + "」");
                        }
                ));
                defaultFocus = 1;
            } else {
                actions.add(new BubbleAction(
                        downloaded ? "重新下载" : "下载此壁纸",
                        downloaded ? "" : WallpaperCatalogPolicy.formatSize(item.size),
                        false, false,
                        () -> {
                            dismissSideBubble(true);
                            startBackgroundDownload(entry, downloaded);
                        }
                ));
                if (!downloaded) defaultFocus = 1;
            }
            actions.add(new BubbleAction(
                    "删除该壁纸缓存",
                    hasCache ? "" : "无缓存",
                    true, !hasCache && !downloading,
                    () -> {
                        dismissSideBubble(true);
                        deleteOnlineCache(entry);
                    }
            ));
        } else if (entry.kind == CardEntry.KIND_IMPORTED) {
            statusLine = entry.video ? "已导入动态壁纸" : "已导入静态壁纸";
            actions.add(new BubbleAction("应用此壁纸", "", false, false, () -> {
                dismissSideBubble(true);
                applyWallpaperEntry(entry);
            }));
            actions.add(new BubbleAction("重命名壁纸", "", false, false, () -> {
                dismissSideBubble(false);
                showRenameDialog(entry.imported);
            }));
            actions.add(new BubbleAction("删除此壁纸", "", true, false, () -> {
                dismissSideBubble(false);
                confirmDelete(entry.imported);
            }));
        } else {
            statusLine = "内置静态壁纸";
            actions.add(new BubbleAction("应用此壁纸", "", false, false, () -> {
                dismissSideBubble(true);
                applyWallpaperEntry(entry);
            }));
        }

        // Build compact side bubble card extending beside the focused wallpaper card
        int bubbleW = dp(212);
        LinearLayout bubble = new LinearLayout(host);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(12), dp(11), dp(12), dp(11));
        bubble.setClickable(true);
        GradientDrawable bubbleBg = TvDialog.background(0xF6192130, dp(15));
        bubbleBg.setStroke(dp(1.2f), 0x48FFFFFF);
        bubble.setBackground(bubbleBg);
        bubble.setElevation(dp(16));

        TextView headTitle = text(entry.title, 13.5f, 0xFFF4F7FB, true);
        headTitle.setSingleLine(true);
        headTitle.setEllipsize(TextUtils.TruncateAt.END);
        headTitle.setPadding(dp(4), 0, dp(4), dp(2));
        bubble.addView(headTitle);

        TextView headSub = text(statusLine, 10.5f, 0xFF8FA3BA, false);
        headSub.setSingleLine(true);
        headSub.setEllipsize(TextUtils.TruncateAt.END);
        headSub.setPadding(dp(4), 0, dp(4), dp(8));
        bubble.addView(headSub);

        View divider = new View(host);
        divider.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        divLp.bottomMargin = dp(7);
        bubble.addView(divider, divLp);

        ArrayList<View> btnViews = new ArrayList<>();
        for (int i = 0; i < actions.size(); i++) {
            final BubbleAction act = actions.get(i);
            LinearLayout row = new LinearLayout(host);
            row.setId(View.generateViewId());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(11), dp(9), dp(11), dp(9));
            row.setFocusable(true);
            row.setFocusableInTouchMode(true);
            row.setClickable(true);

            final int restBg = 0xFF232C3D;
            final int restFg = act.muted ? 0xFF7D8C9E : (act.danger ? 0xFFFF9B94 : 0xFFEFF4FA);
            final int focusFg = act.muted ? 0xFF566270 : (act.danger ? 0xFFC42B22 : 0xFF101318);
            final GradientDrawable rowBg = TvDialog.background(restBg, dp(10));
            row.setBackground(rowBg);

            TextView labelTv = text(act.label, 13f, restFg, true);
            labelTv.setSingleLine(true);
            labelTv.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(labelTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView hintTv = null;
            if (!act.hint.isEmpty()) {
                hintTv = text(act.hint, 10.5f, 0xFF899BB0, false);
                hintTv.setSingleLine(true);
                hintTv.setPadding(dp(6), 0, 0, 0);
                row.addView(hintTv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final TextView finalHint = hintTv;

            UiMotion focusMotion = new UiMotion(row, 0, 34, p -> {
                rowBg.setColor((Integer) COLORS.evaluate(p, restBg, 0xFFF4F6FA));
                labelTv.setTextColor(p >= 0.45f ? focusFg : restFg);
                if (finalHint != null) finalHint.setTextColor(p >= 0.45f ? 0xFF556375 : 0xFF899BB0);
            });
            row.setOnFocusChangeListener((v, focused) -> focusMotion.to(focused ? 1f : 0f));
            row.setOnClickListener(v -> act.onClick.run());
            attachTouchClick(row);

            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) rowLp.topMargin = dp(5);
            bubble.addView(row, rowLp);
            btnViews.add(row);
        }

        // Trap Up/Down focus cleanly inside bubble buttons
        for (int i = 0; i < btnViews.size(); i++) {
            View v = btnViews.get(i);
            v.setNextFocusLeftId(v.getId());
            v.setNextFocusRightId(v.getId());
            v.setNextFocusUpId(btnViews.get(i > 0 ? i - 1 : btnViews.size() - 1).getId());
            v.setNextFocusDownId(btnViews.get(i + 1 < btnViews.size() ? i + 1 : 0).getId());
        }

        // Measure bubble height to anchor cleanly beside the focused card without clipping
        bubble.measure(
                View.MeasureSpec.makeMeasureSpec(bubbleW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        );
        int bubbleH = bubble.getMeasuredHeight();

        int[] rootLoc = new int[2];
        int[] cardLoc = new int[2];
        overlayRoot.getLocationInWindow(rootLoc);
        ui.root.getLocationInWindow(cardLoc);
        int overlayW = overlayRoot.getWidth() > 0 ? overlayRoot.getWidth() : host.getResources().getDisplayMetrics().widthPixels;
        int overlayH = overlayRoot.getHeight() > 0 ? overlayRoot.getHeight() : host.getResources().getDisplayMetrics().heightPixels;
        int cardLeft = cardLoc[0] - rootLoc[0];
        int cardTop = cardLoc[1] - rootLoc[1];
        int cardRight = cardLeft + ui.root.getWidth();
        int cardCenterY = cardTop + ui.root.getHeight() / 2;

        boolean extendRight = (cardRight + dp(10) + bubbleW <= overlayW - dp(12));
        int left = extendRight
                ? cardRight + dp(10)
                : Math.max(dp(12), cardLeft - bubbleW - dp(10));
        int top = Math.max(dp(18), Math.min(overlayH - bubbleH - dp(18), cardCenterY - bubbleH / 2));

        bubbleLayer.removeAllViews();
        FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(bubbleW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT);
        bLp.leftMargin = left;
        bLp.topMargin = top;
        bubbleLayer.addView(bubble, bLp);
        bubbleLayer.setVisibility(View.VISIBLE);
        bubbleLayer.bringToFront();

        // Smooth side-extension animation emerging from the focused card edge
        float startTx = extendRight ? -dp(16) : dp(16);
        bubble.setTranslationX(startTx);
        bubble.setAlpha(0f);
        bubbleSlideMotion = new UiMotion(bubble, startTx, 30, bubble::setTranslationX);
        bubbleAlphaMotion = new UiMotion(bubble, 0f, 34, bubble::setAlpha);
        bubbleSlideMotion.to(0f);
        bubbleAlphaMotion.to(1f);

        footerHint.setText("上下选择操作  ·  确定执行  ·  左右 / 菜单 / 返回关闭气泡");

        final View initialBtn = btnViews.get(Math.max(0, Math.min(btnViews.size() - 1, defaultFocus)));
        bubble.post(initialBtn::requestFocus);
    }

    private void dismissSideBubble(boolean restoreFocus) {
        if (bubbleSlideMotion != null) {
            bubbleSlideMotion.dispose();
            bubbleSlideMotion = null;
        }
        if (bubbleAlphaMotion != null) {
            bubbleAlphaMotion.dispose();
            bubbleAlphaMotion = null;
        }
        if (bubbleLayer != null) {
            bubbleLayer.removeAllViews();
            bubbleLayer.setVisibility(View.GONE);
        }
        View anchor = bubbleAnchorCard;
        bubbleAnchorCard = null;
        if (restoreFocus && anchor != null && !closed.get()) {
            anchor.requestFocus();
            if (anchor.getTag() instanceof CardEntry) {
                updateFooterHint((CardEntry) anchor.getTag());
            }
        }
    }

    private void showRenameDialog(WallpaperLibrary.Entry entry) {
        EditText input = new EditText(host);
        input.setSingleLine(true);
        input.setText(entry.title);
        input.selectAll();
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(28)});
        input.setHint("输入壁纸名称");
        host.showDialog(new TvDialog.Builder(host)
                .setPageId("wallpaper_rename")
                .setTitle("重命名壁纸")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    if (host.wallpaperLibrary.rename(entry.id, input.getText().toString())) {
                        host.toast("名称已更新");
                    }
                })
                .setNegativeButton("取消", null)
                .create());
    }

    private void confirmDelete(WallpaperLibrary.Entry entry) {
        host.showDialog(new TvDialog.Builder(host)
                .setPageId("wallpaper_delete")
                .setTitle("删除「" + entry.title + "」？")
                .setMessage("将从「我的壁纸」中永久删除此文件；若当前正在使用，将自动切回默认壁纸。")
                .setPositiveButton("删除", (d, w) -> {
                    boolean wasActive = entry.unifiedId().equals(WallpaperLibrary.activeId(host.preferences));
                    if (host.wallpaperLibrary.delete(entry.id)) {
                        Bitmap old = thumbCache.remove(entry.unifiedId());
                        if (old != null && !old.isRecycled()) old.recycle();
                        if (wasActive) host.loadWallpaper();
                        host.toast("已删除壁纸");
                    }
                })
                .setNegativeButton("取消", null)
                .create());
    }

    private TextView text(String value, float sp, int color, boolean medium) {
        TextView t = new TextView(host);
        t.setText(value);
        t.setTextSize(sp);
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

    private void cleanup() {
        if (!closed.compareAndSet(false, true)) return;
        dismissSideBubble(false);
        for (AtomicBoolean flag : downloadCancels.values()) flag.set(true);
        downloadCancels.clear();
        downloadProgress.clear();
        if (host.phone != null) host.phone.setWallpaperImportedListener(null);
        onlineStore.cancelActive();
        catalogNet.shutdownNow();
        downloadPool.shutdownNow();
        // Allow an already-clicked local apply to finish even if the user immediately presses Back.
        applyExecutor.shutdown();
        thumbs.shutdownNow();
        for (CardUi ui : cards) ui.thumb.setImageBitmap(null);
        for (Bitmap b : thumbCache.values()) if (b != null && !b.isRecycled()) b.recycle();
        thumbCache.clear();
    }
}
