// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import java.util.*;

/** Stable settings identities and deterministic migration; independent of labels and Android. */
final class SettingsCatalog {
    static final String[] ROOT_IDS={"appearance","apps","recents","playback","device","startup","maintenance","phone"};
    static final String[] ROOT_LABELS={"桌面与外观","应用与分类","最近任务","正在播放","网络与声音","系统与启动","维护与帮助","Marco 互联"};
    static final Set<String> ORDER_KEYS=Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "main","main_v2","clock","device","startup","cards","appearance","apps","wallpaper",
            "maintenance","recents","task_style","playback","diagnostics","health","backup","updates")));
    static final Map<String,int[]> INT_SPECS;
    static final Set<String> HOME_KEYS;
    static {
        Map<String,int[]> specs=new LinkedHashMap<>();
        specs.put("card_row_position",new int[]{0,100,75});
        specs.put("card_height",new int[]{70,130,100});
        specs.put("card_radius",new int[]{0,90,32});
        specs.put("focus_scale",new int[]{0,30,16});
        specs.put("label_opacity",new int[]{0,100,0});
        specs.put("card_surface",new int[]{0,100,55});
        specs.put("motion_duration",new int[]{120,500,260});
        specs.put("clock_style",new int[]{0,3,0});
        specs.put("clock_tilt",new int[]{0,14,10});
        specs.put("clock_size",new int[]{36,160,96});
        specs.put("idle_seconds",new int[]{0,600,30});
        specs.put("ambient_clock_size",new int[]{100,280,176});
        specs.put("ambient_clock_brightness",new int[]{40,100,100});
        specs.put("ambient_dim",new int[]{0,100,72});
        specs.put("ambient_transition",new int[]{1,30,3});
        specs.put("ambient_sleep_minutes",new int[]{0,240,60});
        specs.put("wallpaper_dim",new int[]{0,100,10});
        specs.put("wallpaper_builtin",new int[]{0,3,0});
        specs.put("recent_size",new int[]{60,120,100});
        specs.put("recent_spacing",new int[]{80,360,210});
        specs.put("recent_tilt",new int[]{0,18,7});
        specs.put("recent_radius",new int[]{8,80,36});
        specs.put("recent_dim",new int[]{0,90,65});
        specs.put("recent_duration",new int[]{120,700,260});
        specs.put("recent_limit",new int[]{3,20,10});
        specs.put("recent_double_ms",new int[]{200,700,400});
        INT_SPECS=Collections.unmodifiableMap(specs);
        Set<String> home=new LinkedHashSet<>(specs.keySet());
        home.addAll(Arrays.asList("ambient_enabled","now_playing","tv_sort","order_tv","order_inputs","order_favorites","order_other"));
        HOME_KEYS=Collections.unmodifiableSet(home);
    }
    static int[] range(String key){
        int[] spec=INT_SPECS.get(key);
        return spec!=null?new int[]{spec[0],spec[1]}:new int[]{0,100};
    }
    static int defaultValue(String key,int fallback){
        int[] spec=INT_SPECS.get(key);
        return spec!=null?spec[2]:fallback;
    }
    static boolean allowed(String group,String key){
        if(group==null||key==null)return false;
        if(group.equals("home"))return HOME_KEYS.contains(key)||(key.startsWith("opened:")&&key.length()>7&&key.length()<=507);
        if(group.equals("cards"))return Arrays.asList("favorites","hidden","pinned","manual_apps").contains(key)||key.matches("(name|cover|manual):.{1,500}");
        return group.equals("menu_order")&&ORDER_KEYS.contains(key);
    }
    static String expectedType(String group,String key){
        if(group.equals("menu_order"))return "s";
        if(group.equals("cards"))return key.contains(":")?"s":"set";
        if(key.startsWith("opened:"))return "l";
        if(key.equals("ambient_enabled")||key.equals("now_playing"))return "b";
        if(key.startsWith("order_")||key.equals("tv_sort"))return "s";
        return "i";
    }
    static boolean imageName(String name){
        return name!=null&&(name.equals("wallpaper-image")||name.matches("cover-[a-f0-9]{64}\\.jpg"));
    }
    static String remap(String saved,String[] from,String[] to){
        if(from.length!=to.length)throw new IllegalArgumentException("migration mapping");
        LinkedHashSet<String> result=new LinkedHashSet<>();
        for(String id:saved.split("\\|"))for(int i=0;i<from.length;i++)if(id.equals(from[i]))result.add(to[i]);
        StringBuilder out=new StringBuilder();
        for(String id:result){
            if(out.length()>0)out.append('|');
            out.append(id);
        }
        return out.toString();
    }
    static String migrateRoot(String saved){
        return remap(saved,new String[]{"clock","wallpapers","local_wallpaper","cards","device","about","startup","hidden","backup","health",
                "diagnostics","recents"},
                new String[]{"appearance","appearance","appearance","appearance","device","maintenance","startup","apps","maintenance","maintenance",
                "maintenance","recents"});
    }
    static String scope(String page,String subject){
        return page+":"+subject;
    }
    static Map<String,String> migration(Map<String,?> current){
        Map<String,String> updates=new LinkedHashMap<>();
        if(current.containsKey("main_v2"))return updates;
        Object value=current.get("main");
        String old=value instanceof String?(String)value:"";
        updates.put("main_v2",migrateRoot(old));
        if(!current.containsKey("appearance"))updates.put("appearance",remap(old,
                new String[]{"clock","wallpapers","local_wallpaper","cards"},new String[]{"clock","wallpaper","wallpaper","cards"}));
        if(!current.containsKey("maintenance"))updates.put("maintenance",remap(old,
                new String[]{"health","backup","diagnostics","about"},new String[]{"health","backup","diagnostics","about"}));
        return updates;
    }
}
