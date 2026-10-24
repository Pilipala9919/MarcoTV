// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.Toast;
import android.widget.EditText;
import android.text.InputFilter;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** A small, offline Android TV launcher. All application artwork comes from installed packages. */
public final class MainActivity extends Activity {
    private static final int PICK_WALLPAPER = 100;
    private static final int PICK_COVER = 101, READ_COVER = 102;
    private CardStore cards;
    private NetworkPanel network;
    private TvDialog activeDialog;
    private final ArrayList<TvDialog> dialogHistory = new ArrayList<>();
    private int menuEpoch;
    private String pendingCoverId;
    private final ArrayList<Tile> allTiles = new ArrayList<>();
    private int loadGeneration, wallpaperGeneration;
    private boolean ambientRequested;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private android.content.SharedPreferences preferences;
    private HomeView home;
    private boolean foreground;
    private boolean dialogOpen;
    private boolean receiverRegistered;
    private final Runnable idle = () -> { if (foreground && !dialogOpen) home.setIdle(true); };
    private final Runnable clock = new Runnable() {
        @Override public void run() {
            if (foreground) { home.invalidate(); handler.postDelayed(this, 1000); }
        }
    };
    private final BroadcastReceiver packageChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { loadApplications(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        SystemIntegration.rememberHome(this);
        SystemIntegration.refreshHomeService(this);
        SystemIntegration.startRecovery(this);
        preferences = getSharedPreferences("home", MODE_PRIVATE);
        if (!preferences.getBoolean("identity_v06", false)) {
            preferences.edit().putBoolean("identity_v06", true).putInt("card_radius", 32).putInt("focus_scale", 16).apply();
        }
        ambientRequested = getIntent().getBooleanExtra("ambient", false);
        if (!preferences.getBoolean("clock_style_v06", false)) preferences.edit().putBoolean("clock_style_v06",true).putInt("clock_size",96).putInt("ambient_transition",3).apply();
        AmbientIntegration.configure(this);
        cards = new CardStore(this);
        network = new NetworkPanel(this, this::showDialog);
        if (state != null) pendingCoverId = state.getString("pending_cover");
        fullscreen();
        home = new HomeView();
        setContentView(home);
        home.requestFocus();
        loadWallpaper();
        loadApplications();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packageChanges, f);
        receiverRegistered = true;
    }

