// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;
import java.text.Collator;
import java.util.*;

/** Launchable application grid, independent of desktop visibility and add-to-home. */
final class AllAppsDrawer {
    final MainActivity host;
    int generation;
    AllAppsDrawer(MainActivity h){
        host=h;
    }
    static final class Entry {
        String label,id;
        Intent intent;
        Bitmap icon;
    }
    int dp(int n){
        return Math.round(n*host.getResources().getDisplayMetrics().density);
    }
    void show(){
        final int request=++generation;
        host.showDialog(new TvDialog.Builder(host).setPageId("all_apps").centered().setTitle("全部应用").setMessage("正在读取应用…")
                .setNegativeButton("返回",null).create());
        final int epoch=host.menuEpoch;
        host.loader.execute(()->{
            ArrayList<Entry> entries=new ArrayList<>();
            HashSet<String> seen=new HashSet<>();
            PackageManager pm=host.getPackageManager();
            try{
                for(String category:new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER,Intent.CATEGORY_LAUNCHER})for(ResolveInfo info:pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                        .addCategory(category),0)){
                    ActivityInfo a=info.activityInfo;
                    if(a==null||!AppEntries.launchable(host,a)||a.packageName.equals(host.getPackageName())||!seen.add(a.packageName))continue;
                    Entry e=new Entry();
                    e.id=a.packageName;
                    e.label=String.valueOf(info.loadLabel(pm));
                    e.intent=new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(a.packageName,a.name))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    entries.add(e);
                }
                Collator order=Collator.getInstance(Locale.CHINA);
                Collections.sort(entries,(a,b)->order.compare(a.label,b.label));
                for(int i=0;i<entries.size();i++){
                    if(request!=generation||epoch!=host.menuEpoch||host.isDestroyed())break;
                    try{
                        Entry e=entries.get(i);
                        Drawable icon=pm.getApplicationIcon(e.id);
                        e.icon=Bitmap.createBitmap(56,56,Bitmap.Config.ARGB_8888);
                        int w=Math.max(1,icon.getIntrinsicWidth()),h=Math.max(1,icon.getIntrinsicHeight());
                        float scale=48f/Math.max(w,h);
                        icon.setBounds((int)(28-w*scale/2),(int)(28-h*scale/2),(int)(28+w*scale/2),(int)(28+h*scale/2));
                        icon.draw(new Canvas(e.icon));
                    }catch(Exception ignored){}
                }
            }catch(RuntimeException failure){
                recycle(entries);
                host.handler.post(()->{
                    if(request==generation&&epoch==host.menuEpoch&&host.isPage("all_apps"))host.showDialog(new TvDialog.Builder(host)
                            .setPageId("all_apps").centered().setTitle("全部应用").setMessage("读取失败，请返回后重新打开。")
                            .setNegativeButton("返回",null).create());
                });
                return;
            }
            host.handler.post(()->{
                if(request!=generation||host.isDestroyed()||epoch!=host.menuEpoch||!host.isPage("all_apps")){
                    recycle(entries);
                    return;
                }
                present(entries);
            });
        });
    }
    private static void recycle(List<Entry> entries){
        for(Entry e:entries)if(e.icon!=null&&!e.icon.isRecycled()){
            e.icon.recycle();
            e.icon=null;
        }
    }
    void present(ArrayList<Entry> entries){
        GridLayout grid=new GridLayout(host);
        grid.setColumnCount(5);
        grid.setOrientation(GridLayout.HORIZONTAL);
        grid.setFocusable(false);
        int[] ids=new int[entries.size()];
        for(int i=0;i<ids.length;i++)ids[i]=View.generateViewId();
        for(int i=0;i<entries.size();i++){
            Entry e=entries.get(i);
            TextView item=new TextView(host);
            item.setId(ids[i]);
            item.setTag(e.id);
            item.setText(e.label);
            item.setGravity(Gravity.CENTER);
            item.setTextColor(0xFFE5EDF4);
            item.setTextSize(13);
            item.setIncludeFontPadding(false);
            item.setLineSpacing(dp(1),1f);
            item.setMaxLines(2);
            item.setEllipsize(android.text.TextUtils.TruncateAt.END);
            item.setPadding(dp(6),dp(8),dp(6),dp(6));
            item.setFocusable(true);
            item.setClickable(true);
            item.setContentDescription(e.label+"，确定打开，长按添加到桌面");
            GradientDrawable bg=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0xFF304657,0xFF202D3C});
            bg.setCornerRadius(dp(14));
            item.setBackground(bg);
            if(e.icon!=null){
                Drawable icon=new BitmapDrawable(host.getResources(),e.icon);
                icon.setBounds(0,0,dp(42),dp(42));
                item.setCompoundDrawables(null,icon,null,null);
                item.setCompoundDrawablePadding(dp(6));
            }
            GridLayout.LayoutParams lp=new GridLayout.LayoutParams();
            lp.width=0;
            lp.height=dp(110);
            lp.columnSpec=GridLayout.spec(i%5,1f);
            lp.rowSpec=GridLayout.spec(i/5);
            lp.setMargins(dp(4),dp(4),dp(4),dp(4));
            grid.addView(item,lp);
            if(i%5>0)item.setNextFocusLeftId(ids[i-1]);
            if(i%5<4&&i+1<ids.length)item.setNextFocusRightId(ids[i+1]);
            if(i>=5)item.setNextFocusUpId(ids[i-5]);
            if(i+5<ids.length)item.setNextFocusDownId(ids[i+5]);
            item.setOnFocusChangeListener((v,focus)->{
                bg.setStroke(dp(2),focus?0xFFC4E9F0:Color.TRANSPARENT);
                v.animate().scaleX(focus?1.04f:1).scaleY(focus?1.04f:1).setDuration(140).start();
                if(focus)v.requestRectangleOnScreen(new Rect(0,0,v.getWidth(),v.getHeight()));
            });
            item.setOnClickListener(v->{
                Tile tile=new Tile();
                tile.id=e.id;
                tile.label=e.label;
                tile.intent=e.intent;
                host.launch(tile);
            });
            item.setOnLongClickListener(v->{
                try{
                    host.library.choose(host.getPackageManager().getApplicationInfo(e.id,0));
                }catch(Exception ignored){
                    host.toast("应用已发生变化，请重新打开列表");
                }
                return true;
            });
        }
        TvDialog dialog=new TvDialog.Builder(host).setPageId("all_apps").centered().setTitle("全部应用 · "+entries.size())
                .setMessage(entries.isEmpty()?"没有找到可启动应用。可到添加应用查找其他入口。":"方向键选择 · 确定打开 · 长按添加到桌面。包含已隐藏的应用，仅显示可启动入口。")
                .setView(grid).setNeutralButton("查找其他入口",(d,w)->host.library.show()).setNegativeButton("返回",null)
                .create();
        dialog.onLeave(()->{
            if(!host.dialogs.hasPage("all_apps"))recycle(entries);
        });
        host.showDialog(dialog);
    }
}
