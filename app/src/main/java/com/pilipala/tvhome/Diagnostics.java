// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.*;
import android.os.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Bounded, local-only event journal. Never accepts exception messages or user content. */
final class Diagnostics {
    static final long MAX_BYTES=5L*1024*1024, RETENTION=7L*24*60*60*1000;
    private static Context context;
    private static final ExecutorService writer=Executors.newSingleThreadExecutor();
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static volatile boolean foreground;
    private static volatile long heartbeat;
    private static String lastCode=""; private static long lastEvent;
    private static final Runnable beat=new Runnable(){public void run(){heartbeat=SystemClock.uptimeMillis();if(foreground)main.postDelayed(this,1000);}};
    static boolean enabled(Context c){return c.getSharedPreferences("diagnostics",0).getBoolean("enabled",true);}
    static synchronized void init(Context c){
        if(context!=null)return;
        if(Build.VERSION.SDK_INT>=24&&!((UserManager)c.getSystemService(Context.USER_SERVICE)).isUserUnlocked())return;
        context=c.getApplicationContext();
        if(enabled(c)&&c.getSharedPreferences("diagnostics",0).getBoolean("foreground",false))event("PREVIOUS_RUN_INTERRUPTED_UNKNOWN");
        c.getSharedPreferences("diagnostics",0).edit().putBoolean("foreground",false).apply();
        Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
            try{if(enabled(context))append("CRASH\n"+safeStack(error));}catch(Throwable ignored){}
            if(previous!=null)previous.uncaughtException(thread,error);
            else {android.os.Process.killProcess(android.os.Process.myPid());System.exit(10);}
        });
        ScheduledExecutorService watch=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"MarcoTV-health");t.setDaemon(true);return t;});
        watch.scheduleWithFixedDelay(()->{
            if(foreground&&enabled(context)&&SystemClock.uptimeMillis()-heartbeat>8000)event("UI_UNRESPONSIVE_SUSPECTED");
        },10,10,TimeUnit.SECONDS);
        event("PROCESS_STARTED");
    }
    static void foreground(boolean active){
        foreground=active;heartbeat=SystemClock.uptimeMillis();main.removeCallbacks(beat);if(active)main.post(beat);
        if(context!=null)context.getSharedPreferences("diagnostics",0).edit().putBoolean("foreground",active&&enabled(context)).apply();
    }
    static String safeStack(Throwable error){
        StringBuilder out=new StringBuilder();int causes=0;
        while(error!=null&&causes++<4){
            out.append(error.getClass().getName()).append('\n');int n=0;
            for(StackTraceElement frame:error.getStackTrace())if(frame.getClassName().startsWith("com.pilipala.tvhome.")&&n++<16)
                out.append(frame.getClassName()).append('.').append(frame.getMethodName()).append(':').append(frame.getLineNumber()).append('\n');
            error=error.getCause();
        }return out.toString();
    }
    static void event(String code){
        if(context==null||!enabled(context)||!code.matches("[A-Z_]{1,60}"))return;
        writer.execute(()->{try{append(code);}catch(Exception ignored){}});
    }
    static File folder(Context c){File f=new File(c.getFilesDir(),"diagnostics");f.mkdirs();return f;}
    private static synchronized void append(String value)throws IOException{
        if(context==null||!enabled(context))return;
        long now=System.currentTimeMillis();if(value.equals(lastCode)&&now-lastEvent<60000)return;
        prune(context,now);lastCode=value;lastEvent=now;
        String day=new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(new Date(now));
        File file=new File(folder(context),day+".txt");
        if(file.length()>512*1024)return;
        try(Writer out=new OutputStreamWriter(new FileOutputStream(file,true),"UTF-8")){
            out.write(new SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(new Date(now))+" "+value+"\n");
        }prune(context,now);
    }
    static synchronized void prune(Context c,long now){
        File[] files=folder(c).listFiles();if(files==null)return;Arrays.sort(files,(a,b)->a.getName().compareTo(b.getName()));
        long total=0;for(File file:files){long start=file.lastModified();try{if(file.getName().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}\\.txt"))start=new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).parse(file.getName().substring(0,10)).getTime();}catch(Exception ignored){}
            if(now-start>RETENTION)file.delete();else total+=file.length();}
        for(File file:files)if(total>MAX_BYTES&&file.exists()){long size=file.length();if(file.delete())total-=size;}
    }
    static synchronized String recent(Context c){
        prune(c,System.currentTimeMillis());File[] files=folder(c).listFiles();if(files==null||files.length==0)return "暂无记录。";
        Arrays.sort(files,(a,b)->a.getName().compareTo(b.getName()));StringBuilder out=new StringBuilder();
        for(File f:files)try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(f),"UTF-8"))){
            out.append(f.getName()).append('\n');String line;while((line=reader.readLine())!=null){out.append(line).append('\n');if(out.length()>60000)out.delete(0,out.length()-60000);}
        }catch(IOException ignored){}return out.toString();
    }
    static void clear(Context c){writer.execute(()->{synchronized(Diagnostics.class){File[] files=folder(c).listFiles();if(files!=null)for(File f:files)f.delete();lastCode="";}});}
    static void setEnabled(Context c,boolean value){c.getSharedPreferences("diagnostics",0).edit().putBoolean("enabled",value).putBoolean("foreground",value&&foreground).apply();}
}
