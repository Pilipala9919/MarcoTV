// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.app.Dialog;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.*;
import android.os.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.*;

/**
 * Lightweight 3-in-1 TV casting coordinator:
 * 1) DLNA / UPnP MediaRenderer (delegating transport & security to DlnaServer + CastProtocol)
 * 2) Marco Connect Web Quick Cast (photos, local videos via staged cache, HTTP/HLS URLs, remote control)
 * 3) System / OEM wireless display bridge (Miracast / Wi-Fi Display via SystemMirroringLauncher)
 */
final class CastManager implements DlnaServer.SoapHandler {
    static final int MODE_OFF = 0;
    static final int MODE_WHEN_OPEN = 1;
    static final int MODE_ALWAYS = 2;
    static final String[] MODE_LABELS = {
            "关闭 DLNA 发现",
            "仅打开互联或投屏页时可发现（推荐）",
            "桌面运行时始终可发现"
    };

    private final MainActivity host;
    private final SharedPreferences prefs;
    private final Set<String> trustedIps = Collections.synchronizedSet(new HashSet<String>());
    private DlnaServer server;
    private boolean openingPage;

    // Playback state shared with DLNA SOAP queries
    private volatile String currentUri = "";
    private volatile String nextUri = "";
    private volatile String currentTitle = "";
    private volatile String currentSource = "";
    private volatile String currentOwnerIp = "";
    private volatile String transportState = "STOPPED";
    private volatile int currentDurationMs;
    private volatile int currentPositionMs;
    private volatile boolean isPhotoCast;

    // Active full-screen cast UI
    private Dialog playerDialog;
    private VideoView videoView;
    private ImageView photoView;
    private Bitmap currentPhoto;
    private int photoRotation;
    private boolean fillScreen;
    private int videoWidth, videoHeight;
    private View osdBar;
    private TextView osdTitle, osdSub, osdTime, bufferingView;
    private ProgressBar osdProgress;
    private File stagedVideoFile;
    private long externalHandoffAt;

    private final Runnable progressTicker = new Runnable() {
        @Override public void run() {
            if (!host.foreground || playerDialog == null || !playerDialog.isShowing()) return;
            if (!isPhotoCast && videoView != null) {
                try {
                    if (videoView.isPlaying()) {
                        currentPositionMs = Math.max(0, videoView.getCurrentPosition());
                        int dur = videoView.getDuration();
                        if (dur > 0) currentDurationMs = dur;
                        transportState = "PLAYING";
                    }
                } catch (RuntimeException ignored) {}
                updateOsdTime();
            }
            host.handler.postDelayed(this, 500);
        }
    };

    private final Runnable hideOsd = new Runnable() {
        @Override public void run() {
            if (osdBar != null && !isPhotoCast && videoView != null && videoView.isPlaying()) {
                osdBar.setVisibility(View.GONE);
            }
        }
    };

    CastManager(MainActivity host) {
        this.host = host;
        this.prefs = host.getSharedPreferences("cast", Context.MODE_PRIVATE);
        cleanStaleUploads();
    }

    private void cleanStaleUploads() {
        UpdateApkProvider.clearCastFile();
        File[] stale = host.getCacheDir().listFiles(
                f -> f.isFile() && f.getName().startsWith("cast-upload-"));
        if (stale != null) {
            for (File f : stale) f.delete();
        }
    }

    int mode() {
        int m = prefs.getInt("cast_mode", MODE_WHEN_OPEN);
        return Math.max(MODE_OFF, Math.min(MODE_ALWAYS, m));
    }

    boolean confirmUntrusted() {
        return prefs.getBoolean("cast_confirm", true);
    }

