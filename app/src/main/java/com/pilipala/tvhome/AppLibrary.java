// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.*;
import android.content.pm.*;
import android.graphics.drawable.Drawable;
import android.text.InputType;
import android.widget.EditText;
import java.text.Collator;
import java.util.*;

final class AppLibrary {
    private final MainActivity host;
    AppLibrary(MainActivity host){this.host=host;}
    static boolean launchable(Context context,ActivityInfo info){return AppEntries.launchable(context,info);}
    void show(){show("",false);}
    void show(String query){show(query,false);}
    private Intent normal(PackageManager pm,String id){Intent i=pm.getLeanbackLaunchIntentForPackage(id);return i==null?pm.getLaunchIntentForPackage(id):i;}
    void show(String query,boolean advanced){
        final int epoch=host.menuEpoch;host.toast("正在读取已安装应用…");
        host.loader.execute(()->{
            try{
                PackageManager pm=host.getPackageManager();ArrayList<ApplicationInfo> apps=new ArrayList<>();Map<String,String> names=new HashMap<>();
                String search=query.trim().toLowerCase(Locale.ROOT);
                for(ApplicationInfo info:pm.getInstalledApplications(0)){
                    if(info.packageName.equals(host.getPackageName()))continue;String label=String.valueOf(info.loadLabel(pm));names.put(info.packageName,label);
                    boolean ordinary=(info.flags&ApplicationInfo.FLAG_SYSTEM)==0||normal(pm,info.packageName)!=null;
                    if(!advanced&&!ordinary)continue;
                    if(search.isEmpty()||label.toLowerCase(Locale.ROOT).contains(search)||info.packageName.toLowerCase(Locale.ROOT).contains(search))apps.add(info);
                }
                Collator alphabet=Collator.getInstance(Locale.CHINA);
                Collections.sort(apps,(a,b)->{int recent=Long.compare(host.preferences.getLong("opened:"+b.packageName,0),host.preferences.getLong("opened:"+a.packageName,0));return recent!=0?recent:alphabet.compare(names.get(a.packageName),names.get(b.packageName));});
                String[] labels=new String[apps.size()+2];Drawable[] icons=new Drawable[labels.length];
                labels[0]="搜索名称或包名";labels[1]=advanced?"返回常用应用列表":"高级：系统组件与其他入口";
                for(int i=0;i<apps.size();i++){ApplicationInfo info=apps.get(i);labels[i+2]=names.get(info.packageName)+(advanced?"\n"+info.packageName:"");
                    try{Drawable icon=info.loadIcon(pm);android.graphics.Bitmap small=android.graphics.Bitmap.createBitmap(64,64,android.graphics.Bitmap.Config.ARGB_8888);icon.setBounds(0,0,64,64);icon.draw(new android.graphics.Canvas(small));icons[i+2]=new android.graphics.drawable.BitmapDrawable(host.getResources(),small);}catch(RuntimeException ignored){}
                }
                host.handler.post(()->{if(host.isDestroyed()||epoch!=host.menuEpoch)return;
                    host.showDialog(new TvDialog.Builder(host).setTitle(advanced?"高级应用入口":"添加应用")
                        .setMessage(query.isEmpty()?(advanced?"系统组件可能没有可用页面；只列出允许外部打开的入口。":"选择应用加入 TV 应用，最近从桌面打开的优先。未找到时可进入高级列表。"):"搜索结果："+query)
                        .setItems(labels,(d,w)->{if(w==0)search(query,advanced);else if(w==1)show(query,!advanced);else choose(apps.get(w-2));}).setIcons(icons).setNegativeButton("返回",null).create());
                });
            }catch(RuntimeException error){Diagnostics.event("APP_LIBRARY_FAILED");host.handler.post(()->host.toast("读取应用失败，请重试"));}
        });
    }
    private void search(String query,boolean advanced){EditText input=new EditText(host);input.setSingleLine(true);input.setHint("应用名称或包名");input.setInputType(InputType.TYPE_CLASS_TEXT);input.setText(query);input.selectAll();
        host.showDialog(new TvDialog.Builder(host).setTitle("搜索应用").setView(input).setPositiveButton("搜索",(d,w)->show(input.getText().toString(),advanced)).setNegativeButton("返回",null).create());}
    private void choose(ApplicationInfo app){
        final int epoch=host.menuEpoch;host.toast("正在检查启动入口…");
        host.loader.execute(()->{AppEntries.Scan scan=AppEntries.scan(host,app.packageName);host.handler.post(()->{
            if(host.isDestroyed()||epoch!=host.menuEpoch)return;
            if(scan.primary.isEmpty()){entries(app,scan);return;}
            ActivityInfo first=scan.primary.values().iterator().next();ComponentName entry=new ComponentName(first.packageName,first.name);
            host.showDialog(new TvDialog.Builder(host).setPageId("add:"+app.packageName).setTitle("添加 · "+app.loadLabel(host.getPackageManager()))
                .setMessage("已找到系统启动入口。也可选择其他公开页面；实际能否打开以电视结果为准。")
                .setItems(new String[]{"添加到 TV 应用","选择其他公开页面","查看入口检查信息"},(d,w)->{
                    if(w==0)add(entry);else if(w==1)entries(app,scan);else entryReport(app,scan);
                }).setNegativeButton("返回",null).create());
        });});
    }
    private void entries(ApplicationInfo app,AppEntries.Scan scan){ArrayList<ActivityInfo> list=new ArrayList<>(scan.all.values());PackageManager pm=host.getPackageManager();
        String[] labels=new String[list.size()];for(int i=0;i<labels.length;i++)labels[i]=list.get(i).loadLabel(pm)+"\n"+list.get(i).name;
        host.showDialog(new TvDialog.Builder(host).setPageId("entries:"+app.packageName).setTitle("公开页面 · "+app.loadLabel(pm)).setMessage(list.isEmpty()?scan.explanation():"这些是查询到的公开页面，部分页面仍可能被厂商限制。")
            .setItems(labels,(d,w)->add(new ComponentName(list.get(w).packageName,list.get(w).name)))
            .setNeutralButton("入口检查信息",(d,w)->entryReport(app,scan)).setNegativeButton("返回",null).create());
    }
    private void entryReport(ApplicationInfo app,AppEntries.Scan scan){String version="未知";try{version=host.getPackageManager().getPackageInfo(app.packageName,0).versionName;}catch(Exception ignored){}
        host.showDialog(new TvDialog.Builder(host).setPageId("entry_report:"+app.packageName).setTitle("应用入口检查")
            .setMessage("Marco TV "+ProjectInfo.VERSION+"\nAndroid API "+android.os.Build.VERSION.SDK_INT+"\n所选应用："+app.packageName+"\n应用版本："+version
                +"\n系统启动入口："+scan.primary.size()+"\n可用公开页面："+scan.all.size()+"\n查询失败："+scan.queryFailures
                +"\n受权限限制："+scan.denied+"\n停用入口："+scan.disabled+"\n\n仅在本页显示所选应用的信息，不自动上传。限制计数可能包含不同查询中的同一入口。")
            .setPositiveButton("返回",null).create());}
    private void add(ComponentName component){try{
        ActivityInfo info=host.getPackageManager().getActivityInfo(component,PackageManager.MATCH_DISABLED_COMPONENTS);if(!launchable(host,info)){host.toast("此入口被停用或需要额外权限");return;}
        String id=component.getPackageName();host.cards.manual(id,component.flattenToString());host.cards.toggle("hidden",id,false);
        host.focusAfterLoad=id;host.exitMenus();host.loadApplications();host.toast("已加入 TV 应用");Diagnostics.event("APP_MANUALLY_ADDED");
    }catch(Exception error){Diagnostics.event("APP_ADD_FAILED");host.toast("应用已变化，请重新选择");}}
}
