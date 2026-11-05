// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.remoteprobe;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class BackgroundCloseChecks {
    public static void main(String[] args) throws Exception {
        Thread caller = Thread.currentThread();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicInteger ioCalls = new AtomicInteger();
        boolean[] cancelled = {false};
        Throwable[] failure = {null};
        BackgroundClose close = new BackgroundClose(() -> cancelled[0] = true, () -> {
            try {
                if (Thread.currentThread() == caller) throw new AssertionError("TLS I/O on caller/UI thread");
                ioCalls.incrementAndGet(); entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("close blocked caller");
            } catch (Throwable error) { failure[0] = error; }
            finally { done.countDown(); }
        });
        close.request();
        if (!cancelled[0]) throw new AssertionError("authorization not cancelled before return");
        if (!entered.await(2, TimeUnit.SECONDS)) throw new AssertionError("cleanup not scheduled");
        Thread[] racers = new Thread[32];
        for (int i=0;i<racers.length;i++) { racers[i]=new Thread(close::request); racers[i].start(); }
        for (Thread race:racers) race.join();
        if (ioCalls.get()!=1) throw new AssertionError("multiple close workers");
        release.countDown();
        if (!done.await(2, TimeUnit.SECONDS) || failure[0]!=null) throw new AssertionError("cleanup failed",failure[0]);
        close.request();
        if (ioCalls.get()!=1) throw new AssertionError("close not idempotent");
        System.out.println("PASS BackgroundClose: synchronous revoke, off-caller I/O, 32 racing requests, idempotent cleanup");
    }
}
