// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.remoteprobe;

public final class SessionPolicyChecks {
    static int count;
    static class Clock implements SessionPolicy.Clock { long time=1000; public long now(){return time;} }
    static class F {
        final Clock clock=new Clock(); final SessionPolicy p=new SessionPolicy(clock);
        String sid;
        void active(){String t=p.openWindow(); eq(p.pair("socket1",t),"PENDING"); ok(p.approve("socket1"));sid=p.grant("socket1");}
        String cmd(long seq,String verb,String arg){return p.command("socket1",sid,seq,verb,arg);}
        String nonce(long seq){return cmd(seq,"SNAPSHOT","").split(" ")[1];}
    }
    static void ok(boolean value){count++;if(!value)throw new AssertionError("assertion "+count);}
    static void eq(Object a,Object b){ok(a.equals(b));}
    static void test(String name,Runnable r){r.run();System.out.println("PASS "+name);}
    public static void main(String[] args){
        test("default closed and no automatic approval",()->{F f=new F();eq(f.p.pair("a",new String(new char[64]).replace("\0", "0")),"PAIR_CLOSED");ok(!f.p.approve("a"));});
        test("wrong ticket and five failure lockout",()->{F f=new F();String t=f.p.openWindow();for(int i=0;i<5;i++)eq(f.p.pair("a","bad"),"BAD_TICKET");eq(f.p.pair("a",t),"PAIR_CLOSED");});
        test("ticket deadline exact",()->{F f=new F();String t=f.p.openWindow();f.clock.time+=120000;eq(f.p.pair("a",t),"PAIR_CLOSED");});
        test("ticket single use and channel-bound approval",()->{F f=new F();String t=f.p.openWindow();eq(f.p.pair("a",t),"PENDING");eq(f.p.pair("b",t),"PAIR_CLOSED");ok(!f.p.approve("b"));ok(f.p.grant("a")==null);ok(f.p.approve("a"));ok(f.p.grant("b")==null);});
        test("approval expiry exact",()->{F f=new F();String t=f.p.openWindow();f.p.pair("a",t);f.clock.time+=30000;ok(!f.p.approve("a"));});
        test("approval cannot extend pairing deadline",()->{F f=new F();String t=f.p.openWindow();f.clock.time+=119999;f.p.pair("a",t);f.clock.time++;ok(!f.p.approve("a"));});
        test("local denial leaves no credential",()->{F f=new F();String t=f.p.openWindow();f.p.pair("a",t);f.p.revoke();ok(!f.p.approve("a"));ok(f.p.grant("a")==null);eq(f.p.pair("a",t),"PAIR_CLOSED");});
        test("wrong credential and foreign connection cannot execute",()->{F f=new F();f.active();eq(f.p.command("socket2",f.sid,1,"PING",""),"UNAUTHORIZED");eq(f.p.command("socket1","bad",1,"PING",""),"UNAUTHORIZED");eq(f.cmd(1,"PING",""),"PONG");});
        test("four directions increment fake counter only",()->{F f=new F();f.active();long seq=0;for(String d:new String[]{"UP","DOWN","LEFT","RIGHT"}){String n=f.nonce(++seq);eq(f.cmd(++seq,"STEP",n+" "+d),"FAKE_APPLIED "+(seq/2));}eq(f.p.fakeCount(),4);});
        test("replay tears down session",()->{F f=new F();f.active();String n=f.nonce(1);eq(f.cmd(1,"STEP",n+" RIGHT"),"REPLAY");eq(f.p.fakeCount(),0);ok(f.p.grant("socket1")==null);});
        test("out of order seq tears down session",()->{F f=new F();f.active();eq(f.cmd(2,"PING",""),"REPLAY");});
        test("challenge consumed once",()->{F f=new F();f.active();String n=f.nonce(1);eq(f.cmd(2,"STEP",n+" RIGHT"),"FAKE_APPLIED 1");eq(f.cmd(3,"STEP",n+" RIGHT"),"STALE_CHALLENGE");eq(f.p.fakeCount(),1);});
        test("challenge expires at 1000ms not when job runs",()->{F f=new F();f.active();String n=f.nonce(1);f.clock.time+=1000;eq(f.cmd(2,"STEP",n+" RIGHT"),"STALE_CHALLENGE");});
        test("replacement snapshot cancels old challenge",()->{F f=new F();f.active();String n=f.nonce(1);f.nonce(2);eq(f.cmd(3,"STEP",n+" RIGHT"),"STALE_CHALLENGE");});
        test("idle timeout exact",()->{F f=new F();f.active();f.clock.time+=15000;eq(f.cmd(1,"PING",""),"UNAUTHORIZED");});
        test("session absolute deadline despite heartbeats",()->{F f=new F();f.active();for(int i=1;i<30;i++){f.clock.time+=10000;eq(f.cmd(i,"PING",""),"PONG");}f.clock.time+=10000;eq(f.cmd(30,"PING",""),"UNAUTHORIZED");});
        test("disconnect revokes credential",()->{F f=new F();f.active();String n=f.nonce(1);f.p.disconnect("socket1");eq(f.cmd(2,"STEP",n+" RIGHT"),"UNAUTHORIZED");});
        test("foreign disconnect cannot revoke owner",()->{F f=new F();f.active();f.p.disconnect("socket2");eq(f.cmd(1,"PING",""),"PONG");});
        test("local revoke closes immediately",()->{F f=new F();f.active();f.p.revoke();eq(f.cmd(1,"PING",""),"UNAUTHORIZED");});
        test("new window invalidates previous credential",()->{F f=new F();f.active();String old=f.sid;f.active();eq(f.p.command("socket1",old,1,"PING",""),"UNAUTHORIZED");});
        test("no arbitrary input and no approval command",()->{F f=new F();f.active();eq(f.cmd(1,"APPROVE",""),"BAD_COMMAND");eq(f.cmd(2,"STEP","keyevent HOME"),"BAD_COMMAND");eq(f.p.fakeCount(),0);});
        test("rate limit and explicit close",()->{F f=new F();f.active();for(int i=1;i<=10;i++)eq(f.cmd(i,"PING",""),"PONG");eq(f.cmd(11,"PING",""),"RATE_LIMIT");f.active();eq(f.cmd(1,"CLOSE",""),"CLOSED");ok(f.p.grant("socket1")==null);});
        test("clock rollback revokes",()->{F f=new F();f.active();f.clock.time--;try{f.cmd(1,"PING","");throw new AssertionError();}catch(IllegalStateException expected){}f.clock.time+=2;eq(f.cmd(1,"PING",""),"UNAUTHORIZED");});
        test("parallel pairing consumes ticket once",()->{F f=new F();String ticket=f.p.openWindow();String[] results=new String[2];Thread a=new Thread(()->results[0]=f.p.pair("a",ticket));Thread b=new Thread(()->results[1]=f.p.pair("b",ticket));a.start();b.start();try{a.join();b.join();}catch(InterruptedException e){throw new AssertionError(e);}ok(!results[0].equals(results[1]));});
        System.out.println("PASS 24 policy scenarios; assertions="+count+"; no real TV approval simulated as evidence");
    }
}
