// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

/**
 * Platform-free reveal, mount/surface timeout, stall detection, loop-aware progress tracking,
 * backend fallback, and center-crop math for live video wallpapers.
 */
final class LiveWallpaperPolicy {
    static final long SURFACE_READY_TIMEOUT_MS = 2000L;
    static final long PREPARE_TIMEOUT_MS = 8000L;
    static final long TEXTURE_STALL_TIMEOUT_MS = 2200L;
    static final long SURFACE_PROGRESS_TIMEOUT_MS = 4500L;
    static final long PROGRESS_POLL_INTERVAL_MS = 250L;
    static final long POST_REVEAL_LIVENESS_MS = 2000L;
    static final int MIN_POST_REVEAL_DELTA_FRAMES = 2;
    static final int SURFACE_REVEAL_PROGRESS_CHANGES = 2;
    static final int SURFACE_STABLE_PROGRESS_CHANGES = 5;
    static final int SURFACE_POST_REVEAL_STALL_POLLS = 8;

    enum Backend {
        TEXTURE,
        SURFACE
    }

    enum WatchdogAction {
        NONE,
        REVEAL,
        FALLBACK_TO_SURFACE,
        FAIL_UNSUPPORTED
    }

    enum StabilityAction {
        CONTINUE_POLLING,
        CONFIRM_PREFERRED,
        FAIL_UNSUPPORTED
    }

    /**
     * Tracks repeated MediaPlayer position changes while tolerating short-clip loop wrap-arounds.
     */
    static final class ProgressTracker {
        private int lastPos = -1;
        private int progressChanges;
        private int loopWraps;
        private int consecutiveUnchanged;
        private boolean sawCompletion;

        void reset() {
            lastPos = -1;
            progressChanges = 0;
            loopWraps = 0;
            consecutiveUnchanged = 0;
            sawCompletion = false;
        }

        void onLoopCompletion() {
            sawCompletion = true;
        }

        boolean observe(boolean playing, int posMs, int durationMs) {
            if (!playing || posMs < 0) {
                consecutiveUnchanged++;
                return false;
            }
            if (lastPos < 0) {
                lastPos = posMs;
                consecutiveUnchanged = 0;
                if (sawCompletion) {
                    sawCompletion = false;
                    loopWraps++;
                    progressChanges++;
                    return true;
                }
                return false;
            }
            if (posMs > lastPos) {
                lastPos = posMs;
                consecutiveUnchanged = 0;
                sawCompletion = false;
                progressChanges++;
                return true;
            }
            boolean wrapped = posMs < lastPos
                    && (sawCompletion
                        || (durationMs > 0 && lastPos >= Math.min(durationMs / 2, 400))
                        || (lastPos - posMs >= 200));
            if (wrapped) {
                lastPos = posMs;
                consecutiveUnchanged = 0;
                sawCompletion = false;
                loopWraps++;
                progressChanges++;
                return true;
            }
            if (sawCompletion) {
                lastPos = posMs;
                consecutiveUnchanged = 0;
                sawCompletion = false;
                loopWraps++;
                progressChanges++;
                return true;
            }
            consecutiveUnchanged++;
            return false;
        }

        int progressChanges() {
            return progressChanges;
        }

        int loopWraps() {
            return loopWraps;
        }

        int consecutiveUnchanged() {
            return consecutiveUnchanged;
        }

        int lastPos() {
            return lastPos;
        }
    }

    private LiveWallpaperPolicy() { }

    static boolean shouldReveal(Backend backend, boolean active, boolean prepared,
                                boolean renderingStarted, int renderedFrames,
                                int surfaceProgressChanges) {
        if (!active || !prepared) return false;
        if (backend == Backend.TEXTURE) {
            return renderedFrames >= 2 || (renderingStarted && renderedFrames >= 1);
        }
        return surfaceProgressChanges >= SURFACE_REVEAL_PROGRESS_CHANGES;
    }