    String udn() {
        String id = prefs.getString("cast_uuid", "");
        if (id == null || id.length() < 16) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString("cast_uuid", id).apply();
        }
        return id;
    }

    String friendlyName() {
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        return model.isEmpty() ? "Marco TV" : "Marco TV (" + model + ")";
    }

    boolean isPlaying() {
        return playerDialog != null && playerDialog.isShowing();
    }

    private boolean isCastStackOpen() {
        return openingPage
                || host.dialogs.hasPage("cast")
                || host.dialogs.hasPage("cast_waiting")
                || host.dialogs.hasPage("cast_mirror_list")
                || host.dialogs.hasPage("cast_mirror_none")
                || host.dialogs.hasPage("cast_approve");
    }

    void resume() {
        if (externalHandoffAt > 0 && SystemClock.elapsedRealtime() - externalHandoffAt > 30 * 60000L) {
            clearStagedVideo();
        }
        syncReceiver();
        if (isPlaying() && !isPhotoCast) {
            host.handler.removeCallbacks(progressTicker);
            host.handler.post(progressTicker);
            showOsd(false);
        }
    }

    void pause() {
        host.handler.removeCallbacks(progressTicker);
        host.handler.removeCallbacks(hideOsd);
        if (isPlaying() && !isPhotoCast && videoView != null) {
            try {
                if (videoView.isPlaying()) {
                    videoView.pause();
                    transportState = "PAUSED_PLAYBACK";
                }
            } catch (RuntimeException ignored) {}
        }
        syncReceiver();
    }

    void destroy() {
        stopPlayback(false);
        clearStagedVideo();
        stopReceiver();
    }

    private void clearStagedVideo() {
        externalHandoffAt = 0;
        UpdateApkProvider.clearCastFile();
        if (stagedVideoFile != null) {
            stagedVideoFile.delete();
            stagedVideoFile = null;
        }
    }

    void trustIp(String ip) {
        if (ip != null && !ip.isEmpty()) trustedIps.add(ip);
    }

    synchronized void syncReceiver() {
        int m = mode();
        boolean phoneOpen = host.phone != null && host.phone.active();
        boolean shouldRun = host.foreground && (m == MODE_ALWAYS
                || (m == MODE_WHEN_OPEN && (isCastStackOpen() || phoneOpen || isPlaying())));
        if (!shouldRun) {
            stopReceiver();
            return;
        }
        String ip = localAddress();
        if (ip == null) {
            stopReceiver();
            return;
        }
        if (server == null || !ip.equals(server.ip())) {
            stopReceiver();
            try {
                server = new DlnaServer(host, host.handler, ip, udn(), friendlyName(), this);
            } catch (Exception e) {
                stopReceiver();
            }
        }
    }

    private synchronized void stopReceiver() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    private synchronized void refreshReceiver() {
        stopReceiver();
        syncReceiver();
        if (server != null) server.announceAlive();
    }

    void show() {
        openingPage = true;
        try {
            syncReceiver();
            LinearLayout root = new LinearLayout(host);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setFocusable(false);
            root.setClipChildren(false);
            root.setClipToPadding(false);
            root.setPadding(dp(2), dp(2), dp(2), dp(2));

            LinearLayout banner = new LinearLayout(host);
            banner.setOrientation(LinearLayout.HORIZONTAL);
            banner.setGravity(Gravity.CENTER_VERTICAL);
            banner.setPadding(dp(16), dp(10), dp(16), dp(10));
            GradientDrawable bannerBg = new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0xFF233040, 0xFF1C2532});
            bannerBg.setCornerRadius(dp(14));
            bannerBg.setStroke(dp(1), 0x2EA9DCE2);
            banner.setBackground(bannerBg);

            final CastGraphicView beaconView = new CastGraphicView(host, CastGraphicView.KIND_STATUS_BEACON, server != null);
            banner.addView(beaconView, new LinearLayout.LayoutParams(dp(42), dp(42)));

            LinearLayout bannerText = new LinearLayout(host);
            bannerText.setOrientation(LinearLayout.VERTICAL);
            bannerText.setPadding(dp(14), 0, dp(12), 0);
            final TextView bannerTitle = new TextView(host);
            bannerTitle.setTextColor(Color.WHITE);
            bannerTitle.setTextSize(15);
            bannerTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            bannerTitle.setSingleLine(true);
            bannerTitle.setEllipsize(TextUtils.TruncateAt.END);
            bannerTitle.setIncludeFontPadding(false);
            bannerText.addView(bannerTitle);

            final TextView bannerSub = new TextView(host);
            bannerSub.setTextColor(0xFFA8B8CA);
            bannerSub.setTextSize(12);
            bannerSub.setSingleLine(true);
            bannerSub.setEllipsize(TextUtils.TruncateAt.END);
            bannerSub.setIncludeFontPadding(false);
            bannerSub.setPadding(0, dp(3), 0, 0);
            bannerText.addView(bannerSub);
            banner.addView(bannerText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            final TextView statusPill = new TextView(host);
            statusPill.setTextSize(12);
            statusPill.setSingleLine(true);
            statusPill.setIncludeFontPadding(false);
            statusPill.setPadding(dp(12), dp(5), dp(12), dp(5));
            banner.addView(statusPill, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(banner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));

            LinearLayout cardsRow = new LinearLayout(host);
            cardsRow.setOrientation(LinearLayout.HORIZONTAL);
            cardsRow.setFocusable(false);
            cardsRow.setClipChildren(false);
            cardsRow.setClipToPadding(false);
            LinearLayout.LayoutParams cardsRowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(156));
            cardsRowLp.topMargin = dp(10);

            int[] cardIds = {View.generateViewId(), View.generateViewId(), View.generateViewId()};
            int[] switchIds = {View.generateViewId(), View.generateViewId(), View.generateViewId()};

            String[] cardTitles = {"视频 App 投屏", "手机扫码快投", "系统无线镜像"};
            String[] cardSubs = {"B站 · 爱优腾 · 抖音 · 夸克", "相册照片 · 本地视频 · 链接", "Miracast · 无线显示组件"};
            String[] cardBadges = {"进入接收页", "浏览器扫码直投", "开启整屏镜像"};
            int[] cardKinds = {CastGraphicView.KIND_CARD_DLNA, CastGraphicView.KIND_CARD_PHONE, CastGraphicView.KIND_CARD_MIRROR};

            for (int i = 0; i < 3; i++) {
                final int idx = i;
                LinearLayout card = new LinearLayout(host);
                card.setId(cardIds[i]);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setGravity(Gravity.CENTER_HORIZONTAL);
                card.setPadding(dp(12), dp(12), dp(12), dp(10));
                card.setFocusable(true);
                card.setClickable(true);

                final GradientDrawable cardBg = new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{0xFF2A394A, 0xFF1E2834});
                cardBg.setCornerRadius(dp(16));
                cardBg.setStroke(dp(1), 0x24FFFFFF);
                card.setBackground(cardBg);

                CastGraphicView illustration = new CastGraphicView(host, cardKinds[i], true);
                card.addView(illustration, new LinearLayout.LayoutParams(dp(54), dp(54)));

                TextView ct = new TextView(host);
                ct.setText(cardTitles[i]);
                ct.setTextColor(Color.WHITE);
                ct.setTextSize(16);
                ct.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                ct.setSingleLine(true);
                ct.setIncludeFontPadding(false);
                ct.setPadding(0, dp(8), 0, dp(3));
                card.addView(ct, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                TextView cs = new TextView(host);
                cs.setText(cardSubs[i]);
                cs.setTextColor(0xFFA8B9CC);
                cs.setTextSize(12);
                cs.setSingleLine(true);
                cs.setEllipsize(TextUtils.TruncateAt.END);
                cs.setIncludeFontPadding(false);
                card.addView(cs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                TextView badge = new TextView(host);
                badge.setText(cardBadges[i]);
                badge.setTextColor(0xFF8BE8F5);
                badge.setTextSize(11);
                badge.setSingleLine(true);
                badge.setIncludeFontPadding(false);
                badge.setPadding(dp(10), dp(3), dp(10), dp(3));
                final GradientDrawable badgeBg = new GradientDrawable();
                badgeBg.setCornerRadius(dp(10));
                badgeBg.setColor(0x245CE1E6);
                badge.setBackground(badgeBg);
                LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                badgeLp.topMargin = dp(8);
                card.addView(badge, badgeLp);

                if (i > 0) card.setNextFocusLeftId(cardIds[i - 1]);
                if (i < 2) card.setNextFocusRightId(cardIds[i + 1]);
                card.setNextFocusDownId(switchIds[i]);

                card.setOnFocusChangeListener((v, hasFocus) -> {
                    cardBg.setColors(hasFocus ? new int[]{0xFF3A586C, 0xFF294152} : new int[]{0xFF2A394A, 0xFF1E2834});
                    cardBg.setStroke(dp(1), hasFocus ? 0xFFABD6DF : 0x24FFFFFF);
                    badgeBg.setColor(hasFocus ? 0x405CE1E6 : 0x245CE1E6);
                });
                card.setOnClickListener(v -> {
                    if (idx == 0) showWaitingPage();
                    else if (idx == 1) host.phone.show();
                    else openSystemMirroring();
                });

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                if (i > 0) lp.leftMargin = dp(10);
                cardsRow.addView(card, lp);
            }
            root.addView(cardsRow, cardsRowLp);

            LinearLayout switchRow = new LinearLayout(host);
            switchRow.setOrientation(LinearLayout.HORIZONTAL);
            switchRow.setFocusable(false);
            switchRow.setClipChildren(false);
            switchRow.setClipToPadding(false);
            LinearLayout.LayoutParams switchRowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68));
            switchRowLp.topMargin = dp(10);

            final CastGraphicView[] switchIcons = new CastGraphicView[3];
            final TextView[] switchSubs = new TextView[3];
            final ImageView[] switchToggles = new ImageView[3];
            String[] switchTitles = {"DLNA 投屏服务", "桌面常驻可发现", "陌生设备确认"};
            int[] switchKinds = {
                    CastGraphicView.KIND_ICON_DLNA_SWITCH,
                    CastGraphicView.KIND_ICON_RADAR_SWITCH,
                    CastGraphicView.KIND_ICON_SHIELD_SWITCH
            };

            final Runnable refreshControls = () -> {
                String ip = localAddress();
                int m = mode();
                DlnaServer activeSrv = server;
                boolean running = activeSrv != null;
                boolean dlnaEnabled = m != MODE_OFF;
                boolean alwaysOn = m == MODE_ALWAYS;
                boolean confirmOn = confirmUntrusted();
                boolean portBusy = running && !activeSrv.ssdpSearchBound();

                beaconView.setActive(running && dlnaEnabled);
                bannerTitle.setText(friendlyName() + (ip != null ? "  ·  " + ip : "  ·  未连接局域网"));
                if (!dlnaEnabled) {
                    bannerSub.setText("DLNA 视频 App 发现已关闭，仍可使用手机扫码快投与系统镜像");
                } else if (portBusy) {
                    bannerSub.setText("系统自带投屏占用了 1900 搜索端口，已启用主动广播兼容模式");
                } else {
                    bannerSub.setText(alwaysOn
                            ? "桌面位于前台时随时可被手机视频 App 搜索并投屏"
                            : "打开投屏页或 Marco 互联时可被手机视频 App 搜索");
                }

                GradientDrawable pillBg = new GradientDrawable();
                pillBg.setCornerRadius(dp(12));
                if (ip == null) {
                    statusPill.setText("● 未连网");
                    statusPill.setTextColor(0xFFFFB86C);
                    pillBg.setColor(0x26FFB86C);
                    pillBg.setStroke(dp(1), 0x66FFB86C);
                } else if (dlnaEnabled && running && portBusy) {
                    statusPill.setText("● 广播兼容");
                    statusPill.setTextColor(0xFFFFD166);
                    pillBg.setColor(0x26FFD166);
                    pillBg.setStroke(dp(1), 0x80FFD166);
                } else if (dlnaEnabled && running) {
                    statusPill.setText("● DLNA 已就绪");
                    statusPill.setTextColor(0xFF63E69A);
                    pillBg.setColor(0x2634C759);
                    pillBg.setStroke(dp(1), 0x8034C759);
                } else {
                    statusPill.setText("● DLNA 已关闭");
                    statusPill.setTextColor(0xFFA0AEC0);
                    pillBg.setColor(0x26718096);
                    pillBg.setStroke(dp(1), 0x55718096);
                }
                statusPill.setBackground(pillBg);

                boolean[] states = {dlnaEnabled, alwaysOn, confirmOn};
                String[] subTexts = {
                        dlnaEnabled ? "已开启 · 允许视频投屏" : "已关闭 · 点击开启",
                        alwaysOn ? "已开启 · 桌面随时可投" : "仅打开投屏/互联页时",
                        confirmOn ? "已开启 · 首次连接弹窗" : "已关闭 · 直接允许播放"
                };
                for (int i = 0; i < 3; i++) {
                    switchIcons[i].setActive(states[i]);
                    switchSubs[i].setText(subTexts[i]);
                    switchSubs[i].setTextColor(states[i] ? 0xFF8CE7BA : 0xFF98A8BC);
                    ToggleDrawable td = new ToggleDrawable(states[i]);
                    td.setBounds(0, 0, dp(46), dp(28));
                    switchToggles[i].setImageDrawable(td);
                }
            };

            for (int i = 0; i < 3; i++) {
                final int idx = i;
                LinearLayout sw = new LinearLayout(host);
                sw.setId(switchIds[i]);
                sw.setOrientation(LinearLayout.HORIZONTAL);
                sw.setGravity(Gravity.CENTER_VERTICAL);
                sw.setPadding(dp(12), dp(8), dp(12), dp(8));
                sw.setFocusable(true);
                sw.setClickable(true);

                final GradientDrawable swBg = new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{0xFF253241, 0xFF1C2530});
                swBg.setCornerRadius(dp(14));
                swBg.setStroke(dp(1), 0x22FFFFFF);
                sw.setBackground(swBg);

                switchIcons[i] = new CastGraphicView(host, switchKinds[i], true);
                sw.addView(switchIcons[i], new LinearLayout.LayoutParams(dp(28), dp(28)));

                LinearLayout swText = new LinearLayout(host);
                swText.setOrientation(LinearLayout.VERTICAL);
                swText.setPadding(dp(10), 0, dp(6), 0);

                TextView st = new TextView(host);
                st.setText(switchTitles[i]);
                st.setTextColor(Color.WHITE);
                st.setTextSize(14);
                st.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                st.setSingleLine(true);
                st.setIncludeFontPadding(false);
                swText.addView(st);

                switchSubs[i] = new TextView(host);
                switchSubs[i].setTextSize(11);
                switchSubs[i].setSingleLine(true);
                switchSubs[i].setEllipsize(TextUtils.TruncateAt.END);
                switchSubs[i].setIncludeFontPadding(false);
                switchSubs[i].setPadding(0, dp(2), 0, 0);
                swText.addView(switchSubs[i]);
                sw.addView(swText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                switchToggles[i] = new ImageView(host);
                switchToggles[i].setScaleType(ImageView.ScaleType.FIT_CENTER);
                sw.addView(switchToggles[i], new LinearLayout.LayoutParams(dp(46), dp(28)));

                if (i > 0) sw.setNextFocusLeftId(switchIds[i - 1]);
                if (i < 2) sw.setNextFocusRightId(switchIds[i + 1]);
                sw.setNextFocusUpId(cardIds[i]);

                sw.setOnFocusChangeListener((v, hasFocus) -> {
                    swBg.setColors(hasFocus ? new int[]{0xFF3A586C, 0xFF294152} : new int[]{0xFF253241, 0xFF1C2530});
                    swBg.setStroke(dp(1), hasFocus ? 0xFFABD6DF : 0x22FFFFFF);
                });
                sw.setOnClickListener(v -> {
                    int m = mode();
                    if (idx == 0) {
                        int next = (m == MODE_OFF) ? MODE_WHEN_OPEN : MODE_OFF;
                        prefs.edit().putInt("cast_mode", next).apply();
                        syncReceiver();
                    } else if (idx == 1) {
                        int next = (m == MODE_ALWAYS) ? MODE_WHEN_OPEN : MODE_ALWAYS;
                        prefs.edit().putInt("cast_mode", next).apply();
                        syncReceiver();
                    } else {
                        prefs.edit().putBoolean("cast_confirm", !confirmUntrusted()).apply();
                    }
                    refreshControls.run();
                });

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                if (i > 0) lp.leftMargin = dp(10);
                switchRow.addView(sw, lp);
            }
            root.addView(switchRow, switchRowLp);
            refreshControls.run();

            TvDialog dialog = new TvDialog.Builder(host).centered().setPageId("cast").setTitle("无线投屏中心")
                    .setView(root)
                    .setNegativeButton("返回", null).create();
            dialog.onReturn(() -> {
                syncReceiver();
                refreshControls.run();
            });
            dialog.onLeave(this::syncReceiver);
            host.showDialog(dialog);
        } finally {
            openingPage = false;
        }
        syncReceiver();
    }

    void showWaitingPage() {
        String ip = localAddress();
        if (ip == null) {
            host.toast("请先连接可用的 Wi-Fi 或有线局域网");
            return;
        }
        openingPage = true;
        try {
            if (mode() == MODE_OFF) prefs.edit().putInt("cast_mode", MODE_WHEN_OPEN).apply();
            syncReceiver();
            DlnaServer activeSrv = server;
            int activePort = activeSrv == null ? 0 : activeSrv.port();
            boolean portBusy = activeSrv != null && !activeSrv.ssdpSearchBound();

            LinearLayout root = new LinearLayout(host);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setFocusable(false);

            LinearLayout radarCard = new LinearLayout(host);
            radarCard.setOrientation(LinearLayout.VERTICAL);
            radarCard.setGravity(Gravity.CENTER);
            radarCard.setPadding(dp(14), dp(14), dp(14), dp(14));
            GradientDrawable radarBg = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xFF233243, 0xFF1A232F});
            radarBg.setCornerRadius(dp(16));
            radarBg.setStroke(dp(1), 0x2EA9DCE2);
            radarCard.setBackground(radarBg);

            CastGraphicView radarHero = new CastGraphicView(host, CastGraphicView.KIND_RADAR_HERO, true);
            radarCard.addView(radarHero, new LinearLayout.LayoutParams(dp(136), dp(136)));

            TextView devName = new TextView(host);
            devName.setText(friendlyName());
            devName.setTextColor(Color.WHITE);
            devName.setTextSize(15);
            devName.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            devName.setSingleLine(true);
            devName.setEllipsize(TextUtils.TruncateAt.END);
            devName.setIncludeFontPadding(false);
            devName.setPadding(0, dp(8), 0, dp(3));
            radarCard.addView(devName);

            TextView addrText = new TextView(host);
            addrText.setText(ip + (activePort > 0 ? ":" + activePort : ""));
            addrText.setTextColor(0xFF9AB0C7);
            addrText.setTextSize(12);
            addrText.setSingleLine(true);
            addrText.setIncludeFontPadding(false);
            radarCard.addView(addrText);

            TextView livePill = new TextView(host);
            livePill.setText(activeSrv == null ? "● 服务启动失败" : portBusy ? "● 主动广播兼容模式" : "● 正在广播 DLNA 信号");
            livePill.setTextColor(activeSrv == null ? 0xFFFF7B72 : portBusy ? 0xFFFFD166 : 0xFF63E69A);
            livePill.setTextSize(11);
            livePill.setSingleLine(true);
            livePill.setIncludeFontPadding(false);
            livePill.setPadding(dp(10), dp(4), dp(10), dp(4));
            GradientDrawable liveBg = new GradientDrawable();
            liveBg.setCornerRadius(dp(10));
            liveBg.setColor(activeSrv == null ? 0x24FF7B72 : portBusy ? 0x24FFD166 : 0x2434C759);
            liveBg.setStroke(dp(1), activeSrv == null ? 0x66FF7B72 : portBusy ? 0x66FFD166 : 0x6634C759);
            livePill.setBackground(liveBg);
            LinearLayout.LayoutParams liveLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            liveLp.topMargin = dp(8);
            radarCard.addView(livePill, liveLp);

            root.addView(radarCard, new LinearLayout.LayoutParams(dp(248), dp(246)));

            LinearLayout stepsCol = new LinearLayout(host);
            stepsCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams stepsColLp = new LinearLayout.LayoutParams(0, dp(246), 1f);
            stepsColLp.leftMargin = dp(12);

            String[] stepTitles = {
                    "连接同一局域网 / Wi-Fi",
                    "打开手机视频 App 播放页",
                    portBusy ? "点击「TV 投屏」（若未搜到请点刷新广播）" : "点击视频画面右上角「TV 投屏」"
            };
            String[] stepDescs = {
                    "确认手机与电视连接在同一路由器下（本机 " + ip + "）",
                    "支持哔哩哔哩、爱奇艺、腾讯视频、优酷、抖音、夸克等",
                    portBusy
                            ? "系统投屏占用了 1900 端口，已定时主动广播「" + friendlyName() + "」"
                            : "在搜索列表中选择「" + friendlyName() + "」即可全屏播放"
            };
            int[] stepKinds = {
                    CastGraphicView.KIND_STEP_WIFI,
                    CastGraphicView.KIND_STEP_VIDEO,
                    CastGraphicView.KIND_STEP_CAST
            };

            for (int i = 0; i < 3; i++) {
                LinearLayout stepCard = new LinearLayout(host);
                stepCard.setOrientation(LinearLayout.HORIZONTAL);
                stepCard.setGravity(Gravity.CENTER_VERTICAL);
                stepCard.setPadding(dp(14), dp(10), dp(14), dp(10));
                GradientDrawable stepBg = new GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{0xFF253242, 0xFF1D2733});
                stepBg.setCornerRadius(dp(14));
                stepBg.setStroke(dp(1), 0x22FFFFFF);
                stepCard.setBackground(stepBg);

                CastGraphicView stepIcon = new CastGraphicView(host, stepKinds[i], true);
                stepCard.addView(stepIcon, new LinearLayout.LayoutParams(dp(42), dp(42)));

                LinearLayout stepText = new LinearLayout(host);
                stepText.setOrientation(LinearLayout.VERTICAL);
                stepText.setPadding(dp(12), 0, 0, 0);

                TextView st = new TextView(host);
                st.setText(stepTitles[i]);
                st.setTextColor(Color.WHITE);
                st.setTextSize(15);
                st.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                st.setSingleLine(true);
                st.setIncludeFontPadding(false);
                stepText.addView(st);

                TextView sd = new TextView(host);
                sd.setText(stepDescs[i]);
                sd.setTextColor(0xFFA9B9CC);
                sd.setTextSize(12);
                sd.setSingleLine(true);
                sd.setEllipsize(TextUtils.TruncateAt.END);
                sd.setIncludeFontPadding(false);
                sd.setPadding(0, dp(3), 0, 0);
                stepText.addView(sd);

                stepCard.addView(stepText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                LinearLayout.LayoutParams stepLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
                if (i > 0) stepLp.topMargin = dp(8);
                stepsCol.addView(stepCard, stepLp);
            }
            root.addView(stepsCol, stepsColLp);

            TvDialog dialog = new TvDialog.Builder(host).centered().setPageId("cast_waiting").setTitle("等待视频 App 投屏")
                    .setView(root)
                    .setPositiveButton("刷新广播信号", (d, w) -> {
                        openingPage = true;
                        try {
                            refreshReceiver();
                        } finally {
                            openingPage = false;
                        }
                        host.toast("已重新广播投屏信号，请在手机端刷新搜索");
                        showWaitingPage();
                    })
                    .setNeutralButton("手机扫码快投", (d, w) -> host.phone.show())
                    .setNeutralButton("系统无线镜像", (d, w) -> openSystemMirroring())
                    .setNegativeButton("返回", null).create();
            dialog.onLeave(this::syncReceiver);
            host.showDialog(dialog);
        } finally {
            openingPage = false;
        }
        syncReceiver();
    }

    void openSystemMirroring() {
        SystemMirroringLauncher.open(host, this::showWaitingPage, this::show);
    }

    JSONObject statusJson() {
        JSONObject o = new JSONObject();
        try {
            DlnaServer activeSrv = server;
            o.put("playing", isPlaying());
            o.put("state", transportState);
            o.put("title", currentTitle);
            o.put("source", currentSource);
            o.put("photo", isPhotoCast);
            o.put("positionSec", currentPositionMs / 1000);
            o.put("durationSec", currentDurationMs / 1000);
            o.put("dlnaActive", activeSrv != null);
            o.put("ssdpSearchBound", activeSrv != null && activeSrv.ssdpSearchBound());
            o.put("deviceName", friendlyName());
        } catch (Exception ignored) {}
        return o;
    }

    void stageAndPlayFile(File file, String title, String sourceIp) {
        if (!host.foreground) {
            if (file != null) file.delete();
            return;
        }
        if (stagedVideoFile != null && !stagedVideoFile.equals(file)) {
            stagedVideoFile.delete();
        }
        externalHandoffAt = 0;
        UpdateApkProvider.clearCastFile();
        stagedVideoFile = file;
        playVideo(Uri.fromFile(file).toString(), title, sourceIp, true);
    }

    void requestCastVideo(final String uri, final String title, final String sourceIp) {
        if (!host.foreground || !CastProtocol.isAllowedMediaUri(uri)) {
            transportState = "STOPPED";
            return;
        }
        boolean trusted = sourceIp != null && trustedIps.contains(sourceIp);
        if (CastProtocol.shouldAutoAllowCast(confirmUntrusted(), trusted)) {
            trustIp(sourceIp);
            playVideo(uri, title, sourceIp, true);
            return;
        }
        String safeTitle = (title == null || title.trim().isEmpty()) ? "视频流" : title.trim();
        if (safeTitle.length() > 100) safeTitle = safeTitle.substring(0, 100);
        final String displayTitle = safeTitle;
        TvDialog approve = new TvDialog.Builder(host).setPageId("cast_approve").setTitle("允许投屏到电视？")
                .setMessage("请求设备：" + (sourceIp == null || sourceIp.isEmpty() ? "局域网设备" : sourceIp)
                        + "\n投射内容：" + displayTitle
                        + "\n\n允许后本次运行期间不再重复询问该设备。")
                .setPositiveButton("允许并播放", (d, w) -> {
                    trustIp(sourceIp);
                    playVideo(uri, displayTitle, sourceIp, true);
                })
                .setNegativeButton("拒绝", (d, w) -> transportState = "STOPPED")
                .create();
        approve.onLeave(this::syncReceiver);
        host.showDialog(approve);
    }

    void playVideo(String uri, String title, String sourceIp, boolean trusted) {
        if (!host.foreground || uri == null || uri.isEmpty()) return;
        if (!uri.startsWith("file:") && !CastProtocol.isAllowedMediaUri(uri)) return;
        if (!uri.startsWith("file:")) {
            clearStagedVideo();
        }
        if (trusted && sourceIp != null) trustIp(sourceIp);
        host.home.setIdle(false);
        host.exitMenus();
        isPhotoCast = false;
        currentUri = uri;
        currentOwnerIp = sourceIp == null ? "" : sourceIp;
        currentTitle = (title == null || title.trim().isEmpty()) ? "投屏视频" : title.trim();
        currentSource = (sourceIp == null || sourceIp.isEmpty()) ? "无线投屏" : "来自 " + sourceIp;
        currentPositionMs = 0;
        currentDurationMs = 0;
        transportState = "PLAYING";
        ensurePlayerDialog();
        host.screensaver.cancel();
        if (photoView != null) photoView.setVisibility(View.GONE);
        if (videoView != null) {
            videoView.setVisibility(View.VISIBLE);
            if (bufferingView != null) {
                bufferingView.setText("正在加载投屏视频…\n" + currentTitle);
                bufferingView.setVisibility(View.VISIBLE);
            }
            try {
                videoView.stopPlayback();
                videoView.setVideoURI(Uri.parse(uri));
                videoView.start();
            } catch (RuntimeException e) {
                Diagnostics.event("CAST_VIDEO_START_FAILED");
                offerExternalPlayer("无法直接播放此视频流，可能包含特殊编码。");
                return;
            }
        }
        showOsd(true);
        host.handler.removeCallbacks(progressTicker);
        host.handler.post(progressTicker);
        syncReceiver();
        Diagnostics.event("CAST_VIDEO_STARTED");
    }

    void showPhoto(Bitmap bitmap, String title, String sourceIp) {
        if (bitmap == null || bitmap.isRecycled()) return;
        if (!host.foreground) {
            bitmap.recycle();
            return;
        }
        clearStagedVideo();
        trustIp(sourceIp);
        host.home.setIdle(false);
        host.exitMenus();
        isPhotoCast = true;
        photoRotation = 0;
        currentUri = "";
        nextUri = "";
        currentOwnerIp = sourceIp == null ? "" : sourceIp;
        currentTitle = (title == null || title.trim().isEmpty()) ? "投屏照片" : title.trim();
        currentSource = (sourceIp == null || sourceIp.isEmpty()) ? "手机快投照片" : "来自 " + sourceIp + " · 照片投屏";
        currentPositionMs = 0;
        currentDurationMs = 0;
        transportState = "PLAYING";
        ensurePlayerDialog();
        host.screensaver.cancel();
        if (videoView != null) {
            try { videoView.stopPlayback(); } catch (RuntimeException ignored) {}
            videoView.setVisibility(View.GONE);
        }
        if (bufferingView != null) bufferingView.setVisibility(View.GONE);
        Bitmap old = currentPhoto;
        currentPhoto = bitmap;
        if (photoView != null) {
            photoView.setVisibility(View.VISIBLE);
            photoView.setRotation(0);
            photoView.setScaleType(fillScreen ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
            photoView.setImageBitmap(bitmap);
        }
        if (old != null && old != bitmap && !old.isRecycled()) old.recycle();
        showOsd(true);
        syncReceiver();
        Diagnostics.event("CAST_PHOTO_SHOWN");
    }

    void remoteControl(String cmd) {
        if (cmd == null) return;
        switch (cmd) {
            case "toggle":
                if (host.foreground) togglePlayPause();
                break;
            case "play":
                if (host.foreground) resumePlayback();
                break;
            case "pause":
                pausePlayback();
                break;
            case "stop":
                stopPlayback(false);
                break;
            case "seek_back":
                if (host.foreground) seekByMs(-15000);
                break;
            case "seek_forward":
                if (host.foreground) seekByMs(15000);
                break;
            case "vol_up":
                if (host.foreground) adjustVolume(1);
                break;
            case "vol_down":
                if (host.foreground) adjustVolume(-1);
                break;
            case "aspect":
            case "rotate":
                if (!host.foreground) break;
                if (isPhotoCast && photoView != null) {
                    photoRotation = (photoRotation + 90) % 360;
                    photoView.setRotation(photoRotation);
                    showOsd(true);
                } else {
                    fillScreen = !fillScreen;
                    updateVideoLayout();
                    host.toast(fillScreen ? "画面比例：铺满全屏" : "画面比例：原始比例");
                }
                break;
        }
    }

    private void togglePlayPause() {
        if (!isPlaying()) return;
        if (isPhotoCast) {
            fillScreen = !fillScreen;
            if (photoView != null) {
                photoView.setScaleType(fillScreen ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
            }
            showOsd(true);
            return;
        }
        if (videoView == null) return;
        try {
            if (videoView.isPlaying()) {
                videoView.pause();
                transportState = "PAUSED_PLAYBACK";
            } else {
                videoView.start();
                transportState = "PLAYING";
            }
        } catch (RuntimeException ignored) {}
        showOsd(true);
    }

    private void resumePlayback() {
        if (!host.foreground || !isPlaying() || isPhotoCast || videoView == null) return;
        try {
            if (!videoView.isPlaying()) videoView.start();
            transportState = "PLAYING";
            showOsd(true);
        } catch (RuntimeException ignored) {}
    }

    private void pausePlayback() {
        if (!isPlaying() || isPhotoCast || videoView == null) return;
        try {
            if (videoView.isPlaying()) videoView.pause();
            transportState = "PAUSED_PLAYBACK";
            showOsd(true);
        } catch (RuntimeException ignored) {}
    }

    private void seekByMs(int deltaMs) {
        if (!isPlaying() || isPhotoCast || videoView == null) return;
        try {
            int dur = videoView.getDuration();
            if (dur <= 0) return;
            int target = Math.max(0, Math.min(dur, videoView.getCurrentPosition() + deltaMs));
            videoView.seekTo(target);
            currentPositionMs = target;
            currentDurationMs = dur;
            showOsd(true);
        } catch (RuntimeException ignored) {}
    }

    private void seekToMs(int targetMs) {
        if (!isPlaying() || isPhotoCast || videoView == null) return;
        try {
            int dur = videoView.getDuration();
            int target = dur > 0 ? Math.max(0, Math.min(dur, targetMs)) : Math.max(0, targetMs);
            videoView.seekTo(target);
            currentPositionMs = target;
            showOsd(true);
        } catch (RuntimeException ignored) {}
    }

    private void adjustVolume(int direction) {
        try {
            AudioManager audio = (AudioManager) host.getSystemService(Context.AUDIO_SERVICE);
            if (audio != null) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        direction > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI);
            }
        } catch (RuntimeException ignored) {}
    }

    void stopPlayback() {
        stopPlayback(false);
    }

    private void stopPlayback(boolean keepStagedForExternal) {
        host.handler.removeCallbacks(progressTicker);
        host.handler.removeCallbacks(hideOsd);
        transportState = "STOPPED";
        currentPositionMs = 0;
        currentDurationMs = 0;
        nextUri = "";
        if (!keepStagedForExternal) {
            currentUri = "";
            currentOwnerIp = "";
            clearStagedVideo();
        }
        if (videoView != null) {
            try { videoView.stopPlayback(); } catch (RuntimeException ignored) {}
        }
        dismissPlayerWindowOnly();
        if (currentPhoto != null) {
            if (photoView != null) photoView.setImageBitmap(null);
            if (!currentPhoto.isRecycled()) currentPhoto.recycle();
            currentPhoto = null;
        }
        syncReceiver();
        host.armIdle();
    }

    private void dismissPlayerWindowOnly() {
        if (playerDialog != null) {
            Dialog d = playerDialog;
            playerDialog = null;
            d.setOnDismissListener(null);
            if (d.isShowing()) {
                try { d.dismiss(); } catch (RuntimeException ignored) {}
            }
        }
    }

    private int dp(float v) {
        return Math.round(v * host.getResources().getDisplayMetrics().density);
    }

    private void ensurePlayerDialog() {
        if (playerDialog != null && playerDialog.isShowing()) return;
        playerDialog = new Dialog(host, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                int key = event.getKeyCode();
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                            || key == KeyEvent.KEYCODE_NUMPAD_ENTER || key == KeyEvent.KEYCODE_BUTTON_A
                            || key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                        togglePlayPause();
                        return true;
                    }
                    if (key == KeyEvent.KEYCODE_MEDIA_PLAY) {
                        resumePlayback();
                        return true;
                    }
                    if (key == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                        pausePlayback();
                        return true;
                    }
                    if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                            || key == KeyEvent.KEYCODE_MEDIA_REWIND || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                        boolean forward = key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
                        if (isPhotoCast && photoView != null) {
                            photoRotation = (photoRotation + (forward ? 90 : 270)) % 360;
                            photoView.setRotation(photoRotation);
                            showOsd(true);
                        } else {
                            int step = event.getRepeatCount() > 4 ? 30000 : 15000;
                            seekByMs(forward ? step : -step);
                        }
                        return true;
                    }
                    if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                        adjustVolume(key == KeyEvent.KEYCODE_DPAD_UP ? 1 : -1);
                        showOsd(true);
                        return true;
                    }
                    if (key == KeyEvent.KEYCODE_MENU) {
                        showPlayerMenu();
                        return true;
                    }
                }
                if (key == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    stopPlayback(false);
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        Window w = playerDialog.getWindow();
        if (w != null) {
            w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            w.getDecorView().setSystemUiVisibility(5894);
        }
        FrameLayout root = new FrameLayout(host);
        root.setBackgroundColor(Color.BLACK);
        root.setOnClickListener(v -> showOsd(true));

        videoView = new VideoView(host);
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
        root.addView(videoView, vlp);

        photoView = new ImageView(host);
        photoView.setVisibility(View.GONE);
        root.addView(photoView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        bufferingView = new TextView(host);
        bufferingView.setTextColor(0xFFE5EDF5);
        bufferingView.setTextSize(17);
        bufferingView.setGravity(Gravity.CENTER);
        bufferingView.setPadding(dp(24), dp(16), dp(24), dp(16));
        bufferingView.setBackground(TvDialog.background(0xCC18222D, dp(16)));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(bufferingView, blp);

        LinearLayout osd = new LinearLayout(host);
        osd.setOrientation(LinearLayout.VERTICAL);
        osd.setPadding(dp(24), dp(18), dp(24), dp(18));
        GradientDrawable osdBg = TvDialog.background(0xE0161E27, dp(20));
        osdBg.setStroke(dp(1), 0x28FFFFFF);
        osd.setBackground(osdBg);

        LinearLayout header = new LinearLayout(host);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        osdTitle = new TextView(host);
        osdTitle.setTextColor(Color.WHITE);
        osdTitle.setTextSize(18);
        osdTitle.setTypeface(Typeface.create("sans-serif-medium", 0));
        osdTitle.setSingleLine(true);
        osdTitle.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(osdTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        osdSub = new TextView(host);
        osdSub.setTextColor(0xFFA9DCE2);
        osdSub.setTextSize(13);
        osdSub.setPadding(dp(16), 0, 0, 0);
        header.addView(osdSub, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        osd.addView(header);

        osdProgress = new ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal);
        osdProgress.setMax(1000);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        plp.topMargin = dp(12);
        plp.bottomMargin = dp(8);
        osd.addView(osdProgress, plp);

        osdTime = new TextView(host);
        osdTime.setTextColor(0xFFBCC8D6);
        osdTime.setTextSize(13);
        osd.addView(osdTime);

        FrameLayout.LayoutParams olp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        olp.setMargins(dp(40), 0, dp(40), dp(28));
        root.addView(osd, olp);
        osdBar = osd;

        videoView.setOnPreparedListener(mp -> {
            if (bufferingView != null) bufferingView.setVisibility(View.GONE);
            videoWidth = mp.getVideoWidth();
            videoHeight = mp.getVideoHeight();
            int dur = videoView.getDuration();
            if (dur > 0) currentDurationMs = dur;
            transportState = "PLAYING";
            updateVideoLayout();
            mp.setOnVideoSizeChangedListener((player, width, height) -> {
                videoWidth = width;
                videoHeight = height;
                updateVideoLayout();
            });
            mp.setOnInfoListener((player, what, extra) -> {
                if (bufferingView != null) {
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                        bufferingView.setText("正在缓冲…");
                        bufferingView.setVisibility(View.VISIBLE);
                    } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END
                            || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        bufferingView.setVisibility(View.GONE);
                    }
                }
                return false;
            });
            showOsd(true);
        });
        videoView.setOnCompletionListener(mp -> {
            String next = nextUri;
            nextUri = "";
            if (next != null && CastProtocol.isAllowedMediaUri(next)) {
                playVideo(next, currentTitle + "（下一集）", currentOwnerIp, true);
                return;
            }
            transportState = "STOPPED";
            showOsd(true);
        });
        videoView.setOnErrorListener((mp, what, extra) -> {
            if (bufferingView != null) bufferingView.setVisibility(View.GONE);
            transportState = "STOPPED";
            offerExternalPlayer("内置播放器暂不支持此视频流的编码格式（错误码 " + what + "）。");
            return true;
        });

        playerDialog.setContentView(root);
        playerDialog.setOnDismissListener(d -> {
            playerDialog = null;
            transportState = "STOPPED";
            host.handler.removeCallbacks(progressTicker);
            host.handler.removeCallbacks(hideOsd);
            syncReceiver();
            host.armIdle();
        });
        playerDialog.show();
    }

    private void updateVideoLayout() {
        if (videoView == null) return;
        int screenW = host.getResources().getDisplayMetrics().widthPixels;
        int screenH = host.getResources().getDisplayMetrics().heightPixels;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) videoView.getLayoutParams();
        if (fillScreen || videoWidth <= 0 || videoHeight <= 0) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        } else {
            float scale = Math.min(screenW / (float) videoWidth, screenH / (float) videoHeight);
            lp.width = Math.max(1, Math.round(videoWidth * scale));
            lp.height = Math.max(1, Math.round(videoHeight * scale));
        }
        lp.gravity = Gravity.CENTER;
        videoView.setLayoutParams(lp);
    }

    private void showOsd(boolean autoHide) {
        if (osdBar == null) return;
        osdBar.setVisibility(View.VISIBLE);
        if (osdTitle != null) osdTitle.setText(currentTitle);
        if (osdSub != null) {
            String stateLabel = "PAUSED_PLAYBACK".equals(transportState) ? "已暂停 · " : "";
            osdSub.setText(stateLabel + currentSource);
        }
        updateOsdTime();
        host.handler.removeCallbacks(hideOsd);
        if (autoHide) host.handler.postDelayed(hideOsd, 4000);
    }

    private void updateOsdTime() {
        if (osdTime == null || osdProgress == null) return;
        if (isPhotoCast) {
            osdProgress.setVisibility(View.GONE);
            osdTime.setText("确定：切换适应/铺满  ·  左右：旋转照片  ·  菜单键：更多  ·  返回：退出投屏");
            return;
        }
        osdProgress.setVisibility(View.VISIBLE);
        int dur = currentDurationMs;
        int pos = currentPositionMs;
        if (dur > 0) {
            osdProgress.setIndeterminate(false);
            osdProgress.setProgress(Math.max(0, Math.min(1000, (int) (pos * 1000L / dur))));
            osdTime.setText(CastProtocol.formatTime(pos) + " / " + CastProtocol.formatTime(dur)
                    + "    ·    确定：暂停/播放  ·  左右：快进快退  ·  上下：音量  ·  菜单键：画面与外部播放");
        } else {
            osdProgress.setProgress(0);
            osdTime.setText("直播或流媒体    ·    确定：暂停/播放  ·  上下：音量  ·  菜单键：画面与外部播放  ·  返回：退出");
        }
    }

    private void showPlayerMenu() {
        if (isPhotoCast) {
            host.showDialog(new TvDialog.Builder(host).setPageId("cast_photo_menu").setTitle("照片投屏选项")
                    .setItems(new String[]{
                            fillScreen ? "切换为完整显示（适应屏幕）" : "切换为铺满全屏（裁切边缘）",
                            "顺时针旋转 90°",
                            "退出投屏"
                    }, (d, w) -> {
                        if (w == 0) remoteControl("toggle");
                        else if (w == 1) remoteControl("rotate");
                        else stopPlayback(false);
                    }).setNegativeButton("返回画面", null).create());
            return;
        }
        final String retryUri = currentUri;
        final String retryTitle = currentTitle;
        final String retryOwner = currentOwnerIp;
        host.showDialog(new TvDialog.Builder(host).setPageId("cast_video_menu").setTitle("投屏播放选项")
                .setMessage(currentTitle + "\n" + currentSource)
                .setItems(new String[]{
                        fillScreen ? "画面比例：切换为原始比例" : "画面比例：切换为铺满全屏",
                        "用外部播放器打开（Kodi / VLC / 系统播放器）",
                        "重新加载当前视频流",
                        "停止并退出投屏"
                }, (d, w) -> {
                    if (w == 0) remoteControl("aspect");
                    else if (w == 1) openInExternalPlayer();
                    else if (w == 2) playVideo(retryUri, retryTitle, retryOwner, true);
                    else stopPlayback(false);
                }).setNegativeButton("继续播放", null).create());
    }

    private void offerExternalPlayer(String reason) {
        final String retryUri = currentUri;
        final String retryTitle = currentTitle;
        final String retryOwner = currentOwnerIp;
        host.handler.removeCallbacks(progressTicker);
        host.handler.removeCallbacks(hideOsd);
        if (videoView != null) {
            try { videoView.stopPlayback(); } catch (RuntimeException ignored) {}
        }
        // Dismiss the black fullscreen player window before showing cast_error so the dialog
        // is never occluded behind playerDialog and Back cleanly returns to the desktop.
        dismissPlayerWindowOnly();
        syncReceiver();
        host.armIdle();

        TvDialog errDialog = new TvDialog.Builder(host).setPageId("cast_error").setTitle("投屏播放提示")
                .setMessage(reason + "\n\n可以调用电视上安装的其他播放器（如 Kodi、VLC、当贝播放器等）继续打开该视频流。")
                .setItems(new String[]{"用外部播放器打开", "重试内置播放", "退出投屏"}, (d, w) -> {
                    if (w == 0) openInExternalPlayer();
                    else if (w == 1) playVideo(retryUri, retryTitle, retryOwner, true);
                    else stopPlayback(false);
                }).setNegativeButton("返回", (d, w) -> stopPlayback(false)).create();
        errDialog.onLeave(() -> {
            if (!isPlaying() && externalHandoffAt == 0) clearStagedVideo();
        });
        host.showDialog(errDialog);
    }

    private void openInExternalPlayer() {
        if (currentUri == null || currentUri.isEmpty()) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            if (currentUri.startsWith("file:")) {
                if (stagedVideoFile == null || !stagedVideoFile.isFile()) {
                    host.toast("本地投屏缓存文件已失效，请重新投射");
                    return;
                }
                Uri contentUri = UpdateApkProvider.stageCastFile(host, stagedVideoFile);
                intent.setDataAndType(contentUri, "video/*");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                host.startActivity(intent);
                externalHandoffAt = SystemClock.elapsedRealtime();
                stopPlayback(true);
            } else {
                intent.setDataAndType(Uri.parse(currentUri), "video/*");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                host.startActivity(intent);
                stopPlayback(false);
            }
        } catch (RuntimeException e) {
            UpdateApkProvider.clearCastFile();
            host.toast("电视未安装可处理此视频流的外部播放器");
        }
    }

    private String localAddress() {
        try {
            ConnectivityManager cm = (ConnectivityManager) host.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                for (Network n : cm.getAllNetworks()) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                    LinkProperties link = cm.getLinkProperties(n);
                    if (caps == null || link == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                            || !(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
                    for (LinkAddress a : link.getLinkAddresses()) {
                        InetAddress ip = a.getAddress();
                        if (ip instanceof Inet4Address && ip.isSiteLocalAddress()) return ip.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Override public DlnaServer.SoapResult onSoapAction(String action, String body, final String remoteIp) {
        String service = CastProtocol.serviceTypeForAction(action);
        boolean trusted = remoteIp != null && trustedIps.contains(remoteIp);
        boolean confirm = confirmUntrusted();
        String inner = "";

        switch (action) {
            case "SetAVTransportURI": {
                final String uri = CastProtocol.extractXmlTag(body, "CurrentURI").trim();
                if (!CastProtocol.isAllowedMediaUri(uri)) {
                    return DlnaServer.SoapResult.fault(716, "Resource not found or unsupported URI scheme");
                }
                if (!host.foreground) {
                    return DlnaServer.SoapResult.fault(701, "Transition not available in background");
                }
                String meta = CastProtocol.extractXmlTag(body, "CurrentURIMetaData");
                String metaTitle = CastProtocol.extractXmlTag(meta, "title");
                final String title = metaTitle.isEmpty() ? "DLNA 投屏视频" : metaTitle;
                if (CastProtocol.shouldAutoAllowCast(confirm, trusted)) {
                    transportState = "TRANSITIONING";
                }
                host.handler.post(() -> requestCastVideo(uri, title, remoteIp));
                break;
            }
            case "SetNextAVTransportURI": {
                if (!CastProtocol.canControlSession(confirm, trusted, isPlaying(), false)) {
                    return DlnaServer.SoapResult.fault(701, "Device not authorized");
                }
                String candidate = CastProtocol.extractXmlTag(body, "NextURI").trim();
                if (!candidate.isEmpty() && !CastProtocol.isAllowedMediaUri(candidate)) {
                    return DlnaServer.SoapResult.fault(716, "Unsupported NextURI scheme");
                }
                nextUri = candidate;
                break;
            }
            case "Play": {
                if (!CastProtocol.canControlSession(confirm, trusted, isPlaying(), false)) {
                    return DlnaServer.SoapResult.fault(701, "Device not authorized");
                }
                host.handler.post(this::resumePlayback);
                break;
            }
            case "Pause": {
                if (!CastProtocol.canControlSession(confirm, trusted, isPlaying(), false)) {
                    return DlnaServer.SoapResult.fault(701, "Device not authorized");
                }
                host.handler.post(this::pausePlayback);
                break;
            }
            case "Stop": {
                if (!CastProtocol.canControlSession(confirm, trusted, isPlaying(), false)) {
                    return DlnaServer.SoapResult.fault(701, "Device not authorized");
                }
                host.handler.post(() -> stopPlayback(false));
                break;
            }
            case "Seek": {
                if (!CastProtocol.canControlSession(confirm, trusted, isPlaying(), false)) {
                    return DlnaServer.SoapResult.fault(701, "Device not authorized");
                }
                String target = CastProtocol.extractXmlTag(body, "Target");
                final int ms = CastProtocol.parseTimeMs(target);
                host.handler.post(() -> seekToMs(ms));
                break;
            }
            case "GetPositionInfo": {
                String dur = CastProtocol.formatTime(currentDurationMs);
                String pos = CastProtocol.formatTime(currentPositionMs);
                String safeUri = CastProtocol.escapeXml(CastProtocol.sanitizeEchoUri(currentUri, currentOwnerIp, remoteIp));
                inner = "<Track>1</Track>"
                        + "<TrackDuration>" + dur + "</TrackDuration>"
                        + "<TrackMetaData></TrackMetaData>"
                        + "<TrackURI>" + safeUri + "</TrackURI>"
                        + "<RelTime>" + pos + "</RelTime>"
                        + "<AbsTime>" + pos + "</AbsTime>"
                        + "<RelCount>2147483647</RelCount>"
                        + "<AbsCount>2147483647</AbsCount>";
                break;
            }
            case "GetTransportInfo": {
                inner = "<CurrentTransportState>" + transportState + "</CurrentTransportState>"
                        + "<CurrentTransportStatus>OK</CurrentTransportStatus>"
                        + "<CurrentSpeed>1</CurrentSpeed>";
                break;
            }
            case "GetMediaInfo": {
                String dur = CastProtocol.formatTime(currentDurationMs);
                String safeCur = CastProtocol.escapeXml(CastProtocol.sanitizeEchoUri(currentUri, currentOwnerIp, remoteIp));
                String safeNext = CastProtocol.escapeXml(CastProtocol.sanitizeEchoUri(nextUri, currentOwnerIp, remoteIp));
                inner = "<NrTracks>1</NrTracks>"
                        + "<MediaDuration>" + dur + "</MediaDuration>"
                        + "<CurrentURI>" + safeCur + "</CurrentURI>"
                        + "<CurrentURIMetaData></CurrentURIMetaData>"
                        + "<NextURI>" + safeNext + "</NextURI>"
                        + "<NextURIMetaData></NextURIMetaData>"
                        + "<PlayMedium>NETWORK</PlayMedium>"
                        + "<RecordMedium>NOT_IMPLEMENTED</RecordMedium>"
                        + "<WriteStatus>NOT_IMPLEMENTED</WriteStatus>";
                break;
            }
            case "GetCurrentTransportActions": {
                inner = "<Actions>Play,Pause,Stop,Seek</Actions>";
                break;
            }
            case "GetProtocolInfo": {
                inner = "<Source></Source>"
                        + "<Sink>http-get:*:video/mp4:*,http-get:*:video/x-mpegURL:*,http-get:*:application/vnd.apple.mpegurl:*,http-get:*:video/*:*,http-get:*:audio/*:*,http-get:*:image/*:*</Sink>";
                break;
            }
            case "GetVolume": {
                int vol = 50;
                try {
                    AudioManager audio = (AudioManager) host.getSystemService(Context.AUDIO_SERVICE);
                    if (audio != null) {
                        int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                        int cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                        vol = Math.round(cur * 100f / max);
                    }
                } catch (RuntimeException ignored) {}
                inner = "<CurrentVolume>" + vol + "</CurrentVolume>";
                break;
            }
            case "SetVolume": {
                if (!host.foreground || !CastProtocol.canControlSession(confirm, trusted, isPlaying(), true)) {
                    return DlnaServer.SoapResult.fault(501, "Volume control requires active authorized cast");
                }
                String desired = CastProtocol.extractXmlTag(body, "DesiredVolume");
                try {
                    final int pct = Math.max(0, Math.min(100, Integer.parseInt(desired)));
                    host.handler.post(() -> {
                        if (!host.foreground) return;
                        try {
                            AudioManager audio = (AudioManager) host.getSystemService(Context.AUDIO_SERVICE);
                            if (audio != null) {
                                int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(pct * max / 100f), AudioManager.FLAG_SHOW_UI);
                            }
                        } catch (RuntimeException ignored) {}
                    });
                } catch (NumberFormatException e) {
                    return DlnaServer.SoapResult.fault(402, "Invalid DesiredVolume");
                }
                break;
            }
            case "GetMute": {
                inner = "<CurrentMute>0</CurrentMute>";
                break;
            }
            case "SetMute": {
                if (!host.foreground || !CastProtocol.canControlSession(confirm, trusted, isPlaying(), true)) {
                    return DlnaServer.SoapResult.fault(501, "Mute control requires active authorized cast");
                }
                break;
            }
            default:
                return DlnaServer.SoapResult.fault(401, "Invalid Action");
        }
        return DlnaServer.SoapResult.ok(service, action, inner);
    }
}
