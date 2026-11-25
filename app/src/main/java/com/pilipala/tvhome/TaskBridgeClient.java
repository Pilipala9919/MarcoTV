// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;

/** The only Binder handoff is the shell-protected provider. No network listener or ADB keys. */
final class TaskBridgeClient {
    private static final class ConnectionSlot {
        final long gen;
        final IBinder binder;
        ConnectionSlot(long g,IBinder b){gen=g;binder=b;}
    }
    private static final Object slotLock=new Object();
    private static volatile IBinder service;
    private static volatile Context appContext;
    private static final java.util.concurrent.atomic.AtomicLong attachGeneration=new java.util.concurrent.atomic.AtomicLong();
    private static volatile ConnectionSlot currentSlot=new ConnectionSlot(0L,null);
    private static volatile long pendingRetryGeneration=-1L;
    private static volatile boolean volumeAssistReady;
    private static volatile boolean volumeAudioReady;
    private static volatile boolean volumeAudioWriteFailed;
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
    static boolean volumeAudioWriteFailed(){
        return connected()&&volumeAudioWriteFailed;
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
    private static boolean isSlotStale(long gen,IBinder pinned){
        ConnectionSlot slot=currentSlot;
        return slot.gen!=gen||slot.binder!=pinned||pinned==null||!pinned.isBinderAlive();
    }
    static void resetForTest(){
        synchronized(slotLock){
            long gen=attachGeneration.incrementAndGet();
            service=null;
            currentSlot=new ConnectionSlot(gen,null);
            pendingRetryGeneration=-1L;
            appContext=null;
            volumeAssistReady=false;
            volumeAudioReady=false;
            volumeAudioWriteFailed=false;
            volumeInputDevices=0;
            volumeAssistReason="disconnected";
        }
        stateListeners.clear();
    }
    static void attach(IBinder binder){
        attach(null,binder);
    }
    static void attach(Context context,IBinder binder){
        final long boundGen;
        synchronized(slotLock){
            boundGen=attachGeneration.incrementAndGet();
            if(context!=null)appContext=context.getApplicationContext();
            service=binder;
            currentSlot=new ConnectionSlot(boundGen,binder);
            pendingRetryGeneration=-1L;
            volumeAssistReady=false;
            volumeAudioReady=false;
            volumeAudioWriteFailed=false;
            volumeInputDevices=0;
            volumeAssistReason=binder==null?"disconnected":"initializing";
        }
        if(binder==null){
            notifyStateListener();
            return;
        }
        try{
            binder.linkToDeath(()->{
                boolean cleared=false;
                synchronized(slotLock){
                    if(currentSlot.gen==boundGen&&service==binder){
                        long nextGen=attachGeneration.incrementAndGet();
                        service=null;
                        currentSlot=new ConnectionSlot(nextGen,null);
                        pendingRetryGeneration=-1L;
                        volumeAssistReady=false;
                        volumeAudioReady=false;
                        volumeAudioWriteFailed=false;
                        volumeInputDevices=0;
                        volumeAssistReason="disconnected";
                        cleared=true;
                    }
                }
                if(cleared){
                    Context ctx=appContext;
                    if(ctx!=null)HomeKeyService.syncRuntimeKeyFilter(ctx);
                    notifyStateListener();
                }
            },0);
        }catch(RemoteException e){
            synchronized(slotLock){
                if(currentSlot.gen==boundGen&&service==binder){
                    long nextGen=attachGeneration.incrementAndGet();
                    service=null;
                    currentSlot=new ConnectionSlot(nextGen,null);
                    pendingRetryGeneration=-1L;
                    volumeAssistReady=false;
                    volumeAudioReady=false;
                    volumeAudioWriteFailed=false;
                    volumeInputDevices=0;
                    volumeAssistReason="disconnected";
                }
            }
        }
    }
    static boolean supportsVolumeAssistProtocol(JSONObject status){
        return status!=null
                &&status.optInt("volumeProtocolVersion",0)>=TaskBridgeServer.VOLUME_PROTOCOL_VERSION
                &&status.has("volumeAudioReady")
                &&status.has("volumeInputDeviceCount")
                &&status.has("volumeAssistReason");
    }
    static void onServerStateChanged(Context context,JSONObject status){
        ConnectionSlot slot=currentSlot;
        onServerStateChanged(context,slot.binder,status);
    }
    static void onServerStateChanged(Context context,IBinder sender,JSONObject status){
        if(status==null)return;
        ConnectionSlot slot=currentSlot;
        if(sender==null||slot.binder!=sender)return;
        if(context!=null)appContext=context.getApplicationContext();
        Context ctx=appContext;
        boolean allowed=ctx!=null&&shouldEnableVolumeAssist(ctx);
        applyServerStatusForSlot(ctx,slot.gen,sender,allowed,status);
    }
    private static boolean applyServerStatusForSlot(Context ctx,long gen,IBinder pinned,boolean allowedByClient,JSONObject status){
        boolean changed;
        boolean readyChanged;
        synchronized(slotLock){
            if(currentSlot.gen!=gen||currentSlot.binder!=pinned)return false;
            boolean alive=pinned!=null&&pinned.isBinderAlive();
            boolean prevReady=volumeAssistReady;
            boolean prevAudio=volumeAudioReady;
            boolean prevWriteFailed=volumeAudioWriteFailed;
            int prevDevices=volumeInputDevices;
            String prevReason=volumeAssistReason;
            if(!supportsVolumeAssistProtocol(status)){
                volumeAssistReady=false;
                volumeAudioReady=false;
                volumeAudioWriteFailed=false;
                volumeInputDevices=0;
                volumeAssistReason=alive?"bridge_update_required":"disconnected";
            }else{
                boolean audioOk=status.optBoolean("volumeAudioReady",false);
                boolean writeFailed=status.optBoolean("volumeAudioWriteFailed",false);
                int devices=status.optInt("volumeInputDeviceCount",0);
                boolean nextReady=alive&&allowedByClient&&status.optBoolean("volumeAssistActive",false)&&audioOk&&!writeFailed&&devices>0;
                String reason=status.optString("volumeAssistReason",nextReady?"ready":(devices<=0?"no_volume_input_device":"unavailable"));
                volumeAssistReady=nextReady;
                volumeAudioReady=audioOk&&!writeFailed;
                volumeAudioWriteFailed=writeFailed;
                volumeInputDevices=devices;
                volumeAssistReason=nextReady?"ready":(!allowedByClient?"key_filter_inactive":reason);
            }
            readyChanged=(prevReady!=volumeAssistReady);
            changed=readyChanged||(prevAudio!=volumeAudioReady)||(prevWriteFailed!=volumeAudioWriteFailed)
                    ||(prevDevices!=volumeInputDevices)||!prevReason.equals(volumeAssistReason);
        }
        if(changed){
            if(ctx!=null&&readyChanged)HomeKeyService.syncRuntimeKeyFilter(ctx);
            notifyStateListener();
        }
        return true;
    }
    private static final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"Marco-bridge-sync");
        t.setDaemon(true);
        return t;
    });
    static void sync(Context c){
        sync(c,false);
    }
    static void retryVolumeAssist(Context c){
        synchronized(slotLock){
            if(currentSlot.binder!=null&&currentSlot.binder.isBinderAlive()){
                pendingRetryGeneration=currentSlot.gen;
            }
        }
        sync(c,true);
    }
    static void clearPendingVolumeAssistRetry(){
        synchronized(slotLock){
            pendingRetryGeneration=-1L;
        }
    }
    private static boolean isKeyFilterTargetActive(Context context){
        return !HomeSetup.paused(context)
                &&!SystemIntegration.homeIsOurs(context)
                &&SystemIntegration.guardMode(context)==HomeSetupPolicy.GuardMode.KEY_FILTER;
    }
    private static void sync(Context c,final boolean explicitRetry){
        Context app=c.getApplicationContext();
        appContext=app;
        final ConnectionSlot queued=currentSlot;
        worker.execute(()->{
            if(currentSlot.gen!=queued.gen)return;
            IBinder active=queued.binder;
            if(active==null||!active.isBinderAlive()){
                boolean cleared=false;
                synchronized(slotLock){
                    if(currentSlot.gen==queued.gen&&!connected()
                            &&(volumeAssistReady||volumeInputDevices!=0||volumeAudioReady||volumeAudioWriteFailed)){
                        volumeAssistReady=false;
                        volumeAudioReady=false;
                        volumeAudioWriteFailed=false;
                        volumeInputDevices=0;
                        volumeAssistReason="disconnected";
                        cleared=true;
                    }
                }
                if(cleared){
                    HomeKeyService.syncRuntimeKeyFilter(app);
                    notifyStateListener();
                }
                return;
            }
            try{
                configure(app,explicitRetry);
            }catch(Exception ignored){
                boolean cleared=false;
                synchronized(slotLock){
                    if(currentSlot.gen==queued.gen&&currentSlot.binder==active
                            &&(volumeAssistReady||volumeInputDevices!=0||volumeAudioReady||volumeAudioWriteFailed)){
                        volumeAssistReady=false;
                        volumeAudioReady=false;
                        volumeAudioWriteFailed=false;
                        volumeInputDevices=0;
                        volumeAssistReason="disconnected";
                        cleared=true;
                    }
                }
                if(cleared){
                    HomeKeyService.syncRuntimeKeyFilter(app);
                    notifyStateListener();
                }
            }
        });
    }
    private static JSONObject callOn(IBinder binder,JSONObject request)throws Exception{
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
    static JSONObject call(JSONObject request)throws Exception{
        return callOn(service,request);
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
        return configure(context,false);
    }
    static JSONObject configure(Context context,boolean explicitRetryVolumeAssist)throws Exception{
        appContext=context.getApplicationContext();
        final int maxAttempts=4;
        for(int attempt=0;attempt<maxAttempts;attempt++){
            ConnectionSlot slot=currentSlot;
            IBinder pinned=slot.binder;
            long gen=slot.gen;
            if(pinned==null||!pinned.isBinderAlive())throw new IOException("任务服务未连接");
            String homePkg=configuredHomePackage(context);
            boolean wake=shouldEnableWake(context);
            boolean wantsVolAssist=shouldEnableVolumeAssist(context);
            boolean keyFilterTarget=isKeyFilterTargetActive(context);
            boolean shouldRetry;
            synchronized(slotLock){
                if(currentSlot.gen==gen&&currentSlot.binder==pinned){
                    if(explicitRetryVolumeAssist&&keyFilterTarget){
                        pendingRetryGeneration=gen;
                    }else if(!keyFilterTarget&&pendingRetryGeneration==gen){
                        pendingRetryGeneration=-1L;
                    }
                }
                shouldRetry=(explicitRetryVolumeAssist&&keyFilterTarget)||(pendingRetryGeneration==gen&&keyFilterTarget);
            }
            try{
                // Pin protocol probe, configure write, and status readback to ONE (gen, IBinder).
                // If peer is replaced mid-handshake, discard stale result and re-handshake for the new connection.
                JSONObject preStatus=callOn(pinned,new JSONObject().put("action","status"));
                if(isSlotStale(gen,pinned)){
                    if(currentSlot.binder==null)throw new IOException("任务服务连接已变更");
                    continue;
                }
                boolean peerSupportsCurrent=supportsVolumeAssistProtocol(preStatus);
                boolean sendVolAssist=peerSupportsCurrent&&wantsVolAssist;
                boolean sendRetry=false;
                synchronized(slotLock){
                    if(currentSlot.gen==gen&&currentSlot.binder==pinned){
                        if(!peerSupportsCurrent){
                            if(pendingRetryGeneration==gen)pendingRetryGeneration=-1L;
                        }else if(sendVolAssist&&(explicitRetryVolumeAssist||pendingRetryGeneration==gen)&&keyFilterTarget){
                            // Atomically claim the one-shot retry so concurrent sync/configure calls never fire it twice.
                            sendRetry=true;
                            if(pendingRetryGeneration==gen)pendingRetryGeneration=-1L;
                        }
                    }
                }
                JSONObject req=new JSONObject().put("action","configure").put("wake",wake).put("home",homePkg).put("volumeAssist",sendVolAssist);
                if(sendRetry)req.put("retryVolumeAssist",true);
                callOn(pinned,req);
                if(isSlotStale(gen,pinned)){
                    if(currentSlot.binder==null)throw new IOException("任务服务连接已变更");
                    continue;
                }
                JSONObject status=callOn(pinned,new JSONObject().put("action","status"));
                if(isSlotStale(gen,pinned)){
                    if(currentSlot.binder==null)throw new IOException("任务服务连接已变更");
                    continue;
                }
                if(applyServerStatusForSlot(appContext,gen,pinned,sendVolAssist,status)){
                    return status;
                }
                if(currentSlot.binder==null)throw new IOException("任务服务连接已变更");
            }catch(Exception e){
                if(isSlotStale(gen,pinned)&&currentSlot.binder!=null)continue;
                throw e;
            }
        }
        throw new IOException("任务服务连接频繁切换，请重试");
    }
}
