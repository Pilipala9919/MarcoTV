// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.SharedPreferences;
import android.net.Uri;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real transaction/queue code with in-memory preferences and no Android system writes. */
public final class CoreConsistencyChecks {
    static int passed;
    static void check(String label,boolean good){if(!good)throw new AssertionError(label);passed++;System.out.println("PASS "+label);}
    static AppearanceSession start(MainActivity host){
        AppearanceSession session=new AppearanceSession(host);
        host.appearanceSession=session;
        host.localPreview=session.draft;
        return session;
    }
    static File directory;
    static File file()throws Exception{return File.createTempFile("marco-edit-check-",".img",directory);}
    public static void main(String[] args)throws Exception{
        MainActivity host=new MainActivity();
        directory=java.nio.file.Files.createTempDirectory("marco-edit-check-").toFile();
        host.directory=directory;
        File original=new File(directory,"wallpaper-image");
        java.nio.file.Files.write(original.toPath(),new byte[]{7,8});
        host.preferences.edit().putString("wallpaper",Uri.fromFile(original).toString()).putInt("clock_size",96)
                .putBoolean("ambient_enabled",true).putInt("wallpaper_dim",25).commit();
        AppearanceSession cancelled=start(host);
        cancelled.draft.values.put("clock_size",120);
        cancelled.draft.booleans.put("ambient_enabled",false);
        cancelled.draft.values.put("ambient_sleep_minutes",60);
        cancelled.wallpaper("",2,true);
        check("child_wallpaper_is_only_previewed",host.preferences.getString("wallpaper","").equals(Uri.fromFile(original).toString())&&cancelled.draft.getString("wallpaper","x").isEmpty());
        check("child_reset_dim_in_draft",cancelled.draft.getInt("wallpaper_dim",0)==10&&host.preferences.getInt("wallpaper_dim",0)==25);
        check("screensaver_not_applied_during_edit",host.preferences.getBoolean("ambient_enabled",false)&&AmbientIntegration.calls==0);
        check("typed_draft_getAll_matches_reads",cancelled.draft.getAll().get("ambient_enabled").equals(false)&&cancelled.draft.contains("ambient_sleep_minutes"));
        File discarded=file();
        cancelled.imported(discarded,cancelled.wallpaperRevision);
        cancelled.finish(false);
        check("cancel_keeps_original_wallpaper_file",original.exists());
        check("cancel_keeps_original_wallpaper_bytes",Arrays.equals(java.nio.file.Files.readAllBytes(original.toPath()),new byte[]{7,8}));
        check("cancel_removes_only_new_import",!discarded.exists());
        check("cancel_reverts_all_visual_types",host.preferences.getInt("clock_size",0)==96&&host.preferences.getBoolean("ambient_enabled",false)&&!host.preferences.contains("ambient_sleep_minutes"));
        check("cancel_releases_preview_owner",host.localPreview==null&&host.appearanceSession==null);
        check("cancel_does_not_configure_system_screensaver",AmbientIntegration.calls==0);
        File late=file();cancelled.imported(late,0);
        check("late_import_after_cancel_is_discarded",!late.exists());
        AppearanceSession saved=start(host);
        saved.pendingImports=1;saved.save();
        check("save_waits_for_pending_image",saved.open&&host.localPreview==saved.draft);
        saved.pendingImports=0;
        File first=file(),second=file();
        saved.imported(first,saved.wallpaperRevision);
        long stale=saved.wallpaperRevision;
        saved.wallpaper("",1,false);
        File outdated=file();saved.imported(outdated,stale);
        check("newer_selection_wins_over_slow_import",!outdated.exists()&&saved.draft.getString("wallpaper","x").isEmpty());
        saved.imported(second,saved.wallpaperRevision);
        java.nio.file.Files.write(second.toPath(),new byte[]{9,10});
        saved.draft.values.put("clock_size",130);
        saved.draft.booleans.put("ambient_enabled",false);
        host.preferences.edit().putInt("unrelated",7).apply();
        int before=host.preferences.commits;
        saved.save();
        check("save_uses_one_preferences_transaction",host.preferences.commits==before+1);
        check("save_applies_wallpaper_and_parameters",host.preferences.getInt("clock_size",0)==130&&host.preferences.getString("wallpaper","").equals(Uri.fromFile(original).toString()));
        check("save_preserves_unrelated_changes",host.preferences.getInt("unrelated",0)==7);
        check("save_applies_system_screensaver_once",!host.preferences.getBoolean("ambient_enabled",true)&&AmbientIntegration.calls==1);
        check("save_promotes_selected_file_to_backup_identity",!second.exists()&&!first.exists()&&original.exists()&&Arrays.equals(java.nio.file.Files.readAllBytes(original.toPath()),new byte[]{9,10}));
        saved.finish(false);saved.save();
        check("leave_after_save_is_idempotent",original.exists()&&AmbientIntegration.calls==1&&host.preferences.commits==before+1);
        File afterSave=file();saved.imported(afterSave,0);
        check("late_import_after_save_is_discarded",!afterSave.exists());
        AppearanceSession older=start(host),newer=start(host);
        older.finish(false);
        check("old_session_cannot_clear_new_owner",host.appearanceSession==newer&&host.localPreview==newer.draft);
        newer.finish(false);
        AppearanceSession failed=start(host);
        File missing=file();failed.imported(missing,failed.wallpaperRevision);missing.delete();
        check("failed_publish_keeps_editor_open",!failed.save()&&failed.open&&host.appearanceSession==failed);
        check("failed_publish_preserves_original_bytes",Arrays.equals(java.nio.file.Files.readAllBytes(original.toPath()),new byte[]{9,10}));
        failed.finish(false);
        AppearanceSession visualOnly=start(host);
        visualOnly.draft.values.put("clock_size",140);visualOnly.save();
        check("visual_only_save_does_not_reconfigure_system_screensaver",AmbientIntegration.calls==1);
        check("task_refresh_preserves_identity_after_reorder",TaskSelection.resolve(new int[]{30,10,20},3,20,1)==2);
        check("task_removed_uses_nearest_position",TaskSelection.resolve(new int[]{10,30},2,20,1)==1);
        check("task_removed_last_clamps_position",TaskSelection.resolve(new int[]{10,20},2,30,2)==1);
        check("task_focus_obeys_display_limit",TaskSelection.resolve(new int[]{10,20,30},2,30,2)==1);
        check("empty_task_refresh_is_safe",TaskSelection.resolve(new int[0],10,30,2)==0);
        check("first_task_open_uses_first_position",TaskSelection.resolve(new int[]{10,20},10,-1,0)==0);
        LatestTaskQueue reads=new LatestTaskQueue("check-reads"),commands=new LatestTaskQueue("check-commands");
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1);
        AtomicInteger executed=new AtomicInteger();
        reads.execute(()->{started.countDown();try{release.await();}catch(InterruptedException ignored){}executed.incrementAndGet();});
        check("read_worker_starts",started.await(2,TimeUnit.SECONDS));
        reads.execute(()->executed.addAndGet(100));
        reads.execute(()->{executed.addAndGet(10);finished.countDown();});
        CountDownLatch command=new CountDownLatch(1);commands.execute(command::countDown);
        check("interaction_queue_not_blocked_by_slow_read",command.await(2,TimeUnit.SECONDS));
        release.countDown();check("latest_read_completes",finished.await(2,TimeUnit.SECONDS));
        check("superseded_pending_read_never_runs",executed.get()==11);
        reads.close();commands.close();
        reads.execute(()->executed.addAndGet(1000));
        check("closed_queue_ignores_new_work",executed.get()==11);
        original.delete();directory.delete();
        System.out.println("OK "+passed+" core consistency checks (in-memory Android facade, no device proof)");
    }
}

