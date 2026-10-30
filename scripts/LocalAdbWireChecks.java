// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.security.*;
import java.security.interfaces.*;
import java.util.*;
import java.util.concurrent.*;

/** Runs the production transport against bounded loopback fixtures, never a television. */
public final class LocalAdbWireChecks {
    static int passed;
    static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);passed++;System.out.println("PASS "+name);}
    static byte[] exact(InputStream in,int n)throws IOException{byte[] b=new byte[n];new DataInputStream(in).readFully(b);return b;}
    static int[] receive(Socket s)throws IOException{
        ByteBuffer b=ByteBuffer.wrap(exact(s.getInputStream(),24)).order(ByteOrder.LITTLE_ENDIAN);
        int[] h=new int[6];for(int i=0;i<6;i++)h[i]=b.getInt();exact(s.getInputStream(),h[3]);return h;
    }
    static void send(Socket s,int cmd,int a,int b,byte[] data)throws IOException{
        int sum=0;for(byte x:data)sum+=x&255;
        s.getOutputStream().write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(cmd).putInt(a).putInt(b).putInt(data.length).putInt(sum).putInt(~cmd).array());
        s.getOutputStream().write(data);s.getOutputStream().flush();
    }
    interface Peer {void run(Socket s)throws Exception;}
    static void fixture(KeyPair key,Peer peer,boolean reject)throws Exception{
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());LocalAdbWire wire=new LocalAdbWire()){
            ExecutorService pool=Executors.newSingleThreadExecutor();
            Future<?> f=pool.submit(()->{try(Socket s=server.accept()){s.setSoTimeout(3000);receive(s);peer.run(s);}catch(Exception e){throw new RuntimeException(e);}});
            try{
                boolean failed=false;
                try{wire.connect("127.0.0.1",server.getLocalPort(),key,m->{});if(!reject)check("2000".equals(wire.shell("id -u")),"shell response and close handshake");}
                catch(IOException e){failed=true;}
                check(failed==reject,reject?"malformed handshake rejected":"valid connection accepted");
                f.get(5,TimeUnit.SECONDS);
            }finally{pool.shutdownNow();}
        }
    }
    public static void main(String[] args)throws Exception{
        File dir=new File(args[0]);dir.mkdirs();KeyPair key=LocalAdbWire.key(dir);
        check(Arrays.equals(key.getPublic().getEncoded(),LocalAdbWire.key(dir).getPublic().getEncoded()),"private key persists locally");
        check(LocalAdbWire.publicKey((RSAPublicKey)key.getPublic()).length==524,"ADB public key format");
        byte[] message="test challenge".getBytes("UTF-8");Signature sig=Signature.getInstance("SHA1withRSA");sig.initVerify(key.getPublic());sig.update(message);
        check(sig.verify(LocalAdbWire.sign(key,MessageDigest.getInstance("SHA-1").digest(message))),"ADB challenge signature");
        boolean invalid=false;try{LocalAdbWire.sign(key,new byte[19]);}catch(IOException e){invalid=true;}check(invalid,"invalid challenge length rejected");
        for(String host:new String[]{"localhost","8.8.8.8","::1","example.com","127.0.0.1;exit"}){
            boolean denied=false;try{LocalAdbWire.address(host);}catch(IOException e){denied=true;}check(denied,"nonliteral loopback target rejected");
        }
        fixture(key,s->{send(s,LocalAdbWire.CNXN,0x01000000,4096,new byte[0]);int[] open=receive(s);send(s,LocalAdbWire.OKAY,7,open[1],new byte[0]);send(s,LocalAdbWire.WRTE,7,open[1],LocalAdbWire.utf("2000\n"));receive(s);send(s,LocalAdbWire.CLSE,7,open[1],new byte[0]);receive(s);},false);
        fixture(key,s->{send(s,LocalAdbWire.AUTH,1,0,new byte[19]);},true);
        fixture(key,s->{s.getOutputStream().write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(LocalAdbWire.CNXN).putInt(0).putInt(0).putInt(1024*1024+1).putInt(0).putInt(~LocalAdbWire.CNXN).array());},true);
        fixture(key,s->{s.getOutputStream().write(new byte[24]);},true);
        fixture(key,s->{s.getOutputStream().write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(LocalAdbWire.CNXN).putInt(0).putInt(0).putInt(1).putInt(99).putInt(~LocalAdbWire.CNXN).array());s.getOutputStream().write(1);},true);
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());LocalAdbWire wire=new LocalAdbWire()){
            ExecutorService pool=Executors.newSingleThreadExecutor();CountDownLatch waiting=new CountDownLatch(1);
            Future<?> job=pool.submit(()->{try{wire.connect("127.0.0.1",server.getLocalPort(),key,m->{});throw new AssertionError("unexpected connect");}catch(IOException expected){}catch(Exception e){throw new RuntimeException(e);}});
            try(Socket s=server.accept()){receive(s);long start=System.nanoTime();wire.close();job.get(2,TimeUnit.SECONDS);check(System.nanoTime()-start<2000000000L,"cancel interrupts pending authorization");}
            finally{pool.shutdownNow();}
        }
        System.out.println("OK "+passed+" local ADB transport checks");
    }
}
