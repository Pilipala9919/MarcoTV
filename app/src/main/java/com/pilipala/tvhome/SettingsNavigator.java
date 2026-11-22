// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.animation.ArgbEvaluator;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.text.InputFilter;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;

/** Builds and navigates every settings panel; the activity only hosts dialogs. */
final class SettingsNavigator {
    private final MainActivity host;
    SettingsNavigator(MainActivity host) {
        this.host = host;
    }
    void personalization(String group){
        new PersonalizationPanel(host).show(group);
    }

    void settings(){
        host.showDialog(settingsDialog());
    }
    TvDialog settingsDialog() {
        SettingsScreen screen = new SettingsScreen(host);
        TvDialog page = new TvDialog.Builder(host)
                .setPageId("main")
                .setTitle("设置")
                .fullscreen()
                .setView(screen)
                .setBackHandler(screen::handleBack)
                .setKeyHandler(screen::handleKey)
                .create();
        screen.bindDialog(page);
        page.onReturn(screen::refresh);
        return page;
    }
    void applicationSettings(){
        host.showDialog(new TvDialog.Builder(host).setPageId("apps").setTitle("应用与分类")
                .setOrder("apps","add","sorting","favorites","pinned","hidden")
                .setItems(new String[]{"添加应用","应用排序","管理收藏卡片","管理置顶卡片","恢复隐藏的桌面卡片"},(d,w)->{
            switch(w){
                case 0:
                    host.library.show();
                    break;
                case 1:
                    sorting();
                    break;
                case 2:
                    managedCards("favorites","收藏卡片");
                    break;
                case 3:
                    managedCards("pinned","置顶卡片");
                    break;
                case 4:
                    hiddenCards();
                    break;
            }
        }).setNegativeButton("返回",null).create());
    }
    void managedCards(String key,String title){
        ArrayList<Tile> found=new ArrayList<>();
        for(Tile t:host.allTiles)if(host.cards.contains(key,t.id))found.add(t);
        if(found.isEmpty()){
            host.toast("暂无"+title);
            return;
        }
        String[] labels=new String[found.size()];
        for(int i=0;i<labels.length;i++)labels[i]=found.get(i).label;
        host.showDialog(new TvDialog.Builder(host).setPageId("manage_"+key).setTitle(title).setItems(labels,(d,w)->cardSettings(found.get(w)))
                .setNegativeButton("返回",null).create());
    }
    void maintenance(){
        host.showDialog(new TvDialog.Builder(host).setPageId("maintenance").setTitle("维护与帮助")
                .setOrder("maintenance","health","backup","diagnostics","guide","info","about","updates")
                .setItems(new String[]{"状态中心","备份与恢复","本地诊断","首次使用引导","设备信息","关于 Marco TV","软件更新"},(d,w)->{
            switch(w){
                case 0:
                    host.health.status();
                    break;
                case 1:
                    host.backups.show();
                    break;
                case 2:
                    host.health.diagnostics();
                    break;
                case 3:
                    host.health.guide(0);
                    break;
                case 4:
                    deviceInfo();
                    break;
                case 5:
                    about();
                    break;
                case 6:
                    host.updates.show();
                    break;
            }
        }).setNegativeButton("返回",null).create());
    }
    void deviceInfo(){
        host.showDialog(new TvDialog.Builder(host).setPageId("device_info").setTitle("设备信息")
                .setMessage("品牌："+Build.MANUFACTURER+"\n型号："+Build.MODEL+"\nAndroid："+Build.VERSION.RELEASE+"（API "+Build.VERSION.SDK_INT+"）"
                +"\n固件版本："+Build.DISPLAY+"\n\n请结合固件逐项验证安装、Home 和开机恢复；不显示设备序列号。")
                .setPositiveButton("返回",null).create());
    }
    void wallpaperSettings(){
        WallpaperCenterDialog.show(host);
    }

    void about(){
        host.showDialog(new TvDialog.Builder(host).setPageId("about").setTitle("Marco TV")
                .setMessage(ProjectInfo.about())
                .setPositiveButton("返回",null)
                .setNeutralButton("开源许可",(d,w)->host.handler.post(()->host.showDialog(new TvDialog.Builder(host)
                .setPageId("licenses").setTitle("开源许可")
                .setMessage(ProjectInfo.licenses(host))
                .setPositiveButton("返回上一级",null).create())))
                .create());
    }

