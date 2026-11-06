// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.app.Dialog;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.*;
import android.net.wifi.WifiManager;
import android.os.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Lightweight 3-in-1 TV casting manager:
 * 1) DLNA / UPnP MediaRenderer (SSDP + AVTransport / RenderingControl SOAP)
 * 2) Marco Connect Web Quick Cast (photos, videos, HTTP/HLS URLs, remote control)
 * 3) System / OEM wireless screen mirroring bridge (Miracast / AirPlay / Wi-Fi Display)
 */
final class CastManager {
    static final int MODE_OFF = 0;
    static final int MODE_WHEN_OPEN = 1;
    static final int MODE_ALWAYS = 2;
    static final String[] MODE_LABELS = {
            "关闭 DLNA 发现",
            "仅打开互联或投屏页时可发现（推荐）",
            "桌面运行时始终可发现"
    };
    private static final String SSDP_ADDRESS = "239.255.255.250";
    private static final int SSDP_PORT = 1900;

    private final MainActivity host;
    private final SharedPreferences prefs;
    private final Set<String> trustedIps = Collections.synchronizedSet(new HashSet<String>());
    private ServerSocket httpServer;
    private MulticastSocket ssdpSocket;
    private WifiManager.MulticastLock multicastLock;
    private ExecutorService ioPool;
    private volatile boolean receiverRunning;
    private volatile String activeIp = "";
    private volatile int activePort;
    private boolean pageOpen;

    // Playback state shared with DLNA SOAP queries
    private volatile String currentUri = "";
    private volatile String nextUri = "";
    private volatile String currentTitle = "";
    private volatile String currentSource = "";
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

    private final Runnable progressTicker = new Runnable() {
        @Override public void run() {
            if (playerDialog == null || !playerDialog.isShowing()) return;
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

    private final Runnable ssdpAliveTicker = new Runnable() {
        @Override public void run() {
            if (!receiverRunning) return;
            final String ip = activeIp;
            final int port = activePort;
            final String uuid = udn();
            if (ioPool != null && !ip.isEmpty() && port > 0) {
                ioPool.execute(() -> sendSsdpNotify(ip, port, uuid, true));
            }
            host.handler.postDelayed(this, 25000);
        }
    };

    CastManager(MainActivity host) {
        this.host = host;
        this.prefs = host.getSharedPreferences("cast", Context.MODE_PRIVATE);
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

    void resume() {
        syncReceiver();
    }

    void destroy() {
        stopPlayback();
        pageOpen = false;
        stopReceiver();
        if (stagedVideoFile != null) {
            stagedVideoFile.delete();
            stagedVideoFile = null;
        }
    }

    void trustIp(String ip) {
        if (ip != null && !ip.isEmpty()) trustedIps.add(ip);
    }

    void syncReceiver() {
        int m = mode();
        boolean phoneOpen = host.phone != null && host.phone.active();
        boolean shouldRun = m == MODE_ALWAYS || (m == MODE_WHEN_OPEN && (pageOpen || phoneOpen || isPlaying()));
        if (shouldRun) {
            String ip = localAddress();
            if (ip == null) {
                stopReceiver();
                return;
            }
            if (!receiverRunning || !ip.equals(activeIp)) {
                stopReceiver();
                startReceiver(ip);
            }
        } else {
            stopReceiver();
        }
    }

    void show() {
        pageOpen = true;
        syncReceiver();
        LinearLayout root = new LinearLayout(host);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFocusable(false);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.setPadding(dp(2), dp(2), dp(2), dp(2));

        // 1. Top Graphical Live Status Banner
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

        final CastGraphicView beaconView = new CastGraphicView(host, CastGraphicView.KIND_STATUS_BEACON, receiverRunning);
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

        // 2. Middle Row: 3 Graphical Feature Cards
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
        String[] cardSubs = {"B站 · 爱优腾 · 抖音 · 夸克", "相册照片 · 本地视频 · 链接", "Miracast · AirPlay 整屏"};
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

        // 3. Bottom Row: 3 Graphical Switch Cards with Real ToggleDrawable Switches
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
        String[] switchTitles = {"DLNA 投屏服务", "后台始终可发现", "陌生设备确认"};
        int[] switchKinds = {
                CastGraphicView.KIND_ICON_DLNA_SWITCH,
                CastGraphicView.KIND_ICON_RADAR_SWITCH,
                CastGraphicView.KIND_ICON_SHIELD_SWITCH
        };

        final Runnable refreshControls = () -> {
            String ip = localAddress();
            int m = mode();
            boolean dlnaEnabled = m != MODE_OFF;
            boolean alwaysOn = m == MODE_ALWAYS;
            boolean confirmOn = confirmUntrusted();

            beaconView.setActive(receiverRunning && dlnaEnabled);
            bannerTitle.setText(friendlyName() + (ip != null ? "  ·  " + ip : "  ·  未连接局域网"));
            bannerSub.setText(dlnaEnabled
                    ? (alwaysOn ? "桌面后台随时可被手机视频 App 搜索并投屏" : "打开投屏页或 Marco 互联时可被手机视频 App 搜索")
                    : "DLNA 视频 App 发现已关闭，仍可使用手机扫码快投与系统镜像");

            GradientDrawable pillBg = new GradientDrawable();
            pillBg.setCornerRadius(dp(12));
            if (ip == null) {
                statusPill.setText("● 未连网");
                statusPill.setTextColor(0xFFFFB86C);
                pillBg.setColor(0x26FFB86C);
                pillBg.setStroke(dp(1), 0x66FFB86C);
            } else if (dlnaEnabled && receiverRunning) {
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
        dialog.onLeave(() -> {
            pageOpen = host.isPage("cast") || host.isPage("cast_waiting") || host.isPage("cast_mode");
            syncReceiver();
        });
        host.showDialog(dialog);
    }

    void showWaitingPage() {
        pageOpen = true;
        if (mode() == MODE_OFF) prefs.edit().putInt("cast_mode", MODE_WHEN_OPEN).apply();
        syncReceiver();
        String ip = localAddress();
        if (ip == null) {
            host.toast("请先连接可用的 Wi-Fi 或有线局域网");
            return;
        }
        LinearLayout root = new LinearLayout(host);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setFocusable(false);

        // Left Radar Hero Card
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
        livePill.setText("● 正在广播 DLNA 信号");
        livePill.setTextColor(0xFF63E69A);
        livePill.setTextSize(11);
        livePill.setSingleLine(true);
        livePill.setIncludeFontPadding(false);
        livePill.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable liveBg = new GradientDrawable();
        liveBg.setCornerRadius(dp(10));
        liveBg.setColor(0x2434C759);
        liveBg.setStroke(dp(1), 0x6634C759);
        livePill.setBackground(liveBg);
        LinearLayout.LayoutParams liveLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        liveLp.topMargin = dp(8);
        radarCard.addView(livePill, liveLp);

        root.addView(radarCard, new LinearLayout.LayoutParams(dp(248), dp(246)));

        // Right 3 Visual Step Cards
        LinearLayout stepsCol = new LinearLayout(host);
        stepsCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams stepsColLp = new LinearLayout.LayoutParams(0, dp(246), 1f);
        stepsColLp.leftMargin = dp(12);

        String[] stepTitles = {
                "连接同一局域网 / Wi-Fi",
                "打开手机视频 App 播放页",
                "点击视频画面右上角「TV 投屏」"
        };
        String[] stepDescs = {
                "确认手机与电视连接在同一路由器下（本机 " + ip + "）",
                "支持哔哩哔哩、爱奇艺、腾讯视频、优酷、抖音、夸克等",
                "在搜索列表中选择「" + friendlyName() + "」即可全屏播放"
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
                    stopReceiver();
                    syncReceiver();
                    host.toast("已重新广播投屏信号，请在手机端刷新搜索");
                    showWaitingPage();
                })
                .setNeutralButton("手机扫码快投", (d, w) -> host.phone.show())
                .setNeutralButton("系统无线镜像", (d, w) -> openSystemMirroring())
                .setNegativeButton("返回", (d, w) -> show()).create();
        dialog.onLeave(() -> {
            pageOpen = host.isPage("cast") || host.isPage("cast_waiting");
            syncReceiver();
        });
        host.showDialog(dialog);
    }

    JSONObject statusJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("playing", isPlaying());
            o.put("state", transportState);
            o.put("title", currentTitle);
            o.put("source", currentSource);
            o.put("photo", isPhotoCast);
            o.put("positionSec", currentPositionMs / 1000);
            o.put("durationSec", currentDurationMs / 1000);
            o.put("dlnaActive", receiverRunning);
            o.put("deviceName", friendlyName());
        } catch (Exception ignored) {}
        return o;
    }

    void stageAndPlayFile(File file, String title, String sourceIp) {
        if (stagedVideoFile != null && !stagedVideoFile.equals(file)) {
            stagedVideoFile.delete();
        }
        stagedVideoFile = file;
        playVideo(Uri.fromFile(file).toString(), title, sourceIp, true);
    }

    void requestCastVideo(final String uri, final String title, final String sourceIp) {
        if (uri == null || !(uri.startsWith("http://") || uri.startsWith("https://"))) return;
        boolean autoAllow = !confirmUntrusted()
                || trustedIps.contains(sourceIp)
                || host.isPage("cast_waiting")
                || host.isPage("cast")
                || (host.phone != null && host.phone.active())
                || isPlaying();
        if (autoAllow) {
            trustIp(sourceIp);
            playVideo(uri, title, sourceIp, true);
            return;
        }
        host.showDialog(new TvDialog.Builder(host).setPageId("cast_approve").setTitle("允许投屏到电视？")
                .setMessage("请求设备：" + (sourceIp == null || sourceIp.isEmpty() ? "局域网设备" : sourceIp)
                        + "\n投射内容：" + (title == null || title.isEmpty() ? "视频流" : title)
                        + "\n\n允许后本次运行期间不再重复询问该设备。")
                .setPositiveButton("允许并播放", (d, w) -> {
                    trustIp(sourceIp);
                    playVideo(uri, title, sourceIp, true);
                })
                .setNegativeButton("拒绝", (d, w) -> transportState = "STOPPED")
                .create());
    }

    void playVideo(String uri, String title, String sourceIp, boolean trusted) {
        if (trusted && sourceIp != null) trustIp(sourceIp);
        host.home.setIdle(false);
        host.exitMenus();
        isPhotoCast = false;
        currentUri = uri;
        currentTitle = (title == null || title.trim().isEmpty()) ? "投屏视频" : title.trim();
        currentSource = (sourceIp == null || sourceIp.isEmpty()) ? "无线投屏" : "来自 " + sourceIp;
        currentPositionMs = 0;
        currentDurationMs = 0;
        transportState = "PLAYING";
        ensurePlayerDialog();
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
            }
        }
        showOsd(true);
        host.handler.removeCallbacks(progressTicker);
        host.handler.post(progressTicker);
        Diagnostics.event("CAST_VIDEO_STARTED");
    }