class MainActivity {
    File directory;
    File getFilesDir(){return directory;}
    final MemoryPreferences preferences=new MemoryPreferences();
    PreviewPreferences localPreview;
    AppearanceSession appearanceSession;
    final HomeView home=new HomeView();
    final IdleController screensaver=new IdleController();
    boolean isDestroyed(){return false;}
    boolean isFinishing(){return false;}
    void loadWallpaper(){}
}
class HomeView {void refreshAppearance(){}void invalidate(){}}
class IdleController {void resume(){}}
class AmbientIntegration {static int calls;static void configure(MainActivity host){calls++;}}
class MemoryPreferences implements SharedPreferences {
    final Map<String,Object> data=new HashMap<>();int commits;
    public Map<String,?> getAll(){return new HashMap<>(data);}
    public int getInt(String k,int d){return data.containsKey(k)?(Integer)data.get(k):d;}
    public String getString(String k,String d){return data.containsKey(k)?(String)data.get(k):d;}
    public boolean getBoolean(String k,boolean d){return data.containsKey(k)?(Boolean)data.get(k):d;}
    public long getLong(String k,long d){return data.containsKey(k)?(Long)data.get(k):d;}
    public float getFloat(String k,float d){return data.containsKey(k)?(Float)data.get(k):d;}
    @SuppressWarnings("unchecked") public Set<String> getStringSet(String k,Set<String> d){return data.containsKey(k)?(Set<String>)data.get(k):d;}
    public boolean contains(String k){return data.containsKey(k);}
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){}
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){}
    public Editor edit(){return new Editor(){
        Map<String,Object> pending=new HashMap<>();Set<String> removed=new HashSet<>();boolean clear;
        public Editor putInt(String k,int v){pending.put(k,v);return this;}
        public Editor putBoolean(String k,boolean v){pending.put(k,v);return this;}
        public Editor putString(String k,String v){pending.put(k,v);return this;}
        public Editor putLong(String k,long v){pending.put(k,v);return this;}
        public Editor putFloat(String k,float v){pending.put(k,v);return this;}
        public Editor putStringSet(String k,Set<String> v){pending.put(k,v);return this;}
        public Editor remove(String k){removed.add(k);return this;}
        public Editor clear(){clear=true;return this;}
        public void apply(){commit();}
        public boolean commit(){if(clear)data.clear();for(String k:removed)data.remove(k);data.putAll(pending);commits++;return true;}
    };}
}
