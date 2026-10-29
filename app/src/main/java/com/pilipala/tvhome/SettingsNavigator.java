// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Build;
import android.text.InputFilter;
import android.view.WindowManager;
import android.widget.EditText;
import java.util.ArrayList;
import java.util.Collections;

/** Builds and navigates every settings panel; the activity only hosts dialogs. */
final class SettingsNavigator {
    private final MainActivity host;
    SettingsNavigator(MainActivity host) { this.host = host; }
    void personalization(String group){new PersonalizationPanel(host).show(group);}

    void settings(){host.showDialog(settingsDialog());}
    TvDialog settingsDialog() {
        return new TvDialog.Builder(host).setPageId("main").setTitle("桌面设置")
            .setOrder("main_v2",java.util.Arrays.copyOfRange(SettingsCatalog.ROOT_IDS,1,SettingsCatalog.ROOT_IDS.length)).setItems(java.util.Arrays.copyOfRange(SettingsCatalog.ROOT_LABELS,1,SettingsCatalog.ROOT_LABELS.length),(d,w)->host.handler.post(()->{
                openCategory(w+1);
            })).setNegativeButton("返回桌面",null).create();
    }
    void openCategory(int index){switch(index){case 0:desktopSettings();break;case 1:applicationSettings();break;case 2:host.recents.settings();break;
        case 3:host.playback.settings();break;case 4:deviceSettings();break;case 5:systemPanel();break;case 6:maintenance();break;case 7:host.phone.show();break;}}
    void desktopSettings(){personalization("cards");}
    void applicationSettings(){host.showDialog(new TvDialog.Builder(host).setPageId("apps").setTitle("应用与分类")
        .setOrder("apps","add","sorting","favorites","pinned","hidden")
        .setItems(new String[]{"添加应用","TV 应用排序","管理收藏卡片","管理置顶卡片","恢复隐藏的桌面卡片"},(d,w)->{
            switch(w){case 0:host.library.show();break;case 1:sorting();break;case 2:managedCards("favorites","收藏卡片");break;
                case 3:managedCards("pinned","置顶卡片");break;case 4:hiddenCards();break;}
        }).setNegativeButton("返回",null).create());}
    private void managedCards(String key,String title){
        ArrayList<Tile> found=new ArrayList<>();for(Tile t:host.allTiles)if(host.cards.contains(key,t.id))found.add(t);
        if(found.isEmpty()){host.toast("暂无"+title);return;}
        String[] labels=new String[found.size()];for(int i=0;i<labels.length;i++)labels[i]=found.get(i).label;
        host.showDialog(new TvDialog.Builder(host).setPageId("manage_"+key).setTitle(title).setItems(labels,(d,w)->cardSettings(found.get(w)))
            .setNegativeButton("返回",null).create());
    }
    void maintenance(){host.showDialog(new TvDialog.Builder(host).setPageId("maintenance").setTitle("维护与帮助")
        .setOrder("maintenance","health","backup","diagnostics","guide","info","about","updates")
        .setItems(new String[]{"状态中心","备份与恢复","本地诊断","首次使用引导","设备信息","关于 Marco TV","软件更新"},(d,w)->{
            switch(w){case 0:host.health.status();break;case 1:host.backups.show();break;case 2:host.health.diagnostics();break;
                case 3:host.health.guide(0);break;case 4:deviceInfo();break;case 5:about();break;case 6:host.updates.show();break;}
        }).setNegativeButton("返回",null).create());}
    void deviceInfo(){host.showDialog(new TvDialog.Builder(host).setPageId("device_info").setTitle("设备信息")
        .setMessage("品牌："+Build.MANUFACTURER+"\n型号："+Build.MODEL+"\nAndroid："+Build.VERSION.RELEASE+"（API "+Build.VERSION.SDK_INT+"）"
            +"\n固件版本："+Build.DISPLAY+"\n\n请结合固件逐项验证安装、Home 和开机恢复；不显示设备序列号。")
        .setPositiveButton("返回",null).create());}
    void wallpaperSettings(){host.showDialog(new TvDialog.Builder(host).setPageId("wallpaper").setTitle("壁纸")
        .setOrder("wallpaper","builtin","local","reset").setItems(new String[]{"内置壁纸","导入本地壁纸","恢复默认壁纸"},(d,w)->{
            if(w==0)wallpaperGallery();else if(w==1){host.cover.pendingId="__wallpaper__";host.cover.chooseCoverImage();}
            else host.showDialog(new TvDialog.Builder(host).setPageId("wallpaper_reset").setTitle("恢复默认壁纸？")
                .setMessage("恢复内置雾蓝海岸和默认压暗程度，不删除原始图片。")
                .setPositiveButton("恢复",(x,y)->{host.preferences.edit().remove("wallpaper").putInt("wallpaper_builtin",0).remove("wallpaper_dim").apply();host.loadWallpaper();wallpaperSettings();})
                .setNegativeButton("取消",null).create());
        }).setNegativeButton("返回",null).create());}

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
            chosen[0]=value;draft.values.put(key,value);host.home.refreshAppearance();host.home.invalidate();
            if(key.equals("ambient_transition"))host.home.replayPreview();
        });slider.compact();
        TvDialog page=new TvDialog.Builder(host).setPageId("slider:"+key).setTitle(title).setPreview(key).setView(slider)
            .setPositiveButton("保存",(d,w)->host.preferences.edit().putInt(key,chosen[0]).apply())
            .setNegativeButton("取消",null).setInlineButton("默认值",(d,w)->{
                slider.setValue(fallback);
            }).create();
        page.onLeave(()->{if(host.localPreview==draft){host.localPreview=null;host.home.refreshAppearance();host.home.invalidate();}});
        host.showDialog(page);host.localPreview=draft;
    }

    void wallpaperGallery() {
        String[] labels = {"雾蓝海岸", "暖沙丘", "极夜山脊", "峡谷"};
        host.showDialog(new TvDialog.Builder(host).setPageId("wallpaper_gallery").setTitle("内置壁纸").setMessage("四张风景壁纸 · 选择后立即预览")
                .setItems(labels, (d,w) -> {
                    host.preferences.edit().remove("wallpaper").putInt("wallpaper_builtin", w).apply(); host.loadWallpaper();
                }).setNegativeButton("返回", null).create());
    }

    void ambientSettings() {
        boolean enabled=AmbientIntegration.enabled(host);
        host.showDialog(new TvDialog.Builder(host).setPageId("clock").setTitle("屏保开关")
            .setMessage("时间大小、亮度与渐变时长在 Magic Desktop 右侧列表调整。")
            .setItems(new String[]{"Marco TV 屏保："+(enabled?"已开启":"未开启"),"立即预览屏保"},(d,w)->{
                if(w==1){previewAmbient();return;}
                host.preferences.edit().putBoolean("ambient_enabled",!enabled).apply();AmbientIntegration.configure(host);
                if(!enabled)host.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else host.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                ambientSettings();
            }).setNegativeButton("返回",null).create());
    }

    void previewAmbient(){
        host.showDialog(new TvDialog.Builder(host).setPageId("saver_preview").setTitle("屏保预览").setPreview("ambient_transition")
                .setPositiveButton("返回上一级",null)
                .setNeutralButton("重播预览",(d,w)->host.handler.post(this::previewAmbient)).create());
    }

    void deviceSettings(){host.showDialog(new TvDialog.Builder(host).setPageId("device").setTitle("网络与声音")
        .setOrder("device","network","sound").setItems(new String[]{"网络与 Wi-Fi","媒体音量"},(d,w)->{
            if(w==0)host.network.show();else soundSettings();
        }).setNegativeButton("返回",null).create());}

    void soundSettings() {
        android.media.AudioManager audio = (android.media.AudioManager) host.getSystemService(Context.AUDIO_SERVICE);
        int value = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        int max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        ContinuousSlider slider = new ContinuousSlider(host, "媒体音量", "", 0, Math.max(1,max), value, v -> {
            try { audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0); }
            catch (RuntimeException e) { host.toast("系统限制音量调整，请使用遥控器音量键"); }
        });
        host.showDialog(new TvDialog.Builder(host).setPageId("sound").setTitle("媒体音量").setMessage("左右调整媒体音量")
                .setView(slider).setPositiveButton("完成", null).create());
    }

    void tileOptions() {
        if (host.home.row < 0 || host.home.rows.isEmpty()) return;
        Row row = host.home.rows.get(host.home.row);
        if (row.tiles.isEmpty()) return;
        Tile tile = row.tiles.get(row.selected);
        boolean favorite = host.cards.contains("favorites", tile.id);
        String[] options={"移动卡片","重命名","更换封面",favorite?"取消收藏":"加入收藏","单独卡片设置","恢复默认外观",tile.kind==1?"打开输入源":"应用信息"};
        host.showDialog(new TvDialog.Builder(host).setPageId(SettingsCatalog.scope("tile",tile.id)).setTitle(tile.label)
            .setItems(options,(d,w)->{switch(w){
                case 0:host.exitMenus();host.home.beginMove();break;
                case 1:renameCard(tile);break;case 2:coverOptions(tile);break;
                case 3:host.cards.toggle("favorites",tile.id,!favorite);host.loadApplications();host.toast(favorite?"已取消收藏":"已加入收藏");break;
                case 4:cardSettings(tile);break;case 5:host.cards.reset(tile.id);host.loadApplications();break;
                case 6:if(tile.kind==1)host.launch(tile);else{
                    String version="";try{version=host.getPackageManager().getPackageInfo(tile.id.split("/")[0],0).versionName;}catch(Exception ignored){}
                    host.showDialog(new TvDialog.Builder(host).setPageId(SettingsCatalog.scope("app_info",tile.id)).setTitle(tile.label)
                        .setMessage("版本 "+version+"\n"+tile.id).setPositiveButton("打开应用",(x,y)->host.launch(tile)).setNegativeButton("返回",null).create());
                }break;
            }}).setNegativeButton("返回",null).create());
    }
    void cardSettings(Tile tile){
        boolean pinned=host.cards.contains("pinned",tile.id),favorite=host.cards.contains("favorites",tile.id);
        ArrayList<String> options=new ArrayList<>();options.add(pinned?"取消置顶":"置顶卡片");options.add(favorite?"取消收藏":"加入收藏");options.add("隐藏卡片");if(tile.kind==0)options.add("卸载应用");
        host.showDialog(new TvDialog.Builder(host).setPageId(SettingsCatalog.scope("card",tile.id)).setTitle("单独卡片设置 · "+tile.label)
            .setMessage("只影响当前卡片；整行排序请到应用与分类设置。")
            .setItems(options.toArray(new String[0]),(d,w)->{
                if(w<2){host.cards.toggle(w==0?"pinned":"favorites",tile.id,w==0?!pinned:!favorite);host.loadApplications();cardSettings(tile);}
                else if(w==2)host.showDialog(new TvDialog.Builder(host).setPageId("hide_confirm").setTitle("隐藏「"+tile.label+"」？")
                    .setMessage("从桌面与收藏中隐藏，不卸载。可在应用与分类中恢复。")
                    .setPositiveButton("隐藏",(x,y)->{host.cards.toggle("hidden",tile.id,true);host.loadApplications();host.showDialog(new TvDialog.Builder(host)
                        .setPageId("hide_done").setTitle("卡片已隐藏").setMessage("可以立即撤销，也可在应用与分类中恢复。")
                        .setPositiveButton("撤销隐藏",(a,b)->{host.cards.toggle("hidden",tile.id,false);host.focusAfterLoad=tile.id;host.exitMenus();host.loadApplications();})
                        .setNegativeButton("返回桌面",(a,b)->host.exitMenus()).create());}).setNegativeButton("取消",null).create());
                else uninstall(tile);
            }).setNegativeButton("返回",null).create());
    }
    void sorting() {
        String mode=AppOrder.mode(host.preferences);int selected=java.util.Arrays.asList(AppOrder.MODES).indexOf(mode);
        host.showDialog(new TvDialog.Builder(host).setPageId("app_sort").setTitle("TV 应用排序")
                .setMessage("置顶卡片始终优先。上次打开记录从 Marco TV 启动的应用；首字母按中文拼音 / 字母比较。自定义顺序独立保存，切换方式不会清空。")
                .setSingleChoiceItems(AppOrder.LABELS,selected,(d,w)->{
                    if(AppOrder.MODES[w].equals("custom")&&host.preferences.getString("order_tv","").isEmpty()) {
                        for(Row row:host.home.rows)if(row.orderKey.equals("order_tv")) {
                            StringBuilder order=new StringBuilder();for(Tile t:row.tiles){if(order.length()>0)order.append('|');order.append(t.id);}
                            host.preferences.edit().putString("order_tv",order.toString()).apply();
                        }
                    }
                    host.preferences.edit().putString("tv_sort",AppOrder.MODES[w]).apply();host.sortCurrentApps();sorting();
                }).setNegativeButton("返回",null).create());
    }
    private void uninstall(Tile tile) {
        boolean system=false;
        try {system=(host.getPackageManager().getApplicationInfo(tile.id,0).flags&ApplicationInfo.FLAG_SYSTEM)!=0;}catch(Exception ignored){}
        if(system) {
            host.showDialog(new TvDialog.Builder(host).setTitle("系统应用")
                    .setMessage("此应用由电视系统预装，不能当作普通应用卸载。可以隐藏卡片，或查看系统应用信息。")
                    .setPositiveButton("查看系统应用信息",(d,w)->{
                        try{host.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+tile.id)));}
                        catch(RuntimeException error){host.toast("电视未提供应用信息页面");}
                    }).setNegativeButton("返回",null).create());return;
        }
        host.showDialog(new TvDialog.Builder(host).setTitle("卸载「"+tile.label+"」？")
                .setMessage("将打开系统卸载确认页。确认卸载后，应用及其本地数据将被删除。")
                .setPositiveButton("继续卸载",(d,w)->{
                    try {host.startActivity(new Intent(Intent.ACTION_DELETE,Uri.parse("package:"+tile.id)));}
                    catch(RuntimeException error){host.toast("电视未允许卸载此应用");}
                }).setNegativeButton("取消",null).create());
    }

    void renameCard(Tile tile) {
        EditText input = new EditText(host);
        input.setSingleLine(true); input.setText(tile.label); input.selectAll();
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        input.setHint("输入卡片名称"); input.setContentDescription("卡片名称");
        int padding = Math.round(24 * host.getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding / 2, padding, padding / 2);
        host.showDialog(new TvDialog.Builder(host).setPageId(SettingsCatalog.scope("rename",tile.id)).setTitle("重命名卡片").setView(input)
                .setPositiveButton("保存", (d, w) -> { host.cards.setName(tile.id, input.getText().toString()); host.loadApplications(); })
                .setNeutralButton("原始名称", (d, w) -> { host.cards.setName(tile.id, ""); host.loadApplications(); })
                .setNegativeButton("返回", null).create());
    }

    void coverOptions(Tile tile) {
        String[] names = {"原始图标或横幅", "本地图片…", "午夜蓝", "森林绿", "暖琥珀", "雾紫"};
        String[] styles = {"original", "image", "blue", "green", "amber", "purple"};
        host.showDialog(new TvDialog.Builder(host).setPageId(SettingsCatalog.scope("cover",tile.id)).setTitle("更换封面 · " + tile.label)
                .setItems(names, (d, which) -> {
                    if (which == 1) { host.cover.pendingId = tile.id; host.handler.post(host.cover::chooseCoverImage); }
                    else { host.cards.cover(tile.id, styles[which]); host.loadApplications(); }
                }).setNegativeButton("返回", null).create());
    }

    void hiddenCards() {
        ArrayList<Tile> hidden = new ArrayList<>();
        for (Tile tile : host.allTiles) if (host.cards.contains("hidden", tile.id)) hidden.add(tile);
        if (hidden.isEmpty()) { host.toast("没有隐藏的卡片"); return; }
        String[] labels = new String[hidden.size()];
        for (int i = 0; i < hidden.size(); i++) labels[i] = hidden.get(i).label;
        host.showDialog(new TvDialog.Builder(host).setPageId("hidden_cards").setTitle("恢复隐藏的桌面卡片").setItems(labels, (d, which) -> {
            host.cards.toggle("hidden", hidden.get(which).id, false); host.loadApplications();
        }).setNeutralButton("全部恢复", (d, w) -> {
            for (String id : host.cards.set("hidden")) host.cards.toggle("hidden", id, false); host.loadApplications();
        }).setNegativeButton("返回", null).create());
    }

    void appearanceSettings(){personalization("cards");}

    void systemPanel(){
        final int epoch=host.menuEpoch;
        SystemIntegration.show(host,d->{if(epoch==host.menuEpoch)host.showDialog(d);});
    }
}
