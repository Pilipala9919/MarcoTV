// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import java.util.*;

/** Upload allowlist independent of Android and local diagnostic free text. */
final class FeedbackPolicy {
    static final String[] CODES={"CRASH","UI_UNRESPONSIVE_SUSPECTED","PREVIOUS_RUN_INTERRUPTED_UNKNOWN",
            "HOME_AUTO_SETUP_INCOMPLETE","HOME_AUTO_RESTORE_INCOMPLETE","HOME_AUTO_COMPAT_UNCONNECTED",
            "HOME_AUTO_SERVICE_REBIND_REQUESTED","HOME_AUTO_NATIVE_SELECTED","HOME_AUTO_COMPAT_CONNECTED","WAKE_HOME_RESTORED"};
    static boolean known(String s){return Arrays.asList(CODES).contains(s);}
    static String label(String s){
        if(s==null)return "";
        s=s.replaceAll("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}","redacted");
        s=s.replaceAll("[^\\p{L}\\p{N} ._()+,:/-]","");
        return s.substring(0,Math.min(100,s.length()));
    }
    static List<String> codes(String journal){
        LinkedHashSet<String> found=new LinkedHashSet<>();
        for(String line:journal.split("\\n"))for(String word:line.trim().split("\\s+"))if(known(word)){found.remove(word);found.add(word);}
        List<String> result=new ArrayList<>(found);
        return result.subList(Math.max(0,result.size()-20),result.size());
    }
    static List<String> frames(String journal){
        LinkedHashSet<String> result=new LinkedHashSet<>();
        for(String line:journal.split("\\n")){
            String s=line.trim();
            if(s.matches("com\\.pilipala\\.tvhome\\.[A-Za-z0-9_.$]+:[0-9]{1,6}")){result.remove(s);result.add(s);}
        }
        List<String> list=new ArrayList<>(result);
        return list.subList(Math.max(0,list.size()-12),list.size());
    }
    static boolean hasFailure(List<String> codes){
        for(String s:codes)if(s.equals("CRASH")||s.endsWith("SUSPECTED")||s.endsWith("UNKNOWN")||s.endsWith("INCOMPLETE")||s.endsWith("UNCONNECTED"))return true;
        return false;
    }
    static boolean due(boolean consent,boolean recording,long now,long last){
        return consent&&recording&&(last==0||now>=last&&now-last>=86400000L);
    }
}
