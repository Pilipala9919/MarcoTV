// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import java.util.*;

/** Exercises the actual upgrade policy and ordering resolver without Android or user data. */
public final class SettingsMigrationChecks {
    static int passed;
    static void check(String name,boolean ok){if(!ok)throw new AssertionError(name);passed++;System.out.println("PASS "+name);}
    static String root(String saved){return MenuOrder.encode(MenuOrder.resolve(saved,SettingsCatalog.ROOT_IDS),SettingsCatalog.ROOT_IDS);}
    public static void main(String[] args){
        Map<String,Object> old=new HashMap<>();
        check("fresh_install_uses_new_default",root(SettingsCatalog.migration(old).get("main_v2")).equals("appearance|apps|recents|playback|device|startup|maintenance"));
        old.put("main","diagnostics|backup|hidden|device|clock|wallpapers|local_wallpaper|cards|startup|recents|about");
        old.put("clock","dim|normal_size|wait");old.put("cards","labels|radius|zoom");old.put("untouched","value");
        Map<String,Object> before=new HashMap<>(old);Map<String,String> update=SettingsCatalog.migration(old);
        check("migration_does_not_mutate_input",old.equals(before));
        check("groups_preserve_first_old_occurrence",root(update.get("main_v2")).equals("maintenance|apps|device|appearance|startup|recents|playback"));
        check("merged_wallpaper_entries_appear_once",update.get("appearance").equals("clock|wallpaper|cards"));
        check("maintenance_preserves_old_preference",update.get("maintenance").equals("diagnostics|backup|about"));
        old.putAll(update);
        check("migration_is_idempotent",SettingsCatalog.migration(old).isEmpty());
        check("existing_nested_sort_unchanged",old.get("clock").equals(before.get("clock"))&&old.get("cards").equals(before.get("cards")));
        check("unrelated_preferences_unchanged",old.get("untouched").equals("value"));
        old.remove("main_v2");old.put("appearance","cards|clock|wallpaper");old.put("maintenance","about|backup");
        update=SettingsCatalog.migration(old);
        check("partial_migration_keeps_new_nested_orders",!update.containsKey("appearance")&&!update.containsKey("maintenance"));
        check("unknown_legacy_ids_are_ignored",SettingsCatalog.migrateRoot("missing|clock|clock|unknown|cards").equals("appearance"));
        check("removed_items_drop_and_new_items_append",MenuOrder.encode(MenuOrder.resolve("startup|sound|network|info|sound",new String[]{"network","sound"}),new String[]{"network","sound"}).equals("sound|network"));
        check("unknown_only_root_falls_back",root(SettingsCatalog.migrateRoot("obsolete")).equals(root("")));
        // Restoring a legacy backup removes all allowlisted new order keys before upgrading again.
        Map<String,Object> restored=new HashMap<>(old);for(String key:SettingsCatalog.ORDER_KEYS)restored.remove(key);
        restored.put("main","hidden|recents|clock");update=SettingsCatalog.migration(restored);
        check("old_backup_replaces_previous_new_root",root(update.get("main_v2")).startsWith("apps|recents|appearance|"));
        check("new_backup_marker_blocks_remigration",SettingsCatalog.migration(Collections.singletonMap("main_v2","device|apps")).isEmpty());
        check("all_new_order_groups_are_backup_allowed",SettingsCatalog.ORDER_KEYS.containsAll(Arrays.asList("main_v2","appearance","apps","maintenance","task_style","health","diagnostics","playback","backup","wallpaper")));
        check("two_cards_with_same_label_have_distinct_pages",!SettingsCatalog.scope("card","fixture.one").equals(SettingsCatalog.scope("card","fixture.two")));
        System.out.println("OK "+passed+" settings migration checks");
    }
}
