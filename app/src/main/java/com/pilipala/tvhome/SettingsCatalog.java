// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import java.util.*;

/** Stable settings identities and deterministic migration; independent of labels and Android. */
final class SettingsCatalog {
    static final String[] ROOT_IDS={"appearance","apps","recents","playback","device","startup","maintenance","phone"};
    static final String[] ROOT_LABELS={"桌面与外观","应用与分类","最近任务","正在播放","网络与声音","系统与启动","维护与帮助","手机管理"};
    static final Set<String> ORDER_KEYS=Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "main","main_v2","clock","device","startup","cards","appearance","apps","wallpaper",
        "maintenance","recents","task_style","playback","diagnostics","health","backup","updates")));
    static String remap(String saved,String[] from,String[] to){
        if(from.length!=to.length)throw new IllegalArgumentException("migration mapping");
        LinkedHashSet<String> result=new LinkedHashSet<>();
        for(String id:saved.split("\\|"))for(int i=0;i<from.length;i++)if(id.equals(from[i]))result.add(to[i]);
        StringBuilder out=new StringBuilder();for(String id:result){if(out.length()>0)out.append('|');out.append(id);}return out.toString();
    }
    static String migrateRoot(String saved){
        return remap(saved,new String[]{"clock","wallpapers","local_wallpaper","cards","device","about","startup","hidden","backup","health","diagnostics","recents"},
            new String[]{"appearance","appearance","appearance","appearance","device","maintenance","startup","apps","maintenance","maintenance","maintenance","recents"});
    }
    static String scope(String page,String subject){return page+":"+subject;}
    static Map<String,String> migration(Map<String,?> current){
        Map<String,String> updates=new LinkedHashMap<>();
        if(current.containsKey("main_v2"))return updates;
        Object value=current.get("main");String old=value instanceof String?(String)value:"";
        updates.put("main_v2",migrateRoot(old));
        if(!current.containsKey("appearance"))updates.put("appearance",remap(old,
            new String[]{"clock","wallpapers","local_wallpaper","cards"},new String[]{"clock","wallpaper","wallpaper","cards"}));
        if(!current.containsKey("maintenance"))updates.put("maintenance",remap(old,
            new String[]{"health","backup","diagnostics","about"},new String[]{"health","backup","diagnostics","about"}));
        return updates;
    }
}
