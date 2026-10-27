// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/** Idempotent upgrade, also applied after restoring an older backup. Never changes app settings. */
final class MenuMigration {
    static void apply(Context context){
        SharedPreferences p=context.getSharedPreferences("menu_order",0);
        java.util.Map<String,String> updates=SettingsCatalog.migration(p.getAll());
        if(updates.isEmpty())return;
        SharedPreferences.Editor e=p.edit();
        for(java.util.Map.Entry<String,String> entry:updates.entrySet())e.putString(entry.getKey(),entry.getValue());
        if(!e.commit())throw new IllegalStateException("menu migration failed");
    }
}
