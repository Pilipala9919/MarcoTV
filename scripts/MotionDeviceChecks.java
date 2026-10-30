// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;
import android.app.*;import android.os.*;import android.content.*;import android.view.*;import android.widget.*;import android.graphics.*;import java.lang.reflect.*;import java.util.*;
public final class MotionDeviceChecks extends Instrumentation {
 MainActivity host;int passed;
 Object field(Object o,String key){try{Field f=o.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}
 void ui(Runnable action){final Throwable[] error={null};runOnMainSync(()->{try{action.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new AssertionError(error[0]);}
 void check(String label,boolean ok){if(!ok)throw new AssertionError(label);passed++;Bundle b=new Bundle();b.putString("stream","PASS "+label+"\n");sendStatus(0,b);}
 TvDialog page(){return (TvDialog)field(host,"activeDialog");}
 ViewGroup root(){return (ViewGroup)field(host.panelWindow(),"root");}
 void waitMotion()throws Exception{Thread.sleep(1600);}
 void capture(String name)throws Exception{Bitmap b=getUiAutomation().takeScreenshot();if(b!=null){java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(host.getExternalFilesDir(null),name+".png"));b.compress(Bitmap.CompressFormat.PNG,100,out);out.close();b.recycle();}}
 public void onCreate(Bundle b){super.onCreate(b);start();}
 public void onStart(){Bundle result=new Bundle();int duration=260;boolean had=false;try{
 host=(MainActivity)startActivitySync(new Intent().setClassName("com.pilipala.tvhome","com.pilipala.tvhome.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(1000);
 duration=host.preferences.getInt("motion_duration",260);had=host.preferences.contains("motion_duration");
 ui(()->{host.preferences.edit().putInt("motion_duration",260).commit();host.home.refreshAppearance();host.exitMenus();host.home.setIdle(false);host.home.row=-1;host.home.animateFocus();});waitMotion();
 final float[] start={0};ui(()->{MotionSpring left=(MotionSpring)field(host.home,"headerLeft");start[0]=left.value;host.home.onKeyDown(22,new KeyEvent(0,22));check("header_retargets_without_jump",left.value==start[0]&&left.target>start[0]);});Thread.sleep(90);
 ui(()->{MotionSpring left=(MotionSpring)field(host.home,"headerLeft");check("header_has_intermediate_positions",left.value>start[0]&&left.value<left.target);float x=left.value;host.home.onKeyDown(21,new KeyEvent(0,21));check("header_reverse_preserves_position",left.value==x);});waitMotion();capture("motion-header");
 final TvDialog[] parent={null};final View[] focus={null};
 ui(()->{parent[0]=new TvDialog.Builder(host).setPageId("motion_parent").setTitle("Motion check").setItems(new String[]{"First","Second"},null).create();host.showDialog(parent[0]);});waitMotion();
 ui(()->{ViewGroup panel=(ViewGroup)field(parent[0],"panel");check("panel_uses_window_renderer",panel.getLayerType()==View.LAYER_TYPE_NONE&&panel.isHardwareAccelerated());check("initial_panel_animation_settles",panel.getTranslationX()==0);focus[0]=((java.util.List<View>)field(parent[0],"choices")).get(1);focus[0].requestFocus();host.showDialog(new TvDialog.Builder(host).setPageId("motion_child").setTitle("Child").create());check("transition_keeps_outgoing_until_animation_finishes",root().getChildCount()==2);});Thread.sleep(90);
 ui(()->{View child=(View)field(page(),"panel");float x=child.getTranslationX();check("child_entry_moves",x>0);page().onBackPressed();check("quick_back_returns_to_parent",page()==parent[0]);check("quick_back_restores_focus",focus[0].isFocused());check("quick_back_has_at_most_two_pages",root().getChildCount()<=2);});waitMotion();
 ui(()->check("settled_transition_releases_outgoing",root().getChildCount()==1));
 ui(()->{host.exitMenus();host.navigator.personalization("cards");});waitMotion();capture("motion-magic");
 ui(()->{check("preview_settles",(Float)field(host.home,"overview")==1f);check("live_preview_has_no_software_desktop_bitmap",field(host.home,"overviewBitmap")==null);ViewGroup panel=(ViewGroup)field(page(),"panel");check("magic_panel_not_software_cached",panel.getLayerType()==View.LAYER_TYPE_NONE);check("magic_scroll_uses_shared_motion",findScroll(panel)!=null);});
 for(int i=0;i<14;i++){ui(()->{host.panelWindow().dispatchKeyEvent(new KeyEvent(0,20));host.panelWindow().dispatchKeyEvent(new KeyEvent(1,20));});Thread.sleep(85);}waitMotion();capture("motion-scroll");
 ui(()->{View focusNow=page().getCurrentFocus();check("scroll_keeps_a_focused_control",focusNow!=null&&focusNow.isShown());host.exitMenus();check("closing_is_not_an_active_menu",!host.panelWindow().isShowing());host.navigator.settings();check("reopen_during_exit_is_active",host.panelWindow().isShowing());});waitMotion();
 ui(()->{check("reopen_has_single_settled_page",root().getChildCount()==1);host.exitMenus();});waitMotion();
 ui(()->{check("preview_returns_to_desktop",(Float)field(host.home,"overview")==0f);check("closed_window_releases_views",root().getChildCount()==0);});
 result.putString("stream","OK "+passed+" motion checks\n");
 }catch(Throwable e){result.putString("stream","FAIL "+e+"\n");}finally{if(host!=null){final int saved=duration;final boolean existed=had;ui(()->{android.content.SharedPreferences.Editor e=host.preferences.edit();if(existed)e.putInt("motion_duration",saved);else e.remove("motion_duration");e.commit();host.home.refreshAppearance();host.exitMenus();});}}finish(-1,result);}
 MotionScrollView findScroll(View v){if(v instanceof MotionScrollView)return (MotionScrollView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){MotionScrollView s=findScroll(((ViewGroup)v).getChildAt(i));if(s!=null)return s;}return null;}
}
