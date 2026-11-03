// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.graphics.Bitmap;

/** Decodes the configured wallpaper off the main thread; only the newest request reaches HomeView. */
final class WallpaperLoader {
    private final MainActivity host;
    private volatile int wallpaperGeneration;

    WallpaperLoader(MainActivity host) {
        this.host = host;
    }

    void load() {
        final int generation = ++wallpaperGeneration;
        final String uri=host.appearance().getString("wallpaper","");
        final int builtin=host.appearance().getInt("wallpaper_builtin",0);
        final int width=host.home.getWidth()>0?host.home.getWidth():host.getResources().getDisplayMetrics().widthPixels;
        final int height=host.home.getHeight()>0?host.home.getHeight():host.getResources().getDisplayMetrics().heightPixels;
        host.wallpaperReads.execute(() -> {
            if(generation!=wallpaperGeneration||Thread.currentThread().isInterrupted())return;
            Bitmap ready = WallpaperSource.load(host,uri,builtin);
            PreviewBackdrop backdrop=new PreviewBackdrop();
            backdrop.prepare(ready,width,height);
            host.handler.post(() -> {
                if (!host.isDestroyed() && generation == wallpaperGeneration) {
                    Bitmap previous = host.home.wallpaper;
                    host.home.wallpaper = ready;
                    host.home.overviewRenderer.backdrop(backdrop);
                    if (previous != null && previous != ready && previous != host.home.wallpaper) previous.recycle();
                    host.home.invalidate();
                }else{
                    backdrop.clear();
                    if(ready!=null)ready.recycle();
                }
            });
        });
    }
}
