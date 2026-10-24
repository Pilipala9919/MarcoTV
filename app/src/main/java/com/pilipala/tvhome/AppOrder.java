// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.SharedPreferences;
import java.text.Collator;
import java.util.*;

final class AppOrder {
    static final String[] MODES = {"recent", "alpha", "custom"};
    static final String[] LABELS = {"上次打开", "首字母排序", "纯自定义"};
    static String mode(SharedPreferences prefs) { return prefs.getString("tv_sort", "recent"); }
    static void opened(SharedPreferences prefs, String id) {
        long stamp=Math.max(System.currentTimeMillis(),prefs.getLong("last_open_counter",0)+1);
        prefs.edit().putLong("opened:"+id,stamp).putLong("last_open_counter",stamp).apply();
    }
    static void sort(List<Tile> tiles, SharedPreferences prefs, CardStore cards) {
        String mode=mode(prefs);
        List<String> custom=Arrays.asList(prefs.getString("order_tv","").split("\\|"));
        Collator alphabet=Collator.getInstance(Locale.CHINA); alphabet.setStrength(Collator.PRIMARY);
        Map<String,Long> times=new HashMap<>(); Set<String> pinned=cards.set("pinned");
        for(Tile tile:tiles) { tile.pinned=pinned.contains(tile.id); times.put(tile.id,prefs.getLong("opened:"+tile.id,0)); }
        Collections.sort(tiles,(a,b)->{
            if(a.pinned!=b.pinned)return a.pinned?-1:1;
            if(mode.equals("recent")) {
                int recent=Long.compare(times.get(b.id),times.get(a.id)); if(recent!=0)return recent;
            } else if(mode.equals("custom")) {
                int ai=custom.indexOf(a.id),bi=custom.indexOf(b.id);
                if(ai<0)ai=Integer.MAX_VALUE;if(bi<0)bi=Integer.MAX_VALUE;
                if(ai!=bi)return Integer.compare(ai,bi);
            }
            int name=alphabet.compare(a.label,b.label);return name!=0?name:a.id.compareTo(b.id);
        });
    }
}
