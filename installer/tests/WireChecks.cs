// Copyright (C) 2026 Pilipala · SPDX-License-Identifier: GPL-3.0-only
using System;using System.IO;using System.Linq;using System.Security.Cryptography;using MarcoInstaller;
public class WireChecks {
 public static int Main(string[] args){
  try{
   using(var key=new RSACryptoServiceProvider(2048)){var p=key.ExportParameters(false);byte[] data=AdbWire.PublicKey(p);var n=new System.Numerics.BigInteger(data.Skip(8).Take(256).Concat(new byte[]{0}).ToArray());uint inv=BitConverter.ToUInt32(data,4);if((n*inv+1)% (System.Numerics.BigInteger.One<<32)!=0)throw new Exception("RSA inverse");}
   if(args.Length==0){Console.WriteLine("PASS RSA public key Montgomery inverse");return 0;}
   using(var adb=new AdbWire(args[0],5555,Console.WriteLine)){
    Console.WriteLine("PASS connected SDK "+adb.Shell("getprop ro.build.version.sdk"));
    byte[] bytes=Enumerable.Range(0,70001).Select(i=>(byte)(i%251)).ToArray();string path="/data/local/tmp/marcotv-wire-check.bin";
    using(var s=new MemoryStream(bytes))adb.Push(s,bytes.Length,path,n=>{});
    string hash;using(var sha=SHA256.Create())hash=BitConverter.ToString(sha.ComputeHash(bytes)).Replace("-","").ToLowerInvariant();
    if(!adb.Shell("sha256sum "+path).StartsWith(hash))throw new Exception("transfer hash mismatch");adb.Shell("rm -f "+path);Console.WriteLine("PASS multi-packet transfer, digest and successive shell streams");
    if(args.Length>1&&args[1]=="--install-embedded"){
     Program.Verify();const string apk="/data/local/tmp/marcotv-installer-check.apk";
     try{using(var s=Program.Payload())adb.Push(s,ReleaseInfo.Size,apk,n=>{});string result=adb.Shell("pm install -r "+apk);if(!result.Split('\n').Any(x=>x.Trim()=="Success"))throw new Exception(InstallerForm.InstallError(result));Console.WriteLine("PASS embedded APK installed over existing version");}finally{adb.Shell("rm -f "+apk);}
    }
   }return 0;
  }catch(Exception e){Console.WriteLine("FAIL "+e.Message);return 1;}
 }
}
