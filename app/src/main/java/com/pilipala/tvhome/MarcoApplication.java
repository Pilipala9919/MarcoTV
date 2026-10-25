// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

public final class MarcoApplication extends android.app.Application {
    @Override public void onCreate(){super.onCreate();Diagnostics.init(this);}
}
