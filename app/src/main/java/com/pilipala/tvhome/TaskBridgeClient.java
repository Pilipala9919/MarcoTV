// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;

/** The only Binder handoff is the shell-protected provider. No network listener or ADB keys. */
final class TaskBridgeClient {
    private static volatile IBinder service;
    private static volatile Context appContext;
    private static volatile boolean volumeAssistReady;
    private static volatile int volumeInputDevices;
    static boolean connected(){
        IBinder binder=service;
        return binder!=null&&binder.isBinderAlive();
    }
    static boolean volumeAssistActive(){
        return connected()&&volumeAssistReady;
    }
    static int volumeInputDeviceCount(){
        return connected()?volumeInputDevices:0;
    }
    static void resetForTest(){
        service=null;
        appContext=null;
        volumeAssistReady=false;
        volumeInputDevices=0;
    }
    static void attach(IBinder binder){
        attach(null,binder);
    }
    static void attach(Context context,IBinder binder){
        if(context!=null)appContext=context.getApplicationContext();
        service=binder;
        volumeAssistReady=false;
        volumeInputDevices=0;
        if(binder==null)return;
        try{
            binder.linkToDeath(()->{
                if(service==binder){
                    service=null;
                    volumeAssistReady=false;
                    volumeInputDevices=0;
                    Context ctx=appContext;
                    if(ctx!=null)HomeKeyService.syncRuntimeKeyFilter(ctx);
                }
            },0);
        }catch(RemoteException e){
            service=null;
            volumeAssistReady=false;
            volumeInputDevices=0;
        }
    }
    private static final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"Marco-bridge-sync");
        t.setDaemon(true);
        return t;
    });
    static void sync(Context c){
        Context app=c.getApplicationContext();
        appContext=app;
        worker.execute(()->{
            try{
                configure(app);
            }catch(Exception ignored){
                if(volumeAssistReady||volumeInputDevices!=0){
                    volumeAssistReady=false;
                    volumeInputDevices=0;
                    HomeKeyService.syncRuntimeKeyFilter(app);
                }
            }
        });
    }
    static JSONObject call(JSONObject request)throws Exception{
        IBinder binder=service;
        if(binder==null||!binder.isBinderAlive())throw new IOException("任务服务未连接");
        Parcel data=Parcel.obtain(),result=Parcel.obtain();
        try{
            data.writeInterfaceToken(TaskBridgeServer.DESCRIPTOR);
            data.writeString(request.toString());
            if(!binder.transact(IBinder.FIRST_CALL_TRANSACTION,data,result,0))throw new IOException("unsupported bridge");
            result.readException();
            String json=result.readString();
            if(json==null||json.length()>500000)throw new IOException("response limit");
            JSONObject reply=new JSONObject(json);
            if(!reply.optBoolean("ok"))throw new IOException(reply.optString("error","桥接请求失败"));
            return reply;
        }finally{
            data.recycle();
            result.recycle();
        }
    }
    static JSONObject request(String action)throws Exception{
        return call(new JSONObject().put("action",action));
    }
    static JSONObject task(String action,int id)throws Exception{
        return call(new JSONObject().put("action",action).put("id",id));
    }
    static String configuredHomePackage(Context context){
        ComponentName original=ComponentName.unflattenFromString(SystemIntegration.trackedHome(context));
        return original==null?"":original.getPackageName();
    }
    static boolean shouldEnableWake(Context context){
        SharedPreferences p=SystemIntegration.prefs(context);
        String homePkg=configuredHomePackage(context);
        boolean wakePref=!HomeSetup.unlocked(context)||context.getSharedPreferences("home",0).getBoolean("recent_wake",true);
        return !HomeSetup.paused(context)
                &&!SystemIntegration.homeIsOurs(context)
                &&p.getBoolean("home_compat",false)
                &&!p.getBoolean("setup_accessibility_pending",false)
                &&SystemIntegration.guardMode(context).handlesWakeFallback()
                &&HomeSetup.serviceEnabled(context)
                &&wakePref
                &&!homePkg.isEmpty();
    }
    static boolean shouldEnableVolumeAssist(Context context){
        SharedPreferences p=SystemIntegration.prefs(context);
        return !HomeSetup.paused(context)
                &&!SystemIntegration.homeIsOurs(context)
                &&p.getBoolean("home_compat",false)
                &&!p.getBoolean("setup_accessibility_pending",false)
                &&SystemIntegration.guardMode(context)==HomeSetupPolicy.GuardMode.KEY_FILTER
                &&HomeSetup.serviceEnabled(context)
                &&HomeKeyService.connected;
    }
    static JSONObject configure(Context context)throws Exception{
        appContext=context.getApplicationContext();
        String homePkg=configuredHomePackage(context);
        boolean wake=shouldEnableWake(context);
        boolean volAssist=shouldEnableVolumeAssist(context);
        call(new JSONObject().put("action","configure").put("wake",wake).put("home",homePkg).put("volumeAssist",volAssist));
        JSONObject status=request("status");
        boolean prevReady=volumeAssistReady;
        boolean nextReady=volAssist&&status.optBoolean("volumeAssistActive",false);
        volumeAssistReady=nextReady;
        volumeInputDevices=status.optInt("volumeInputDeviceCount",0);
        if(prevReady!=nextReady)HomeKeyService.syncRuntimeKeyFilter(appContext);
        return status;
    }
}
