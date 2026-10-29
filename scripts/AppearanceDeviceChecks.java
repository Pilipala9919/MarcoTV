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
    static void ui(Instrumentation inst,Runnable work){final Throwable[] error={null};inst.runOnMainSync(()->{try{work.run();}catch(Throwable t){error[0]=t;}});if(error[0]!=null)throw new AssertionError(error[0]);}
    static void run(Instrumentation inst,MainActivity host,BiConsumer<String,Boolean> check)throws Exception{
        SharedPreferences prefs=host.preferences;boolean hadSurface=prefs.contains("card_surface"),hadMotion=prefs.contains("motion_duration");
        int savedSurface=prefs.getInt("card_surface",55),savedMotion=prefs.getInt("motion_duration",260);
        final TvDialog[] dialog={null};final Throwable[] failure={null};
        try{
            final boolean[] ready={false};
            for(int attempt=0;attempt<50;attempt++){ui(inst,()->ready[0]=host.home.isShown()&&host.home.hasWindowFocus());if(ready[0])break;Thread.sleep(100);}
            if(!ready[0])throw new AssertionError("Desktop did not acquire input focus");
            ui(inst,()->{host.exitMenus();host.home.setIdle(false);prefs.edit().putInt("motion_duration",500).commit();host.home.refreshAppearance();host.home.enter();});
            MotionSpring entrance=(MotionSpring)field(host.home,"entrance");
            final boolean[] intermediate={false};
            for(int attempt=0;attempt<25;attempt++){Thread.sleep(20);ui(inst,()->intermediate[0]=entrance.value>0&&entrance.value<1);if(intermediate[0])break;}
            check.accept("desktop_entry_has_intermediate_frames",intermediate[0]);
            for(int attempt=0;attempt<30;attempt++){Thread.sleep(100);final boolean[] done={false};ui(inst,()->done[0]=entrance.value==1);if(done[0])break;}ui(inst,()->check.accept("desktop_entry_settles",entrance.value==1));
            ui(inst,()->{
                dialog[0]=new TvDialog.Builder(host).setPageId("appearance_fixture").setTitle("Animation fixture").setItems(new String[]{"One","Two"},null).create();
                host.showDialog(dialog[0]);
            });
            View panel=(View)field(dialog[0],"panel");Thread.sleep(80);
            ui(inst,()->check.accept("panel_entry_has_intermediate_frames",panel.getAlpha()==1&&panel.getTranslationX()>0));
            Thread.sleep(600);ui(inst,()->check.accept("panel_entry_settles",panel.getAlpha()==1&&panel.getTranslationX()==0));
            ui(inst,()->{host.showDialog(new TvDialog.Builder(host).setPageId("appearance_child").setTitle("Child").create());});
            Thread.sleep(600);ui(inst,()->{try{TvDialog child=(TvDialog)field(host,"activeDialog");check.accept("settings_reuse_same_native_window",child.getWindow()==dialog[0].getWindow());child.onBackPressed();}catch(Exception e){throw new RuntimeException(e);}});
            Thread.sleep(80);ui(inst,()->check.accept("cached_parent_replays_return_animation",panel.getAlpha()==1&&panel.getTranslationX()<0));
            Thread.sleep(600);
            final View[] parentFocus={null},childFocus={null};final TvDialog[] settingsRoot={null};final int[] rootLeft={0};
            ui(inst,()->{try{
                host.exitMenus();host.navigator.settings();settingsRoot[0]=(TvDialog)field(host,"activeDialog");
                parentFocus[0]=PhoneDeviceChecks.find((View)field(settingsRoot[0],"panel"),"维护与帮助");parentFocus[0].requestFocus();
            }catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(350);
            ui(inst,()->{try{rootLeft[0]=((View)field(settingsRoot[0],"panel")).getLeft();parentFocus[0].performClick();}catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(400);
            ui(inst,()->{try{
                android.view.ViewGroup root=(android.view.ViewGroup)field(host.panelWindow(),"root");
                check.accept("settings_detail_uses_compact_width",root.getChildAt(0).getWidth()<=361*host.getResources().getDisplayMetrics().density);
                check.accept("settings_child_replaces_parent_in_same_position",root.getChildCount()==1&&root.getChildAt(0).getLeft()==rootLeft[0]);
                check.accept("settings_parent_is_detached",((View)field(settingsRoot[0],"panel")).getParent()==null);
                childFocus[0]=PhoneDeviceChecks.find(root,"关于 Marco TV");childFocus[0].requestFocus();childFocus[0].performClick();
            }catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(350);
            ui(inst,()->{try{
                android.view.ViewGroup root=(android.view.ViewGroup)field(host.panelWindow(),"root");
                check.accept("deeper_page_does_not_stack_panels",root.getChildCount()==1&&host.isPage("about"));
                ((TvDialog)field(host,"activeDialog")).onBackPressed();
            }catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(350);
            ui(inst,()->{try{
                check.accept("nested_back_restores_clicked_row",host.isPage("maintenance")&&childFocus[0].isFocused());
                ((TvDialog)field(host,"activeDialog")).onBackPressed();
            }catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(350);
            ui(inst,()->{try{
                check.accept("root_back_restores_clicked_nonfirst_row",host.isPage("main")&&parentFocus[0].isFocused());
                parentFocus[0].performClick();
            }catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(350);
            ui(inst,()->{try{
                ((TvDialog)field(host,"activeDialog")).onBackPressed();
            }catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(350);
            ui(inst,()->{try{
                check.accept("repeated_return_keeps_same_focus",parentFocus[0].isFocused());
                host.navigator.slider("卡片圆角","card_radius",0,80,32,"");
            }catch(Exception e){throw new RuntimeException(e);}});
            Thread.sleep(400);
            ui(inst,()->{try{
                check.accept("preview_overview_reaches_perspective",(Float)field(host.home,"overview")==1f);
                android.graphics.Matrix matrix=(android.graphics.Matrix)field(host.home,"overviewMatrix");float[] corners={0,0,host.home.getWidth(),0,host.home.getWidth(),host.home.getHeight(),0,host.home.getHeight()};matrix.mapPoints(corners);
                check.accept("preview_keeps_entire_desktop_left_of_controls",corners[0]>=0&&corners[2]<host.home.getWidth()*.67f&&corners[1]!=corners[3]);
                check.accept("preview_right_edge_recedes",corners[3]>corners[1]&&corners[5]<corners[7]);
                android.graphics.Paint previewPaint=(android.graphics.Paint)field(host.home,"overviewPaint");
                check.accept("preview_uses_filtered_antialiased_surface",field(host.home,"overviewBitmap")!=null&&previewPaint.isAntiAlias()&&previewPaint.isFilterBitmap());
                host.exitMenus();
            }catch(Exception e){throw new RuntimeException(e);}});
            Thread.sleep(350);
            ui(inst,()->{try{
                check.accept("preview_releases_surface_on_exit",field(host.home,"overviewBitmap")==null);
                check.accept("preview_exit_restores_full_desktop",(Float)field(host.home,"overview")==0f);
                host.network.show();
            }catch(Exception e){throw new RuntimeException(e);}});
            Thread.sleep(350);
            ui(inst,()->{try{
                android.view.ViewGroup root=(android.view.ViewGroup)field(host.panelWindow(),"root");View bubble=root.getChildAt(0);float density=host.getResources().getDisplayMetrics().density;
                check.accept("wifi_bubble_is_small_and_top_anchored",bubble.getWidth()<=301*density&&bubble.getHeight()<=351*density&&bubble.getTop()<host.home.getHeight()/3);
                android.widget.TextView wifi=(android.widget.TextView)PhoneDeviceChecks.find(bubble,"Wi-Fi");
                // Find the switch row, not the identical title.
                java.util.List<?> rows=(java.util.List<?>)field((TvDialog)field(host,"activeDialog"),"itemViews");wifi=(android.widget.TextView)rows.get(0);
                android.view.accessibility.AccessibilityNodeInfo info=wifi.createAccessibilityNodeInfo();
                check.accept("wifi_switch_reports_real_checked_state",info.isCheckable()&&info.isChecked()==((android.net.wifi.WifiManager)host.getApplicationContext().getSystemService(android.content.Context.WIFI_SERVICE)).isWifiEnabled()&&wifi.getCompoundDrawables()[2] instanceof ToggleDrawable);info.recycle();
                Bitmap on=Bitmap.createBitmap(52,32,Bitmap.Config.ARGB_8888),off=Bitmap.createBitmap(52,32,Bitmap.Config.ARGB_8888);
                ToggleDrawable onToggle=new ToggleDrawable(true),offToggle=new ToggleDrawable(false);onToggle.setBounds(0,0,52,32);offToggle.setBounds(0,0,52,32);onToggle.draw(new Canvas(on));offToggle.draw(new Canvas(off));
                check.accept("wifi_switch_moves_knob_and_changes_track",on.getPixel(36,16)==Color.WHITE&&off.getPixel(16,16)==Color.WHITE&&Color.green(on.getPixel(10,16))>Color.green(off.getPixel(42,16)));on.recycle();off.recycle();
                ((TvDialog)field(host,"activeDialog")).onBackPressed();
                check.accept("wifi_back_returns_directly_to_desktop",!host.panelWindow().isShowing());
            }catch(Exception e){throw new RuntimeException(e);}});
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
            ui(inst,()->{try{
                host.exitMenus();int before=prefs.getInt("card_radius",32);boolean hadRadius=prefs.contains("card_radius");
                host.navigator.slider("卡片圆角","card_radius",0,80,32,"");
                TvDialog preview=(TvDialog)field(host,"activeDialog");Object spec=field(preview,"spec");
                ContinuousSlider slider=(ContinuousSlider)field(spec,"view");slider.setValue(before==70?60:70);
                check.accept("preview_does_not_write_saved_preferences",prefs.getInt("card_radius",32)==before&&host.appearance().getInt("card_radius",32)!=before);
                preview.onBackPressed();check.accept("back_discards_local_preview",host.localPreview==null&&host.appearance().getInt("card_radius",32)==before);
                host.navigator.slider("卡片圆角","card_radius",0,80,32,"");host.exitMenus();
                check.accept("home_discards_local_preview",host.localPreview==null);
                host.navigator.slider("卡片圆角","card_radius",0,80,32,"");TvDialog savePage=(TvDialog)field(host,"activeDialog");
                ContinuousSlider saveSlider=(ContinuousSlider)field(field(savePage,"spec"),"view");saveSlider.setValue(before==70?60:70);
                PhoneDeviceChecks.find(savePage.getWindow().getDecorView(),"保存").performClick();host.exitMenus();
                check.accept("preview_save_commits_value",prefs.getInt("card_radius",32)!=(before));
                if(hadRadius)prefs.edit().putInt("card_radius",before).commit();else prefs.edit().remove("card_radius").commit();
                host.home.resetToApps();Row row=host.home.rows.get(0);int was=row.selected;
                row.selected=1;host.home.ensureVisible();check.accept("second_card_keeps_row_at_origin",row.scroll.target==0);
                row.selected=3;host.home.ensureVisible();check.accept("later_cards_anchor_to_second_position",row.scroll.target==700);
                row.selected=was;host.home.ensureVisible();
                host.settings();TvDialog root=(TvDialog)field(host,"activeDialog");root.onBackPressed();
                check.accept("root_back_closes_shared_window",!host.panelWindow().isShowing());
            }catch(Exception e){throw new RuntimeException(e);}});
            final int originalRadius=prefs.getInt("card_radius",32);final boolean hadPersonalRadius=prefs.contains("card_radius");
            final PersonalizationPanel[] editor={null};final TvDialog[] editorPage={null};
            ui(inst,()->{host.exitMenus();host.navigator.personalization("cards");});Thread.sleep(400);
            ui(inst,()->{try{
                editorPage[0]=(TvDialog)field(host,"activeDialog");editor[0]=(PersonalizationPanel)field(field(editorPage[0],"spec"),"view");
                java.util.Map<String,ContinuousSlider> controls=(java.util.Map<String,ContinuousSlider>)field(editor[0],"controls");
                check.accept("personalization_contains_all_twenty_parameters",controls.size()==20);
                controls.get("card_radius").setValue(originalRadius==70?60:70);
                check.accept("personalization_edits_stay_in_draft",prefs.getInt("card_radius",32)==originalRadius&&host.appearance().getInt("card_radius",32)!=originalRadius);
                controls.get("ambient_clock_size").requestFocus();
                check.accept("personalization_focus_selects_screensaver_preview","ambient_clock_size".equals(editorPage[0].previewKey()));
                host.navigator.wallpaperSettings();
            }catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(250);
            ui(inst,()->{try{((TvDialog)field(host,"activeDialog")).onBackPressed();}catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(350);
            ui(inst,()->{try{
                check.accept("personalization_child_return_keeps_draft",host.isPage("personalization")&&host.localPreview!=null&&host.appearance().getInt("card_radius",32)!=originalRadius);
                editorPage[0].onBackPressed();
                check.accept("personalization_back_discards_all_draft",host.localPreview==null&&prefs.getInt("card_radius",32)==originalRadius);
                host.navigator.personalization("cards");
            }catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(350);
            ui(inst,()->{try{
                TvDialog current=(TvDialog)field(host,"activeDialog");PersonalizationPanel edit=(PersonalizationPanel)field(field(current,"spec"),"view");
                java.util.Map<String,ContinuousSlider> controls=(java.util.Map<String,ContinuousSlider>)field(edit,"controls");controls.get("card_radius").setValue(originalRadius==70?60:70);
                PhoneDeviceChecks.find((View)field(current,"panel"),"保存").performClick();
            }catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(350);
            ui(inst,()->{
                check.accept("personalization_save_commits_draft",prefs.getInt("card_radius",32)!=originalRadius&&host.localPreview==null);
                if(hadPersonalRadius)prefs.edit().putInt("card_radius",originalRadius).commit();else prefs.edit().remove("card_radius").commit();
                host.navigator.personalization("cards");host.exitMenus();
                check.accept("personalization_home_discards_draft",host.localPreview==null);
            });
            final HomeKeyService[] service={null};final android.content.Intent[] launch={null};
            final SharedPreferences gesturePrefs=host.getSharedPreferences("test013_home_gestures",0);
            ui(inst,()->{try{
                gesturePrefs.edit().clear().putBoolean("home_compat",true).putBoolean("recent_enabled",true).putString("home_long_action","marco").commit();
                service[0]=new HomeKeyService();
                android.content.ContextWrapper context=new android.content.ContextWrapper(host){
                    @Override public SharedPreferences getSharedPreferences(String name,int mode){return gesturePrefs;}
                    @Override public android.content.Context createDeviceProtectedStorageContext(){return this;}
                    @Override public void startActivity(android.content.Intent intent){launch[0]=intent;}
                };
                Method attach=android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext",android.content.Context.class);attach.setAccessible(true);attach.invoke(service[0],context);
                check.accept("home_down_waits_for_release",service[0].onKeyEvent(new android.view.KeyEvent(0,3))&&launch[0]==null);
                service[0].onKeyEvent(new android.view.KeyEvent(1,3));check.accept("home_release_returns_desktop",launch[0]!=null&&!launch[0].getBooleanExtra("recent_apps",true));
                service[0].onKeyEvent(new android.view.KeyEvent(0,3));service[0].onKeyEvent(new android.view.KeyEvent(1,3));
                check.accept("home_double_release_opens_recents",launch[0].getBooleanExtra("recent_apps",false));launch[0]=null;
                service[0].onKeyEvent(new android.view.KeyEvent(0,3));
            }catch(Exception e){throw new RuntimeException(e);}});
            Thread.sleep(720);
            ui(inst,()->{
                check.accept("home_hold_opens_configured_action",launch[0]!=null&&launch[0].getBooleanExtra("recent_apps",false));launch[0]=null;
                service[0].onKeyEvent(new android.view.KeyEvent(1,3));check.accept("home_hold_release_does_not_launch_again",launch[0]==null);
                gesturePrefs.edit().putBoolean("home_native_keys",true).commit();
                check.accept("native_mode_passes_home_down_and_up",!service[0].onKeyEvent(new android.view.KeyEvent(0,3))&&!service[0].onKeyEvent(new android.view.KeyEvent(1,3)));
                gesturePrefs.edit().putBoolean("home_native_keys",false).commit();
                check.accept("non_home_navigation_passes_through",!service[0].onKeyEvent(new android.view.KeyEvent(0,21)));
                service[0].onKeyEvent(new android.view.KeyEvent(0,3));service[0].onKeyEvent(new android.view.KeyEvent(0,26));
            });Thread.sleep(720);
            ui(inst,()->{check.accept("power_cancels_pending_home_hold",launch[0]==null);gesturePrefs.edit().clear().commit();});
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
