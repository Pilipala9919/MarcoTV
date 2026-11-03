// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.*;
import android.os.Build;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicit opt-in transport; one bounded pending report, idempotent retries, no install identifier. */
final class FeedbackManager {
    static final String SITE="https://marcotv.wlj9919.top";
    private static final ExecutorService worker=Executors.newSingleThreadExecutor();
    private static final Object lock=new Object();
    private static volatile HttpURLConnection active;
    static SharedPreferences prefs(Context c){return c.getSharedPreferences("feedback",0);}
    static boolean automatic(Context c){return prefs(c).getBoolean("automatic",false);}
    static String status(Context c){return prefs(c).getString("status","尚未提交");}
    static File pending(Context c){return new File(c.getNoBackupFilesDir(),"pending-feedback.json");}
    static JSONObject snapshot(Context c,String reason)throws Exception{
        String journal=Diagnostics.recent(c);
        return new JSONObject().put("schema",1).put("id",UUID.randomUUID().toString().replace("-",""))
                .put("version",ProjectInfo.VERSION).put("edition",BuildEdition.RESTRICTED?"restricted":"standard")
                .put("manufacturer",FeedbackPolicy.label(Build.MANUFACTURER)).put("model",FeedbackPolicy.label(Build.MODEL))
                .put("firmware",FeedbackPolicy.label(Build.DISPLAY)).put("api",Build.VERSION.SDK_INT)
                .put("reason",reason).put("home",SystemIntegration.homeIsOurs(c)).put("service",HomeKeyService.connected)
                .put("events",new JSONArray(FeedbackPolicy.codes(journal))).put("frames",new JSONArray(FeedbackPolicy.frames(journal)));
    }
    static String link(JSONObject report){
        try{
            JSONObject compact=new JSONObject(report.toString());
            JSONArray frames=compact.getJSONArray("frames"),shortFrames=new JSONArray();
            for(int i=Math.max(0,frames.length()-3);i<frames.length();i++)shortFrames.put(frames.getString(i));
            compact.put("frames",shortFrames);
            return SITE+"/feedback/#"+Base64.encodeToString(compact.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
        }catch(Exception e){throw new IllegalArgumentException("Invalid report");}
    }
    static void setAutomatic(Context c,boolean enabled){
        synchronized(lock){
            prefs(c).edit().putBoolean("automatic",enabled).apply();
            if(!enabled){
                HttpURLConnection connection=active;if(connection!=null)connection.disconnect();
                try{File f=pending(c);if(f.exists()&&read(f).optString("reason").equals("automatic"))f.delete();}catch(Exception ignored){}
            }
        }
        if(enabled)tick(c);
    }
    static void tick(Context c){
        final Context app=c.getApplicationContext();
        if(!automatic(app)||!Diagnostics.enabled(app))return;
        worker.execute(()->{
            synchronized(lock){
                long now=System.currentTimeMillis();
                if(!FeedbackPolicy.due(automatic(app),Diagnostics.enabled(app),now,prefs(app).getLong("last_attempt",0)))return;
            }
            try{
                JSONObject report=pending(app).exists()?read(pending(app)):snapshot(app,"automatic");
                if(!report.optString("reason").equals("automatic"))return;
                if(!FeedbackPolicy.hasFailure(FeedbackPolicy.codes(Diagnostics.recent(app))))return;
                String signature=report.optString("version")+report.getJSONArray("events").toString()+report.getJSONArray("frames").toString();
                if(signature.equals(prefs(app).getString("last_signature","")))return;
                prefs(app).edit().putLong("last_attempt",System.currentTimeMillis()).apply();
                transmit(app,report,true);
                if(prefs(app).getString("last_id","").equals(report.getString("id")))prefs(app).edit().putString("last_signature",signature).apply();
            }catch(Exception ignored){prefs(app).edit().putString("status","暂未提交，稍后可重试").apply();}
        });
    }
    interface Result{void done(String message);}
    static void send(Context c,JSONObject report,Result result){
        Context app=c.getApplicationContext();worker.execute(()->{
            String message;
            try{message=transmit(app,report,false);}catch(Exception e){message="暂未提交。如已有待提交报告，请先重试或清除；网络恢复后可再提交。";prefs(app).edit().putString("status",message).apply();}
            String outcome=message;new android.os.Handler(android.os.Looper.getMainLooper()).post(()->result.done(outcome));
        });
    }
    static void retry(Context c,Result result){
        worker.execute(()->{
            try{JSONObject report=read(pending(c));send(c,report,result);}
            catch(Exception e){new android.os.Handler(android.os.Looper.getMainLooper()).post(()->result.done("没有待重试的报告。"));}
        });
    }
    private static String transmit(Context c,JSONObject report,boolean auto)throws Exception{
        synchronized(lock){
            if(auto&&(!automatic(c)||!Diagnostics.enabled(c)))return "自动提交已关闭";
            if(pending(c).exists()&&!read(pending(c)).optString("id").equals(report.optString("id")))throw new IOException("Pending report exists");
            byte[] bytes=report.toString().getBytes("UTF-8");if(bytes.length>12000)throw new IOException("Report too large");
            File part=new File(c.getNoBackupFilesDir(),"pending-feedback.part");
            try(FileOutputStream out=new FileOutputStream(part)){out.write(bytes);out.getFD().sync();}
            android.system.Os.rename(part.getAbsolutePath(),pending(c).getAbsolutePath());
        }
        JSONObject response;
        try{response=request("/api/feedback/reports",report,auto?c:null);}catch(Exception e){prefs(c).edit().putString("status","暂未提交，可重试或扫码提交").apply();throw e;}
        if(!report.getString("id").equals(response.optString("id"))||!java.util.Arrays.asList("received","investigating","resolved").contains(response.optString("state")))throw new IOException("Receipt not confirmed");
        synchronized(lock){pending(c).delete();prefs(c).edit().putString("last_id",report.getString("id")).putString("query_id",report.getString("id")).putString("status","已收到 · "+report.getString("id").substring(0,8)).apply();}
        return "已收到反馈，编号 "+report.getString("id")+"。无需公开诊断文件。";
    }
    static JSONObject request(String path,JSONObject body)throws Exception{
        return request(path,body,null);
    }
    private static JSONObject request(String path,JSONObject body,Context autoContext)throws Exception{
        HttpURLConnection connection=(HttpURLConnection)new URL(SITE+path).openConnection();
        synchronized(lock){
            if(autoContext!=null&&(!automatic(autoContext)||!Diagnostics.enabled(autoContext)))throw new IOException("Consent withdrawn");
            active=connection;
        }
        try{
            connection.setConnectTimeout(8000);connection.setReadTimeout(10000);connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent","MarcoTV-Feedback/1");connection.setRequestProperty("Content-Type","application/json");
            connection.setRequestMethod("POST");connection.setDoOutput(true);
            byte[] data=body.toString().getBytes("UTF-8");connection.setFixedLengthStreamingMode(data.length);
            try(OutputStream out=connection.getOutputStream()){out.write(data);}
            int status=connection.getResponseCode();if(status!=200&&status!=201)throw new IOException("Feedback unavailable");
            try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[1024];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>4096)throw new IOException("Response too large");out.write(buffer,0,n);}
                return new JSONObject(new String(out.toByteArray(),"UTF-8"));
            }
        }finally{connection.disconnect();active=null;}
    }
    static void checkStatus(Context c,Result callback){
        Context app=c.getApplicationContext();worker.execute(()->{
            String message;
            try{
                String id=prefs(app).getString("query_id",prefs(app).getString("last_id",""));
                if(id.length()!=32)throw new IOException("No report");
                JSONObject r=request("/api/feedback/status",new JSONObject().put("id",id));
                String state=r.optString("state");
                message="编号 "+id+"\n"+(state.equals("received")?"已收到，等待排查":state.equals("investigating")?"正在排查":state.equals("resolved")?"已处理，请检查最新版本":"暂时没有处理状态");
            }catch(Exception e){message="尚未查到报告，服务暂不可用或编号已过期。未提交的报告可重试。";}
            String result=message;new android.os.Handler(android.os.Looper.getMainLooper()).post(()->callback.done(result));
        });
    }
    static void clearPending(Context c){
        synchronized(lock){
            HttpURLConnection connection=active;if(connection!=null)connection.disconnect();
            pending(c).delete();prefs(c).edit().putString("status","已清除本机待提交报告；已收到的反馈不受影响").apply();
        }
    }
    static JSONObject read(File file)throws Exception{
        if(file.length()>12000)throw new IOException("Report too large");
        try(FileInputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] data=new byte[1024];int n;while((n=in.read(data))!=-1){if(out.size()+n>12000)throw new IOException("Report too large");out.write(data,0,n);}
            return new JSONObject(new String(out.toByteArray(),"UTF-8"));
        }
    }
}
