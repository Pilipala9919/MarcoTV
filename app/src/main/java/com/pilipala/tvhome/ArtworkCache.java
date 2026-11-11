// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.util.LruCache;

/**
 * Memory-bounded artwork cache.
 * On television boxes with little RAM the cache is turned off entirely: keeping decoded artwork
 * around costs more than decoding it again, so those devices trade CPU for usable memory.
 */
final class ArtworkCache {
    private static final int RICH_BUDGET_MB = 26;
    private static final int LEAN_BUDGET_MB = 6;
    private static boolean evaluated;
    private static boolean retain = true;
    private static final LruCache<String, Bitmap> ENTRIES = new LruCache<String, Bitmap>(RICH_BUDGET_MB * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private ArtworkCache() { }

    static void configure(Context context) {
        boolean severe = severeLowMemory(context);
        retain = !severe;
        if (severe) ENTRIES.evictAll();
        ENTRIES.resize((severe ? 1 : lean(context) ? LEAN_BUDGET_MB : RICH_BUDGET_MB) * 1024 * 1024);
        evaluated = true;
    }

    static Bitmap get(String key) {
        return key == null ? null : ENTRIES.get(key);
    }

    static void put(String key, Bitmap value) {
        if (key == null || value == null || value.isRecycled()) return;
        if (evaluated && !retain) return;
        ENTRIES.put(key, value);
    }

    static void clear() {
        ENTRIES.evictAll();
    }

    static void remove(String key) {
        if (key != null) ENTRIES.remove(key);
    }

    static void invalidatePackage(String packageName) {
        if (packageName == null) return;
        String prefix = "pkg:" + packageName + ":";
        for (String key : ENTRIES.snapshot().keySet()) if (key.startsWith(prefix)) ENTRIES.remove(key);
    }

    /** True when memory is so constrained (<=1 GB RAM or <=48 MB heap) that caching decoded bitmaps risks OOM. */
    private static boolean severeLowMemory(Context context) {
        long maxHeap = Runtime.getRuntime().maxMemory();
        if (maxHeap <= 48L * 1024 * 1024) return true;
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            if (info.totalMem > 0 && info.totalMem <= 1024L * 1024 * 1024) return true;
            if (manager.isLowRamDevice() && maxHeap <= 64L * 1024 * 1024) return true;
        }
        return false;
    }

    /** True when the device should use a reduced artwork cache budget. */
    static boolean lean(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            if (manager.isLowRamDevice()) return true;
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            if (info.totalMem > 0 && info.totalMem <= 2L * 1024 * 1024 * 1024) return true;
        }
        return Runtime.getRuntime().maxMemory() <= 64L * 1024 * 1024;
    }
}
