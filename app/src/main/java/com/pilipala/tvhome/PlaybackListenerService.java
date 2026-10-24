// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.Intent;
import android.service.notification.NotificationListenerService;

/** Access token for active media sessions. Ordinary notifications are never read or stored. */
public final class PlaybackListenerService extends NotificationListenerService {
    static final String CHANGED="com.pilipala.tvhome.PLAYBACK_ACCESS_CHANGED";
    @Override public void onListenerConnected() { sendBroadcast(new Intent(CHANGED).setPackage(getPackageName())); }
    @Override public void onListenerDisconnected() { sendBroadcast(new Intent(CHANGED).setPackage(getPackageName())); }
}
