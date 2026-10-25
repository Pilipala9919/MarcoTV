// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.*;
import android.media.MediaMetadata;
import android.media.session.*;
import android.os.*;
import java.io.*;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.zip.*;
import org.json.*;

/** Isolated storage exercises real backup APIs without replacing the user's preferences. */
final class FeatureRegressionChecks {
    static void run(Instrumentation runner,MainActivity host,BiConsumer<String,Boolean> check)throws Exception{
        final File root=new File(host.getCacheDir(),"isolated-feature-checks");root.mkdirs();
        Context context=new ContextWrapper(host){
            @Override public File getFilesDir(){File f=new File(root,"files");f.mkdirs();return f;}
            @Override public File getCacheDir(){File f=new File(root,"cache");f.mkdirs();return f;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return host.getSharedPreferences("test011_"+name,mode);}
        };
        for(String group:BackupStore.GROUPS)context.getSharedPreferences(group,0).edit().clear().commit();
        File archive=new File(root,"backup.zip");SharedPreferences prefs=context.getSharedPreferences("home",0);
        try{
            HomeInputGate gate=new HomeInputGate();check.accept("home_gate_initially_allows_awake",gate.allow(true,100));gate.power(200);
            check.accept("home_gate_blocks_power_transition",!gate.allow(true,500));check.accept("home_gate_recovers_after_power_settles",gate.allow(true,2001));gate.screen(false,2100);
            check.accept("home_gate_blocks_screen_off",!gate.allow(false,5000)&&!gate.allow(true,5000));gate.screen(true,5100);
            check.accept("home_gate_waits_for_wake_focus",!gate.allow(true,5500));check.accept("home_gate_allows_settled_wake",gate.allow(true,5801));
            HomePressTracker presses=new HomePressTracker();check.accept("home_first_press_is_immediate_single",!presses.press(1000,400));
            check.accept("home_second_press_opens_recents",presses.press(1250,400));check.accept("home_third_press_starts_new_pair",!presses.press(1400,400));check.accept("home_slow_press_is_single",!presses.press(1900,400));
            prefs.edit().putInt("card_radius",44).putString("tv_sort","custom").putString("order_tv","one|two").putBoolean("ambient_enabled",false).putString("saved_dream","private-system-state").commit();
            CardStore cards=new CardStore(context);cards.setName("com.fixture.missing","测试卡片");cards.toggle("pinned","com.fixture.missing",true);
            Bitmap image=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888);image.eraseColor(Color.GREEN);
            try(OutputStream out=new FileOutputStream(cards.imageFile("com.fixture.missing"))){image.compress(Bitmap.CompressFormat.JPEG,90,out);}image.recycle();cards.cover("com.fixture.missing","image");
            JSONObject original=BackupStore.capture(context,true);BackupStore.write(context,archive,original);
            prefs.edit().putInt("recent_size",110).putBoolean("recent_enabled",true).putBoolean("recent_wake",true).commit();JSONObject recent=BackupStore.capture(context,false);
            check.accept("backup_includes_task_card_appearance",recent.getJSONObject("home").getJSONObject("recent_size").getInt("value")==110);
            check.accept("backup_excludes_task_activation",!recent.getJSONObject("home").has("recent_enabled")&&!recent.getJSONObject("home").has("recent_wake"));
            check.accept("backup_excludes_system_permissions",!original.has("system_integration")&&!original.getJSONObject("home").has("saved_dream"));
            try(BackupStore.PreparedInput input=new BackupStore.PreparedInput(context,archive)){
                check.accept("backup_image_roundtrip",input.value.images.size()==1);
                check.accept("backup_missing_apps_reported",input.value.missing==1);
                prefs.edit().putInt("card_radius",12).commit();BackupStore.restore(context,input.value);
                check.accept("backup_restores_settings",prefs.getInt("card_radius",0)==44&&prefs.getString("tv_sort","").equals("custom"));
                check.accept("backup_restores_cards",cards.contains("pinned","com.fixture.missing")&&cards.cover("com.fixture.missing").equals("image"));
                check.accept("backup_preserves_local_system_state",prefs.getString("saved_dream","").equals("private-system-state"));
                File before=new File(context.getFilesDir(),"backups/before-restore.zip");
                try(BackupStore.PreparedInput prior=new BackupStore.PreparedInput(context,before)){check.accept("restore_saves_previous_configuration",prior.value.metadata.getJSONObject("home").getJSONObject("card_radius").getInt("value")==12);}
            }
            JSONObject settings=BackupStore.capture(context,false);BackupStore.write(context,new File(root,"settings.zip"),settings);
            try(BackupStore.PreparedInput only=new BackupStore.PreparedInput(context,new File(root,"settings.zip"))){check.accept("settings_only_omits_images",only.value.images.isEmpty()&&only.value.metadata.getJSONObject("cards").getJSONObject("cover:com.fixture.missing").getString("value").equals("original"));}
            int before=prefs.getInt("card_radius",0);
            check.accept("backup_rejects_path_traversal",rejects(context,zip("../escaped",new byte[]{1}))&&!new File(root,"escaped").exists());
            JSONObject invalid=new JSONObject(settings.toString());invalid.put("schema",99);
            check.accept("backup_rejects_unknown_schema",rejects(context,zip("settings.json",invalid.toString().getBytes("UTF-8"))));
            invalid=new JSONObject(settings.toString());invalid.getJSONObject("home").put("card_radius",new JSONObject().put("type","s").put("value","bad"));
            check.accept("backup_rejects_wrong_type",rejects(context,zip("settings.json",invalid.toString().getBytes("UTF-8"))));
            invalid=new JSONObject(settings.toString());invalid.getJSONObject("home").getJSONObject("card_radius").put("value",9999);
            check.accept("backup_rejects_out_of_range",rejects(context,zip("settings.json",invalid.toString().getBytes("UTF-8"))));
            invalid=new JSONObject(settings.toString());invalid.getJSONObject("home").put("wallpaper",new JSONObject().put("type","s").put("value","file:///private"));
            check.accept("backup_rejects_imported_local_path",rejects(context,zip("settings.json",invalid.toString().getBytes("UTF-8"))));
            check.accept("backup_rejects_missing_images",rejects(context,zip("settings.json",original.toString().getBytes("UTF-8"))));
            check.accept("rejected_backup_keeps_configuration",prefs.getInt("card_radius",0)==before);
            BackupStore.write(context,new File(context.getFilesDir(),"backups/before-restore.zip"),BackupStore.capture(context,true));
            prefs.edit().putInt("card_radius",8).commit();try(OutputStream out=new FileOutputStream(new File(context.getFilesDir(),"backup-restore-pending"))){out.write(1);}
            BackupStore.recoverInterrupted(context);check.accept("interrupted_restore_rolls_back",prefs.getInt("card_radius",0)==44&&!new File(context.getFilesDir(),"backup-restore-pending").exists());
            Throwable error=new IllegalStateException("password=secret https://private/video");String safe=Diagnostics.safeStack(error);
            check.accept("diagnostics_omits_exception_message",!safe.contains("secret")&&!safe.contains("https://")&&safe.contains("IllegalStateException"));
            File expired=new File(Diagnostics.folder(context),"expired.txt");try(OutputStream out=new FileOutputStream(expired)){out.write(1);}expired.setLastModified(System.currentTimeMillis()-Diagnostics.RETENTION-1000);
            Diagnostics.prune(context,System.currentTimeMillis());check.accept("diagnostics_removes_expired_records",!expired.exists());
            for(int i=0;i<6;i++)try(RandomAccessFile file=new RandomAccessFile(new File(Diagnostics.folder(context),"volume"+i+".txt"),"rw")){file.setLength(1024*1024);}
            Diagnostics.prune(context,System.currentTimeMillis());long total=0;for(File f:Diagnostics.folder(context).listFiles())total+=f.length();check.accept("diagnostics_caps_disk_use",total<=Diagnostics.MAX_BYTES);
            check.accept("diagnostics_toggle_persists",Diagnostics.enabled(context));Diagnostics.setEnabled(context,false);check.accept("diagnostics_can_be_disabled",!Diagnostics.enabled(context));
        }finally{
            for(String group:new String[]{"home","cards","menu_order","diagnostics"})context.getSharedPreferences(group,0).edit().clear().commit();remove(root,root);
        }
        manualAndMove(runner,host,check);
        media(runner,host,check);
    }
    private static void manualAndMove(Instrumentation runner,MainActivity host,BiConsumer<String,Boolean> check)throws Exception{
        String id="com.pilipala.tvhome.regression";final Throwable[] failed={null};
        try{
            runner.runOnMainSync(()->{try{java.lang.reflect.Method add=AppLibrary.class.getDeclaredMethod("add",ComponentName.class);add.setAccessible(true);add.invoke(host.library,new ComponentName(id,DeviceRegressionChecks.FixtureActivity.class.getName()));}catch(Throwable e){failed[0]=e;}});
            if(failed[0]!=null)throw new AssertionError(failed[0]);
            host.loader.submit(()->{}).get();runner.waitForIdleSync();
            runner.runOnMainSync(()->{try{
                Row row=host.home.rows.get(host.home.row);check.accept("manual_add_focuses_new_card",row.tiles.get(row.selected).id.equals(id));
                boolean realApps=true;for(Tile t:row.tiles)realApps&=t.kind==0&&t.intent!=null;check.accept("app_row_contains_only_real_apps",realApps);
                ArrayList<Tile> expected=new ArrayList<>(row.tiles);AppOrder.sort(expected,host.preferences,host.cards);host.sortCurrentApps();
                check.accept("recent_sort_includes_first_app",row.tiles.equals(expected));
                int selected=row.selected;row.selected=0;host.home.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_LEFT,new android.view.KeyEvent(0,android.view.KeyEvent.KEYCODE_DPAD_LEFT));
                check.accept("left_drawer_starts_with_add_app",host.home.getContentDescription().toString().equals("分类操作栏，添加应用"));
                host.home.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,new android.view.KeyEvent(0,android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
                check.accept("drawer_right_returns_to_first_app",row.selected==0&&host.home.getContentDescription().toString().equals(row.title+"，"+row.tiles.get(0).label));row.selected=selected;
                ArrayList<Tile> before=new ArrayList<>(row.tiles);int at=row.selected;host.home.beginMove();
                host.home.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,new android.view.KeyEvent(0,android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
                host.home.onKeyUp(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,new android.view.KeyEvent(1,android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
                host.home.onKeyDown(android.view.KeyEvent.KEYCODE_BACK,new android.view.KeyEvent(0,android.view.KeyEvent.KEYCODE_BACK));
                host.home.onKeyUp(android.view.KeyEvent.KEYCODE_BACK,new android.view.KeyEvent(1,android.view.KeyEvent.KEYCODE_BACK));
                check.accept("remote_move_cancel_preserves_order",row.tiles.equals(before)&&row.selected==at);
                host.home.beginMove();host.home.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,new android.view.KeyEvent(0,android.view.KeyEvent.KEYCODE_DPAD_RIGHT));host.onNewIntent(new Intent(Intent.ACTION_MAIN));
                check.accept("home_cancels_uncommitted_move",row.tiles.equals(before)&&!host.home.isMoving());
                ArrayList<Row> savedRows=host.home.rows;int savedRow=host.home.row;
                try{host.home.rows=new ArrayList<>();Row empty=new Row("TV 应用","");host.home.rows.add(empty);host.home.resetToApps();
                    host.home.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,new android.view.KeyEvent(0,android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
                    host.home.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_LEFT,new android.view.KeyEvent(0,android.view.KeyEvent.KEYCODE_DPAD_LEFT));
                    check.accept("empty_app_row_can_open_add_menu",empty.selected==0&&host.home.getContentDescription().toString().equals("分类操作栏，添加应用"));
                }finally{host.home.rows=savedRows;host.home.resetToApps();host.home.row=savedRow;}
            }catch(Throwable e){failed[0]=e;}});if(failed[0]!=null)throw new AssertionError(failed[0]);
        }finally{
            runner.runOnMainSync(()->{host.cards.toggle("manual_apps",id,false);host.cards.toggle("hidden",id,false);host.cards.toggle("pinned",id,false);host.getSharedPreferences("cards",0).edit().remove("manual:"+id).apply();host.loadApplications();});
            host.loader.submit(()->{}).get();runner.waitForIdleSync();
        }
    }
    private static byte[] zip(String name,byte[] bytes)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(out)){z.putNextEntry(new ZipEntry(name));z.write(bytes);z.closeEntry();}return out.toByteArray();}
    private static boolean rejects(Context c,byte[] bytes){try{BackupStore.Prepared p=BackupStore.read(c,new ByteArrayInputStream(bytes));p.close();return false;}catch(Exception expected){return true;}}
    private static void remove(File root,File file)throws Exception{if(!file.getCanonicalPath().equals(root.getCanonicalPath())&&!file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new IOException();File[] children=file.listFiles();if(children!=null)for(File child:children)remove(root,child);file.delete();}
    private static void media(Instrumentation runner,MainActivity host,BiConsumer<String,Boolean> check)throws Exception{
        MediaSession[] sessions=new MediaSession[2];
        runner.runOnMainSync(()->{
            host.playback.refresh();for(int i=0;i<2;i++){sessions[i]=new MediaSession(host,"source-check-"+i);sessions[i].setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,"fixture-"+i).build());sessions[i].setPlaybackState(new PlaybackState.Builder().setState(i==0?PlaybackState.STATE_PLAYING:PlaybackState.STATE_PAUSED,0,1).build());sessions[i].setActive(true);}
        });Thread.sleep(500);runner.waitForIdleSync();
        try{
            check.accept("media_prefers_active_playback",host.playback.item!=null&&host.playback.item.title.equals("fixture-0"));
            runner.runOnMainSync(()->host.playback.hideCurrent());check.accept("media_hide_does_not_stop_playback",sessions[0].getController().getPlaybackState().getState()==PlaybackState.STATE_PLAYING&&(host.playback.item==null||!host.playback.item.title.equals("fixture-0")));
            runner.runOnMainSync(()->host.playback.restoreHidden());check.accept("media_hidden_card_can_return",host.playback.item!=null&&host.playback.item.title.equals("fixture-0"));
            check.accept("media_missing_cover_uses_app_icon",host.playback.item!=null&&host.playback.item.artwork!=null);
            final Throwable[] failure={null};runner.runOnMainSync(()->{try{
                java.lang.reflect.Field preferred=NowPlaying.class.getDeclaredField("preferred");preferred.setAccessible(true);preferred.set(host.playback,sessions[1].getSessionToken());host.playback.refresh();
                check.accept("media_manual_source_selection",host.playback.item!=null&&host.playback.item.title.equals("fixture-1"));preferred.set(host.playback,null);host.playback.refresh();
                java.lang.reflect.Field paused=NowPlaying.class.getDeclaredField("pausedSince");paused.setAccessible(true);((Map<MediaSession.Token,Long>)paused.get(host.playback)).put(sessions[1].getSessionToken(),SystemClock.elapsedRealtime()-31*60*1000L);
                host.playback.hideCurrent();check.accept("media_stale_pause_is_hidden",host.playback.item==null||!host.playback.item.title.equals("fixture-1"));host.playback.restoreHidden();
            }catch(Throwable e){failure[0]=e;}});if(failure[0]!=null)throw new AssertionError(failure[0]);
        }finally{runner.runOnMainSync(()->{for(MediaSession session:sessions)session.release();});}
    }
}
