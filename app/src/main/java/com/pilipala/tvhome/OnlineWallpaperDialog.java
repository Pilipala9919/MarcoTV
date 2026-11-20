// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.InterruptedIOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Apple TV-styled online mirror wallpaper browser with live preview, SHA-256 verification and local cache. */
final class OnlineWallpaperDialog {
    private final MainActivity host;
    private final OnlineWallpaperStore store;
    private final AppearanceSession session;
    private final int epoch;
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbs = Executors.newSingleThreadExecutor();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Map<String, Bitmap> thumbMemory = new HashMap<>();

    private ImageView previewImage;
    private TextView previewPlaceholder;
    private TextView previewBadge;
    private TextView previewTitle;
    private TextView previewSub;
    private TextView previewMeta;
    private TextView statusBanner;
    private LinearLayout listBox;
    private TextView refreshBtn;
    private TextView clearBtn;

    private List<OnlineWallpaperStore.Item> items = Collections.emptyList();
    private final List<RowUi> rows = new ArrayList<>();
    private String downloadingId;
    private int downloadPercent;
    private String focusedId;
    private boolean busyCatalog;

    private static final class RowUi {
        final OnlineWallpaperStore.Item item;
        final LinearLayout container;
        final TextView title;
        final TextView sub;
        final TextView pill;

        RowUi(OnlineWallpaperStore.Item item, LinearLayout container, TextView title, TextView sub, TextView pill) {
            this.item = item;
            this.container = container;
            this.title = title;
            this.sub = sub;
            this.pill = pill;
        }
    }

    static void show(MainActivity host) {
        new OnlineWallpaperDialog(host).open();
    }

    private OnlineWallpaperDialog(MainActivity host) {
        this.host = host;
        this.store = new OnlineWallpaperStore(host);
        this.session = host.appearanceSession;
        this.epoch = host.menuEpoch;
    }

    private int dp(float v) {
        return Math.round(v * host.getResources().getDisplayMetrics().density);
    }

    private void open() {
        LinearLayout root = new LinearLayout(host);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.TOP);

