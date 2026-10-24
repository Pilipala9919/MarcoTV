// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import java.util.*;

/** Stable item IDs keep persisted ordering independent from translated/stateful labels. */
final class MenuOrder {
    static ArrayList<Integer> resolve(String saved,String[] ids){
        ArrayList<Integer> result=new ArrayList<>();
        for(String id:saved.split("\\|"))for(int i=0;i<ids.length;i++)
            if(ids[i].equals(id)&&!result.contains(i))result.add(i);
        for(int i=0;i<ids.length;i++)if(!result.contains(i))result.add(i);
        return result;
    }
    static String encode(List<Integer> order,String[] ids){
        StringBuilder out=new StringBuilder();
        for(int i:order){if(out.length()>0)out.append('|');out.append(ids[i]);}
        return out.toString();
    }
}
