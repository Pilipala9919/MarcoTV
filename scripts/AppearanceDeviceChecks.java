// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;
import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.graphics.*;
import android.view.View;
import java.lang.reflect.*;
import java.util.function.BiConsumer;

/** Exercises real entry/return animation and artwork overlays; restores preferences. */
final class AppearanceDeviceChecks {
    static Object field(Object object,String key)throws Exception{Field f=object.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(object);}
    static void ui(Instrumentation inst,Runnable work){inst.runOnMainSync(work);}
    static void run(Instrumentation inst,MainActivity host,BiConsumer<String,Boolean> check)throws Exception{
        SharedPreferences prefs=host.preferences;boolean hadSurface=prefs.contains("card_surface"),hadMotion=prefs.contains("motion_duration");
        int savedSurface=prefs.getInt("card_surface",55),savedMotion=prefs.getInt("motion_duration",260);
        final TvDialog[] dialog={null};final Throwable[] failure={null};
        try{
            ui(inst,()->{host.exitMenus();host.home.setIdle(false);prefs.edit().putInt("motion_duration",500).commit();host.home.refreshAppearance();host.home.enter();});
            MotionSpring entrance=(MotionSpring)field(host.home,"entrance");
            Thread.sleep(100);ui(inst,()->check.accept("desktop_entry_has_intermediate_frames",entrance.value>0&&entrance.value<1));
            Thread.sleep(1400);ui(inst,()->check.accept("desktop_entry_settles",entrance.value==1));
            ui(inst,()->{
                dialog[0]=new TvDialog.Builder(host).setPageId("appearance_fixture").setTitle("Animation fixture").setItems(new String[]{"One","Two"},null).create();
                host.showDialog(dialog[0]);
            });
            View panel=(View)field(dialog[0],"panel");Thread.sleep(80);
            ui(inst,()->check.accept("panel_entry_has_intermediate_frames",panel.getAlpha()>0.55f&&panel.getAlpha()<1&&panel.getTranslationX()>0));
            Thread.sleep(600);ui(inst,()->check.accept("panel_entry_settles",panel.getAlpha()==1&&panel.getTranslationX()==0));
            ui(inst,()->{host.showDialog(new TvDialog.Builder(host).setPageId("appearance_child").setTitle("Child").create());});
            Thread.sleep(600);ui(inst,()->{try{TvDialog child=(TvDialog)field(host,"activeDialog");child.onBackPressed();}catch(Exception e){throw new RuntimeException(e);}});
            Thread.sleep(80);ui(inst,()->check.accept("cached_parent_replays_return_animation",panel.getAlpha()<1&&panel.getTranslationX()<0));
            Thread.sleep(600);
            ui(inst,()->{try{
                host.exitMenus();
                Tile tile=new Tile();tile.label="Fixture";tile.banner=true;tile.artwork=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888);tile.artwork.eraseColor(0xFF8090A0);tile.emphasis.snap(1);
                Bitmap plain=Bitmap.createBitmap(600,500,Bitmap.Config.ARGB_8888),styled=Bitmap.createBitmap(600,500,Bitmap.Config.ARGB_8888);
                Method draw=HomeView.class.getDeclaredMethod("drawTile",Canvas.class,Tile.class,float.class,float.class,float.class);draw.setAccessible(true);
                prefs.edit().putInt("card_surface",0).commit();host.home.refreshAppearance();draw.invoke(host.home,new Canvas(plain),tile,300f,200f,1f);
                prefs.edit().putInt("card_surface",100).commit();host.home.refreshAppearance();draw.invoke(host.home,new Canvas(styled),tile,300f,200f,1f);
                check.accept("material_adds_top_light_and_bottom_shade",Color.red(styled.getPixel(300,120))>Color.red(plain.getPixel(300,120))&&Color.red(styled.getPixel(300,240))<Color.red(plain.getPixel(300,240)));
                check.accept("material_keeps_corners_transparent",Color.alpha(styled.getPixel(105,75))==0);
                check.accept("new_appearance_parameters_are_backed_up",BackupStore.capture(host,false).getJSONObject("home").has("card_surface")&&BackupStore.capture(host,false).getJSONObject("home").has("motion_duration"));
                check.accept("motion_backup_bounds",BackupStore.range("motion_duration")[0]==120&&BackupStore.range("motion_duration")[1]==500);
                tile.artwork.recycle();plain.recycle();styled.recycle();
            }catch(Throwable e){failure[0]=e;}});
            if(failure[0]!=null)throw new AssertionError(failure[0]);
            final Tile[] selected={null};
            Method press=HomeView.class.getDeclaredMethod("pressSelected",boolean.class);press.setAccessible(true);
            ui(inst,()->{try{host.home.resetToApps();selected[0]=host.home.rows.get(0).tiles.get(host.home.rows.get(0).selected);press.invoke(host.home,true);}catch(Exception e){throw new RuntimeException(e);}});
            Thread.sleep(100);ui(inst,()->check.accept("card_press_animates_without_jump",selected[0].press.value>0&&selected[0].press.value<1));
            ui(inst,()->{try{press.invoke(host.home,false);}catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(1000);
            ui(inst,()->check.accept("card_release_returns_to_rest",selected[0].press.value==0));
            ui(inst,()->{
                Bitmap b=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);b.eraseColor(0xFF336699);
                check.accept("uniform_icon_can_extend",ArtworkStyle.uniformEdge(b));
                b.setPixel(0,0,0x00336699);check.accept("transparent_icon_not_stretched",!ArtworkStyle.uniformEdge(b));
                b.setPixel(0,0,0xFFFFFFFF);check.accept("complex_edge_not_stretched",!ArtworkStyle.uniformEdge(b));b.recycle();
            });
        }finally{
            ui(inst,()->{SharedPreferences.Editor e=prefs.edit();if(hadSurface)e.putInt("card_surface",savedSurface);else e.remove("card_surface");if(hadMotion)e.putInt("motion_duration",savedMotion);else e.remove("motion_duration");e.commit();host.exitMenus();host.home.refreshAppearance();host.home.invalidate();});
        }
    }
}
