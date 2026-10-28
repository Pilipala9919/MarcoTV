// Copyright (C) 2026 Pilipala · SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvinstaller;
import java.io.*;import java.security.*;import java.security.interfaces.*;import java.nio.*;import java.math.*;import java.util.*;
/** Host JVM checks exercise the exact transport used by the Android helper. */
public class WireChecks {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);System.out.println("PASS "+s);}
 public static void main(String[] args)throws Exception{
  File dir=new File(args[0]);dir.mkdirs();KeyPair key=AdbWire.key(dir);byte[] pub=AdbWire.publicKey((RSAPublicKey)key.getPublic());ByteBuffer b=ByteBuffer.wrap(pub).order(ByteOrder.LITTLE_ENDIAN);
  check(pub.length==524&&b.getInt(0)==64&&b.getInt(520)==65537,"public key structure");
  BigInteger mod=BigInteger.ONE.shiftLeft(32),n=((RSAPublicKey)key.getPublic()).getModulus();check(n.multiply(BigInteger.valueOf(b.getInt(4)&0xffffffffL)).add(BigInteger.ONE).mod(mod).signum()==0,"public key inverse");
  byte[] data="test challenge".getBytes("UTF-8"),hash=MessageDigest.getInstance("SHA-1").digest(data);Signature sig=Signature.getInstance("SHA1withRSA");sig.initVerify(key.getPublic());sig.update(data);check(sig.verify(AdbWire.sign(key,hash)),"ADB token signature");check(Arrays.equals(key.getPublic().getEncoded(),AdbWire.key(dir).getPublic().getEncoded()),"key persists between runs");
  for(String s:new String[]{"127.0.0.1","8.8.8.8","::1","example.com","1;exit","256.1.1.1"}){boolean denied=false;try{AdbWire.address(s);}catch(IOException e){denied=true;}check(denied,"invalid target rejected");}
  if(args.length<2)return;
  try(AdbWire adb=new AdbWire()){adb.connect(args[1],5555,key,System.out::println);check(Integer.parseInt(adb.shell("getprop ro.build.version.sdk"))>=23,"real TV connection");byte[] bytes=new byte[70001];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i%251);String path="/data/local/tmp/marcotv-java-check.bin";adb.push(new ByteArrayInputStream(bytes),bytes.length,path,p->{});StringBuilder sha=new StringBuilder();for(byte x:MessageDigest.getInstance("SHA-256").digest(bytes))sha.append(String.format("%02x",x&255));check(adb.shell("sha256sum "+path).startsWith(sha.toString()),"real multi-packet sync transfer");adb.shell("rm -f "+path);check(adb.shell("getprop ro.build.version.sdk").trim().length()>0,"successive shell streams");}
 }
}
