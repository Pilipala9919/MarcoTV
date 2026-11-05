// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package android.os;
/** Host transport-test shim only; never compiled into the Android probe. */
public final class SystemClock {
    public static long elapsedRealtime() { return System.nanoTime()/1_000_000; }
}
