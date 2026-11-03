// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** At most one running and one latest pending read. Callers still guard result ownership. */
final class LatestTaskQueue {
    private final ThreadPoolExecutor worker;
    private boolean closed;
    LatestTaskQueue(String name){
        worker=new ThreadPoolExecutor(0,1,15,TimeUnit.SECONDS,new LinkedBlockingQueue<Runnable>(1),r->{
            Thread thread=new Thread(r,name);
            thread.setDaemon(true);
            return thread;
        });
    }
    synchronized void execute(Runnable task){
        if(closed)return;
        worker.getQueue().clear();
        worker.execute(task);
    }
    synchronized void cancelPending(){worker.getQueue().clear();}
    synchronized void close(){closed=true;worker.shutdownNow();}
}