    private void fullscreen() {
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override protected void onResume() {
        super.onResume(); foreground = true; fullscreen();
        if (AmbientIntegration.enabled(this)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (home != null) { home.setIdle(false); home.requestFocus(); }
        if (home != null) home.animateFocus();
        handler.removeCallbacks(clock); handler.post(clock); armIdle();
        if (ambientRequested) { ambientRequested = false; handler.removeCallbacks(idle); home.setIdle(true); }
        if(activeDialog!=null&&!activeDialog.isShowing())presentDialog(activeDialog);
    }

    @Override protected void onPause() {
        foreground = false; handler.removeCallbacks(idle); handler.removeCallbacks(clock);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (home != null) { home.stopMotion(); if (home.fade != null) home.fade.cancel(); if(home.previewFade!=null)home.previewFade.cancel(); }
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (network != null) network.close();
        closeMenus();
        loader.shutdownNow();
        if (receiverRegistered) unregisterReceiver(packageChanges);
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        closeMenus();
        ambientRequested = intent.getBooleanExtra("ambient", false);
        if (home != null) { home.setIdle(false); home.resetToApps(); if (ambientRequested && foreground) { home.setIdle(true); ambientRequested=false; } }
    }

    private void armIdle() {
        handler.removeCallbacks(idle);
        int seconds = preferences.getInt("idle_seconds", 30);
        if (foreground && !dialogOpen && seconds > 0 && AmbientIntegration.enabled(this)) handler.postDelayed(idle, seconds * 1000L);
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }

    private void loadWallpaper() {
        final int generation = ++wallpaperGeneration;
        loader.execute(() -> {
            Bitmap ready = WallpaperSource.load(this);
            handler.post(() -> { if (!isDestroyed() && generation == wallpaperGeneration) { home.wallpaper = ready; home.invalidate(); } });
        });
    }

    private static final class Tile {
        String label, id;
        Intent intent;
        Bitmap artwork;
        BitmapShader shader;
        boolean banner;
        int tint;
        String glyph;
        final MotionSpring emphasis = new MotionSpring(0, 22, 0.001f);
        String displayLabel, coverLabel;
        int kind; // 0 installed application, 1 TV input
    }
    private static final class Row {
        String title, symbol, orderKey;
        ArrayList<Tile> tiles = new ArrayList<>();
        int selected;
        final MotionSpring scroll = new MotionSpring(0, 18, 0.05f);
        Row(String title, String symbol) {
            this.title = title; this.symbol = symbol;
            orderKey = title.equals("TV 应用") ? "order_tv" : title.equals("输入源") ? "order_inputs"
                    : title.equals("收藏") ? "order_favorites" : "order_other";
        }
    }

    private Tile applicationTile(ResolveInfo resolve) {
        PackageManager pm = getPackageManager();
        Tile tile = new Tile();
        tile.id = resolve.activityInfo.packageName;
        tile.label = resolve.loadLabel(pm).toString();
        tile.intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(tile.id, resolve.activityInfo.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        Drawable art = null;
        try { art = resolve.activityInfo.loadBanner(pm); } catch (Exception ignored) { }
        tile.banner = art != null;
        if (art == null && (resolve.activityInfo.icon != 0 || resolve.activityInfo.applicationInfo.icon != 0)) {
            try { art = resolve.loadIcon(pm); } catch (Exception ignored) { }
        }
        if (art != null) {
            int w = tile.banner ? 480 : 192, h = tile.banner ? 270 : 192;
            tile.artwork = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(tile.artwork); art.setBounds(0, 0, w, h); art.draw(canvas);
        }
        return tile;
    }

    private void loadApplications() {
        final int generation = ++loadGeneration;
        final String focusedId = home.rows.isEmpty() || home.row < 0 ? null : home.rows.get(home.row).tiles.get(home.rows.get(home.row).selected).id;
        final String focusedRow = home.rows.isEmpty() || home.row < 0 ? null : home.rows.get(home.row).orderKey;
        loader.execute(() -> {
            ArrayList<Row> result = new ArrayList<>();
            Row apps = new Row("TV 应用", "\ue333");
            Row inputs = new Row("输入源", "\ue890");
            Row others = new Row("其他应用", "\ue5c3");
            PackageManager pm = getPackageManager();
            HashSet<String> seen = new HashSet<>();
            HashSet<String> excluded = new HashSet<>(Arrays.asList(getPackageName(), "com.spocky.projengmenu", "com.mitv.tvhome"));
            try {
                for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
                    if (!excluded.contains(r.activityInfo.packageName) && seen.add(r.activityInfo.packageName)) apps.tiles.add(applicationTile(r));
                }
                for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
                    String id = r.activityInfo.packageName;
                    if (!excluded.contains(id) && seen.add(id)) {
                        Tile t = applicationTile(r);
                        if (id.equals("com.xiaomi.mitv.tvplayer") || id.equals("com.mitv.tvplayer")) apps.tiles.add(t); else others.tiles.add(t);
                    }
                }
                String tvPlayer = "com.xiaomi.mitv.tvplayer";
                if (!seen.contains(tvPlayer)) {
                    for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).setPackage(tvPlayer), 0)) {
                        if (r.activityInfo.exported && seen.add(tvPlayer)) { apps.tiles.add(applicationTile(r)); break; }
                    }
                }
                String saved = preferences.getString("order_tv", "");
                final List<String> order = Arrays.asList(saved.isEmpty()
                        ? new String[]{"com.fongmi.android.tv", "com.maric.adbkeeper", "com.netease.uuremote", "com.limelight.qiin", "com.xiaomi.mitv.tvplayer", "com.mitv.tvplayer"}
                        : saved.split("\\|"));
                Comparator<Tile> comparator = (a, b) -> {
                    int ai = order.indexOf(a.id), bi = order.indexOf(b.id);
                    if (ai < 0) ai = 1000; if (bi < 0) bi = 1000;
                    return ai != bi ? ai - bi : a.label.compareToIgnoreCase(b.label);
                };
                Collections.sort(apps.tiles, comparator);
                final List<String> otherOrder = Arrays.asList(preferences.getString("order_other", "").split("\\|"));
                Collections.sort(others.tiles, (a, b) -> {
                    int ai = otherOrder.indexOf(a.id), bi = otherOrder.indexOf(b.id);
                    if (ai < 0) ai = 1000; if (bi < 0) bi = 1000;
                    return ai != bi ? ai - bi : a.label.compareToIgnoreCase(b.label);
                });
            } catch (Exception ex) { android.util.Log.e("TVHome", "Application discovery", ex); }
            try {
                TvInputManager manager = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
                if (manager != null) for (TvInputInfo info : manager.getTvInputList()) {
                    if (info.isPassthroughInput() && !info.isHidden(this)) {
                        Tile t = new Tile(); t.kind = 1; t.id = info.getId();
                        CharSequence custom = info.loadCustomLabel(this);
                        t.label = (custom == null ? info.loadLabel(this) : custom).toString();
                        t.intent = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(t.id))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        inputs.tiles.add(t);
                    }
                }
            } catch (Exception ex) { android.util.Log.i("TVHome", "Inputs not exposed by this device", ex); }
            ArrayList<Tile> catalog = new ArrayList<>();
            catalog.addAll(apps.tiles); catalog.addAll(inputs.tiles); catalog.addAll(others.tiles);
            Row favorites = new Row("收藏", "\ue838");
            for (Tile tile : catalog) {
                tile.label = cards.name(tile.id, tile.label);
                String style = cards.cover(tile.id);
                if (style.equals("image")) {
                    Bitmap custom = cards.loadImage(tile.id);
                    if (custom != null) { tile.artwork = custom; tile.banner = true; }
                } else if (!style.equals("original")) {
                    tile.artwork = null; tile.banner = false;
                    tile.tint = style.equals("blue") ? 0xFF183857 : style.equals("green") ? 0xFF194A43 : style.equals("amber") ? 0xFF63432C : 0xFF302D45;
                    tile.glyph = tile.kind == 1 ? "\ue333" : "\ue5c3";
                }
                if (cards.contains("favorites", tile.id) && !cards.contains("hidden", tile.id)) favorites.tiles.add(copyTile(tile));
            }
            for (Row r : Arrays.asList(apps, inputs, others)) {
                Iterator<Tile> iterator = r.tiles.iterator();
                while (iterator.hasNext()) if (cards.contains("hidden", iterator.next().id)) iterator.remove();
            }
            sortSaved(inputs); sortSaved(favorites);
            if (!favorites.tiles.isEmpty()) result.add(favorites);
            if (!apps.tiles.isEmpty()) result.add(apps);
            if (!inputs.tiles.isEmpty()) result.add(inputs);
            if (!others.tiles.isEmpty()) result.add(others);
            handler.post(() -> {
                if (!isDestroyed() && generation == loadGeneration) {
                    allTiles.clear(); allTiles.addAll(catalog);
                    home.rows = result; home.resetToApps();
                    if (focusedId != null) {
                        for (int i = 0; i < result.size(); i++) if (result.get(i).orderKey.equals(focusedRow)) {
                            Row r = result.get(i);
                            for (int j = 0; j < r.tiles.size(); j++) if (r.tiles.get(j).id.equals(focusedId)) { home.row = i; r.selected = j; }
                        }
                    }
                    home.ensureVisible(); home.animateFocus(); home.describeFocus(); home.invalidate();
                }
            });
        });
    }

    private Tile copyTile(Tile source) {
        Tile t = new Tile(); t.id = source.id; t.label = source.label; t.intent = source.intent;
        t.artwork = source.artwork; t.banner = source.banner; t.kind = source.kind; t.tint = source.tint; t.glyph = source.glyph;
        return t;
    }
    private void sortSaved(Row row) {
        final List<String> order = Arrays.asList(preferences.getString(row.orderKey, "").split("\\|"));
        Collections.sort(row.tiles, (a, b) -> {
            int ai = order.indexOf(a.id), bi = order.indexOf(b.id);
            if (ai < 0) ai = 1000; if (bi < 0) bi = 1000;
            return ai == bi ? a.label.compareToIgnoreCase(b.label) : ai-bi;
        });
    }

    private void launch(Tile tile) {
        try { startActivity(tile.intent); }
        catch (ActivityNotFoundException | SecurityException ex) { toast("暂时无法打开「" + tile.label + "」"); }
    }

    private void showDialog(TvDialog dialog) {
        if(isDestroyed()||isFinishing())return;
        if(activeDialog!=null){activeDialog.setOnDismissListener(null);activeDialog.dismiss();}
        // Explicit callbacks which refresh an existing page replace it rather than
        // adding a duplicate ancestor. Keep parent views so focus/scroll survive Back.
        for(int i=0;i<dialogHistory.size();i++)if(dialogHistory.get(i).pageKey().equals(dialog.pageKey())){
            while(dialogHistory.size()>i)dialogHistory.remove(dialogHistory.size()-1);break;
        }
        if(dialogHistory.isEmpty()&&!dialog.pageKey().equals("main")&&(dialog.reorderable()||dialog.pageKey().equals("网络与 Wi-Fi")||dialog.pageKey().equals("壁纸图库")))dialogHistory.add(settingsDialog());
        dialogHistory.add(dialog);presentDialog(dialog);
    }
    private void presentDialog(TvDialog dialog){
        activeDialog=dialog;dialogOpen=true;handler.removeCallbacks(idle);
        home.preview(dialog.previewKey());
        dialog.setBackAction(this::menuBack);
        dialog.setOnDismissListener(d->handler.postDelayed(()->{
            // Item callbacks may open a child, refresh a page, or launch a picker.
            if(activeDialog==dialog&&!dialog.isShowing()&&foreground&&!isFinishing()){
                if(dialog.isForwardDismiss())presentDialog(dialog);else menuBack();
            }
        },100));
        dialog.show();
    }
    private void menuBack(){
        if(dialogHistory.isEmpty())return;
        if(dialogHistory.size()==1){closeMenus();home.requestFocus();armIdle();return;}
        if(activeDialog!=null){activeDialog.setOnDismissListener(null);activeDialog.dismiss();}
        dialogHistory.remove(dialogHistory.size()-1);
        presentDialog(dialogHistory.get(dialogHistory.size()-1));
    }
    private void closeMenus(){
        menuEpoch++;TvDialog previous=activeDialog;activeDialog=null;dialogHistory.clear();dialogOpen=false;
        if(previous!=null){previous.setOnDismissListener(null);previous.dismiss();}
        if(home!=null)home.preview(null);
    }
    private void systemPanel(){
        final int epoch=menuEpoch;
        SystemIntegration.show(this,d->{if(epoch==menuEpoch)showDialog(d);});
    }

    private void settings(){showDialog(settingsDialog());}
    private TvDialog settingsDialog() {
        int idleSeconds = preferences.getInt("idle_seconds", 30);
        String idleLabel = idleSeconds == 0 ? "不隐藏" : idleSeconds + " 秒";
        String[] options = {"时钟与屏保", "壁纸图库", "更换本地壁纸", "设备设置", "关于 Marco TV", "开机与默认桌面", "卡片外观", "恢复隐藏的卡片"};
        TvDialog dialog = new TvDialog.Builder(this).setTitle("桌面设置")
                .setOrder("main","clock","wallpapers","local_wallpaper","device","about","startup","cards","hidden")
                .setItems(options, (d, which) -> {
                    switch (which) {
                        case 0: handler.post(this::ambientSettings); break;
                        case 1: handler.post(this::wallpaperGallery); break;
                        case 2:
                            pendingCoverId = "__wallpaper__"; handler.post(this::chooseCoverImage);
                            break;
                        case 3: handler.post(this::deviceSettings); break;
                        case 4: handler.post(this::about); break;
                        case 5: handler.post(this::systemPanel); break;
                        case 6: handler.post(this::appearanceSettings); break;
                        case 7: handler.post(this::hiddenCards); break;
                    }
                }).setNegativeButton("返回", null).create();
        return dialog;
    }

    private void about(){
        showDialog(new TvDialog.Builder(this).setTitle("Marco TV")
                .setMessage(ProjectInfo.about())
                .setPositiveButton("返回",null)
                .setNeutralButton("开源许可",(d,w)->handler.post(()->showDialog(new TvDialog.Builder(this)
                        .setTitle("开源许可")
                        .setMessage(ProjectInfo.licenses(this))
                        .setPositiveButton("返回上一级",null).create())))
                .create());
    }

    private void slider(String title, String key, int min, int max, int fallback, String unit) {
        ContinuousSlider slider = new ContinuousSlider(this, title, unit, min, max, preferences.getInt(key, fallback), value -> {
            preferences.edit().putInt(key, value).apply(); home.refreshAppearance(); home.invalidate();
            if(key.equals("ambient_transition"))home.replayPreview();
        });
        slider.compact();
        TvDialog.Builder builder=new TvDialog.Builder(this).setTitle(title).setPreview(key)
                .setView(slider).setPositiveButton("返回上一级",null);
        if(key.equals("ambient_transition"))builder.setNeutralButton("重播预览",(d,w)->handler.post(()->slider(title,key,min,max,fallback,unit)));
        showDialog(builder.create());
    }

    private void wallpaperGallery() {
        String[] labels = {"雾蓝海岸", "暖沙丘", "极夜山脊", "峡谷", "壁纸亮度"};
        showDialog(new TvDialog.Builder(this).setTitle("壁纸图库").setMessage("四张风景壁纸 · 选择后立即预览")
                .setItems(labels, (d,w) -> {
                    if (w == 4) handler.post(() -> slider("壁纸压暗", "wallpaper_dim", 0, 90, 10, "%"));
                    else { preferences.edit().remove("wallpaper").putInt("wallpaper_builtin", w).apply(); loadWallpaper(); }
                }).setNegativeButton("返回", null).create());
    }

    private void ambientSettings() {
        boolean enabled = AmbientIntegration.enabled(this);
        String[] labels = {"Marco TV 屏保：" + (enabled ? "已开启" : "未开启"), "进入屏保等待", "桌面时间大小", "屏保时间大小", "屏保时间亮度", "屏保背景压暗", "渐变时长", "立即预览屏保"};
        showDialog(new TvDialog.Builder(this).setTitle("时钟与屏保")
                .setOrder("clock","enabled","wait","normal_size","ambient_size","brightness","dim","duration","preview")
                .setMessage("闲置时，右上角时间慢慢放大，背景逐渐暗下。按方向键返回桌面。")
                .setItems(labels, (d,w) -> handler.post(() -> {
                    switch(w) {
                        case 0: preferences.edit().putBoolean("ambient_enabled", !enabled).apply();
                            AmbientIntegration.configure(this);
                            if (!enabled) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                            handler.post(this::ambientSettings); break;
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

    private void previewAmbient(){
        showDialog(new TvDialog.Builder(this).setTitle("屏保预览").setPreview("ambient_transition")
                .setPositiveButton("返回上一级",null)
                .setNeutralButton("重播预览",(d,w)->handler.post(this::previewAmbient)).create());
    }

    private void deviceSettings() {
        showDialog(new TvDialog.Builder(this).setTitle("设备设置")
                .setOrder("device","network","sound","info","startup")
                .setMessage("常用设置，在桌面里完成")
                .setItems(new String[]{"网络与 Wi-Fi", "声音", "设备信息", "开机与默认桌面"}, (d, w) -> handler.post(() -> {
                    if (w == 0) network.show();
                    else if (w == 1) soundSettings();
                    else if (w == 2) showDialog(new TvDialog.Builder(this).setTitle("设备信息")
                            .setMessage(Build.MANUFACTURER + " · " + Build.MODEL + "\nAndroid " + Build.VERSION.RELEASE
                                    + "\nMarco TV " + ProjectInfo.VERSION + "\n作者 " + ProjectInfo.AUTHOR)
                            .setPositiveButton("返回", (dialog, which) -> handler.post(this::deviceSettings)).create());
                    else systemPanel();
                })).setNegativeButton("返回", (d,w) -> handler.post(this::settings)).create());
    }

    private void soundSettings() {
        android.media.AudioManager audio = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        int value = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        int max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        ContinuousSlider slider = new ContinuousSlider(this, "媒体音量", "", 0, Math.max(1,max), value, v -> {
            try { audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0); }
            catch (RuntimeException e) { toast("系统限制音量调整，请使用遥控器音量键"); }
        });
        showDialog(new TvDialog.Builder(this).setTitle("声音").setMessage("左右调整媒体音量")
                .setView(slider).setPositiveButton("完成", null).create());
    }

    private void tileOptions() {
        if (home.row < 0 || home.rows.isEmpty()) return;
        Row row = home.rows.get(home.row);
        if (row.tiles.isEmpty()) return;
        Tile tile = row.tiles.get(row.selected);
        boolean favorite = cards.contains("favorites", tile.id);
        String[] options = {"向左移动", "向右移动", "重命名", "更换封面", favorite ? "取消收藏" : "加入收藏", "隐藏卡片", "恢复默认外观", tile.kind == 1 ? "打开输入源" : "应用信息"};
        showDialog(new TvDialog.Builder(this).setTitle(tile.label).setItems(options, (d, which) -> {
            if (which < 2) {
                int next = row.selected + (which == 0 ? -1 : 1);
                if (next >= 0 && next < row.tiles.size()) {
                    Collections.swap(row.tiles, row.selected, next); row.selected = next;
                    StringBuilder order = new StringBuilder();
                    for (Tile t : row.tiles) { if (order.length() > 0) order.append('|'); order.append(t.id); }
                    preferences.edit().putString(row.orderKey, order.toString()).apply();
                    home.ensureVisible(); home.animateFocus(); home.describeFocus();
                }
            } else handler.post(() -> {
                switch (which) {
                    case 2: renameCard(tile); break;
                    case 3: coverOptions(tile); break;
                    case 4: cards.toggle("favorites", tile.id, !favorite); loadApplications(); toast(favorite ? "已取消收藏" : "已加入收藏，按上键可到收藏分组"); break;
                    case 5:
                        showDialog(new TvDialog.Builder(this).setTitle("隐藏「" + tile.label + "」？")
                                .setMessage("从桌面与收藏中隐藏，不卸载应用。可在桌面设置中恢复。")
                                .setPositiveButton("隐藏", (dialog, button) -> { cards.toggle("hidden", tile.id, true); loadApplications(); })
                                .setNegativeButton("返回", null).create()); break;
                    case 6: cards.reset(tile.id); loadApplications(); break;
                    case 7:
                        if (tile.kind == 1) launch(tile);
                        else {
                            String version = "";
                            try { version = getPackageManager().getPackageInfo(tile.id, 0).versionName; } catch (Exception ignored) { }
                            showDialog(new TvDialog.Builder(this).setTitle(tile.label).setMessage("版本 " + version + "\n" + tile.id)
                                    .setPositiveButton("打开应用", (dialog, button) -> launch(tile))
                                    .setNegativeButton("返回", null).create());
                        }
                        break;
                }
            });
        }).setNegativeButton("返回", null).create());
    }

    private void renameCard(Tile tile) {
        EditText input = new EditText(this);
        input.setSingleLine(true); input.setText(tile.label); input.selectAll();
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        input.setHint("输入卡片名称"); input.setContentDescription("卡片名称");
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding / 2, padding, padding / 2);
        showDialog(new TvDialog.Builder(this).setTitle("重命名卡片").setView(input)
                .setPositiveButton("保存", (d, w) -> { cards.setName(tile.id, input.getText().toString()); loadApplications(); })
                .setNeutralButton("原始名称", (d, w) -> { cards.setName(tile.id, ""); loadApplications(); })
                .setNegativeButton("返回", null).create());
    }

    private void coverOptions(Tile tile) {
        String[] names = {"原始图标或横幅", "本地图片…", "午夜蓝", "森林绿", "暖琥珀", "雾紫"};
        String[] styles = {"original", "image", "blue", "green", "amber", "purple"};
        showDialog(new TvDialog.Builder(this).setTitle("更换封面 · " + tile.label)
                .setItems(names, (d, which) -> {
                    if (which == 1) { pendingCoverId = tile.id; handler.post(this::chooseCoverImage); }
                    else { cards.cover(tile.id, styles[which]); loadApplications(); }
                }).setNegativeButton("返回", null).create());
    }

    private void chooseCoverImage() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (getPackageManager().resolveActivity(picker, 0) != null) {
            try { startActivityForResult(picker, PICK_COVER); return; } catch (RuntimeException ignored) { }
        }
        if (Build.VERSION.SDK_INT >= 33) { toast("请安装支持选择图片的文件管理器"); pendingCoverId = null; return; }
        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, READ_COVER); return;
        }
        browseCoverFolder(Environment.getExternalStorageDirectory());
    }

    private void browseCoverFolder(File folder) {
        if (pendingCoverId == null) return;
        File root = Environment.getExternalStorageDirectory();
        try {
            String base = root.getCanonicalPath(), path = folder.getCanonicalPath();
            if (!path.equals(base) && !path.startsWith(base + File.separator)) { toast("请选择共享存储中的图片"); return; }
        } catch (IOException e) { toast("无法读取此目录"); return; }
        File[] found = folder.listFiles(f -> !f.getName().startsWith(".") && (f.isDirectory()
                || f.getName().toLowerCase(Locale.ROOT).matches(".*\\.(png|jpg|jpeg|webp)")));
        ArrayList<File> files = new ArrayList<>(); if (found != null) files.addAll(Arrays.asList(found));
        Collections.sort(files, (a, b) -> a.isDirectory() != b.isDirectory() ? (a.isDirectory() ? -1 : 1) : a.getName().compareToIgnoreCase(b.getName()));
        if (files.size() > 300) { files = new ArrayList<>(files.subList(0, 300)); toast("此目录仅显示前 300 项，请将封面放入单独文件夹"); }
        boolean parent = !folder.equals(root);
        ArrayList<String> labels = new ArrayList<>(); if (parent) labels.add("↑ 上一级");
        for (File file : files) labels.add((file.isDirectory() ? "文件夹 · " : "") + file.getName());
        if (labels.isEmpty()) {
            showDialog(new TvDialog.Builder(this).setTitle("此文件夹没有图片").setMessage("可把 PNG、JPG 或 WebP 图片放入电视的 Download 或 Pictures 文件夹。")
                    .setPositiveButton("返回目录", (d, w) -> handler.post(() -> browseCoverFolder(parent ? folder.getParentFile() : root)))
                    .setNegativeButton("取消", (d, w) -> pendingCoverId = null).create()); return;
        }
        final ArrayList<File> choices = files;
        showDialog(new TvDialog.Builder(this).setTitle(folder.equals(root) ? "电视存储 · 选择图片" : folder.getName())
                .setItems(labels.toArray(new String[0]), (d, which) -> handler.post(() -> {
                    if (parent && which == 0) { browseCoverFolder(folder.getParentFile()); return; }
                    File selected = choices.get(which - (parent ? 1 : 0));
                    if (selected.isDirectory()) browseCoverFolder(selected); else importCover(Uri.fromFile(selected));
                })).setNegativeButton("取消", (d, w) -> pendingCoverId = null).create());
    }

    private void importCover(Uri uri) {
        final String id = pendingCoverId; pendingCoverId = null;
        if (id == null) { toast("请重新选择要编辑的卡片"); return; }
        toast("正在保存封面…");
        loader.execute(() -> {
            try {
                if (id.equals("__wallpaper__")) {
                    BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                    try (InputStream check = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(check, null, bounds); }
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Invalid image");
                    android.util.AtomicFile file = new android.util.AtomicFile(new File(getFilesDir(), "wallpaper-image"));
                    FileOutputStream output = null;
                    try (InputStream input = getContentResolver().openInputStream(uri)) {
                        output = file.startWrite(); byte[] buffer = new byte[8192]; int count; long total = 0;
                        while ((count = input.read(buffer)) >= 0) {
                            total += count; if (total > 32 * 1024 * 1024) throw new IOException("Image too large");
                            output.write(buffer, 0, count);
                        }
                        file.finishWrite(output); output = null;
                    } catch (IOException | RuntimeException error) { if (output != null) file.failWrite(output); throw error; }
                    preferences.edit().putString("wallpaper", Uri.fromFile(file.getBaseFile()).toString()).apply();
                    handler.post(() -> { if (!isDestroyed()) { loadWallpaper(); toast("壁纸已保存"); } });
                } else {
                    cards.importImage(id, uri);
                    handler.post(() -> { if (!isDestroyed()) { loadApplications(); toast("封面已保存"); } });
                }
            } catch (IOException | RuntimeException e) {
                handler.post(() -> { if (!isDestroyed()) toast("图片无法读取，请换一张图片"); });
            }
        });
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == NetworkPanel.PERMISSION) { network.permissionResult(grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED); return; }
        if (request == READ_COVER) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) chooseCoverImage();
            else { pendingCoverId = null; toast("未获得图片读取权限，仍可使用内置封面"); }
        }
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("pending_cover", pendingCoverId); super.onSaveInstanceState(state);
    }

    private void hiddenCards() {
        ArrayList<Tile> hidden = new ArrayList<>();
        for (Tile tile : allTiles) if (cards.contains("hidden", tile.id)) hidden.add(tile);
        if (hidden.isEmpty()) { toast("没有隐藏的卡片"); return; }
        String[] labels = new String[hidden.size()];
        for (int i = 0; i < hidden.size(); i++) labels[i] = hidden.get(i).label;
        showDialog(new TvDialog.Builder(this).setTitle("选择要恢复的卡片").setItems(labels, (d, which) -> {
            cards.toggle("hidden", hidden.get(which).id, false); loadApplications();
        }).setNeutralButton("全部恢复", (d, w) -> {
            for (String id : cards.set("hidden")) cards.toggle("hidden", id, false); loadApplications();
        }).setNegativeButton("返回", null).create());
    }

    private void appearanceSettings() {
        showDialog(new TvDialog.Builder(this).setTitle("卡片外观")
                .setOrder("cards","radius","zoom","labels","reset")
                .setItems(new String[]{"卡片圆角", "选中放大幅度", "未选中名称亮度", "恢复默认外观设置"}, (d,w) -> handler.post(() -> {
                    switch(w) {
                        case 0: slider("卡片圆角", "card_radius", 0, 90, 32, ""); break;
                        case 1: slider("选中放大幅度", "focus_scale", 0, 30, 16, "%"); break;
                        case 2: slider("未选中名称亮度", "label_opacity", 0, 100, 0, "%"); break;
                        case 3: preferences.edit().remove("card_radius").remove("focus_scale").remove("label_opacity").apply(); home.refreshAppearance(); home.invalidate(); break;
                    }
                })).setNegativeButton("返回", null).create());
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_COVER) {
            if (result == RESULT_OK && data != null && data.getData() != null) importCover(data.getData());
            else pendingCoverId = null;
            return;
        }
        if (request == PICK_WALLPAPER && result == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try { getContentResolver().takePersistableUriPermission(uri, data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception ignored) { }
            preferences.edit().putString("wallpaper", uri.toString()).apply(); loadWallpaper();
        }
    }

    private final class HomeView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Typeface normal = Typeface.create("sans-serif", Typeface.NORMAL);
        private final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
        private final Typeface symbols = Typeface.createFromAsset(getAssets(), "MaterialIcons-Regular.ttf");
        private final SimpleDateFormat time = new SimpleDateFormat("HH:mm", Locale.getDefault());
        private ArrayList<Row> rows = new ArrayList<>();
        private Bitmap wallpaper;
        private int row = 0, topSelected = 0;
        private float uiAlpha = 1, ambientProgress;
        private final ClockScene clockScene = new ClockScene();
        private boolean idleMode, wakingKey, longPressed;
        private ValueAnimator fade;
        private final RectF rect = new RectF();
        private final RectF iconBounds = new RectF();
        private final Matrix artworkMatrix = new Matrix();
        private final Paint artworkPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final MotionSpring section = new MotionSpring(0, 20, 0.001f);
        private final Choreographer.FrameCallback motionFrame = this::advanceMotion;
        private boolean motionScheduled;
        private long lastFrameNanos;
        private float scale = 1, offsetY;
        private float radius = 70, focusScale = .24f;
        private float labelOpacity;
        private final MotionSpring edge=new MotionSpring(0,20,.001f);
        private boolean edgeOpen;
        private int edgeSelected;
        private final String[] edgeItems={"编辑当前卡片","卡片外观","时钟与屏保","桌面设置"};
        void edge(boolean open){edgeOpen=open;edge.target(open?1:0);animateFocus();describeFocus();}
        private void edgeAction(){
            int action=edgeSelected;edge(false);
            if(action==0)tileOptions();else if(action==1)appearanceSettings();else if(action==2)ambientSettings();else settings();
        }
        private String previewKey;
        private float previewAmbient;
        private ValueAnimator previewFade;
        private int previewSavedRow=-2;
        void preview(String key){
            if(key!=null&&previewSavedRow==-2){previewSavedRow=row;row=Math.max(0,row);animateFocus();}
            else if(key==null&&previewSavedRow!=-2){row=previewSavedRow;previewSavedRow=-2;animateFocus();}
            previewKey=key;
            if(previewFade!=null)previewFade.cancel();
            previewAmbient=key!=null&&key.startsWith("ambient_")?1:0;
            if("ambient_transition".equals(key))replayPreview();
            invalidate();
        }
        void replayPreview(){
            if(!"ambient_transition".equals(previewKey))return;
            if(previewFade!=null)previewFade.cancel();
            previewAmbient=0;previewFade=ValueAnimator.ofFloat(0,1);
            previewFade.setDuration(preferences.getInt("ambient_transition",3)*1000L);
            previewFade.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            previewFade.addUpdateListener(a->{previewAmbient=(float)a.getAnimatedValue();invalidate();});previewFade.start();
        }

        HomeView() {
            super(MainActivity.this); setFocusable(true); setFocusableInTouchMode(true);
            setContentDescription("Marco TV。方向键选择应用，确定键打开，菜单键设置。");
            // Keep the window GPU renderer, without caching the entire animated screen
            // in an extra hardware layer on memory-constrained television GPUs.
            setLayerType(View.LAYER_TYPE_NONE, null);
            refreshAppearance();
        }

        void refreshAppearance() {
            radius = preferences.getInt("card_radius", 32);
            focusScale = preferences.getInt("focus_scale", 16) / 100f;
            labelOpacity = preferences.getInt("label_opacity", preferences.getInt("label_mode", 0) == 1 ? 55 : 0) / 100f;
        }
        void describeFocus() {
            if (rows.isEmpty()) setContentDescription("Marco TV，暂无可见卡片，按菜单键恢复隐藏的卡片");
            else if (row >= 0 && row < rows.size() && !rows.get(row).tiles.isEmpty())
                setContentDescription(rows.get(row).title + "，" + rows.get(row).tiles.get(rows.get(row).selected).label);
        }

        void resetToApps() {
            edgeOpen=false;edge.snap(0);
            stopMotion(); row = 0; section.snap(0);
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i); r.selected = Math.max(0, Math.min(r.selected, r.tiles.size() - 1));
                for (int j = 0; j < r.tiles.size(); j++) r.tiles.get(j).emphasis.snap(i == 0 && j == r.selected ? 1 : 0);
            }
            ensureVisible(); animateFocus(); invalidate();
        }

        void setIdle(boolean value) {
            if (value == idleMode && ((fade != null && fade.isRunning()) || ambientProgress == (value ? 1 : 0))) { if (!value) armIdle(); return; }
            idleMode = value;
            if (fade != null) fade.cancel();
            fade = ValueAnimator.ofFloat(ambientProgress, value ? 1 : 0);
            fade.setDuration(value ? preferences.getInt("ambient_transition",3) * 1000L : 400);
            fade.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            fade.addUpdateListener(a -> { ambientProgress = (float) a.getAnimatedValue(); uiAlpha = 1 - Math.min(1, ambientProgress * 4); invalidate(); });
            fade.start();
            if (!value) armIdle();
        }

        private void animateFocus() {
            section.target(Math.max(0, row));
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                for (int j = 0; j < r.tiles.size(); j++) r.tiles.get(j).emphasis.target(i == row && j == r.selected ? 1 : 0);
            }
            if (!motionScheduled && foreground && isAttachedToWindow()) {
                motionScheduled = true; lastFrameNanos = 0;
                Choreographer.getInstance().postFrameCallback(motionFrame);
            }
            invalidate();
        }

        private void advanceMotion(long frameNanos) {
            if (!foreground || !isAttachedToWindow()) { motionScheduled = false; return; }
            float dt = lastFrameNanos == 0 ? 1f / 60 : Math.min(0.05f, (frameNanos - lastFrameNanos) / 1_000_000_000f);
            lastFrameNanos = frameNanos;
            boolean moving = section.step(dt);moving|=edge.step(dt);
            for (Row r : rows) {
                moving |= r.scroll.step(dt);
                for (Tile t : r.tiles) moving |= t.emphasis.step(dt);
            }
            invalidate();
            if (moving) Choreographer.getInstance().postFrameCallback(motionFrame);
            else { motionScheduled = false; lastFrameNanos = 0; }
        }

        void stopMotion() {
            Choreographer.getInstance().removeFrameCallback(motionFrame);
            motionScheduled = false; lastFrameNanos = 0;
        }

        @Override protected void onDetachedFromWindow() { stopMotion(); super.onDetachedFromWindow(); }

        private float clampedEmphasis(Tile t) {
            return Math.max(0, Math.min(1, t.emphasis.value));
        }

        void ensureVisible() {
            if (row < 0 || row >= rows.size()) return;
            Row r = rows.get(row);
            float next = r.scroll.target;
            float selectedX = 272 + r.selected * 350 - next;
            if (selectedX > 1630) next += selectedX - 1630;
            if (selectedX < 272) next = Math.max(0, next + selectedX - 272);
            r.scroll.target(next);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float ambient=previewKey==null?ambientProgress:previewAmbient;
            float contentAlpha=previewKey==null?uiAlpha:1-Math.min(1,ambient*4);
            scale = Math.min(getWidth() / 1920f, getHeight() / 1080f);
            offsetY = (getHeight() - 1080 * scale) / 2;
            canvas.drawColor(Color.BLACK);
            if (wallpaper != null) {
                float factor = Math.max(getWidth() / (float) wallpaper.getWidth(), getHeight() / (float) wallpaper.getHeight());
                float w = wallpaper.getWidth() * factor, h = wallpaper.getHeight() * factor;
                rect.set((getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
                paint.setAlpha(255); canvas.drawBitmap(wallpaper, null, rect, paint);
            }
            canvas.save(); canvas.translate((getWidth() - 1920 * scale) / 2, offsetY); canvas.scale(scale, scale);
            paint.setColor(Color.argb(ClockScene.dim(preferences,ambient),0,0,0)); canvas.drawRect(0,0,1920,1080,paint);
            clockScene.draw(canvas,preferences,ambient);
            if (contentAlpha > 0.005f) {
                int muted = alphaColor(0xC0FFFFFF, contentAlpha);
                float wifiX = clockScene.left(preferences) - 70;
                icon(canvas, "\ue8b8", 132, 110, 40, muted);
                text(canvas, "MARCO TV", 178, 119, 23, normal, alphaColor(0xB0FFFFFF,contentAlpha), Paint.Align.LEFT);
                text(canvas, "by Pilipala", 178, 145, 14, normal, alphaColor(0x78FFFFFF,contentAlpha), Paint.Align.LEFT);
                icon(canvas, "\ue63e", wifiX, 110, 38, muted);
                if (row == -1) {
                    float selectedX = topSelected == 0 ? 132 : wifiX;
                    paint.setColor(alphaColor(0x28FFFFFF, contentAlpha));canvas.drawCircle(selectedX,110,34,paint);
                    icon(canvas,topSelected==0?"\ue8b8":"\ue63e",selectedX,110,40,alphaColor(Color.WHITE,contentAlpha));
                }
                if (rows.isEmpty()) text(canvas, allTiles.isEmpty() ? "正在读取应用…" : "没有可见卡片，按菜单键恢复", 112, 480, 34, normal, alphaColor(Color.WHITE, contentAlpha), Paint.Align.LEFT);
                else {
                    // Keep the moving shelves inside the content area, clear of the fixed clock.
                    int shelfClip = canvas.save(); canvas.clipRect(0, previewKey==null?285:210, 1920, previewKey==null?925:660);
                    for (int i = 0; i < rows.size(); i++) {
                        float distance = i - section.value;
                        if (Math.abs(distance) < 1.35f) {
                            float opacity = contentAlpha * Math.max(0, 1 - Math.abs(distance) * 0.75f);
                            canvas.save();canvas.translate(edge.value*390,0);
                            drawRow(canvas, rows.get(i), (previewKey==null?660:450) + distance * 515, opacity*(1-edge.value*.35f));canvas.restore();
                        }
                    }
                    canvas.restoreToCount(shelfClip);
                    int next = Math.max(0, row) + 1;
                    if (next < rows.size() && previewKey==null) {
                        Row r = rows.get(next);
                        icon(canvas, r.symbol, 136, 1003, 48, alphaColor(Color.WHITE, contentAlpha * .3f));
                        text(canvas, r.title, 176, 1010, 34, bold, alphaColor(Color.WHITE, contentAlpha * .3f), Paint.Align.LEFT);
                    }
                }
                if(edge.value>.002f&&previewKey==null)drawEdge(canvas,contentAlpha);
            }
            canvas.restore();
        }

        private int alphaColor(int color, float a) { return (color & 0x00FFFFFF) | (Math.round(Color.alpha(color) * a) << 24); }

        private void drawEdge(Canvas canvas,float opacity){
            float a=Math.max(0,Math.min(1,edge.value))*opacity;
            canvas.save();canvas.translate(-80*(1-edge.value),0);
            paint.setColor(alphaColor(0xEE17222E,a));rect.set(70,390,438,875);canvas.drawRoundRect(rect,28,28,paint);
            text(canvas,rows.get(Math.max(0,row)).title,100,438,25,bold,alphaColor(0xFFDCE8EE,a),Paint.Align.LEFT);
            for(int i=0;i<edgeItems.length;i++){
                float y=465+i*78;
                if(i==edgeSelected){paint.setColor(alphaColor(0xFFE4F0F5,a));rect.set(88,y,420,y+65);canvas.drawRoundRect(rect,16,16,paint);}
                text(canvas,edgeItems[i],110,y+42,25,normal,alphaColor(i==edgeSelected?0xFF152331:0xFFCDD7E0,a),Paint.Align.LEFT);
            }
            text(canvas,"右键返回卡片",100,838,19,normal,alphaColor(0xFFA1B3C3,a),Paint.Align.LEFT);canvas.restore();
        }

        private void text(Canvas canvas, String text, float x, float y, float size, Typeface font, int color, Paint.Align align) {
            paint.setColor(color); paint.setTypeface(font); paint.setTextSize(size); paint.setTextAlign(align);
            canvas.drawText(text, x, y, paint);
        }

        private void icon(Canvas canvas, String glyph, float x, float y, float size, int color) {
            paint.setTypeface(symbols); paint.setTextSize(size); paint.setTextAlign(Paint.Align.CENTER); paint.setColor(color);
            canvas.drawText(glyph, x, y - (paint.ascent() + paint.descent()) / 2, paint);
        }

        private void drawRow(Canvas canvas, Row r, float y, float opacity) {
            icon(canvas, r.symbol, 136, y - 172, 48, alphaColor(Color.WHITE, opacity));
            text(canvas, r.title, 176, y - 165, 34, bold, alphaColor(Color.WHITE, opacity), Paint.Align.LEFT);
            int raised = -1;
            for (int i = 0; i < r.tiles.size(); i++) if (raised < 0 || r.tiles.get(i).emphasis.value > r.tiles.get(raised).emphasis.value) raised = i;
            for (int i = 0; i < r.tiles.size(); i++) if (i != raised) drawTile(canvas, r.tiles.get(i), 272 + i * 350 - r.scroll.value, y, opacity);
            if (raised >= 0) drawTile(canvas, r.tiles.get(raised), 272 + raised * 350 - r.scroll.value, y, opacity);
        }

        private void drawTile(Canvas canvas, Tile tile, float x, float shelfY, float opacity) {
            if (x < -250 || x > 2180) return;
            float emphasis = clampedEmphasis(tile);
            float zoom = 1 + focusScale * emphasis;
            float y = shelfY - 6 * emphasis;
            float w = 320 * zoom, h = 180 * zoom;
            rect.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2);
            paint.setColor(alphaColor(tile.tint != 0 ? tile.tint : tile.banner ? Color.BLACK : 0xFF243541, opacity));
            canvas.drawRoundRect(rect, radius * zoom, radius * zoom, paint);
            if (tile.artwork != null) {
                if (tile.banner) {
                    if (tile.shader == null) tile.shader = new BitmapShader(tile.artwork, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    artworkMatrix.setScale(rect.width() / tile.artwork.getWidth(), rect.height() / tile.artwork.getHeight());
                    artworkMatrix.postTranslate(rect.left, rect.top); tile.shader.setLocalMatrix(artworkMatrix);
                    artworkPaint.setShader(tile.shader); artworkPaint.setAlpha(Math.round(255 * opacity));
                    canvas.drawRoundRect(rect, radius * zoom, radius * zoom, artworkPaint);
                    artworkPaint.setShader(null);
                } else {
                    float size = 116 * zoom;
                    iconBounds.set(x - size / 2, y - size / 2, x + size / 2, y + size / 2);
                    artworkPaint.setAlpha(Math.round(255 * opacity));
                    canvas.drawBitmap(tile.artwork, null, iconBounds, artworkPaint);
                }
            } else if (tile.tint != 0) {
                icon(canvas, tile.glyph, x, y - 20 * zoom, 60 * zoom, alphaColor(0xB0FFFFFF, opacity));
                if (tile.coverLabel == null) tile.coverLabel = clippedLabel(tile.label, 235, 28);
                text(canvas, tile.coverLabel, x, y + 48 * zoom, 28 * zoom, bold, alphaColor(Color.WHITE, opacity), Paint.Align.CENTER);
            } else icon(canvas, tile.kind == 1 ? "\ue333" : "\ue859", x, y, 112 * zoom, alphaColor(Color.WHITE, opacity));
            if (emphasis > .005f || labelOpacity > 0) {
                if (tile.displayLabel == null) {
                    tile.displayLabel = clippedLabel(tile.label, 300, 36);
                }
                text(canvas, tile.displayLabel, Math.max(112, x - 160), shelfY + 182, 36, bold,
                        alphaColor(Color.WHITE, opacity * (labelOpacity + (1-labelOpacity) * emphasis)), Paint.Align.LEFT);
            }
        }

        private String clippedLabel(String value, float width, float size) {
            paint.setTypeface(bold); paint.setTextSize(size);
            if (paint.measureText(value) <= width) return value;
            int end = value.length();
            while (end > 0 && paint.measureText(value.substring(0, end) + "…") > width)
                end = value.offsetByCodePoints(end, -1);
            return value.substring(0, end) + "…";
        }

        @Override public boolean onKeyDown(int code, KeyEvent event) {
            boolean navigation = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                    || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                    || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU;
            if (!navigation) return super.onKeyDown(code, event);
            if (idleMode) { wakingKey = true; setIdle(false); return true; }
            armIdle();
            if(edgeOpen){
                if(code==KeyEvent.KEYCODE_DPAD_RIGHT||code==KeyEvent.KEYCODE_BACK)edge(false);
                else if(code==KeyEvent.KEYCODE_DPAD_UP)edgeSelected=Math.max(0,edgeSelected-1);
                else if(code==KeyEvent.KEYCODE_DPAD_DOWN)edgeSelected=Math.min(edgeItems.length-1,edgeSelected+1);
                else if(code==KeyEvent.KEYCODE_MENU){edge(false);settings();}
                if(edgeOpen)setContentDescription("分类操作栏，"+edgeItems[edgeSelected]);else describeFocus();
                invalidate();return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                if (event.getRepeatCount() == 0) { longPressed = false; event.startTracking(); }
                else if (event.isLongPress() && !longPressed && row >= 0) { longPressed = true; tileOptions(); }
                return true;
            }
            if (code == KeyEvent.KEYCODE_MENU) { if (event.getRepeatCount() == 0) settings(); return true; }
            if (code == KeyEvent.KEYCODE_BACK) {
                if (row != 0) { row = 0; ensureVisible(); animateFocus(); } else setIdle(true);
                return true;
            }
            if (rows.isEmpty()) { if (code == KeyEvent.KEYCODE_DPAD_UP) { row = -1; invalidate(); } return true; }
            if (row == -1) {
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) row = 0;
                else if (code == KeyEvent.KEYCODE_DPAD_LEFT) topSelected = 0;
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) topSelected = 1;
                ensureVisible(); animateFocus();
            } else {
                Row r = rows.get(row);
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if(r.selected==0&&event.getRepeatCount()==0){edgeSelected=0;edge(true);setContentDescription("分类操作栏，"+edgeItems[0]);return true;}
                    r.selected=Math.max(0,r.selected-1);
                }
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) r.selected = Math.min(r.tiles.size() - 1, r.selected + 1);
                else if (code == KeyEvent.KEYCODE_DPAD_UP) row--;
                else if (code == KeyEvent.KEYCODE_DPAD_DOWN) row = Math.min(rows.size() - 1, row + 1);
                ensureVisible(); animateFocus();
            }
            if (row >= 0 && row < rows.size() && !rows.get(row).tiles.isEmpty()) {
                String label = rows.get(row).tiles.get(rows.get(row).selected).label;
                setContentDescription(rows.get(row).title + "，" + label); announceForAccessibility(label);
            }
            invalidate(); return true;
        }

        @Override public boolean onKeyUp(int code, KeyEvent event) {
            if (wakingKey) { wakingKey = false; return true; }
            if(edgeOpen&&(code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER)){edgeAction();return true;}
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                if (longPressed) { longPressed = false; return true; }
                if (row == -1) { if (topSelected == 0) settings(); else network.show(); }
                else if (!rows.isEmpty()) { Row r = rows.get(row); if (!r.tiles.isEmpty()) launch(r.tiles.get(r.selected)); }
                return true;
            }
            return super.onKeyUp(code, event);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() != MotionEvent.ACTION_UP) return true;
            if (idleMode) { setIdle(false); return true; }
            armIdle();
            float x = (event.getX() - (getWidth() - 1920 * scale) / 2) / scale;
            float y = (event.getY() - offsetY) / scale;
            if(edgeOpen){
                if(x>=88&&x<=420&&y>=465&&y<777){edgeSelected=Math.min(3,(int)((y-465)/78));edgeAction();}
                else edge(false);return true;
            }
            if (y < 205 && x < 380) { settings(); return true; }
            if (y < 205 && Math.abs(x-(clockScene.left(preferences)-70)) < 65) { network.show(); return true; }
            if (!rows.isEmpty() && y > 520 && y < 810) {
                Row r = rows.get(Math.max(0, row));
                int index = Math.round((x - 272 + r.scroll.value) / 350);
                if (index >= 0 && index < r.tiles.size()) {
                    if (row >= 0 && index == r.selected) launch(r.tiles.get(index));
                    else { row = Math.max(0, row); r.selected = index; ensureVisible(); animateFocus(); }
                }
            }
            return true;
        }
    }
}
