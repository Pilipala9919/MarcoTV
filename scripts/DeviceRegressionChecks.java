// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;

/** Runs only from a separately installed, same-signed test APK. Never bundled in Marco TV. */
public final class DeviceRegressionChecks extends Instrumentation {
    private int passed;
    private void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
        passed++;
        Bundle result = new Bundle(); result.putString("stream", "PASS " + name + "\n"); sendStatus(0, result);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            cacheChecks();
            orderChecks();
            MainActivity activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            final Throwable[] failure = new Throwable[1];
            runOnMainSync(() -> {
                try { navigationChecks(activity); applicationChecks(activity); } catch (Throwable error) { failure[0] = error; }
            });
            if (failure[0] != null) throw new AssertionError(failure[0]);
            playbackChecks(activity);
            result.putString("stream", "OK " + passed + " device regression checks\n");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL " + error + "\n"); finish(0, result);
        }
    }
    private Tile tile(String id,String name) {Tile tile=new Tile();tile.id=id;tile.label=name;return tile;}
    private void orderChecks() {
        SharedPreferences prefs=getTargetContext().getSharedPreferences("regression_order",0);prefs.edit().clear().commit();
        CardStore cards=new CardStore(getTargetContext());
        Tile a=tile("__test_alpha__","阿尔法"),b=tile("__test_beta__","北京"),c=tile("__test_cheng__","成都");
        ArrayList<Tile> tiles=new ArrayList<>(Arrays.asList(c,b,a));
        try {
            check("recent_sort_is_default",AppOrder.mode(prefs).equals("recent"));
            AppOrder.opened(prefs,a.id);AppOrder.opened(prefs,b.id);AppOrder.sort(tiles,prefs,cards);
            check("last_opened_first",tiles.get(0)==b&&tiles.get(1)==a);
            cards.toggle("pinned",c.id,true);AppOrder.sort(tiles,prefs,cards);
            check("pinned_before_recent",tiles.get(0)==c&&tiles.get(1)==b);
            cards.toggle("pinned",c.id,false);prefs.edit().putString("tv_sort","alpha").apply();AppOrder.sort(tiles,prefs,cards);
            check("chinese_alphabetic_order",tiles.get(0)==a&&tiles.get(1)==b&&tiles.get(2)==c);
            prefs.edit().putString("tv_sort","custom").putString("order_tv",b.id+"|"+c.id+"|"+a.id).apply();AppOrder.sort(tiles,prefs,cards);
            check("custom_order_restored",tiles.get(0)==b&&tiles.get(1)==c&&tiles.get(2)==a);
            AppOrder.opened(prefs,a.id);AppOrder.sort(tiles,prefs,cards);
            check("launch_does_not_change_custom_order",tiles.get(0)==b&&tiles.get(2)==a);
            cards.toggle("pinned",a.id,true);AppOrder.sort(tiles,prefs,cards);
            check("pinned_before_custom",tiles.get(0)==a&&tiles.get(1)==b);
            prefs.edit().putString("tv_sort","alpha").apply();AppOrder.sort(tiles,prefs,cards);
            check("switch_preserves_saved_custom_order",prefs.getString("order_tv","").equals(b.id+"|"+c.id+"|"+a.id));
            Bitmap icon=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);icon.eraseColor(Color.rgb(30,110,210));
            new android.graphics.Canvas(icon).drawCircle(16,16,5,new android.graphics.Paint());
            check("icon_background_comes_from_border",ArtworkStyle.background(icon)==Color.rgb(30,110,210));
            Bitmap full=ArtworkStyle.card(new android.graphics.drawable.BitmapDrawable(getTargetContext().getResources(),icon));
            check("icon_background_fills_entire_card",full.getWidth()==480&&full.getHeight()==270&&full.getPixel(0,0)==Color.rgb(30,110,210));
            full.recycle();icon.recycle();
        } finally {
            cards.toggle("pinned",a.id,false);cards.toggle("pinned",c.id,false);prefs.edit().clear().commit();
        }
    }
    private void applicationChecks(MainActivity host) throws Exception {
        String id="com.pilipala.tvhome.regression";
        ComponentName component=new ComponentName(id,FixtureActivity.class.getName());
        android.content.pm.ActivityInfo info=host.getPackageManager().getActivityInfo(component,0);
        check("manual_exported_activity_is_launchable",AppLibrary.launchable(host,info));
        info.exported=false;check("manual_private_activity_is_rejected",!AppLibrary.launchable(host,info));
        host.cards.manual(id,component.flattenToString());
        check("manual_entry_persists",new CardStore(host).manual(id).equals(component.flattenToString()));
        host.cards.toggle("manual_apps",id,false);
        host.cards.toggle("pinned",id,false);host.cards.toggle("hidden",id,false);
        host.preferences.edit().remove("opened:"+id).apply();
        host.getSharedPreferences("cards",0).edit().remove("manual:"+id).apply();
    }
    public static final class FixtureActivity extends android.app.Activity { }
    private void playbackChecks(MainActivity host) throws Exception {
        final MediaSession[] session=new MediaSession[1];final boolean[] paused={false};
        runOnMainSync(()->{
            host.playback.refresh();
            session[0]=new MediaSession(host,"MarcoTV regression fixture");
            session[0].setCallback(new MediaSession.Callback(){@Override public void onPause(){paused[0]=true;}},new Handler(Looper.getMainLooper()));
            session[0].setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,"Marco TV 测试会话").build());
            session[0].setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING,1000,1).build());session[0].setActive(true);
        });
        try {
            for(int i=0;i<20;i++){Thread.sleep(150);waitForIdleSync();if(host.playback.item!=null&&host.playback.item.title.equals("Marco TV 测试会话"))break;}
            check("media_session_title_visible",host.playback.item!=null&&host.playback.item.title.equals("Marco TV 测试会话"));
            check("playing_state_visible",host.playback.item.playing);
            runOnMainSync(()->host.playback.item.controller.getTransportControls().pause());Thread.sleep(200);waitForIdleSync();
            check("transport_pause_reaches_session",paused[0]);
            runOnMainSync(()->session[0].setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED,1000,0).build()));
            Thread.sleep(200);waitForIdleSync();
            check("paused_state_updates",host.playback.item==null||!host.playback.item.title.equals("Marco TV 测试会话")||!host.playback.item.playing);
        } finally {runOnMainSync(()->session[0].release());}
        Thread.sleep(200);waitForIdleSync();
        check("released_session_removed",host.playback.item==null||!host.playback.item.title.equals("Marco TV 测试会话"));
    }
    private void cacheChecks() throws Exception {
        Context context = getTargetContext();
        ArtworkCache.configure(context);
        Field retain = field(ArtworkCache.class, "retain");
        boolean previous = retain.getBoolean(null);
        // Explicitly exercise the retained-cache branch even on a low-memory TV.
        retain.setBoolean(null, true);
        String id = "__marcotv_regression_only__";
        CardStore cards = new CardStore(context);
        File file = new File(context.getCacheDir(), "marcotv-regression.png");
        Bitmap red = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888); red.eraseColor(Color.RED);
        Bitmap blue = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888); blue.eraseColor(Color.BLUE);
        try {
            cards.cover(id, "image"); ArtworkCache.put("cover:" + id, red);
            check("retained_cache_branch_active", cards.loadImage(id) == red);
            try (FileOutputStream out = new FileOutputStream(file)) { blue.compress(Bitmap.CompressFormat.PNG, 100, out); }
            cards.importImage(id, Uri.fromFile(file));
            Bitmap loaded = cards.loadImage(id);
            check("reimport_replaces_cached_cover", loaded != red && Color.blue(loaded.getPixel(20, 20)) > 220
                    && Color.red(loaded.getPixel(20, 20)) < 30);
            ArtworkCache.put("pkg:example.one:0", red); ArtworkCache.put("pkg:example.one:1", blue);
            ArtworkCache.put("pkg:example.other:0", blue);
            ArtworkCache.invalidatePackage("example.one");
            check("package_update_evicts_icon_and_banner", ArtworkCache.get("pkg:example.one:0") == null
                    && ArtworkCache.get("pkg:example.one:1") == null);
            check("package_update_keeps_unrelated_artwork", ArtworkCache.get("pkg:example.other:0") == blue
                    && ArtworkCache.get("cover:" + id) == loaded);
        } finally {
            cards.reset(id); file.delete(); ArtworkCache.clear(); retain.setBoolean(null, previous);
            red.recycle(); blue.recycle();
        }
    }
    private void navigationChecks(MainActivity activity) throws Exception {
        NetworkPanel network = activity.network;
        Field settings = field(NetworkPanel.class, "settingsEpoch");
        Field permissions = field(NetworkPanel.class, "permissionEpoch");
        Field history = field(MainActivity.class, "dialogHistory");
        network.show(); settings.setInt(network, activity.menuEpoch);
        check("wifi_result_handled", network.onActivityResult(NetworkPanel.WIFI_SETTINGS));
        List<?> pages = (List<?>) history.get(activity);
        check("wifi_return_restores_network_page", !pages.isEmpty()
                && ((TvDialog) pages.get(pages.size() - 1)).pageKey().equals("网络与 Wi-Fi"));
        settings.setInt(network, activity.menuEpoch); permissions.setInt(network, activity.menuEpoch);
        activity.onNewIntent(new Intent(Intent.ACTION_MAIN));
        network.onActivityResult(NetworkPanel.WIFI_SETTINGS); network.permissionResult(false);
        check("home_discards_wifi_and_permission_results", ((List<?>) history.get(activity)).isEmpty());
        network.show(); settings.setInt(network, activity.menuEpoch - 1);
        int size = ((List<?>) history.get(activity)).size();
        network.onActivityResult(NetworkPanel.WIFI_SETTINGS);
        check("obsolete_result_does_not_add_page", ((List<?>) history.get(activity)).size() == size);
        activity.onNewIntent(new Intent(Intent.ACTION_MAIN));
    }
}
