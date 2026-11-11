// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;
import android.content.Context;
import java.net.*;
import java.io.*;
import java.util.concurrent.*;
import org.json.*;

public final class FeedbackTransportChecks {
    static int passed,calls,mode=503;
    static String posted="";
    static void check(boolean value){if(!value)throw new AssertionError("check "+passed);passed++;}
    static void drain()throws Exception{
        java.lang.reflect.Field f=FeedbackManager.class.getDeclaredField("worker");f.setAccessible(true);
        ((ExecutorService)f.get(null)).submit(()->{}).get(5,TimeUnit.SECONDS);
    }
    static String send(Context c,JSONObject p,boolean retry)throws Exception{
        CountDownLatch latch=new CountDownLatch(1);String[] result={null};
        FeedbackManager.Result callback=s->{result[0]=s;latch.countDown();};
        if(retry)FeedbackManager.retry(c,callback);else FeedbackManager.send(c,p,callback);
        if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("callback timeout");return result[0];
    }
    static class Connection extends HttpURLConnection {
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        Connection(URL u){super(u);}
        public void disconnect(){}public boolean usingProxy(){return false;}public void connect(){}
        public OutputStream getOutputStream(){return output;}
        public int getResponseCode(){calls++;posted=output.toString(java.nio.charset.StandardCharsets.UTF_8);return mode==503?503:201;}
        public InputStream getInputStream(){
            String id=new JSONObject(posted).getString("id");
            String response=mode==999?"x".repeat(5000):new JSONObject().put("id",mode==998?"wrong":id).put("state","received").toString();
            return new ByteArrayInputStream(response.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    public static void main(String[] args)throws Exception{
        try{run(args);}finally{
            java.lang.reflect.Field f=FeedbackManager.class.getDeclaredField("worker");f.setAccessible(true);
            ((ExecutorService)f.get(null)).shutdownNow();
        }
    }
    static void run(String[] args)throws Exception{
        URL.setURLStreamHandlerFactory(protocol->protocol.equals("https")?new URLStreamHandler(){protected URLConnection openConnection(URL u){return new Connection(u);}}:null);
        Context c=new Context();
        FeedbackManager.pending(c).delete();
        check(!FeedbackManager.automatic(c));
        FeedbackManager.tick(c);drain();check(calls==0);
        JSONObject report=FeedbackManager.snapshot(c,"manual");
        check(report.keySet().size()==13);
        check(!report.toString().contains("secret-password"));
        check(!report.toString().contains("external.package"));
        String fragment=FeedbackManager.link(report).split("#",2)[1];
        check(new JSONObject(new String(java.util.Base64.getUrlDecoder().decode(fragment),java.nio.charset.StandardCharsets.UTF_8)).getString("id").equals(report.getString("id")));
        JSONObject qr=FeedbackManager.qrSnapshot(report);
        check(qr.getString("id").matches("[0-9a-f]{32}")&&!qr.getString("id").equals(report.getString("id"))&&qr.getString("id").equals(FeedbackManager.qrSnapshot(report).getString("id")));
        String result=send(c,report,false);check(!result.startsWith("已收到"));
        check(FeedbackManager.pending(c).exists());
        String id=new JSONObject(posted).getString("id");check(id.equals(report.getString("id")));
        JSONObject another=FeedbackManager.snapshot(c,"manual");int before=calls;
        send(c,another,false);check(calls==before);check(FeedbackManager.read(FeedbackManager.pending(c)).getString("id").equals(id));
        mode=201;result=send(c,null,true);check(result.startsWith("已收到"));
        check(new JSONObject(posted).getString("id").equals(id));check(!FeedbackManager.pending(c).exists());
        check(FeedbackManager.prefs(c).getString("query_id","").equals(id));
        mode=998;send(c,another,false);check(FeedbackManager.pending(c).exists());
        check(!FeedbackManager.prefs(c).getString("last_id","").equals(another.getString("id")));
        FeedbackManager.clearPending(c);mode=999;send(c,another,false);check(FeedbackManager.pending(c).exists());
        FeedbackManager.clearPending(c);mode=503;
        FeedbackManager.setAutomatic(c,true);drain();check(FeedbackManager.pending(c).exists());
        check(FeedbackManager.read(FeedbackManager.pending(c)).getString("reason").equals("automatic"));
        before=calls;FeedbackManager.tick(c);drain();check(calls==before);
        FeedbackManager.setAutomatic(c,false);check(!FeedbackManager.pending(c).exists());
        before=calls;FeedbackManager.tick(c);drain();check(calls==before);
        mode=201;FeedbackManager.prefs(c).edit().putLong("last_attempt",0).apply();
        FeedbackManager.setAutomatic(c,true);drain();check(calls==before+1);
        before=calls;FeedbackManager.prefs(c).edit().putLong("last_attempt",0).apply();
        FeedbackManager.tick(c);drain();check(calls==before);
        Diagnostics.recording=false;FeedbackManager.prefs(c).edit().putLong("last_attempt",0).apply();
        FeedbackManager.setAutomatic(c,true);drain();check(calls==before);
        FeedbackManager.setAutomatic(c,false);
        System.out.println("Feedback transport checks passed: "+passed);
    }
}
class ProjectInfo {static final String VERSION="0.16.0";}
class BuildEdition {static final boolean RESTRICTED=false;}
class HomeKeyService {static boolean connected;}
class SystemIntegration {static boolean homeIsOurs(Context c){return false;}}
class Diagnostics {
    static boolean recording=true;
    static boolean enabled(Context c){return recording;}
    static String recent(Context c){return "12:00 CRASH\nsecret-password\nexternal.package.a:3\ncom.pilipala.tvhome.Test.method:15\n";}
}
