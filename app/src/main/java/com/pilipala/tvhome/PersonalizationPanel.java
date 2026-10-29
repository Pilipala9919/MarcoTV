// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Map;

/** One scrollable editor; parameters share a reversible draft and a live scene. */
final class PersonalizationPanel extends LinearLayout {
    private final MainActivity host;
    private final PreviewPreferences draft;
    private final Map<String,ContinuousSlider> controls=new LinkedHashMap<>();
    private final Map<String,Integer> defaults=new LinkedHashMap<>();
    private final Map<String,View> groups=new LinkedHashMap<>();
    private TvDialog page;
    private TextView tiltHint;
    private String scene="personalization";
    private boolean recentPreviewLoaded;
    PersonalizationPanel(MainActivity host){
        super(host);this.host=host;draft=new PreviewPreferences(host.preferences);setOrientation(VERTICAL);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        section("cards","桌面卡片与动效");
        slider("卡片圆角","card_radius",0,90,32,"");
        slider("选中放大","focus_scale",0,30,16,"%");
        slider("未选中名称亮度","label_opacity",0,100,0,"%");
        slider("卡片质感","card_surface",0,100,55,"%");
        slider("界面动效时长","motion_duration",120,500,260," ms");
        section("clock","时间与屏保");
        slider("时钟字体","clock_style",0,ClockScene.STYLES.length-1,0,"");controls.get("clock_style").choices(ClockScene.STYLES);
        slider("圆体倾斜幅度","clock_tilt",0,14,10,"°");
        tiltHint=new TextView(host);TextView tip=tiltHint;tip.setText("倾斜幅度仅用于「柔圆错倾」字体");tip.setTextColor(0xFF9AB2C0);tip.setTextSize(11);tip.setPadding(dp(8),0,dp(8),dp(8));addView(tip);
        slider("桌面时间大小","clock_size",36,160,96,"");
        slider("进入屏保等待","idle_seconds",0,600,30," 秒");
        slider("屏保时间大小","ambient_clock_size",100,280,176,"");
        slider("屏保时间亮度","ambient_clock_brightness",40,100,100,"%");
        slider("屏保背景压暗","ambient_dim",0,100,72,"%");
        slider("屏保渐变时长","ambient_transition",1,30,3," 秒");
        action("屏保开关与系统接管…",()->host.navigator.ambientSettings(),"personalization");
        section("wallpaper","壁纸");
        slider("桌面壁纸压暗","wallpaper_dim",0,90,10,"%");
        action("选择或导入壁纸…",()->host.navigator.wallpaperSettings(),"personalization");
        section("recent","最近任务卡片");
        slider("任务卡片大小","recent_size",60,120,100,"%");
        slider("任务卡片间距","recent_spacing",80,360,210,"");
        slider("任务卡片倾斜","recent_tilt",0,18,7,"°");
        slider("任务卡片圆角","recent_radius",8,80,36,"");
        slider("任务背景压暗","recent_dim",0,90,65,"%");
        slider("任务动画时长","recent_duration",120,700,280," ms");
        slider("任务显示数量","recent_limit",3,20,10," 个");
        slider("Home 双击间隔","recent_double_ms",200,700,400," ms");
        updateClockControls();
    }
    private void updateClockControls(){
        View tilt=controls.get("clock_tilt");int visibility=draft.getInt("clock_style",0)==2?VISIBLE:GONE;
        if(tilt!=null)tilt.setVisibility(visibility);if(tiltHint!=null)tiltHint.setVisibility(visibility);
    }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void section(String id,String title){
        TextView label=new TextView(host);label.setText(title);label.setTextColor(0xFFB7CDD9);label.setTextSize(13);label.setTypeface(Typeface.DEFAULT,Typeface.BOLD);label.setPadding(dp(4),dp(15),0,dp(8));addView(label);groups.put(id,label);
    }
    private void slider(String title,String key,int min,int max,int fallback,String unit){
        ContinuousSlider control=new ContinuousSlider(host,title,unit,min,max,host.preferences.getInt(key,fallback),value->{
            draft.values.put(key,value);if(key.equals("clock_style"))updateClockControls();host.home.refreshAppearance();host.home.invalidate();
            if(key.equals("ambient_transition"))host.home.replayPreview();
        });control.inline();controls.put(key,control);defaults.put(key,fallback);
        control.setOnFocusChangeListener((v,focused)->{control.invalidate();if(focused)selectScene(key);});
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(66));params.bottomMargin=dp(6);addView(control,params);
    }
    private void selectScene(String key){
        scene=key;page.setPreviewScene(key);
        if(key.startsWith("recent_")&&!recentPreviewLoaded){recentPreviewLoaded=true;host.recents.load();}
    }
    private void action(String label,Runnable action,String key){
        TextView row=new TextView(host);row.setText(label);row.setTextColor(Color.WHITE);row.setTextSize(14);row.setPadding(dp(12),dp(14),dp(12),dp(14));row.setBackground(TvDialog.background(0xFF293541,dp(12)));row.setFocusable(true);row.setFocusableInTouchMode(true);
        row.setOnFocusChangeListener((v,focused)->{row.setBackground(TvDialog.background(focused?0xFF354B5D:0xFF293541,dp(12)));if(focused)selectScene(key);});row.setOnClickListener(v->action.run());addView(row);
    }
    void show(String group){
        if(host.phone!=null)host.phone.clearPreview();
        if(host.recents.visible)host.recents.close();
        page=new TvDialog.Builder(host).setPageId("personalization").setTitle("Magic Desktop").setPreview(scene).setView(this)
            .setPositiveButton("保存",(d,w)->save()).setNegativeButton("取消",null)
            .setInlineButton("默认值",(d,w)->{for(Map.Entry<String,ContinuousSlider> entry:controls.entrySet())entry.getValue().setValue(defaults.get(entry.getKey()));}).create();
        page.onLeave(()->{if(host.localPreview==draft){host.localPreview=null;host.home.refreshAppearance();host.home.invalidate();}});
        host.localPreview=draft;host.showDialog(page);
        post(()->{View heading=groups.get(group);int at=heading==null?0:indexOfChild(heading)+1;if(at<getChildCount())getChildAt(at).requestFocus();});
    }
    private void save(){SharedPreferences.Editor editor=host.preferences.edit();for(Map.Entry<String,Integer> value:draft.values.entrySet())editor.putInt(value.getKey(),value.getValue());editor.apply();host.home.refreshAppearance();host.home.invalidate();}
}
