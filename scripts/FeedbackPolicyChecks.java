// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;
import java.util.*;
public class FeedbackPolicyChecks {
    static int n;
    static void check(boolean value){if(!value)throw new AssertionError("check "+n);n++;}
    public static void main(String[] args){
        check(!FeedbackPolicy.due(false,true,100000000,0));
        check(!FeedbackPolicy.due(true,false,100000000,0));
        check(FeedbackPolicy.due(true,true,100000000,0));
        check(!FeedbackPolicy.due(true,true,1000,2000));
        check(!FeedbackPolicy.due(true,true,86400000,1));
        check(FeedbackPolicy.due(true,true,86400001,1));
        check(!FeedbackPolicy.hasFailure(Arrays.asList("HOME_AUTO_NATIVE_SELECTED")));
        check(FeedbackPolicy.hasFailure(Arrays.asList("CRASH")));
        check(FeedbackPolicy.codes("01:00 CRASH\npassword=secret\n01:01 PROCESS_STARTED\n01:02 CRASH").equals(Arrays.asList("CRASH")));
        check(FeedbackPolicy.frames("com.pilipala.tvhome.MainActivity.onCreate:12\nandroid.other.secret:12\ncom.pilipala.tvhome.Main.a:1 password").equals(Arrays.asList("com.pilipala.tvhome.MainActivity.onCreate:12")));
        check(FeedbackPolicy.label("TV\nsecret<>").equals("TVsecret"));
        check(FeedbackPolicy.label(null).equals(""));
        check(FeedbackPolicy.label(String.join("",Collections.nCopies(200,"x"))).length()==100);
        StringBuilder stack=new StringBuilder();for(int i=0;i<100;i++)stack.append("com.pilipala.tvhome.Test.method:").append(i).append('\n');
        check(FeedbackPolicy.frames(stack.toString()).size()==12);
        for(String code:FeedbackPolicy.CODES)check(FeedbackPolicy.known(code));
        System.out.println("Feedback policy checks passed: "+n);
    }
}
