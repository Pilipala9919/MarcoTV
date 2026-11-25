// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

/**
 * Deterministic volume long-press repeat compensator for TaskBridgeServer (3A mode).
 *
 * <p>Short presses (< initialDelayMs) produce zero synthetic adjustments so the system handles
 * them natively (1 step). Continuous holds begin stepping at {@code initialDelayMs} (default 500 ms)
 * every {@code repeatIntervalMs} (default 55 ms), stop immediately on key release, other key press,
 * native repeat detection, bridge disconnect, input device EOF/disconnect, screen off, mode switch,
 * or safety ceiling.
 */
final class VolumeRepeatEngine {
    static final int EV_KEY = 1;
    static final int KEY_VOLUMEDOWN = 114;
    static final int KEY_VOLUMEUP = 115;

    static final long DEFAULT_INITIAL_DELAY_MS = 500L;
    static final long DEFAULT_REPEAT_INTERVAL_MS = 55L;
    static final long DEFAULT_BASELINE_SAMPLE_MS = 340L;
    static final int DEFAULT_MAX_STEPS = 120;
    static final long DEFAULT_MAX_HOLD_MS = 8000L;

    interface TaskHandle {
        void cancel();
    }

    interface Scheduler {
        TaskHandle schedule(Runnable task, long delayMs);
    }

    interface Callbacks {
        boolean isEnabled();
        boolean isConnected();
        boolean isInteractive();
        int readStreamVolume();
        int readStreamMaxVolume();
        boolean adjustVolume(int direction);
        long nowMs();
    }

    static final class InputKeyEvent {
        final int type;
        final int code;
        final int value;

        InputKeyEvent(int type, int code, int value) {
            this.type = type;
            this.code = code;
            this.value = value;
        }
    }

    static int eventSize(boolean is64Bit) {
        return is64Bit ? 24 : 16;
    }

    static InputKeyEvent parseInputEvent(byte[] buf, boolean is64Bit) {
        int size = eventSize(is64Bit);
        if (buf == null || buf.length < size) return null;
        int off = is64Bit ? 16 : 8;
        int type = (buf[off] & 0xFF) | ((buf[off + 1] & 0xFF) << 8);
        int code = (buf[off + 2] & 0xFF) | ((buf[off + 3] & 0xFF) << 8);
        int value = (buf[off + 4] & 0xFF)
                | ((buf[off + 5] & 0xFF) << 8)
                | ((buf[off + 6] & 0xFF) << 16)
                | ((buf[off + 7] & 0xFF) << 24);
        return new InputKeyEvent(type, code, value);
    }

    private final Scheduler scheduler;
    private final Callbacks callbacks;
    private final long initialDelayMs;
    private final long repeatIntervalMs;
    private final long baselineSampleMs;
    private final int maxSteps;
    private final long maxHoldMs;

    private final Object lock = new Object();
    private long generation;
    private String activeDevice = "";
    private int activeCode;
    private int activeDirection;
    private long holdStartMs;
    private int volAtDown = -1;
    private int baselineVol = -1;
    private boolean baselineSampled;
    private int inferredStep;
    private boolean stepKnownFromDown;
    private int stepsIssued;
    private boolean nativeRepeatDetected;
    private TaskHandle baselineTask;
    private TaskHandle repeatTask;

    private long totalAdjustments;
    private String lastStopReason = "idle";

    VolumeRepeatEngine(Scheduler scheduler, Callbacks callbacks) {
        this(scheduler, callbacks, DEFAULT_INITIAL_DELAY_MS, DEFAULT_REPEAT_INTERVAL_MS,
                DEFAULT_BASELINE_SAMPLE_MS, DEFAULT_MAX_STEPS, DEFAULT_MAX_HOLD_MS);
    }

    VolumeRepeatEngine(Scheduler scheduler, Callbacks callbacks,
                       long initialDelayMs, long repeatIntervalMs,
                       long baselineSampleMs, int maxSteps, long maxHoldMs) {
        this.scheduler = scheduler;
        this.callbacks = callbacks;
        this.initialDelayMs = initialDelayMs;
        this.repeatIntervalMs = repeatIntervalMs;
        this.baselineSampleMs = Math.min(baselineSampleMs, Math.max(50L, initialDelayMs - 50L));
        this.maxSteps = maxSteps;
        this.maxHoldMs = maxHoldMs;
    }

    void onKeyEvent(String deviceId, int type, int code, int value) {
        if (type != EV_KEY) return;
        if (code != KEY_VOLUMEUP && code != KEY_VOLUMEDOWN) {
            if (value == 1) cancelActive("other_key");
            return;
        }
        int dir = code == KEY_VOLUMEUP ? 1 : -1;
        if (value == 0) {
            synchronized (lock) {
                if (activeCode == code && (activeDevice.isEmpty() || activeDevice.equals(deviceId))) {
                    cancelLocked("key_up");
                }
            }
            return;
        }
        if (value == 2) {
            // Native hardware/kernel repeat observed: suppress Marco compensation to avoid double-stepping.
            synchronized (lock) {
                if (activeCode == code) {
                    nativeRepeatDetected = true;
                    cancelLocked("kernel_repeat");
                }
            }
            return;
        }
        if (value != 1) return;

        if (!callbacks.isEnabled() || !callbacks.isConnected() || !callbacks.isInteractive()) {
            cancelActive("precondition_off");
            return;
        }
        int downVol = safeReadVolume();
        synchronized (lock) {
            cancelLocked("restarted");
            final long gen = ++generation;
            activeDevice = deviceId == null ? "" : deviceId;
            activeCode = code;
            activeDirection = dir;
            holdStartMs = callbacks.nowMs();
            volAtDown = downVol;
            baselineVol = downVol;
            baselineSampled = false;
            inferredStep = 0;
            stepKnownFromDown = false;
            stepsIssued = 0;
            nativeRepeatDetected = false;
            baselineTask = scheduler.schedule(() -> sampleBaseline(gen), baselineSampleMs);
            repeatTask = scheduler.schedule(() -> onRepeatTick(gen), initialDelayMs);
        }
    }

