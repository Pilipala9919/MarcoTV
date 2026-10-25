// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

/** A completed pair resets, so a third press starts a new single press. */
final class HomePressTracker {
    private long previous=-1;
    boolean press(long now,int interval){
        boolean twice=previous>=0&&now>=previous&&now-previous<=interval;
        previous=twice?-1:now;return twice;
    }
    void reset(){previous=-1;}
}
