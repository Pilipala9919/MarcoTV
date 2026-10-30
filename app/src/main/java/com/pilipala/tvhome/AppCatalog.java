// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import java.util.*;

/** Builds the home shelves from installed launchers, TV inputs and the user's card settings. */
final class AppCatalog {
    private final MainActivity host;
    private int loadGeneration;

    AppCatalog(MainActivity host) {
        this.host = host;
    }

    private Tile applicationTile(ResolveInfo resolve) {
        PackageManager pm = host.getPackageManager();
        Tile tile = new Tile();
        tile.id = resolve.activityInfo.packageName;
        tile.label = resolve.loadLabel(pm).toString();
        tile.intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(tile.id, resolve.activityInfo.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        for (boolean banner : new boolean[]{true, false}) {
            Bitmap cached = ArtworkCache.get("pkg:" + tile.id + ":" + (banner ? 1 : 0));
            if (cached != null) {
                tile.artwork = cached;
                tile.banner = banner;
                return tile;
            }
        }
        Drawable art = null;
        try {
            art = resolve.activityInfo.loadBanner(pm);
        } catch (Exception ignored) { }
        tile.banner = art != null;
        if (art == null && (resolve.activityInfo.icon != 0 || resolve.activityInfo.applicationInfo.icon != 0)) {
            try {
                art = resolve.loadIcon(pm);
            } catch (Exception ignored) { }
        }
        String cacheKey = "pkg:" + tile.id + ":" + (tile.banner ? 1 : 0);
        if (art != null && !tile.banner) {
            tile.artwork = ArtworkStyle.card(art);
            tile.banner = true;
            ArtworkCache.put("pkg:"+tile.id+":1",tile.artwork);
            return tile;
        }
        if (art != null) {
            int w = tile.banner ? 480 : 192, h = tile.banner ? 270 : 192;
            tile.artwork = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(tile.artwork);
            art.setBounds(0, 0, w, h);
            art.draw(canvas);
            ArtworkCache.put(cacheKey, tile.artwork);
        }
        return tile;
    }

    void load() {
        final int generation = ++loadGeneration;
        final Row current = host.home.row>=0&&host.home.row<host.home.rows.size()?host.home.rows.get(host.home.row):null;
        final String focusedId = current==null||current.selected<0||current.selected>=current.tiles.size()?null:current.tiles.get(current.selected).id;
        final String focusedRow = current==null?null:current.orderKey;
        host.loader.execute(() -> {
            ArrayList<Row> result = new ArrayList<>();
            Row apps = new Row("TV 应用", "\ue333");
            Row inputs = new Row("输入源", "\ue890");
            Row others = new Row("其他应用", "\ue5c3");
            PackageManager pm = host.getPackageManager();
            HashSet<String> seen = new HashSet<>();
            HashSet<String> excluded = new HashSet<>(Arrays.asList(host.getPackageName(), "com.spocky.projengmenu", "com.mitv.tvhome"));
            try {
                for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
                    if (!excluded.contains(r.activityInfo.packageName) && seen.add(r.activityInfo.packageName)) apps.tiles.add(applicationTile(r));
                }
                for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
                    String id = r.activityInfo.packageName;
                    if (!excluded.contains(id) && seen.add(id)) {
                        Tile t = applicationTile(r);
                        if (id.equals("com.xiaomi.mitv.tvplayer") || id.equals("com.mitv.tvplayer")) apps.tiles.add(t);
                        else others.tiles.add(t);
                    }
                }
                String tvPlayer = "com.xiaomi.mitv.tvplayer";
                if (!seen.contains(tvPlayer)) {
                    for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).setPackage(tvPlayer), 0)) {
                        if (r.activityInfo.exported && seen.add(tvPlayer)) {
                            apps.tiles.add(applicationTile(r));
                            break;
                        }
                    }
                }
                for(String id:host.cards.set("manual_apps")) {
                    try {
                        ComponentName component=ComponentName.unflattenFromString(host.cards.manual(id));
                        if(component==null || !component.getPackageName().equals(id) || id.equals(host.getPackageName()))continue;
                        ActivityInfo info=pm.getActivityInfo(component,PackageManager.MATCH_DISABLED_COMPONENTS);
                        if(!AppLibrary.launchable(host,info))continue;
                        ResolveInfo resolved=new ResolveInfo();
                        resolved.activityInfo=info;
                        for(Iterator<Tile> it=apps.tiles.iterator();it.hasNext();)if(it.next().id.equals(id))it.remove();
                        for(Iterator<Tile> it=others.tiles.iterator();it.hasNext();)if(it.next().id.equals(id))it.remove();
                        apps.tiles.add(applicationTile(resolved));
                        seen.add(id);
                    } catch(PackageManager.NameNotFoundException|RuntimeException ignored) { }
                }
                final List<String> otherOrder = Arrays.asList(host.preferences.getString("order_other", "")
                        .split("\\|"));
                Collections.sort(others.tiles, (a, b) -> {
                    int ai = otherOrder.indexOf(a.id), bi = otherOrder.indexOf(b.id);
                    if (ai < 0) ai = 1000;
                    if (bi < 0) bi = 1000;
                    return ai != bi ? ai - bi : a.label.compareToIgnoreCase(b.label);
                });
            } catch (Exception ex) {
                android.util.Log.e("TVHome", "Application discovery", ex);
            }
            try {
                TvInputManager manager = (TvInputManager) host.getSystemService(Context.TV_INPUT_SERVICE);
                if (manager != null) for (TvInputInfo info : manager.getTvInputList()) {
                    if (info.isPassthroughInput() && !info.isHidden(host)) {
                        Tile t = new Tile();
                        t.kind = 1;
                        t.id = info.getId();
                        CharSequence custom = info.loadCustomLabel(host);
                        t.label = (custom == null ? info.loadLabel(host) : custom).toString();
                        t.intent = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(t.id))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        inputs.tiles.add(t);
                    }
                }
            } catch (Exception ex) {
                android.util.Log.i("TVHome", "Inputs not exposed by this device", ex);
            }
            ArrayList<Tile> catalog = new ArrayList<>();
            catalog.addAll(apps.tiles);
            catalog.addAll(inputs.tiles);
            catalog.addAll(others.tiles);
            Row favorites = new Row("收藏", "\ue838");
            for (Tile tile : catalog) {
                tile.label = host.cards.name(tile.id, tile.label);
                tile.pinned=host.cards.contains("pinned",tile.id);
                String style = host.cards.cover(tile.id);
                if (style.equals("image")) {
                    Bitmap custom = host.cards.loadImage(tile.id);
                    if (custom != null) {
                        tile.artwork = custom;
                        tile.banner = true;
                    }
                } else if (!style.equals("original")) {
                    tile.artwork = null;
                    tile.banner = false;
                    tile.tint = style.equals("blue") ? 0xFF183857 : style.equals("green") ? 0xFF194A43 : style.equals("amber") ? 0xFF63432C : 0xFF302D45;
                    tile.glyph = tile.kind == 1 ? "\ue333" : "\ue5c3";
                }
                if (host.cards.contains("favorites", tile.id) && !host.cards.contains("hidden", tile.id)) favorites.tiles.add(copyTile(tile));
            }
            for (Row r : Arrays.asList(apps, inputs, others)) {
                Iterator<Tile> iterator = r.tiles.iterator();
                while (iterator.hasNext()) if (host.cards.contains("hidden", iterator.next().id)) iterator.remove();
            }
            sortSaved(inputs);
            sortSaved(favorites);
            sortSaved(others);
            AppOrder.sort(apps.tiles,host.preferences,host.cards);
            result.add(apps);
            if (!favorites.tiles.isEmpty()) result.add(favorites);
            if (!inputs.tiles.isEmpty()) result.add(inputs);
            if (!others.tiles.isEmpty()) result.add(others);
            host.handler.post(() -> {
                if (!host.isDestroyed() && generation == loadGeneration) {
                    host.allTiles.clear();
                    host.allTiles.addAll(catalog);
                    host.home.rows = result;
                    host.home.resetToApps();
                    if (focusedId != null) {
                        for (int i = 0; i < result.size(); i++) if (result.get(i).orderKey.equals(focusedRow)) {
                            Row r = result.get(i);
                            for (int j = 0; j < r.tiles.size(); j++) if (r.tiles.get(j).id.equals(focusedId)) {
                                host.home.row = i;
                                r.selected = j;
                            }
                        }
                    }
                    if(host.focusAfterLoad!=null){
                        for(int i=0;i<result.size();i++)if(result.get(i).orderKey.equals("order_tv"))for(int j=0;j<result.get(i).tiles.size();j++)if(result.get(i).tiles.get(j).id.equals(host.focusAfterLoad)){
                            host.home.row=i;
                            result.get(i).selected=j;
                        }
                        host.focusAfterLoad=null;
                    }
                    host.home.ensureVisible();
                    host.home.animateFocus();
                    host.home.describeFocus();
                    host.home.invalidate();
                    if(host.phone!=null)host.phone.catalogChanged();
                }
            });
        });
    }

    private Tile copyTile(Tile source) {
        Tile t = new Tile();
        t.id = source.id;
        t.label = source.label;
        t.intent = source.intent;
        t.pinned = source.pinned;
        t.artwork = source.artwork;
        t.banner = source.banner;
        t.kind = source.kind;
        t.tint = source.tint;
        t.glyph = source.glyph;
        return t;
    }
    private void sortSaved(Row row) {
        final List<String> order = Arrays.asList(host.preferences.getString(row.orderKey, "").split("\\|"));
        Collections.sort(row.tiles, (a, b) -> {
            if(a.pinned!=b.pinned)return a.pinned?-1:1;
            int ai = order.indexOf(a.id), bi = order.indexOf(b.id);
            if (ai < 0) ai = 1000;
            if (bi < 0) bi = 1000;
            return ai == bi ? a.label.compareToIgnoreCase(b.label) : ai-bi;
        });
    }

    void sortCurrent() {
        for(Row r:host.home.rows)if(r.orderKey.equals("order_tv")&&!r.tiles.isEmpty()) {
            String focused=r.tiles.get(r.selected).id;
            AppOrder.sort(r.tiles,host.preferences,host.cards);
            for(int i=0;i<r.tiles.size();i++)if(r.tiles.get(i).id.equals(focused))r.selected=i;
        }
        host.home.ensureVisible();
        host.home.animateFocus();
        host.home.describeFocus();
    }
}
