// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;
import android.app.*;import android.os.*;import android.content.*;import android.view.*;import android.widget.*;import java.util.*;import java.lang.reflect.*;
public final class DrawerHomeChecks extends Instrumentation {
 MainActivity host;int passed;
 Object field(Object o,String n){try{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}
 void check(String n,boolean ok){if(!ok)throw new AssertionError(n);passed++;Bundle b=new Bundle();b.putString("stream","PASS "+n+"\n");sendStatus(0,b);}
 void ui(Runnable r){final Throwable[] e={null};runOnMainSync(()->{try{r.run();}catch(Throwable t){e[0]=t;}});if(e[0]!=null)throw new AssertionError(e[0]);}
 TvDialog page(){return (TvDialog)field(host,"activeDialog");}
 GridLayout grid(){if(page()==null)return null;Object spec=field(page(),"spec");Object view=field(spec,"view");return view instanceof GridLayout?(GridLayout)view:null;}
 public void onCreate(Bundle b){super.onCreate(b);start();}
 public void onStart(){Bundle result=new Bundle();try{
 host=(MainActivity)startActivitySync(new Intent().setClassName("com.pilipala.tvhome","com.pilipala.tvhome.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(1500);
 ui(()->{host.exitMenus();host.drawer.show();});
 for(int i=0;i<100;i++){final boolean[] ready={false};ui(()->ready[0]=grid()!=null);if(ready[0])break;Thread.sleep(100);}
 ui(()->{GridLayout g=grid();check("drawer_grid_loaded",g!=null&&g.getChildCount()>0);check("drawer_has_five_columns",g.getColumnCount()==5);HashSet<Object> ids=new HashSet<>();boolean labels=true,focusable=true;for(int i=0;i<g.getChildCount();i++){TextView v=(TextView)g.getChildAt(i);labels&=v.getText().length()>0;focusable&=v.isFocusable();ids.add(v.getTag());}check("drawer_packages_unique",ids.size()==g.getChildCount());check("drawer_names_visible",labels);check("drawer_cells_focusable",focusable);check("drawer_first_cell_focuses",g.getChildAt(0).requestFocus());if(g.getChildCount()>1)check("drawer_right_navigation_points_to_next",g.getChildAt(0).getNextFocusRightId()==g.getChildAt(1).getId());else check("drawer_single_app_navigation",true);
 host.panelWindow().onBackPressed();check("drawer_back_skips_loading_page",!host.isPage("all_apps"));
 Map<String,?> before=SystemIntegration.prefs(host).getAll();int alias=host.getPackageManager().getComponentEnabledSetting(new ComponentName(host,"com.pilipala.tvhome.HomeActivity"));host.defaultHome.show();check("simple_home_page_opens",host.isPage("default_home"));check("opening_home_help_does_not_change_settings",before.equals(SystemIntegration.prefs(host).getAll())&&alias==host.getPackageManager().getComponentEnabledSetting(new ComponentName(host,"com.pilipala.tvhome.HomeActivity")));host.defaultHome.help();check("blocked_home_has_plain_help",host.isPage("default_home_help"));host.panelWindow().onBackPressed();check("home_help_back_restores_parent",host.isPage("default_home"));host.exitMenus();
 host.home.row=-1;try{Field f=HomeView.class.getDeclaredField("topSelected");f.setAccessible(true);f.setInt(host.home,3);}catch(Exception e){throw new RuntimeException(e);}host.home.onKeyUp(23,new KeyEvent(1,23));check("header_opens_drawer",host.isPage("all_apps"));host.exitMenus();
 });Thread.sleep(400);ui(()->check("closed_drawer_does_not_reopen_after_load",!host.isPage("all_apps")));
 result.putString("stream","OK 14 drawer/home checks\n");
 }catch(Throwable e){result.putString("stream","FAIL "+e+"\n");}finally{if(host!=null)ui(()->host.exitMenus());}finish(-1,result);}
}
