// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.*;
import android.content.pm.*;
import android.text.InputType;
import android.widget.EditText;
import java.text.Collator;
import java.util.*;

/** User-selected exported activities supplement launcher discovery. */
final class AppLibrary {
    private final MainActivity host;
    AppLibrary(MainActivity host) { this.host=host; }
    static boolean launchable(Context context,ActivityInfo info) {
        return info.exported && info.enabled && info.applicationInfo.enabled
                && (info.permission==null || context.checkSelfPermission(info.permission)==PackageManager.PERMISSION_GRANTED);
    }
    void show() { show(""); }
    void show(String query) {
        final int epoch=host.menuEpoch;host.toast("正在读取已安装应用…");
        host.loader.execute(()->{
            ArrayList<ApplicationInfo> apps=new ArrayList<>();PackageManager pm=host.getPackageManager();
            String search=query.trim().toLowerCase(Locale.ROOT);
            for(ApplicationInfo info:pm.getInstalledApplications(0)) {
                if(info.packageName.equals(host.getPackageName()))continue;
                String label=String.valueOf(info.loadLabel(pm));
                if(search.isEmpty()||label.toLowerCase(Locale.ROOT).contains(search)||info.packageName.toLowerCase(Locale.ROOT).contains(search))apps.add(info);
            }
            Collator collator=Collator.getInstance(Locale.CHINA);
            Collections.sort(apps,(a,b)->collator.compare(String.valueOf(a.loadLabel(pm)),String.valueOf(b.loadLabel(pm))));
            String[] labels=new String[apps.size()];for(int i=0;i<labels.length;i++)labels[i]=apps.get(i).loadLabel(pm)+"\n"+apps.get(i).packageName;
            host.handler.post(()->{
                if(host.isDestroyed()||epoch!=host.menuEpoch)return;
                host.showDialog(new TvDialog.Builder(host).setTitle("添加应用")
                        .setMessage(search.isEmpty()?"选择应用加入 TV 应用。包含隐藏应用及未识别为电视应用的软件。":"搜索结果："+query)
                        .setItems(labels,(d,which)->choose(apps.get(which)))
                        .setNeutralButton("搜索名称或包名",(d,w)->search())
                        .setNegativeButton("返回",null).create());
            });
        });
    }
    private void search() {
        EditText input=new EditText(host);input.setSingleLine(true);input.setHint("应用名称或包名");input.setInputType(InputType.TYPE_CLASS_TEXT);
        host.showDialog(new TvDialog.Builder(host).setTitle("搜索应用").setView(input)
                .setPositiveButton("搜索",(d,w)->show(input.getText().toString())).setNegativeButton("返回",null).create());
    }
    private void choose(ApplicationInfo app) {
        PackageManager pm=host.getPackageManager();ArrayList<ActivityInfo> entries=new ArrayList<>();
        try {
            PackageInfo pkg=pm.getPackageInfo(app.packageName,PackageManager.GET_ACTIVITIES);
            if(pkg.activities!=null)for(ActivityInfo info:pkg.activities)if(launchable(host,info))entries.add(info);
        } catch(PackageManager.NameNotFoundException ignored) { }
        Intent normal=pm.getLeanbackLaunchIntentForPackage(app.packageName);
        if(normal==null)normal=pm.getLaunchIntentForPackage(app.packageName);
        final ComponentName defaultEntry=normal==null?null:normal.getComponent();
        if(defaultEntry!=null)Collections.sort(entries,(a,b)->Boolean.compare(!a.name.equals(defaultEntry.getClassName()),!b.name.equals(defaultEntry.getClassName())));
        String[] labels=new String[entries.size()];
        for(int i=0;i<labels.length;i++) {ActivityInfo info=entries.get(i);labels[i]=(defaultEntry!=null&&info.name.equals(defaultEntry.getClassName())?"默认打开入口":"可打开页面")+" · "+info.loadLabel(pm)+"\n"+info.name;}
        host.showDialog(new TvDialog.Builder(host).setTitle("添加 · "+app.loadLabel(pm))
                .setMessage(entries.isEmpty()?"此应用没有允许外部打开的页面，无法创建有效卡片。系统服务通常没有桌面入口。":"选择打开入口。通常选择默认入口；系统工具的其他页面可能仍被电视限制。")
                .setItems(labels,(d,index)->{
                    ActivityInfo info=entries.get(index);ComponentName component=new ComponentName(info.packageName,info.name);
                    host.cards.manual(app.packageName,component.flattenToString());host.cards.toggle("hidden",app.packageName,false);
                    host.loadApplications();host.toast("已加入 TV 应用");
                }).setNegativeButton("返回",null).create());
    }
}