    static WatchdogAction evaluateStartup(Backend backend, boolean active,
                                          boolean surfaceReady, boolean prepared, boolean ready,
                                          boolean renderingStarted, int renderedFrames,
                                          int surfaceProgressChanges,
                                          long mountedAgoMs, long startedAgoMs, long preparedAgoMs,
                                          boolean surfaceFallbackAllowed) {
        if (!active || ready) return WatchdogAction.NONE;
        if (!surfaceReady) {
            if (mountedAgoMs >= SURFACE_READY_TIMEOUT_MS) {
                return backend == Backend.TEXTURE && surfaceFallbackAllowed
                        ? WatchdogAction.FALLBACK_TO_SURFACE
                        : WatchdogAction.FAIL_UNSUPPORTED;
            }
            return WatchdogAction.NONE;
        }
        if (!prepared) {
            if (startedAgoMs >= PREPARE_TIMEOUT_MS) {
                return backend == Backend.TEXTURE && surfaceFallbackAllowed
                        ? WatchdogAction.FALLBACK_TO_SURFACE
                        : WatchdogAction.FAIL_UNSUPPORTED;
            }
            return WatchdogAction.NONE;
        }
        if (shouldReveal(backend, active, true, renderingStarted, renderedFrames, surfaceProgressChanges)) {
            return WatchdogAction.REVEAL;
        }
        if (backend == Backend.TEXTURE) {
            if (preparedAgoMs >= TEXTURE_STALL_TIMEOUT_MS) {
                return surfaceFallbackAllowed
                        ? WatchdogAction.FALLBACK_TO_SURFACE
                        : WatchdogAction.FAIL_UNSUPPORTED;
            }
            return WatchdogAction.NONE;
        }
        if (preparedAgoMs >= SURFACE_PROGRESS_TIMEOUT_MS) {
            return WatchdogAction.FAIL_UNSUPPORTED;
        }
        return WatchdogAction.NONE;
    }

    static WatchdogAction evaluateTexturePostReveal(boolean active, boolean ready,
                                                    int framesAtReveal, int currentFrames,
                                                    int durationMs, boolean surfaceFallbackAllowed) {
        if (!active || !ready) return WatchdogAction.NONE;
        if (durationMs > 0 && durationMs <= 1000) return WatchdogAction.NONE;
        if (currentFrames - framesAtReveal < MIN_POST_REVEAL_DELTA_FRAMES) {
            return surfaceFallbackAllowed
                    ? WatchdogAction.FALLBACK_TO_SURFACE
                    : WatchdogAction.FAIL_UNSUPPORTED;
        }
        return WatchdogAction.NONE;
    }

    static StabilityAction evaluateSurfaceStability(boolean active, boolean ready,
                                                    int progressChanges, int consecutiveUnchanged) {
        if (!active || !ready) return StabilityAction.CONTINUE_POLLING;
        if (progressChanges >= SURFACE_STABLE_PROGRESS_CHANGES) {
            return StabilityAction.CONFIRM_PREFERRED;
        }
        if (consecutiveUnchanged >= SURFACE_POST_REVEAL_STALL_POLLS) {
            return StabilityAction.FAIL_UNSUPPORTED;
        }
        return StabilityAction.CONTINUE_POLLING;
    }

    static boolean shouldFallbackOnError(Backend backend, boolean surfaceFallbackAllowed) {
        return backend == Backend.TEXTURE && surfaceFallbackAllowed;
    }

    static int[] centerCropSize(int viewW, int viewH, int videoW, int videoH) {
        if (viewW <= 0 || viewH <= 0 || videoW <= 0 || videoH <= 0) {
            return new int[]{Math.max(0, viewW), Math.max(0, viewH)};
        }
        float viewAspect = viewW / (float) viewH;
        float videoAspect = videoW / (float) videoH;
        if (videoAspect > viewAspect) {
            int scaledW = Math.max(viewW, Math.round(viewH * videoAspect));
            return new int[]{scaledW, viewH};
        }
        int scaledH = Math.max(viewH, Math.round(viewW / videoAspect));
        return new int[]{viewW, scaledH};
    }
}