    void onDeviceDisconnected(String deviceId) {
        synchronized (lock) {
            if (activeCode != 0 && (deviceId == null || activeDevice.equals(deviceId))) {
                cancelLocked("device_disconnected");
            }
        }
    }

    void cancelActive(String reason) {
        synchronized (lock) {
            if (activeCode != 0 || baselineTask != null || repeatTask != null) {
                cancelLocked(reason);
            }
        }
    }

    boolean isHolding() {
        synchronized (lock) {
            return activeCode != 0;
        }
    }

    long totalAdjustments() {
        synchronized (lock) {
            return totalAdjustments;
        }
    }

    String lastStopReason() {
        synchronized (lock) {
            return lastStopReason;
        }
    }

    private void sampleBaseline(long gen) {
        int cur = safeReadVolume();
        synchronized (lock) {
            if (gen != generation || activeCode == 0) return;
            baselineTask = null;
            baselineVol = cur;
            baselineSampled = cur >= 0;
            if (cur >= 0 && volAtDown >= 0) {
                int diff = (cur - volAtDown) * activeDirection;
                if (diff > 0) {
                    inferredStep = diff;
                    stepKnownFromDown = true;
                }
            }
        }
    }

    private void onRepeatTick(long gen) {
        int dir;
        synchronized (lock) {
            if (gen != generation || activeCode == 0) return;
            repeatTask = null;
            if (nativeRepeatDetected) {
                cancelLocked("native_repeat");
                return;
            }
            if (!callbacks.isEnabled()) {
                cancelLocked("mode_disabled");
                return;
            }
            if (!callbacks.isConnected()) {
                cancelLocked("bridge_disconnected");
                return;
            }
            if (!callbacks.isInteractive()) {
                cancelLocked("screen_off");
                return;
            }
            long now = callbacks.nowMs();
            if (stepsIssued >= maxSteps || (now - holdStartMs) >= maxHoldMs) {
                cancelLocked("safety_timeout");
                return;
            }
            dir = activeDirection;
        }

        // Duplicate-adjustment guard: detect if the system's own InputDispatcher is already repeating.
        int curVol = safeReadVolume();
        int maxVol = safeReadMaxVolume();
        synchronized (lock) {
            if (gen != generation || activeCode == 0) return;
            boolean saturated = (dir > 0 && maxVol > 0 && curVol >= maxVol) || (dir < 0 && curVol == 0);
            if (baselineSampled && curVol >= 0 && !saturated) {
                int moved = (curVol - baselineVol) * dir;
                if (stepsIssued == 0) {
                    if (moved > 0) {
                        nativeRepeatDetected = true;
                        cancelLocked("native_repeat_detected");
                        return;
                    }
                } else {
                    if (inferredStep <= 0 && moved > 0) {
                        inferredStep = Math.max(1, moved / stepsIssued);
                    }
                    if (inferredStep > 0) {
                        int maxExpected = (stepKnownFromDown ? stepsIssued : stepsIssued + 1) * inferredStep;
                        if (moved > maxExpected) {
                            nativeRepeatDetected = true;
                            cancelLocked("external_volume_advance");
                            return;
                        }
                    }
                }
            }
        }

        boolean adjusted = false;
        try {
            adjusted = callbacks.adjustVolume(dir);
        } catch (Throwable ignored) { }

        synchronized (lock) {
            if (gen != generation || activeCode == 0) return;
            if (!adjusted) {
                cancelLocked("audio_adjust_failed");
                return;
            }
            stepsIssued++;
            totalAdjustments++;
            if (stepsIssued >= maxSteps || (callbacks.nowMs() - holdStartMs) >= maxHoldMs) {
                cancelLocked("safety_timeout");
                return;
            }
            repeatTask = scheduler.schedule(() -> onRepeatTick(gen), repeatIntervalMs);
        }
    }

    private int safeReadVolume() {
        try {
            return callbacks.readStreamVolume();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private int safeReadMaxVolume() {
        try {
            return callbacks.readStreamMaxVolume();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private void cancelLocked(String reason) {
        generation++;
        if (baselineTask != null) {
            baselineTask.cancel();
            baselineTask = null;
        }
        if (repeatTask != null) {
            repeatTask.cancel();
            repeatTask = null;
        }
        activeDevice = "";
        activeCode = 0;
        activeDirection = 0;
        stepsIssued = 0;
        lastStopReason = reason;
    }
}
