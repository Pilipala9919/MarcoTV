// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
using System;
using System.IO;
using System.Text;
using System.Net;
using System.Net.Sockets;
using System.Numerics;
using System.Security.Cryptography;
using System.Collections.Generic;

namespace MarcoInstaller {
// A small, sequential ADB transport. No local ADB server or arbitrary command UI.
public sealed class AdbWire : IDisposable {
    readonly TcpClient client=new TcpClient(); NetworkStream stream; RSACryptoServiceProvider key;
    int serial=0; public const int CNXN=0x4e584e43, AUTH=0x48545541, OPEN=0x4e45504f, OKAY=0x59414b4f, WRTE=0x45545257, CLSE=0x45534c43;
    public static byte[] Utf(string s) { return Encoding.UTF8.GetBytes(s); }
    public static IPAddress Address(string text) {
        IPAddress ip; if (!IPAddress.TryParse(text,out ip)||ip.AddressFamily!=AddressFamily.InterNetwork) throw new IOException("请填写电视网络详情中的 IPv4 地址。");
        byte[] b=ip.GetAddressBytes();
        if (!(b[0]==10 || (b[0]==172&&b[1]>=16&&b[1]<=31) || (b[0]==192&&b[1]==168)) || b[3]==255) throw new IOException("请使用家庭局域网中的电视地址。");
        return ip;
    }
    static BigInteger Number(byte[] big) { byte[] b=new byte[big.Length+1]; for(int i=0;i<big.Length;i++)b[i]=big[big.Length-1-i];return new BigInteger(b); }
    static byte[] Little(BigInteger n,int size) { byte[] b=n.ToByteArray(),r=new byte[size];Array.Copy(b,r,Math.Min(b.Length,size));return r; }
    public static byte[] PublicKey(RSAParameters p) {
        BigInteger n=Number(p.Modulus),mod=BigInteger.One<<32;
        // Inverse of an odd RSA modulus modulo 2^32, by Newton iteration.
        BigInteger inv=BigInteger.One;for(int i=0;i<5;i++)inv=(inv*(2-n*inv))%mod;if(inv<0)inv+=mod;
        using(var m=new MemoryStream())using(var w=new BinaryWriter(m)) {
            w.Write(64);w.Write((uint)((mod-inv)%mod));w.Write(Little(n,256));w.Write(Little(BigInteger.ModPow(2,4096,n),256));w.Write((uint)Number(p.Exponent));return m.ToArray();
        }
    }
    public AdbWire() {}
    public AdbWire(string host,int port,Action<string> status) { Connect(host,port,status); }
    public void Connect(string host,int port,Action<string> status) {
        if(port<1||port>65535)throw new IOException("连接端口须为 1–65535。");
        try {
            var ar=client.BeginConnect(Address(host),port,null,null);
            try { if(!ar.AsyncWaitHandle.WaitOne(7000))throw new IOException("连接超时。请确认电视已开启网络调试，且两端在同一家庭网络。");client.EndConnect(ar); }
            finally { ar.AsyncWaitHandle.Close(); }
            client.NoDelay=true;client.ReceiveTimeout=65000;client.SendTimeout=20000;stream=client.GetStream();
            var cp=new CspParameters(){KeyContainerName="MarcoTVInstaller-Pilipala-v1",Flags=CspProviderFlags.UseDefaultKeyContainer};
            // A named per-user CSP container keeps the private key out of the distribution.
            cp.Flags=CspProviderFlags.NoFlags;key=new RSACryptoServiceProvider(2048,cp);key.PersistKeyInCsp=true;
            Send(CNXN,0x01000000,4096,Utf("host::\0"));int attempts=0;
            for(int i=0;i<6;i++) {
                var p=Read();if(p.Cmd==CNXN)return;
                if(p.Cmd!=AUTH||p.A!=1||p.Data.Length!=20)throw new IOException("该端口不是支持的普通 ADB 连接。仅提供无线配对码的电视请查看其他安装路线。");
                if(attempts++==0)Send(AUTH,2,0,key.SignHash(p.Data,"SHA1"));
                else {status("请在电视弹窗选择“允许”，确认这是你自己的设备。");Send(AUTH,3,0,Utf(Convert.ToBase64String(PublicKey(key.ExportParameters(false)))+" Pilipala@MarcoTVInstaller\0"));}
            }
            throw new IOException("电视未完成连接授权，请确认弹窗后重试。");
        }catch{Dispose();throw;}
    }
    sealed class Packet {public int Cmd,A,B;public byte[] Data;}
    static byte[] Exact(Stream s,int n) {byte[] b=new byte[n];int off=0;while(off<n){int got=s.Read(b,off,n-off);if(got==0)throw new IOException("电视连接已断开，请重新连接。");off+=got;}return b;}
    Packet Read() {
        byte[] h=Exact(stream,24);int cmd=BitConverter.ToInt32(h,0),size=BitConverter.ToInt32(h,12);
        if((cmd^BitConverter.ToInt32(h,20))!=-1||size<0||size>1024*1024)throw new IOException("电视返回了无效的连接数据。");
        byte[] b=Exact(stream,size);uint sum=0;foreach(byte x in b)sum+=x;
        if(sum!=BitConverter.ToUInt32(h,16))throw new IOException("连接数据校验失败。");
        return new Packet(){Cmd=cmd,A=BitConverter.ToInt32(h,4),B=BitConverter.ToInt32(h,8),Data=b};
    }
    Packet ReadFor(int local) {for(int i=0;i<64;i++){var p=Read();if(p.Cmd==CLSE&&p.B>0&&p.B<local)continue;return p;}throw new IOException("连接关闭响应过多，请重新连接。");}
    void Send(int cmd,int a,int b,byte[] data) {
        uint sum=0;foreach(byte x in data)sum+=x;
        using(var m=new MemoryStream())using(var w=new BinaryWriter(m)){w.Write(cmd);w.Write(a);w.Write(b);w.Write(data.Length);w.Write(sum);w.Write(~cmd);w.Write(data);byte[] all=m.ToArray();stream.Write(all,0,all.Length);}
    }
    sealed class Channel : IDisposable {
        AdbWire adb;int local,remote;bool closed;Queue<byte> pending=new Queue<byte>();
        public Channel(AdbWire a,string service) {adb=a;local=++a.serial;a.Send(OPEN,local,0,Utf(service+"\0"));var p=a.ReadFor(local);if(p.Cmd!=OKAY||p.B!=local)throw new IOException("电视拒绝了安装连接，请检查调试与安装限制。");remote=p.A;}
        Packet Next() {var p=adb.ReadFor(local);if(p.A!=remote||p.B!=local)throw new IOException("连接会话不匹配。");if(p.Cmd==WRTE){if(pending.Count+p.Data.Length>1024*1024)throw new IOException("电视返回信息过长。");foreach(byte b in p.Data)pending.Enqueue(b);adb.Send(OKAY,local,remote,new byte[0]);}else if(p.Cmd==CLSE){closed=true;adb.Send(CLSE,local,remote,new byte[0]);}return p;}
        public void Write(byte[] data) {for(int off=0;off<data.Length;){int n=Math.Min(4096,data.Length-off);byte[] part=new byte[n];Array.Copy(data,off,part,0,n);adb.Send(WRTE,local,remote,part);while(true){var p=Next();if(p.Cmd==OKAY)break;if(closed)throw new IOException("电视中断了文件传输。");if(p.Cmd!=WRTE)throw new IOException("无效传输响应。");}off+=n;}}
        public byte[] ReadExact(int n) {while(pending.Count<n&&!closed)Next();if(pending.Count<n)throw new IOException("电视返回的数据不完整。");byte[] b=new byte[n];for(int i=0;i<n;i++)b[i]=pending.Dequeue();return b;}
        public string ReadText() {using(var m=new MemoryStream()){while(true){while(pending.Count>0){m.WriteByte(pending.Dequeue());if(m.Length>1024*1024)throw new IOException("电视返回信息过长。");}if(closed)return Encoding.UTF8.GetString(m.ToArray());Next();}}}
        public void Dispose(){if(!closed){adb.Send(CLSE,local,remote,new byte[0]);while(!closed){var p=Next();if(p.Cmd!=CLSE&&p.Cmd!=WRTE&&p.Cmd!=OKAY)throw new IOException("关闭连接失败。");}}}
    }
    public string Shell(string command) {using(var c=new Channel(this,"shell:"+command))return c.ReadText().Trim();}
    static byte[] Sync(string id,byte[] data,int? value=null){using(var m=new MemoryStream())using(var w=new BinaryWriter(m)){w.Write(Utf(id));w.Write(value??data.Length);w.Write(data);return m.ToArray();}}
    public void Push(Stream file,long size,string path,Action<int> progress) {
        using(var c=new Channel(this,"sync:")) {
            c.Write(Sync("SEND",Utf(path+",33188")));byte[] buf=new byte[32768];long done=0;int n;
            while((n=file.Read(buf,0,buf.Length))>0){byte[] part=new byte[n];Array.Copy(buf,part,n);c.Write(Sync("DATA",part));done+=n;progress((int)(done*100/size));}
            if(done!=size)throw new IOException("安装包大小不一致。");c.Write(Sync("DONE",new byte[0],(int)(DateTime.UtcNow-new DateTime(1970,1,1)).TotalSeconds));
            byte[] result=c.ReadExact(8);string id=Encoding.ASCII.GetString(result,0,4);int len=BitConverter.ToInt32(result,4);
            if(id!="OKAY") {if(len<0||len>65536)throw new IOException("传输失败。");if(id=="FAIL")c.ReadExact(len);throw new IOException("电视无法保存安装文件，可能存储空间不足或权限受限。");}
        }
    }
    public void Dispose(){if(client!=null)client.Close();if(key!=null)key.Dispose();}
}
}