    void showPhoto(Bitmap bitmap, String title, String sourceIp) {
        if (bitmap == null || bitmap.isRecycled()) return;
        trustIp(sourceIp);
        host.home.setIdle(false);
        host.exitMenus();
        isPhotoCast = true;
        photoRotation = 0;
        currentUri = "";
        currentTitle = (title == null || title.trim().isEmpty()) ? "投屏照片" : title.trim();
        currentSource = (sourceIp == null || sourceIp.isEmpty()) ? "手机快投照片" : "来自 " + sourceIp + " · 照片投屏";
        currentPositionMs = 0;
        currentDurationMs = 0;
        transportState = "PLAYING";
        ensurePlayerDialog();
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
        Diagnostics.event("CAST_PHOTO_SHOWN");
    }

    void remoteControl(String cmd) {
        if (cmd == null) return;
        switch (cmd) {
            case "toggle":
                togglePlayPause();
                break;
            case "play":
                resumePlayback();
                break;
            case "pause":
                pausePlayback();
                break;
            case "stop":
                stopPlayback();
                break;
            case "seek_back":
                seekByMs(-15000);
                break;
            case "seek_forward":
                seekByMs(15000);
                break;
            case "vol_up":
                adjustVolume(1);
                break;
            case "vol_down":
                adjustVolume(-1);
                break;
            case "aspect":
            case "rotate":
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
        if (!isPlaying() || isPhotoCast || videoView == null) return;
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
        host.handler.removeCallbacks(progressTicker);
        host.handler.removeCallbacks(hideOsd);
        transportState = "STOPPED";
        currentPositionMs = 0;
        currentDurationMs = 0;
        if (videoView != null) {
            try { videoView.stopPlayback(); } catch (RuntimeException ignored) {}
        }
        if (playerDialog != null && playerDialog.isShowing()) {
            try { playerDialog.dismiss(); } catch (RuntimeException ignored) {}
        }
        if (currentPhoto != null) {
            if (photoView != null) photoView.setImageBitmap(null);
            if (!currentPhoto.isRecycled()) currentPhoto.recycle();
            currentPhoto = null;
        }
        syncReceiver();
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
                    if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
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
                    stopPlayback();
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
            if (nextUri != null && !nextUri.isEmpty()) {
                String next = nextUri;
                nextUri = "";
                playVideo(next, currentTitle + "（下一集）", "", true);
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
            transportState = "STOPPED";
            host.handler.removeCallbacks(progressTicker);
            host.handler.removeCallbacks(hideOsd);
            syncReceiver();
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
            osdTime.setText(formatTime(pos) + " / " + formatTime(dur)
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
                        else stopPlayback();
                    }).setNegativeButton("返回画面", null).create());
            return;
        }
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
                    else if (w == 2) playVideo(currentUri, currentTitle, "", true);
                    else stopPlayback();
                }).setNegativeButton("继续播放", null).create());
    }

    private void offerExternalPlayer(String reason) {
        host.showDialog(new TvDialog.Builder(host).setPageId("cast_error").setTitle("投屏播放提示")
                .setMessage(reason + "\n\n可以调用电视上安装的其他播放器（如 Kodi、VLC、当贝播放器等）继续打开该视频流。")
                .setItems(new String[]{"用外部播放器打开", "重试内置播放", "退出投屏"}, (d, w) -> {
                    if (w == 0) openInExternalPlayer();
                    else if (w == 1) playVideo(currentUri, currentTitle, "", true);
                    else stopPlayback();
                }).setNegativeButton("返回", null).create());
    }

    private void openInExternalPlayer() {
        if (currentUri == null || currentUri.isEmpty()) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(currentUri), "video/*");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            host.startActivity(intent);
            stopPlayback();
        } catch (RuntimeException e) {
            host.toast("电视未安装可处理此视频流的外部播放器");
        }
    }

    // --- Module 3: System / OEM Screen Mirroring Bridge ---

    private static final class MirrorTarget {
        final String label;
        final Intent intent;
        MirrorTarget(String label, Intent intent) {
            this.label = label;
            this.intent = intent;
        }
    }

    void openSystemMirroring() {
        PackageManager pm = host.getPackageManager();
        ArrayList<MirrorTarget> targets = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();

        String[][] knownPackages = {
                {"com.xiaomi.mitv.smartshare", "小米无线投屏（Miracast / 米联）"},
                {"com.duokan.airkan.tvbox", "小米多看投屏（AirPlay / DLNA）"},
                {"com.xiaomi.mitv.miracast", "小米无线显示（Miracast）"},
                {"com.hpplay.happycast", "乐播投屏"},
                {"com.tcl.wfd", "TCL 无线投屏"},
                {"com.hisense.multiscreen", "海信多屏互动"},
                {"com.skyworth.miracast", "创维无线投屏"},
                {"com.konka.miracast", "康佳无线投屏"},
                {"com.changhong.miracast", "长虹无线投屏"}
        };
        for (String[] pair : knownPackages) {
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pair[0]);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pair[0]);
            if (launch != null && seen.add(pair[0])) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                targets.add(new MirrorTarget(pair[1], launch));
            }
        }

        for (ApplicationInfo app : pm.getInstalledApplications(0)) {
            if (app.packageName.equals(host.getPackageName()) || seen.contains(app.packageName)) continue;
            String label = String.valueOf(app.loadLabel(pm));
            String lower = (label + " " + app.packageName).toLowerCase(Locale.ROOT);
            if (lower.contains("miracast") || lower.contains("smartshare") || lower.contains("airkan")
                    || label.contains("投屏") || label.contains("无线显示") || label.contains("多屏互动") || label.contains("屏幕镜像")) {
                Intent launch = pm.getLeanbackLaunchIntentForPackage(app.packageName);
                if (launch == null) launch = pm.getLaunchIntentForPackage(app.packageName);
                if (launch != null && seen.add(app.packageName)) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    targets.add(new MirrorTarget(label, launch));
                }
            }
        }

        String[][] settingsActions = {
                {"android.settings.WIFI_DISPLAY_SETTINGS", "系统无线显示设置（Miracast）"},
                {"android.settings.CAST_SETTINGS", "系统投射设置（Cast）"}
        };
        for (String[] action : settingsActions) {
            Intent intent = new Intent(action[0]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ResolveInfo info = pm.resolveActivity(intent, 0);
            if (info != null && info.activityInfo != null && info.activityInfo.exported
                    && !info.activityInfo.name.endsWith("EmptyStubActivity")
                    && seen.add(info.activityInfo.packageName + "/" + info.activityInfo.name)) {
                targets.add(new MirrorTarget(action[1], intent));
            }
        }

        if (targets.isEmpty()) {
            host.showDialog(new TvDialog.Builder(host).setPageId("cast_mirror_none").setTitle("未检测到系统无线镜像入口")
                    .setMessage("当前电视固件未开放独立的 Miracast / 无线显示页面。\n\n你仍然可以直接使用：\n"
                            + "1. 视频 App 投屏（Marco TV 已内置 DLNA 接收器，无需外部软件）\n"
                            + "2. Marco 互联手机扫码快投（直接投射手机相册照片、视频和直播链接）")
                    .setPositiveButton("打开等待投屏页", (d, w) -> showWaitingPage())
                    .setNegativeButton("返回", null).create());
            return;
        }
        if (targets.size() == 1) {
            try {
                host.startActivity(targets.get(0).intent);
                return;
            } catch (RuntimeException ignored) {}
        }
        String[] names = new String[targets.size()];
        for (int i = 0; i < targets.size(); i++) names[i] = targets.get(i).label;
        host.showDialog(new TvDialog.Builder(host).setPageId("cast_mirror_list").setTitle("选择系统无线镜像入口")
                .setMessage("整屏镜像（Miracast / AirPlay）由电视内置组件提供；视频 App 投屏可直接留在 Marco TV 内使用。")
                .setItems(names, (d, w) -> {
                    try {
                        host.startActivity(targets.get(w).intent);
                    } catch (RuntimeException e) {
                        host.toast("系统未允许直接打开该镜像页面");
                    }
                }).setNegativeButton("返回", (d, w) -> show()).create());
    }

    // --- Module 1: DLNA / UPnP MediaRenderer (SSDP + HTTP SOAP) ---

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

    private synchronized void startReceiver(final String ip) {
        try {
            InetAddress bindAddr = InetAddress.getByName(ip);
            httpServer = new ServerSocket();
            httpServer.setReuseAddress(true);
            httpServer.bind(new InetSocketAddress(bindAddr, 0), 16);
            activeIp = ip;
            activePort = httpServer.getLocalPort();
            receiverRunning = true;
            ioPool = Executors.newCachedThreadPool();

            try {
                WifiManager wifi = (WifiManager) host.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifi != null) {
                    multicastLock = wifi.createMulticastLock("marcotv-dlna");
                    multicastLock.setReferenceCounted(false);
                    multicastLock.acquire();
                }
            } catch (RuntimeException ignored) {}

            final ServerSocket srv = httpServer;
            Thread httpAccept = new Thread(() -> {
                while (receiverRunning && !srv.isClosed()) {
                    try {
                        final Socket client = srv.accept();
                        client.setSoTimeout(5000);
                        ExecutorService pool = ioPool;
                        if (pool != null) pool.execute(() -> handleHttpClient(client));
                        else client.close();
                    } catch (IOException ignored) {}
                }
            }, "MarcoDlnaHttp");
            httpAccept.setDaemon(true);
            httpAccept.start();

            try {
                ssdpSocket = new MulticastSocket(null);
                ssdpSocket.setReuseAddress(true);
                ssdpSocket.bind(new InetSocketAddress(SSDP_PORT));
                NetworkInterface nif = NetworkInterface.getByInetAddress(bindAddr);
                InetAddress group = InetAddress.getByName(SSDP_ADDRESS);
                if (nif != null) ssdpSocket.joinGroup(new InetSocketAddress(group, SSDP_PORT), nif);
                else ssdpSocket.joinGroup(group);

                final MulticastSocket sock = ssdpSocket;
                final int port = activePort;
                final String uuid = udn();
                Thread ssdpLoop = new Thread(() -> listenSsdp(sock, ip, port, uuid), "MarcoDlnaSsdp");
                ssdpLoop.setDaemon(true);
                ssdpLoop.start();
            } catch (IOException ignored) {
                // Even if UDP 1900 is occupied by another system service, outgoing SSDP NOTIFY announcements still work.
            }

            host.handler.removeCallbacks(ssdpAliveTicker);
            host.handler.post(ssdpAliveTicker);
        } catch (IOException e) {
            stopReceiver();
        }
    }

    private synchronized void stopReceiver() {
        final String ip = activeIp;
        final int port = activePort;
        final String uuid = udn();
        boolean wasRunning = receiverRunning;
        receiverRunning = false;
        activeIp = "";
        activePort = 0;
        host.handler.removeCallbacks(ssdpAliveTicker);
        if (wasRunning && !ip.isEmpty() && port > 0) {
            new Thread(() -> sendSsdpNotify(ip, port, uuid, false), "MarcoDlnaBye").start();
        }
        if (ssdpSocket != null) {
            try { ssdpSocket.close(); } catch (RuntimeException ignored) {}
            ssdpSocket = null;
        }
        if (httpServer != null) {
            try { httpServer.close(); } catch (IOException ignored) {}
            httpServer = null;
        }
        if (ioPool != null) {
            ioPool.shutdownNow();
            ioPool = null;
        }
        if (multicastLock != null) {
            try { if (multicastLock.isHeld()) multicastLock.release(); } catch (RuntimeException ignored) {}
            multicastLock = null;
        }
    }

    private void listenSsdp(MulticastSocket sock, String ip, int port, String uuid) {
        byte[] buf = new byte[2048];
        while (receiverRunning && !sock.isClosed()) {
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                sock.receive(pkt);
                String msg = new String(pkt.getData(), pkt.getOffset(), pkt.getLength(), StandardCharsets.UTF_8);
                if (!msg.startsWith("M-SEARCH * HTTP/1.1")) continue;
                String lower = msg.toLowerCase(Locale.ROOT);
                if (!lower.contains("ssdp:discover")) continue;
                String st = extractHeader(msg, "ST");
                if (st.isEmpty()) continue;
                if (st.equals("ssdp:all")
                        || st.equals("upnp:rootdevice")
                        || st.equals("uuid:" + uuid)
                        || st.contains("MediaRenderer:")
                        || st.contains("AVTransport:")
                        || st.contains("RenderingControl:")
                        || st.contains("ConnectionManager:")) {
                    String replyTarget = st.equals("ssdp:all") ? "urn:schemas-upnp-org:device:MediaRenderer:1" : st;
                    String usn = "uuid:" + uuid + (replyTarget.startsWith("uuid:") ? "" : "::" + replyTarget);
                    String response = "HTTP/1.1 200 OK\r\n"
                            + "CACHE-CONTROL: max-age=1800\r\n"
                            + "EXT:\r\n"
                            + "LOCATION: http://" + ip + ":" + port + "/dlna/device.xml\r\n"
                            + "SERVER: Android/" + Build.VERSION.RELEASE + " UPnP/1.0 MarcoTV/" + ProjectInfo.VERSION + "\r\n"
                            + "ST: " + replyTarget + "\r\n"
                            + "USN: " + usn + "\r\n"
                            + "Content-Length: 0\r\n\r\n";
                    byte[] data = response.getBytes(StandardCharsets.UTF_8);
                    sock.send(new DatagramPacket(data, data.length, pkt.getAddress(), pkt.getPort()));
                }
            } catch (IOException ignored) {}
        }
    }

    private static void sendSsdpNotify(String ip, int port, String uuid, boolean alive) {
        String[] ntList = {
                "upnp:rootdevice",
                "uuid:" + uuid,
                "urn:schemas-upnp-org:device:MediaRenderer:1",
                "urn:schemas-upnp-org:service:AVTransport:1",
                "urn:schemas-upnp-org:service:RenderingControl:1"
        };
        try (DatagramSocket sock = new DatagramSocket()) {
            InetAddress group = InetAddress.getByName(SSDP_ADDRESS);
            for (String nt : ntList) {
                String usn = "uuid:" + uuid + (nt.startsWith("uuid:") ? "" : "::" + nt);
                String msg = "NOTIFY * HTTP/1.1\r\n"
                        + "HOST: " + SSDP_ADDRESS + ":" + SSDP_PORT + "\r\n"
                        + "CACHE-CONTROL: max-age=1800\r\n"
                        + "LOCATION: http://" + ip + ":" + port + "/dlna/device.xml\r\n"
                        + "NT: " + nt + "\r\n"
                        + "NTS: " + (alive ? "ssdp:alive" : "ssdp:byebye") + "\r\n"
                        + "SERVER: Android/" + Build.VERSION.RELEASE + " UPnP/1.0 MarcoTV/" + ProjectInfo.VERSION + "\r\n"
                        + "USN: " + usn + "\r\n"
                        + "Content-Length: 0\r\n\r\n";
                byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
                sock.send(new DatagramPacket(bytes, bytes.length, group, SSDP_PORT));
            }
        } catch (IOException ignored) {}
    }

    static String extractHeader(String rawHttp, String name) {
        String prefix = name.toLowerCase(Locale.ROOT) + ":";
        for (String line : rawHttp.split("\r?\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private void handleHttpClient(Socket socket) {
        try (Socket s = socket;
             InputStream in = new BufferedInputStream(s.getInputStream());
             OutputStream out = s.getOutputStream()) {
            ByteArrayOutputStream headBuf = new ByteArrayOutputStream();
            int state = 0, b;
            while ((b = in.read()) != -1) {
                headBuf.write(b);
                if (headBuf.size() > 16384) return;
                if ((state == 0 || state == 2) && b == '\r') state++;
                else if ((state == 1 || state == 3) && b == '\n') {
                    state++;
                    if (state == 4) break;
                } else state = 0;
            }
            String headStr = new String(headBuf.toByteArray(), StandardCharsets.UTF_8);
            String[] lines = headStr.split("\r\n");
            if (lines.length == 0 || lines[0].isEmpty()) return;
            String[] start = lines[0].split(" ");
            if (start.length < 2) return;
            String method = start[0].toUpperCase(Locale.ROOT);
            String path = start[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            if (method.equals("SUBSCRIBE") || method.equals("UNSUBSCRIBE")) {
                String subReply = "HTTP/1.1 200 OK\r\n"
                        + "SID: uuid:marcotv-event-" + udn() + "\r\n"
                        + "TIMEOUT: Second-1800\r\n"
                        + "Content-Length: 0\r\n"
                        + "Connection: close\r\n\r\n";
                out.write(subReply.getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            }

            if (method.equals("GET")) {
                String xml;
                if (path.equals("/dlna/device.xml")) xml = buildDeviceXml(friendlyName(), udn());
                else if (path.equals("/dlna/avtransport.xml")) xml = AVTRANSPORT_SCPD;
                else if (path.equals("/dlna/rendering.xml")) xml = RENDERING_SCPD;
                else if (path.equals("/dlna/connection.xml")) xml = CONNECTION_SCPD;
                else {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                writeHttpXml(out, 200, xml);
                return;
            }

            if (!method.equals("POST")) {
                out.write("HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                return;
            }

            int contentLength = 0;
            try {
                contentLength = Integer.parseInt(extractHeader(headStr, "Content-Length"));
            } catch (NumberFormatException ignored) {}
            if (contentLength < 0 || contentLength > 262144) return;
            byte[] bodyBytes = new byte[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = in.read(bodyBytes, read, contentLength - read);
                if (n < 0) break;
                read += n;
            }
            String body = new String(bodyBytes, 0, read, StandardCharsets.UTF_8);
            String soapAction = parseSoapAction(extractHeader(headStr, "SOAPAction"), body);
            String remoteIp = s.getInetAddress() == null ? "" : s.getInetAddress().getHostAddress();
            String responseXml = handleSoapAction(soapAction, body, remoteIp);
            writeHttpXml(out, 200, responseXml);
        } catch (Exception ignored) {}
    }

    private static void writeHttpXml(OutputStream out, int code, String xml) throws IOException {
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + code + " OK\r\n"
                + "Content-Type: text/xml; charset=\"utf-8\"\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "EXT:\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    static String parseSoapAction(String header, String body) {
        if (header != null && !header.isEmpty()) {
            String clean = header.replace("\"", "").trim();
            int hash = clean.indexOf('#');
            if (hash >= 0 && hash + 1 < clean.length()) return clean.substring(hash + 1).trim();
        }
        if (body != null) {
            String[] known = {
                    "SetAVTransportURI", "SetNextAVTransportURI", "Play", "Pause", "Stop", "Seek",
                    "GetPositionInfo", "GetTransportInfo", "GetMediaInfo", "GetProtocolInfo",
                    "GetCurrentTransportActions", "GetVolume", "SetVolume", "GetMute", "SetMute"
            };
            for (String act : known) {
                if (body.contains(":" + act) || body.contains("<" + act)) return act;
            }
        }
        return "";
    }

    static String extractXmlTag(String xml, String tag) {
        if (xml == null || tag == null) return "";
        String[] opens = {"<" + tag + ">", "<" + tag + " "};
        for (String open : opens) {
            int start = xml.indexOf(open);
            if (start >= 0) {
                int contentStart = xml.indexOf('>', start);
                if (contentStart < 0) continue;
                contentStart++;
                int end = xml.indexOf("</" + tag + ">", contentStart);
                if (end < 0) {
                    int nsEnd = xml.indexOf(":" + tag + ">", contentStart);
                    if (nsEnd > contentStart) {
                        int closeOpen = xml.lastIndexOf("</", nsEnd);
                        if (closeOpen >= contentStart) end = closeOpen;
                    }
                }
                if (end >= contentStart) return unescapeXml(xml.substring(contentStart, end).trim());
            }
        }
        // Check namespace-prefixed open tag e.g. <dc:title>
        int colonTag = xml.indexOf(":" + tag + ">");
        if (colonTag >= 0) {
            int contentStart = colonTag + tag.length() + 2;
            int end = xml.indexOf(":" + tag + ">", contentStart);
            if (end > contentStart) {
                int closeStart = xml.lastIndexOf("</", end);
                if (closeStart >= contentStart) return unescapeXml(xml.substring(contentStart, closeStart).trim());
            }
        }
        return "";
    }

    static String unescapeXml(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    static String escapeXml(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    static int parseTimeMs(String target) {
        if (target == null || target.isEmpty()) return 0;
        String clean = target.trim();
        int dot = clean.indexOf('.');
        if (dot >= 0) clean = clean.substring(0, dot);
        String[] parts = clean.split(":");
        try {
            if (parts.length == 3) {
                return (Integer.parseInt(parts[0]) * 3600
                        + Integer.parseInt(parts[1]) * 60
                        + Integer.parseInt(parts[2])) * 1000;
            }
            if (parts.length == 2) {
                return (Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1])) * 1000;
            }
            return Integer.parseInt(parts[0]) * 1000;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String formatTime(int ms) {
        int totalSec = Math.max(0, ms / 1000);
        int h = totalSec / 3600;
        int m = (totalSec % 3600) / 60;
        int s = totalSec % 60;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, s);
    }

    private String handleSoapAction(String action, String body, final String remoteIp) {
        String service = (action.contains("Volume") || action.contains("Mute"))
                ? "urn:schemas-upnp-org:service:RenderingControl:1"
                : action.equals("GetProtocolInfo")
                ? "urn:schemas-upnp-org:service:ConnectionManager:1"
                : "urn:schemas-upnp-org:service:AVTransport:1";
        String inner = "";
        switch (action) {
            case "SetAVTransportURI": {
                final String uri = extractXmlTag(body, "CurrentURI");
                String meta = extractXmlTag(body, "CurrentURIMetaData");
                String metaTitle = extractXmlTag(meta, "title");
                final String title = metaTitle.isEmpty() ? "DLNA 投屏视频" : metaTitle;
                if (!uri.isEmpty()) {
                    currentUri = uri;
                    transportState = "PLAYING";
                    host.handler.post(() -> requestCastVideo(uri, title, remoteIp));
                }
                break;
            }
            case "SetNextAVTransportURI": {
                nextUri = extractXmlTag(body, "NextURI");
                break;
            }
            case "Play": {
                transportState = "PLAYING";
                host.handler.post(this::resumePlayback);
                break;
            }
            case "Pause": {
                transportState = "PAUSED_PLAYBACK";
                host.handler.post(this::pausePlayback);
                break;
            }
            case "Stop": {
                transportState = "STOPPED";
                host.handler.post(this::stopPlayback);
                break;
            }
            case "Seek": {
                String target = extractXmlTag(body, "Target");
                final int ms = parseTimeMs(target);
                host.handler.post(() -> seekToMs(ms));
                break;
            }
            case "GetPositionInfo": {
                String dur = formatTime(currentDurationMs);
                String pos = formatTime(currentPositionMs);
                String uri = escapeXml(currentUri);
                inner = "<Track>1</Track>"
                        + "<TrackDuration>" + dur + "</TrackDuration>"
                        + "<TrackMetaData></TrackMetaData>"
                        + "<TrackURI>" + uri + "</TrackURI>"
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
                String dur = formatTime(currentDurationMs);
                String uri = escapeXml(currentUri);
                inner = "<NrTracks>1</NrTracks>"
                        + "<MediaDuration>" + dur + "</MediaDuration>"
                        + "<CurrentURI>" + uri + "</CurrentURI>"
                        + "<CurrentURIMetaData></CurrentURIMetaData>"
                        + "<NextURI>" + escapeXml(nextUri) + "</NextURI>"
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
                String desired = extractXmlTag(body, "DesiredVolume");
                try {
                    final int pct = Math.max(0, Math.min(100, Integer.parseInt(desired)));
                    host.handler.post(() -> {
                        try {
                            AudioManager audio = (AudioManager) host.getSystemService(Context.AUDIO_SERVICE);
                            if (audio != null) {
                                int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(pct * max / 100f), 0);
                            }
                        } catch (RuntimeException ignored) {}
                    });
                } catch (NumberFormatException ignored) {}
                break;
            }
            case "GetMute": {
                inner = "<CurrentMute>0</CurrentMute>";
                break;
            }
            case "SetMute": {
                break;
            }
        }
        String respTag = action.isEmpty() ? "ActionResponse" : action + "Response";
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + respTag + " xmlns:u=\"" + service + "\">"
                + inner
                + "</u:" + respTag + "></s:Body></s:Envelope>";
    }

    static String buildDeviceXml(String friendlyName, String uuid) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\">"
                + "<specVersion><major>1</major><minor>0</minor></specVersion>"
                + "<device>"
                + "<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>"
                + "<friendlyName>" + escapeXml(friendlyName) + "</friendlyName>"
                + "<manufacturer>Pilipala</manufacturer>"
                + "<modelName>Marco TV</modelName>"
                + "<modelNumber>" + ProjectInfo.VERSION + "</modelNumber>"
                + "<UDN>uuid:" + escapeXml(uuid) + "</UDN>"
                + "<serviceList>"
                + "<service>"
                + "<serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>"
                + "<serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>"
                + "<SCPDURL>/dlna/avtransport.xml</SCPDURL>"
                + "<controlURL>/dlna/avtransport/control</controlURL>"
                + "<eventSubURL>/dlna/avtransport/event</eventSubURL>"
                + "</service>"
                + "<service>"
                + "<serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>"
                + "<serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>"
                + "<SCPDURL>/dlna/rendering.xml</SCPDURL>"
                + "<controlURL>/dlna/rendering/control</controlURL>"
                + "<eventSubURL>/dlna/rendering/event</eventSubURL>"
                + "</service>"
                + "<service>"
                + "<serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>"
                + "<serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>"
                + "<SCPDURL>/dlna/connection.xml</SCPDURL>"
                + "<controlURL>/dlna/connection/control</controlURL>"
                + "<eventSubURL>/dlna/connection/event</eventSubURL>"
                + "</service>"
                + "</serviceList>"
                + "</device></root>";
    }

    private static final String AVTRANSPORT_SCPD = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">"
            + "<specVersion><major>1</major><minor>0</minor></specVersion>"
            + "<actionList>"
            + "<action><name>SetAVTransportURI</name></action>"
            + "<action><name>SetNextAVTransportURI</name></action>"
            + "<action><name>Play</name></action>"
            + "<action><name>Pause</name></action>"
            + "<action><name>Stop</name></action>"
            + "<action><name>Seek</name></action>"
            + "<action><name>GetPositionInfo</name></action>"
            + "<action><name>GetTransportInfo</name></action>"
            + "<action><name>GetMediaInfo</name></action>"
            + "<action><name>GetCurrentTransportActions</name></action>"
            + "</actionList><serviceStateTable></serviceStateTable></scpd>";

    private static final String RENDERING_SCPD = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">"
            + "<specVersion><major>1</major><minor>0</minor></specVersion>"
            + "<actionList>"
            + "<action><name>GetVolume</name></action>"
            + "<action><name>SetVolume</name></action>"
            + "<action><name>GetMute</name></action>"
            + "<action><name>SetMute</name></action>"
            + "</actionList><serviceStateTable></serviceStateTable></scpd>";

    private static final String CONNECTION_SCPD = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">"
            + "<specVersion><major>1</major><minor>0</minor></specVersion>"
            + "<actionList><action><name>GetProtocolInfo</name></action></actionList>"
            + "<serviceStateTable></serviceStateTable></scpd>";

    /** Procedural vector graphics & animated radar views for the Casting Center. */
    static final class CastGraphicView extends View {
        static final int KIND_STATUS_BEACON = 0;
        static final int KIND_CARD_DLNA = 1;
        static final int KIND_CARD_PHONE = 2;
        static final int KIND_CARD_MIRROR = 3;
        static final int KIND_ICON_DLNA_SWITCH = 4;
        static final int KIND_ICON_RADAR_SWITCH = 5;
        static final int KIND_ICON_SHIELD_SWITCH = 6;
        static final int KIND_RADAR_HERO = 7;
        static final int KIND_STEP_WIFI = 8;
        static final int KIND_STEP_VIDEO = 9;
        static final int KIND_STEP_CAST = 10;

        private final int kind;
        private boolean active;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path path = new Path();

        CastGraphicView(Context context, int kind, boolean active) {
            super(context);
            this.kind = kind;
            this.active = active;
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        void setActive(boolean value) {
            if (this.active != value) {
                this.active = value;
                invalidate();
            }
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float cx = w * 0.5f, cy = h * 0.5f;
            float u = Math.min(w, h) / 48f;

            switch (kind) {
                case KIND_STATUS_BEACON:
                    drawStatusBeacon(c, cx, cy, u);
                    break;
                case KIND_CARD_DLNA:
                    drawCardDlna(c, cx, cy, u);
                    break;
                case KIND_CARD_PHONE:
                    drawCardPhone(c, cx, cy, u);
                    break;
                case KIND_CARD_MIRROR:
                    drawCardMirror(c, cx, cy, u);
                    break;
                case KIND_ICON_DLNA_SWITCH:
                    drawSwitchDlna(c, cx, cy, u);
                    break;
                case KIND_ICON_RADAR_SWITCH:
                    drawSwitchRadar(c, cx, cy, u);
                    break;
                case KIND_ICON_SHIELD_SWITCH:
                    drawSwitchShield(c, cx, cy, u);
                    break;
                case KIND_RADAR_HERO:
                    drawRadarHero(c, cx, cy, u);
                    break;
                case KIND_STEP_WIFI:
                    drawStepBadge(c, cx, cy, u, 1);
                    break;
                case KIND_STEP_VIDEO:
                    drawStepBadge(c, cx, cy, u, 2);
                    break;
                case KIND_STEP_CAST:
                    drawStepBadge(c, cx, cy, u, 3);
                    break;
            }
        }

        private void drawStatusBeacon(Canvas c, float cx, float cy, float u) {
            float phase = (SystemClock.uptimeMillis() % 2200L) / 2200f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(active ? 0x2234C759 : 0x1E6C7A8E);
            c.drawCircle(cx, cy, 22 * u, paint);
            if (active) {
                float r = (10 + phase * 12) * u;
                int alpha = Math.max(0, Math.min(255, Math.round((1f - phase) * 140)));
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.2f * u);
                paint.setColor((alpha << 24) | 0x48E59A);
                c.drawCircle(cx, cy, r, paint);
            }
            // Mini TV frame + cast arcs
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.4f * u);
            paint.setColor(active ? 0xFF8BE8F5 : 0xFF8393A7);
            rect.set(cx - 12 * u, cy - 9 * u, cx + 12 * u, cy + 8 * u);
            c.drawRoundRect(rect, 3.5f * u, 3.5f * u, paint);
            c.drawLine(cx - 5 * u, cy + 12 * u, cx + 5 * u, cy + 12 * u, paint);

            paint.setColor(active ? 0xFF48E59A : 0xFF718096);
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(cx - 7 * u, cy + 3 * u, 2f * u, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f * u);
            rect.set(cx - 12 * u, cy - 2 * u, cx - 2 * u, cy + 8 * u);
            c.drawArc(rect, -90, 90, false, paint);
            rect.set(cx - 16 * u, cy - 6 * u, cx + 2 * u, cy + 12 * u);
            c.drawArc(rect, -90, 90, false, paint);
            if (active && isAttachedToWindow()) postInvalidateDelayed(40);
        }

        private void drawCardDlna(Canvas c, float cx, float cy, float u) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x225CE1E6);
            c.drawCircle(cx, cy, 23 * u, paint);

            // Widescreen TV monitor
            paint.setColor(0x285CE1E6);
            rect.set(cx - 15 * u, cy - 12 * u, cx + 15 * u, cy + 8 * u);
            c.drawRoundRect(rect, 4 * u, 4 * u, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.4f * u);
            paint.setColor(0xFFB4ECF7);
            c.drawRoundRect(rect, 4 * u, 4 * u, paint);
            c.drawLine(cx - 6 * u, cy + 13 * u, cx + 6 * u, cy + 13 * u, paint);
            c.drawLine(cx, cy + 8 * u, cx, cy + 13 * u, paint);

            // Play triangle inside TV
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF5CE1E6);
            path.reset();
            path.moveTo(cx - 1.5f * u, cy - 6.5f * u);
            path.lineTo(cx + 6.5f * u, cy - 2f * u);
            path.lineTo(cx - 1.5f * u, cy + 2.5f * u);
            path.close();
            c.drawPath(path, paint);

            // DLNA Cast arcs at bottom-left
            paint.setColor(0xFF48E59A);
            c.drawCircle(cx - 11 * u, cy + 4.5f * u, 1.8f * u, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.9f * u);
            rect.set(cx - 15.5f * u, cy, cx - 6.5f * u, cy + 9 * u);
            c.drawArc(rect, -90, 90, false, paint);
            rect.set(cx - 19f * u, cy - 3.5f * u, cx - 3f * u, cy + 12.5f * u);
            c.drawArc(rect, -90, 90, false, paint);
        }

        private void drawCardPhone(Canvas c, float cx, float cy, float u) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x2264DFDF);
            c.drawCircle(cx, cy, 23 * u, paint);

            // Mini TV frame at top-right
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f * u);
            paint.setColor(0x998BE8F5);
            rect.set(cx - 3 * u, cy - 14 * u, cx + 16 * u, cy - 1 * u);
            c.drawRoundRect(rect, 3 * u, 3 * u, paint);

            // Smartphone body at bottom-left
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF233647);
            rect.set(cx - 15 * u, cy - 7 * u, cx + 1 * u, cy + 15 * u);
            c.drawRoundRect(rect, 3.5f * u, 3.5f * u, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.3f * u);
            paint.setColor(0xFFB4ECF7);
            c.drawRoundRect(rect, 3.5f * u, 3.5f * u, paint);

            // Media badge on phone screen
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF5CE1E6);
            c.drawCircle(cx - 7 * u, cy + 4 * u, 3.2f * u, paint);

            // Flying cast arrow from phone to TV
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.3f * u);
            paint.setColor(0xFF48E59A);
            c.drawLine(cx - 3 * u, cy, cx + 7 * u, cy - 8 * u, paint);
            c.drawLine(cx + 2 * u, cy - 8 * u, cx + 7 * u, cy - 8 * u, paint);
            c.drawLine(cx + 7 * u, cy - 8 * u, cx + 7 * u, cy - 3 * u, paint);
        }

        private void drawCardMirror(Canvas c, float cx, float cy, float u) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x228EC5FC);
            c.drawCircle(cx, cy, 23 * u, paint);

            // Rear TV screen
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x267FB8D8);
            rect.set(cx - 16 * u, cy - 12 * u, cx + 10 * u, cy + 6 * u);
            c.drawRoundRect(rect, 3.5f * u, 3.5f * u, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.2f * u);
            paint.setColor(0xFF9ACCE6);
            c.drawRoundRect(rect, 3.5f * u, 3.5f * u, paint);

            // Front overlapping mirrored screen
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF21374A);
            rect.set(cx - 4 * u, cy - 4 * u, cx + 16 * u, cy + 13 * u);
            c.drawRoundRect(rect, 3.5f * u, 3.5f * u, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.3f * u);
            paint.setColor(0xFF5CE1E6);
            c.drawRoundRect(rect, 3.5f * u, 3.5f * u, paint);

            // Sync wave bars inside front screen
            paint.setStrokeWidth(2f * u);
            paint.setColor(0xFF48E59A);
            c.drawLine(cx + 1 * u, cy + 3 * u, cx + 11 * u, cy + 3 * u, paint);
            c.drawLine(cx + 3 * u, cy + 7 * u, cx + 9 * u, cy + 7 * u, paint);
        }

        private void drawSwitchDlna(Canvas c, float cx, float cy, float u) {
            int mainColor = active ? 0xFF48E59A : 0xFF7B8B9E;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(active ? 0x2248E59A : 0x1A7B8B9E);
            c.drawCircle(cx, cy, 22 * u, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3.2f * u);
            paint.setColor(mainColor);
            rect.set(cx - 13 * u, cy - 10 * u, cx + 13 * u, cy + 10 * u);
            c.drawRoundRect(rect, 4 * u, 4 * u, paint);

            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(cx - 8 * u, cy + 5 * u, 2.2f * u, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.6f * u);
            rect.set(cx - 14 * u, cy - 1 * u, cx - 2 * u, cy + 11 * u);
            c.drawArc(rect, -90, 90, false, paint);
        }

        private void drawSwitchRadar(Canvas c, float cx, float cy, float u) {
            int mainColor = active ? 0xFF5CE1E6 : 0xFF7B8B9E;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(active ? 0x225CE1E6 : 0x1A7B8B9E);
            c.drawCircle(cx, cy, 22 * u, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.8f * u);
            paint.setColor(mainColor);
            c.drawCircle(cx, cy, 13 * u, paint);
            paint.setStrokeWidth(2.2f * u);
            c.drawCircle(cx, cy, 7 * u, paint);
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, 2.8f * u, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.6f * u);
            c.drawLine(cx, cy, cx + 9 * u, cy - 9 * u, paint);
        }

        private void drawSwitchShield(Canvas c, float cx, float cy, float u) {
            int mainColor = active ? 0xFF48E59A : 0xFF7B8B9E;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(active ? 0x2248E59A : 0x1A7B8B9E);
            c.drawCircle(cx, cy, 22 * u, paint);

            path.reset();
            path.moveTo(cx, cy - 14 * u);
            path.lineTo(cx + 12 * u, cy - 9 * u);
            path.lineTo(cx + 11 * u, cy + 3 * u);
            path.quadTo(cx + 9 * u, cy + 12 * u, cx, cy + 15 * u);
            path.quadTo(cx - 9 * u, cy + 12 * u, cx - 11 * u, cy + 3 * u);
            path.lineTo(cx - 12 * u, cy - 9 * u);
            path.close();

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f * u);
            paint.setColor(mainColor);
            c.drawPath(path, paint);

            // Checkmark inside shield
            c.drawLine(cx - 5 * u, cy + 1 * u, cx - 1 * u, cy + 5 * u, paint);
            c.drawLine(cx - 1 * u, cy + 5 * u, cx + 6 * u, cy - 3 * u, paint);
        }

        private void drawRadarHero(Canvas c, float cx, float cy, float u) {
            float t = (SystemClock.uptimeMillis() % 3000L) / 3000f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x165CE1E6);
            c.drawCircle(cx, cy, 22 * u, paint);

            // 3 animated expanding radar waves
            paint.setStyle(Paint.Style.STROKE);
            for (int i = 0; i < 3; i++) {
                float p = (t + i / 3f) % 1f;
                float r = (7f + p * 16.5f) * u;
                int alpha = Math.max(0, Math.min(255, Math.round((1f - p) * 165)));
                paint.setStrokeWidth((2.4f - p * 1.2f) * u);
                paint.setColor((alpha << 24) | 0x5CE1E6);
                c.drawCircle(cx, cy, r, paint);
            }

            // Center glowing core circle
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF233B4E);
            c.drawCircle(cx, cy, 10.5f * u, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.6f * u);
            paint.setColor(0xFF7BE4F2);
            c.drawCircle(cx, cy, 10.5f * u, paint);

            // Central TV + Cast emblem
            rect.set(cx - 6.2f * u, cy - 4.8f * u, cx + 6.2f * u, cy + 3.6f * u);
            paint.setStrokeWidth(1.5f * u);
            paint.setColor(Color.WHITE);
            c.drawRoundRect(rect, 1.8f * u, 1.8f * u, paint);
            c.drawLine(cx - 2.8f * u, cy + 5.8f * u, cx + 2.8f * u, cy + 5.8f * u, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF48E59A);
            c.drawCircle(cx - 3.6f * u, cy + 1.5f * u, 1.1f * u, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.2f * u);
            rect.set(cx - 6.2f * u, cy - 1.1f * u, cx - 1f * u, cy + 4.1f * u);
            c.drawArc(rect, -90, 90, false, paint);

            if (isAttachedToWindow()) postInvalidateDelayed(36);
        }

        private void drawStepBadge(Canvas c, float cx, float cy, float u, int step) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x245CE1E6);
            c.drawCircle(cx, cy, 22 * u, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.2f * u);
            paint.setColor(0x885CE1E6);
            c.drawCircle(cx, cy, 21 * u, paint);

            if (step == 1) {
                // Wi-Fi arcs
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFF5CE1E6);
                c.drawCircle(cx, cy + 7 * u, 2.5f * u, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.6f * u);
                rect.set(cx - 7 * u, cy, cx + 7 * u, cy + 14 * u);
                c.drawArc(rect, -135, 90, false, paint);
                rect.set(cx - 12 * u, cy - 5 * u, cx + 12 * u, cy + 19 * u);
                c.drawArc(rect, -135, 90, false, paint);
            } else if (step == 2) {
                // Play triangle
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFF5CE1E6);
                path.reset();
                path.moveTo(cx - 4 * u, cy - 8 * u);
                path.lineTo(cx + 8 * u, cy);
                path.lineTo(cx - 4 * u, cy + 8 * u);
                path.close();
                c.drawPath(path, paint);
            } else {
                // TV + Cast
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.5f * u);
                paint.setColor(0xFF48E59A);
                rect.set(cx - 11 * u, cy - 8 * u, cx + 11 * u, cy + 7 * u);
                c.drawRoundRect(rect, 3 * u, 3 * u, paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(cx - 6 * u, cy + 3 * u, 2f * u, paint);
                paint.setStyle(Paint.Style.STROKE);
                rect.set(cx - 11 * u, cy - 2 * u, cx - 1 * u, cy + 8 * u);
                c.drawArc(rect, -90, 90, false, paint);
            }
        }
    }
}
