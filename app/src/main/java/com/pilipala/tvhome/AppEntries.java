// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.*;
import android.content.pm.*;
import android.os.Process;
import java.util.*;

/** Merge public launcher routes before enumerating other exported activities. Never bypass permissions. */
final class AppEntries {
    static boolean enabled(boolean declared,int override){return override==PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        ||(override==PackageManager.COMPONENT_ENABLED_STATE_DEFAULT&&declared);}
    static boolean launchable(Context c,ActivityInfo info){
        if(info==null||info.applicationInfo==null||!info.exported)return false;
        PackageManager pm=c.getPackageManager();boolean activity=info.enabled,app=info.applicationInfo.enabled;
        try{activity=enabled(activity,pm.getComponentEnabledSetting(new ComponentName(info.packageName,info.name)));}catch(RuntimeException ignored){}
        try{app=enabled(app,pm.getApplicationEnabledSetting(info.packageName));}catch(RuntimeException ignored){}
        return activity&&app&&(info.permission==null||info.permission.isEmpty()||c.checkSelfPermission(info.permission)==PackageManager.PERMISSION_GRANTED);
    }
    static final class Scan {
        final LinkedHashMap<String,ActivityInfo> primary=new LinkedHashMap<>(),all=new LinkedHashMap<>();
        int denied,disabled,privatePages,queryFailures;
        void put(Context c,ActivityInfo info,boolean launcher){
            if(info==null)return;String key=new ComponentName(info.packageName,info.name).flattenToString();
            if(!info.exported){privatePages++;return;}
            if(!launchable(c,info)){if(info.permission!=null&&!info.permission.isEmpty()&&c.checkSelfPermission(info.permission)!=PackageManager.PERMISSION_GRANTED)denied++;else disabled++;return;}
            all.put(key,info);if(launcher)primary.put(key,info);
        }
        String explanation(){return "Marco TV 暂未查到可用入口，不代表此应用一定无法打开。\n"
            +"查询失败 "+queryFailures+" 次 · 受权限限制 "+denied+" 项 · 停用入口 "+disabled+" 项。\n\n"
            +"请先从电视原厂桌面尝试打开。若原厂桌面可打开，可反馈应用版本及下面的入口检查信息；厂商专用入口仍需实机核对。";}
    }
    private static void fromIntent(Context c,Scan s,Intent intent){
        if(intent==null)return;PackageManager pm=c.getPackageManager();
        try{if(intent.getComponent()!=null)s.put(c,pm.getActivityInfo(intent.getComponent(),PackageManager.MATCH_DISABLED_COMPONENTS),true);
            else for(ResolveInfo r:pm.queryIntentActivities(intent,0))s.put(c,r.activityInfo,true);
        }catch(Exception e){s.queryFailures++;}
    }
    static Scan scan(Context c,String id){
        PackageManager pm=c.getPackageManager();Scan s=new Scan();
        try{fromIntent(c,s,pm.getLeanbackLaunchIntentForPackage(id));}catch(RuntimeException e){s.queryFailures++;}
        try{fromIntent(c,s,pm.getLaunchIntentForPackage(id));}catch(RuntimeException e){s.queryFailures++;}
        for(String category:new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER,Intent.CATEGORY_LAUNCHER,Intent.CATEGORY_INFO}){
            try{for(ResolveInfo r:pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(id),0))s.put(c,r.activityInfo,true);}
            catch(RuntimeException e){s.queryFailures++;}
        }
        try{LauncherApps launcher=(LauncherApps)c.getSystemService(Context.LAUNCHER_APPS_SERVICE);
            if(launcher!=null)for(LauncherActivityInfo info:launcher.getActivityList(id,Process.myUserHandle()))
                s.put(c,pm.getActivityInfo(info.getComponentName(),PackageManager.MATCH_DISABLED_COMPONENTS),true);
        }catch(Exception e){s.queryFailures++;}
        try{for(ResolveInfo r:pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).setPackage(id),0))s.put(c,r.activityInfo,false);}
        catch(RuntimeException e){s.queryFailures++;}
        try{PackageInfo pkg=pm.getPackageInfo(id,PackageManager.GET_ACTIVITIES|PackageManager.MATCH_DISABLED_COMPONENTS);
            if(pkg.activities!=null)for(ActivityInfo info:pkg.activities)s.put(c,info,false);
        }catch(Exception e){s.queryFailures++;}
        return s;
    }
}
