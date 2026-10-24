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
    private static boolean evaluated;
    private static boolean retain = true;
    private static final LruCache<String, Bitmap> ENTRIES = new LruCache<String, Bitmap>(RICH_BUDGET_MB * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    private ArtworkCache() { }

    static void configure(Context context) {
        retain = !lean(context);
        if (!retain) ENTRIES.evictAll();
        evaluated = true;
    }

    static Bitmap get(String key) { return key == null ? null : ENTRIES.get(key); }

    static void put(String key, Bitmap value) {
        if (key == null || value == null || value.isRecycled()) return;
        if (evaluated && !retain) return;
        ENTRIES.put(key, value);
    }

    static void clear() { ENTRIES.evictAll(); }

    static void remove(String key) { if (key != null) ENTRIES.remove(key); }

    /** True when the device cannot spare memory for cached artwork. */
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
