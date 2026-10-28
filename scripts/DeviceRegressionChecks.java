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
    private boolean bridgeOnly,taskActions;
    private boolean phoneOnly;private boolean appearanceOnly;private boolean updatesOnly;private String candidate,wrongSigner;
    private void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
        passed++;
        Bundle result = new Bundle(); result.putString("stream", "PASS " + name + "\n"); sendStatus(0, result);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args);phoneOnly=args!=null&&"true".equals(args.getString("phone_only"));appearanceOnly=args!=null&&"true".equals(args.getString("appearance_only"));updatesOnly=args!=null&&"true".equals(args.getString("updates_only"));candidate=args==null?null:args.getString("candidate");wrongSigner=args==null?null:args.getString("wrong_signer");bridgeOnly=args!=null&&"true".equals(args.getString("bridge_only"));taskActions=args!=null&&"true".equals(args.getString("task_actions"));start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if(updatesOnly){UpdateDeviceChecks.run(getTargetContext(),candidate,wrongSigner,this::check);result.putString("stream","OK "+passed+" update device checks\n");finish(-1,result);return;}
            if(taskActions){taskActionChecks();result.putString("stream","OK "+passed+" task action checks\n");finish(-1,result);return;}
            if(bridgeOnly){bridgeChecks();result.putString("stream","OK "+passed+" bridge checks\n");finish(-1,result);return;}
            if(phoneOnly){MainActivity host=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();PhoneDeviceChecks.run(this,host,this::check);result.putString("stream","OK "+passed+" phone checks\n");finish(-1,result);return;}
            if(appearanceOnly){MainActivity host=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();AppearanceDeviceChecks.run(this,host,this::check);result.putString("stream","OK "+passed+" appearance checks\n");finish(-1,result);return;}
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
            FeatureRegressionChecks.run(this,activity,this::check);
            result.putString("stream", "OK " + passed + " device regression checks\n");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL " + error + "\n"); finish(0, result);
        }
    }
    private void bridgeChecks()throws Exception{
        for(int i=0;i<20;i++){try{TaskBridgeClient.request("status");break;}catch(Exception waiting){Thread.sleep(500);}}
        org.json.JSONObject status=TaskBridgeClient.configure(getTargetContext());check("bridge_authenticated_connection",status.optBoolean("ok"));check("bridge_version_matches_app",ProjectInfo.VERSION.equals(status.optString("version")));
        org.json.JSONArray tasks=TaskBridgeClient.request("list").getJSONArray("tasks");java.util.Set<Integer> ids=new java.util.HashSet<>();boolean valid=true;
        for(int i=0;i<tasks.length();i++){org.json.JSONObject task=tasks.getJSONObject(i);valid&=task.getInt("id")>=0&&ids.add(task.getInt("id"))&&!task.getString("package").equals(getTargetContext().getPackageName());}
        check("bridge_returns_unique_real_task_ids",valid);boolean rejected=false;try{TaskBridgeClient.task("switch",-1);}catch(Exception expected){rejected=true;}check("bridge_rejects_invalid_task",rejected);
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
    public static final class FixtureActivity extends android.app.Activity {
        @Override public void onCreate(Bundle state){super.onCreate(state);android.widget.TextView text=new android.widget.TextView(this);text.setText("Marco TV 测试任务 "+getIntent().getDataString());text.setTextSize(32);text.setTextColor(android.graphics.Color.WHITE);text.setBackgroundColor(0xff20364d);text.setGravity(android.view.Gravity.CENTER);setContentView(text);}
    }
    private java.util.ArrayList<Integer> fixtureTasks()throws Exception{
        java.util.ArrayList<Integer> ids=new java.util.ArrayList<>();org.json.JSONArray tasks=TaskBridgeClient.request("list").getJSONArray("tasks");
        for(int i=0;i<tasks.length();i++)if("com.pilipala.tvhome.regression".equals(tasks.getJSONObject(i).getString("package")))ids.add(tasks.getJSONObject(i).getInt("id"));return ids;
    }
    private void taskActionChecks()throws Exception{
        for(int i=0;i<20;i++){try{TaskBridgeClient.configure(getTargetContext());break;}catch(Exception waiting){Thread.sleep(500);}}
        try{
            for(String name:new String[]{"A","B"}){getTargetContext().startActivity(new Intent().setComponent(new ComponentName("com.pilipala.tvhome.regression",FixtureActivity.class.getName())).setData(Uri.parse("marcotv-test:"+name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_NEW_DOCUMENT|Intent.FLAG_ACTIVITY_MULTIPLE_TASK));Thread.sleep(700);}
            java.util.ArrayList<Integer> ids=fixtureTasks();check("same_app_keeps_two_system_tasks",ids.size()==2&&!ids.get(0).equals(ids.get(1)));
            int older=ids.get(1),newer=ids.get(0);check("snapshot_returns_explicit_availability",TaskBridgeClient.task("snapshot",older).has("available"));
            TaskBridgeClient.task("switch",older);Thread.sleep(600);check("switch_moves_exact_task_to_front",TaskBridgeClient.request("list").getJSONArray("tasks").getJSONObject(0).getInt("id")==older);
            MainActivity host=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));runOnMainSync(()->host.recents.show());
            for(int i=0;i<40;i++){Thread.sleep(200);if(host.recents.entries.size()>=2)break;}
            final int[] count={0};runOnMainSync(()->{for(RecentApps.Entry entry:host.recents.entries)if(entry.id.equals("com.pilipala.tvhome.regression"))count[0]++;});check("stack_keeps_both_tasks_of_same_app",count[0]==2);
            runOnMainSync(()->{host.recents.move(1);host.recents.close();});check("stack_closes_to_home",!host.recents.visible);
            final int[] entranceAlpha={0,0};final int sampleY=Math.round(555-245*host.recents.value("size",100)/100f);
            runOnMainSync(()->{host.recents.show();Bitmap frame=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888);host.recents.draw(new android.graphics.Canvas(frame),false);entranceAlpha[0]=Color.alpha(frame.getPixel(960,sampleY));frame.recycle();});
            check("entrance_starts_below_final_card_position",entranceAlpha[0]<255);
            Thread.sleep(host.recents.value("duration",280)+400);waitForIdleSync();
            runOnMainSync(()->{Bitmap frame=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888);host.recents.draw(new android.graphics.Canvas(frame),false);entranceAlpha[1]=Color.alpha(frame.getPixel(960,sampleY));frame.recycle();host.recents.close();});
            check("entrance_settles_to_opaque_task_card",entranceAlpha[1]==255);
            if(TaskBridgeClient.request("status").optBoolean("canRemove")){
                runOnMainSync(()->host.recents.show());for(int i=0;i<40;i++){Thread.sleep(100);if(host.recents.entries.size()>=2)break;}
                final boolean[] visuals={false,false};runOnMainSync(()->{
                    for(int i=0;i<host.recents.entries.size();i++)if(host.recents.entries.get(i).taskId==older)host.recents.selected=i;host.recents.stop();
                    RecentApps.Entry entry=host.recents.entries.get(host.recents.selected);visuals[1]=entry.snapshot!=null||entry.artwork!=null;
                    Bitmap image=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888);host.recents.draw(new android.graphics.Canvas(image),false);visuals[0]=Color.alpha(image.getPixel(960,555))==255;image.recycle();
                    host.recents.touch(android.view.MotionEvent.ACTION_DOWN,960,600);host.recents.touch(android.view.MotionEvent.ACTION_MOVE,960,360);host.recents.touch(android.view.MotionEvent.ACTION_UP,960,280);
                });check("task_card_background_is_opaque",visuals[0]);check("task_card_has_snapshot_or_logo",visuals[1]);
                for(int i=0;i<30&&fixtureTasks().contains(older);i++)Thread.sleep(100);Thread.sleep(350);
                check("swipe_removes_only_selected_task",!fixtureTasks().contains(older)&&fixtureTasks().contains(newer));
                for(int i=0;i<40&&host.recents.entries.stream().anyMatch(e->e.taskId==older);i++){Thread.sleep(100);waitForIdleSync();}
                check("removed_card_leaves_stack",host.recents.entries.stream().noneMatch(e->e.taskId==older));
                boolean rejected=false;try{TaskBridgeClient.task("switch",older);}catch(Exception expected){rejected=true;}check("removed_task_cannot_reopen",rejected);
                runOnMainSync(()->{for(int i=0;i<host.recents.entries.size();i++)if(host.recents.entries.get(i).taskId==newer)host.recents.selected=i;host.recents.stop();host.recents.key(android.view.KeyEvent.KEYCODE_DPAD_UP,new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DPAD_UP));});
                for(int i=0;i<30&&!fixtureTasks().isEmpty();i++)Thread.sleep(100);check("remote_up_clears_fixture_task",fixtureTasks().isEmpty());
            }else{
                boolean rejected=false;try{TaskBridgeClient.task("remove",older);}catch(Exception expected){rejected=true;}check("unavailable_remove_is_rejected",rejected);
                check("denied_remove_keeps_both_tasks",fixtureTasks().contains(older)&&fixtureTasks().contains(newer));
                check("remove_capability_is_explicit",TaskBridgeClient.request("status").has("canRemove"));
                check("unavailable_remove_has_no_gesture_side_effect",fixtureTasks().size()==2);check("unavailable_remove_keeps_task_ids",fixtureTasks().containsAll(ids));check("unavailable_remove_stays_explicit",!TaskBridgeClient.request("status").optBoolean("canRemove"));
            }
        }finally{for(int id:fixtureTasks())try{TaskBridgeClient.task("remove",id);}catch(Exception ignored){}}
    }
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
                && ((TvDialog) pages.get(pages.size() - 1)).pageKey().equals("wifi_popover"));
        settings.setInt(network, activity.menuEpoch); permissions.setInt(network, activity.menuEpoch);
        activity.onNewIntent(new Intent(Intent.ACTION_MAIN));
        network.onActivityResult(NetworkPanel.WIFI_SETTINGS); network.permissionResult(false);
        check("home_discards_wifi_and_permission_results", ((List<?>) history.get(activity)).isEmpty());
        network.show(); settings.setInt(network, activity.menuEpoch - 1);
        int size = ((List<?>) history.get(activity)).size();
        network.onActivityResult(NetworkPanel.WIFI_SETTINGS);
        check("obsolete_result_does_not_add_page", ((List<?>) history.get(activity)).size() == size);
        activity.onNewIntent(new Intent(Intent.ACTION_MAIN));
        activity.navigator.settings();activity.navigator.desktopSettings();activity.navigator.wallpaperSettings();
        check("new_settings_keep_three_levels",((List<?>)history.get(activity)).size()==3&&activity.isPage("wallpaper"));
        java.lang.reflect.Method back=MainActivity.class.getDeclaredMethod("menuBack");back.setAccessible(true);
        back.invoke(activity);check("back_returns_one_group",activity.isPage("appearance"));
        back.invoke(activity);check("back_returns_settings_root",activity.isPage("main"));
        back.invoke(activity);check("root_back_returns_desktop",((List<?>)history.get(activity)).isEmpty()&&!field(MainActivity.class,"dialogOpen").getBoolean(activity));
        activity.navigator.settings();activity.navigator.applicationSettings();
        activity.onNewIntent(new Intent(Intent.ACTION_MAIN));
        check("home_exits_new_group_hierarchy",((List<?>)history.get(activity)).isEmpty());
        activity.navigator.settings();activity.navigator.maintenance();activity.health.diagnostics();activity.health.diagnostics();
        check("page_refresh_does_not_duplicate_history",((List<?>)history.get(activity)).size()==3);
        java.util.Map<String,?> systemBefore=SystemIntegration.prefs(activity).getAll();
        StatusSnapshot.local(activity);
        check("local_status_does_not_change_system_preferences",systemBefore.equals(SystemIntegration.prefs(activity).getAll()));
        activity.onNewIntent(new Intent(Intent.ACTION_MAIN));
    }
}
