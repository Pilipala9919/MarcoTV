// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.remoteprobe;

import java.util.concurrent.atomic.AtomicBoolean;

/** At most one cleanup worker per server. Cancellation is synchronous, I/O is not. */
final class BackgroundClose {
    private final Runnable cancelAuthorization, closeTransport;
    private final AtomicBoolean started = new AtomicBoolean();
    BackgroundClose(Runnable cancelAuthorization, Runnable closeTransport) {
        this.cancelAuthorization = cancelAuthorization;
        this.closeTransport = closeTransport;
    }
    void request() {
        cancelAuthorization.run();
        if (started.compareAndSet(false, true))
            new Thread(closeTransport, "marco-tls-close").start();
    }
}
