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
    CardStore cards;
    NetworkPanel network;
    SettingsNavigator navigator;
    CoverManager cover;
    AppLibrary library;
    AllAppsDrawer drawer;
    DefaultHomePanel defaultHome;
    LocalFiles files;
    BackupPanel backups;
    HealthPanel health;
    UpdatePanel updates;
    PhoneManager phone;
    CastManager cast;
    PreviewPreferences previewPreferences;
    PreviewPreferences localPreview;
    AppearanceSession appearanceSession;
    SharedPreferences appearance(){
        return localPreview!=null?localPreview:previewPreferences==null?preferences:previewPreferences;
    }
    boolean canShowUpdateNotice(){
        return foreground&&!dialogs.open()&&(recents==null||!recents.visible);
    }
    RecentApps recents;
    private final HomePressTracker homePressTracker=new HomePressTracker();
    String focusAfterLoad;
    NowPlaying playback;
    private final BroadcastReceiver playbackAccess = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if(playback!=null)playback.refresh();
        }
    };
    final DialogStack dialogs = new DialogStack(this);
    private PanelWindow panels;
    PanelWindow panelWindow(){
        if(panels==null)panels=new PanelWindow(this);
        return panels;
    }
    int menuEpoch;
    final ArrayList<Tile> allTiles = new ArrayList<>();
    private final AppCatalog catalog = new AppCatalog(this);
    private final WallpaperLoader wallpapers = new WallpaperLoader(this);
    private boolean ambientRequested;
    final Handler handler = new Handler(Looper.getMainLooper());
    final ExecutorService loader = Executors.newSingleThreadExecutor();
    final LatestTaskQueue appReads=new LatestTaskQueue("Marco-apps");
    final LatestTaskQueue wallpaperReads=new LatestTaskQueue("Marco-wallpaper");
    final LatestTaskQueue taskReads=new LatestTaskQueue("Marco-tasks");
    final LatestTaskQueue taskImages=new LatestTaskQueue("Marco-task-images");
    final ExecutorService taskCommands=Executors.newSingleThreadExecutor();
    SharedPreferences preferences;
    HomeView home;
    boolean foreground;
    private boolean receiverRegistered;
    final IdleController screensaver = new IdleController(this);
    void idleChanged(boolean idle) {
        screensaver.changed(idle);
        scheduleClock();
    }
    private static long nextClockDelay(boolean drifting) {
        long toMinute = 60000L - (System.currentTimeMillis() % 60000L) + 20L;
        return drifting ? Math.min(toMinute, 5000L) : toMinute;
    }
    void scheduleClock() {
        handler.removeCallbacks(clock);
        if (foreground) handler.postDelayed(clock, nextClockDelay(home != null && home.idleMode));
    }
    private final Runnable clock = new Runnable() {
        @Override public void run() {
            if (foreground) {
                home.invalidate();
                scheduleClock();
            }
        }
    };
    private final BroadcastReceiver timeChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (foreground && home != null) {
                home.invalidate();
                scheduleClock();
            }
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
        normalizeViewportMetrics();
        HomeSetup.finishCompatibilityConsent(this);
        HomeSetup.observeHomeIntent(this, getIntent());
        Diagnostics.init(this);
        try {
            BackupStore.recoverInterrupted(this);
        } catch(Exception e){
            Diagnostics.event("RESTORE_ROLLBACK_FAILED");
        }
        try{
            MenuMigration.apply(this);
        }catch(RuntimeException e){
            Diagnostics.event("MENU_MIGRATION_FAILED");
        }
        boolean firstInstall=getSharedPreferences("home",0).getAll().isEmpty();
        SystemIntegration.rememberHome(this);
        SystemIntegration.refreshHomeService(this);
        SystemIntegration.startRecovery(this);
        preferences = getSharedPreferences("home", MODE_PRIVATE);
        previewPreferences = new PreviewPreferences(preferences);
        if (!preferences.getBoolean("identity_v06", false)) {
            preferences.edit().putBoolean("identity_v06", true).putInt("card_radius", 32).putInt("focus_scale", 16)
                    .apply();
        }
        ambientRequested = getIntent().getBooleanExtra("ambient", false);
        if (!preferences.getBoolean("clock_style_v06", false)) preferences.edit().putBoolean("clock_style_v06",true)
                .putInt("clock_size",96).putInt("ambient_transition",3).apply();
        AmbientIntegration.configure(this);
        cards = new CardStore(this);
        ArtworkCache.configure(this);
        network = new NetworkPanel(this, this::showDialog);
        navigator = new SettingsNavigator(this);
        cover = new CoverManager(this);
        library = new AppLibrary(this);
        drawer=new AllAppsDrawer(this);
        defaultHome=new DefaultHomePanel(this);
        files = new LocalFiles(this);
        backups=new BackupPanel(this);
        health=new HealthPanel(this);
        updates=new UpdatePanel(this);
        recents=new RecentApps(this);
        playback = new NowPlaying(this);
        registerReceiver(playbackAccess, new IntentFilter(PlaybackListenerService.CHANGED));
        if (state != null) cover.pendingId = state.getString("pending_cover");
        fullscreen();
        home = new HomeView(this);
        setContentView(home);
        phone = new PhoneManager(this);
        cast = new CastManager(this);
        home.requestFocus();
        loadWallpaper();
        loadApplications();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packageChanges, f);
        IntentFilter timeFilter = new IntentFilter();
        timeFilter.addAction(Intent.ACTION_TIME_CHANGED);
        timeFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        timeFilter.addAction(Intent.ACTION_TIME_TICK);
        registerReceiver(timeChanges, timeFilter);
        receiverRegistered = true;
        if(getIntent().hasCategory(Intent.CATEGORY_HOME)&&recents.enabled())homePressTracker.press(SystemClock.elapsedRealtime(),preferences.getInt("recent_double_ms",
                400));
        if(getIntent().getBooleanExtra("recent_apps",false))handler.post(recents::show);
        if(firstInstall&&!getSharedPreferences("onboarding",0).getBoolean("done",false))handler.post(()->{
            if(foreground)health.guide(0);
        });
    }

    private void normalizeViewportMetrics() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        if (dm == null || dm.widthPixels <= 0 || dm.heightPixels <= 0) return;
        int w = Math.max(dm.widthPixels, dm.heightPixels);
        int h = Math.min(dm.widthPixels, dm.heightPixels);
        float targetDensity = Math.max(0.75f, Math.min(6.0f, Math.min(w / 960f, h / 540f)));
        android.content.res.Configuration cfg = getResources().getConfiguration();
        float rawFontScale = cfg != null && cfg.fontScale > 0 ? cfg.fontScale : 1f;
        float clampedFontScale = Math.max(0.85f, Math.min(1.15f, rawFontScale));
        int targetDpi = Math.round(targetDensity * 160f);
        float targetScaledDensity = targetDensity * clampedFontScale;
        dm.density = targetDensity;
        dm.densityDpi = targetDpi;
        dm.scaledDensity = targetScaledDensity;
        Context app = getApplicationContext();
        if (app != null && app.getResources() != null) {
            android.util.DisplayMetrics appDm = app.getResources().getDisplayMetrics();
            if (appDm != null) {
                appDm.density = targetDensity;
                appDm.densityDpi = targetDpi;
                appDm.scaledDensity = targetScaledDensity;
            }
        }
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        normalizeViewportMetrics();
        if (home != null) home.invalidate();
    }

    private void fullscreen() {
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override protected void onResume() {
        super.onResume();
        normalizeViewportMetrics();
        foreground = true;
        HomeSetup.reconcile(this);
        Diagnostics.foreground(true);
        fullscreen();
        TaskBridgeClient.sync(this);
        if(getIntent().getBooleanExtra("wake_recovery",false)){
            getIntent().removeExtra("wake_recovery");
            Diagnostics.event("WAKE_HOME_RESTORED");
        }
        screensaver.resume();
        if (home != null) {
            home.setIdle(false);
            home.requestFocus();
        }
        if (home != null) {
            if(dialogs.active()==null&&!ambientRequested)home.enter();
            else home.animateFocus();
            home.invalidate();
        }
        scheduleClock();
        armIdle();
        if (ambientRequested) {
            ambientRequested = false;
            screensaver.cancel();
            home.setIdle(true);
        }
        dialogs.resume();
        if(network!=null)network.resume();
        if(cast!=null)cast.resume();
        if(playback!=null)playback.refresh();
        if(home!=null&&!home.rows.isEmpty())sortCurrentApps();
        if(updates!=null)updates.resume();
        if(defaultHome!=null)defaultHome.resumed();
        FeedbackPanel.maybeOffer(this);
    }

    @Override protected void onPause() {
        if(phone!=null)phone.clearPreview();
        if(updates!=null)updates.pause();
        if(network!=null)network.pause();
        foreground = false;
        if(cast!=null)cast.pause();
        Diagnostics.foreground(false);
        if(playback!=null)playback.stop();
        handler.removeCallbacks(clock);
        screensaver.pause();
        if(recents!=null)recents.stop();
        if (home != null) {
            home.stopMotion();
            if (home.fade != null) home.fade.cancel();
            if(home.previewFade!=null)home.previewFade.cancel();
        }
        super.onPause();
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        Diagnostics.event("MEMORY_PRESSURE");
        ArtworkCache.clear();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_BACKGROUND) ArtworkCache.clear();
    }

    @Override protected void onDestroy() {
        if(defaultHome!=null)defaultHome.destroy();
        if(cast!=null)cast.destroy();
        if(phone!=null)phone.destroy();
        handler.removeCallbacksAndMessages(null);
        if (network != null) network.close();
        if (playback != null) {
            playback.stop();
            unregisterReceiver(playbackAccess);
        }
        closeMenus();
        if(recents!=null&&home!=null)recents.close();
        appReads.close();
        wallpaperReads.close();
        taskReads.close();
        taskImages.close();
        taskCommands.shutdownNow();
        loader.shutdownNow();
        if (receiverRegistered) {
            unregisterReceiver(packageChanges);
            unregisterReceiver(timeChanges);
        }
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        HomeSetup.observeHomeIntent(this, intent);
        boolean recent=intent.getBooleanExtra("recent_apps",false);
        if(!intent.getBooleanExtra("home_press_handled",false)&&intent.hasCategory(Intent.CATEGORY_HOME)&&recents.enabled())recent=homePressTracker.press(SystemClock.elapsedRealtime(),
                preferences.getInt("recent_double_ms",400));
        else if(!intent.hasCategory(Intent.CATEGORY_HOME))homePressTracker.reset();
        recents.close();
        if(cast!=null&&cast.isPlaying())cast.stopPlayback();
        closeMenus();
        ambientRequested = intent.getBooleanExtra("ambient", false);
        if (home != null) {
            home.setIdle(false);
            home.resetToApps();
            if (ambientRequested && foreground) {
                home.setIdle(true);
                ambientRequested=false;
            }
        }
        if(recent)recents.show();
    }

    void armIdle() {
        screensaver.arm();
    }

    void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    void loadWallpaper() {
        wallpapers.load();
    }

    void loadApplications() {
        catalog.load();
    }

    void sortCurrentApps() {
        catalog.sortCurrent();
    }

    void launch(Tile tile) {
        try {
            startActivity(tile.intent);
            if(tile.kind==0)AppOrder.opened(preferences,tile.id);
        }
        catch (ActivityNotFoundException | SecurityException ex) {
            Diagnostics.event("APP_OPEN_FAILED");
            toast("暂时无法打开「" + tile.label + "」");
        }
    }

    // Entry points used by HomeView; the panels live in SettingsNavigator.
    void settings() {
        backups.autoSnapshot();
        Diagnostics.event("SETTINGS_OPENED");
        navigator.settings();
    }
    void exitMenus(){
        closeMenus();
        home.requestFocus();
        armIdle();
    }
    void tileOptions() {
        navigator.tileOptions();
    }
    void appearanceSettings() {
        navigator.appearanceSettings();
    }
    void ambientSettings() {
        navigator.ambientSettings();
    }

    void showDialog(TvDialog dialog) {
        dialogs.show(dialog);
    }
    boolean isPage(String id){
        return dialogs.isPage(id);
    }
    void closeMenus(){
        if(backups!=null)backups.cancel();
        if(network!=null)network.cancelPending();
        menuEpoch++;
        dialogs.close();
        if(panels!=null)panels.close();
        if(home!=null)home.preview(null);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == NetworkPanel.PERMISSION) {
            network.permissionResult(grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED);
            return;
        }
        if (request == LocalFiles.READ) {
            files.permission(grants);
            return;
        }
        if (request == CoverManager.READ_COVER) cover.onPermissionResult(grants);
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("pending_cover", cover.pendingForState());
        super.onSaveInstanceState(state);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if(updates!=null&&updates.result(request))return;
        if (files.result(request,result,data)) return;
        if (network.onActivityResult(request)) return;
        cover.onActivityResult(request, result, data);
    }
}