        // Left fixed Apple TV hero preview card (16:9 thumbnail + metadata + attribution)
        LinearLayout hero = new LinearLayout(host);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(12), dp(12), dp(12), dp(10));
        GradientDrawable heroBg = TvDialog.background(0xFF1E2430, dp(16));
        heroBg.setStroke(dp(1), 0x26FFFFFF);
        hero.setBackground(heroBg);

        FrameLayout frame = new FrameLayout(host);
        GradientDrawable frameBg = TvDialog.background(0xFF10151E, dp(11));
        frameBg.setStroke(dp(1), 0x33FFFFFF);
        frame.setBackground(frameBg);
        frame.setClipToOutline(true);
        frame.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(11));
            }
        });

        previewImage = new ImageView(host);
        previewImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(previewImage, new FrameLayout.LayoutParams(-1, -1));

        previewPlaceholder = new TextView(host);
        previewPlaceholder.setText("精选壁纸预览");
        previewPlaceholder.setTextColor(0xFF8395AB);
        previewPlaceholder.setTextSize(12);
        previewPlaceholder.setGravity(Gravity.CENTER);
        frame.addView(previewPlaceholder, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(-1, dp(155));
        frameParams.bottomMargin = dp(9);
        hero.addView(frame, frameParams);

        previewBadge = new TextView(host);
        previewBadge.setTextSize(10.5f);
        previewBadge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        previewBadge.setPadding(dp(8), dp(2), dp(8), dp(2));
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, -2);
        badgeParams.bottomMargin = dp(5);
        hero.addView(previewBadge, badgeParams);

        previewTitle = new TextView(host);
        previewTitle.setTextColor(0xFFF4F7FB);
        previewTitle.setTextSize(16.5f);
        previewTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        previewTitle.setSingleLine(true);
        previewTitle.setEllipsize(TextUtils.TruncateAt.END);
        hero.addView(previewTitle);

        previewSub = new TextView(host);
        previewSub.setTextColor(0xFFB8C6D8);
        previewSub.setTextSize(12f);
        previewSub.setSingleLine(true);
        previewSub.setEllipsize(TextUtils.TruncateAt.END);
        previewSub.setPadding(0, dp(2), 0, dp(4));
        hero.addView(previewSub);

        previewMeta = new TextView(host);
        previewMeta.setTextColor(0xFF889BB0);
        previewMeta.setTextSize(10.5f);
        previewMeta.setLineSpacing(dp(2), 1f);
        previewMeta.setMaxLines(2);
        previewMeta.setEllipsize(TextUtils.TruncateAt.END);
        hero.addView(previewMeta);

        LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(dp(300), -2);
        heroParams.rightMargin = dp(14);
        root.addView(hero, heroParams);

        // Right column: compact status banner + independently scrollable wallpaper list & utility actions
        LinearLayout right = new LinearLayout(host);
        right.setOrientation(LinearLayout.VERTICAL);

        statusBanner = new TextView(host);
        statusBanner.setTextColor(0xFF9EB4CC);
        statusBanner.setTextSize(12f);
        statusBanner.setSingleLine(true);
        statusBanner.setEllipsize(TextUtils.TruncateAt.END);
        statusBanner.setPadding(dp(12), dp(7), dp(12), dp(7));
        statusBanner.setBackground(TvDialog.background(0xFF1A202B, dp(10)));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.bottomMargin = dp(8);
        right.addView(statusBanner, statusParams);

        MotionScrollView listScroll = new MotionScrollView(host);
        listScroll.setFadeColor(0xFF171B23);
        listScroll.setFillViewport(false);
        listScroll.setVerticalScrollBarEnabled(false);
        listScroll.setFocusable(false);

        LinearLayout scrollBody = new LinearLayout(host);
        scrollBody.setOrientation(LinearLayout.VERTICAL);
        scrollBody.setPadding(dp(1), dp(2), dp(1), dp(4));

        listBox = new LinearLayout(host);
        listBox.setOrientation(LinearLayout.VERTICAL);
        scrollBody.addView(listBox, new LinearLayout.LayoutParams(-1, -2));

        // Utility buttons row (Refresh + Clear cache)
        LinearLayout actions = new LinearLayout(host);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(6), 0, 0);

        refreshBtn = actionPill("刷新在线列表", () -> fetchCatalog(true));
        clearBtn = actionPill("清理缓存", this::clearCache);
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, -2, 1);
        p1.rightMargin = dp(8);
        actions.addView(refreshBtn, p1);
        actions.addView(clearBtn, new LinearLayout.LayoutParams(0, -2, 1));
        scrollBody.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        listScroll.addView(scrollBody, new FrameLayout.LayoutParams(-1, -2));
        int screenH = host.getResources().getDisplayMetrics().heightPixels;
        int listHeight = Math.min(dp(238), Math.max(dp(156), screenH - dp(252)));
        right.addView(listScroll, new LinearLayout.LayoutParams(-1, listHeight));

        root.addView(right, new LinearLayout.LayoutParams(0, -2, 1));

        TvDialog dialog = new TvDialog.Builder(host)
                .centered()
                .setPageId("wallpaper_online")
                .setTitle("在线精选壁纸")
                .setView(root)
                .setNegativeButton("返回", null)
                .create();
        dialog.onLeave(this::cleanup);
        host.showDialog(dialog);

        updateCacheButton();
        OnlineWallpaperStore.Catalog cached = store.loadCachedCatalog();
        if (cached != null && !cached.items.isEmpty()) {
            renderCatalog(cached, "已读取本地目录缓存，正在后台检查最新壁纸…");
            fetchCatalog(false);
        } else {
            showEmptyPreview("正在获取精选壁纸", "连接镜像站获取壁纸目录，请稍候…");
            setStatus("正在从镜像站获取精选壁纸目录…", 0xFF9EB4CC);
            fetchCatalog(true);
        }
    }

    private TextView actionPill(String label, Runnable onClick) {
        TextView btn = new TextView(host);
        btn.setText(label);
        btn.setTextColor(0xFFEAF0F7);
        btn.setTextSize(12.5f);
        btn.setSingleLine(true);
        btn.setEllipsize(TextUtils.TruncateAt.END);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(10), dp(9), dp(10), dp(9));
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        GradientDrawable bg = TvDialog.background(0xFF232A37, dp(11));
        bg.setStroke(dp(1), 0x22FFFFFF);
        btn.setBackground(bg);
        btn.setOnFocusChangeListener((v, focused) -> {
            bg.setColor(focused ? 0xFFF4F6FA : 0xFF232A37);
            bg.setStroke(dp(1), focused ? 0xFFFFFFFF : 0x22FFFFFF);
            btn.setTextColor(focused ? 0xFF101318 : 0xFFEAF0F7);
            btn.setTypeface(Typeface.DEFAULT, focused ? Typeface.BOLD : Typeface.NORMAL);
        });
        btn.setOnClickListener(v -> onClick.run());
        return btn;
    }

    private void showEmptyPreview(String title, String subtitle) {
        previewImage.setImageBitmap(null);
        previewPlaceholder.setVisibility(View.VISIBLE);
        setBadge(previewBadge, "镜像壁纸库", 0xFF7FD4FF, 0x220A84FF, 0x550A84FF);
        previewTitle.setText(title);
        previewSub.setText(subtitle);
        previewMeta.setText("镜像地址：marcotv.wlj9919.top/wallpapers/");
    }

    private void setBadge(TextView view, String text, int fg, int bgColor, int stroke) {
        view.setText(text);
        view.setTextColor(fg);
        GradientDrawable d = TvDialog.background(bgColor, dp(9));
        d.setStroke(dp(1), stroke);
        view.setBackground(d);
    }

    private void setStatus(String text, int color) {
        statusBanner.setText(text);
        statusBanner.setTextColor(color);
        statusBanner.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void updateCacheButton() {
        long bytes = store.cacheSizeBytes();
        clearBtn.setText(bytes > 0 ? "清理缓存（" + WallpaperCatalogPolicy.formatSize(bytes) + "）" : "暂无本地缓存");
    }

    private void fetchCatalog(boolean verbose) {
        if (busyCatalog || closed.get()) return;
        busyCatalog = true;
        if (verbose) setStatus("正在连接镜像站拉取壁纸目录…", 0xFF9EB4CC);
        net.execute(() -> {
            try {
                OnlineWallpaperStore.Catalog catalog = store.fetchCatalog(closed);
                host.handler.post(() -> {
                    busyCatalog = false;
                    if (closed.get() || host.isDestroyed()) return;
                    String note = "共 " + catalog.items.size() + " 张精选壁纸"
                            + (catalog.updated.isEmpty() ? "" : " · " + catalog.updated)
                            + " · 上下预览，按确定应用";
                    renderCatalog(catalog, note);
                });
            } catch (InterruptedIOException ignored) {
                host.handler.post(() -> busyCatalog = false);
            } catch (Exception error) {
                host.handler.post(() -> {
                    busyCatalog = false;
                    if (closed.get() || host.isDestroyed()) return;
                    if (items.isEmpty()) {
                        showEmptyPreview("暂未获取到在线壁纸", "请检查电视网络连接，或确认镜像站已部署 wallpapers/catalog.json。");
                        setStatus("连接失败：镜像站暂不可达或尚未上传壁纸包。你仍可使用内置壁纸或本地导入（最大 50 MB）。", 0xFFFFC176);
                    } else if (verbose) {
                        setStatus("未能刷新最新目录，当前显示上次缓存的壁纸列表（已下载的壁纸仍可离线切换）。", 0xFFFFC176);
                    }
                });
            }
        });
    }

    private void renderCatalog(OnlineWallpaperStore.Catalog catalog, String statusText) {
        this.items = catalog.items;
        listBox.removeAllViews();
        rows.clear();
        setStatus(statusText, 0xFF9EB4CC);
        for (int i = 0; i < items.size(); i++) {
            final OnlineWallpaperStore.Item item = items.get(i);
            RowUi row = buildRow(item);
            rows.add(row);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(6);
            listBox.addView(row.container, lp);
        }
        updateCacheButton();
        if (!rows.isEmpty()) {
            int focusIndex = 0;
            if (focusedId != null) {
                for (int i = 0; i < rows.size(); i++) {
                    if (rows.get(i).item.id.equals(focusedId)) {
                        focusIndex = i;
                        break;
                    }
                }
            }
            selectPreview(rows.get(focusIndex).item);
            final View target = rows.get(focusIndex).container;
            listBox.post(target::requestFocus);
        }
    }

    private RowUi buildRow(OnlineWallpaperStore.Item item) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(12), dp(10));
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        GradientDrawable bg = TvDialog.background(0xFF212734, dp(12));
        bg.setStroke(dp(1), 0x18FFFFFF);
        row.setBackground(bg);

        LinearLayout left = new LinearLayout(host);
        left.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(host);
        title.setText(item.title);
        title.setTextColor(0xFFF2F6FB);
        title.setTextSize(14.5f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        left.addView(title);

        TextView sub = new TextView(host);
        String line = item.width + " × " + item.height + " · " + WallpaperCatalogPolicy.formatSize(item.size);
        if (!item.subtitle.isEmpty()) line += " · " + item.subtitle;
        sub.setText(line);
        sub.setTextColor(0xFF91A3B8);
        sub.setTextSize(11f);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setPadding(0, dp(2), 0, 0);
        left.addView(sub);

        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, -2, 1);
        leftLp.rightMargin = dp(8);
        row.addView(left, leftLp);

        TextView pill = new TextView(host);
        pill.setTextSize(11f);
        pill.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        pill.setPadding(dp(9), dp(3.5f), dp(9), dp(3.5f));
        row.addView(pill, new LinearLayout.LayoutParams(-2, -2));

        RowUi holder = new RowUi(item, row, title, sub, pill);
        styleRow(holder, false);

        row.setOnFocusChangeListener((v, focused) -> {
            styleRow(holder, focused);
            if (focused) selectPreview(item);
        });
        row.setOnClickListener(v -> onSelect(item));
        return holder;
    }

    private void styleRow(RowUi holder, boolean focused) {
        GradientDrawable bg = (GradientDrawable) holder.container.getBackground();
        bg.setColor(focused ? 0xFFF4F6FA : 0xFF212734);
        bg.setStroke(dp(1), focused ? 0xFFFFFFFF : 0x18FFFFFF);
        holder.title.setTextColor(focused ? 0xFF101318 : 0xFFF2F6FB);
        holder.sub.setTextColor(focused ? 0xFF475466 : 0xFF91A3B8);
        updateRowPill(holder, focused);
    }

    private void updateRowPill(RowUi holder, boolean focused) {
        OnlineWallpaperStore.Item item = holder.item;
        if (item.id.equals(downloadingId)) {
            setBadge(holder.pill, "下载中 " + downloadPercent + "%",
                    focused ? 0xFF7A4B00 : 0xFFFFD166,
                    focused ? 0x33FFD166 : 0x22FFD166,
                    0x66FFD166);
        } else if (store.isDownloaded(item)) {
            setBadge(holder.pill, "已下载 · 应用",
                    focused ? 0xFF0E6B33 : 0xFF63E69A,
                    focused ? 0x2434C759 : 0x2234C759,
                    0x6634C759);
        } else {
            setBadge(holder.pill, "下载 · " + WallpaperCatalogPolicy.formatSize(item.size),
                    focused ? 0xFF0A57A8 : 0xFF7FD4FF,
                    focused ? 0x1F0A84FF : 0x200A84FF,
                    0x550A84FF);
        }
    }

    private void refreshAllPills() {
        for (RowUi holder : rows) updateRowPill(holder, holder.container.hasFocus());
        if (focusedId != null) {
            for (OnlineWallpaperStore.Item item : items) {
                if (item.id.equals(focusedId)) {
                    updatePreviewTexts(item);
                    break;
                }
            }
        }
        updateCacheButton();
    }

    private void selectPreview(OnlineWallpaperStore.Item item) {
        focusedId = item.id;
        updatePreviewTexts(item);
        Bitmap cached = thumbMemory.get(item.id);
        if (cached != null && !cached.isRecycled()) {
            previewImage.setImageBitmap(cached);
            previewPlaceholder.setVisibility(View.GONE);
            return;
        }
        previewImage.setImageBitmap(null);
        previewPlaceholder.setText("预览加载中…");
        previewPlaceholder.setVisibility(View.VISIBLE);
        final String wantedId = item.id;
        thumbs.execute(() -> {
            if (closed.get()) return;
            Bitmap bmp = store.loadPreviewBitmap(item, closed);
            host.handler.post(() -> {
                if (closed.get() || host.isDestroyed()) {
                    if (bmp != null) bmp.recycle();
                    return;
                }
                if (bmp != null) {
                    Bitmap prev = thumbMemory.put(wantedId, bmp);
                    if (prev != null && prev != bmp) prev.recycle();
                    if (wantedId.equals(focusedId)) {
                        previewImage.setImageBitmap(bmp);
                        previewPlaceholder.setVisibility(View.GONE);
                    }
                    updateCacheButton();
                } else if (wantedId.equals(focusedId)) {
                    previewPlaceholder.setText("暂无预览图");
                }
            });
        });
    }

    private void updatePreviewTexts(OnlineWallpaperStore.Item item) {
        if (item.id.equals(downloadingId)) {
            setBadge(previewBadge, "正在下载 " + downloadPercent + "%", 0xFFFFD166, 0x22FFD166, 0x66FFD166);
        } else if (store.isDownloaded(item)) {
            setBadge(previewBadge, "已下载 · 本地可用", 0xFF63E69A, 0x2234C759, 0x6634C759);
        } else {
            setBadge(previewBadge, "在线精选 · " + WallpaperCatalogPolicy.formatSize(item.size), 0xFF7FD4FF, 0x200A84FF, 0x550A84FF);
        }
        previewTitle.setText(item.title);
        previewSub.setText(item.subtitle.isEmpty() ? "按确定下载并设为桌面壁纸" : item.subtitle);
        StringBuilder meta = new StringBuilder();
        meta.append(item.width).append(" × ").append(item.height)
                .append(" · ").append(WallpaperCatalogPolicy.formatSize(item.size));
        if (!item.credit.isEmpty()) meta.append("\n署名：").append(item.credit);
        if (!item.license.isEmpty()) meta.append(item.credit.isEmpty() ? "\n许可：" : " · ").append(item.license);
        previewMeta.setText(meta.toString());
    }

    private void onSelect(OnlineWallpaperStore.Item item) {
        if (downloadingId != null) {
            host.toast("正在下载壁纸，请稍候…");
            return;
        }
        downloadingId = item.id;
        downloadPercent = store.isDownloaded(item) ? 100 : 0;
        final long sessionRev = session == null ? 0L : session.wallpaperRevision;
        if (session != null) session.pendingImports++;
        refreshAllPills();
        setStatus(store.isDownloaded(item) ? "正在校验并应用「" + item.title + "」…" : "正在下载「" + item.title + "」…", 0xFFFFD166);

        net.execute(() -> {
            File staged = null;
            try {
                store.downloadWallpaper(item, pct -> host.handler.post(() -> {
                    if (closed.get() || !item.id.equals(downloadingId)) return;
                    downloadPercent = pct;
                    refreshAllPills();
                    setStatus("正在下载「" + item.title + "」 " + pct + "%…", 0xFFFFD166);
                }), closed);
                staged = store.stageCopyForApply(item);
                final File readyFile = staged;
                staged = null;
                host.handler.post(() -> {
                    downloadingId = null;
                    if (session != null) session.pendingImports = Math.max(0, session.pendingImports - 1);
                    if (closed.get() || host.isDestroyed() || epoch != host.menuEpoch) {
                        readyFile.delete();
                        return;
                    }
                    applyReadyFile(item, readyFile, sessionRev);
                    // Refresh thumbnail from freshly downloaded full wallpaper if not yet cached in memory
                    if (!thumbMemory.containsKey(item.id)) selectPreview(item);
                    refreshAllPills();
                });
            } catch (InterruptedIOException ignored) {
                if (staged != null) staged.delete();
                host.handler.post(() -> {
                    downloadingId = null;
                    if (session != null) session.pendingImports = Math.max(0, session.pendingImports - 1);
                    if (!closed.get()) {
                        refreshAllPills();
                        setStatus("下载已取消", 0xFF9EB4CC);
                    }
                });
            } catch (Exception error) {
                if (staged != null) staged.delete();
                Diagnostics.event("ONLINE_WALLPAPER_DOWNLOAD_FAILED");
                final String reason = error.getMessage() == null ? "请检查网络后重试" : error.getMessage();
                host.handler.post(() -> {
                    downloadingId = null;
                    if (session != null) session.pendingImports = Math.max(0, session.pendingImports - 1);
                    if (closed.get() || host.isDestroyed()) return;
                    refreshAllPills();
                    setStatus("「" + item.title + "」下载或校验失败（" + reason + "）", 0xFFFF9B8A);
                    host.toast("壁纸下载失败，请重试");
                });
            }
        });
    }

    private void applyReadyFile(OnlineWallpaperStore.Item item, File staged, long sessionRev) {
        String label = "在线 · " + item.title;
        if (session != null) {
            if (session.imported(staged, sessionRev)) {
                session.draft.strings.put("wallpaper_label", label);
                setStatus("已预览「" + item.title + "」，返回 Magic Desktop 点「保存」即可生效。", 0xFF63E69A);
                host.toast("壁纸已预览，请在 Magic Desktop 保存");
            }
            return;
        }
        try {
            File published = WallpaperFile.publish(staged, host.getFilesDir());
            host.preferences.edit()
                    .putString("wallpaper", Uri.fromFile(published).toString())
                    .putString("wallpaper_label", label)
                    .apply();
            host.loadWallpaper();
            setStatus("已设为桌面壁纸：「" + item.title + "」（已缓存到本地，断网也可用）", 0xFF63E69A);
            host.toast("已应用壁纸：" + item.title);
        } catch (Exception error) {
            staged.delete();
            setStatus("保存壁纸失败，请检查存储空间后重试。", 0xFFFF9B8A);
            host.toast("壁纸保存失败");
        }
    }

    private void clearCache() {
        if (downloadingId != null) {
            host.toast("正在下载中，请稍后再清理");
            return;
        }
        net.execute(() -> {
            long reclaimed = store.clearCache();
            host.handler.post(() -> {
                if (closed.get() || host.isDestroyed()) return;
                for (Bitmap b : thumbMemory.values()) if (b != null && !b.isRecycled()) b.recycle();
                thumbMemory.clear();
                previewImage.setImageBitmap(null);
                previewPlaceholder.setText("缓存已清理");
                previewPlaceholder.setVisibility(View.VISIBLE);
                refreshAllPills();
                setStatus("已清理在线壁纸缓存 " + WallpaperCatalogPolicy.formatSize(reclaimed) + "（当前桌面壁纸不受影响）", 0xFF9EB4CC);
                host.toast("已清理在线壁纸缓存");
            });
        });
    }

    private void cleanup() {
        if (!closed.compareAndSet(false, true)) return;
        store.cancelActive();
        net.shutdownNow();
        thumbs.shutdownNow();
        previewImage.setImageBitmap(null);
        for (Bitmap b : thumbMemory.values()) if (b != null && !b.isRecycled()) b.recycle();
        thumbMemory.clear();
    }
}
