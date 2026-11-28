// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.util.Log;
import android.view.KeyEvent;

/** Optional Home guard; defaults to OEM window pullback without key filtering so remote key repeats stay native. */
public final class HomeKeyService extends AccessibilityService {
    static final long VOLUME_YIELD_MS = 5000L;
    static final int VOLUME_YIELD_STREAM = 3; // AudioManager.STREAM_MUSIC
    static final long HOME_HOLD_SAFETY_TIMEOUT_MS = 2500L;
    static final long CROSS_SOURCE_INFLIGHT_DEDUP_MS = 350L;
    static final long CROSS_SOURCE_SETTLED_DEDUP_MS = 100L;
    static final String ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    static final String EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";
    static final String EXTRA_PREV_VOLUME_STREAM_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE";
    static volatile boolean connected;
    static volatile int runtimeFlags;
    static volatile boolean runtimeKeyFilterRequested;
    static volatile boolean runtimeVolumeYieldActive;
    static volatile boolean runtimeVolumeYieldFailed;
    static volatile boolean runtimeModeApplied;
    static volatile long connectionGeneration;
    private static volatile long modeAppliedGeneration;
    private static HomeKeyService instance;
    private long suppressUntil;
    private boolean recentAppsSuppressed;
    private long preserveRecentsUntil;
    private boolean held;
    private boolean volumeYieldActive;
    private long volumeYieldUntil;
    static boolean isActionableVolumeChange(Intent intent){
        if(intent==null||!ACTION_VOLUME_CHANGED.equals(intent.getAction()))return false;
        int stream=intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE,-1);
        if(stream!=VOLUME_YIELD_STREAM)return false;
        int prev=intent.getIntExtra(EXTRA_PREV_VOLUME_STREAM_VALUE,-1);
        int cur=intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE,-1);
        return prev>=0&&cur>=0&&prev!=cur;
    }
    private HomeSetupPolicy.GuardMode guardMode(){
        return SystemIntegration.guardMode(this);
    }
    static boolean isGuardModeApplied(Context context){
        return isGuardModeApplied(context,0L);
    }
    static boolean isGuardModeApplied(Context context,long afterGeneration){
        if(!connected||!runtimeModeApplied||modeAppliedGeneration<=afterGeneration)return false;
        boolean compat=SystemIntegration.prefs(context).getBoolean("home_compat",false);
        boolean paused=HomeSetup.paused(context);
        boolean nativeHome=SystemIntegration.homeIsOurs(context);
        boolean baseExpected=HomeSetupPolicy.shouldRequestKeyFilter(compat,paused,nativeHome,SystemIntegration.guardMode(context));
        boolean expected=baseExpected&&!runtimeVolumeYieldActive;
        return runtimeKeyFilterRequested==expected;
    }
    static void syncRuntimeKeyFilter(Context context){
        HomeKeyService service=instance;
        if(service!=null&&connected)service.applyServiceFlags();
    }
    private final Runnable volumeYieldRestore=()->endVolumeYield();
    private void clearLocalVolumeYieldState(){
        handler.removeCallbacks(volumeYieldRestore);
        volumeYieldActive=false;
        volumeYieldUntil=0;
    }
    private void clearVolumeYieldState(){
        clearLocalVolumeYieldState();
        if(instance==this){
            runtimeVolumeYieldActive=false;
            runtimeVolumeYieldFailed=false;
        }
    }
    private void endVolumeYield(){
        handler.removeCallbacks(volumeYieldRestore);
        if(!volumeYieldActive){
            if(instance==this)runtimeVolumeYieldActive=false;
            return;
        }
        volumeYieldActive=false;
        volumeYieldUntil=0;
        if(instance==this)runtimeVolumeYieldActive=false;
        applyServiceFlags();
        if(runtimeKeyFilterRequested){
            Diagnostics.event("HOME_KEY_FILTER_RESTORED_AFTER_VOLUME");
            Log.i("MarcoTVSystem","KEY_FILTER restored after volume yield window");
        }
    }
    private void beginOrExtendVolumeYield(){
        if(instance!=this)return;
        boolean compat=SystemIntegration.prefs(this).getBoolean("home_compat",false);
        boolean paused=HomeSetup.paused(this);
        boolean nativeHome=SystemIntegration.homeIsOurs(this);
        HomeSetupPolicy.GuardMode mode=guardMode();
        if(!canRedirect()
                ||SystemIntegration.isRebindPending(this)
                ||!HomeSetupPolicy.shouldUseVolumeYield(compat,paused,nativeHome,mode,TaskBridgeClient.volumeAssistActive())){
            return;
        }
        // Only yield when key filtering is actually active (or already genuinely yielded by us).
        if(!volumeYieldActive&&!runtimeKeyFilterRequested)return;
        boolean entering=!volumeYieldActive;
        volumeYieldActive=true;
        volumeYieldUntil=SystemClock.elapsedRealtime()+VOLUME_YIELD_MS;
        handler.removeCallbacks(volumeYieldRestore);
        handler.postDelayed(volumeYieldRestore,VOLUME_YIELD_MS);
        if(entering){
            applyServiceFlags();
            if(volumeYieldActive&&!runtimeKeyFilterRequested){
                Diagnostics.event("HOME_KEY_FILTER_YIELDED_FOR_VOLUME");
                Log.i("MarcoTVSystem","KEY_FILTER temporarily yielded for "+VOLUME_YIELD_MS+"ms (no-bridge volume assist)");
            }
        }
    }
    private void applyServiceFlags(){
        if(instance!=this)return;
        boolean prevKeyFilter=runtimeKeyFilterRequested;
        boolean prevModeApplied=runtimeModeApplied;
        boolean prevYield=runtimeVolumeYieldActive;
        boolean prevYieldFailed=runtimeVolumeYieldFailed;
        int prevFlags=runtimeFlags;
        HomeSetupPolicy.GuardMode mode=guardMode();
        boolean compat=SystemIntegration.prefs(this).getBoolean("home_compat",false);
        boolean paused=HomeSetup.paused(this);
        boolean nativeHome=SystemIntegration.homeIsOurs(this);
        boolean baseExpected=HomeSetupPolicy.shouldRequestKeyFilter(compat,paused,nativeHome,mode);
        if(!baseExpected||TaskBridgeClient.volumeAssistActive()){
            clearVolumeYieldState();
            runtimeVolumeYieldFailed=false;
        }
        boolean requestingYield=baseExpected&&volumeYieldActive;
        boolean expectedFilter=baseExpected&&!requestingYield;
        AccessibilityServiceInfo info=getServiceInfo();
        if(info==null){
            runtimeFlags=0;
            runtimeKeyFilterRequested=false;
            runtimeVolumeYieldActive=false;
            runtimeVolumeYieldFailed=false;
            runtimeModeApplied=false;
            modeAppliedGeneration=0;
            clearLocalVolumeYieldState();
            if(prevKeyFilter||prevModeApplied||prevYield||prevYieldFailed||prevFlags!=0)TaskBridgeClient.notifyStateChanged();
            return;
        }
        int nextFlags=expectedFilter
                ?(info.flags|AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS)
                :(info.flags&~AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS);
        info.flags=nextFlags;
        setServiceInfo(info);
        AccessibilityServiceInfo applied=getServiceInfo();
        if(applied==null){
            runtimeFlags=0;
            runtimeKeyFilterRequested=false;
            runtimeVolumeYieldActive=false;
            runtimeVolumeYieldFailed=false;
            runtimeModeApplied=false;
            modeAppliedGeneration=0;
            clearLocalVolumeYieldState();
            if(prevKeyFilter||prevModeApplied||prevYield||prevYieldFailed||prevFlags!=0)TaskBridgeClient.notifyStateChanged();
            return;
        }
        int effectiveFlags=applied.flags;
        runtimeFlags=effectiveFlags;
        runtimeKeyFilterRequested=(effectiveFlags&AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS)!=0;
        if(requestingYield&&runtimeKeyFilterRequested){
            // System refused to drop FLAG_REQUEST_FILTER_KEY_EVENTS: never claim yield is active.
            // Key filtering itself remains ON, so 3A bridge assist can still take over if connected later.
            clearLocalVolumeYieldState();
            runtimeVolumeYieldActive=false;
            runtimeVolumeYieldFailed=true;
            runtimeModeApplied=true;
            modeAppliedGeneration=connected?connectionGeneration:0;
            Diagnostics.event("HOME_KEY_FILTER_YIELD_REJECTED");
        }else{
            if(requestingYield){
                runtimeVolumeYieldActive=true;
                runtimeVolumeYieldFailed=false;
            }else{
                runtimeVolumeYieldActive=false;
                if(!baseExpected||!runtimeKeyFilterRequested)runtimeVolumeYieldFailed=false;
            }
            runtimeModeApplied=(runtimeKeyFilterRequested==expectedFilter);
            modeAppliedGeneration=(connected&&runtimeModeApplied)?connectionGeneration:0;
            if(expectedFilter&&!runtimeKeyFilterRequested){
                Diagnostics.event("HOME_KEY_FILTER_APPLY_REJECTED");
            }else if(!expectedFilter&&runtimeKeyFilterRequested){
                Diagnostics.event("HOME_KEY_FILTER_REVOKE_REJECTED");
            }
        }
        if(!runtimeKeyFilterRequested)resetPress();
        if(connected&&runtimeModeApplied)SystemIntegration.markHomeServiceRebindConnected(this);
        if(prevKeyFilter!=runtimeKeyFilterRequested||prevModeApplied!=runtimeModeApplied
                ||prevYield!=runtimeVolumeYieldActive||prevYieldFailed!=runtimeVolumeYieldFailed
                ||prevFlags!=runtimeFlags){
            TaskBridgeClient.sync(this);
            TaskBridgeClient.notifyStateChanged();
        }
        Log.i("MarcoTVSystem","HOME service ready; mode="+mode.prefValue
                +"; compat="+compat
                +"; paused="+paused
                +"; nativeHome="+nativeHome
                +"; volYield="+runtimeVolumeYieldActive
                +"; volYieldFailed="+runtimeVolumeYieldFailed
                +"; keyFilter="+runtimeKeyFilterRequested
                +"; modeApplied="+runtimeModeApplied
                +"; gen="+connectionGeneration
                +"; capabilities="+applied.getCapabilities()
                +"; flags="+effectiveFlags);
    }
    private void cancelWindowFallback() {
        handler.removeCallbacks(fallback);
        windowPullPolicy.reset();
    }
    private void resetCrossSourceState() {
        lastPressTrackedAt = -10000L;
        lastPressSource = "";
        lastPressEventSource = "";
        lastPressDouble = false;
        returnedToMarcoSinceLastPress = true;
    }
    private boolean isCrossSourceDuplicate(String incomingSource, long now) {
        if (lastPressTrackedAt < 0L || lastPressSource.isEmpty() || lastPressSource.equals(incomingSource)) {
            return false;
        }
        long delta = now - lastPressTrackedAt;
        if (delta < 0L) return false;
        if (!returnedToMarcoSinceLastPress) {
            return delta < CROSS_SOURCE_INFLIGHT_DEDUP_MS;
        }
        return delta < CROSS_SOURCE_SETTLED_DEDUP_MS;
    }
    static boolean openSystemRecents(){
        return instance!=null&&instance.systemRecents();
    }
    private boolean systemRecents(){
        cancelWindowFallback();
        handler.removeCallbacks(homeBroadcastFollowup);
        homePressTracker.reset();
        resetCrossSourceState();
        suppressUntil=SystemClock.elapsedRealtime()+10000;
        recentAppsSuppressed=true;
        boolean result=performGlobalAction(GLOBAL_ACTION_RECENTS);
        if(!result){
            suppressUntil=0;
            recentAppsSuppressed=false;
        }
        Diagnostics.event(result?"SYSTEM_RECENTS_REQUESTED":"SYSTEM_RECENTS_UNAVAILABLE");
        return result;
    }
    private final Runnable handlingHomeTimeout = () -> {
        if (this.handlingHome) {
            Log.i("MarcoTVSystem", "HOME_TRACE signal=key_timeout resetting stuck handlingHome elapsed="
                    + SystemClock.elapsedRealtime());
            resetPress();
        }
    };
    private void resetPress(){
        handlingHome=false;
        held=false;
        handler.removeCallbacks(longHome);
        handler.removeCallbacks(handlingHomeTimeout);
    }
    private final Runnable longHome=()->{
        if(!this.handlingHome||held||!canRedirect())return;
        held=true;
        this.homePressTracker.reset();
        HomeSetup.cancelPendingShortPress();
        HomeSetup.clearPendingHomeEvent(this);
        String action=SystemIntegration.prefs(this).getString("home_long_action","system");
        long now=SystemClock.elapsedRealtime();
        Log.i("MarcoTVSystem","HOME_TRACE signal=long_press action="+action+" elapsed="+now);
        if(action.equals("marco")){
            lastPressTrackedAt=now;
            lastPressSource="key";
            lastPressEventSource=HomeSetup.SOURCE_COMPAT_KEY;
            lastPressDouble=true;
            returnedToMarcoSinceLastPress=false;
            lastLaunchAt=now;
            lastLaunchSource="long_marco";
            try{
                startActivity(SystemIntegration.homeIntent(this)
                        .putExtra("home_press_handled",true)
                        .putExtra("recent_apps",true)
                        .putExtra("home_return_source","long_marco")
                        .putExtra("home_return_at",now));
            }catch(RuntimeException e){
                Log.w("MarcoTVSystem","Long HOME unavailable",e);
            }
        }
        else if(action.equals("system")&&!systemRecents())android.widget.Toast.makeText(this,"系统不支持此入口，可在系统与启动中启用原厂按键模式",android.widget.Toast.LENGTH_LONG)
                .show();
    };
    private long lastOpen;
    private long lastDirect=-10000;
    private long lastPressTrackedAt=-10000L;
    private String lastPressSource="";
    private String lastPressEventSource="";
    private boolean lastPressDouble;
    private boolean returnedToMarcoSinceLastPress=true;
    private long lastLaunchAt=-10000L;
    private String lastLaunchSource="";
    private long dismissingRecentAppsUntil;
    private boolean homeBroadcastSettled;
    private final HomePressTracker homePressTracker=new HomePressTracker();
    private final HomeInputGate inputGate=new HomeInputGate();
    private final HomeWindowPullPolicy windowPullPolicy=new HomeWindowPullPolicy();
    private boolean canRedirect(){
        if(!HomeSetup.unlocked(this))return false;
        android.os.PowerManager power=(android.os.PowerManager)getSystemService(POWER_SERVICE);
        return inputGate.allow(power!=null&&power.isInteractive(),SystemClock.elapsedRealtime());
    }
    private Intent homePressIntent(String eventSource,String pressSource,String traceSource,boolean noAnimation){
        long now=SystemClock.elapsedRealtime();
        lastOpen=now;
        boolean twice=false;
        if(HomeSetup.unlocked(this)){
            android.content.SharedPreferences p=getSharedPreferences("home",0);
            if(p.getBoolean("recent_enabled",false))twice=homePressTracker.press(now,p.getInt("recent_double_ms",400));
            else homePressTracker.reset();
        }else{
            homePressTracker.reset();
        }
        lastPressTrackedAt=now;
        lastPressSource=pressSource;
        lastPressEventSource=eventSource==null?"":eventSource;
        lastPressDouble=twice;
        returnedToMarcoSinceLastPress=false;
        lastLaunchAt=now;
        lastLaunchSource=traceSource;
        Intent intent=SystemIntegration.homeIntent(this)
                .putExtra("home_press_handled",true)
                .putExtra("recent_apps",twice)
                .putExtra("home_return_source",traceSource)
                .putExtra("home_return_at",now);
        if(noAnimation)intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if(twice){
            preserveRecentsUntil=now+800;
            handler.removeCallbacks(fallback);
            HomeSetup.cancelPendingShortPress();
            HomeSetup.clearPendingHomeEvent(this);
        }else{
            preserveRecentsUntil=0;
            if(eventSource!=null&&!eventSource.isEmpty()){
                intent.putExtra(HomeSetup.EXTRA_EVENT_TOKEN,HomeSetup.issueHomeEvent(this,eventSource));
            }
        }
        return intent;
    }
    private boolean handlingHome;
    private boolean receiverRegistered;
    private boolean wakeOriginalSeen;
    private boolean wakeOtherAppSeen;
    private boolean wasOriginalHomeBeforeSleep;
    private String lastActivePackage = "";
    private long lastOriginalHomeAt = -10000;
    private boolean isInteractive(){
        android.os.PowerManager power=(android.os.PowerManager)getSystemService(POWER_SERVICE);
        return power!=null&&power.isInteractive();
    }
    private boolean isOriginalHomePackage(String shown){
        return SystemIntegration.matchesTrackedHomePackage(this,shown);
    }
    private static boolean isWakeSystemOverlay(String pkg){
        return "com.android.systemui".equals(pkg)||"com.xiaomi.mitv.systemui".equals(pkg)||"android".equals(pkg);
    }
    private boolean wakePreferenceEnabled(){
        return !HomeSetup.unlocked(this)||getSharedPreferences("home",0).getBoolean("recent_wake",true);
    }
    private void scheduleWakeCheck(long delayMs){
        handler.removeCallbacks(wakeCheck);
        handler.postDelayed(wakeCheck,delayMs);
    }
    private void markServiceStoppedCleanly(){
        android.content.SharedPreferences p=SystemIntegration.prefs(this);
        if(p.getBoolean("compat_service_active",false))p.edit().putBoolean("compat_service_active",false).apply();
    }
    private final Runnable wakeCheck=()->{
        long now=SystemClock.elapsedRealtime();
        long remaining=inputGate.remainingSettleMs(now);
        if(isInteractive()&&!inputGate.isScreenOff()&&remaining>0&&remaining<=2000){
            scheduleWakeCheck(remaining+100);
            return;
        }
        boolean standbyReturn=SystemIntegration.consumeStandbyWakeReturn(this);
        boolean originalSeen=wakeOriginalSeen||wasOriginalHomeBeforeSleep;
        boolean otherSeen=wakeOtherAppSeen;
        wakeOriginalSeen=false;
        wakeOtherAppSeen=false;
        wasOriginalHomeBeforeSleep=false;
        if(!HomeSetupPolicy.wakeFallback(wakePreferenceEnabled(),HomeSetup.paused(this),
                SystemIntegration.homeIsOurs(this),
                SystemIntegration.prefs(this).getBoolean("home_compat",false),
                !guardMode().handlesWakeFallback(),HomeSetup.unlocked(this),
                canRedirect()&&now>=suppressUntil,otherSeen,originalSeen||standbyReturn))return;
        try{
            HomeSetup.noteStandbyWake(this);
            startActivity(SystemIntegration.homeIntent(this).putExtra("wake_recovery",true));
            Diagnostics.event("HOME_WAKE_RECOVERY");
            Log.i("MarcoTVSystem","HOME wake recovery");
        }catch(RuntimeException e){
            Log.w("MarcoTVSystem","Home wake recovery failed",e);
        }
    };
    private final BroadcastReceiver homeBroadcast = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if(ACTION_VOLUME_CHANGED.equals(i.getAction())){
                if(!isActionableVolumeChange(i))return;
                beginOrExtendVolumeYield();
                return;
            }
            if(Intent.ACTION_SCREEN_OFF.equals(i.getAction())||Intent.ACTION_SCREEN_ON.equals(i.getAction())){
                boolean on=Intent.ACTION_SCREEN_ON.equals(i.getAction());
                long now=SystemClock.elapsedRealtime();
                inputGate.screen(on,now);
                resetPress();
                homePressTracker.reset();
                resetCrossSourceState();
                cancelWindowFallback();
                handler.removeCallbacks(homeBroadcastFollowup);
                handler.removeCallbacks(wakeCheck);
                if(!on&&volumeYieldActive){
                    clearVolumeYieldState();
                    applyServiceFlags();
                }
                if(on){
                    HomeSetup.noteStandbyWake(c);
                    wakeOriginalSeen=now-lastOriginalHomeAt<=1500;
                    wakeOtherAppSeen=false;
                    scheduleWakeCheck(900);
                }else{
                    HomeSetup.noteStandbyWake(c);
                    wasOriginalHomeBeforeSleep=isOriginalHomePackage(lastActivePackage);
                    wakeOriginalSeen=false;
                    wakeOtherAppSeen=false;
                }
                Diagnostics.event(on?"SCREEN_ON_HOME_SETTLING":"SCREEN_OFF_HOME_SUSPENDED");
                return;
            }
            if(!canRedirect())return;
            if(SystemIntegration.homeIsOurs(c))return;
            if (HomeSetup.paused(c) || !SystemIntegration.prefs(c).getBoolean("home_compat", false)) return;
            long now=SystemClock.elapsedRealtime();
            String reason=i.getStringExtra("reason");
            HomeSetupPolicy.GuardMode mode=guardMode();
            if(!mode.handlesHomeBroadcast())return;
            // Drive broadcast branching strictly from the actual readback filter bit, never merely the requested mode.
            boolean directKeyActive=runtimeKeyFilterRequested&&mode.handlesDirectHomeKey()&&!volumeYieldActive;
            if(!directKeyActive&&"assist".equals(reason)){
                homeBroadcastSettled=true;
                cancelWindowFallback();
                handler.removeCallbacks(homeBroadcastFollowup);
                suppressUntil=now+10000;
                recentAppsSuppressed=false;
                return;
            }
            if(!directKeyActive&&"recentapps".equals(reason)){
                // Dismissing an already-open OEM recents overlay via Home can emit a trailing
                // "recentapps" broadcast right after "homekey"; ignore only that closing echo.
                if(now<dismissingRecentAppsUntil)return;
                homeBroadcastSettled=true;
                cancelWindowFallback();
                handler.removeCallbacks(homeBroadcastFollowup);
                suppressUntil=now+10000;
                recentAppsSuppressed=true;
                return;
            }
            if (!"homekey".equals(reason)) return;
            boolean recentDirect=now-lastDirect<200;
            boolean crossDup=isCrossSourceDuplicate("broadcast",now);
            Log.i("MarcoTVSystem","HOME_TRACE signal=broadcast reason="+reason
                    +" handling="+handlingHome+" recentDirect="+recentDirect
                    +" crossDup="+crossDup+" returned="+returnedToMarcoSinceLastPress+" elapsed="+now);
            if(handlingHome||recentDirect||crossDup)return;
            dismissingRecentAppsUntil=(recentAppsSuppressed&&suppressUntil>now)?now+600:0;
            recentAppsSuppressed=false;
            suppressUntil=0;
            homeBroadcastSettled=false;
            cancelWindowFallback();
            handler.removeCallbacks(homeBroadcastFollowup);
            try {
                startActivity(homePressIntent(HomeSetup.SOURCE_COMPAT_BROADCAST,"broadcast","broadcast",true));
                Diagnostics.event("HOME_BROADCAST_HANDLED");
                Log.i("MarcoTVSystem","HOME_TRACE action=launch source=broadcast double="+lastPressDouble+" elapsed="+now);
                if(mode.handlesWindowFallback()&&!SystemIntegration.homeIsOurs(c)){
                    handler.postDelayed(homeBroadcastFollowup,450);
                }
            }
            catch (RuntimeException e) {
                HomeSetup.clearPendingHomeEvent(c);
                Log.w("MarcoTVSystem", "Home broadcast unavailable", e);
            }
        }
    };
    @Override protected void onServiceConnected() {
        connectionGeneration++;
        connected=true;
        instance=this;
        clearVolumeYieldState();
        applyServiceFlags();
        if (!receiverRegistered) {
            IntentFilter filter=new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(ACTION_VOLUME_CHANGED);
            registerReceiver(homeBroadcast,filter);
            receiverRegistered = true;
        }
        android.content.SharedPreferences p=SystemIntegration.prefs(this);
        if(p.getBoolean("home_compat",false)&&!HomeSetup.paused(this)
                &&(!p.getBoolean("compat_service_active",false)||!p.getBoolean("compat_seen_present",false))){
            p.edit().putBoolean("compat_service_active",true).putBoolean("compat_seen_present",true).apply();
        }
        if(!isInteractive()){
            inputGate.screen(false,SystemClock.elapsedRealtime());
        }else if(SystemIntegration.hasStandbyWakeReturn(this)){
            scheduleWakeCheck(900);
        }
        TaskBridgeClient.sync(this);
        Diagnostics.event("HOME_SERVICE_CONNECTED");
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable fallback = () -> {
        long now = SystemClock.elapsedRealtime();
        if (!windowPullPolicy.onFollowupDue(now)) return;
        performWindowFallback(now, false, "window_followup");
    };
    private void performWindowFallback(long now, boolean newEpisode, String traceSource) {
        if (HomeSetup.paused(this) || SystemIntegration.homeIsOurs(this) || !canRedirect() || !guardMode().handlesWindowFallback()
                || now < suppressUntil || !SystemIntegration.prefs(this).getBoolean("home_compat", false)) return;
        boolean crossDup = isCrossSourceDuplicate("window", now);
        boolean countAsNewPress = newEpisode && !crossDup;
        Intent intent;
        boolean recentApps;
        if (countAsNewPress) {
            intent = homePressIntent(HomeSetup.SOURCE_WINDOW_FALLBACK, "window", traceSource, true);
            recentApps = lastPressDouble;
        } else {
            recentApps = (crossDup && lastPressDouble) || now < preserveRecentsUntil;
            intent = SystemIntegration.homeIntent(this)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra("home_press_handled", true)
                    .putExtra("recent_apps", recentApps)
                    .putExtra("home_return_source", traceSource)
                    .putExtra("home_return_at", now);
            if (recentApps) {
                HomeSetup.cancelPendingShortPress();
                HomeSetup.clearPendingHomeEvent(this);
            } else {
                intent.putExtra(HomeSetup.EXTRA_EVENT_TOKEN,
                        HomeSetup.issueHomeEvent(this, HomeSetup.SOURCE_WINDOW_FALLBACK));
            }
            returnedToMarcoSinceLastPress = false;
            lastLaunchAt = now;
            lastLaunchSource = traceSource;
        }
        try {
            startActivity(intent);
            Diagnostics.event("HOME_WINDOW_FALLBACK");
            Log.i("MarcoTVSystem", "HOME_TRACE action=launch source=" + traceSource
                    + " newEpisode=" + newEpisode + " crossDup=" + crossDup
                    + " double=" + recentApps + " pulls=" + windowPullPolicy.pullsInEpisode()
                    + " elapsed=" + now);
        }
        catch (RuntimeException e) {
            if (!recentApps) HomeSetup.clearPendingHomeEvent(this);
            Log.w("MarcoTVSystem", "Home redirection failed", e);
        }
    }
    private final Runnable homeBroadcastFollowup = () -> {
        if (homeBroadcastSettled || SystemIntegration.homeIsOurs(this)) return;
        long now = SystemClock.elapsedRealtime();
        if (HomeSetup.paused(this) || !canRedirect() || !guardMode().handlesWindowFallback()
                || now < suppressUntil || !SystemIntegration.prefs(this).getBoolean("home_compat", false)) return;
        boolean keepRecents = now < preserveRecentsUntil;
        try {
            Intent intent = SystemIntegration.homeIntent(this)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra("home_return_source", "broadcast_retry")
                    .putExtra("home_return_at", now);
            if (keepRecents) {
                intent.putExtra("home_press_handled", true).putExtra("recent_apps", true);
            } else {
                intent.putExtra(HomeSetup.EXTRA_EVENT_TOKEN, HomeSetup.issueHomeEvent(this, HomeSetup.SOURCE_COMPAT_BROADCAST));
            }
            startActivity(intent);
            Diagnostics.event("HOME_BROADCAST_RETRY");
            Log.i("MarcoTVSystem", "HOME broadcast retry" + (keepRecents ? " (preserve recents)" : ""));
        }
        catch (RuntimeException e) {
            if (!keepRecents) HomeSetup.clearPendingHomeEvent(this);
            Log.w("MarcoTVSystem", "Home redirection failed", e);
        }
    };
    @Override protected boolean onKeyEvent(KeyEvent event) {
        if(event.getKeyCode()==KeyEvent.KEYCODE_POWER){
            inputGate.power(SystemClock.elapsedRealtime());
            resetPress();
            homePressTracker.reset();
            resetCrossSourceState();
            cancelWindowFallback();
            handler.removeCallbacks(homeBroadcastFollowup);
            return false;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        boolean compat=SystemIntegration.prefs(this).getBoolean("home_compat",false);
        boolean nativeHome=SystemIntegration.homeIsOurs(this);
        if(!canRedirect()||volumeYieldActive||!runtimeKeyFilterRequested
                ||!HomeSetupPolicy.shouldRequestKeyFilter(compat,HomeSetup.paused(this),nativeHome,guardMode())){
            resetPress();
            return false;
        }
        long now=SystemClock.elapsedRealtime();
        if(event.getAction()==KeyEvent.ACTION_DOWN){
            if(event.getRepeatCount()==0){
                resetPress();
                suppressUntil=0;
                handlingHome=SystemIntegration.prefs(this).getBoolean("home_compat",false);
                Log.i("MarcoTVSystem","HOME_TRACE signal=key action=DOWN repeat=0 handling="+handlingHome+" elapsed="+now);
                if(handlingHome){
                    cancelWindowFallback();
                    lastDirect=now;
                    handler.postDelayed(longHome,600);
                    handler.postDelayed(handlingHomeTimeout,HOME_HOLD_SAFETY_TIMEOUT_MS);
                }
            }
            if(handlingHome&&!held&&event.isLongPress()){
                handler.removeCallbacks(longHome);
                longHome.run();
            }
            return handlingHome;
        }
        boolean consumed=handlingHome;
        Log.i("MarcoTVSystem","HOME_TRACE signal=key action=UP consumed="+consumed+" held="+held+" canceled="+event.isCanceled()+" elapsed="+now);
        if(consumed)lastDirect=now;
        handler.removeCallbacks(longHome);
        handler.removeCallbacks(handlingHomeTimeout);
        if(consumed&&!held&&!event.isCanceled()){
            lastDirect=now;
            try{
                startActivity(homePressIntent(HomeSetup.SOURCE_COMPAT_KEY,"key","key",false));
                Diagnostics.event("HOME_DIRECT_HANDLED");
                Log.i("MarcoTVSystem","HOME_TRACE action=launch source=key double="+lastPressDouble+" elapsed="+now);
            }
            catch(RuntimeException e){
                HomeSetup.clearPendingHomeEvent(this);
                Log.w("MarcoTVSystem","Direct HOME failed",e);
            }
        }
        resetPress();
        return consumed;
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.getPackageName() == null) return;
        if(HomeSetup.paused(this))return;
        String shown = event.getPackageName().toString();
        long now = SystemClock.elapsedRealtime();
        boolean interactive = isInteractive();
        boolean originalWindow = isOriginalHomePackage(shown);
        if (!originalWindow) {
            // Any non-OEM-launcher foreground window (Marco, user app, or system assistant/overlay)
            // cancels a pending Home broadcast retry and resets window pullback policy so a
            // subsequent OEM launcher appearance is never skipped.
            homeBroadcastSettled = true;
            handler.removeCallbacks(homeBroadcastFollowup);
            cancelWindowFallback();
        }
        if (getPackageName().equals(shown)) {
            lastActivePackage = shown;
            dismissingRecentAppsUntil = 0;
            wakeOriginalSeen = false;
            wakeOtherAppSeen = false;
            wasOriginalHomeBeforeSleep = false;
            returnedToMarcoSinceLastPress = true;
            cancelWindowFallback();
            handler.removeCallbacks(wakeCheck);
            if (interactive) SystemIntegration.clearStandbyWakeReturn(this);
            if (lastLaunchAt > 0L && now - lastLaunchAt < 3000L) {
                Log.i("MarcoTVSystem", "HOME_TRACE signal=window target=self source=" + lastLaunchSource
                        + " sinceLaunchMs=" + (now - lastLaunchAt) + " elapsed=" + now);
            }
        } else if (originalWindow) {
            lastActivePackage = shown;
            lastOriginalHomeAt = now;
            wakeOriginalSeen = true;
            wakeOtherAppSeen = false;
        } else if (!isWakeSystemOverlay(shown)) {
            lastActivePackage = shown;
            if (interactive) {
                wakeOriginalSeen = false;
                wakeOtherAppSeen = true;
                wasOriginalHomeBeforeSleep = false;
                handler.removeCallbacks(wakeCheck);
                SystemIntegration.clearStandbyWakeReturn(this);
            }
        }
        if(!canRedirect()){
            cancelWindowFallback();
            handler.removeCallbacks(homeBroadcastFollowup);
            return;
        }
        if (AmbientIntegration.enabled(this) && shown.equals("com.mitv.screensaver")
                && event.getClassName() != null && event.getClassName().toString().equals("com.mitv.screensaver.RenderActivity")) {
            // Xiaomi launches a proprietary activity instead of Android's selected dream.
            // Only its actual full-screen saver is replaced, never its settings page.
            try {
                startActivity(SystemIntegration.homeIntent(this).putExtra("ambient", true));
                Log.i("MarcoTVSystem", "Vendor screensaver replaced");
            }
            catch (RuntimeException e) {
                Log.w("MarcoTVSystem", "Vendor ambient unavailable", e);
            }
            return;
        }
        if (SystemIntegration.homeIsOurs(this)) {
            cancelWindowFallback();
            handler.removeCallbacks(homeBroadcastFollowup);
            return;
        }
        if (!SystemIntegration.prefs(this).getBoolean("home_compat", false)) return;
        String className = event.getClassName() == null ? "" : event.getClassName().toString();
        if(!guardMode().handlesWindowFallback()||handlingHome||now<suppressUntil){
            if (originalWindow) {
                Log.i("MarcoTVSystem", "HOME_TRACE signal=window target=original_home class=" + className
                        + " skipped=true mode=" + guardMode().prefValue + " handling=" + handlingHome
                        + " suppressed=" + (now < suppressUntil) + " elapsed=" + now);
            }
            return;
        }
        if (!originalWindow){
            cancelWindowFallback();
            return;
        }
        // Pull back immediately on the first OEM Home notification instead of waiting behind
        // a sliding 60 ms debounce; coalesce duplicate same-class bursts into one bounded check.
        HomeWindowPullPolicy.Decision decision = windowPullPolicy.onOriginalHomeWindow(now, className);
        Log.i("MarcoTVSystem", "HOME_TRACE signal=window target=original_home class=" + className
                + " decision=" + decision.action + " delayMs=" + decision.delayMs
                + " newEpisode=" + decision.newEpisode + " elapsed=" + now);
        if (decision.action == HomeWindowPullPolicy.Action.PULL_NOW) {
            handler.removeCallbacks(fallback);
            handler.removeCallbacks(homeBroadcastFollowup);
            performWindowFallback(now, decision.newEpisode, "window_immediate");
        } else if (decision.action == HomeWindowPullPolicy.Action.SCHEDULE_FOLLOWUP) {
            handler.removeCallbacks(fallback);
            handler.removeCallbacks(homeBroadcastFollowup);
            handler.postDelayed(fallback, decision.delayMs);
        }
    }
    @Override public boolean onUnbind(Intent intent) {
        clearLocalVolumeYieldState();
        if(instance==this){
            connected=false;
            runtimeFlags=0;
            runtimeKeyFilterRequested=false;
            runtimeVolumeYieldActive=false;
            runtimeVolumeYieldFailed=false;
            runtimeModeApplied=false;
            modeAppliedGeneration=0;
            markServiceStoppedCleanly();
            TaskBridgeClient.sync(this);
            TaskBridgeClient.notifyStateChanged();
        }
        return super.onUnbind(intent);
    }
    @Override public void onDestroy() {
        clearLocalVolumeYieldState();
        if(instance==this){
            connected=false;
            runtimeFlags=0;
            runtimeKeyFilterRequested=false;
            runtimeVolumeYieldActive=false;
            runtimeVolumeYieldFailed=false;
            runtimeModeApplied=false;
            modeAppliedGeneration=0;
            instance=null;
            markServiceStoppedCleanly();
            TaskBridgeClient.sync(this);
            TaskBridgeClient.notifyStateChanged();
        }
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) unregisterReceiver(homeBroadcast);
        super.onDestroy();
    }
    @Override public void onInterrupt() { }
}