    void slider(String title, String key, int min, int max, int fallback, String unit) {
        // Local and phone previews have independent ownership; only Save writes preferences.
        if(host.phone!=null)host.phone.clearPreview();
        PreviewPreferences draft=new PreviewPreferences(host.preferences);
        final int[] chosen={host.preferences.getInt(key,fallback)};
        ContinuousSlider slider=new ContinuousSlider(host,title,unit,min,max,chosen[0],value->{
            chosen[0]=value;
            draft.values.put(key,value);
            host.home.refreshAppearance();
            host.home.invalidate();
            if(key.equals("ambient_transition"))host.home.replayPreview();
        });
        slider.compact();
        TvDialog page=new TvDialog.Builder(host).setPageId("slider:"+key).setTitle(title).setPreview(key)
                .setView(slider)
                .setPositiveButton("保存",(d,w)->host.preferences.edit().putInt(key,chosen[0]).apply())
                .setNegativeButton("取消",null).setInlineButton("默认值",(d,w)->{
            slider.setValue(fallback);
        }).create();
        page.onLeave(()->{
            if(host.localPreview==draft){
                host.localPreview=null;
                host.home.refreshAppearance();
                host.home.invalidate();
            }
        });
        host.showDialog(page);
        host.localPreview=draft;
    }

    static final int[] SLEEP_CHOICES={15,30,60,120,0};
    static String sleepLabel(int minutes){
        return minutes<=0?"不限制（始终保持亮屏）":minutes+" 分钟后";
    }
    void ambientSettings() {
        boolean enabled=host.preferences.getBoolean("ambient_enabled",true);
        boolean pure=host.preferences.getBoolean("ambient_pure_wallpaper",false);
        host.showDialog(new TvDialog.Builder(host).setPageId("clock").setTitle("屏保开关")
                .setMessage("本页修改即时生效；时钟大小、亮度与渐变时长可在 Magic Desktop 调整。\n开启「闲置纯净壁纸模式」后，进入屏保将隐藏时钟并取消压暗，纯净欣赏动态或静态壁纸。")
                .setItems(new String[]{
                        "Marco TV 屏保："+(enabled?"已开启":"未开启"),
                        "闲置纯净壁纸模式："+(pure?"已开启":"未开启"),
                        "立即预览屏保",
                        "屏保后允许电视休眠："+sleepLabel(host.preferences.getInt("ambient_sleep_minutes",IdleController.DEFAULT_SLEEP_MINUTES))
                },(d,w)->{
            if(w==0){
                host.preferences.edit().putBoolean("ambient_enabled",!enabled).apply();
                AmbientIntegration.configure(host);
                host.screensaver.resume();
                ambientSettings();
                return;
            }
            if(w==1){
                host.preferences.edit().putBoolean("ambient_pure_wallpaper",!pure).apply();
                host.home.invalidate();
                ambientSettings();
                return;
            }
            if(w==2){
                previewAmbient();
                return;
            }
            int current=host.preferences.getInt("ambient_sleep_minutes",IdleController.DEFAULT_SLEEP_MINUTES),next=SLEEP_CHOICES[0];
            for(int i=0;i<SLEEP_CHOICES.length;i++)if(SLEEP_CHOICES[i]==current)next=SLEEP_CHOICES[(i+1)%SLEEP_CHOICES.length];
            host.preferences.edit().putInt("ambient_sleep_minutes",next).apply();
            ambientSettings();
        }).setNegativeButton("返回",null).create());
    }

    void previewAmbient(){
        host.showDialog(new TvDialog.Builder(host).setPageId("saver_preview").setTitle("屏保预览").setPreview("ambient_transition")
                .setPositiveButton("返回上一级",null)
                .setNeutralButton("重播预览",(d,w)->host.handler.post(this::previewAmbient)).create());
    }

