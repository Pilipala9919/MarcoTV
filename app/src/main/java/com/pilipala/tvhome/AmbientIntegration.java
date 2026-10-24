// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.*;
import android.provider.Settings;
import android.util.Log;

/** Reversible Android screensaver selection. Foreground idle is owned by our window. */
final class AmbientIntegration {
    static boolean enabled(Context c){return c.getSharedPreferences("home",0).getBoolean("ambient_enabled",true);}
    static void configure(Context c){
        if(!SystemIntegration.authorized(c))return;
        SharedPreferences p=SystemIntegration.prefs(c);
        try{
            if(enabled(c)){
                if(!p.contains("saved_dream"))p.edit()
                        .putString("saved_dream",read(c,"screensaver_components"))
                        .putString("saved_dream_enabled",read(c,"screensaver_enabled")).commit();
                Settings.Secure.putString(c.getContentResolver(),"screensaver_components",c.getPackageName()+"/.MarcoDreamService");
                Settings.Secure.putInt(c.getContentResolver(),"screensaver_enabled",1);
            }else if(p.contains("saved_dream")){
                Settings.Secure.putString(c.getContentResolver(),"screensaver_components",nullable(p.getString("saved_dream","")));
                Settings.Secure.putString(c.getContentResolver(),"screensaver_enabled",nullable(p.getString("saved_dream_enabled","")));
            }
        }catch(RuntimeException e){Log.w("MarcoTVSystem","Dream configuration unavailable",e);}
    }
    private static String read(Context c,String key){String s=Settings.Secure.getString(c.getContentResolver(),key);return s==null?"":s;}
    private static String nullable(String s){return s.isEmpty()?null:s;}
}
