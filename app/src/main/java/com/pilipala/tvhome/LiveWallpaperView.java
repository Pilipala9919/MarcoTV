// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.TextureView;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Muted looping video wallpaper backed by TextureView + native MediaPlayer.
 * All MediaPlayer Binder IPC (prepare, start, setSurface, release) runs serialized on a dedicated
 * single background thread so hardware decoders (e.g. Amlogic OMX) can NEVER stall or ANR the UI thread.
 */
final class LiveWallpaperView extends TextureView implements TextureView.SurfaceTextureListener {
    interface ReadyListener {
        void onVideoReadyChanged(boolean ready);
    }

    interface ErrorListener {
        void onVideoError(String path);
    }

    private final ExecutorService mediaExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MarcoTV-live-wp");
        t.setDaemon(true);
        return t;
    });

    // Accessed only on mediaExecutor:
    private MediaPlayer activePlayer;
    private Surface activeSurface;

    // Accessed on UI thread (generation is volatile for fast-bail checks on mediaExecutor):
    private String videoPath;
    private String lastErroredPath;
    private boolean surfaceAvailable;
    private boolean active;
    private boolean started;
    private boolean ready;
    private int videoWidth;
    private int videoHeight;
    private volatile int generation;
    private ReadyListener readyListener;
    private ErrorListener errorListener;
    private Runnable pendingReveal;

    LiveWallpaperView(Context context) {
        super(context);
        setOpaque(true);
        setFocusable(false);
        setClickable(false);
        setSurfaceTextureListener(this);
        setVisibility(GONE);
    }

    void setListeners(ReadyListener readyListener, ErrorListener errorListener) {
        this.readyListener = readyListener;
        this.errorListener = errorListener;
    }

    boolean hasVideo() {
        return videoPath != null && !videoPath.isEmpty();
    }

    boolean isVideoReady() {
        return ready && hasVideo() && active;
    }

    void setVideoPath(String path) {
        String normalized = (path == null || path.isEmpty() || !new File(path).isFile()) ? "" : path;
        if (normalized.equals(videoPath == null ? "" : videoPath)) {
            if (active && hasVideo() && surfaceAvailable && !started) {
                startPlayerAsync();
            }
            return;
        }
        releasePlayerAsync(null);
        videoPath = normalized;
        lastErroredPath = null;
        videoWidth = 0;
        videoHeight = 0;
        if (normalized.isEmpty()) {
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);
        if (active && surfaceAvailable) {
            startPlayerAsync();
        }
    }

    void setActive(boolean shouldPlay) {
        if (active == shouldPlay) {
            return;
        }
        active = shouldPlay;
        if (!active) {
            releasePlayerAsync(null);
        } else if (hasVideo()) {
            setVisibility(VISIBLE);
            if (surfaceAvailable) startPlayerAsync();
        }
    }

    void releasePlayer() {
        releasePlayerAsync(null);
    }

    void destroy() {
        releasePlayerAsync(null);
        mediaExecutor.shutdown();
    }

    private void setReady(boolean value) {
        if (ready == value) return;
        ready = value;
        if (readyListener != null) readyListener.onVideoReadyChanged(ready);
    }

    private void cancelPendingReveal() {
        if (pendingReveal != null) {
            removeCallbacks(pendingReveal);
            pendingReveal = null;
        }
    }

    private void startPlayerAsync() {
        if (!active || !hasVideo() || !surfaceAvailable) return;
        final SurfaceTexture st = getSurfaceTexture();
        if (st == null) return;
        final int token = ++generation;
        final String path = videoPath;
        started = true;
        cancelPendingReveal();
        setReady(false);

        mediaExecutor.execute(() -> {
            teardownOnWorker(null);
            if (token != generation) return;
            MediaPlayer mp = null;
            Surface surf = null;
            try {
                surf = new Surface(st);
                mp = new MediaPlayer();
                final MediaPlayer created = mp;
                mp.setOnVideoSizeChangedListener((m, width, height) -> {
                    if (token != generation) return;
                    if (width > 0 && height > 0) {
                        videoWidth = width;
                        videoHeight = height;
                        updateCenterCropTransform();
                    }
                });
                mp.setOnInfoListener((m, what, extra) -> {
                    if (token != generation) return false;
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        cancelPendingReveal();
                        setReady(true);
                        return true;
                    }
                    return false;
                });
                mp.setOnPreparedListener(m -> {
                    if (token != generation || !active) return;
                    mediaExecutor.execute(() -> {
                        if (token != generation || created != activePlayer) return;
                        try {
                            int vw = created.getVideoWidth();
                            int vh = created.getVideoHeight();
                            created.setVolume(0f, 0f);
                            created.setLooping(true);
                            created.start();
                            post(() -> {
                                if (token != generation || !active) return;
                                if (vw > 0 && vh > 0) {
                                    videoWidth = vw;
                                    videoHeight = vh;
                                    updateCenterCropTransform();
                                }
                                cancelPendingReveal();
                                pendingReveal = () -> {
                                    pendingReveal = null;
                                    if (token == generation && active && !ready) {
                                        setReady(true);
                                    }
                                };
                                postDelayed(pendingReveal, 180);
                            });
                        } catch (RuntimeException error) {
                            post(() -> handlePlaybackFailure(token, path));
                        }
                    });
                });
                mp.setOnErrorListener((m, what, extra) -> {
                    handlePlaybackFailure(token, path);
                    return true;
                });
                mp.setSurface(surf);
                mp.setDataSource(path);
                mp.setLooping(true);
                mp.setVolume(0f, 0f);
                mp.setScreenOnWhilePlaying(false);
                if (token != generation) {
                    safeRelease(mp, surf, null);
                    return;
                }
                activePlayer = mp;
                activeSurface = surf;
                mp.prepareAsync();
            } catch (Exception error) {
                safeRelease(mp, surf, null);
                post(() -> handlePlaybackFailure(token, path));
            }
        });
    }

    private void handlePlaybackFailure(int token, String path) {
        if (token != generation) return;
        releasePlayerAsync(null);
        Diagnostics.event("LIVE_WALLPAPER_ERROR");
        if (path != null && !path.equals(lastErroredPath)) {
            lastErroredPath = path;
            if (errorListener != null) errorListener.onVideoError(path);
        }
    }

    private void releasePlayerAsync(SurfaceTexture textureToRelease) {
        generation++;
        started = false;
        cancelPendingReveal();
        setReady(false);
        if (!mediaExecutor.isShutdown()) {
            mediaExecutor.execute(() -> teardownOnWorker(textureToRelease));
        }
    }

    private void teardownOnWorker(SurfaceTexture textureToRelease) {
        MediaPlayer mp = activePlayer;
        Surface surf = activeSurface;
        activePlayer = null;
        activeSurface = null;
        safeRelease(mp, surf, textureToRelease);
    }

    private static void safeRelease(MediaPlayer mp, Surface surf, SurfaceTexture textureToRelease) {
        if (mp != null) {
            try { mp.setOnPreparedListener(null); } catch (RuntimeException ignored) {}
            try { mp.setOnInfoListener(null); } catch (RuntimeException ignored) {}
            try { mp.setOnErrorListener(null); } catch (RuntimeException ignored) {}
            try { mp.setOnVideoSizeChangedListener(null); } catch (RuntimeException ignored) {}
            // Detach surface BEFORE release so Amlogic hardware codecs never stall waiting on BufferQueue.
            try { mp.setSurface(null); } catch (RuntimeException ignored) {}
            try { mp.release(); } catch (RuntimeException ignored) {}
        }
        if (surf != null) {
            try { surf.release(); } catch (RuntimeException ignored) {}
        }
        if (textureToRelease != null) {
            try { textureToRelease.release(); } catch (RuntimeException ignored) {}
        }
    }

    private void updateCenterCropTransform() {
        int vw = getWidth(), vh = getHeight();
        if (vw <= 0 || vh <= 0 || videoWidth <= 0 || videoHeight <= 0) return;
        float viewAspect = vw / (float) vh;
        float videoAspect = videoWidth / (float) videoHeight;
        float sx = 1f, sy = 1f;
        if (videoAspect > viewAspect) {
            sx = videoAspect / viewAspect;
        } else {
            sy = viewAspect / videoAspect;
        }
        Matrix m = new Matrix();
        m.setScale(sx, sy, vw / 2f, vh / 2f);
        setTransform(m);
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
        surfaceAvailable = true;
        updateCenterCropTransform();
        if (active && hasVideo()) startPlayerAsync();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
        updateCenterCropTransform();
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        surfaceAvailable = false;
        // Let the worker thread release the SurfaceTexture strictly AFTER MediaPlayer detaches it.
        releasePlayerAsync(st);
        return false;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}

    @Override protected void onDetachedFromWindow() {
        releasePlayerAsync(null);
        super.onDetachedFromWindow();
    }
}