    void soundSettings() {
        android.media.AudioManager audio = (android.media.AudioManager) host.getSystemService(Context.AUDIO_SERVICE);
        int value = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        int max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        ContinuousSlider slider = new ContinuousSlider(host, "媒体音量", "", 0, Math.max(1,max), value, v -> {
            try {
                audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0);
            }
            catch (RuntimeException e) {
                host.toast("系统限制音量调整，请使用遥控器音量键");
            }
        });
        host.showDialog(new TvDialog.Builder(host).setPageId("sound").setTitle("媒体音量").setMessage("左右调整媒体音量")
                .setView(slider).setPositiveButton("完成", null).create());
    }

    void tileOptions() {
        if (host.home.row < 0 || host.home.rows.isEmpty()) return;
        Row row = host.home.rows.get(host.home.row);
        if (row.tiles.isEmpty()) return;
        Tile tile = row.tiles.get(row.selected);
        boolean pinned = host.cards.contains("pinned", tile.id);
        boolean atFirst = row.selected == 0;
        boolean favorite = host.cards.contains("favorites", tile.id);
        String pinLabel = pinned && atFirst ? "取消置顶" : "置顶到首位";
        String[] options = tile.kind == 1
                ? new String[]{pinLabel,"移动卡片","重命名","自定义长宽高","更换封面",favorite?"取消收藏":"加入收藏","单独卡片设置","恢复默认外观","打开输入源"}
                : new String[]{pinLabel,"移动卡片","重命名","自定义长宽高","更换封面",favorite?"取消收藏":"加入收藏","单独卡片设置","卸载应用","恢复默认外观","应用信息"};
        host.showDialog(new TvDialog.Builder(host).setPageId(SettingsCatalog.scope("tile",tile.id)).setTitle(tile.label)
                .setItems(options,(d,w)->{
            if(tile.kind==1&&w>=7)w++;
            switch(w){
                case 0:
                    host.exitMenus();
                    if(pinned&&atFirst){
                        host.cards.unpin(tile.id);
                        host.loadApplications();
                        host.toast("已取消置顶");
                    }else{
                        AppOrder.pinToFirst(host.preferences,host.cards,row,tile);
                        host.focusAfterLoad=tile.id;
                        host.loadApplications();
                        host.toast("已置顶到首位");
                    }
                    break;
                case 1:
                    host.exitMenus();
                    host.home.beginMove();
                    break;
                case 2:
                    renameCard(tile);
                    break;
                case 3:
                    cardCustomize(tile);
                    break;
                case 4:
                    coverOptions(tile);
                    break;
                case 5:
                    host.cards.toggle("favorites",tile.id,!favorite);
                    host.loadApplications();
                    host.toast(favorite?"已取消收藏":"已加入收藏");
                    break;
                case 6:
                    cardSettings(tile);
                    break;
                case 7:
                    uninstall(tile);
                    break;
                case 8:
                    host.cards.reset(tile.id);
                    host.loadApplications();
                    break;
                case 9:
                    if(tile.kind==1)host.launch(tile);
                    else{
                        String pkg=packageNameOf(tile.id);
                        String version="";
                        boolean system=false,updated=false;
                        try{
                            PackageManager pm=host.getPackageManager();
                            PackageInfo pi=pm.getPackageInfo(pkg,0);
                            if(pi!=null&&pi.versionName!=null)version=pi.versionName;
                            ApplicationInfo ai=pm.getApplicationInfo(pkg,0);
                            system=(ai.flags&ApplicationInfo.FLAG_SYSTEM)!=0;
                            updated=(ai.flags&ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0;
                        }catch(Exception ignored){}
                        String kindText=system?(updated?"系统预装（已更新，可卸载更新）":"系统预装应用"):"用户安装应用（可卸载）";
                        host.showDialog(new TvDialog.Builder(host).setPageId(SettingsCatalog.scope("app_info",tile.id))
                                .setTitle(tile.label)
                                .setMessage("版本："+(version.isEmpty()?"未知":version)+"\n包名："+pkg+"\n类型："+kindText)
                                .setPositiveButton("打开应用",(x,y)->host.launch(tile))
                                .setNeutralButton("系统应用信息",(x,y)->openSystemAppInfo(pkg))
                                .setNegativeButton("返回",null).create());
                    }
                    break;
            }
        }).setNegativeButton("返回",null).create());
    }
    void cardCustomize(Tile tile){
        if(host.phone!=null)host.phone.clearPreview();
        CardStore.Dimensions dim=host.cards.dimensions(tile.id);
        int gWidth=host.preferences.getInt("card_width",100);
        int gHeight=host.preferences.getInt("card_height",100);
        int gDepth=host.preferences.getInt("card_depth",0);
        int gRadius=host.preferences.getInt("card_radius",32);
        LinearLayout panel=new LinearLayout(host);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setDescendantFocusability(LinearLayout.FOCUS_AFTER_DESCENDANTS);
        float density=host.getResources().getDisplayMetrics().density;
        int gap=Math.round(6*density);
        final ParameterCard[] cards=new ParameterCard[4];
        Runnable collapseOthers=()->{
            for(ParameterCard c:cards)if(c!=null&&c.isExpanded())c.collapse(false);
        };
        java.util.function.Consumer<ParameterCard> collapseExcept=keep->{
            for(ParameterCard c:cards)if(c!=null&&c!=keep&&c.isExpanded())c.collapse(false);
        };
        cards[0]=new ParameterCard(host,"卡片宽度","%",CardStore.MIN_WIDTH,CardStore.MAX_WIDTH,
                dim.width>0?dim.width:gWidth,
                dim.width>0?"已独立设置 · 范围 60%–160%":"当前跟随全局 · 调整即独立生效",
                v->{
                    dim.width=v;
                    host.cards.setDimensions(tile.id,dim);
                    host.catalog.applyDimensions(tile.id,dim);
                },null,()->collapseExcept.accept(cards[0]));
        cards[1]=new ParameterCard(host,"卡片高度","%",CardStore.MIN_HEIGHT,CardStore.MAX_HEIGHT,
                dim.height>0?dim.height:gHeight,
                dim.height>0?"已独立设置 · 范围 60%–150%":"当前跟随全局 · 调整即独立生效",
                v->{
                    dim.height=v;
                    host.cards.setDimensions(tile.id,dim);
                    host.catalog.applyDimensions(tile.id,dim);
                },null,()->collapseExcept.accept(cards[1]));
        cards[2]=new ParameterCard(host,"卡片厚度","%",CardStore.MIN_DEPTH,CardStore.MAX_DEPTH,
                dim.depth>=0?dim.depth:gDepth,
                dim.depth>=0?"已独立设置 · 立体厚度与悬浮阴影":"当前跟随全局 · 调整即独立生效",
                v->{
                    dim.depth=v;
                    host.cards.setDimensions(tile.id,dim);
                    host.catalog.applyDimensions(tile.id,dim);
                },null,()->collapseExcept.accept(cards[2]));
        cards[3]=new ParameterCard(host,"卡片圆角","",CardStore.MIN_RADIUS,CardStore.MAX_RADIUS,
                dim.radius>=0?dim.radius:gRadius,
                dim.radius>=0?"已独立设置 · 范围 0–90":"当前跟随全局 · 调整即独立生效",
                v->{
                    dim.radius=v;
                    host.cards.setDimensions(tile.id,dim);
                    host.catalog.applyDimensions(tile.id,dim);
                },null,()->collapseExcept.accept(cards[3]));
        for(ParameterCard c:cards){
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
            lp.bottomMargin=gap;
            panel.addView(c,lp);
        }
        ArgbEvaluator colors=new ArgbEvaluator();
        String[] actions={"手机扫码上传封面图片…","更换封面样式 / 本地图片…"};
        for(int i=0;i<actions.length;i++){
            final int index=i;
            TextView row=new TextView(host);
            row.setText(actions[i]);
            row.setTextColor(Color.WHITE);
            row.setTextSize(13.5f);
            int pad=Math.round(12*density);
            row.setPadding(pad,pad,pad,pad);
            row.setFocusable(true);
            row.setFocusableInTouchMode(true);
            GradientDrawable surface=TvDialog.background(0xFF232C37,pad);
            surface.setStroke(Math.round(density),0x14FFFFFF);
            row.setBackground(surface);
            UiMotion focus=new UiMotion(row,0,30,value->{
                surface.setColor((Integer)colors.evaluate(value,0xFF232C37,0xFF334A5E));
                surface.setStroke(Math.round(density),(Integer)colors.evaluate(value,0x14FFFFFF,0x55FFFFFF));
            });
            row.setOnFocusChangeListener((v,focused)->focus.to(focused?1:0));
            row.setOnClickListener(v->{
                collapseOthers.run();
                if(index==0)host.phone.showCardCoverImport(tile);
                else coverOptions(tile);
            });
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
            lp.bottomMargin=gap;
            panel.addView(row,lp);
        }
        TvDialog page=new TvDialog.Builder(host)
                .setPageId(SettingsCatalog.scope("card_size",tile.id))
                .setTitle("自定义 · "+tile.label)
                .setPreview("cards")
                .setView(panel)
                .setBackHandler(()->{
                    for(ParameterCard c:cards){
                        if(c!=null&&c.isExpanded()){
                            c.collapse(true);
                            return true;
                        }
                    }
                    return false;
                })
                .setInlineButton("恢复跟随全局",(d,w)->{
                    collapseOthers.run();
                    dim.width=0;
                    dim.height=0;
                    dim.depth=-1;
                    dim.radius=-1;
                    host.cards.resetDimensions(tile.id);
                    host.catalog.applyDimensions(tile.id,dim);
                    cards[0].setValue(gWidth,false);
                    cards[1].setValue(gHeight,false);
                    cards[2].setValue(gDepth,false);
                    cards[3].setValue(gRadius,false);
                    host.toast("已恢复跟随全局尺寸");
                })
                .create();
        host.showDialog(page);
        panel.post(()->cards[0].focusHeader());
    }
    void cardSettings(Tile tile){
        boolean pinned=host.cards.contains("pinned",tile.id),favorite=host.cards.contains("favorites",tile.id);
        ArrayList<String> options=new ArrayList<>();
        options.add(pinned?"取消置顶":"置顶卡片");
        options.add(favorite?"取消收藏":"加入收藏");
        options.add("自定义长宽高");
        options.add("更换封面");
        options.add("隐藏卡片");
        if(tile.kind==0)options.add("卸载应用");
        host.showDialog(new TvDialog.Builder(host).setPageId(SettingsCatalog.scope("card",tile.id)).setTitle("单独卡片设置 · "+tile.label)
                .setMessage("只影响当前卡片；整行排序请到应用与媒体设置。")
                .setItems(options.toArray(new String[0]),(d,w)->{
            if(w==0){
                if(pinned)host.cards.unpin(tile.id);
                else host.cards.pinFirst(tile.id);
                host.loadApplications();
                cardSettings(tile);
            }else if(w==1){
                host.cards.toggle("favorites",tile.id,!favorite);
                host.loadApplications();
                cardSettings(tile);
            }else if(w==2){
                cardCustomize(tile);
            }else if(w==3){
                coverOptions(tile);
            }
            else if(w==4)host.showDialog(new TvDialog.Builder(host).setPageId("hide_confirm").setTitle("隐藏「"+tile.label+"」？")
                    .setMessage("从桌面与收藏中隐藏，不卸载。可在应用与媒体设置中恢复。")
                    .setPositiveButton("隐藏",(x,y)->{
                host.cards.toggle("hidden",tile.id,true);
                host.loadApplications();
                host.showDialog(new TvDialog.Builder(host)
                        .setPageId("hide_done").setTitle("卡片已隐藏").setMessage("可以立即撤销，也可在应用与媒体设置中恢复。")
                        .setPositiveButton("撤销隐藏",(a,b)->{
                    host.cards.toggle("hidden",tile.id,false);
                    host.focusAfterLoad=tile.id;
                    host.exitMenus();
                    host.loadApplications();
                })
                        .setNegativeButton("返回桌面",(a,b)->host.exitMenus()).create());
            }).setNegativeButton("取消",null).create());
            else uninstall(tile);
        }).setNegativeButton("返回",null).create());
    }
    void sorting() {
        String mode=AppOrder.mode(host.preferences);
        int selected=java.util.Arrays.asList(AppOrder.MODES).indexOf(mode);
        host.showDialog(new TvDialog.Builder(host).setPageId("app_sort").setTitle("应用排序")
                .setMessage("置顶卡片始终优先。上次打开记录从 Marco TV 启动的应用；首字母按中文拼音 / 字母比较。自定义顺序独立保存，切换方式不会清空。")
                .setSingleChoiceItems(AppOrder.LABELS,selected,(d,w)->{
            if(AppOrder.MODES[w].equals("custom")&&host.preferences.getString("order_tv","").isEmpty()) {
                for(Row row:host.home.rows)if(row.orderKey.equals("order_tv")) {
                    StringBuilder order=new StringBuilder();
                    for(Tile t:row.tiles){
                        if(order.length()>0)order.append('|');
                        order.append(t.id);
                    }
                    host.preferences.edit().putString("order_tv",order.toString()).apply();
                }
            }
            host.preferences.edit().putString("tv_sort",AppOrder.MODES[w]).apply();
            host.sortCurrentApps();
            sorting();
        }).setNegativeButton("返回",null).create());
    }
    static String packageNameOf(String id){
        if(id==null)return "";
        int slash=id.indexOf('/');
        return slash>0?id.substring(0,slash):id;
    }
    void openSystemAppInfo(String pkg){
        try{
            host.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+pkg)));
        }catch(RuntimeException error){
            host.toast("电视未提供应用信息页面");
        }
    }
    private static final class ManagedApp {
        String pkg,label,version;
        Intent intent;
        boolean system,updatedSystem,pinned,hidden;
        boolean canUninstall(){
            return !system||updatedSystem;
        }
    }
    private int appManagerGeneration;
    void appManager(){
        appManager(0);
    }
    void appManager(final int filter){
        final String[] filterNames={"全部应用","可卸载应用","已隐藏应用","系统应用"};
        final int mode=Math.max(0,Math.min(filterNames.length-1,filter));
        final int req=++appManagerGeneration;
        final int epoch=host.menuEpoch;
        host.showDialog(new TvDialog.Builder(host).setPageId("app_manager").setTitle("应用管理 · "+filterNames[mode])
                .setMessage("正在读取已安装应用…").setNegativeButton("返回",null).create());
        host.loader.execute(()->{
            ArrayList<ManagedApp> list=new ArrayList<>();
            HashSet<String> seen=new HashSet<>();
            PackageManager pm=host.getPackageManager();
            try{
                for(String category:new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER,Intent.CATEGORY_LAUNCHER}){
                    for(ResolveInfo info:pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category),0)){
                        ActivityInfo a=info.activityInfo;
                        if(a==null||!AppEntries.launchable(host,a)||a.packageName.equals(host.getPackageName())||!seen.add(a.packageName))continue;
                        ManagedApp m=new ManagedApp();
                        m.pkg=a.packageName;
                        m.label=host.cards.name(m.pkg,String.valueOf(info.loadLabel(pm)));
                        m.intent=new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(a.packageName,a.name))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                        int flags=a.applicationInfo.flags;
                        m.system=(flags&ApplicationInfo.FLAG_SYSTEM)!=0;
                        m.updatedSystem=(flags&ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0;
                        m.pinned=host.cards.contains("pinned",m.pkg);
                        m.hidden=host.cards.contains("hidden",m.pkg);
                        try{
                            PackageInfo pi=pm.getPackageInfo(m.pkg,0);
                            m.version=pi!=null&&pi.versionName!=null?pi.versionName:"";
                        }catch(Exception ignored){
                            m.version="";
                        }
                        if(mode==1&&!m.canUninstall())continue;
                        if(mode==2&&!m.hidden)continue;
                        if(mode==3&&!m.system)continue;
                        list.add(m);
                    }
                }
                Collator order=Collator.getInstance(Locale.CHINA);
                Collections.sort(list,(a,b)->{
                    int tierA=a.system?(a.updatedSystem?1:2):0;
                    int tierB=b.system?(b.updatedSystem?1:2):0;
                    if(tierA!=tierB)return Integer.compare(tierA,tierB);
                    if(a.hidden!=b.hidden)return a.hidden?1:-1;
                    return order.compare(a.label,b.label);
                });
            }catch(RuntimeException ignored){}
            host.handler.post(()->{
                if(req!=appManagerGeneration||host.isDestroyed()||epoch!=host.menuEpoch||!host.isPage("app_manager"))return;
                String[] labels=new String[list.size()];
                for(int i=0;i<list.size();i++){
                    ManagedApp m=list.get(i);
                    StringBuilder sb=new StringBuilder(m.label);
                    if(m.pinned)sb.append(" [置顶]");
                    if(m.hidden)sb.append(" [已隐藏]");
                    if(m.system)sb.append(m.updatedSystem?" [系统·已更新]":" [系统]");
                    if(!m.version.isEmpty())sb.append(" · v").append(m.version);
                    sb.append(!m.system?" · 可卸载":(m.updatedSystem?" · 可卸载更新":" · 系统内置"));
                    labels[i]=sb.toString();
                }
                int nextFilter=(mode+1)%filterNames.length;
                TvDialog.Builder b=new TvDialog.Builder(host).setPageId("app_manager")
                        .setTitle("应用管理 · "+filterNames[mode]+"（"+list.size()+"）")
                        .setMessage(list.isEmpty()?"当前筛选下没有应用，可切换筛选条件查看。":"选择应用进行打开、置顶、隐藏、系统确认卸载或查看系统应用信息。")
                        .setNeutralButton("切换："+filterNames[nextFilter],(d,w)->appManager(nextFilter))
                        .setNegativeButton("返回",null);
                if(!list.isEmpty())b.setItems(labels,(d,w)->{
                    ManagedApp m=list.get(w);
                    managePackage(m.pkg,m.label,m.intent);
                });
                host.showDialog(b.create());
            });
        });
    }
    void managePackage(final String pkg,final String label,final Intent launchIntent){
        String version="";
        boolean system=false,updated=false;
        ApplicationInfo info=null;
        try{
            PackageManager pm=host.getPackageManager();
            info=pm.getApplicationInfo(pkg,0);
            system=(info.flags&ApplicationInfo.FLAG_SYSTEM)!=0;
            updated=(info.flags&ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0;
            PackageInfo pi=pm.getPackageInfo(pkg,0);
            if(pi!=null&&pi.versionName!=null)version=pi.versionName;
        }catch(Exception ignored){
            host.toast("应用已发生变化，请重新打开列表");
            return;
        }
        final ApplicationInfo appInfo=info;
        final boolean isSystem=system,isUpdated=updated;
        final boolean pinned=host.cards.contains("pinned",pkg),hidden=host.cards.contains("hidden",pkg);
        String uninstallLabel=isSystem?(isUpdated?"卸载更新（系统确认）":"系统内置应用（不可直接卸载）"):"卸载应用（系统确认）";
        String[] options={
                "打开应用",
                "添加到桌面 / 选择入口",
                pinned?"取消置顶":"置顶到首位",
                hidden?"恢复显示到桌面":"从桌面隐藏",
                uninstallLabel,
                "系统应用信息（清缓存 / 强停）"
        };
        StringBuilder msg=new StringBuilder("包名：").append(pkg);
        if(!version.isEmpty())msg.append("\n版本：").append(version);
        msg.append("\n类型：").append(isSystem?(isUpdated?"系统预装（已更新，可卸载更新）":"系统预装应用"):"用户安装应用（可卸载）");
        if(pinned||hidden)msg.append("\n状态：").append(pinned?"已置顶":"").append(pinned&&hidden?" · ":"").append(hidden?"已隐藏":"");
        host.showDialog(new TvDialog.Builder(host).setPageId(SettingsCatalog.scope("pkg",pkg))
                .setTitle("管理 · "+label).setMessage(msg.toString())
                .setItems(options,(d,w)->{
                    if(w==0){
                        Intent target=launchIntent!=null?launchIntent:host.getPackageManager().getLaunchIntentForPackage(pkg);
                        if(target==null){
                            host.toast("该应用没有默认可启动页面");
                            return;
                        }
                        Tile t=new Tile();
                        t.id=pkg;
                        t.label=label;
                        t.intent=target;
                        host.launch(t);
                    }else if(w==1){
                        host.library.choose(appInfo);
                    }else if(w==2){
                        if(pinned)host.cards.unpin(pkg);
                        else host.cards.pinFirst(pkg);
                        host.loadApplications();
                        host.toast(pinned?"已取消置顶":"已置顶到首位");
                        managePackage(pkg,label,launchIntent);
                    }else if(w==3){
                        host.cards.toggle("hidden",pkg,!hidden);
                        host.loadApplications();
                        host.toast(hidden?"已恢复显示到桌面":"已从桌面隐藏");
                        managePackage(pkg,label,launchIntent);
                    }else if(w==4){
                        uninstallPackage(pkg,label);
                    }else{
                        openSystemAppInfo(pkg);
                    }
                }).setNegativeButton("返回",null).create());
    }
    private void uninstall(Tile tile) {
        uninstallPackage(packageNameOf(tile.id),tile.label);
    }
    void uninstallPackage(String pkg,String label) {
        boolean system=false,updatedSystem=false;
        try {
            int flags=host.getPackageManager().getApplicationInfo(pkg,0).flags;
            system=(flags&ApplicationInfo.FLAG_SYSTEM)!=0;
            updatedSystem=(flags&ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0;
        }catch(Exception ignored){}
        if(system&&!updatedSystem) {
            host.showDialog(new TvDialog.Builder(host).setTitle("系统内置应用")
                    .setMessage("此应用由电视系统预装，不能当作普通应用卸载。可以隐藏卡片，或前往系统应用信息查看缓存与停用选项。")
                    .setPositiveButton("查看系统应用信息",(d,w)->openSystemAppInfo(pkg))
                    .setNegativeButton("返回",null).create());
            return;
        }
        String title=updatedSystem?("卸载「"+label+"」的更新？"):("卸载「"+label+"」？");
        String body=updatedSystem
                ?"此为系统预装应用的更新版本。将打开系统卸载确认页，确认后将卸载更新并还原出厂版本。"
                :"将打开系统卸载确认页。确认卸载后，应用及其本地数据将被删除。";
        host.showDialog(new TvDialog.Builder(host).setTitle(title)
                .setMessage(body)
                .setPositiveButton("继续卸载",(d,w)->{
            try {
                host.startActivity(new Intent(Intent.ACTION_DELETE,Uri.parse("package:"+pkg)));
            }
            catch(RuntimeException error){
                host.toast("电视未允许卸载此应用");
            }
        }).setNegativeButton("取消",null).create());
    }

    void renameCard(Tile tile) {
        EditText input = new EditText(host);
        input.setSingleLine(true);
        input.setText(tile.label);
        input.selectAll();
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        input.setHint("输入卡片名称");
        input.setContentDescription("卡片名称");
        int padding = Math.round(24 * host.getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding / 2, padding, padding / 2);
        host.showDialog(new TvDialog.Builder(host).setPageId(SettingsCatalog.scope("rename",tile.id))
                .setTitle("重命名卡片").setView(input)
                .setPositiveButton("保存", (d, w) -> {
            host.cards.setName(tile.id, input.getText().toString());
            host.loadApplications();
        })
                .setNeutralButton("原始名称", (d, w) -> {
            host.cards.setName(tile.id, "");
            host.loadApplications();
        })
                .setNegativeButton("返回", null).create());
    }

    void coverOptions(Tile tile) {
        String[] names = {"手机扫码上传图片…", "电视本地图片…", "原始图标或横幅", "午夜蓝", "森林绿", "暖琥珀", "雾紫"};
        String[] styles = {"qr", "image", "original", "blue", "green", "amber", "purple"};
        host.showDialog(new TvDialog.Builder(host).setPageId(SettingsCatalog.scope("cover",tile.id)).setTitle("更换封面 · " + tile.label)
                .setItems(names, (d, which) -> {
            if (which == 0) {
                host.phone.showCardCoverImport(tile);
            }
            else if (which == 1) {
                host.cover.pendingId = tile.id;
                host.handler.post(host.cover::chooseCoverImage);
            }
            else {
                host.cards.cover(tile.id, styles[which]);
                host.loadApplications();
            }
        }).setNegativeButton("返回", null).create());
    }

    void hiddenCards() {
        ArrayList<Tile> hidden = new ArrayList<>();
        for (Tile tile : host.allTiles) if (host.cards.contains("hidden", tile.id)) hidden.add(tile);
        if (hidden.isEmpty()) {
            host.toast("没有隐藏的卡片");
            return;
        }
        String[] labels = new String[hidden.size()];
        for (int i = 0; i < hidden.size(); i++) labels[i] = hidden.get(i).label;
        host.showDialog(new TvDialog.Builder(host).setPageId("hidden_cards").setTitle("恢复隐藏的桌面卡片").setItems(labels, (d, which) -> {
            host.cards.toggle("hidden", hidden.get(which).id, false);
            host.loadApplications();
        }).setNeutralButton("全部恢复", (d, w) -> {
            for (String id : host.cards.set("hidden")) host.cards.toggle("hidden", id, false);
            host.loadApplications();
        }).setNegativeButton("返回", null).create());
    }

    void appearanceSettings(){
        personalization("cards");
    }

    void systemPanel(){
        final int epoch=host.menuEpoch;
        SystemIntegration.show(host,d->{
            if(epoch==host.menuEpoch)host.showDialog(d);
        });
    }
}
