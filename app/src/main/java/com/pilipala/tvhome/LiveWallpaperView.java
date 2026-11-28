// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import java.io.File;
import java.io.FileInputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Muted looping video wallpaper with serialized MediaPlayer IPC on a dedicated worker thread.
 *
 * Supervises every play cycle starting immediately at view mount (catching missing surface
 * callbacks as well as prepare/render stalls), uses a fresh surface per active cycle, and
 * automatically falls back from TextureView to SurfaceView when OEM TV decoders fail, stall,
 * or freeze. SurfaceView is latched as preferred only after repeated progress updates (including
 * short-clip loop wraps) confirm stable continuous playback.
 */
final class LiveWallpaperView extends FrameLayout {
    interface ReadyListener {
        void onVideoReadyChanged(boolean ready);
    }

    interface ErrorListener {
        void onVideoError(String path);
    }

    private static volatile boolean preferSurfaceView;

    private final ExecutorService mediaExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MarcoTV-live-wp");
        t.setDaemon(true);
        return t;
    });

    // Accessed only on mediaExecutor:
    private MediaPlayer activePlayer;
    private Surface ownedSurface;

    // Accessed on UI thread (generation is volatile for fast-bail checks on mediaExecutor):
    private String videoPath;
    private String lastErroredPath;
    private boolean active;
    private boolean overviewActive;
    private boolean started;
    private boolean prepared;
    private boolean renderingStarted;
    private boolean ready;
    private boolean surfaceFallbackTriedForPath;
    private boolean useSurfaceForPath;
    private int renderedFrames;
    private int framesAtReveal;
    private int durationMs;
    private int videoWidth;
    private int videoHeight;
    private long mountedAt;
    private long startedAt;
    private long preparedAt;
    private final LiveWallpaperPolicy.ProgressTracker surfaceProgress = new LiveWallpaperPolicy.ProgressTracker();
    private LiveWallpaperPolicy.Backend activeBackend = LiveWallpaperPolicy.Backend.TEXTURE;
    private View activeChild;
    private TextureView textureChild;
    private SurfaceView surfaceChild;
    private boolean childSurfaceReady;
    private volatile int generation;
    private ReadyListener readyListener;
    private ErrorListener errorListener;
    private Runnable startupWatchdog;
    private Runnable postRevealCheck;

    LiveWallpaperView(Context context) {
        super(context);
        setFocusable(false);
        setClickable(false);
        setClipChildren(true);
        setClipToPadding(true);
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

    void setOverviewActive(boolean inOverview) {
        if (overviewActive == inOverview) return;
        overviewActive = inOverview;
        if (effectiveBackend() == LiveWallpaperPolicy.Backend.SURFACE) {
            syncPlaybackState();
        }
    }

    void setVideoPath(String path) {
        String normalized = (path == null || path.isEmpty() || !new File(path).isFile()) ? "" : path;
        if (normalized.equals(videoPath == null ? "" : videoPath)) {
            if (shouldRun() && !started && activeChild == null) {
                lastErroredPath = null;
                syncPlaybackState();
            } else if (shouldRun() && !started && childSurfaceReady) {
                lastErroredPath = null;
                startMountedChild();
            }
            return;
        }
        stopAndUnmountSurface();
        videoPath = normalized;
        lastErroredPath = null;
        surfaceFallbackTriedForPath = false;
        useSurfaceForPath = preferSurfaceView;
        videoWidth = 0;
        videoHeight = 0;
        durationMs = 0;
        if (normalized.isEmpty()) {
            if (getVisibility() != GONE) setVisibility(GONE);
            return;
        }
        if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
        syncPlaybackState();
    }

    void setActive(boolean shouldPlay) {
        if (active == shouldPlay) {
            if (shouldRun() && !started) {
                syncPlaybackState();
            }
            return;
        }
        active = shouldPlay;
        if (active) {
            lastErroredPath = null;
        }
        syncPlaybackState();
    }

    void releasePlayer() {
        stopAndUnmountSurface();
    }

    void destroy() {
        active = false;
        stopAndUnmountSurface();
        mediaExecutor.shutdown();
    }

    private LiveWallpaperPolicy.Backend effectiveBackend() {
        return (preferSurfaceView || useSurfaceForPath)
                ? LiveWallpaperPolicy.Backend.SURFACE
                : LiveWallpaperPolicy.Backend.TEXTURE;
    }

    private boolean shouldRun() {
        if (!active || !hasVideo()) return false;
        if (!isAttachedToWindow() || getWindowVisibility() != VISIBLE) return false;
        if (overviewActive && effectiveBackend() == LiveWallpaperPolicy.Backend.SURFACE) return false;
        return true;
    }

    private void syncPlaybackState() {
        if (!shouldRun()) {
            stopAndUnmountSurface();
            if (!hasVideo() && getVisibility() != GONE) setVisibility(GONE);
            return;
        }
        if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
        LiveWallpaperPolicy.Backend wanted = effectiveBackend();
        if (activeChild == null || activeBackend != wanted) {
            stopAndUnmountSurface();
            mountSurfaceChild(wanted);
            return;
        }
        if (!started && childSurfaceReady) {
            startMountedChild();
        }
    }

    private void mountSurfaceChild(LiveWallpaperPolicy.Backend backend) {
        activeBackend = backend;
        childSurfaceReady = false;
        started = false;
        prepared = false;
        renderingStarted = false;
        renderedFrames = 0;
        framesAtReveal = 0;
        durationMs = 0;
        surfaceProgress.reset();
        mountedAt = SystemClock.elapsedRealtime();
        startedAt = mountedAt;
        preparedAt = 0L;
        final int mountToken = ++generation;
        final String path = videoPath;
        cancelWatchdogs();
        setReady(false);
        scheduleStartupWatchdog(mountToken, path, backend);

        if (backend == LiveWallpaperPolicy.Backend.TEXTURE) {
            final TextureView tv = new TextureView(getContext());
            tv.setOpaque(true);
            tv.setFocusable(false);
            tv.setClickable(false);
            tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                    if (tv != textureChild || mountToken != generation) return;
                    childSurfaceReady = true;
                    updateVideoScale();
                    if (shouldRun() && !started) {
                        startPlayerAsync(mountToken, LiveWallpaperPolicy.Backend.TEXTURE, st, null);
                    }
                }

                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
                    if (tv == textureChild) updateVideoScale();
                }

                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    if (tv == textureChild) {
                        childSurfaceReady = false;
                        releasePlayerAsync(st, null);
                    } else if (!mediaExecutor.isShutdown()) {
                        mediaExecutor.execute(() -> safeReleaseSurfaceTexture(st));
                    } else {
                        safeReleaseSurfaceTexture(st);
                    }
                    return false;
                }

                @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {
                    if (tv != textureChild || mountToken != generation || !started || !shouldRun()) return;
                    renderedFrames++;
                    maybeRevealNow("texture_frame");
                }
            });
            textureChild = tv;
            surfaceChild = null;
            activeChild = tv;
            addView(tv, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            if (tv.isAvailable() && tv.getSurfaceTexture() != null) {
                childSurfaceReady = true;
                startPlayerAsync(mountToken, LiveWallpaperPolicy.Backend.TEXTURE, tv.getSurfaceTexture(), null);
            }
            return;
        }

        final SurfaceView sv = new SurfaceView(getContext());
        sv.setFocusable(false);
        sv.setClickable(false);
        sv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                if (sv != surfaceChild || mountToken != generation) return;
                childSurfaceReady = true;
                updateVideoScale();
                if (shouldRun() && !started) {
                    startPlayerAsync(mountToken, LiveWallpaperPolicy.Backend.SURFACE, null, holder);
                }
            }

            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                if (sv == surfaceChild) updateVideoScale();
            }

            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                if (sv == surfaceChild) {
                    childSurfaceReady = false;
                    releasePlayerAsync(null, null);
                }
            }
        });
        surfaceChild = sv;
        textureChild = null;
        activeChild = sv;
        addView(sv, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
    }

    private void startMountedChild() {
        final int token = generation;
        if (activeBackend == LiveWallpaperPolicy.Backend.TEXTURE && textureChild != null) {
            SurfaceTexture st = textureChild.getSurfaceTexture();
            if (st != null) startPlayerAsync(token, LiveWallpaperPolicy.Backend.TEXTURE, st, null);
        } else if (activeBackend == LiveWallpaperPolicy.Backend.SURFACE && surfaceChild != null) {
            SurfaceHolder holder = surfaceChild.getHolder();
            if (holder != null && holder.getSurface() != null && holder.getSurface().isValid()) {
                startPlayerAsync(token, LiveWallpaperPolicy.Backend.SURFACE, null, holder);
            }
        }
    }

    private void setReady(boolean value) {
        if (ready == value) return;
        ready = value;
        Log.i("MarcoTV", "Live wallpaper ready=" + ready + " backend=" + activeBackend
                + " frames=" + renderedFrames + " progressChanges=" + surfaceProgress.progressChanges()
                + " loops=" + surfaceProgress.loopWraps() + " posMs=" + surfaceProgress.lastPos());
        if (readyListener != null) readyListener.onVideoReadyChanged(ready);
    }

    private void cancelWatchdogs() {
        if (startupWatchdog != null) {
            removeCallbacks(startupWatchdog);
            startupWatchdog = null;
        }
        if (postRevealCheck != null) {
            removeCallbacks(postRevealCheck);
            postRevealCheck = null;
        }
    }

    private void maybeRevealNow(String trigger) {
        if (ready || !started || !shouldRun()) return;
        if (!LiveWallpaperPolicy.shouldReveal(activeBackend, active, prepared,
                renderingStarted, renderedFrames, surfaceProgress.progressChanges())) {
            return;
        }
        if (startupWatchdog != null) {
            removeCallbacks(startupWatchdog);
            startupWatchdog = null;
        }
        framesAtReveal = renderedFrames;
        Log.i("MarcoTV", "Live wallpaper reveal trigger=" + trigger + " backend=" + activeBackend
                + " frames=" + renderedFrames + " progressChanges=" + surfaceProgress.progressChanges()
                + " loops=" + surfaceProgress.loopWraps() + " posMs=" + surfaceProgress.lastPos());
        setReady(true);
        if (activeBackend == LiveWallpaperPolicy.Backend.TEXTURE) {
            scheduleTexturePostRevealCheck(generation, videoPath);
        } else {
            scheduleSurfaceStabilityMonitor(generation, videoPath);
        }
    }

    private void scheduleTexturePostRevealCheck(final int token, final String path) {
        if (postRevealCheck != null) removeCallbacks(postRevealCheck);
        postRevealCheck = () -> {
            postRevealCheck = null;
            if (token != generation || !shouldRun() || !ready) return;
            LiveWallpaperPolicy.WatchdogAction action = LiveWallpaperPolicy.evaluateTexturePostReveal(
                    active, ready, framesAtReveal, renderedFrames, durationMs,
                    !surfaceFallbackTriedForPath);
            if (action == LiveWallpaperPolicy.WatchdogAction.FALLBACK_TO_SURFACE) {
                Log.w("MarcoTV", "Live wallpaper TextureView froze after reveal (frames="
                        + framesAtReveal + "->" + renderedFrames + "); switching to SurfaceView");
                switchToSurfaceBackend(token);
            } else if (action == LiveWallpaperPolicy.WatchdogAction.FAIL_UNSUPPORTED) {
                Log.w("MarcoTV", "Live wallpaper TextureView froze after reveal; falling back to poster");
                handlePlaybackFailure(token, path);
            } else {
                Log.i("MarcoTV", "Live wallpaper healthy backend=TEXTURE frames="
                        + framesAtReveal + "->" + renderedFrames);
            }
        };
        postDelayed(postRevealCheck, LiveWallpaperPolicy.POST_REVEAL_LIVENESS_MS);
    }

    private void scheduleSurfaceStabilityMonitor(final int token, final String path) {
        if (postRevealCheck != null) removeCallbacks(postRevealCheck);
        postRevealCheck = new Runnable() {
            @Override public void run() {
                if (token != generation || !shouldRun() || !ready) {
                    postRevealCheck = null;
                    return;
                }
                if (mediaExecutor.isShutdown()) {
                    postRevealCheck = null;
                    return;
                }
                mediaExecutor.execute(() -> {
                    if (token != generation || activePlayer == null) return;
                    boolean playing = false;
                    int pos = -1;
                    try {
                        playing = activePlayer.isPlaying();
                        pos = activePlayer.getCurrentPosition();
                    } catch (RuntimeException ignored) {}
                    final boolean observedPlaying = playing;
                    final int observedPos = pos;
                    post(() -> {
                        if (token != generation || !shouldRun() || !ready) return;
                        surfaceProgress.observe(observedPlaying, observedPos, durationMs);
                        LiveWallpaperPolicy.StabilityAction action = LiveWallpaperPolicy.evaluateSurfaceStability(
                                active, ready, surfaceProgress.progressChanges(),
                                surfaceProgress.consecutiveUnchanged());
                        if (action == LiveWallpaperPolicy.StabilityAction.CONFIRM_PREFERRED) {
                            postRevealCheck = null;
                            if (surfaceFallbackTriedForPath) {
                                preferSurfaceView = true;
                            }
                            Log.i("MarcoTV", "Live wallpaper stable backend=SURFACE changes="
                                    + surfaceProgress.progressChanges() + " loops=" + surfaceProgress.loopWraps()
                                    + " posMs=" + surfaceProgress.lastPos()
                                    + " preferredLatched=" + preferSurfaceView);
                            return;
                        }
                        if (action == LiveWallpaperPolicy.StabilityAction.FAIL_UNSUPPORTED) {
                            postRevealCheck = null;
                            Log.w("MarcoTV", "Live wallpaper SurfaceView stalled before stability changes="
                                    + surfaceProgress.progressChanges() + " unchanged="
                                    + surfaceProgress.consecutiveUnchanged() + " posMs=" + observedPos);
                            handlePlaybackFailure(token, path);
                            return;
                        }
                        if (postRevealCheck != null) {
                            postDelayed(postRevealCheck, LiveWallpaperPolicy.PROGRESS_POLL_INTERVAL_MS);
                        }
                    });
                });
            }
        };
        postDelayed(postRevealCheck, LiveWallpaperPolicy.PROGRESS_POLL_INTERVAL_MS);
    }

    private void scheduleStartupWatchdog(final int token, final String path,
                                         final LiveWallpaperPolicy.Backend backend) {
        if (startupWatchdog != null) removeCallbacks(startupWatchdog);
        startupWatchdog = new Runnable() {
            @Override public void run() {
                if (token != generation || !shouldRun() || ready) {
                    startupWatchdog = null;
                    return;
                }
                if (backend == LiveWallpaperPolicy.Backend.SURFACE
                        && childSurfaceReady && prepared && !mediaExecutor.isShutdown()) {
                    mediaExecutor.execute(() -> {
                        if (token != generation || activePlayer == null) return;
                        boolean playing = false;
                        int pos = -1;
                        try {
                            playing = activePlayer.isPlaying();
                            pos = activePlayer.getCurrentPosition();
                        } catch (RuntimeException ignored) {}
                        final boolean isPlaying = playing;
                        final int currentPos = pos;
                        post(() -> {
                            if (token != generation || !shouldRun() || ready) return;
                            surfaceProgress.observe(isPlaying, currentPos, durationMs);
                            evaluateWatchdogTick(token, path, backend);
                        });
                    });
                } else {
                    evaluateWatchdogTick(token, path, backend);
                }
            }
        };
        postDelayed(startupWatchdog, LiveWallpaperPolicy.PROGRESS_POLL_INTERVAL_MS);
    }

    private void evaluateWatchdogTick(int token, String path, LiveWallpaperPolicy.Backend backend) {
        if (token != generation || !shouldRun() || ready) return;
        long now = SystemClock.elapsedRealtime();
        long mountedAgo = Math.max(0L, now - mountedAt);
        long startedAgo = Math.max(0L, now - (started ? startedAt : mountedAt));
        long preparedAgo = prepared ? Math.max(0L, now - preparedAt) : 0L;
        LiveWallpaperPolicy.WatchdogAction action = LiveWallpaperPolicy.evaluateStartup(
                backend, active, childSurfaceReady, prepared, ready, renderingStarted,
                renderedFrames, surfaceProgress.progressChanges(),
                mountedAgo, startedAgo, preparedAgo, !surfaceFallbackTriedForPath);
        if (action == LiveWallpaperPolicy.WatchdogAction.REVEAL) {
            maybeRevealNow("watchdog_progress");
            return;
        }
        if (action == LiveWallpaperPolicy.WatchdogAction.FALLBACK_TO_SURFACE) {
            Log.w("MarcoTV", "Live wallpaper TextureView stalled surfaceReady=" + childSurfaceReady
                    + " prepared=" + prepared + " renderingStarted=" + renderingStarted
                    + " frames=" + renderedFrames + " progressChanges=" + surfaceProgress.progressChanges()
                    + " mountedAgoMs=" + mountedAgo + " startedAgoMs=" + startedAgo
                    + "; switching to SurfaceView");
            switchToSurfaceBackend(token);
            return;
        }
        if (action == LiveWallpaperPolicy.WatchdogAction.FAIL_UNSUPPORTED) {
            Log.w("MarcoTV", "Live wallpaper playback stalled backend=" + backend
                    + " surfaceReady=" + childSurfaceReady + " prepared=" + prepared
                    + " renderingStarted=" + renderingStarted + " frames=" + renderedFrames
                    + " progressChanges=" + surfaceProgress.progressChanges()
                    + " mountedAgoMs=" + mountedAgo + " startedAgoMs=" + startedAgo);
            handlePlaybackFailure(token, path);
            return;
        }
        if (startupWatchdog != null) {
            postDelayed(startupWatchdog, LiveWallpaperPolicy.PROGRESS_POLL_INTERVAL_MS);
        }
    }

    private void switchToSurfaceBackend(int token) {
        if (token != generation) return;
        surfaceFallbackTriedForPath = true;
        useSurfaceForPath = true;
        stopAndUnmountSurface();
        if (shouldRun()) {
            mountSurfaceChild(LiveWallpaperPolicy.Backend.SURFACE);
        }
    }

    private void startPlayerAsync(final int token, final LiveWallpaperPolicy.Backend backend,
                                  final SurfaceTexture st, final SurfaceHolder holder) {
        if (token != generation || !shouldRun()) return;
        if (backend == LiveWallpaperPolicy.Backend.TEXTURE && st == null) return;
        if (backend == LiveWallpaperPolicy.Backend.SURFACE
                && (holder == null || holder.getSurface() == null || !holder.getSurface().isValid())) {
            return;
        }
        final String path = videoPath;
        started = true;
        prepared = false;
        renderingStarted = false;
        renderedFrames = 0;
        framesAtReveal = 0;
        durationMs = 0;
        surfaceProgress.reset();
        startedAt = SystemClock.elapsedRealtime();
        preparedAt = 0L;
        if (startupWatchdog == null) {
            scheduleStartupWatchdog(token, path, backend);
        }

        mediaExecutor.execute(() -> {
            teardownOnWorker(null);
            if (token != generation) return;
            logTrackMetadata(path, backend);
            MediaPlayer mp = null;
            Surface createdSurface = null;
            try {
                mp = new MediaPlayer();
                final MediaPlayer created = mp;
                mp.setOnVideoSizeChangedListener((m, width, height) -> post(() -> {
                    if (token != generation) return;
                    if (width > 0 && height > 0) {
                        videoWidth = width;
                        videoHeight = height;
                        updateVideoScale();
                    }
                }));
                mp.setOnInfoListener((m, what, extra) -> {
                    Log.i("MarcoTV", "Live wallpaper info backend=" + backend
                            + " what=" + what + " extra=" + extra);
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        post(() -> {
                            if (token != generation || !shouldRun() || ready) return;
                            renderingStarted = true;
                            maybeRevealNow("rendering_start");
                        });
                        return true;
                    }
                    return false;
                });
                mp.setOnCompletionListener(m -> {
                    post(() -> {
                        if (token == generation) surfaceProgress.onLoopCompletion();
                    });
                    mediaExecutor.execute(() -> {
                        if (token != generation || created != activePlayer) return;
                        try {
                            created.seekTo(0);
                            created.start();
                        } catch (RuntimeException ignored) {}
                    });
                });
                mp.setOnPreparedListener(m -> {
                    if (token != generation) return;
                    mediaExecutor.execute(() -> {
                        if (token != generation || created != activePlayer) return;
                        try {
                            int vw = created.getVideoWidth();
                            int vh = created.getVideoHeight();
                            int dur = created.getDuration();
                            created.setVolume(0f, 0f);
                            created.setLooping(true);
                            try {
                                created.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                            } catch (RuntimeException ignored) {}
                            created.start();
                            Log.i("MarcoTV", "Live wallpaper prepared backend=" + backend
                                    + " video=" + vw + "x" + vh + " durMs=" + dur);
                            post(() -> {
                                if (token != generation || !shouldRun()) return;
                                prepared = true;
                                preparedAt = SystemClock.elapsedRealtime();
                                durationMs = dur;
                                if (vw > 0 && vh > 0) {
                                    videoWidth = vw;
                                    videoHeight = vh;
                                    updateVideoScale();
                                }
                                maybeRevealNow("prepared");
                            });
                        } catch (RuntimeException error) {
                            Log.w("MarcoTV", "Live wallpaper start failed backend=" + backend, error);
                            post(() -> handlePlayerError(token, path, backend));
                        }
                    });
                });
                mp.setOnErrorListener((m, what, extra) -> {
                    Log.w("MarcoTV", "Live wallpaper error backend=" + backend
                            + " what=" + what + " extra=" + extra + " file=" + new File(path).getName());
                    post(() -> handlePlayerError(token, path, backend));
                    return true;
                });
                File file = new File(path);
                try (FileInputStream fis = new FileInputStream(file)) {
                    mp.setDataSource(fis.getFD(), 0L, file.length());
                }
                if (backend == LiveWallpaperPolicy.Backend.TEXTURE) {
                    createdSurface = new Surface(st);
                    mp.setSurface(createdSurface);
                } else {
                    Surface svSurface = holder.getSurface();
                    if (svSurface == null || !svSurface.isValid()) {
                        safeRelease(mp, null, null);
                        post(() -> {
                            if (token == generation) {
                                started = false;
                                childSurfaceReady = false;
                            }
                        });
                        return;
                    }
                    mp.setSurface(svSurface);
                }
                mp.setScreenOnWhilePlaying(false);
                if (token != generation) {
                    safeRelease(mp, createdSurface, null);
                    return;
                }
                activePlayer = mp;
                ownedSurface = createdSurface;
                mp.prepareAsync();
            } catch (Exception error) {
                Log.w("MarcoTV", "Live wallpaper prepare exception backend=" + backend, error);
                safeRelease(mp, createdSurface, null);
                post(() -> handlePlayerError(token, path, backend));
            }
        });
    }

    private static void logTrackMetadata(String path, LiveWallpaperPolicy.Backend backend) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            File file = new File(path);
            try (FileInputStream fis = new FileInputStream(file)) {
                extractor.setDataSource(fis.getFD(), 0L, file.length());
            }
            int tracks = extractor.getTrackCount();
            for (int i = 0; i < tracks; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    int w = safeInt(format, MediaFormat.KEY_WIDTH, 0);
                    int h = safeInt(format, MediaFormat.KEY_HEIGHT, 0);
                    String fps = safeFrameRate(format);
                    int profile = safeInt(format, MediaFormat.KEY_PROFILE, -1);
                    int level = safeInt(format, MediaFormat.KEY_LEVEL, -1);
                    long durUs = safeLong(format, MediaFormat.KEY_DURATION, 0L);
                    Log.i("MarcoTV", "Live wallpaper prepare backend=" + backend
                            + " file=" + file.getName() + " mime=" + mime
                            + " size=" + w + "x" + h + " fps=" + fps
                            + " profile=" + profile + " level=" + level
                            + " durMs=" + (durUs / 1000L));
                    return;
                }
            }
            Log.i("MarcoTV", "Live wallpaper prepare backend=" + backend
                    + " file=" + file.getName() + " videoTrack=none");
        } catch (Throwable error) {
            Log.i("MarcoTV", "Live wallpaper prepare backend=" + backend
                    + " file=" + new File(path).getName() + " probeFailed=" + error.getClass().getSimpleName());
        } finally {
            try { extractor.release(); } catch (Throwable ignored) {}
        }
    }

    private static int safeInt(MediaFormat format, String key, int fallback) {
        if (!format.containsKey(key)) return fallback;
        try { return format.getInteger(key); } catch (Throwable ignored) { return fallback; }
    }

    private static long safeLong(MediaFormat format, String key, long fallback) {
        if (!format.containsKey(key)) return fallback;
        try { return format.getLong(key); } catch (Throwable ignored) { return fallback; }
    }

    private static String safeFrameRate(MediaFormat format) {
        if (!format.containsKey(MediaFormat.KEY_FRAME_RATE)) return "0";
        try { return String.valueOf(format.getInteger(MediaFormat.KEY_FRAME_RATE)); }
        catch (Throwable ignored) {}
        try { return String.format(Locale.ROOT, "%.2f", format.getFloat(MediaFormat.KEY_FRAME_RATE)); }
        catch (Throwable ignored) {}
        return "?";
    }

    private void handlePlayerError(int token, String path, LiveWallpaperPolicy.Backend backend) {
        if (token != generation) return;
        if (LiveWallpaperPolicy.shouldFallbackOnError(backend, !surfaceFallbackTriedForPath)) {
            Log.w("MarcoTV", "Live wallpaper error on TEXTURE; retrying on SurfaceView");
            switchToSurfaceBackend(token);
            return;
        }
        handlePlaybackFailure(token, path);
    }

    private void handlePlaybackFailure(int token, String path) {
        if (token != generation) return;
        stopAndUnmountSurface();
        Diagnostics.event("LIVE_WALLPAPER_ERROR");
        if (path != null && !path.equals(lastErroredPath)) {
            lastErroredPath = path;
            if (errorListener != null) errorListener.onVideoError(path);
        }
    }

    private void stopAndUnmountSurface() {
        final View stale = activeChild;
        activeChild = null;
        textureChild = null;
        surfaceChild = null;
        childSurfaceReady = false;
        releasePlayerAsync(null, stale);
    }

    private void releasePlayerAsync(SurfaceTexture textureToRelease, View viewToRemoveAfterTeardown) {
        generation++;
        started = false;
        prepared = false;
        renderingStarted = false;
        renderedFrames = 0;
        framesAtReveal = 0;
        surfaceProgress.reset();
        cancelWatchdogs();
        setReady(false);
        if (!mediaExecutor.isShutdown()) {
            mediaExecutor.execute(() -> {
                teardownOnWorker(textureToRelease);
                if (viewToRemoveAfterTeardown != null) {
                    post(() -> {
                        if (viewToRemoveAfterTeardown != activeChild
                                && viewToRemoveAfterTeardown.getParent() == LiveWallpaperView.this) {
                            removeView(viewToRemoveAfterTeardown);
                        }
                    });
                }
            });
        } else {
            if (viewToRemoveAfterTeardown != null
                    && viewToRemoveAfterTeardown != activeChild
                    && viewToRemoveAfterTeardown.getParent() == this) {
                removeView(viewToRemoveAfterTeardown);
            }
            safeReleaseSurfaceTexture(textureToRelease);
        }
    }

    private void teardownOnWorker(SurfaceTexture textureToRelease) {
        MediaPlayer mp = activePlayer;
        Surface surf = ownedSurface;
        activePlayer = null;
        ownedSurface = null;
        safeRelease(mp, surf, textureToRelease);
    }

    private static void safeReleaseSurfaceTexture(SurfaceTexture st) {
        if (st != null) {
            try { st.release(); } catch (RuntimeException ignored) {}
        }
    }

    private static void safeRelease(MediaPlayer mp, Surface surf, SurfaceTexture textureToRelease) {
        if (mp != null) {
            try { mp.setOnPreparedListener(null); } catch (RuntimeException ignored) {}
            try { mp.setOnInfoListener(null); } catch (RuntimeException ignored) {}
            try { mp.setOnCompletionListener(null); } catch (RuntimeException ignored) {}
            try { mp.setOnErrorListener(null); } catch (RuntimeException ignored) {}
            try { mp.setOnVideoSizeChangedListener(null); } catch (RuntimeException ignored) {}
            // Stop and reset BEFORE release (never setSurface(null) while playing, which deadlocks Xiaomi MiPlayer/libvlc vout).
            try { mp.stop(); } catch (RuntimeException ignored) {}
            try { mp.reset(); } catch (RuntimeException ignored) {}
            try { mp.release(); } catch (RuntimeException ignored) {}
        }
        if (surf != null) {
            try { surf.release(); } catch (RuntimeException ignored) {}
        }
        safeReleaseSurfaceTexture(textureToRelease);
    }

    private void updateVideoScale() {
        int vw = getWidth(), vh = getHeight();
        if (vw <= 0 || vh <= 0 || videoWidth <= 0 || videoHeight <= 0) return;
        if (activeBackend == LiveWallpaperPolicy.Backend.TEXTURE && textureChild != null) {
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
            textureChild.setTransform(m);
            return;
        }
        if (activeBackend == LiveWallpaperPolicy.Backend.SURFACE && surfaceChild != null) {
            int[] crop = LiveWallpaperPolicy.centerCropSize(vw, vh, videoWidth, videoHeight);
            LayoutParams lp = (LayoutParams) surfaceChild.getLayoutParams();
            if (lp == null || lp.width != crop[0] || lp.height != crop[1] || lp.gravity != Gravity.CENTER) {
                surfaceChild.setLayoutParams(new LayoutParams(crop[0], crop[1], Gravity.CENTER));
            }
        }
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateVideoScale();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (shouldRun()) syncPlaybackState();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        syncPlaybackState();
    }

    @Override protected void onDetachedFromWindow() {
        stopAndUnmountSurface();
        super.onDetachedFromWindow();
    }
}
