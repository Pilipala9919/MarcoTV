// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;
import android.app.*;import android.os.*;import android.content.*;import android.graphics.*;import android.view.*;import java.util.*;import java.io.*;

/** Run only against a designated emulator/test device; restores all Lite preferences. */
public final class LiteDeviceChecks extends Instrumentation {
    int passed;LiteActivity host;Map<String,?> before;Bundle args;String output;
    void check(String name,boolean ok){if(!ok)throw new AssertionError(name);passed++;Bundle b=new Bundle();b.putString("stream","PASS "+name+"\n");sendStatus(0,b);}
    void ui(final Runnable action){final Throwable[] error={null};runOnMainSync(new Runnable(){public void run(){try{action.run();}catch(Throwable t){error[0]=t;}}});if(error[0]!=null)throw new AssertionError(error[0]);}
    public void onCreate(Bundle arguments){super.onCreate(arguments);args=arguments;start();}
    void key(int key){host.home.onKeyDown(key,new KeyEvent(KeyEvent.ACTION_DOWN,key));host.home.onKeyUp(key,new KeyEvent(KeyEvent.ACTION_UP,key));}
    void screenshot(String name){try{Bitmap b=Bitmap.createBitmap(host.home.getWidth(),host.home.getHeight(),Bitmap.Config.ARGB_8888);host.home.draw(new Canvas(b));FileOutputStream f=new FileOutputStream(new File(host.getFilesDir(),name+".png"));try{b.compress(Bitmap.CompressFormat.PNG,100,f);}finally{f.close();b.recycle();}}catch(Exception e){throw new RuntimeException(e);}}
    public void onStart(){Bundle result=new Bundle();try{
        host=(LiteActivity)startActivitySync(new Intent().setClassName("com.pilipala.tvhome.lite","com.pilipala.tvhome.LiteActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));before=new HashMap<String,Object>(host.prefs.getAll());Thread.sleep(2500);
        ui(new Runnable(){public void run(){
            check("actual_android_api_is_14",Build.VERSION.SDK_INT==14);
            check("lite_has_separate_package",host.getPackageName().equals("com.pilipala.tvhome.lite"));
            check("launcher_apps_discovered",host.apps.size()>0);
            boolean unique=true;HashSet<String> ids=new HashSet<String>();int icons=0;for(LiteActivity.App a:host.apps){unique&=ids.add(a.id);if(a.icon!=null){icons++;checkIcon(a.icon);}}
            check("apps_unique_and_icon_cache_bounded",unique&&icons<=64);
            check("wallpaper_decoded_under_memory_budget",host.home.wallpaper!=null&&host.home.wallpaper.getWidth()*host.home.wallpaper.getHeight()<=1920*1080);
            host.prefs.edit().clear().commit();host.home.closePage();host.home.selected=0;host.home.topFocus=false;
            key(22);check("dpad_moves_to_second_card",host.home.selected==1);key(21);check("dpad_returns_to_first_card",host.home.selected==0);
            key(19);check("up_focuses_header",host.home.topFocus);key(22);key(23);check("header_opens_magic_desktop",host.home.page==2&&host.home.draft!=null);
            host.home.choice=0;host.home.change(1);check("position_preview_is_reversible_draft",host.home.draft.getInt("card_row_position",75)==76&&!host.prefs.contains("card_row_position"));
            key(4);check("back_discards_draft",host.home.page==0&&host.home.draft==null&&!host.prefs.contains("card_row_position"));
            host.home.open(2);host.home.choice=1;host.home.change(1);host.home.choice=LiteSettings.KEYS.length;host.home.activate();check("save_commits_height",host.prefs.getInt("card_height",0)==101&&host.home.page==0);
            host.home.open(2);host.home.choice=1;host.home.change(1);host.onNewIntent(new Intent());check("home_intent_discards_unsaved_draft",host.home.page==0&&host.prefs.getInt("card_height",0)==101);
            host.home.open(2);host.home.choice=LiteSettings.KEYS.length+2;host.home.activate();check("defaults_require_save",host.home.draft.getInt("card_height",0)==100&&host.prefs.getInt("card_height",0)==101);
            host.home.closePage();host.prefs.edit().putInt("card_row_position",25).putInt("card_height",125).putInt("clock_style",2).commit();host.backup(false);host.prefs.edit().putInt("card_height",70).commit();host.backup(true);
            check("local_backup_restores_parameters",host.prefs.getInt("card_height",0)==125&&host.prefs.getInt("card_row_position",0)==25&&host.prefs.getInt("clock_style",0)==2);
            int original=host.prefs.getInt("card_height",0);try{FileOutputStream f=new FileOutputStream(new File(host.getFilesDir(),"lite-backup.json"));f.write("invalid".getBytes("UTF-8"));f.close();}catch(Exception e){throw new RuntimeException(e);}host.backup(true);check("invalid_backup_keeps_settings",host.prefs.getInt("card_height",0)==original);
            HashSet<Integer> hashes=new HashSet<Integer>();Bitmap frame=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(frame);ClockScene clock=new ClockScene(host);PreviewPreferences p=new PreviewPreferences(host.prefs);
            for(int style=0;style<4;style++){frame.eraseColor(Color.TRANSPARENT);p.values.put("clock_style",style);clock.draw(c,p,0);int[] pixels=new int[frame.getWidth()*frame.getHeight()];frame.getPixels(pixels,0,frame.getWidth(),0,0,frame.getWidth(),frame.getHeight());hashes.add(Arrays.hashCode(pixels));}
            check("four_static_clock_fonts_render_on_api14",hashes.size()==4);frame.recycle();
            host.home.ambient=true;host.home.saver.snap(1);host.home.ambientAmount=1;host.home.topFocus=true;host.home.top=1;key(23);check("wake_confirm_does_not_open_menu",host.home.page==0&&!host.home.ambient);
            host.home.saver.snap(0);host.home.ambientAmount=0;host.home.open(1);key(4);check("settings_back_returns_to_desktop",host.home.page==0);
            host.home.open(4);key(4);check("status_back_returns_one_level",host.home.page==1);host.home.closePage();
            host.home.open(2);host.home.draft.values.put("card_row_position",65);host.home.draft.values.put("card_height",115);screenshot("lite-magic-api14");host.home.closePage();screenshot("lite-home-api14");
            host.home.ambient=true;host.home.saver.snap(1);host.home.ambientAmount=1;screenshot("lite-clock-api14");host.home.wake();
            host.home.pause();check("paused_view_stops_frame_and_timer_loop",!host.home.running);host.home.resume();check("resume_restarts_desktop",host.home.running&&!host.home.ambient);
        }});
        // Launch a real installed app and verify Lite is paused rather than trapping navigation.
        ui(new Runnable(){public void run(){host.launch(host.apps.get(0));}});Thread.sleep(700);check("application_launch_leaves_lite",!host.alive);
        result.putString("stream","OK "+passed+" Lite API14 checks\n");
    }catch(Throwable e){result.putString("stream","FAIL "+e+"\n");}finally{if(host!=null&&before!=null)ui(new Runnable(){public void run(){host.home.closePage();SharedPreferences.Editor edit=host.prefs.edit().clear();for(Map.Entry<String,?> x:before.entrySet()){Object v=x.getValue();if(v instanceof Integer)edit.putInt(x.getKey(),(Integer)v);else if(v instanceof Long)edit.putLong(x.getKey(),(Long)v);else if(v instanceof Boolean)edit.putBoolean(x.getKey(),(Boolean)v);else if(v instanceof String)edit.putString(x.getKey(),(String)v);}edit.commit();}});}finish(-1,result);}
    void checkIcon(Bitmap b){if(b.getWidth()>96||b.getHeight()>96)throw new AssertionError("icon dimensions");}
}
