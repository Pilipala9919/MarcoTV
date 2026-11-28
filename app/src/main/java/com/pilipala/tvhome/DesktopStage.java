// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

/**
 * GPU-native desktop stage hosting:
 * 1) a single-pass blurred backdrop shown only during Magic Desktop preview, and
 * 2) a 3D-transformable scene container (live video wallpaper + HomeView) with hardware
 *    rounded-corner outline clipping, eliminating per-frame clipPath & EVEN_ODD mask overhead.
 */
final class DesktopStage extends FrameLayout {
    final View backdropView;
    final FrameLayout sceneContainer;
    final LiveWallpaperView liveWallpaper;
    final HomeView home;

    private final GradientDrawable borderDrawable = new GradientDrawable();
    private float cornerRadius;
    private float lastOverview = -1f;
    private boolean hardwareLayerActive;
    private boolean borderAttached;

    DesktopStage(MainActivity host, HomeView home) {
        super(host);
        this.home = home;
        setFocusable(false);
        setClipChildren(false);
        setClipToPadding(false);
        setBackgroundColor(0xFF0B121C);
        borderDrawable.setColor(Color.TRANSPARENT);
        borderDrawable.setStroke(Math.max(1, Math.round(1.4f * getResources().getDisplayMetrics().density)), 0x709BB9CB);

        backdropView = new View(host) {
            @Override protected void onDraw(Canvas canvas) {
                home.overviewRenderer.drawBackdrop(canvas);
            }
        };
        backdropView.setFocusable(false);
        backdropView.setVisibility(GONE);
        addView(backdropView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        sceneContainer = new FrameLayout(host);
        sceneContainer.setFocusable(false);
        sceneContainer.setClipChildren(true);
        sceneContainer.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                int w = view.getWidth(), h = view.getHeight();
                if (w > 0 && h > 0) {
                    outline.setRoundRect(0, 0, w, h, cornerRadius);
                }
            }
        });
        sceneContainer.setClipToOutline(false);

        liveWallpaper = new LiveWallpaperView(host);
        sceneContainer.addView(liveWallpaper, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        sceneContainer.addView(home, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(sceneContainer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        home.overviewRenderer.attachStage(this);
        liveWallpaper.setListeners(
                ready -> home.setLiveVideoReady(ready),
                path -> host.handler.post(() -> {
                    if (!host.isDestroyed() && host.foreground) {
                        host.toast("当前视频编码暂不被本机支持，已回退为静态首帧壁纸");
                    }
                })
        );
    }

    void invalidateBackdrop() {
        if (backdropView.getVisibility() == VISIBLE) {
            backdropView.invalidate();
        }
    }

    void applyOverview(float overview, float scale, float tx, float ty, float radius, float cameraDist) {
        boolean active = overview > 0.001f;
        boolean wasActive = lastOverview > 0.001f;
        lastOverview = overview;
        liveWallpaper.setOverviewActive(active);

        if (active) {
            if (backdropView.getVisibility() != VISIBLE) {
                backdropView.setVisibility(VISIBLE);
                backdropView.invalidate();
            }
            int w = sceneContainer.getWidth(), h = sceneContainer.getHeight();
            if (w > 0 && h > 0) {
                sceneContainer.setPivotX(w / 2f);
                sceneContainer.setPivotY(h / 2f);
            }
            sceneContainer.setCameraDistance(cameraDist);
            sceneContainer.setRotationY(OverviewRenderer.TILT_DEGREES * overview);
            sceneContainer.setScaleX(scale);
            sceneContainer.setScaleY(scale);
            sceneContainer.setTranslationX(tx);
            sceneContainer.setTranslationY(ty);
            if (Math.abs(cornerRadius - radius) > 0.25f || !wasActive) {
                cornerRadius = radius;
                borderDrawable.setCornerRadius(radius);
                sceneContainer.invalidateOutline();
            }
            borderDrawable.setAlpha(Math.max(0, Math.min(255, Math.round(255 * overview))));
            if (!borderAttached) {
                sceneContainer.setForeground(borderDrawable);
                borderAttached = true;
            }
            if (!sceneContainer.getClipToOutline()) {
                sceneContainer.setClipToOutline(true);
            }
            if (!hardwareLayerActive && sceneContainer.isHardwareAccelerated()) {
                sceneContainer.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                hardwareLayerActive = true;
            }
        } else {
            if (backdropView.getVisibility() != GONE) {
                backdropView.setVisibility(GONE);
            }
            if (borderAttached) {
                sceneContainer.setForeground(null);
                borderAttached = false;
            }
            if (hardwareLayerActive) {
                sceneContainer.setLayerType(View.LAYER_TYPE_NONE, null);
                hardwareLayerActive = false;
            }
            if (sceneContainer.getClipToOutline()) {
                sceneContainer.setClipToOutline(false);
            }
            cornerRadius = 0f;
            sceneContainer.setRotationY(0f);
            sceneContainer.setScaleX(1f);
            sceneContainer.setScaleY(1f);
            sceneContainer.setTranslationX(0f);
            sceneContainer.setTranslationY(0f);
        }
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0 && lastOverview > 0.001f) {
            home.overviewRenderer.applyStage(lastOverview);
        }
    }
}
