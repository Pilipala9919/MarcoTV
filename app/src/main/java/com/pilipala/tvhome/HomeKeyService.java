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
    static volatile boolean connected;
    static volatile int runtimeFlags;
    static volatile boolean runtimeKeyFilterRequested;
    private static HomeKeyService instance;
    private long suppressUntil;
    private long preserveRecentsUntil;
    private boolean held;
    private HomeSetupPolicy.GuardMode guardMode(){
        return SystemIntegration.guardMode(this);
    }
    static void syncRuntimeKeyFilter(Context context){
        HomeKeyService service=instance;
        if(service!=null)service.applyServiceFlags();
    }
    private void applyServiceFlags(){
        HomeSetupPolicy.GuardMode mode=guardMode();
        boolean compat=SystemIntegration.prefs(this).getBoolean("home_compat",false);
        boolean paused=HomeSetup.paused(this);
        AccessibilityServiceInfo info=getServiceInfo();
        if(info==null){
            runtimeFlags=0;
            runtimeKeyFilterRequested=false;
            return;
        }
        int nextFlags=HomeSetupPolicy.applyKeyFilterFlag(
                info.flags,AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS,compat,paused,mode);
        info.flags=nextFlags;
        setServiceInfo(info);
        AccessibilityServiceInfo applied=getServiceInfo();
        int effectiveFlags=applied!=null?applied.flags:nextFlags;
        runtimeFlags=effectiveFlags;
        runtimeKeyFilterRequested=(effectiveFlags&AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS)!=0;
        if(!HomeSetupPolicy.shouldRequestKeyFilter(compat,paused,mode))resetPress();
        Log.i("MarcoTVSystem","HOME service ready; mode="+mode.prefValue
                +"; compat="+compat
                +"; paused="+paused
                +"; keyFilter="+runtimeKeyFilterRequested
                +"; capabilities="+(applied!=null?applied.getCapabilities():info.getCapabilities())
                +"; flags="+effectiveFlags);
    }
    static boolean openSystemRecents(){
        return instance!=null&&instance.systemRecents();
    }
    private boolean systemRecents(){
        handler.removeCallbacks(fallback);
        handler.removeCallbacks(homeBroadcastFollowup);
        homePressTracker.reset();
        suppressUntil=SystemClock.elapsedRealtime()+10000;
        boolean result=performGlobalAction(GLOBAL_ACTION_RECENTS);
        if(!result)suppressUntil=0;
        Diagnostics.event(result?"SYSTEM_RECENTS_REQUESTED":"SYSTEM_RECENTS_UNAVAILABLE");
        return result;
    }
    private void resetPress(){
        handlingHome=false;
        held=false;
        handler.removeCallbacks(longHome);
    }
    private final Runnable longHome=()->{
        if(!this.handlingHome||held||!canRedirect())return;
        held=true;
        this.homePressTracker.reset();
        String action=SystemIntegration.prefs(this).getString("home_long_action","system");
        if(action.equals("marco")){
            try{
                startActivity(SystemIntegration.homeIntent(this).putExtra("home_press_handled",true).putExtra("recent_apps",true));
            }catch(RuntimeException e){
                Log.w("MarcoTVSystem","Long HOME unavailable",e);
            }
        }
        else if(action.equals("system")&&!systemRecents())android.widget.Toast.makeText(this,"系统不支持此入口，可在系统与启动中启用原厂按键模式",android.widget.Toast.LENGTH_LONG)
                .show();
    };
    private long lastOpen;
    private long lastDirect=-10000;
    private long lastHomeBroadcastAt=-10000;
    private long lastWindowFallbackAt=-10000;
    private long dismissingRecentAppsUntil;
    private boolean homeBroadcastSettled;
    private final HomePressTracker homePressTracker=new HomePressTracker();
    private final HomeInputGate inputGate=new HomeInputGate();
    private boolean canRedirect(){
        if(!HomeSetup.unlocked(this))return false;
        android.os.PowerManager power=(android.os.PowerManager)getSystemService(POWER_SERVICE);
        return inputGate.allow(power!=null&&power.isInteractive(),SystemClock.elapsedRealtime());
    }
    private Intent homePressIntent(String source){
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
        Intent intent=SystemIntegration.homeIntent(this).putExtra("home_press_handled",true).putExtra("recent_apps",twice);
        if(twice){
            preserveRecentsUntil=now+800;
            handler.removeCallbacks(fallback);
            HomeSetup.cancelPendingShortPress();
            HomeSetup.clearPendingHomeEvent(this);
        }else{
            preserveRecentsUntil=0;
            if(source!=null&&!source.isEmpty()){
                intent.putExtra(HomeSetup.EXTRA_EVENT_TOKEN,HomeSetup.issueHomeEvent(this,source));
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
            if(Intent.ACTION_SCREEN_OFF.equals(i.getAction())||Intent.ACTION_SCREEN_ON.equals(i.getAction())){
                boolean on=Intent.ACTION_SCREEN_ON.equals(i.getAction());
                long now=SystemClock.elapsedRealtime();
                inputGate.screen(on,now);
                resetPress();
                homePressTracker.reset();
                handler.removeCallbacks(fallback);
                handler.removeCallbacks(homeBroadcastFollowup);
                handler.removeCallbacks(wakeCheck);
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
            if (HomeSetup.paused(c) || !SystemIntegration.prefs(c).getBoolean("home_compat", false)) return;
            long now=SystemClock.elapsedRealtime();
            String reason=i.getStringExtra("reason");
            HomeSetupPolicy.GuardMode mode=guardMode();
            if(!mode.handlesHomeBroadcast())return;
            if(!mode.handlesDirectHomeKey()&&"assist".equals(reason)){
                homeBroadcastSettled=true;
                handler.removeCallbacks(fallback);
                handler.removeCallbacks(homeBroadcastFollowup);
                suppressUntil=now+10000;
                return;
            }
            if(!mode.handlesDirectHomeKey()&&"recentapps".equals(reason)){
                // Dismissing an already-open OEM recents overlay via Home can emit a trailing
                // "recentapps" broadcast right after "homekey"; ignore only that closing echo.
                if(now<dismissingRecentAppsUntil)return;
                homeBroadcastSettled=true;
                handler.removeCallbacks(fallback);
                handler.removeCallbacks(homeBroadcastFollowup);
                suppressUntil=now+10000;
                return;
            }
            if (!"homekey".equals(reason)) return;
            if(handlingHome)return;
            if(now-lastDirect<200)return;
            dismissingRecentAppsUntil=suppressUntil>now?now+600:0;
            suppressUntil=0;
            lastHomeBroadcastAt=now;
            homeBroadcastSettled=false;
            handler.removeCallbacks(fallback);
            handler.removeCallbacks(homeBroadcastFollowup);
            try {
                startActivity(homePressIntent(HomeSetup.SOURCE_COMPAT_BROADCAST));
                Diagnostics.event("HOME_BROADCAST_HANDLED");
                Log.i("MarcoTVSystem", "HOME broadcast handled");
                if(mode.handlesWindowFallback()&&!SystemIntegration.homeIsOurs(c)){
                    handler.postDelayed(homeBroadcastFollowup,150);
                }
            }
            catch (RuntimeException e) {
                HomeSetup.clearPendingHomeEvent(c);
                Log.w("MarcoTVSystem", "Home broadcast unavailable", e);
            }
        }
    };
    @Override protected void onServiceConnected() {
        connected=true;
        instance=this;
        applyServiceFlags();
        if (!receiverRegistered) {
            IntentFilter filter=new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
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
        Diagnostics.event("HOME_SERVICE_CONNECTED");
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private void launchHomeFallback(String source, String diagEvent, String logLabel) {
        long now = SystemClock.elapsedRealtime();
        if (HomeSetup.paused(this) || !canRedirect() || !guardMode().handlesWindowFallback()
                || now < suppressUntil || !SystemIntegration.prefs(this).getBoolean("home_compat", false)) return;
        boolean windowPullback = HomeSetup.SOURCE_WINDOW_FALLBACK.equals(source);
        if (windowPullback && now - lastWindowFallbackAt < 60) return;
        boolean keepRecents = now < preserveRecentsUntil;
        try {
            Intent intent = SystemIntegration.homeIntent(this);
            if (keepRecents) {
                intent.putExtra("home_press_handled", true).putExtra("recent_apps", true);
            } else {
                String token = HomeSetup.issueHomeEvent(this, source);
                intent.putExtra(HomeSetup.EXTRA_EVENT_TOKEN, token);
            }
            startActivity(intent);
            if (windowPullback) lastWindowFallbackAt = now;
            Diagnostics.event(diagEvent);
            Log.i("MarcoTVSystem", logLabel + (keepRecents ? " (preserve recents)" : ""));
        }
        catch (RuntimeException e) {
            if (!keepRecents) HomeSetup.clearPendingHomeEvent(this);
            Log.w("MarcoTVSystem", "Home redirection failed", e);
        }
    }
    private final Runnable fallback = () ->
            launchHomeFallback(HomeSetup.SOURCE_WINDOW_FALLBACK, "HOME_WINDOW_FALLBACK", "HOME window fallback");
    private final Runnable homeBroadcastFollowup = () -> {
        if (homeBroadcastSettled || SystemIntegration.homeIsOurs(this)) return;
        launchHomeFallback(HomeSetup.SOURCE_COMPAT_BROADCAST, "HOME_BROADCAST_RETRY", "HOME broadcast retry");
    };
    @Override protected boolean onKeyEvent(KeyEvent event) {
        if(event.getKeyCode()==KeyEvent.KEYCODE_POWER){
            inputGate.power(SystemClock.elapsedRealtime());
            resetPress();
            homePressTracker.reset();
            handler.removeCallbacks(fallback);
            handler.removeCallbacks(homeBroadcastFollowup);
            return false;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        boolean compat=SystemIntegration.prefs(this).getBoolean("home_compat",false);
        if(!canRedirect()||!HomeSetupPolicy.shouldRequestKeyFilter(compat,HomeSetup.paused(this),guardMode())){
            resetPress();
            return false;
        }
        if(event.getAction()==KeyEvent.ACTION_DOWN){
            if(event.getRepeatCount()==0){
                resetPress();
                suppressUntil=0;
                handlingHome=SystemIntegration.prefs(this).getBoolean("home_compat",false);
                if(handlingHome){
                    handler.removeCallbacks(fallback);
                    lastDirect=SystemClock.elapsedRealtime();
                    handler.postDelayed(longHome,600);
                }
            }
            if(handlingHome&&!held&&event.isLongPress()){
                handler.removeCallbacks(longHome);
                longHome.run();
            }
            return handlingHome;
        }
        boolean consumed=handlingHome;
        if(consumed)lastDirect=SystemClock.elapsedRealtime();
        handler.removeCallbacks(longHome);
        if(consumed&&!held&&!event.isCanceled()){
            lastDirect=SystemClock.elapsedRealtime();
            try{
                startActivity(homePressIntent(HomeSetup.SOURCE_COMPAT_KEY));
                Diagnostics.event("HOME_DIRECT_HANDLED");
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
            // cancels a pending Home broadcast retry so Marco never steals focus back from it.
            homeBroadcastSettled = true;
            handler.removeCallbacks(homeBroadcastFollowup);
        }
        if (getPackageName().equals(shown)) {
            lastActivePackage = shown;
            wakeOriginalSeen = false;
            wakeOtherAppSeen = false;
            wasOriginalHomeBeforeSleep = false;
            handler.removeCallbacks(wakeCheck);
            if (interactive) SystemIntegration.clearStandbyWakeReturn(this);
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
            handler.removeCallbacks(fallback);
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
        if (!SystemIntegration.prefs(this).getBoolean("home_compat", false)) return;
        if(!guardMode().handlesWindowFallback()||handlingHome||now<suppressUntil)return;
        if (!originalWindow){
            handler.removeCallbacks(fallback);
            return;
        }
        // OEM HOME can open a second launcher activity immediately afterwards.
        // Coalesce passive events, but pull back immediately when an active Home broadcast is in flight.
        handler.removeCallbacks(fallback);
        handler.removeCallbacks(homeBroadcastFollowup);
        long delay = now - lastHomeBroadcastAt <= 1200 ? 0 : 60;
        handler.postDelayed(fallback, delay);
    }
    @Override public boolean onUnbind(Intent intent) {
        connected=false;
        runtimeFlags=0;
        runtimeKeyFilterRequested=false;
        markServiceStoppedCleanly();
        return super.onUnbind(intent);
    }
    @Override public void onDestroy() {
        connected=false;
        runtimeFlags=0;
        runtimeKeyFilterRequested=false;
        markServiceStoppedCleanly();
        if(instance==this)instance=null;
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) unregisterReceiver(homeBroadcast);
        super.onDestroy();
    }
    @Override public void onInterrupt() { }
}
