// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

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
import android.view.*;
import android.widget.Toast;
import java.util.*;
import java.util.concurrent.*;

/** A small, offline Android TV launcher. All application artwork comes from installed packages. */
public final class MainActivity extends Activity {
    private static final int PICK_WALLPAPER = 100;
    CardStore cards;
    NetworkPanel network;
    SettingsNavigator navigator;
    CoverManager cover;
    AppLibrary library;
    LocalFiles files;
    BackupPanel backups;
    HealthPanel health;
    RecentApps recents;
    private final HomePressTracker homePressTracker=new HomePressTracker();
    String focusAfterLoad;
    NowPlaying playback;
    private final BroadcastReceiver playbackAccess = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { if(playback!=null)playback.refresh(); }
    };
    private TvDialog activeDialog;
    private final ArrayList<TvDialog> dialogHistory = new ArrayList<>();
    int menuEpoch;
    final ArrayList<Tile> allTiles = new ArrayList<>();
    private int loadGeneration, wallpaperGeneration;
    private boolean ambientRequested;
    final Handler handler = new Handler(Looper.getMainLooper());
    final ExecutorService loader = Executors.newSingleThreadExecutor();
    SharedPreferences preferences;
    HomeView home;
    boolean foreground;
    private boolean dialogOpen;
    private boolean receiverRegistered;
    private final Runnable idle = () -> { if (foreground && !dialogOpen && !home.isMoving() && !recents.visible) home.setIdle(true); };
    private final Runnable clock = new Runnable() {
        @Override public void run() {
            if (foreground) { home.invalidate(); handler.postDelayed(this, 1000); }
        }
    };
    private final BroadcastReceiver packageChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent.getData() != null) ArtworkCache.invalidatePackage(intent.getData().getSchemeSpecificPart());
            loadApplications();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Diagnostics.init(this);
        try {BackupStore.recoverInterrupted(this);} catch(Exception e){Diagnostics.event("RESTORE_ROLLBACK_FAILED");}
        try{MenuMigration.apply(this);}catch(RuntimeException e){Diagnostics.event("MENU_MIGRATION_FAILED");}
        boolean firstInstall=getSharedPreferences("home",0).getAll().isEmpty();
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
        ArtworkCache.configure(this);
        network = new NetworkPanel(this, this::showDialog);
        navigator = new SettingsNavigator(this);
        cover = new CoverManager(this);
        library = new AppLibrary(this);
        files = new LocalFiles(this);backups=new BackupPanel(this);health=new HealthPanel(this);
        recents=new RecentApps(this);
        playback = new NowPlaying(this);
        registerReceiver(playbackAccess, new IntentFilter(PlaybackListenerService.CHANGED));
        if (state != null) cover.pendingId = state.getString("pending_cover");
        fullscreen();
        home = new HomeView(this);
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
        if(getIntent().hasCategory(Intent.CATEGORY_HOME)&&recents.enabled())homePressTracker.press(SystemClock.elapsedRealtime(),preferences.getInt("recent_double_ms",400));
        if(getIntent().getBooleanExtra("recent_apps",false))handler.post(recents::show);
        if(firstInstall&&!getSharedPreferences("onboarding",0).getBoolean("done",false))handler.post(()->{if(foreground)health.guide(0);});
    }

    private void fullscreen() {
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override protected void onResume() {
        super.onResume(); foreground = true; Diagnostics.foreground(true); fullscreen();
        TaskBridgeClient.sync(this);
        if(getIntent().getBooleanExtra("wake_recovery",false)){getIntent().removeExtra("wake_recovery");Diagnostics.event("WAKE_HOME_RESTORED");}
        if (AmbientIntegration.enabled(this)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (home != null) { home.setIdle(false); home.requestFocus(); }
        if (home != null) home.animateFocus();
        handler.removeCallbacks(clock); handler.post(clock); armIdle();
        if (ambientRequested) { ambientRequested = false; handler.removeCallbacks(idle); home.setIdle(true); }
        if(activeDialog!=null&&!activeDialog.isShowing())presentDialog(activeDialog);
        if(playback!=null)playback.refresh();
        if(home!=null&&!home.rows.isEmpty())sortCurrentApps();
    }

    @Override protected void onPause() {
        foreground = false; Diagnostics.foreground(false); if(playback!=null)playback.stop(); handler.removeCallbacks(idle); handler.removeCallbacks(clock);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(recents!=null)recents.stop();
        if (home != null) { home.stopMotion(); if (home.fade != null) home.fade.cancel(); if(home.previewFade!=null)home.previewFade.cancel(); }
        super.onPause();
    }

    @Override public void onLowMemory() { super.onLowMemory(); Diagnostics.event("MEMORY_PRESSURE"); ArtworkCache.clear(); }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_BACKGROUND) ArtworkCache.clear();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (network != null) network.close();
        if (playback != null) { playback.stop(); unregisterReceiver(playbackAccess); }
        closeMenus();
        loader.shutdownNow();
        if (receiverRegistered) unregisterReceiver(packageChanges);
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        boolean recent=intent.getBooleanExtra("recent_apps",false);
        if(!intent.getBooleanExtra("home_press_handled",false)&&intent.hasCategory(Intent.CATEGORY_HOME)&&recents.enabled())recent=homePressTracker.press(SystemClock.elapsedRealtime(),preferences.getInt("recent_double_ms",400));
        else if(!intent.hasCategory(Intent.CATEGORY_HOME))homePressTracker.reset();
        recents.close();
        closeMenus();
        ambientRequested = intent.getBooleanExtra("ambient", false);
        if (home != null) { home.setIdle(false); home.resetToApps(); if (ambientRequested && foreground) { home.setIdle(true); ambientRequested=false; } }
        if(recent)recents.show();
    }

    void armIdle() {
        handler.removeCallbacks(idle);
        int seconds = preferences.getInt("idle_seconds", 30);
        if (foreground && !dialogOpen && (recents==null||!recents.visible) && seconds > 0 && AmbientIntegration.enabled(this)) handler.postDelayed(idle, seconds * 1000L);
    }

    void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }

    void loadWallpaper() {
        final int generation = ++wallpaperGeneration;
        loader.execute(() -> {
            Bitmap ready = WallpaperSource.load(this);
            handler.post(() -> {
                if (!isDestroyed() && generation == wallpaperGeneration) {
                    Bitmap previous = home.wallpaper;
                    home.wallpaper = ready;
                    if (previous != null && previous != ready && previous != home.wallpaper) previous.recycle();
                    home.invalidate();
                }
            });
        });
    }

    private Tile applicationTile(ResolveInfo resolve) {
        PackageManager pm = getPackageManager();
        Tile tile = new Tile();
        tile.id = resolve.activityInfo.packageName;
        tile.label = resolve.loadLabel(pm).toString();
        tile.intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(tile.id, resolve.activityInfo.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        for (boolean banner : new boolean[]{true, false}) {
            Bitmap cached = ArtworkCache.get("pkg:" + tile.id + ":" + (banner ? 1 : 0));
            if (cached != null) { tile.artwork = cached; tile.banner = banner; return tile; }
        }
        Drawable art = null;
        try { art = resolve.activityInfo.loadBanner(pm); } catch (Exception ignored) { }
        tile.banner = art != null;
        if (art == null && (resolve.activityInfo.icon != 0 || resolve.activityInfo.applicationInfo.icon != 0)) {
            try { art = resolve.loadIcon(pm); } catch (Exception ignored) { }
        }
        String cacheKey = "pkg:" + tile.id + ":" + (tile.banner ? 1 : 0);
        if (art != null && !tile.banner) {
            tile.artwork = ArtworkStyle.card(art); tile.banner = true;
            ArtworkCache.put("pkg:"+tile.id+":1",tile.artwork); return tile;
        }
        if (art != null) {
            int w = tile.banner ? 480 : 192, h = tile.banner ? 270 : 192;
            tile.artwork = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(tile.artwork); art.setBounds(0, 0, w, h); art.draw(canvas);
            ArtworkCache.put(cacheKey, tile.artwork);
        }
        return tile;
    }

    void loadApplications() {
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
                for(String id:cards.set("manual_apps")) {
                    try {
                        ComponentName component=ComponentName.unflattenFromString(cards.manual(id));
                        if(component==null || !component.getPackageName().equals(id) || id.equals(getPackageName()))continue;
                        ActivityInfo info=pm.getActivityInfo(component,0);
                        if(!AppLibrary.launchable(this,info))continue;
                        ResolveInfo resolved=new ResolveInfo();resolved.activityInfo=info;
                        for(Iterator<Tile> it=apps.tiles.iterator();it.hasNext();)if(it.next().id.equals(id))it.remove();
                        for(Iterator<Tile> it=others.tiles.iterator();it.hasNext();)if(it.next().id.equals(id))it.remove();
                        apps.tiles.add(applicationTile(resolved));seen.add(id);
                    } catch(PackageManager.NameNotFoundException|RuntimeException ignored) { }
                }
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
                tile.pinned=cards.contains("pinned",tile.id);
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
            sortSaved(inputs); sortSaved(favorites); sortSaved(others);
            AppOrder.sort(apps.tiles,preferences,cards);
            result.add(apps);
            if (!favorites.tiles.isEmpty()) result.add(favorites);
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
                    if(focusAfterLoad!=null){for(int i=0;i<result.size();i++)if(result.get(i).orderKey.equals("order_tv"))for(int j=0;j<result.get(i).tiles.size();j++)if(result.get(i).tiles.get(j).id.equals(focusAfterLoad)){home.row=i;result.get(i).selected=j;}focusAfterLoad=null;}
                    home.ensureVisible(); home.animateFocus(); home.describeFocus(); home.invalidate();
                }
            });
        });
    }

    private Tile copyTile(Tile source) {
        Tile t = new Tile(); t.id = source.id; t.label = source.label; t.intent = source.intent;
        t.pinned = source.pinned; t.artwork = source.artwork; t.banner = source.banner; t.kind = source.kind; t.tint = source.tint; t.glyph = source.glyph;
        return t;
    }
    private void sortSaved(Row row) {
        final List<String> order = Arrays.asList(preferences.getString(row.orderKey, "").split("\\|"));
        Collections.sort(row.tiles, (a, b) -> {
            if(a.pinned!=b.pinned)return a.pinned?-1:1;
            int ai = order.indexOf(a.id), bi = order.indexOf(b.id);
            if (ai < 0) ai = 1000; if (bi < 0) bi = 1000;
            return ai == bi ? a.label.compareToIgnoreCase(b.label) : ai-bi;
        });
    }

    void sortCurrentApps() {
        for(Row r:home.rows)if(r.orderKey.equals("order_tv")&&!r.tiles.isEmpty()) {
            String focused=r.tiles.get(r.selected).id;
            AppOrder.sort(r.tiles,preferences,cards);
            for(int i=0;i<r.tiles.size();i++)if(r.tiles.get(i).id.equals(focused))r.selected=i;
        }
        home.ensureVisible();home.animateFocus();home.describeFocus();
    }

    void launch(Tile tile) {
        try { startActivity(tile.intent); if(tile.kind==0)AppOrder.opened(preferences,tile.id); }
        catch (ActivityNotFoundException | SecurityException ex) { Diagnostics.event("APP_OPEN_FAILED"); toast("暂时无法打开「" + tile.label + "」"); }
    }

    // Entry points used by HomeView; the panels live in SettingsNavigator.
    void settings() { backups.autoSnapshot(); Diagnostics.event("SETTINGS_OPENED"); navigator.settings(); }
    void exitMenus(){closeMenus();home.requestFocus();armIdle();}
    void tileOptions() { navigator.tileOptions(); }
    void appearanceSettings() { navigator.appearanceSettings(); }
    void ambientSettings() { navigator.ambientSettings(); }

    void showDialog(TvDialog dialog) {
        if(isDestroyed()||isFinishing())return;
        if(activeDialog!=null){activeDialog.setOnDismissListener(null);activeDialog.dismiss();}
        // Explicit callbacks which refresh an existing page replace it rather than
        // adding a duplicate ancestor. Keep parent views so focus/scroll survive Back.
        for(int i=0;i<dialogHistory.size();i++)if(dialogHistory.get(i).pageKey().equals(dialog.pageKey())){
            while(dialogHistory.size()>i)dialogHistory.remove(dialogHistory.size()-1);break;
        }
        if(dialogHistory.isEmpty()&&!dialog.pageKey().equals("main")&&(dialog.reorderable()||dialog.pageKey().equals("网络与 Wi-Fi")||dialog.pageKey().equals("壁纸图库")))dialogHistory.add(navigator.settingsDialog());
        dialogHistory.add(dialog);presentDialog(dialog);
    }
    boolean isPage(String id){return activeDialog!=null&&activeDialog.isShowing()&&activeDialog.pageKey().equals(id);}
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
        if(backups!=null)backups.cancel();
        if(network!=null)network.cancelPending();
        menuEpoch++;TvDialog previous=activeDialog;activeDialog=null;dialogHistory.clear();dialogOpen=false;
        if(previous!=null){previous.setOnDismissListener(null);previous.dismiss();}
        if(home!=null)home.preview(null);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == NetworkPanel.PERMISSION) { network.permissionResult(grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED); return; }
        if (request == LocalFiles.READ) {files.permission(grants);return;}
        if (request == CoverManager.READ_COVER) cover.onPermissionResult(grants);
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("pending_cover", cover.pendingId); super.onSaveInstanceState(state);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (files.result(request,result,data)) return;
        if (network.onActivityResult(request)) return;
        if (cover.onActivityResult(request, result, data)) return;
        if (request == PICK_WALLPAPER && result == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try { getContentResolver().takePersistableUriPermission(uri, data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception ignored) { }
            preferences.edit().putString("wallpaper", uri.toString()).apply(); loadWallpaper();
        }
    }
}
