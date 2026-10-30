// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.graphics.Bitmap;

/** Decodes the configured wallpaper off the main thread; only the newest request reaches HomeView. */
final class WallpaperLoader {
    private final MainActivity host;
    private int wallpaperGeneration;

    WallpaperLoader(MainActivity host) {
        this.host = host;
    }

    void load() {
        final int generation = ++wallpaperGeneration;
        host.loader.execute(() -> {
            Bitmap ready = WallpaperSource.load(host);
            host.handler.post(() -> {
                if (!host.isDestroyed() && generation == wallpaperGeneration) {
                    Bitmap previous = host.home.wallpaper;
                    host.home.wallpaper = ready;
                    if (previous != null && previous != ready && previous != host.home.wallpaper) previous.recycle();
                    host.home.invalidate();
                }
            });
        });
    }
}
