// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.*;
import javax.crypto.Cipher;
import java.math.BigInteger;
import java.util.*;
import android.util.Base64;

/** Loopback-only ADB transport adapted from our installer. No remote hosts, uploads or shell UI. */
final class LocalAdbWire implements Closeable {
    static final int CNXN=0x4e584e43,AUTH=0x48545541,OPEN=0x4e45504f,OKAY=0x59414b4f,WRTE=0x45545257,CLSE=0x45534c43;
    private final Socket socket=new Socket();private InputStream in;private OutputStream out;private int serial;
    public static byte[] utf(String s){return s.getBytes(StandardCharsets.UTF_8);}
    interface Status { void accept(String message); }
    static InetAddress address(String text)throws IOException{
        if(!"127.0.0.1".equals(text))throw new IOException("Only this TV is allowed");
        return InetAddress.getByAddress(new byte[]{127,0,0,1});
    }
    public static KeyPair key(File folder)throws Exception{
        File f=new File(folder,"home-setup-adb-key");KeyFactory factory=KeyFactory.getInstance("RSA");
        if(f.exists()){
            if(f.length()>8192)throw new IOException("连接密钥异常，请清除助手数据后重新授权");byte[] b=new byte[(int)f.length()];try(InputStream in=new FileInputStream(f)){exact(in,b);}
            PrivateKey priv=factory.generatePrivate(new PKCS8EncodedKeySpec(b));RSAPrivateKeySpec spec=factory.getKeySpec(priv,RSAPrivateKeySpec.class);return new KeyPair(factory.generatePublic(new RSAPublicKeySpec(spec.getModulus(),RSAKeyGenParameterSpec.F4)),priv);
        }
        KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(new RSAKeyGenParameterSpec(2048,RSAKeyGenParameterSpec.F4));KeyPair k=g.generateKeyPair();File tmp=new File(folder,"home-setup-adb-key.tmp");try(FileOutputStream o=new FileOutputStream(tmp)){o.write(k.getPrivate().getEncoded());o.getFD().sync();}if(!tmp.renameTo(f))throw new IOException("无法保存连接密钥");return k;
    }
    private static byte[] little(BigInteger n,int size){byte[] b=n.toByteArray(),r=new byte[size];for(int i=0;i<size&&i<b.length;i++)r[i]=b[b.length-1-i];return r;}
    public static byte[] publicKey(RSAPublicKey key){BigInteger n=key.getModulus(),mod=BigInteger.ONE.shiftLeft(32);ByteBuffer b=ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);b.putInt(64).putInt(mod.subtract(n.modInverse(mod)).intValue());b.put(little(n,256)).put(little(BigInteger.ONE.shiftLeft(4096).mod(n),256)).putInt(key.getPublicExponent().intValue());return b.array();}
    public static byte[] sign(KeyPair key,byte[] token)throws Exception{
        if(token.length!=20)throw new IOException("无效授权请求");byte[] prefix={0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14};byte[] msg=new byte[prefix.length+20];System.arraycopy(prefix,0,msg,0,prefix.length);System.arraycopy(token,0,msg,prefix.length,20);
        Cipher c=Cipher.getInstance("RSA/ECB/PKCS1Padding");c.init(Cipher.ENCRYPT_MODE,key.getPrivate());return c.doFinal(msg);
    }
    public void connect(String host,int port,KeyPair key,Status status)throws Exception{
        try{
            if(port<1||port>65535)throw new IOException("端口须为 1–65535");socket.connect(new InetSocketAddress(address(host),port),7000);socket.setSoTimeout(8000);socket.setTcpNoDelay(true);in=socket.getInputStream();out=socket.getOutputStream();send(CNXN,0x01000000,4096,utf("host::\0"));int count=0;
            for(int i=0;i<6;i++){Packet p=read();if(p.cmd==CNXN){socket.setSoTimeout(45000);return;}if(p.cmd!=AUTH||p.a!=1||p.data.length!=20)throw new IOException("此端口不支持普通网络 ADB；无线配对码机型请查看其他路线");if(count++==0)send(AUTH,2,0,sign(key,p.data));else{socket.setSoTimeout(45000);status.accept("请在电视上用遥控器选择“允许连接”");send(AUTH,3,0,utf(Base64.encodeToString(publicKey((RSAPublicKey)key.getPublic()),Base64.NO_WRAP)+" Pilipala@MarcoTVLocalSetup\0"));}}
            throw new IOException("电视尚未完成授权，请确认弹窗后重试");
        }catch(Exception e){close();throw e;}
    }
    private static void exact(InputStream in,byte[] b)throws IOException{int off=0;while(off<b.length){int n=in.read(b,off,b.length-off);if(n<0)throw new IOException("电视连接已断开");off+=n;}}
    private static final class Packet{int cmd,a,b;byte[] data;}
    private Packet read()throws IOException{byte[] header=new byte[24];exact(in,header);ByteBuffer h=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);Packet p=new Packet();p.cmd=h.getInt();p.a=h.getInt();p.b=h.getInt();int n=h.getInt(),sum=h.getInt(),magic=h.getInt();if(magic!=~p.cmd||n<0||n>1024*1024)throw new IOException("电视返回了无效数据");p.data=new byte[n];exact(in,p.data);int actual=0;for(byte b:p.data)actual+=b&255;if(actual!=sum)throw new IOException("连接数据校验失败");return p;}
    private Packet readFor(int local)throws IOException{for(int i=0;i<64;i++){Packet p=read();if(p.cmd==CLSE&&p.b>0&&p.b<local)continue;return p;}throw new IOException("连接关闭响应过多，请重新连接");}
    private void send(int cmd,int a,int b,byte[] data)throws IOException{int sum=0;for(byte x:data)sum+=x&255;ByteBuffer h=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);h.putInt(cmd).putInt(a).putInt(b).putInt(data.length).putInt(sum).putInt(~cmd);out.write(h.array());out.write(data);out.flush();}
    private final class Channel implements Closeable{
        int local=++serial,remote;boolean closed;ArrayDeque<Byte> pending=new ArrayDeque<>();
        Channel(String service)throws IOException{send(OPEN,local,0,utf(service+"\0"));Packet p=readFor(local);if(p.cmd!=OKAY||p.b!=local)throw new IOException("电视拒绝安装连接，请检查调试和安装权限");remote=p.a;}
        Packet next()throws IOException{Packet p=readFor(local);if(p.a!=remote||p.b!=local)throw new IOException("连接会话不匹配");if(p.cmd==WRTE){if(pending.size()+p.data.length>1024*1024)throw new IOException("电视返回信息过长");for(byte b:p.data)pending.add(b);send(OKAY,local,remote,new byte[0]);}else if(p.cmd==CLSE){closed=true;send(CLSE,local,remote,new byte[0]);}return p;}
        void write(byte[] data)throws IOException{for(int off=0;off<data.length;){int n=Math.min(4096,data.length-off);send(WRTE,local,remote,Arrays.copyOfRange(data,off,off+n));for(;;){Packet p=next();if(p.cmd==OKAY)break;if(closed||p.cmd!=WRTE)throw new IOException("电视中断了传输");}off+=n;}}
        byte[] bytes(int n)throws IOException{while(pending.size()<n&&!closed)next();if(pending.size()<n)throw new IOException("电视返回的数据不完整");byte[] b=new byte[n];for(int i=0;i<n;i++)b[i]=pending.remove();return b;}
        String text()throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();for(;;){while(!pending.isEmpty()){b.write(pending.remove());if(b.size()>1024*1024)throw new IOException("电视返回信息过长");}if(closed)return new String(b.toByteArray(),StandardCharsets.UTF_8).trim();next();}}
        public void close()throws IOException{if(!closed){send(CLSE,local,remote,new byte[0]);while(!closed)next();}}
    }
    public String shell(String command)throws IOException{try(Channel c=new Channel("shell:"+command)){return c.text();}}
    public void close(){try{socket.close();}catch(IOException ignored){}}
}
