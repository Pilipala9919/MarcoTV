// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Bounded, session-only HTTP transport. No arbitrary file or shell routes. */
final class PhoneHttp implements AutoCloseable {
    interface Handler {
        Response check(Request r) throws Exception;
        Response handle(Request r) throws Exception;
    }
    static final class Request {
        String method, path, target; long length;
        final Map<String,String> headers=new HashMap<>();
        File body;
        String header(String name){return headers.getOrDefault(name, "");}
        String text() throws IOException {
            if(length>65536)throw new IOException("body limit");
            if(body==null)return "";
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream in=new FileInputStream(body)){copy(in,out);}
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
        String param(String key) throws IOException {
            int q=target.indexOf('?');if(q<0)return "";
            for(String part:target.substring(q+1).split("&")){
                String[] pair=part.split("=",2);
                if(URLDecoder.decode(pair[0],"UTF-8").equals(key))return pair.length<2?"":URLDecoder.decode(pair[1],"UTF-8");
            }return "";
        }
    }
    static final class Response {
        int code;String type;byte[] bytes;File file;boolean deleteFile;
        final Map<String,String> headers=new LinkedHashMap<>();
        Response(int c,String t,byte[] b){code=c;type=t;bytes=b;}
        static Response text(int c,String s){return new Response(c,"text/plain; charset=utf-8",s.getBytes(StandardCharsets.UTF_8));}
        static Response file(File f,String type,boolean delete){Response r=new Response(200,type,null);r.file=f;r.deleteFile=delete;return r;}
    }
    private final ServerSocket server;
    private final File folder;
    private final Handler handler;
    private final ThreadPoolExecutor pool=new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4));
    private final Set<Socket> sockets=Collections.synchronizedSet(new HashSet<>());
    private volatile boolean closed;
    PhoneHttp(InetAddress address,int port,File folder,Handler handler)throws IOException{
        this.folder=folder;this.handler=handler;folder.mkdirs();
        server=new ServerSocket();server.setReuseAddress(true);server.bind(new InetSocketAddress(address,port),8);
        Thread accept=new Thread(()->{while(!closed)try{
            Socket s=server.accept();s.setSoTimeout(12000);sockets.add(s);
            try{pool.execute(()->serve(s));}catch(RejectedExecutionException full){sockets.remove(s);s.close();}
        }catch(IOException ignored){if(!closed)close();}},"MarcoPhoneAccept");accept.setDaemon(true);accept.start();
    }
    int port(){return server.getLocalPort();}
    private void serve(Socket socket){
        Request request=null;Response result=null;
        try(Socket s=socket){
            InputStream in=new BufferedInputStream(s.getInputStream());
            try{
                request=readHead(in);result=handler.check(request);
                if(result==null){
                    long max=request.path.equals("/restore")?128L*1024*1024:request.path.equals("/upload")?16L*1024*1024:65536;
                    if(request.length>max)result=Response.text(413,"文件过大");
                    else{
                        if(request.length>0){
                            if(folder.getUsableSpace()<request.length+16L*1024*1024)throw new IOException("space");
                            request.body=File.createTempFile("phone-",".part",folder);
                            long end=System.currentTimeMillis()+120000,remaining=request.length;
                            try(OutputStream out=new FileOutputStream(request.body)){
                                byte[] block=new byte[16384];while(remaining>0){
                                    if(closed||System.currentTimeMillis()>end)throw new IOException("deadline");
                                    int n=in.read(block,0,(int)Math.min(block.length,remaining));if(n<0)throw new EOFException();out.write(block,0,n);remaining-=n;
                                }
                            }
                        }
                        result=handler.handle(request);
                    }
                }
            }catch(Exception error){result=Response.text(400,"请求未完成，请刷新后重试");}
            if(result==null)result=Response.text(500,"暂时无法完成");
            OutputStream out=new BufferedOutputStream(s.getOutputStream());
            long size=result.file==null?result.bytes.length:result.file.length();
            StringBuilder h=new StringBuilder("HTTP/1.1 "+result.code+" "+(result.code==200?"OK":"Error")+"\r\nContent-Type: "+result.type+"\r\nContent-Length: "+size+"\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nX-Frame-Options: DENY\r\n");
            h.append("Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'\r\n");
            for(Map.Entry<String,String> e:result.headers.entrySet())h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            h.append("\r\n");out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
            if(result.file==null)out.write(result.bytes);else try(InputStream data=new FileInputStream(result.file)){copy(data,out);}out.flush();
        }catch(IOException ignored){}finally{
            sockets.remove(socket);if(request!=null&&request.body!=null)request.body.delete();
            if(result!=null&&result.deleteFile&&result.file!=null)result.file.delete();
        }
    }
    static Request readHead(InputStream in)throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();int state=0;
        while(state<4){int c=in.read();if(c<0)throw new EOFException();if(b.size()>=8192)throw new IOException("header limit");b.write(c);
            state=(state==0&&c==13)?1:(state==1&&c==10)?2:(state==2&&c==13)?3:(state==3&&c==10)?4:c==13?1:0;}
        String[] lines=new String(b.toByteArray(),StandardCharsets.US_ASCII).split("\r\n");String[] first=lines[0].split(" ");
        if(first.length!=3||!first[2].equals("HTTP/1.1")||!Arrays.asList("GET","POST").contains(first[0])||!first[1].startsWith("/")||first[1].startsWith("//")||first[1].contains("#"))throw new IOException("request line");
        Request r=new Request();r.method=first[0];r.target=first[1];r.path=first[1].split("\\?",2)[0];
        for(int i=1;i<lines.length;i++){int colon=lines[i].indexOf(':');if(colon<1||Character.isWhitespace(lines[i].charAt(0)))throw new IOException("header");String key=lines[i].substring(0,colon).toLowerCase(Locale.ROOT);if(r.headers.put(key,lines[i].substring(colon+1).trim())!=null)throw new IOException("duplicate header");}
        if(r.header("host").isEmpty()||!r.header("transfer-encoding").isEmpty()||!r.header("expect").isEmpty())throw new IOException("framing");
        String length=r.header("content-length");if(r.method.equals("POST")&&!length.matches("[0-9]{1,9}"))throw new IOException("length required");
        r.length=length.isEmpty()?0:Long.parseLong(length);if(r.length<0||(r.method.equals("GET")&&r.length!=0))throw new IOException("length");return r;
    }
    private static void copy(InputStream in,OutputStream out)throws IOException{byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
    public void close(){if(closed)return;closed=true;try{server.close();}catch(IOException ignored){}synchronized(sockets){for(Socket s:sockets)try{s.close();}catch(IOException ignored){}sockets.clear();}pool.shutdownNow();}
}
