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
    private static final java.util.concurrent.atomic.AtomicLong attachGeneration=new java.util.concurrent.atomic.AtomicLong();
    private static volatile boolean volumeAssistReady;
    private static volatile boolean volumeAudioReady;
    private static volatile int volumeInputDevices;
    private static volatile String volumeAssistReason="disconnected";
    private static final java.util.concurrent.CopyOnWriteArraySet<Runnable> stateListeners=new java.util.concurrent.CopyOnWriteArraySet<>();
    static boolean connected(){
        IBinder binder=service;
        return binder!=null&&binder.isBinderAlive();
    }
    static boolean volumeAssistActive(){
        return connected()&&volumeAssistReady;
    }
    static boolean volumeAudioReady(){
        return connected()&&volumeAudioReady;
    }
    static int volumeInputDeviceCount(){
        return connected()?volumeInputDevices:0;
    }
    static String volumeAssistReason(){
        return connected()?volumeAssistReason:"disconnected";
    }
    static void addStateListener(Runnable listener){
        if(listener!=null)stateListeners.add(listener);
    }
    static void removeStateListener(Runnable listener){
        if(listener!=null)stateListeners.remove(listener);
    }
    static void setStateListener(Runnable listener){
        stateListeners.clear();
        if(listener!=null)stateListeners.add(listener);
    }
    static void notifyStateChanged(){
        notifyStateListener();
    }
    private static void notifyStateListener(){
        if(stateListeners.isEmpty())return;
        for(Runnable l:stateListeners){
            try{
                new Handler(Looper.getMainLooper()).post(l);
            }catch(Throwable ignored){}
        }
    }
    static void resetForTest(){
        attachGeneration.incrementAndGet();
        service=null;
        appContext=null;
        volumeAssistReady=false;
        volumeAudioReady=false;
        volumeInputDevices=0;
        volumeAssistReason="disconnected";
        stateListeners.clear();
    }
    static void attach(IBinder binder){
        attach(null,binder);
    }
    static void attach(Context context,IBinder binder){
        attachGeneration.incrementAndGet();
        if(context!=null)appContext=context.getApplicationContext();
        service=binder;
        volumeAssistReady=false;
        volumeAudioReady=false;
        volumeInputDevices=0;
        volumeAssistReason=binder==null?"disconnected":"initializing";
        if(binder==null){
            notifyStateListener();
            return;
        }
        try{
            binder.linkToDeath(()->{
                if(service==binder){
                    attachGeneration.incrementAndGet();
                    service=null;
                    volumeAssistReady=false;
                    volumeAudioReady=false;
                    volumeInputDevices=0;
                    volumeAssistReason="disconnected";
                    Context ctx=appContext;
                    if(ctx!=null)HomeKeyService.syncRuntimeKeyFilter(ctx);
                    notifyStateListener();
                }
            },0);
        }catch(RemoteException e){
            service=null;
            volumeAssistReady=false;
            volumeAudioReady=false;
            volumeInputDevices=0;
            volumeAssistReason="disconnected";
        }
    }
    static void onServerStateChanged(Context context,JSONObject status){
        if(status==null)return;
        if(context!=null)appContext=context.getApplicationContext();
        Context ctx=appContext;
        boolean allowed=ctx!=null&&shouldEnableVolumeAssist(ctx);
        applyServerStatus(ctx,allowed,status);
    }
    private static void applyServerStatus(Context ctx,boolean allowedByClient,JSONObject status){
        boolean prevReady=volumeAssistReady;
        int prevDevices=volumeInputDevices;
        String prevReason=volumeAssistReason;
        boolean audioOk=status.optBoolean("volumeAudioReady",status.optBoolean("volumeAssistActive",false));
        int devices=status.optInt("volumeInputDeviceCount",0);
        boolean nextReady=connected()&&allowedByClient&&status.optBoolean("volumeAssistActive",false)&&audioOk&&devices>0;
        String reason=status.optString("volumeAssistReason",nextReady?"ready":(devices<=0?"no_volume_input_device":"unavailable"));
        volumeAssistReady=nextReady;
        volumeAudioReady=audioOk;
        volumeInputDevices=devices;
        volumeAssistReason=nextReady?"ready":(!allowedByClient?"key_filter_inactive":reason);
        boolean changed=(prevReady!=volumeAssistReady)||(prevDevices!=volumeInputDevices)||!prevReason.equals(volumeAssistReason);
        if(changed){
            if(ctx!=null&&prevReady!=volumeAssistReady)HomeKeyService.syncRuntimeKeyFilter(ctx);
            notifyStateListener();
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
        final long queuedGen=attachGeneration.get();
        worker.execute(()->{
            if(attachGeneration.get()!=queuedGen)return;
            IBinder active=service;
            if(active==null||!active.isBinderAlive()){
                if(attachGeneration.get()==queuedGen&&!connected()
                        &&(volumeAssistReady||volumeInputDevices!=0||volumeAudioReady)){
                    volumeAssistReady=false;
                    volumeAudioReady=false;
                    volumeInputDevices=0;
                    volumeAssistReason="disconnected";
                    HomeKeyService.syncRuntimeKeyFilter(app);
                    notifyStateListener();
                }
                return;
            }
            try{
                configure(app);
            }catch(Exception ignored){
                if(attachGeneration.get()==queuedGen&&service==active
                        &&(volumeAssistReady||volumeInputDevices!=0||volumeAudioReady)){
                    volumeAssistReady=false;
                    volumeAudioReady=false;
                    volumeInputDevices=0;
                    volumeAssistReason="disconnected";
                    HomeKeyService.syncRuntimeKeyFilter(app);
                    notifyStateListener();
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
                &&HomeKeyService.connected
                &&HomeKeyService.runtimeModeApplied
                &&HomeKeyService.runtimeKeyFilterRequested
                &&!HomeKeyService.runtimeVolumeYieldActive;
    }
    static JSONObject configure(Context context)throws Exception{
        appContext=context.getApplicationContext();
        String homePkg=configuredHomePackage(context);
        boolean wake=shouldEnableWake(context);
        boolean volAssist=shouldEnableVolumeAssist(context);
        call(new JSONObject().put("action","configure").put("wake",wake).put("home",homePkg).put("volumeAssist",volAssist));
        JSONObject status=request("status");
        applyServerStatus(appContext,volAssist,status);
        return status;
    }
}
