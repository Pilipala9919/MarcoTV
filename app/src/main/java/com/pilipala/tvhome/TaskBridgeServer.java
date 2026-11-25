// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;

/** Started explicitly as shell by the setup tool. Binder UID authentication, fixed operations only. */
public final class TaskBridgeServer {
    static final String DESCRIPTOR="com.pilipala.tvhome.TaskBridge.v1";
    static final int EVENT_VOLUME_STATE=IBinder.FIRST_CALL_TRANSACTION+1;
    static final int VOLUME_PROTOCOL_VERSION=2;
    static final int STREAM_MUSIC=3;
    private static final int USE_DEFAULT_STREAM_TYPE=Integer.MIN_VALUE;
    private static final int FLAG_SHOW_UI=1;
    interface VolumeDeviceProbe {
        Set<String> probeVolumeCapablePaths();
    }
    interface VolumeStateListener {
        void onStateChanged(JSONObject status);
    }
    private final int appUid,user;
    private final Object manager,power,audio;
    private final Class<?> api,powerApi,audioApi;
    private volatile boolean wakeRecovery;
    private volatile boolean volumeAssist;
    private volatile boolean audioReadFailed;
    private volatile boolean audioWriteFailed;
    private volatile String audioFailureReason="";
    private volatile String originalHome="",lastWake="尚无唤醒记录";
    private long lastWakeTime;
    private volatile boolean connected;
    private volatile IBinder clientLifetime;
    private volatile String lastPublishedVolumeKey="";
    private final Object wakeMonitor=new Object();
    private final ScheduledExecutorService volumeScheduler=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"MarcoTV-vol-repeat");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentMap<String,InputReader> inputReaders=new ConcurrentHashMap<>();
    private final VolumeRepeatEngine volumeEngine;
    private static final class InputReader {
        final String path;
        volatile boolean stopped;
        volatile FileInputStream stream;
        InputReader(String path){ this.path=path; }
        void stop(){
            stopped=true;
            FileInputStream s=stream;
            if(s!=null){
                try{ s.close(); }catch(IOException ignored){}
            }
        }
    }
    private final Binder binder=new Binder(){
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            if(code==INTERFACE_TRANSACTION){
                reply.writeString(DESCRIPTOR);
                return true;
            }
            if(code!=FIRST_CALL_TRANSACTION)return super.onTransact(code,data,reply,flags);
            if(Binder.getCallingUid()!=appUid)throw new SecurityException("Marco TV only");
            data.enforceInterface(DESCRIPTOR);
            String json=data.readString();
            if(json==null||json.length()>4096)throw new SecurityException("request limit");
            long identity=Binder.clearCallingIdentity();
            try{
                JSONObject answer;
                try{
                    answer=handle(new JSONObject(json));
                }catch(Exception e){
                    answer=new JSONObject();
                    try{
                        answer.put("ok",false).put("error","系统暂不支持此操作或任务已结束");
                    }catch(JSONException impossible){}
                }
                reply.writeNoException();
                reply.writeString(answer.toString());
                return true;
            }finally{
                Binder.restoreCallingIdentity(identity);
            }
        }
    };
    static Object resolveService(int sdkInt,boolean taskManager,
            String taskManagerClass,String activityManagerClass,String activityManagerNativeClass)throws Exception{
        if(taskManager&&sdkInt>=29){
            return Class.forName(taskManagerClass).getDeclaredMethod("getService").invoke(null);
        }
        if(sdkInt>=26){
            try{
                return Class.forName(activityManagerClass).getDeclaredMethod("getService").invoke(null);
            }catch(NoSuchMethodException fallback){
                // Some firmware builds omit ActivityManager.getService(); fall back to ActivityManagerNative.
            }
        }
        return Class.forName(activityManagerNativeClass).getDeclaredMethod("getDefault").invoke(null);
    }
    private static Object activityManager()throws Exception{
        return resolveService(Build.VERSION.SDK_INT,false,
                "android.app.ActivityTaskManager","android.app.ActivityManager","android.app.ActivityManagerNative");
    }
    private static Object taskManager()throws Exception{
        return resolveService(Build.VERSION.SDK_INT,true,
                "android.app.ActivityTaskManager","android.app.ActivityManager","android.app.ActivityManagerNative");
    }
    static boolean invokeRemoveTask(Object manager,Class<?> api,int id)throws Exception{
        try{
            return (Boolean)api.getMethod("removeTask",int.class).invoke(manager,id);
        }catch(NoSuchMethodException fallback){
            return (Boolean)api.getMethod("removeTask",int.class,int.class).invoke(manager,id,0);
        }
    }
    static List<?> invokeGetTasks(Object manager,Class<?> api)throws Exception{
        for(Method method:api.getMethods())if(method.getName().equals("getTasks")){
            Class<?>[] types=method.getParameterTypes();
            Object[] args=new Object[types.length];
            boolean known=true;
            for(int i=0;i<types.length;i++){
                if(types[i]==int.class)args[i]=i==0?1:(types.length==2?0:-1);
                else if(types[i]==boolean.class)args[i]=false;
                else{
                    known=false;
                    break;
                }
            }
            if(!known)continue;
            Object result=method.invoke(manager,args);
            if(result instanceof List)return (List<?>)result;
        }
        return Collections.emptyList();
    }
    static boolean hasSupportedAdjustMethod(Class<?> audioApi){
        if(audioApi==null)return false;
        for(Method method:audioApi.getMethods()){
            String name=method.getName();
            Class<?>[] types=method.getParameterTypes();
            if("adjustStreamVolume".equals(name)&&types.length==4&&types[0]==int.class&&types[1]==int.class
                    &&types[2]==int.class&&types[3]==String.class){
                return true;
            }
            if("adjustSuggestedStreamVolume".equals(name)){
                if(types.length==5&&types[0]==int.class&&types[1]==int.class&&types[2]==int.class
                        &&types[3]==String.class&&types[4]==String.class)return true;
                if(types.length==6&&types[0]==int.class&&types[1]==int.class&&types[2]==int.class
                        &&types[3]==String.class&&types[4]==String.class&&(types[5]==int.class||types[5]==String.class))return true;
                if(types.length==7&&types[0]==int.class&&types[1]==int.class&&types[2]==int.class
                        &&types[3]==String.class&&types[4]==String.class&&types[5]==int.class&&types[6]==boolean.class)return true;
            }
        }
        return false;
    }
    static boolean isAudioControlCapable(Object audio,Class<?> audioApi,int streamType){
        if(audio==null||audioApi==null||!hasSupportedAdjustMethod(audioApi))return false;
        int cur=invokeGetStreamVolume(audio,audioApi,streamType);
        int min=invokeGetStreamMinVolume(audio,audioApi,streamType);
        int max=invokeGetStreamMaxVolume(audio,audioApi,streamType);
        return cur>=0&&min>=0&&max>min&&cur>=min&&cur<=max;
    }
    static boolean invokeAdjustStreamOrSuggestedVolume(Object audio,Class<?> audioApi,int direction,
            int streamType,int flags,String callingPkg,String caller,int callerUid)throws Exception{
        if(audio==null||audioApi==null)return false;
        int explicitStream=streamType==USE_DEFAULT_STREAM_TYPE?STREAM_MUSIC:streamType;
        // Prefer adjustStreamVolume on the exact same stream used for volume readback so read & adjust never diverge.
        for(Method method:audioApi.getMethods())if(method.getName().equals("adjustStreamVolume")){
            Class<?>[] types=method.getParameterTypes();
            if(types.length==4&&types[0]==int.class&&types[1]==int.class&&types[2]==int.class&&types[3]==String.class){
                method.invoke(audio,explicitStream,direction,flags,callingPkg);
                return true;
            }
        }
        return invokeAdjustSuggestedVolume(audio,audioApi,direction,explicitStream,flags,callingPkg,caller,callerUid);
    }
    static boolean invokeAdjustSuggestedVolume(Object audio,Class<?> audioApi,int direction,
            int streamType,int flags,String callingPkg,String caller,int callerUid)throws Exception{
        if(audio==null||audioApi==null)return false;
        for(Method method:audioApi.getMethods())if(method.getName().equals("adjustSuggestedStreamVolume")){
            Class<?>[] types=method.getParameterTypes();
            if(types.length==5&&types[0]==int.class&&types[1]==int.class&&types[2]==int.class
                    &&types[3]==String.class&&types[4]==String.class){
                method.invoke(audio,direction,streamType,flags,callingPkg,caller);
                return true;
            }
            if(types.length==6&&types[0]==int.class&&types[1]==int.class&&types[2]==int.class
                    &&types[3]==String.class&&types[4]==String.class&&types[5]==int.class){
                method.invoke(audio,direction,streamType,flags,callingPkg,caller,callerUid);
                return true;
            }
            if(types.length==6&&types[0]==int.class&&types[1]==int.class&&types[2]==int.class
                    &&types[3]==String.class&&types[4]==String.class&&types[5]==String.class){
                method.invoke(audio,direction,streamType,flags,callingPkg,caller,null);
                return true;
            }
            if(types.length==7&&types[0]==int.class&&types[1]==int.class&&types[2]==int.class
                    &&types[3]==String.class&&types[4]==String.class&&types[5]==int.class&&types[6]==boolean.class){
                method.invoke(audio,direction,streamType,flags,callingPkg,caller,callerUid,false);
                return true;
            }
        }
        int fallbackStream=streamType==USE_DEFAULT_STREAM_TYPE?STREAM_MUSIC:streamType;
        for(Method method:audioApi.getMethods())if(method.getName().equals("adjustStreamVolume")){
            Class<?>[] types=method.getParameterTypes();
            if(types.length==4&&types[0]==int.class&&types[1]==int.class&&types[2]==int.class&&types[3]==String.class){
                method.invoke(audio,fallbackStream,direction,flags,callingPkg);
                return true;
            }
        }
        return false;
    }
    static int invokeGetStreamVolume(Object audio,Class<?> audioApi,int streamType){
        if(audio==null||audioApi==null)return -1;
        try{
            Object v=audioApi.getMethod("getStreamVolume",int.class).invoke(audio,streamType);
            return v instanceof Number?((Number)v).intValue():-1;
        }catch(Exception ignored){
            return -1;
        }
    }
    static int invokeGetStreamMinVolume(Object audio,Class<?> audioApi,int streamType){
        if(audio==null||audioApi==null)return -1;
        try{
            Object v=audioApi.getMethod("getStreamMinVolume",int.class).invoke(audio,streamType);
            return v instanceof Number?((Number)v).intValue():-1;
        }catch(NoSuchMethodException olderApi){
            return 0;
        }catch(Exception ignored){
            return -1;
        }
    }
    static int invokeGetStreamMaxVolume(Object audio,Class<?> audioApi,int streamType){
        if(audio==null||audioApi==null)return -1;
        try{
            Object v=audioApi.getMethod("getStreamMaxVolume",int.class).invoke(audio,streamType);
            return v instanceof Number?((Number)v).intValue():-1;
        }catch(Exception ignored){
            return -1;
        }
    }
    /**
     * Parses `getevent -p` (or `getevent -lp`) output and returns only `/dev/input/event*` paths
     * whose EV_KEY (0001) capability table declares BOTH KEY_VOLUMEDOWN (0072) and KEY_VOLUMEUP (0073).
     */
    static Set<String> parseVolumeCapableEventPaths(String geteventOutput){
        Set<String> result=new LinkedHashSet<>();
        if(geteventOutput==null||geteventOutput.isEmpty())return result;
        String currentPath=null;
        boolean inKeySection=false;
        boolean hasVolDown=false;
        boolean hasVolUp=false;
        for(String raw:geteventOutput.split("\\r?\\n")){
            String trimmed=raw.trim();
            if(trimmed.startsWith("add device ")){
                if(currentPath!=null&&hasVolDown&&hasVolUp)result.add(currentPath);
                currentPath=null;
                inKeySection=false;
                hasVolDown=false;
                hasVolUp=false;
                int colon=trimmed.indexOf(':');
                if(colon>=0){
                    String candidate=trimmed.substring(colon+1).trim();
                    if(candidate.matches("/dev/input/event[0-9]+"))currentPath=candidate;
                }
                continue;
            }
            if(currentPath==null)continue;
            if(trimmed.startsWith("KEY (0001):")){
                inKeySection=true;
                String rest=trimmed.substring("KEY (0001):".length());
                if(containsKeyToken(rest,"0072","KEY_VOLUMEDOWN"))hasVolDown=true;
                if(containsKeyToken(rest,"0073","KEY_VOLUMEUP"))hasVolUp=true;
                continue;
            }
            if(trimmed.startsWith("name:")||trimmed.startsWith("events:")||trimmed.startsWith("input props:")
                    ||trimmed.matches("[A-Z]+ \\([0-9a-fA-F]{4}\\):.*")){
                inKeySection=false;
                continue;
            }
            if(inKeySection){
                if(containsKeyToken(trimmed,"0072","KEY_VOLUMEDOWN"))hasVolDown=true;
                if(containsKeyToken(trimmed,"0073","KEY_VOLUMEUP"))hasVolUp=true;
            }
        }
        if(currentPath!=null&&hasVolDown&&hasVolUp)result.add(currentPath);
        return result;
    }
    private static boolean containsKeyToken(String line,String hexCode,String symbolName){
        if(line==null||line.isEmpty())return false;
        for(String rawToken:line.trim().split("\\s+")){
            // AOSP getevent -p / -lp appends '*' to keys that are currently pressed (e.g. "0072*", "KEY_VOLUMEUP*").
            String token=rawToken;
            while(token.endsWith("*"))token=token.substring(0,token.length()-1);
            if(token.isEmpty())continue;
            if(hexCode.equalsIgnoreCase(token)||symbolName.equals(token))return true;
        }
        return false;
    }
    /**
     * Reads stdout/stderr from a child process with a strict wall-clock deadline that also aborts
     * blocking reads and never uses API 26+ {@code Process.waitFor(long, TimeUnit)} (API 23+ safe).
     * Returns {@code null} on non-zero exit code, timeout, truncation, or I/O failure so callers can
     * reliably distinguish a failed probe from a clean scan returning 0 devices.
     */
    static String readProcessOutputWithTimeout(final java.lang.Process process,long timeoutMs,final int maxBytes){
        if(process==null)return null;
        final ByteArrayOutputStream out=new ByteArrayOutputStream();
        final boolean[] readOk={false};
        final boolean[] truncated={false};
        final Thread reader=new Thread(()->{
            InputStream in=process.getInputStream();
            try{
                byte[] buf=new byte[2048];
                int n;
                while((n=in.read(buf))!=-1){
                    int remaining=maxBytes-out.size();
                    if(remaining>0){
                        out.write(buf,0,Math.min(n,remaining));
                    }
                    if(out.size()>=maxBytes){
                        truncated[0]=true;
                        break;
                    }
                }
                readOk[0]=true;
            }catch(Throwable ignored){
            }finally{
                try{ in.close(); }catch(Throwable ignored){}
            }
        },"MarcoTV-probe-read");
        reader.setDaemon(true);
        reader.start();
        final long deadlineNs=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.max(1L,timeoutMs));
        try{
            long remainingMs=Math.max(1L,TimeUnit.NANOSECONDS.toMillis(deadlineNs-System.nanoTime()));
            reader.join(remainingMs);
            if(reader.isAlive()){
                destroyProcessQuietly(process);
                reader.interrupt();
                reader.join(250);
                return null;
            }
            if(truncated[0]){
                destroyProcessQuietly(process);
                return null;
            }
            if(!readOk[0]){
                destroyProcessQuietly(process);
                return null;
            }
            while(true){
                try{
                    int exitCode=process.exitValue();
                    return exitCode==0?out.toString("UTF-8"):null;
                }catch(IllegalThreadStateException stillRunning){
                    long leftNs=deadlineNs-System.nanoTime();
                    if(leftNs<=0){
                        destroyProcessQuietly(process);
                        return null;
                    }
                    Thread.sleep(Math.min(20L,Math.max(1L,TimeUnit.NANOSECONDS.toMillis(leftNs))));
                }
            }
        }catch(InterruptedException ie){
            destroyProcessQuietly(process);
            Thread.currentThread().interrupt();
            return null;
        }catch(Throwable ignored){
            destroyProcessQuietly(process);
            return null;
        }finally{
            destroyProcessQuietly(process);
        }
    }
    private static void destroyProcessQuietly(java.lang.Process p){
        if(p==null)return;
        // Always destroy the child BEFORE touching streams so a reader thread blocked in read()
        // receives EOF instead of deadlocking on the InputStream monitor during close().
        try{ p.destroy(); }catch(Throwable ignored){}
        try{ p.getOutputStream().close(); }catch(Throwable ignored){}
        try{ p.getErrorStream().close(); }catch(Throwable ignored){}
    }
    private static Set<String> probeVolumeCapablePathsLive(){
        try{
            java.lang.Process p=new ProcessBuilder("/system/bin/getevent","-p").redirectErrorStream(true).start();
            String output=readProcessOutputWithTimeout(p,3000L,262144);
            if(output==null)return null;
            return parseVolumeCapableEventPaths(output);
        }catch(Throwable ignored){
            return null;
        }
    }
    /** Pure-testable evaluator shared by TaskBridgeServer status, hotplug push, and unit checks. */
    static JSONObject buildVolumeStatusJson(boolean configured,boolean connected,
                                            Object audio,Class<?> audioApi,boolean audioRuntimeFailed,
                                            int volumeDeviceCount,long adjustCount,String lastStopReason)throws JSONException{
        return buildVolumeStatusJson(configured,connected,audio,audioApi,false,audioRuntimeFailed,
                audioRuntimeFailed?"audio_runtime_failed":"",volumeDeviceCount,adjustCount,lastStopReason);
    }
    static JSONObject buildVolumeStatusJson(boolean configured,boolean connected,
                                            Object audio,Class<?> audioApi,
                                            boolean audioReadFailed,boolean audioWriteFailed,String failureReason,
                                            int volumeDeviceCount,long adjustCount,String lastStopReason)throws JSONException{
        boolean audioProbeOk=isAudioControlCapable(audio,audioApi,STREAM_MUSIC);
        boolean audioReady=!audioWriteFailed&&!audioReadFailed&&audioProbeOk;
        boolean inputReady=volumeDeviceCount>0;
        boolean active=configured&&connected&&audioReady&&inputReady;
        String reason;
        if(!configured)reason="disabled";
        else if(!connected)reason="disconnected";
        else if(audioWriteFailed)reason=(failureReason!=null&&!failureReason.isEmpty())?failureReason:"audio_adjust_failed";
        else if(audioReadFailed||!audioProbeOk)reason=(audioReadFailed&&failureReason!=null&&!failureReason.isEmpty())?failureReason:"audio_unavailable";
        else if(!inputReady)reason="no_volume_input_device";
        else reason="ready";
        return new JSONObject()
                .put("volumeProtocolVersion",VOLUME_PROTOCOL_VERSION)
                .put("volumeAssist",configured)
                .put("volumeAssistActive",active)
                .put("volumeAudioReady",audioReady)
                .put("volumeAudioWriteFailed",audioWriteFailed)
                .put("volumeInputReady",inputReady)
                .put("volumeInputDeviceCount",volumeDeviceCount)
                .put("volumeAdjustCount",adjustCount)
                .put("volumeLastStopReason",lastStopReason==null?"idle":lastStopReason)
                .put("volumeAssistReason",reason);
    }
    /** Pure-testable read/write audio health reconciliation used during periodic scans. */
    static final class AudioHealthState {
        boolean readFailed;
        boolean writeFailed;
        String failureReason="";
        void recordFailure(String reason){
            String r=(reason==null||reason.isEmpty())?"audio_runtime_failed":reason;
            failureReason=r;
            if("audio_adjust_failed".equals(r)||"audio_adjust_no_effect".equals(r)){
                writeFailed=true;
            }else{
                readFailed=true;
            }
        }
        void recordWriteSuccess(){
            writeFailed=false;
            if(!readFailed)failureReason="";
        }
        void onPeriodicReadProbe(boolean readCapable){
            // Periodic scan may recover a transient read failure once readback succeeds again,
            // but MUST NEVER clear a persistent write denial solely from readback!
            if(readCapable&&readFailed){
                readFailed=false;
                if(!writeFailed)failureReason="";
            }
        }
        void onExplicitRetry(){
            readFailed=false;
            writeFailed=false;
            failureReason="";
        }
        void onConfigure(boolean nextAssist,boolean explicitRetry){
            // Only an explicit user retry may clear sticky audio write failure; passive false -> true
            // transitions (such as 5s no-bridge yield expiry or periodic scans) MUST preserve it.
            if(shouldClearAudioFailureOnConfigure(nextAssist,explicitRetry))onExplicitRetry();
        }
    }
    static boolean shouldClearAudioFailureOnConfigure(boolean nextAssist,boolean explicitRetry){
        return nextAssist&&explicitRetry;
    }
    private TaskBridgeServer(int uid,int userId)throws Exception{
        appUid=uid;
        user=userId;
        manager=taskManager();
        api=Class.forName(Build.VERSION.SDK_INT>=29?"android.app.IActivityTaskManager":"android.app.IActivityManager");
        Object powerBinder=Class.forName("android.os.ServiceManager").getMethod("getService",String.class)
                .invoke(null,"power");
        power=Class.forName("android.os.IPowerManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,powerBinder);
        powerApi=Class.forName("android.os.IPowerManager");
        Object resolvedAudio=null;
        Class<?> resolvedAudioApi=null;
        try{
            Object audioBinder=Class.forName("android.os.ServiceManager").getMethod("getService",String.class)
                    .invoke(null,"audio");
            if(audioBinder!=null){
                resolvedAudio=Class.forName("android.media.IAudioService$Stub").getMethod("asInterface",IBinder.class)
                        .invoke(null,audioBinder);
                resolvedAudioApi=Class.forName("android.media.IAudioService");
            }
        }catch(Throwable ignored){}
        audio=resolvedAudio;
        audioApi=resolvedAudioApi;
        volumeEngine=new VolumeRepeatEngine((task,delayMs)->{
            ScheduledFuture<?> future=volumeScheduler.schedule(task,Math.max(0L,delayMs),TimeUnit.MILLISECONDS);
            return ()->future.cancel(false);
        },new VolumeRepeatEngine.Callbacks(){
            @Override public boolean isEnabled(){ return volumeAssist&&!audioWriteFailed&&!audioReadFailed; }
            @Override public boolean isConnected(){ return connected; }
            @Override public boolean isInteractive(){ return interactive(); }
            @Override public int readStreamVolume(){ return invokeGetStreamVolume(audio,audioApi,STREAM_MUSIC); }
            @Override public int readStreamMinVolume(){ return invokeGetStreamMinVolume(audio,audioApi,STREAM_MUSIC); }
            @Override public int readStreamMaxVolume(){ return invokeGetStreamMaxVolume(audio,audioApi,STREAM_MUSIC); }
            @Override public boolean adjustVolume(int direction){
                try{
                    boolean ok=invokeAdjustStreamOrSuggestedVolume(audio,audioApi,direction,STREAM_MUSIC,
                            FLAG_SHOW_UI,"com.android.shell","MarcoTV",android.os.Process.myUid());
                    if(ok&&audioWriteFailed){
                        audioWriteFailed=false;
                        if(!audioReadFailed)audioFailureReason="";
                        notifyClientVolumeStateIfChanged();
                    }
                    return ok;
                }catch(Throwable t){
                    return false;
                }
            }
            @Override public long nowMs(){ return SystemClock.elapsedRealtime(); }
            @Override public void onCapabilityFailure(String reason){
                String r=(reason==null||reason.isEmpty())?"audio_runtime_failed":reason;
                audioFailureReason=r;
                if("audio_adjust_failed".equals(r)||"audio_adjust_no_effect".equals(r)){
                    audioWriteFailed=true;
                }else{
                    audioReadFailed=true;
                }
                notifyClientVolumeStateIfChanged();
            }
        });
    }
    private boolean is64BitProcess(){
        try{
            return android.os.Process.is64Bit();
        }catch(Throwable ignored){
            String arch=System.getProperty("os.arch","");
            return arch!=null&&(arch.contains("64"));
        }
    }
    private void notifyClientVolumeStateIfChanged(){
        try{
            JSONObject vol=buildVolumeStatusJson(volumeAssist,connected,audio,audioApi,
                    audioReadFailed,audioWriteFailed,audioFailureReason,
                    inputReaders.size(),volumeEngine.totalAdjustments(),volumeEngine.lastStopReason());
            String key=vol.optBoolean("volumeAssistActive")+"|"+vol.optInt("volumeInputDeviceCount")
                    +"|"+vol.optBoolean("volumeAudioReady")+"|"+vol.optBoolean("volumeAudioWriteFailed")
                    +"|"+vol.optString("volumeAssistReason");
            if(key.equals(lastPublishedVolumeKey))return;
            lastPublishedVolumeKey=key;
            IBinder target=clientLifetime;
            if(target==null||!target.isBinderAlive())return;
            Parcel data=Parcel.obtain();
            try{
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeString(vol.toString());
                target.transact(EVENT_VOLUME_STATE,data,null,IBinder.FLAG_ONEWAY);
            }catch(Throwable ignored){
            }finally{
                data.recycle();
            }
        }catch(Throwable ignored){}
    }
    /** Returns currently-open reader paths that should be closed after a completed capability scan. */
    static Set<String> devicesToRemoveAfterScan(Set<String> openPaths,Set<String> scannedCapablePaths){
        Set<String> remove=new LinkedHashSet<>();
        if(openPaths==null||scannedCapablePaths==null)return remove;
        for(String path:openPaths){
            if(!scannedCapablePaths.contains(path))remove.add(path);
        }
        return remove;
    }
    private void syncVolumeReaders(){
        boolean audioCapable=isAudioControlCapable(audio,audioApi,STREAM_MUSIC);
        // Periodic readback probe may clear transient read failures, NEVER sticky write failures!
        if(audioCapable&&audioReadFailed){
            audioReadFailed=false;
            if(!audioWriteFailed)audioFailureReason="";
        }
        if(!volumeAssist||!connected||!audioCapable||audioWriteFailed){
            String stopReason=!connected?"bridge_disconnected":!volumeAssist?"mode_disabled"
                    :(audioWriteFailed?(audioFailureReason.isEmpty()?"audio_adjust_failed":audioFailureReason):"audio_unavailable");
            stopVolumeReaders(stopReason);
            notifyClientVolumeStateIfChanged();
            return;
        }
        Set<String> capablePaths=probeVolumeCapablePathsLive();
        if(capablePaths==null){
            // Probe timed out or failed transiently: keep currently healthy readers intact and never disrupt bridge.
            notifyClientVolumeStateIfChanged();
            return;
        }
        Set<String> present=new HashSet<>();
        for(String path:capablePaths){
            File f=new File(path);
            if(!f.exists())continue;
            present.add(path);
            if(inputReaders.containsKey(path))continue;
            try{
                FileInputStream fis=new FileInputStream(f);
                InputReader reader=new InputReader(path);
                reader.stream=fis;
                if(inputReaders.putIfAbsent(path,reader)!=null){
                    try{ fis.close(); }catch(IOException ignored){}
                    continue;
                }
                startInputReaderThread(reader);
            }catch(Throwable ignored){}
        }
        for(String removedPath:devicesToRemoveAfterScan(inputReaders.keySet(),present)){
            InputReader removed=inputReaders.remove(removedPath);
            if(removed!=null){
                removed.stop();
                volumeEngine.onDeviceDisconnected(removedPath);
            }
        }
        notifyClientVolumeStateIfChanged();
    }
    private void stopVolumeReaders(String reason){
        volumeEngine.cancelActive(reason);
        for(Map.Entry<String,InputReader> entry:new ArrayList<>(inputReaders.entrySet())){
            if(inputReaders.remove(entry.getKey(),entry.getValue())){
                entry.getValue().stop();
            }
        }
    }
    private void startInputReaderThread(InputReader reader){
        final boolean is64=is64BitProcess();
        final int evSize=VolumeRepeatEngine.eventSize(is64);
        Thread thread=new Thread(()->{
            byte[] buf=new byte[evSize];
            try(FileInputStream fis=reader.stream){
                while(!reader.stopped&&volumeAssist&&connected){
                    int offset=0;
                    while(offset<evSize){
                        int n=fis.read(buf,offset,evSize-offset);
                        if(n<0)return;
                        offset+=n;
                    }
                    VolumeRepeatEngine.InputKeyEvent ev=VolumeRepeatEngine.parseInputEvent(buf,is64);
                    if(ev!=null)volumeEngine.onKeyEvent(reader.path,ev.type,ev.code,ev.value);
                }
            }catch(Throwable ignored){
            }finally{
                inputReaders.remove(reader.path,reader);
                volumeEngine.onDeviceDisconnected(reader.path);
                notifyClientVolumeStateIfChanged();
            }
        },"MarcoTV-ev-"+new File(reader.path).getName());
        thread.setDaemon(true);
        thread.start();
    }
    private boolean interactive(){
        try{
            return (Boolean)powerApi.getMethod("isInteractive").invoke(power);
        }catch(Exception e){
            return false;
        }
    }
    private boolean canRemove(){
        try{
            Object am=activityManager();
            Class<?> cls=Class.forName("android.app.IActivityManager");
            String permission=Build.VERSION.SDK_INT>=29?"android.permission.MANAGE_ACTIVITY_TASKS":"android.permission.REMOVE_TASKS";
            return ((Integer)cls.getMethod("checkPermission",String.class,int.class,int.class).invoke(am,permission,android.os.Process.myPid(),
                    android.os.Process.myUid()))==0;
        }catch(Exception e){
            return false;
        }
    }
    private boolean canRemoveSingleStack(){
        if(Build.VERSION.SDK_INT!=28)return false;
        try{
            return ((Integer)api.getMethod("checkPermission",String.class,int.class,int.class).invoke(manager,"android.permission.MANAGE_ACTIVITY_STACKS",
                    android.os.Process.myPid(),android.os.Process.myUid()))==0;
        }catch(Exception e){
            return false;
        }
    }
    /** Android 9 fallback only: never remove a shared, pinned, home, or other-user stack. */
    private int singleTaskStack(int id)throws Exception{
        if(!canRemoveSingleStack())return -1;
        for(Object stack:(List<?>)api.getMethod("getAllStackInfos").invoke(manager)){
            Object ids=field(stack,"taskIds");
            if(!(ids instanceof int[])||((int[])ids).length!=1||((int[])ids)[0]!=id||number(stack,"userId",-1)!=user)continue;
            Object config=field(stack,"configuration"),window=field(config,"windowConfiguration");
            if(window==null)continue;
            int type=(Integer)window.getClass().getMethod("getActivityType").invoke(window),mode=(Integer)window.getClass()
                    .getMethod("getWindowingMode").invoke(window);
            if(type==1&&mode==1)return number(stack,"stackId",-1);
        }
        return -1;
    }
    static List<?> unwrapTaskList(Object slice)throws Exception{
        if(slice==null)return Collections.emptyList();
        if(slice instanceof List)return (List<?>)slice;
        Object list=slice.getClass().getMethod("getList").invoke(slice);
        return list instanceof List?(List<?>)list:Collections.emptyList();
    }
    private List<?> tasks()throws Exception{
        Object slice=api.getMethod("getRecentTasks",int.class,int.class,int.class).invoke(manager,60,0,user);
        return unwrapTaskList(slice);
    }
    private static Object field(Object object,String name){
        try{
            return object.getClass().getField(name).get(object);
        }catch(Exception e){
            return null;
        }
    }
    private static int number(Object object,String name,int fallback){
        Object n=field(object,name);
        return n instanceof Number?((Number)n).intValue():fallback;
    }
    private boolean excluded(Object task){
        Intent intent=(Intent)field(task,"baseIntent");
        String pkg=packageName(task);
        return pkg.isEmpty()||pkg.equals("com.pilipala.tvhome")||pkg.equals("com.android.systemui")||pkg.equals("android")||pkg.equals(originalHome)||pkg.equals("com.xiaomi.eetv.litehome")||(intent!=null&&intent.hasCategory(Intent.CATEGORY_HOME));
    }
    private String packageName(Object task){
        Object component=field(task,"topActivity");
        if(!(component instanceof ComponentName))component=field(task,"baseActivity");
        if(component instanceof ComponentName)return ((ComponentName)component).getPackageName();
        Intent intent=(Intent)field(task,"baseIntent");
        return intent!=null&&intent.getComponent()!=null?intent.getComponent().getPackageName():"";
    }
    private Object task(int id)throws Exception{
        for(Object task:tasks())if(number(task,"persistentId",number(task,"taskId",-1))==id&&!excluded(task)&&number(task,"userId",user)==user)return task;
        throw new IOException("task unavailable");
    }
    private JSONObject list()throws Exception{
        JSONArray rows=new JSONArray();
        for(Object task:tasks()){
            if(excluded(task)||number(task,"userId",user)!=user)continue;
            int id=number(task,"persistentId",number(task,"taskId",-1));
            if(id<0)continue;
            rows.put(new JSONObject().put("id",id).put("package",packageName(task)).put("removable",canRemove()||singleTaskStack(id)>=0));
        }
        return new JSONObject().put("tasks",rows);
    }
    private JSONObject snapshot(int id)throws Exception{
        task(id);
        Object snapshot=null;
        for(Method method:api.getMethods())if(method.getName().equals("getTaskSnapshot")){
            Class<?>[] types=method.getParameterTypes();
            if(types.length==2&&types[0]==int.class&&types[1]==boolean.class){
                snapshot=method.invoke(manager,id,false);
                break;
            }
            if(types.length==3&&types[0]==int.class&&types[1]==boolean.class&&types[2]==boolean.class){
                snapshot=method.invoke(manager,id,false,false);
                break;
            }
        }
        if(snapshot==null)return new JSONObject().put("available",false);
        Bitmap hardware=null,small=null;
        try{
            if(Build.VERSION.SDK_INT>=30){
                try{
                    Object hb=snapshot.getClass().getMethod("getHardwareBuffer").invoke(snapshot);
                    Object cs=snapshot.getClass().getMethod("getColorSpace").invoke(snapshot);
                    if(hb!=null){
                        for(Method m:Bitmap.class.getMethods())if(m.getName().equals("wrapHardwareBuffer")&&m.getParameterTypes().length==2){
                            hardware=(Bitmap)m.invoke(null,hb,cs);
                            break;
                        }
                        try{hb.getClass().getMethod("close").invoke(hb);}catch(Throwable ignored){}
                    }
                }catch(Throwable ignored){
                    return new JSONObject().put("available",false);
                }
            }else if(Build.VERSION.SDK_INT==28||Build.VERSION.SDK_INT==29){
                try{
                    Object buffer=snapshot.getClass().getMethod("getSnapshot").invoke(snapshot);
                    for(Method method:Bitmap.class.getDeclaredMethods())if(method.getName().equals("createHardwareBitmap")&&method.getParameterTypes().length==1){
                        method.setAccessible(true);
                        hardware=(Bitmap)method.invoke(null,buffer);
                        break;
                    }
                }catch(Throwable ignored){
                    return new JSONObject().put("available",false);
                }
            }else{
                return new JSONObject().put("available",false);
            }
            if(hardware==null)return new JSONObject().put("available",false);
            Bitmap copy=hardware.copy(Bitmap.Config.ARGB_8888,false);
            if(copy==null)return new JSONObject().put("available",false);
            float ratio=Math.min(1,640f/copy.getWidth());
            small=Bitmap.createScaledBitmap(copy,Math.max(1,(int)(copy.getWidth()*ratio)),Math.max(1,(int)(copy.getHeight()*ratio)),true);
            if(small!=copy)copy.recycle();
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG,78,out);
            if(out.size()>300000)return new JSONObject().put("available",false);
            return new JSONObject().put("available",true).put("image",android.util.Base64.encodeToString(out.toByteArray(),android.util.Base64.NO_WRAP));
        }finally{
            if(small!=null)small.recycle();
            if(hardware!=null)hardware.recycle();
        }
    }
    private JSONObject handle(JSONObject request)throws Exception{
        String action=request.getString("action");
        JSONObject answer;
        if(action.equals("status")){
            JSONObject vol=buildVolumeStatusJson(volumeAssist,connected,audio,audioApi,
                    audioReadFailed,audioWriteFailed,audioFailureReason,
                    inputReaders.size(),volumeEngine.totalAdjustments(),volumeEngine.lastStopReason());
            answer=new JSONObject().put("version",ProjectInfo.VERSION).put("wake",wakeRecovery)
                    .put("canRemove",canRemove()||canRemoveSingleStack()).put("removeMode",canRemove()?"task":canRemoveSingleStack()?"single_task_stack":"unavailable")
                    .put("lastWake",lastWake).put("lastWakeTime",lastWakeTime)
                    .put("volumeProtocolVersion",vol.optInt("volumeProtocolVersion"))
                    .put("volumeAssist",vol.optBoolean("volumeAssist"))
                    .put("volumeAssistActive",vol.optBoolean("volumeAssistActive"))
                    .put("volumeAudioReady",vol.optBoolean("volumeAudioReady"))
                    .put("volumeAudioWriteFailed",vol.optBoolean("volumeAudioWriteFailed"))
                    .put("volumeInputReady",vol.optBoolean("volumeInputReady"))
                    .put("volumeInputDeviceCount",vol.optInt("volumeInputDeviceCount"))
                    .put("volumeAdjustCount",vol.optLong("volumeAdjustCount"))
                    .put("volumeLastStopReason",vol.optString("volumeLastStopReason"))
                    .put("volumeAssistReason",vol.optString("volumeAssistReason"));
        }
        else if(action.equals("configure")){
            wakeRecovery=request.optBoolean("wake",false);
            String home=request.optString("home","");
            originalHome=home.matches("[A-Za-z0-9_.]{1,200}")?home:"";
            boolean nextAssist=request.optBoolean("volumeAssist",false);
            boolean explicitRetry=request.optBoolean("retryVolumeAssist",false);
            volumeAssist=nextAssist;
            // Clear sticky audio write failure ONLY on explicit user retry; never on passive false -> true
            // transitions (e.g. 5s no-bridge volume yield expiry, mode toggles, or periodic scans).
            if(shouldClearAudioFailureOnConfigure(nextAssist,explicitRetry)){
                audioReadFailed=false;
                audioWriteFailed=false;
                audioFailureReason="";
            }
            syncVolumeReaders();
            synchronized(wakeMonitor){wakeMonitor.notifyAll();}
            answer=new JSONObject();
        }
        else if(action.equals("list"))answer=list();
        else if(action.equals("snapshot"))answer=snapshot(request.getInt("id"));
        else if(action.equals("switch")){
            int id=request.getInt("id");
            task(id);
            if(!interactive())throw new IOException("screen off");
            api.getMethod("startActivityFromRecents",int.class,Bundle.class).invoke(manager,id,null);
            answer=new JSONObject();
        }
        else if(action.equals("remove")){
            int id=request.getInt("id");
            task(id);
            if(canRemove()){
                boolean removed=invokeRemoveTask(manager,api,id);
                if(!removed)throw new IOException("remove rejected");
            }
            else{
                int stack=singleTaskStack(id);
                if(stack<0||singleTaskStack(id)!=stack)throw new SecurityException("single task stack unavailable");
                api.getMethod("removeStack",int.class).invoke(manager,stack);
            }
            answer=new JSONObject();
        }
        else throw new IOException("unsupported operation");
        return answer.put("ok",true);
    }
    static Object invokeProviderCall(Object provider,Class<?> providerApi,String authority,String callMethod,Object extras,int callerUid)throws Exception{
        for(Method method:providerApi.getMethods())if(method.getName().equals("call")){
            Class<?>[] types=method.getParameterTypes();
            Object reply=null;
            if(types.length==4&&types[0]==String.class){
                reply=method.invoke(provider,"com.android.shell",callMethod,null,extras);
            }else if(types.length==5&&types[0]==String.class){
                reply=method.invoke(provider,"com.android.shell",authority,callMethod,null,extras);
            }else if(types.length==6&&types[0]==String.class&&types[1]==String.class){
                // Android 11 (API 30): (callingPkg, attributionTag, authority, method, arg, extras)
                reply=method.invoke(provider,"com.android.shell",null,authority,callMethod,null,extras);
            }else if(types.length==5&&types[0].getName().endsWith("AttributionSource")){
                // Android 12+ (API 31+): (AttributionSource, authority, method, arg, extras)
                Class<?> builderCls=Class.forName(types[0].getName()+"$Builder");
                Object builder=builderCls.getConstructor(int.class).newInstance(callerUid);
                builderCls.getMethod("setPackageName",String.class).invoke(builder,"com.android.shell");
                Object source=builderCls.getMethod("build").invoke(builder);
                reply=method.invoke(provider,source,authority,callMethod,null,extras);
            }
            if(reply!=null)return reply;
        }
        return null;
    }
    private void publish(){
        Object am=null;
        Class<?> amApi=null;
        Binder token=new Binder();
        String authority="com.pilipala.tvhome.taskbridge";
        try{
            am=activityManager();
            amApi=Class.forName("android.app.IActivityManager");
            Object holder=null;
            for(Method method:amApi.getMethods())if(method.getName().equals("getContentProviderExternal")){
                if(method.getParameterTypes().length==3)holder=method.invoke(am,authority,user,token);
                else if(method.getParameterTypes().length==4)holder=method.invoke(am,authority,user,token,"MarcoTV");
                if(holder!=null)break;
            }
            if(holder==null)throw new IOException("provider unavailable");
            Object provider=holder.getClass().getField("provider").get(holder);
            Class<?> providerApi=Class.forName("android.content.IContentProvider");
            Bundle extras=new Bundle();
            extras.putBinder("service",binder);
            Bundle reply=(Bundle)invokeProviderCall(provider,providerApi,authority,"connect",extras,android.os.Process.myUid());
            IBinder lifetime=reply==null?null:reply.getBinder("lifetime");
            if(lifetime!=null){
                clientLifetime=lifetime;
                lastPublishedVolumeKey="";
                connected=true;
                lifetime.linkToDeath(()->{
                    connected=false;
                    clientLifetime=null;
                    lastPublishedVolumeKey="";
                    stopVolumeReaders("bridge_disconnected");
                    synchronized(wakeMonitor){wakeMonitor.notifyAll();}
                },0);
            }
        }catch(Exception e){
            connected=false;
            android.util.Log.w("MarcoTVBridge","Binder handoff unavailable",e);
        }
        finally{
            if(am!=null)try{
                amApi.getMethod("removeContentProviderExternal",String.class,IBinder.class).invoke(am,authority,token);
            }catch(Exception ignored){}
        }
    }
    private String foregroundPackage()throws Exception{
        List<?> running=invokeGetTasks(manager,api);
        return running.isEmpty()?"":packageName(running.get(0));
    }
    private void watchWake(){
        Thread thread=new Thread(()->{
            boolean was=interactive(),wasMonitoring=false;
            long ready=0,lastPublish=0,lastInputScan=0;
            while(true){
                try{
                    boolean activeMonitor=wakeRecovery&&!originalHome.isEmpty();
                    boolean volMonitor=volumeAssist&&connected;
                    long sleepMs=(connected&&!activeMonitor&&ready==0)?(volMonitor?2500L:5000L):500L;
                    synchronized(wakeMonitor){wakeMonitor.wait(sleepMs);}
                    long now=SystemClock.elapsedRealtime();
                    if(!connected&&now-lastPublish>5000){
                        publish();
                        lastPublish=now;
                    }
                    if(volumeAssist&&connected&&now-lastInputScan>=2500){
                        syncVolumeReaders();
                        lastInputScan=now;
                    }
                    if(volumeEngine.isHolding()&&!interactive()){
                        volumeEngine.cancelActive("screen_off");
                    }
                    if(!(wakeRecovery&&!originalHome.isEmpty())&&ready==0){
                        wasMonitoring=false;
                        continue;
                    }
                    boolean on=interactive();
                    if(!wasMonitoring){
                        was=on;
                        wasMonitoring=true;
                    }
                    if(on&&!was)ready=now+1200;
                    if(!on)ready=0;
                    was=on;
                    if(ready==0||SystemClock.elapsedRealtime()<ready)continue;
                    ready=0;
                    if(!wakeRecovery||originalHome.isEmpty())continue;
                    String foreground=foregroundPackage();
                    if(foreground.equals(originalHome)||(originalHome.equals("com.mitv.tvhome")&&foreground.equals("com.xiaomi.eetv.litehome"))){
                        java.lang.Process process=new ProcessBuilder("/system/bin/am","start","-n","com.pilipala.tvhome/.MainActivity","--ez",
                                "wake_recovery","true").redirectErrorStream(true).start();
                        try(InputStream in=process.getInputStream()){
                            byte[] b=new byte[1024];
                            while(in.read(b)!=-1){}
                        }
                        int result=process.waitFor();
                        lastWake=result==0?"已从原桌面恢复":"系统未允许恢复";
                    }else lastWake="保留当前应用";
                    lastWakeTime=System.currentTimeMillis();
                }catch(Exception e){
                    lastWake="恢复检查失败";
                    lastWakeTime=System.currentTimeMillis();
                }
            }
        },"MarcoTV-wake");
        thread.setDaemon(true);
        thread.start();
    }
    public static void main(String[] args)throws Exception{
        if(android.os.Process.myUid()!=2000||args.length!=2)throw new SecurityException("requires shell setup");
        Looper.prepareMainLooper();
        try(RandomAccessFile lock=new RandomAccessFile("/data/local/tmp/marcotv-task-bridge.lock","rw")){
            FileLock held=null;
            for(int attempt=0;attempt<10&&held==null;attempt++){
                held=lock.getChannel().tryLock();
                if(held==null)Thread.sleep(100);
            }
            if(held==null)return;
            try{
                TaskBridgeServer server=new TaskBridgeServer(Integer.parseInt(args[0]),Integer.parseInt(args[1]));
                server.publish();
                server.watchWake();
                Looper.loop();
            }finally{
                held.release();
            }
        }
    }
}
