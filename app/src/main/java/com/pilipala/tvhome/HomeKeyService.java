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

/** Optional Home filter; ordinary navigation and power keys always pass through. */
public final class HomeKeyService extends AccessibilityService {
    static volatile boolean connected;
    private static HomeKeyService instance;
    private long suppressUntil;
    private boolean held;
    private boolean nativeHome(){return SystemIntegration.prefs(this).getBoolean("home_native_keys",false);}
    static boolean openSystemRecents(){return instance!=null&&instance.systemRecents();}
    private boolean systemRecents(){
        handler.removeCallbacks(fallback);homePressTracker.reset();suppressUntil=SystemClock.elapsedRealtime()+10000;
        boolean result=performGlobalAction(GLOBAL_ACTION_RECENTS);
        if(!result)suppressUntil=0;
        Diagnostics.event(result?"SYSTEM_RECENTS_REQUESTED":"SYSTEM_RECENTS_UNAVAILABLE");return result;
    }
    private void resetPress(){handlingHome=false;held=false;handler.removeCallbacks(longHome);}
    private final Runnable longHome=()->{
        if(!this.handlingHome||held||!canRedirect())return;
        held=true;this.homePressTracker.reset();
        String action=SystemIntegration.prefs(this).getString("home_long_action","system");
        if(action.equals("marco")){try{startActivity(SystemIntegration.homeIntent(this).putExtra("home_press_handled",true).putExtra("recent_apps",true));}catch(RuntimeException e){Log.w("MarcoTVSystem","Long HOME unavailable",e);}}
        else if(action.equals("system")&&!systemRecents())android.widget.Toast.makeText(this,"系统不支持此入口，可在系统与启动中启用原厂按键模式",android.widget.Toast.LENGTH_LONG).show();
    };
    private long lastOpen;
    private long lastDirect=-10000;
    private final HomePressTracker homePressTracker=new HomePressTracker();
    private final HomeInputGate inputGate=new HomeInputGate();
    private boolean canRedirect(){android.os.PowerManager power=(android.os.PowerManager)getSystemService(POWER_SERVICE);return inputGate.allow(power!=null&&power.isInteractive(),SystemClock.elapsedRealtime());}
    private Intent homePressIntent(){
        long now=SystemClock.elapsedRealtime();lastOpen=now;
        android.content.SharedPreferences p=getSharedPreferences("home",0);
        boolean twice=false;if(p.getBoolean("recent_enabled",false))twice=homePressTracker.press(now,p.getInt("recent_double_ms",400));else homePressTracker.reset();
        return SystemIntegration.homeIntent(this).putExtra("home_press_handled",true).putExtra("recent_apps",twice);
    }
    private boolean handlingHome;
    private boolean receiverRegistered;
    private final BroadcastReceiver homeBroadcast = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if(Intent.ACTION_SCREEN_OFF.equals(i.getAction())||Intent.ACTION_SCREEN_ON.equals(i.getAction())){
                boolean on=Intent.ACTION_SCREEN_ON.equals(i.getAction());inputGate.screen(on,SystemClock.elapsedRealtime());resetPress();homePressTracker.reset();handler.removeCallbacks(fallback);Diagnostics.event(on?"SCREEN_ON_HOME_SETTLING":"SCREEN_OFF_HOME_SUSPENDED");return;
            }
            if(!canRedirect())return;
            if (!SystemIntegration.prefs(c).getBoolean("home_compat", false)) return;
            String reason=i.getStringExtra("reason");
            if(nativeHome()&&("recentapps".equals(reason)||"assist".equals(reason))){suppressUntil=SystemClock.elapsedRealtime()+10000;return;}
            if(handlingHome||SystemClock.elapsedRealtime()<suppressUntil)return;
            if (!"homekey".equals(reason)) return;
            if(SystemClock.elapsedRealtime()-lastDirect<200)return;
            handler.removeCallbacks(fallback);
            try { startActivity(homePressIntent()); Diagnostics.event("HOME_BROADCAST_HANDLED");Log.i("MarcoTVSystem", "HOME broadcast handled"); }
            catch (RuntimeException e) { Log.w("MarcoTVSystem", "Home broadcast unavailable", e); }
        }
    };
    @Override protected void onServiceConnected() {
        connected=true;instance=this;
        AccessibilityServiceInfo info = getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        setServiceInfo(info);
        if (!receiverRegistered) {IntentFilter filter=new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);filter.addAction(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_SCREEN_ON);registerReceiver(homeBroadcast,filter); receiverRegistered = true; }
        Diagnostics.event("HOME_SERVICE_CONNECTED");
        Log.i("MarcoTVSystem", "HOME key filter ready; capabilities=" + info.getCapabilities() + "; flags=" + info.flags);
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable fallback = () -> {
        if (!canRedirect()||nativeHome()||SystemClock.elapsedRealtime()<suppressUntil||!SystemIntegration.prefs(this).getBoolean("home_compat", false)) return;
        try { startActivity(SystemIntegration.homeIntent(this)); Diagnostics.event("HOME_WINDOW_FALLBACK");Log.i("MarcoTVSystem", "HOME window fallback"); }
        catch (RuntimeException e) { Log.w("MarcoTVSystem", "Home redirection failed", e); }
    };
    @Override protected boolean onKeyEvent(KeyEvent event) {
        if(event.getKeyCode()==KeyEvent.KEYCODE_POWER){inputGate.power(SystemClock.elapsedRealtime());resetPress();homePressTracker.reset();handler.removeCallbacks(fallback);return false;}
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if(!canRedirect()||nativeHome()){resetPress();return false;}
        if(event.getAction()==KeyEvent.ACTION_DOWN){
            if(event.getRepeatCount()==0){
                resetPress();suppressUntil=0;
                handlingHome=SystemIntegration.prefs(this).getBoolean("home_compat",false);
                if(handlingHome){handler.removeCallbacks(fallback);lastDirect=SystemClock.elapsedRealtime();handler.postDelayed(longHome,600);}
            }
            if(handlingHome&&!held&&event.isLongPress()){handler.removeCallbacks(longHome);longHome.run();}
            return handlingHome;
        }
        boolean consumed=handlingHome;if(consumed)lastDirect=SystemClock.elapsedRealtime();
        handler.removeCallbacks(longHome);
        if(consumed&&!held&&!event.isCanceled()){
            lastDirect=SystemClock.elapsedRealtime();
            try{startActivity(homePressIntent());Diagnostics.event("HOME_DIRECT_HANDLED");}
            catch(RuntimeException e){Log.w("MarcoTVSystem","Direct HOME failed",e);}
        }
        resetPress();return consumed;
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.getPackageName() == null) return;
        String shown = event.getPackageName().toString();
        if(!canRedirect()){handler.removeCallbacks(fallback);return;}
        if (AmbientIntegration.enabled(this) && shown.equals("com.mitv.screensaver")
                && event.getClassName() != null && event.getClassName().toString().equals("com.mitv.screensaver.RenderActivity")) {
            // Xiaomi launches a proprietary activity instead of Android's selected dream.
            // Only its actual full-screen saver is replaced, never its settings page.
            try { startActivity(SystemIntegration.homeIntent(this).putExtra("ambient", true)); Log.i("MarcoTVSystem", "Vendor screensaver replaced"); }
            catch (RuntimeException e) { Log.w("MarcoTVSystem", "Vendor ambient unavailable", e); }
            return;
        }
        if (!SystemIntegration.prefs(this).getBoolean("home_compat", false)) return;
        if(nativeHome()||handlingHome||SystemClock.elapsedRealtime()<suppressUntil)return;
        ComponentName original = ComponentName.unflattenFromString(SystemIntegration.prefs(this).getString("original_home", ""));
        if (original == null || event.getPackageName() == null || getPackageName().equals(original.getPackageName())) return;
        // This Xiaomi firmware dispatches HOME through tvhome into its separate lite launcher.
        boolean originalWindow = original.getPackageName().equals(shown)
                || (original.getPackageName().equals("com.mitv.tvhome") && shown.equals("com.xiaomi.eetv.litehome"));
        if (!originalWindow){handler.removeCallbacks(fallback);return;}
        // OEM HOME can open a second launcher activity immediately afterwards.
        // Coalesce these events instead of discarding the second one for 1.2 s.
        handler.removeCallbacks(fallback); handler.postDelayed(fallback, 60);
    }
    @Override public void onDestroy() {
        connected=false;if(instance==this)instance=null;
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) unregisterReceiver(homeBroadcast);
        super.onDestroy();
    }
    @Override public void onInterrupt() { }
}
