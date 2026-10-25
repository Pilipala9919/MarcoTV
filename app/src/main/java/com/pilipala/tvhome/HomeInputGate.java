// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

/** Suppress redirection during power transitions; never consumes power or navigation keys. */
final class HomeInputGate {
    private boolean screenOff;
    private long settleUntil;
    void power(long now){settleUntil=now+1800;}
    void screen(boolean on,long now){screenOff=!on;settleUntil=on?now+700:Long.MAX_VALUE;}
    boolean allow(boolean interactive,long now){return interactive&&!screenOff&&now>=settleUntil;}
}
