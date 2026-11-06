// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

public final class FocusSessionChecks implements FocusSession.Surface {
    long now = 100;
    final FocusSession security = new FocusSession(() -> now);
    final MarcoFocusController ctrl = new MarcoFocusController(() -> now);
    final String channel = "first";
    String sid;
    long seq;
    FocusSessionChecks() {
        ctrl.replaceLayout(new int[]{2,0,3}, new int[]{0,-1,1},0);
        for (MarcoFocusController.Blocker b : MarcoFocusController.Blocker.values()) ctrl.setBlocked(b,false);
        String ticket = security.openWindow();
        equal("PENDING", security.pair(channel,ticket));
        equal(false,security.approve("foreign"));
        equal(true,security.approve(channel));
        sid=security.grant(channel);
        ctrl.beginAuthorizedLease(300000);
    }
    public MarcoFocusController.Snapshot snapshot(){ return ctrl.snapshot(); }
    public MarcoFocusController.Result move(MarcoFocusController.Snapshot s,MarcoFocusController.Direction d,long end){
        return ctrl.execute(ctrl.prepareUntil(s,d,end));
    }
    String req(String verb,String arg){ return security.frame(channel,sid,++seq,verb,arg,now,this); }
    String nonce(){ return req("STATE","").split(" ")[1]; }
    static void equal(Object expected,Object actual){
        if(!expected.equals(actual)) throw new AssertionError(expected+" vs "+actual);
    }
    static void status(String expected,String response){ equal(expected,response.split(" ")[0]); }
    public static void main(String[] args) {
        FocusSessionChecks f=new FocusSessionChecks();
        for(String move:new String[]{"RIGHT","DOWN","LEFT","UP"}) status("MOVED",f.req("STEP",f.nonce()+" "+move));
        equal(1,f.ctrl.snapshot().column);
        status("NO_CHANGE",f.req("STEP",f.nonce()+" RIGHT"));
        f.now+=1000;
        String n=f.nonce();
        f.ctrl.localFocus(0,new int[]{1,-1,0}); // boundary press, unchanged coordinates
        status("STALE_FOCUS",f.req("STEP",n+" LEFT"));
        n=f.nonce(); f.ctrl.replaceLayout(new int[]{2,0,3},new int[]{1,-1,0},0);
        status("STALE_LAYOUT",f.req("STEP",n+" LEFT"));
        n=f.nonce(); f.now+=1000;
        status("STALE_CHALLENGE",f.req("STEP",n+" LEFT"));
        status("COMMAND_EXPIRED",f.security.frame(f.channel,f.sid,++f.seq,"STATE","",f.now-1000,f));
        n=f.nonce(); status("MOVED",f.req("STEP",n+" LEFT"));
        status("STALE_CHALLENGE",f.req("STEP",n+" RIGHT"));
        status("REPLAY",f.security.frame(f.channel,f.sid,f.seq,"PING","",f.now,f));
        status("UNAUTHORIZED",f.req("PING",""));
        for(MarcoFocusController.Blocker b:MarcoFocusController.Blocker.values()){
            f=new FocusSessionChecks(); n=f.nonce(); f.ctrl.setBlocked(b,true);
            status("STALE_SESSION",f.req("STEP",n+" RIGHT")); equal(0,f.ctrl.snapshot().column);
        }
        f=new FocusSessionChecks(); n=f.nonce(); f.ctrl.destroy();
        equal(MarcoFocusController.Status.DESTROYED,f.ctrl.snapshot().availability);
        f.security.disconnect(f.channel); status("UNAUTHORIZED",f.req("STEP",n+" RIGHT"));
        f=new FocusSessionChecks(); n=f.nonce();
        status("UNAUTHORIZED",f.security.frame("foreign",f.sid,2,"STEP",n+" RIGHT",f.now,f));
        f.now+=15000; status("UNAUTHORIZED",f.req("STEP",n+" RIGHT"));
        f=new FocusSessionChecks();
        for(int i=0;i<59;i++){ f.now+=5000; equal("PONG",f.req("PING","")); }
        f.now+=5000; status("UNAUTHORIZED",f.req("PING",""));
        System.out.println("PASS directions, boundary, original revisions, timeout, replay, single use, blockers, destroy, disconnect, owner, idle and absolute expiry");
    }
}
