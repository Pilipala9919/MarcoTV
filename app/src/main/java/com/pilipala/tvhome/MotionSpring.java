// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

/** Exact critically damped spring: a new target preserves both position and velocity. */
final class MotionSpring {
    float value, velocity, target;
    private final float frequency, tolerance;

    MotionSpring(float initial, float frequency, float tolerance) {
        this.value = this.target = initial;
        this.frequency = frequency;
        this.tolerance = tolerance;
    }

    void target(float next) { target = next; }

    void snap(float next) { value = target = next; velocity = 0; }

    boolean step(float seconds) {
        if (seconds <= 0) return moving();
        double offset = value - target;
        double coupling = velocity + frequency * offset;
        double decay = Math.exp(-frequency * seconds);
        value = (float) (target + (offset + coupling * seconds) * decay);
        velocity = (float) ((velocity - frequency * coupling * seconds) * decay);
        if (!moving()) { snap(target); return false; }
        return true;
    }

    private boolean moving() {
        return Math.abs(value - target) > tolerance || Math.abs(velocity) > tolerance * frequency;
    }
}
