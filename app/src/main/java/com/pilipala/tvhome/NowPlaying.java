// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.app.PendingIntent;
import android.content.*;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.*;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import java.util.*;

final class NowPlaying {
    static final class Item {
        String title,source,packageName; Bitmap artwork; boolean playing; MediaController controller;
    }
    private final MainActivity host;
    private final MediaSessionManager manager;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ArrayList<MediaController> controllers=new ArrayList<>();
    private final ComponentName listener;
    private boolean observing;
    private final Set<MediaSession.Token> hidden=new HashSet<>();
    private final Map<MediaSession.Token,Long> pausedSince=new HashMap<>();
    private MediaSession.Token preferred;
    private final Runnable expire=new Runnable(){public void run(){if(host.foreground){publish();handler.postDelayed(this,20000);}}};
    int availableCount(){int count=0;for(MediaController c:controllers)if(priority(c.getPlaybackState())>0)count++;return count;}
    void hideCurrent(){if(item!=null)hidden.add(item.controller.getSessionToken());publish();}
    void restoreHidden(){hidden.clear();pausedSince.clear();publish();}
    private boolean eligible(MediaController c){
        int rank=priority(c.getPlaybackState());MediaSession.Token token=c.getSessionToken();
        if(rank==2)pausedSince.remove(token);
        else if(rank==1&&!pausedSince.containsKey(token))pausedSince.put(token,android.os.SystemClock.elapsedRealtime());
        return rank>0&&!hidden.contains(token)&&(rank!=1||android.os.SystemClock.elapsedRealtime()-pausedSince.get(token)<30*60*1000L);
    }

