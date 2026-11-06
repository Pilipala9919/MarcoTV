// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.os.SystemClock;
import java.net.InetAddress;
import java.util.concurrent.*;

/** Host-only fixture. stdin/synthetic approval are never in the app source set. */
public final class FocusTransportFixture implements FocusTransport.Events, FocusSession.Surface {
    final ExecutorService ui = Executors.newSingleThreadExecutor();
    final String mode;
    MarcoFocusController ctrl;
    FocusTransportFixture(String mode){ this.mode=mode; }
    public void pending(FocusTransport owner,String channel,String peer){
        ui.execute(()->{
            if (mode.equals("deny")) { owner.close(); return; }
            ctrl=new MarcoFocusController(SystemClock::elapsedRealtime);
            ctrl.replaceLayout(new int[]{3,2},new int[]{0,0},0);
            for(MarcoFocusController.Blocker b:MarcoFocusController.Blocker.values()) ctrl.setBlocked(b,false);
            ctrl.beginAuthorizedLease(300000); owner.policy.approve(channel);
        });
    }
    public void frame(FocusTransport owner,String channel,String[] parts,long received){
        ui.execute(()->{
            if(parts[2].equals("STEP") && mode.equals("queue")){
                try { Thread.sleep(1250); } catch(InterruptedException e){throw new AssertionError(e);}
            }
            String result=owner.policy.frame(channel,parts[0],Long.parseLong(parts[1]),parts[2],
                    parts.length==4?parts[3]:"",received,this);
            owner.reply(result,owner.policy.state()==FocusSession.State.CLOSED);
            if(parts[2].equals("STEP")) System.out.println("STEP "+result.split(" ")[0]+" "+ctrl.snapshot().column);
        });
    }
    public MarcoFocusController.Snapshot snapshot(){ return ctrl.snapshot(); }
    public MarcoFocusController.Result move(MarcoFocusController.Snapshot s,MarcoFocusController.Direction d,long end){
        return ctrl.execute(ctrl.prepareUntil(s,d,end));
    }
    public void ended(FocusTransport owner){}
    public static void main(String[] args)throws Exception{
        FocusTransportFixture fixture=new FocusTransportFixture(args[2]);
        FocusSession policy=new FocusSession(SystemClock::elapsedRealtime);
        String ticket=policy.openWindow();
        FocusTransport server=new FocusTransport(new FocusIdentity(args[0]),InetAddress.getByName(args[1]),policy,fixture);
        System.out.println(server.listener.getLocalPort()+" "+ticket); server.start();
        System.in.read(); server.close(); fixture.ui.shutdown();
    }
}
