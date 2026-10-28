// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class PhoneHttpChecks {
    static int passed;
    static void check(String name,boolean ok){if(!ok)throw new AssertionError(name);passed++;System.out.println("PASS "+name);}
    static PhoneHttp.Request parse(String input)throws Exception{return PhoneHttp.readHead(new ByteArrayInputStream(input.getBytes(StandardCharsets.US_ASCII)));}
    static void reject(String name,String input)throws Exception{boolean rejected=false;try{parse(input);}catch(Exception e){rejected=true;}check(name,rejected);}
    static String send(int port,String input)throws Exception{try(Socket s=new Socket("127.0.0.1",port)){s.setSoTimeout(3000);s.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[1024];int n;while((n=s.getInputStream().read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}}
    public static void main(String[] args)throws Exception{
        check("valid_query_decode",parse("GET /art?id=a%2Fb HTTP/1.1\r\nHost: localhost\r\n\r\n").param("id").equals("a/b"));
        reject("duplicate_length","POST /action HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\nContent-Length: 1\r\n\r\n");
        reject("chunked_framing","POST /action HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\nTransfer-Encoding: chunked\r\n\r\n");
        reject("missing_length","POST /action HTTP/1.1\r\nHost: localhost\r\n\r\n");
        reject("negative_length","POST /action HTTP/1.1\r\nHost: localhost\r\nContent-Length: -1\r\n\r\n");
        reject("get_body","GET / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 2\r\n\r\n");
        reject("absolute_target","GET http://example.com/ HTTP/1.1\r\nHost: localhost\r\n\r\n");
        reject("folded_header","GET / HTTP/1.1\r\nHost: localhost\r\n Folded: value\r\n\r\n");
        reject("header_limit","GET / HTTP/1.1\r\nHost: localhost\r\nLarge: "+new String(new char[9000]).replace('\0','x')+"\r\n\r\n");
        File folder=Files.createTempDirectory("marcotv-http-test").toFile();
        PhoneHttp server=new PhoneHttp(InetAddress.getByName("127.0.0.1"),0,folder,new PhoneHttp.Handler(){
            public PhoneHttp.Response check(PhoneHttp.Request r){return r.path.equals("/denied")?PhoneHttp.Response.text(401,"denied"):null;}
            public PhoneHttp.Response handle(PhoneHttp.Request r)throws Exception{return PhoneHttp.Response.text(200,r.text());}
        });int port=server.port();
        try{
            String response=send(port,"POST /action HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\n\r\nhello");
            check("body_roundtrip",response.endsWith("hello")&&response.startsWith("HTTP/1.1 200"));
            check("response_is_not_cached",response.contains("Cache-Control: no-store")&&response.contains("X-Frame-Options: DENY"));
            check("oversized_body_rejected_before_upload",send(port,"POST /action HTTP/1.1\r\nHost: localhost\r\nContent-Length: 70000\r\n\r\n").startsWith("HTTP/1.1 413"));
            check("authorization_precedes_upload",send(port,"POST /denied HTTP/1.1\r\nHost: localhost\r\nContent-Length: 70000\r\n\r\n").startsWith("HTTP/1.1 401"));
            check("temporary_bodies_removed",folder.list().length==0);
        }finally{server.close();folder.delete();}
        boolean stopped=false;try{new Socket("127.0.0.1",port).close();}catch(IOException e){stopped=true;}check("server_stops_listening",stopped);
        System.out.println("OK "+passed+" phone transport checks");
    }
}