    Item item;
    private final MediaSessionManager.OnActiveSessionsChangedListener sessions=this::replace;
    private final MediaController.Callback callback=new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { publish(); }
        @Override public void onMetadataChanged(MediaMetadata data) { publish(); }
        @Override public void onSessionDestroyed() { handler.post(()->refresh()); }
    };
    NowPlaying(MainActivity host) {
        this.host=host;manager=(MediaSessionManager)host.getSystemService(Context.MEDIA_SESSION_SERVICE);
        listener=new ComponentName(host,PlaybackListenerService.class);
    }
    boolean enabled() { return host.preferences.getBoolean("now_playing",true); }
    boolean authorized() {
        String allowed=Settings.Secure.getString(host.getContentResolver(),"enabled_notification_listeners");
        if(allowed!=null)for(String value:allowed.split(":"))if(listener.equals(ComponentName.unflattenFromString(value)))return true;
        return false;
    }
    void refresh() {
        if(!host.foreground || !enabled() || !authorized() || manager==null) { stop();return; }
        try {
            if(!observing) { manager.addOnActiveSessionsChangedListener(sessions,listener,handler);observing=true; }
            replace(manager.getActiveSessions(listener));handler.removeCallbacks(expire);handler.postDelayed(expire,20000);
        } catch(SecurityException error) { stop(); }
    }
    private void replace(List<MediaController> active) {
        for(MediaController c:controllers)c.unregisterCallback(callback);controllers.clear();
        if(active!=null)for(MediaController c:active) {
            controllers.add(c);c.registerCallback(callback,handler);
        }
        Set<MediaSession.Token> activeTokens=new HashSet<>();for(MediaController c:controllers)activeTokens.add(c.getSessionToken());
        hidden.retainAll(activeTokens);pausedSince.keySet().retainAll(activeTokens);if(preferred!=null&&!activeTokens.contains(preferred))preferred=null;
        publish();
    }
    static int priority(PlaybackState state) {
        if(state==null)return 0;
        switch(state.getState()) {
            case PlaybackState.STATE_PLAYING:case PlaybackState.STATE_BUFFERING:case PlaybackState.STATE_CONNECTING:return 2;
            case PlaybackState.STATE_PAUSED:return 1;
            default:return 0;
        }
    }
    private void publish() {
        MediaController chosen=null;int score=0;
        for(MediaController c:controllers) {if(!eligible(c))continue;int rank=priority(c.getPlaybackState())+(c.getSessionToken().equals(preferred)?10:0);if(rank>score){chosen=c;score=rank;}}
        item=chosen==null?null:describe(chosen);
        if(host.home!=null) {
            if(host.home.row==-2) {
                if(item==null) {host.home.row=0;host.home.ensureVisible();host.home.animateFocus();}
                host.home.describeFocus();
            }
            host.home.invalidate();
        }
    }
    private Item describe(MediaController controller) {
        Item result=new Item();result.controller=controller;result.packageName=controller.getPackageName();
        try { result.source=String.valueOf(host.getPackageManager().getApplicationLabel(host.getPackageManager().getApplicationInfo(result.packageName,0))); }
        catch(Exception ignored){result.source=result.packageName;}
        MediaMetadata data=controller.getMetadata();result.title=result.source;
        if(data!=null) {
            CharSequence title=data.getDescription().getTitle();if(title!=null&&title.length()>0)result.title=title.toString();
            result.artwork=data.getDescription().getIconBitmap();
        }
        if(result.artwork==null)try {
            android.graphics.drawable.Drawable icon=host.getPackageManager().getApplicationIcon(result.packageName);
            result.artwork=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888);icon.setBounds(0,0,96,96);icon.draw(new android.graphics.Canvas(result.artwork));
        }catch(Exception ignored){}
        result.playing=priority(controller.getPlaybackState())==2;return result;
    }
    void stop() {
        handler.removeCallbacksAndMessages(null);
        if(observing&&manager!=null)manager.removeOnActiveSessionsChangedListener(sessions);observing=false;
        for(MediaController c:controllers)c.unregisterCallback(callback);controllers.clear();item=null;
        if(host.home!=null)host.home.invalidate();
    }
    void settings() {
        host.showDialog(new TvDialog.Builder(host).setPageId("playback").setTitle("正在播放")
                .setOrder("playback","enabled","permission","source","restore")
                .setMessage("在桌面左上显示其他应用提供的媒体标题、封面和状态。仅使用系统媒体会话，不读取或保存普通通知内容。\n\n显示卡片："+(enabled()?"已开启":"已关闭")+"\n通知使用权："+(authorized()?"已允许":"未允许")+"\n没有提供媒体会话的应用无法显示。暂停会话在观察 30 分钟后收起，可手动恢复。")
                .setItems(new String[]{enabled()?"关闭播放卡片":"开启播放卡片","打开通知使用权设置","选择播放来源","恢复暂时隐藏的播放卡片"},(d,w)->{
                    if(w==0){host.preferences.edit().putBoolean("now_playing",!enabled()).apply();refresh();settings();}
                    else if(w==2)sources();
                    else if(w==3){restoreHidden();settings();}
                    else try {host.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));}
                    catch(RuntimeException error){host.toast("电视未提供此授权页面，请按安装说明使用 ADB 授权");}
                }).setNegativeButton("返回",null).create());
    }
    void controls() {
        final Item selected=item;if(selected==null){host.toast("当前没有可用的播放会话");return;}
        PlaybackState state=selected.controller.getPlaybackState();long actions=state==null?0:state.getActions();
        boolean canToggle=(actions&(PlaybackState.ACTION_PLAY_PAUSE|(selected.playing?PlaybackState.ACTION_PAUSE:PlaybackState.ACTION_PLAY)))!=0;
        ArrayList<String> choices=new ArrayList<>();choices.add("返回播放应用");
        if(canToggle)choices.add(selected.playing?"暂停":"继续播放");
        choices.add("选择播放来源");choices.add("暂时隐藏此卡片");
        host.showDialog(new TvDialog.Builder(host).setTitle(selected.title).setMessage(selected.source+" · "+(selected.playing?"正在播放":"已暂停"))
                .setItems(choices.toArray(new String[0]),(d,w)->{
                    String action=choices.get(w);
                    if(w==0)open(selected);
                    else if(action.equals("选择播放来源"))sources();
                    else if(action.equals("暂时隐藏此卡片")){hidden.add(selected.controller.getSessionToken());publish();host.exitMenus();host.toast("卡片已隐藏，播放不受影响；可在播放卡片设置中恢复");}
                    else try {
                        if(priority(selected.controller.getPlaybackState())==2)selected.controller.getTransportControls().pause();else selected.controller.getTransportControls().play();
                    }catch(RuntimeException error){Diagnostics.event("MEDIA_CONTROL_FAILED");host.toast("播放应用未响应控制");}
                }).setNegativeButton("返回",null).create());
    }
    void sources(){
        ArrayList<MediaController> active=new ArrayList<>();for(MediaController c:controllers)if(priority(c.getPlaybackState())>0)active.add(c);
        String[] names=new String[active.size()+1];names[0]="自动选择 · 正在播放优先";
        for(int i=0;i<active.size();i++){Item entry=describe(active.get(i));names[i+1]=entry.source+" · "+(entry.playing?"播放中":"已暂停")+"\n"+entry.title;}
        host.showDialog(new TvDialog.Builder(host).setTitle("选择播放来源").setMessage("选择来源只改变桌面展示，不会停止其他播放器。暂时隐藏仅在本次 Marco TV 进程中保留。")
            .setItems(names,(d,w)->{preferred=w==0?null:active.get(w-1).getSessionToken();if(preferred!=null){hidden.remove(preferred);pausedSince.remove(preferred);}publish();host.exitMenus();})
            .setNegativeButton("返回",null).create());
    }
    private void open(Item selected) {
        try {
            PendingIntent intent=selected.controller.getSessionActivity();
            if(intent!=null)intent.send();
            else {
                Intent launch=host.getPackageManager().getLeanbackLaunchIntentForPackage(selected.packageName);
                if(launch==null)launch=host.getPackageManager().getLaunchIntentForPackage(selected.packageName);
                if(launch==null){host.toast("播放应用未提供打开入口");return;}host.startActivity(launch);
            }
            AppOrder.opened(host.preferences,selected.packageName);
        } catch(PendingIntent.CanceledException|RuntimeException error){host.toast("暂时无法返回播放应用");}
    }
}
