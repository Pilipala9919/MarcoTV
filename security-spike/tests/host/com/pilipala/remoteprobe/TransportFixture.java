// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.remoteprobe;
import java.net.InetAddress;
/** Automated approval is ONLY in this host fixture, never the Android APK. */
public final class TransportFixture {
    public static void main(String[] args) throws Exception {
        SessionPolicy policy=new SessionPolicy(android.os.SystemClock::elapsedRealtime);
        String ticket=policy.openWindow();
        ProbeServer server=new ProbeServer(new DeviceIdentity(args[0]),InetAddress.getByName(args[1]),policy,new ProbeServer.Observer(){
            public void event(String value) { }
            public void pending(String channel,String peer){
                if(args[2].equals("approve"))policy.approve(channel);else policy.revoke();
            }
        },false);
        System.out.println(server.port()+" "+ticket);System.out.flush();
        server.start();
        System.in.read();server.close();
    }
}
