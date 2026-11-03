// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

/** Preserve task identity on refresh, otherwise use the nearest surviving position. */
final class TaskSelection {
    static int resolve(int[] ids,int limit,int taskId,int previous){
        int count=Math.max(0,Math.min(ids.length,limit));
        for(int i=0;i<count;i++)if(ids[i]==taskId)return i;
        return Math.max(0,Math.min(previous,count-1));
    }
}
