// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

public final class MotionSpringChecks {
    private static int checks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++; System.out.println("PASS " + name);
    }
    private static void settle(MotionSpring motion) { for (int i = 0; i < 180; i++) motion.step(1f / 60); }
    public static void main(String[] args) {
        MotionSpring interrupted = new MotionSpring(0, 22, .001f);
        interrupted.target(1); for (int i = 0; i < 5; i++) interrupted.step(1f / 60);
        float position = interrupted.value, velocity = interrupted.velocity;
        interrupted.target(0);
        check(interrupted.value == position && interrupted.velocity == velocity, "reversing keeps current position and velocity");
        settle(interrupted); check(interrupted.value == 0 && interrupted.velocity == 0, "reversed motion settles exactly");

        MotionSpring at30 = new MotionSpring(0, 18, .000001f), at60 = new MotionSpring(0, 18, .000001f);
        at30.target(1); at60.target(1);
        for (int i = 0; i < 9; i++) at30.step(1f / 30);
        for (int i = 0; i < 18; i++) at60.step(1f / 60);
        check(Math.abs(at30.value - at60.value) < .00001f, "motion timing agrees at 30 and 60 frames per second");

        MotionSpring outgoing = new MotionSpring(1, 22, .001f), incoming = new MotionSpring(0, 22, .001f);
        outgoing.target(0); incoming.target(1);
        outgoing.step(1f / 60); incoming.step(1f / 60);
        check(outgoing.value > .9f && incoming.value > 0 && incoming.value < .1f, "both cards begin gradually without a scale jump");
        check(Math.abs(outgoing.value + incoming.value - 1) < .00001f, "old and new focus emphasis crossfade together");

        MotionSpring repeated = new MotionSpring(0, 22, .001f);
        boolean bounded = true;
        for (int i = 0; i < 400; i++) {
            if (i % 4 == 0) repeated.target(i % 8 == 0 ? 1 : 0);
            repeated.step(.01f); bounded &= repeated.value >= -.001f && repeated.value <= 1.001f;
        }
        check(bounded, "rapid reversals stay bounded");
        repeated.target(1); settle(repeated);
        check(repeated.value == 1 && !repeated.step(1f / 60), "final focus settles and stops scheduling frames");

        MotionSpring scroll = new MotionSpring(0, 18, .05f); scroll.target(350); scroll.step(.05f);
        position = scroll.value; velocity = scroll.velocity; scroll.target(700);
        check(scroll.value == position && scroll.velocity == velocity, "carousel retarget keeps scroll continuity");
        settle(scroll); check(scroll.value == 700, "carousel reaches the final requested position");
        scroll.target(0); scroll.step(1f);
        check(Float.isFinite(scroll.value) && scroll.value >= 0, "a delayed frame does not destabilize the spring");
        System.out.println(checks + " motion checks passed");
    }
}
