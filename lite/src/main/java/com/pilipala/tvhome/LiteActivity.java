// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputFilter;
import android.view.*;
import android.widget.EditText;
import android.widget.Toast;
import java.util.*;
import java.io.*;
import org.json.*;

/** Separate API-14 entry point. Never loads full-edition services or network permissions. */
public final class LiteActivity extends Activity {
    static final int REQ_PICK_WALLPAPER=101;
    static final int REQ_PICK_COVER=102;
    SharedPreferences prefs;
    LiteView home;
    ArrayList<App> apps=new ArrayList<App>();
    ArrayList<App> favorites=new ArrayList<App>();
    ArrayList<App> tvInputs=new ArrayList<App>();
    ArrayList<App> allApps=new ArrayList<App>();
    ArrayList<App> filteredApps=new ArrayList<App>();
    ArrayList<String[]> manualActivities=new ArrayList<String[]>();
    boolean manualScanning,weatherLoaded;
    volatile WeatherSnapshot localWeather;
    volatile LocalWeatherProbe.Result lastWeatherProbe;
    final Handler handler=new Handler();
    boolean alive;
    volatile boolean destroyed;
    boolean loaded,loading,dirty=true,reloadRequested;
    int loadGeneration,scanCount;
    String status="正在读取应用…",restoreStatus="",restoredSelection,pendingCoverAppId;
    ArrayList<App> catalog=new ArrayList<App>();
    final BroadcastReceiver packageChanges=new BroadcastReceiver(){
        public void onReceive(Context c,Intent i){
            dirty=true;
            if(alive)loadApps();
        }
    };
    final BroadcastReceiver timeChanges=new BroadcastReceiver(){
        public void onReceive(Context c,Intent i){
            home.invalidate();
            home.scheduleTick();
        }
    };
    static final class App {
        String id,origLabel,label,version,cover;
        Intent intent;
        Bitmap icon;
        boolean pinned,hidden,favorite,systemApp,updatedSystemApp;
        long opened,pinOrder;
        int customOrder=Integer.MAX_VALUE,favOrder=Integer.MAX_VALUE;
        int customW,customH,customD=-1,customR=-1;
        boolean canUninstall(){
            return !systemApp||updatedSystemApp;
        }
        boolean hasCustomSize(){
            return customW>0||customH>0||customD>=0||customR>=0;
        }
        boolean hasCustomCover(){
            return cover!=null&&cover.length()>0;
        }
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        prefs=getSharedPreferences("lite_home",0);
        home=new LiteView(this);
        setContentView(home);
        IntentFilter filter=new IntentFilter();
        for(String action:new String[]{Intent.ACTION_PACKAGE_ADDED,Intent.ACTION_PACKAGE_REMOVED,Intent.ACTION_PACKAGE_CHANGED,Intent.ACTION_PACKAGE_REPLACED})filter.addAction(action);
        filter.addDataScheme("package");
        registerReceiver(packageChanges,filter);
        IntentFilter timeFilter=new IntentFilter();
        timeFilter.addAction(Intent.ACTION_TIME_CHANGED);
        timeFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeChanges,timeFilter);
        if(state!=null)home.restoreState(state);
        if(!weatherLoaded)probeWeatherAsync(false);
    }
    @Override protected void onResume(){
        super.onResume();
        alive=true;
        home.resume();
        if(!loaded||dirty)loadApps();
        else rebuildVisible();
    }
    @Override protected void onPause(){
        alive=false;
        home.pause();
        super.onPause();
    }
    @Override protected void onDestroy(){
        destroyed=true;
        loadGeneration++;
        unregisterReceiver(packageChanges);
        unregisterReceiver(timeChanges);
        home.release();
        recycle(catalog);
        super.onDestroy();
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);
        setIntent(intent);
        home.cancelMove(false);
        home.closePage();
        home.wake();
    }
    @Override protected void onSaveInstanceState(Bundle out){
        super.onSaveInstanceState(out);
        home.saveState(out);
    }
    @Override protected void onActivityResult(int req,int res,Intent data){
        super.onActivityResult(req,res,data);
        if(res!=RESULT_OK||data==null||data.getData()==null)return;
        if(req==REQ_PICK_WALLPAPER){
            importWallpaper(data.getData());
        }else if(req==REQ_PICK_COVER&&pendingCoverAppId!=null){
            importCardCover(pendingCoverAppId,data.getData());
        }
    }
    void toast(String text){
        Toast.makeText(this,text,Toast.LENGTH_SHORT).show();
    }
    static int readParam(SharedPreferences p,int i){
        if(LiteSettings.isBool(i))return p.getBoolean(LiteSettings.KEYS[i],LiteSettings.DEFAULT[i]!=0)?1:0;
        return LiteSettings.clamp(i,p.getInt(LiteSettings.KEYS[i],LiteSettings.DEFAULT[i]));
    }
    static void writeParam(SharedPreferences.Editor e,int i,int v){
        int c=LiteSettings.clamp(i,v);
        if(LiteSettings.isBool(i))e.putBoolean(LiteSettings.KEYS[i],c!=0);
        else e.putInt(LiteSettings.KEYS[i],c);
    }
    static void writeDraft(PreviewPreferences d,int i,int v){
        int c=LiteSettings.clamp(i,v);
        if(LiteSettings.isBool(i))d.booleans.put(LiteSettings.KEYS[i],c!=0);
        else d.values.put(LiteSettings.KEYS[i],c);
    }
    int sortMode(){
        if(prefs.contains("sort_mode"))return Math.max(0,Math.min(2,prefs.getInt("sort_mode",0)));
        return prefs.getBoolean("alphabetic",false)?1:0;
    }
    String sortModeLabel(){
        int m=sortMode();
        return m==1?"首字母":m==2?"自定义":"最近打开";
    }
    int sleepMinutes(){
        return prefs.getInt("ambient_sleep_minutes",30);
    }
    int volumePercent(){
        try{
            AudioManager am=(AudioManager)getSystemService(AUDIO_SERVICE);
            int max=Math.max(1,am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            return Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC)*100f/max);
        }catch(Exception ignored){
            return 0;
        }
    }
    void adjustVolume(int delta){
        try{
            AudioManager am=(AudioManager)getSystemService(AUDIO_SERVICE);
            int max=Math.max(1,am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            int cur=am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int next=Math.max(0,Math.min(max,cur+delta));
            am.setStreamVolume(AudioManager.STREAM_MUSIC,next,0);
        }catch(Exception ignored){}
    }
    int hiddenCount(){
        int n=0;
        for(App a:catalog)if(prefs.getBoolean("hidden:"+a.id,false))n++;
        return n;
    }
    App findApp(String id){
        if(id==null)return null;
        for(App a:catalog)if(id.equals(a.id))return a;
        return null;
    }
    boolean isFirstPinned(App a){
        return a!=null&&a.pinned&&!apps.isEmpty()&&a.id.equals(apps.get(0).id);
    }
    static HashMap<String,Integer> parseOrderMap(String raw){
        HashMap<String,Integer> map=new HashMap<String,Integer>();
        if(raw!=null&&raw.length()>0){
            String[] ids=raw.split(",");
            for(int i=0;i<ids.length;i++)if(ids[i].length()>0)map.put(ids[i],i);
        }
        return map;
    }
    void rebuildVisible(){
        String selected=restoredSelection==null?home.selectedId():restoredSelection;
        restoredSelection=null;
        HashMap<String,Integer> customRanks=parseOrderMap(prefs.getString("order_list",""));
        HashMap<String,Integer> favRanks=parseOrderMap(prefs.getString("fav_order",""));
        ArrayList<App> visible=new ArrayList<App>();
        ArrayList<App> favs=new ArrayList<App>();
        ArrayList<App> all=new ArrayList<App>();
        for(App app:catalog){
            String customLabel=prefs.getString("label:"+app.id,"");
            app.label=(customLabel!=null&&customLabel.trim().length()>0)?customLabel.trim():app.origLabel;
            app.pinned=prefs.getBoolean("pinned:"+app.id,false);
            app.pinOrder=prefs.getLong("pin_order:"+app.id,0);
            app.hidden=prefs.getBoolean("hidden:"+app.id,false);
            app.favorite=prefs.getBoolean("fav:"+app.id,false);
            app.opened=prefs.getLong("opened:"+app.id,0);
            app.cover=prefs.getString("cover:"+app.id,"");
            Integer rank=customRanks.get(app.id);
            app.customOrder=rank!=null?rank:Integer.MAX_VALUE;
            Integer fRank=favRanks.get(app.id);
            app.favOrder=fRank!=null?fRank:Integer.MAX_VALUE;
            int[] sz=LiteSettings.parseCardSize(prefs.getString("size:"+app.id,""));
            app.customW=sz[0];
            app.customH=sz[1];
            app.customD=sz[2];
            app.customR=sz[3];
            all.add(app);
            if(!app.hidden){
                visible.add(app);
                if(app.favorite)favs.add(app);
            }
        }
        final int mode=sortMode();
        Collections.sort(visible,new Comparator<App>(){
            public int compare(App a,App b){
                if(a.pinned!=b.pinned)return a.pinned?-1:1;
                if(a.pinned&&a.pinOrder!=b.pinOrder)return a.pinOrder>b.pinOrder?-1:1;
                if(mode==2&&a.customOrder!=b.customOrder)return a.customOrder<b.customOrder?-1:1;
                if(mode==0&&a.opened!=b.opened)return a.opened>b.opened?-1:1;
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        Collections.sort(favs,new Comparator<App>(){
            public int compare(App a,App b){
                if(a.favOrder!=b.favOrder)return a.favOrder<b.favOrder?-1:1;
                if(a.pinned!=b.pinned)return a.pinned?-1:1;
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        Collections.sort(all,new Comparator<App>(){
            public int compare(App a,App b){
                if(a.hidden!=b.hidden)return a.hidden?1:-1;
                if(a.pinned!=b.pinned)return a.pinned?-1:1;
                if(a.pinned&&a.pinOrder!=b.pinOrder)return a.pinOrder>b.pinOrder?-1:1;
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        apps=visible;
        favorites=favs;
        allApps=all;
        if(home.activeRow==1&&favorites.isEmpty()){
            home.activeRow=tvInputs.isEmpty()?0:2;
            home.rowSpring.snap(home.activeRow);
        }else if(home.activeRow==2&&tvInputs.isEmpty()){
            home.activeRow=favorites.isEmpty()?0:1;
            home.rowSpring.snap(home.activeRow);
        }
        rebuildFilteredApps();
        home.selectId(selected);
        status=apps.isEmpty()?(catalog.isEmpty()?"没有找到可启动应用，可在设置中刷新":"应用已全部隐藏，可在设置或全部应用中恢复"):"";
        home.invalidate();
    }
    void rebuildFilteredApps(){
        ArrayList<App> out=new ArrayList<App>();
        String q=home.allQuery==null?"":home.allQuery.trim().toLowerCase(Locale.US);
        int f=home.allFilter;
        for(App a:allApps){
            if(f==1&&!a.canUninstall())continue;
            if(f==2&&!a.favorite)continue;
            if(f==3&&!a.hidden)continue;
            if(f==4&&!a.systemApp)continue;
            if(q.length()>0){
                boolean hit=a.label.toLowerCase(Locale.US).contains(q)
                        ||a.origLabel.toLowerCase(Locale.US).contains(q)
                        ||a.id.toLowerCase(Locale.US).contains(q);
                if(!hit)continue;
            }
            out.add(a);
        }
        filteredApps=out;
        if(home.page==6&&home.choice>=filteredApps.size())home.choice=Math.max(0,filteredApps.size()-1);
    }
    void loadApps(){
        if(destroyed)return;
        if(loading){
            reloadRequested=true;
            return;
        }
        loading=true;
        dirty=false;
        scanCount++;
        final int generation=++loadGeneration;
        final int iconLimit=Math.min(64,Math.max(8,((android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE))
                .getMemoryClass()*1024*1024/24/(96*96*4)));
        new Thread(new Runnable(){
            public void run(){
                final ArrayList<App> found=new ArrayList<App>();
                final ArrayList<App> foundInputs=new ArrayList<App>();
                HashSet<String> packages=new HashSet<String>();
                PackageManager pm=getPackageManager();
                boolean success=true;
                try{
                    ArrayList<ResolveInfo> matches=new ArrayList<ResolveInfo>();
                    for(String category:new String[]{"android.intent.category.LEANBACK_LAUNCHER",Intent.CATEGORY_LAUNCHER})matches.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                            .addCategory(category),0));
                    for(ResolveInfo r:matches){
                        ActivityInfo a=r.activityInfo;
                        if(a==null||!a.enabled||!a.exported||!a.applicationInfo.enabled||a.packageName.equals(getPackageName())||packages.contains(a.packageName))continue;
                        packages.add(a.packageName);
                        App app=new App();
                        app.id=a.packageName;
                        CharSequence rawLabel=r.loadLabel(pm);
                        app.origLabel=rawLabel!=null?rawLabel.toString():a.packageName;
                        app.label=app.origLabel;
                        int flags=a.applicationInfo.flags;
                        app.systemApp=(flags&ApplicationInfo.FLAG_SYSTEM)!=0;
                        app.updatedSystemApp=(flags&ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0;
                        try{
                            PackageInfo pi=pm.getPackageInfo(a.packageName,0);
                            app.version=pi!=null&&pi.versionName!=null?pi.versionName:"";
                        }catch(Exception ignored){
                            app.version="";
                        }
                        app.pinned=prefs.getBoolean("pinned:"+app.id,false);
                        app.pinOrder=prefs.getLong("pin_order:"+app.id,0);
                        app.hidden=prefs.getBoolean("hidden:"+app.id,false);
                        app.favorite=prefs.getBoolean("fav:"+app.id,false);
                        app.opened=prefs.getLong("opened:"+app.id,0);
                        app.intent=new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(a.packageName,a.name))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                        found.add(app);
                    }
                    Map<String,?> allPrefs=prefs.getAll();
                    if(allPrefs!=null){
                        for(Map.Entry<String,?> me:allPrefs.entrySet()){
                            String pk=me.getKey();
                            if(pk==null||!pk.startsWith("manual:")||!(me.getValue() instanceof String))continue;
                            String pkg=pk.substring(7);
                            String comp=(String)me.getValue();
                            if(pkg.length()==0||comp.length()==0||pkg.equals(getPackageName())||packages.contains(pkg))continue;
                            ComponentName cn=ComponentName.unflattenFromString(comp);
                            if(cn==null)continue;
                            try{
                                ActivityInfo a=pm.getActivityInfo(cn,0);
                                if(a==null||!a.enabled||!a.exported||a.applicationInfo==null||!a.applicationInfo.enabled)continue;
                                packages.add(pkg);
                                App app=new App();
                                app.id=pkg;
                                CharSequence rl=a.loadLabel(pm);
                                app.origLabel=(rl!=null&&rl.length()>0)?rl.toString():pkg;
                                app.label=app.origLabel;
                                int flags=a.applicationInfo.flags;
                                app.systemApp=(flags&ApplicationInfo.FLAG_SYSTEM)!=0;
                                app.updatedSystemApp=(flags&ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0;
                                try{
                                    PackageInfo pi=pm.getPackageInfo(pkg,0);
                                    app.version=pi!=null&&pi.versionName!=null?pi.versionName:"";
                                }catch(Exception ignored){
                                    app.version="";
                                }
                                app.pinned=prefs.getBoolean("pinned:"+app.id,false);
                                app.pinOrder=prefs.getLong("pin_order:"+app.id,0);
                                app.hidden=prefs.getBoolean("hidden:"+app.id,false);
                                app.favorite=prefs.getBoolean("fav:"+app.id,false);
                                app.opened=prefs.getLong("opened:"+app.id,0);
                                app.intent=new Intent(Intent.ACTION_MAIN).setComponent(cn)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                                found.add(app);
                            }catch(Throwable ignored){}
                        }
                    }
                    if(Build.VERSION.SDK_INT>=21){
                        try{
                            Object mgr=getSystemService("tv_input");
                            if(mgr!=null){
                                java.lang.reflect.Method getList=mgr.getClass().getMethod("getTvInputList");
                                Object rawList=getList.invoke(mgr);
                                if(rawList instanceof List){
                                    Class<?> infoCls=Class.forName("android.media.tv.TvInputInfo");
                                    java.lang.reflect.Method getId=infoCls.getMethod("getId");
                                    java.lang.reflect.Method loadLabel=infoCls.getMethod("loadLabel",Context.class);
                                    java.lang.reflect.Method isHidden=infoCls.getMethod("isHidden",Context.class);
                                    java.lang.reflect.Method isPassthrough=infoCls.getMethod("isPassthroughInput");
                                    Class<?> contractCls=Class.forName("android.media.tv.TvContract");
                                    java.lang.reflect.Method buildUri=contractCls.getMethod("buildChannelUriForPassthroughInput",String.class);
                                    for(Object item:(List<?>)rawList){
                                        if(item==null)continue;
                                        if(!Boolean.TRUE.equals(isPassthrough.invoke(item)))continue;
                                        if(Boolean.TRUE.equals(isHidden.invoke(item,LiteActivity.this)))continue;
                                        String inputId=(String)getId.invoke(item);
                                        if(inputId==null||inputId.length()==0)continue;
                                        Object uriObj=buildUri.invoke(null,inputId);
                                        if(!(uriObj instanceof Uri))continue;
                                        CharSequence lbl=(CharSequence)loadLabel.invoke(item,LiteActivity.this);
                                        App inp=new App();
                                        inp.id="input:"+inputId;
                                        inp.origLabel=lbl!=null?lbl.toString():"输入源";
                                        inp.label=inp.origLabel;
                                        inp.systemApp=true;
                                        inp.intent=new Intent(Intent.ACTION_VIEW,(Uri)uriObj).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                        foundInputs.add(inp);
                                    }
                                }
                            }
                        }catch(Throwable ignored){}
                    }
                    final int mode=sortMode();
                    Collections.sort(found,new Comparator<App>(){
                        public int compare(App a,App b){
                            if(a.hidden!=b.hidden)return a.hidden?1:-1;
                            if(a.pinned!=b.pinned)return a.pinned?-1:1;
                            if(a.pinned&&a.pinOrder!=b.pinOrder)return a.pinOrder>b.pinOrder?-1:1;
                            if(mode==0&&a.opened!=b.opened)return a.opened>b.opened?-1:1;
                            return a.label.compareToIgnoreCase(b.label);
                        }
                    });
                    for(int i=0;i<Math.min(iconLimit,found.size());i++){
                        if(destroyed)break;
                        try{
                            App a=found.get(i);
                            Drawable d=pm.getApplicationIcon(a.id);
                            a.icon=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888);
                            int w=Math.max(1,d.getIntrinsicWidth()),h=Math.max(1,d.getIntrinsicHeight());
                            float f=84f/Math.max(w,h);
                            d.setBounds((int)(48-w*f/2),(int)(48-h*f/2),(int)(48+w*f/2),(int)(48+h*f/2));
                            d.draw(new Canvas(a.icon));
                        }catch(Exception ignored){}
                    }
                }catch(Exception failure){
                    success=false;
                }
                final boolean ok=success;
                handler.post(new Runnable(){
                    public void run(){
                        loading=false;
                        if(generation!=loadGeneration||destroyed||isFinishing()){
                            recycle(found);
                            return;
                        }
                        if(ok){
                            ArrayList<App> old=catalog;
                            catalog=found;
                            tvInputs=foundInputs;
                            loaded=true;
                            rebuildVisible();
                            recycle(old);
                        }else{
                            recycle(found);
                            dirty=true;
                            status="读取应用失败，请在设置中刷新应用列表";
                            toast(status);
                            home.invalidate();
                        }
                        if(reloadRequested){
                            reloadRequested=false;
                            dirty=true;
                            if(alive)loadApps();
                        }
                    }
                });
            }
        },"MarcoLite-apps").start();
    }
    static void recycle(List<App> list){
        for(App a:list)if(a.icon!=null&&!a.icon.isRecycled())a.icon.recycle();
    }
    static boolean isConfirmKey(int key){
        return key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER
                ||key==KeyEvent.KEYCODE_NUMPAD_ENTER||key==KeyEvent.KEYCODE_BUTTON_A;
    }
    boolean declaresHome(){
        try{
            List<ResolveInfo> list=getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(getPackageName()),0);
            return list!=null&&!list.isEmpty();
        }catch(Exception ignored){
            return false;
        }
    }
    int defaultHomeState(){
        boolean declares=declaresHome();
        String resolvedPkg="",resolvedCls="";
        try{
            ResolveInfo r=getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addCategory(Intent.CATEGORY_DEFAULT),
                    PackageManager.MATCH_DEFAULT_ONLY);
            if(r!=null&&r.activityInfo!=null){
                resolvedPkg=r.activityInfo.packageName;
                resolvedCls=r.activityInfo.name;
            }
        }catch(Exception ignored){}
        Intent current=getIntent();
        boolean fromHome=current!=null&&Intent.ACTION_MAIN.equals(current.getAction())&&current.hasCategory(Intent.CATEGORY_HOME);
        return LiteSettings.homeState(declares,getPackageName(),resolvedPkg,resolvedCls,fromHome);
    }
    boolean isDefaultHome(){
        return LiteSettings.isDefaultHomeState(defaultHomeState());
    }
    void launch(App app){
        try{
            startActivity(app.intent);
            prefs.edit().putLong("opened:"+app.id,System.currentTimeMillis()).apply();
        }catch(Exception e){
            toast("系统未允许打开该应用，请尝试原厂桌面");
        }
    }
    void systemSettings(){
        try{
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }catch(Exception e){
            toast("此设备未提供系统设置入口");
        }
    }
    void openHomeSettings(){
        if(!declaresHome()){
            toast("受限版不含默认桌面入口，请直接从应用列表启动");
            return;
        }
        try{
            startActivity(new Intent("android.settings.HOME_SETTINGS"));
        }catch(Exception e){
            toast("此系统未提供默认桌面设置页，已打开系统设置");
            systemSettings();
        }
    }
    void pickImage(int requestCode,String title){
        try{
            Intent intent=new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(intent,title),requestCode);
        }catch(Exception e){
            toast("此设备未提供系统图片选择器");
        }
    }
    void pickWallpaper(){
        pickImage(REQ_PICK_WALLPAPER,"选择本地壁纸");
    }
    void pickCardCover(String appId){
        pendingCoverAppId=appId;
        pickImage(REQ_PICK_COVER,"选择卡片封面图片");
    }
    Bitmap decodeBoundedImage(Uri uri,int maxW,int maxH)throws Exception{
        BitmapFactory.Options bounds=new BitmapFactory.Options();
        bounds.inJustDecodeBounds=true;
        InputStream in1=getContentResolver().openInputStream(uri);
        if(in1==null)return null;
        try{
            BitmapFactory.decodeStream(in1,null,bounds);
        }finally{
            in1.close();
        }
        if(bounds.outWidth<=0||bounds.outHeight<=0||(long)bounds.outWidth*bounds.outHeight>100000000L)return null;
        int sample=1;
        while(bounds.outWidth/sample>maxW||bounds.outHeight/sample>maxH)sample*=2;
        BitmapFactory.Options opts=new BitmapFactory.Options();
        opts.inSampleSize=sample;
        opts.inPreferredConfig=Bitmap.Config.RGB_565;
        InputStream in2=getContentResolver().openInputStream(uri);
        if(in2==null)return null;
        try{
            return BitmapFactory.decodeStream(in2,null,opts);
        }finally{
            in2.close();
        }
    }
    ArrayList<String[]> customWallpapers(){
        ArrayList<String[]> list=new ArrayList<String[]>();
        String raw=prefs.getString("custom_wallpapers","");
        boolean hasLegacy=false;
        if(raw!=null&&raw.length()>0){
            String[] entries=raw.split(";");
            for(String e:entries){
                int bar=e.indexOf('|');
                if(bar<=0)continue;
                String name=e.substring(0,bar).trim();
                String title=e.substring(bar+1).trim();
                if(name.length()==0||name.indexOf('/')>=0||name.indexOf('\\')>=0||name.indexOf("..")>=0)continue;
                if(title.length()==0)title="本地图片";
                File f="custom-wallpaper.jpg".equals(name)?new File(getFilesDir(),name):new File(new File(getFilesDir(),"wallpapers"),name);
                if(f.isFile()){
                    if("custom-wallpaper.jpg".equals(name))hasLegacy=true;
                    list.add(new String[]{name,title});
                }
            }
        }
        File legacy=new File(getFilesDir(),"custom-wallpaper.jpg");
        if(!hasLegacy&&legacy.isFile())list.add(new String[]{"custom-wallpaper.jpg","本地图片"});
        return list;
    }
    void saveCustomWallpaperList(List<String[]> items){
        StringBuilder sb=new StringBuilder();
        for(int i=0;i<items.size();i++){
            if(i>0)sb.append(';');
            sb.append(items.get(i)[0]).append('|').append(items.get(i)[1].replace('|',' ').replace(';',' '));
        }
        prefs.edit().putString("custom_wallpapers",sb.toString()).apply();
    }
    void deleteCustomWallpaper(String fileName){
        if(fileName==null||fileName.length()==0)return;
        File f="custom-wallpaper.jpg".equals(fileName)?new File(getFilesDir(),fileName):new File(new File(getFilesDir(),"wallpapers"),fileName);
        if(f.exists())f.delete();
        ArrayList<String[]> list=customWallpapers();
        ArrayList<String[]> next=new ArrayList<String[]>();
        for(String[] entry:list)if(!fileName.equals(entry[0]))next.add(entry);
        saveCustomWallpaperList(next);
        String active=prefs.getString("active_custom_wall","");
        if(fileName.equals(active)||next.isEmpty()){
            if(next.isEmpty()){
                prefs.edit().remove("active_custom_wall").putInt("wallpaper",0).apply();
            }else{
                prefs.edit().putString("active_custom_wall",next.get(0)[0]).putInt("wallpaper",2).apply();
            }
        }
        home.loadWallpaper();
        toast("已移除壁纸");
    }
    void probeWeatherAsync(final boolean notifyUser){
        weatherLoaded=true;
        if(notifyUser)toast("正在探测本机系统天气...");
        new Thread(new Runnable(){
            public void run(){
                final LocalWeatherProbe.Result res=LocalWeatherProbe.probe(LiteActivity.this);
                handler.post(new Runnable(){
                    public void run(){
                        if(destroyed||isFinishing())return;
                        lastWeatherProbe=res;
                        localWeather=res.snapshot;
                        home.invalidate();
                        if(notifyUser){
                            toast(res.snapshot!=null?"已读取本机天气数据":"未读到开放的本机天气接口");
                        }
                    }
                });
            }
        },"MarcoLite-weather").start();
    }
    void scanManualActivities(){
        if(manualScanning)return;
        manualScanning=true;
        home.invalidate();
        new Thread(new Runnable(){
            public void run(){
                final ArrayList<String[]> found=new ArrayList<String[]>();
                try{
                    PackageManager pm=getPackageManager();
                    HashSet<String> launcherPkgs=new HashSet<String>();
                    for(String cat:new String[]{"android.intent.category.LEANBACK_LAUNCHER",Intent.CATEGORY_LAUNCHER}){
                        List<ResolveInfo> rs=pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat),0);
                        if(rs!=null)for(ResolveInfo r:rs)if(r.activityInfo!=null)launcherPkgs.add(r.activityInfo.packageName);
                    }
                    List<PackageInfo> pkgs=pm.getInstalledPackages(PackageManager.GET_ACTIVITIES);
                    if(pkgs!=null){
                        for(PackageInfo pi:pkgs){
                            if(pi==null||pi.packageName==null||pi.packageName.equals(getPackageName())||launcherPkgs.contains(pi.packageName))continue;
                            if(pi.activities==null||pi.applicationInfo==null||!pi.applicationInfo.enabled)continue;
                            ActivityInfo chosen=null;
                            for(ActivityInfo ai:pi.activities){
                                if(ai!=null&&ai.enabled&&ai.exported&&(ai.permission==null||ai.permission.length()==0)){
                                    chosen=ai;
                                    break;
                                }
                            }
                            if(chosen==null)continue;
                            CharSequence pl=pm.getApplicationLabel(pi.applicationInfo);
                            String label=(pl!=null&&pl.length()>0)?pl.toString():pi.packageName;
                            found.add(new String[]{pi.packageName,new ComponentName(pi.packageName,chosen.name).flattenToString(),label});
                        }
                    }
                    Collections.sort(found,new Comparator<String[]>(){
                        public int compare(String[] a,String[] b){
                            return a[2].compareToIgnoreCase(b[2]);
                        }
                    });
                }catch(Throwable ignored){}
                handler.post(new Runnable(){
                    public void run(){
                        manualScanning=false;
                        if(destroyed||isFinishing())return;
                        manualActivities=found;
                        home.choice=Math.max(0,Math.min(Math.max(0,home.count()-1),home.choice));
                        home.invalidate();
                    }
                });
            }
        },"MarcoLite-entries").start();
    }
    void importWallpaper(final Uri uri){
        toast("正在导入本地壁纸…");
        new Thread(new Runnable(){
            public void run(){
                String savedFile=null;
                Bitmap decoded=null;
                try{
                    decoded=decodeBoundedImage(uri,1280,720);
                    if(decoded!=null){
                        File dir=new File(getFilesDir(),"wallpapers");
                        dir.mkdirs();
                        String fileName="wall-"+System.currentTimeMillis()+".jpg";
                        File target=new File(dir,fileName);
                        File temp=new File(dir,fileName+".tmp");
                        FileOutputStream out=new FileOutputStream(temp);
                        try{
                            decoded.compress(Bitmap.CompressFormat.JPEG,86,out);
                            out.getFD().sync();
                        }finally{
                            out.close();
                        }
                        if(temp.renameTo(target))savedFile=fileName;
                    }
                }catch(Exception ignored){
                }finally{
                    if(decoded!=null&&!decoded.isRecycled())decoded.recycle();
                }
                final String finalFile=savedFile;
                handler.post(new Runnable(){
                    public void run(){
                        if(destroyed||isFinishing())return;
                        if(finalFile!=null){
                            ArrayList<String[]> list=customWallpapers();
                            String label="本地壁纸 "+(list.size()+1);
                            list.add(0,new String[]{finalFile,label});
                            while(list.size()>12){
                                String[] removed=list.remove(list.size()-1);
                                File rf="custom-wallpaper.jpg".equals(removed[0])?new File(getFilesDir(),removed[0]):new File(new File(getFilesDir(),"wallpapers"),removed[0]);
                                if(rf.exists())rf.delete();
                            }
                            saveCustomWallpaperList(list);
                            prefs.edit().putInt("wallpaper",2).putString("active_custom_wall",finalFile).apply();
                            home.loadWallpaper();
                            toast("本地壁纸已保存到壁纸库");
                        }else{
                            toast("图片读取失败，请确认格式为 JPG/PNG");
                        }
                    }
                });
            }
        },"MarcoLite-wall").start();
    }
    void importCardCover(final String appId,final Uri uri){
        toast("正在导入卡片封面…");
        new Thread(new Runnable(){
            public void run(){
                String savedName=null;
                Bitmap decoded=null;
                try{
                    decoded=decodeBoundedImage(uri,480,270);
                    if(decoded!=null){
                        File dir=new File(getFilesDir(),"covers");
                        dir.mkdirs();
                        String fileName="cover-"+Integer.toHexString(appId.hashCode())+".jpg";
                        File target=new File(dir,fileName);
                        File temp=new File(dir,fileName+".tmp");
                        FileOutputStream out=new FileOutputStream(temp);
                        try{
                            decoded.compress(Bitmap.CompressFormat.JPEG,84,out);
                            out.getFD().sync();
                        }finally{
                            out.close();
                        }
                        if(temp.renameTo(target))savedName=fileName;
                    }
                }catch(Exception ignored){
                }finally{
                    if(decoded!=null&&!decoded.isRecycled())decoded.recycle();
                }
                final String name=savedName;
                handler.post(new Runnable(){
                    public void run(){
                        if(destroyed||isFinishing())return;
                        if(name!=null){
                            prefs.edit().putString("cover:"+appId,"image:"+name).apply();
                            home.evictCover(name);
                            rebuildVisible();
                            toast("自定义卡片封面已应用");
                        }else{
                            toast("封面图片读取失败，请确认格式为 JPG/PNG");
                        }
                    }
                });
            }
        },"MarcoLite-cover").start();
    }
    void openAppInfo(App a){
        try{
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+a.id)));
        }catch(Exception e){
            systemSettings();
        }
    }
    void uninstallApp(App a){
        if(!a.canUninstall()){
            toast("系统内置应用无法直接卸载，已打开系统应用信息");
            openAppInfo(a);
            return;
        }
        try{
            dirty=true;
            startActivity(new Intent(Intent.ACTION_DELETE,Uri.parse("package:"+a.id)));
        }catch(Exception e){
            toast("无法调起系统卸载界面，已打开系统应用信息");
            openAppInfo(a);
        }
    }
    void renameApp(final App a){
        final EditText input=new EditText(this);
        input.setSingleLine(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        input.setText(a.label);
        input.setSelection(a.label.length());
        new AlertDialog.Builder(this).setTitle("重命名 · "+a.origLabel).setView(input)
                .setPositiveButton("保存",new DialogInterface.OnClickListener(){
                    public void onClick(DialogInterface d,int w){
                        String next=input.getText().toString().trim();
                        SharedPreferences.Editor e=prefs.edit();
                        if(next.length()==0||next.equals(a.origLabel))e.remove("label:"+a.id);
                        else e.putString("label:"+a.id,next);
                        e.apply();
                        rebuildVisible();
                        toast(next.length()==0||next.equals(a.origLabel)?"已恢复原名":"已重命名为 "+next);
                    }
                })
                .setNeutralButton("恢复原名",new DialogInterface.OnClickListener(){
                    public void onClick(DialogInterface d,int w){
                        prefs.edit().remove("label:"+a.id).apply();
                        rebuildVisible();
                        toast("已恢复原名");
                    }
                })
                .setNegativeButton("取消",null)
                .show();
    }
    void searchAllAppsDialog(){
        final EditText input=new EditText(this);
        input.setSingleLine(true);
        input.setHint("输入应用名称或包名关键词");
        input.setText(home.allQuery);
        input.setSelection(home.allQuery.length());
        new AlertDialog.Builder(this).setTitle("搜索全部应用").setView(input)
                .setPositiveButton("搜索",new DialogInterface.OnClickListener(){
                    public void onClick(DialogInterface d,int w){
                        home.allQuery=input.getText().toString().trim();
                        home.choice=0;
                        rebuildFilteredApps();
                        home.invalidate();
                    }
                })
                .setNeutralButton("清除搜索",new DialogInterface.OnClickListener(){
                    public void onClick(DialogInterface d,int w){
                        home.allQuery="";
                        home.choice=0;
                        rebuildFilteredApps();
                        home.invalidate();
                    }
                })
                .setNegativeButton("取消",null)
                .show();
    }
    void persistRowOrder(List<App> rowList,boolean isFavorites,boolean pinnedGroup){
        SharedPreferences.Editor e=prefs.edit();
        if(isFavorites){
            StringBuilder sb=new StringBuilder();
            for(int i=0;i<rowList.size();i++){
                if(i>0)sb.append(',');
                sb.append(rowList.get(i).id);
            }
            e.putString("fav_order",sb.toString());
        }else if(pinnedGroup){
            long base=prefs.getLong("pin_seq",100)+rowList.size()+2;
            for(int i=0;i<rowList.size();i++){
                App item=rowList.get(i);
                if(item.pinned)e.putLong("pin_order:"+item.id,base-i);
            }
            e.putLong("pin_seq",base+1);
        }else{
            StringBuilder sb=new StringBuilder();
            for(int i=0;i<rowList.size();i++){
                if(i>0)sb.append(',');
                sb.append(rowList.get(i).id);
            }
            e.putString("order_list",sb.toString());
            e.putInt("sort_mode",2);
            e.putBoolean("alphabetic",false);
        }
        e.apply();
    }
    void moveApp(App a,int dir){
        boolean isFav=home.activeRow==1&&!favorites.isEmpty();
        ArrayList<App> list=isFav?favorites:apps;
        int idx=-1;
        for(int i=0;i<list.size();i++)if(list.get(i).id.equals(a.id)){
            idx=i;
            break;
        }
        if(idx<0){
            toast("请先取消隐藏再调整位置");
            return;
        }
        int target=idx+dir;
        if(target<0||target>=list.size()||(!isFav&&list.get(target).pinned!=a.pinned)){
            toast(dir<0?"已在同组最左侧":"已在同组最右侧");
            return;
        }
        ArrayList<App> reordered=new ArrayList<App>(list);
        Collections.swap(reordered,idx,target);
        persistRowOrder(reordered,isFav,!isFav&&a.pinned);
        restoredSelection=a.id;
        rebuildVisible();
        toast(dir<0?"已向左移动":"已向右移动");
    }
    void saveCustomCardSize(App a,int w,int h,int d,int r){
        String fmt=LiteSettings.formatCardSize(w,h,d,r);
        SharedPreferences.Editor e=prefs.edit();
        if(fmt.length()==0)e.remove("size:"+a.id);
        else e.putString("size:"+a.id,fmt);
        e.apply();
        rebuildVisible();
    }
    void setCardCover(App a,String coverValue){
        String oldFile=LiteSettings.coverFileName(a.cover);
        SharedPreferences.Editor e=prefs.edit();
        if(coverValue==null||coverValue.length()==0)e.remove("cover:"+a.id);
        else e.putString("cover:"+a.id,coverValue);
        e.apply();
        if(oldFile!=null&&!oldFile.equals(LiteSettings.coverFileName(coverValue))){
            home.evictCover(oldFile);
            new File(new File(getFilesDir(),"covers"),oldFile).delete();
        }
        rebuildVisible();
    }
    void resetCardAppearance(App a){
        String oldFile=LiteSettings.coverFileName(a.cover);
        prefs.edit().remove("label:"+a.id).remove("cover:"+a.id).remove("size:"+a.id).apply();
        if(oldFile!=null){
            home.evictCover(oldFile);
            new File(new File(getFilesDir(),"covers"),oldFile).delete();
        }
        rebuildVisible();
        toast("已恢复卡片默认外观");
    }
    void licenses(){
        StringBuilder text=new StringBuilder("Marco TV Lite "+LiteSettings.VERSION+" · Pilipala\n免费开源 · GPL-3.0-only\n\n本软件免费开源。如果你付费购买了本软件，请尽快向购买平台申请退款。\n\n");
        try{
            for(String name:getAssets().list("licenses")){
                text.append("\n—— ").append(name).append(" ——\n");
                BufferedReader reader=new BufferedReader(new InputStreamReader(getAssets().open("licenses/"+name),"UTF-8"));
                try{
                    String line;
                    while((line=reader.readLine())!=null)text.append(line).append('\n');
                }finally{
                    reader.close();
                }
            }
        }catch(Exception e){
            text.append("许可文件读取失败，请查看随包源码 LICENSE 与 licenses 目录。");
        }
        android.widget.TextView body=new android.widget.TextView(this);
        body.setText(text);
        body.setTextSize(16);
        body.setTextColor(Color.WHITE);
        body.setPadding(24,24,24,24);
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);
        scroll.setBackgroundColor(0xFF192C3B);
        scroll.addView(body);
        scroll.setFocusable(true);
        new AlertDialog.Builder(this).setTitle("开源许可").setView(scroll).setPositiveButton("返回",null)
                .show();
    }
    void backup(boolean restore){
        File file=new File(getFilesDir(),"lite-backup.json");
        try{
            if(!restore){
                JSONObject o=new JSONObject();
                o.put("format","MarcoTV-Lite");
                o.put("schema",1);
                JSONObject values=new JSONObject();
                for(int i=0;i<LiteSettings.KEYS.length;i++)values.put(LiteSettings.KEYS[i],readParam(prefs,i));
                int wall=prefs.getInt("wallpaper",0);
                values.put("wallpaper",wall==2?0:Math.max(0,Math.min(5,wall)));
                o.put("appearance",values);
                File temp=new File(getFilesDir(),"lite-backup.tmp");
                FileOutputStream out=new FileOutputStream(temp);
                try{
                    out.write(o.toString().getBytes("UTF-8"));
                    out.getFD().sync();
                }finally{
                    out.close();
                }
                if(!temp.renameTo(file))throw new IOException("rename");
                toast("外观备份已保存在本机；卸载会删除");
            }else{
                if(!file.exists()){
                    toast("还没有本机外观备份");
                    return;
                }
                if(file.length()>16384)throw new IOException("size");
                FileInputStream in=new FileInputStream(file);
                ByteArrayOutputStream data=new ByteArrayOutputStream();
                try{
                    byte[] b=new byte[1024];
                    int n;
                    while((n=in.read(b))!=-1){
                        data.write(b,0,n);
                        if(data.size()>16384)throw new IOException("size");
                    }
                }finally{
                    in.close();
                }
                JSONObject o=new JSONObject(data.toString("UTF-8"));
                if(!"MarcoTV-Lite".equals(o.getString("format"))||o.getInt("schema")!=1)throw new IOException("format");
                JSONObject values=o.getJSONObject("appearance");
                int[] checked=new int[LiteSettings.KEYS.length];
                for(int i=0;i<checked.length;i++){
                    if(!values.has(LiteSettings.KEYS[i])){
                        if(i<LiteSettings.CORE_COUNT)throw new IOException("missing");
                        checked[i]=LiteSettings.DEFAULT[i];
                        continue;
                    }
                    Object raw=values.get(LiteSettings.KEYS[i]);
                    if(!(raw instanceof Integer))throw new IOException("type");
                    checked[i]=(Integer)raw;
                    if(checked[i]!=LiteSettings.clamp(i,checked[i]))throw new IOException("range");
                }
                int wall=values.getInt("wallpaper");
                if(wall<0||wall>5||wall==2)throw new IOException("wallpaper");
                SharedPreferences.Editor edit=prefs.edit();
                for(int i=0;i<checked.length;i++)writeParam(edit,i,checked[i]);
                int[] previous=new int[checked.length];
                for(int i=0;i<previous.length;i++)previous[i]=readParam(prefs,i);
                int oldWallpaper=prefs.getInt("wallpaper",0);
                if(!edit.putInt("wallpaper",wall).commit()){
                    SharedPreferences.Editor rollback=prefs.edit();
                    for(int i=0;i<previous.length;i++)writeParam(rollback,i,previous[i]);
                    rollback.putInt("wallpaper",oldWallpaper).commit();
                    restoreStatus="write_failed";
                    toast("存储写入失败，恢复未完成。请检查可用空间后重试");
                    return;
                }
                restoreStatus="success";
                home.loadWallpaper();
                home.invalidate();
                toast("本机外观已恢复");
            }
        }catch(Exception e){
            toast("备份或恢复未完成，请检查文件和可用空间后重试");
        }
    }
    void appAction(int item){
        App a=findApp(home.managedId);
        if(a==null){
            home.backPage();
            return;
        }
        if(item==0){
            SharedPreferences.Editor e=prefs.edit();
            if(a.pinned&&isFirstPinned(a)){
                e.putBoolean("pinned:"+a.id,false).remove("pin_order:"+a.id).apply();
                toast("已取消置顶");
            }else{
                long seq=prefs.getLong("pin_seq",0)+1;
                e.putBoolean("pinned:"+a.id,true).putLong("pin_order:"+a.id,seq).putLong("pin_seq",seq).apply();
                restoredSelection=a.id;
                toast("已置顶到首位");
            }
            rebuildVisible();
            home.backPage();
        }else if(item==1){
            boolean nextHidden=!a.hidden;
            prefs.edit().putBoolean("hidden:"+a.id,nextHidden).apply();
            rebuildVisible();
            toast(nextHidden?"已隐藏应用，可在设置或全部应用中恢复":"已恢复显示");
            home.backPage();
        }else if(item==2){
            uninstallApp(a);
            home.backPage();
        }else if(item==3){
            home.backPage();
            renameApp(a);
        }else if(item==4){
            moveApp(a,-1);
            home.invalidate();
        }else if(item==5){
            moveApp(a,1);
            home.invalidate();
        }else if(item==6){
            home.open(7);
        }else if(item==7){
            home.open(8);
        }else if(item==8){
            boolean nextFav=!a.favorite;
            prefs.edit().putBoolean("fav:"+a.id,nextFav).apply();
            rebuildVisible();
            toast(nextFav?"已加入收藏（首页按↓切换收藏行）":"已取消收藏");
            home.backPage();
        }else if(item==9){
            home.beginMove(a);
        }else if(item==10){
            resetCardAppearance(a);
            home.backPage();
        }else if(item==11){
            openAppInfo(a);
            home.backPage();
        }else if(item==12){
            home.closePage();
            launch(a);
        }else{
            home.backPage();
        }
    }

    final class LiteView extends View {
        final LiteActivity host;
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        final RectF rect=new RectF();
        final Path path=new Path();
        final Matrix shaderMatrix=new Matrix();
        final ClockScene clock;
        final MotionSpring scroll=new MotionSpring(0,18,.08f),demo=new MotionSpring(0,12,.08f);
        final MotionSpring rowSpring=new MotionSpring(0,14,.005f);
        final MotionSpring[] headerFocus={new MotionSpring(0,20,.001f),new MotionSpring(0,20,.001f),new MotionSpring(0,20,.001f)};
        Bitmap wallpaper;
        int wallpaperKind=0;
        Shader procWallBase,procWallGlow;
        int procWallCachedId=-1;
        PreviewPreferences draft;
        final Typeface normalFace=Typeface.create("sans-serif",Typeface.NORMAL),boldFace=Typeface.create("sans-serif",Typeface.BOLD);
        final HashMap<String,String> shortLabels=new HashMap<String,String>();
        final LinkedHashMap<String,Bitmap> coverCache=new LinkedHashMap<String,Bitmap>(16,0.75f,true){
            @Override protected boolean removeEldestEntry(Map.Entry<String,Bitmap> eldest){
                if(size()>16){
                    Bitmap b=eldest.getValue();
                    if(b!=null&&!b.isRecycled())b.recycle();
                    return true;
                }
                return false;
            }
        };
        final HashMap<String,BitmapShader> coverShaders=new HashMap<String,BitmapShader>();
        final ArrayDeque<int[]> history=new ArrayDeque<int[]>();
        int drawCount,tickCount,demoDirection=1;
        long lastMinute=-1,nextTickDelay,ambientEnteredAt=0;
        String managedId,movingId,allQuery="";
        ArrayList<App> moveBackup;
        int allFilter=0,activeRow=0;
        Shader cardFill,focusFill;
        Shader[] colorCoverFills=new Shader[LiteSettings.COVER_COLORS.length];
        float fillY=-1,fillHeight=-1;
        float[] cardWidths=new float[0],cardCenters=new float[0];
        int selected,top=0,page=0,choice=0;
        boolean topFocus=false,ambient=false,running=false,longPress=false,wakeKey=false,keepScreenOn=false,edgeOpen=false;
        int edgeChoice=0;
        float wallTopLeftLum=0.16f,wallTopRightLum=0.16f,wallShelfLum=0.16f;
        long touched,lastFrame;
        float ambientAmount=0;
        final MotionSpring saver=new MotionSpring(0,10,.001f);
        final String[] menu={"Magic Desktop","切换壁纸","应用排序","恢复所有隐藏应用","系统设置 / 网络","保存本机外观备份","恢复本机外观备份",
                "设备与兼容状态","关于与开源许可","刷新应用列表","全部应用与管理","导入本地壁纸","设为默认桌面","媒体音量","屏保休眠定时","立即进入屏保",
                "我的壁纸库","桌面天气探测","手动添加应用入口"};
        final String[] filterNames={"全部","可卸载","收藏","已隐藏","系统"};
        final Runnable frame=new Runnable(){
            public void run(){
                if(!running)return;
                long now=SystemClock.uptimeMillis();
                float dt=Math.min(.06f,(now-lastFrame)/1000f);
                lastFrame=now;
                boolean moving=scroll.step(dt)|saver.step(dt)|demo.step(dt)|rowSpring.step(dt);
                for(int i=0;i<headerFocus.length;i++)moving|=headerFocus[i].step(dt);
                ambientAmount=saver.value;
                invalidate();
                if(moving)postDelayed(this,16);
            }
        };
        final Runnable tick=new Runnable(){
            public void run(){
                if(!running)return;
                tickCount++;
                long now=SystemClock.uptimeMillis(),minute=System.currentTimeMillis()/60000;
                boolean changed=minute!=lastMinute;
                lastMinute=minute;
                int seconds=prefs.getInt("idle_seconds",60);
                if(page==0&&movingId==null&&!ambient&&seconds>0&&now-touched>=seconds*1000L){
                    enterAmbient();
                    changed=true;
                }
                updateKeepScreenOn();
                if(changed)invalidate();
                scheduleTick();
            }
        };
        LiteView(LiteActivity h){
            super(h);
            host=h;
            clock=new ClockScene(h);
            setFocusable(true);
            setFocusableInTouchMode(true);
            requestFocus();
            loadWallpaper();
            wake();
        }
        ArrayList<App> activeRowApps(){
            if(activeRow==1&&!favorites.isEmpty())return favorites;
            if(activeRow==2&&!tvInputs.isEmpty())return tvInputs;
            return apps;
        }
        void enterAmbient(){
            ambient=true;
            ambientEnteredAt=SystemClock.uptimeMillis();
            saver.target(1);
            updateKeepScreenOn();
            scheduleMotion();
        }
        void updateKeepScreenOn(){
            boolean want=running;
            int mins=host.sleepMinutes();
            if(want&&ambient&&mins>0&&ambientEnteredAt>0){
                if(SystemClock.uptimeMillis()-ambientEnteredAt>=mins*60000L)want=false;
            }
            if(want!=keepScreenOn){
                keepScreenOn=want;
                Window w=host.getWindow();
                if(w!=null){
                    if(want)w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            }
        }
        void evictCover(String fileName){
            Bitmap old=coverCache.remove(fileName);
            coverShaders.remove(fileName);
            if(old!=null&&!old.isRecycled())old.recycle();
        }
        Bitmap loadCoverBitmap(String fileName){
            if(fileName==null)return null;
            Bitmap cached=coverCache.get(fileName);
            if(cached!=null&&!cached.isRecycled())return cached;
            File f=new File(new File(host.getFilesDir(),"covers"),fileName);
            if(!f.isFile())return null;
            try{
                BitmapFactory.Options o=new BitmapFactory.Options();
                o.inPreferredConfig=Bitmap.Config.RGB_565;
                Bitmap b=BitmapFactory.decodeFile(f.getAbsolutePath(),o);
                if(b!=null){
                    coverCache.put(fileName,b);
                    coverShaders.put(fileName,new BitmapShader(b,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP));
                }
                return b;
            }catch(Exception ignored){
                return null;
            }
        }
        void beginMove(App a){
            boolean isFav=activeRow==1&&!favorites.isEmpty();
            ArrayList<App> list=isFav?favorites:apps;
            int idx=-1;
            for(int i=0;i<list.size();i++)if(list.get(i).id.equals(a.id)){
                idx=i;
                break;
            }
            if(idx<0&&!favorites.isEmpty()){
                isFav=false;
                activeRow=0;
                rowSpring.snap(0);
                list=apps;
                for(int i=0;i<list.size();i++)if(list.get(i).id.equals(a.id)){
                    idx=i;
                    break;
                }
            }
            if(idx<0){
                toast("请先取消隐藏再移动卡片");
                backPage();
                return;
            }
            closePage();
            topFocus=false;
            selected=idx;
            movingId=a.id;
            moveBackup=new ArrayList<App>(list);
            scroll.target(targetScroll(selected));
            scheduleMotion();
            invalidate();
        }
        void finishMove(){
            if(movingId==null)return;
            boolean isFav=activeRow==1&&!favorites.isEmpty();
            ArrayList<App> list=isFav?favorites:apps;
            App moved=findApp(movingId);
            movingId=null;
            moveBackup=null;
            if(moved!=null){
                persistRowOrder(list,isFav,!isFav&&moved.pinned);
                restoredSelection=moved.id;
                rebuildVisible();
                toast("已保存卡片顺序");
            }
            invalidate();
        }
        void cancelMove(boolean showToast){
            if(movingId==null)return;
            if(moveBackup!=null){
                if(activeRow==1&&!favorites.isEmpty())favorites=new ArrayList<App>(moveBackup);
                else apps=new ArrayList<App>(moveBackup);
            }
            String keep=movingId;
            movingId=null;
            moveBackup=null;
            selectId(keep);
            if(showToast)toast("已取消移动");
            invalidate();
        }
        void syncHeaderTargets(){
            boolean changed=false;
            for(int i=0;i<headerFocus.length;i++){
                float want=(page==0&&movingId==null&&topFocus&&top==i)?1f:0f;
                if(headerFocus[i].target!=want){
                    headerFocus[i].target(want);
                    changed=true;
                }
            }
            if(changed)scheduleMotion();
        }
        void computeCardLayout(SharedPreferences a,List<App> list){
            int n=list.size();
            if(cardWidths.length!=n){
                cardWidths=new float[n];
                cardCenters=new float[n];
            }
            int globalW=a.getInt("card_width",100);
            float cursor=LiteSettings.SHELF_START_X;
            for(int i=0;i<n;i++){
                float w=LiteSettings.cardWidthPx(globalW,list.get(i).customW);
                cardWidths[i]=w;
                cardCenters[i]=cursor+w/2f;
                cursor+=w+LiteSettings.CARD_GAP;
            }
        }
        float targetScroll(int idx){
            computeCardLayout(appearance(),activeRowApps());
            return LiteSettings.scrollForCenters(cardCenters,idx);
        }
        void scheduleTick(){
            removeCallbacks(tick);
            if(!running)return;
            long delay=60000-System.currentTimeMillis()%60000;
            int seconds=prefs.getInt("idle_seconds",60);
            if(page==0&&movingId==null&&!ambient&&seconds>0)delay=Math.min(delay,Math.max(1,touched+seconds*1000L-SystemClock.uptimeMillis()));
            int sleepMin=host.sleepMinutes();
            if(ambient&&sleepMin>0&&ambientEnteredAt>0){
                long rem=ambientEnteredAt+sleepMin*60000L-SystemClock.uptimeMillis();
                if(rem>0)delay=Math.min(delay,rem);
            }
            nextTickDelay=delay;
            postDelayed(tick,delay);
        }
        void resume(){
            running=true;
            wake();
            lastMinute=System.currentTimeMillis()/60000;
            invalidate();
            scheduleTick();
        }
        void pause(){
            running=false;
            removeCallbacks(frame);
            removeCallbacks(tick);
            updateKeepScreenOn();
        }
        void saveState(Bundle out){
            out.putInt("page",page);
            out.putInt("choice",choice);
            out.putInt("top",top);
            out.putInt("activeRow",activeRow);
            out.putInt("allFilter",allFilter);
            out.putString("allQuery",allQuery);
            out.putBoolean("topFocus",topFocus);
            out.putString("selected",selectedId());
            out.putString("managed",managedId);
            int[] parents=new int[history.size()*2];
            int index=0;
            for(int[] item:history){
                parents[index++]=item[0];
                parents[index++]=item[1];
            }
            out.putIntArray("parents",parents);
            if(draft!=null){
                Bundle values=new Bundle();
                for(Map.Entry<String,Integer> item:draft.values.entrySet())values.putInt(item.getKey(),item.getValue());
                for(Map.Entry<String,Boolean> item:draft.booleans.entrySet())values.putInt(item.getKey(),item.getValue()?1:0);
                out.putBundle("draft",values);
            }
        }
        void restoreState(Bundle saved){
            page=Math.max(0,Math.min(11,saved.getInt("page",0)));
            choice=saved.getInt("choice",0);
            top=Math.max(0,Math.min(2,saved.getInt("top",0)));
            activeRow=Math.max(0,Math.min(2,saved.getInt("activeRow",0)));
            allFilter=Math.max(0,Math.min(filterNames.length-1,saved.getInt("allFilter",0)));
            allQuery=saved.getString("allQuery","");
            if(allQuery==null)allQuery="";
            topFocus=saved.getBoolean("topFocus",false);
            restoredSelection=saved.getString("selected");
            managedId=saved.getString("managed");
            history.clear();
            int[] parents=saved.getIntArray("parents");
            if(parents!=null)for(int i=0;i+1<Math.min(12,parents.length);i+=2)history.addLast(new int[]{Math.max(0,Math.min(11,parents[i])),Math.max(0,
                    parents[i+1])});
            if(page==2){
                draft=new PreviewPreferences(prefs);
                Bundle values=saved.getBundle("draft");
                if(values!=null)for(int i=0;i<LiteSettings.KEYS.length;i++)if(values.containsKey(LiteSettings.KEYS[i]))writeDraft(draft,i,
                        values.getInt(LiteSettings.KEYS[i]));
            }
            choice=Math.max(0,Math.min(Math.max(0,count()-1),choice));
            rowSpring.snap(activeRow);
            for(int i=0;i<headerFocus.length;i++)headerFocus[i].snap((page==0&&topFocus&&top==i)?1f:0f);
            invalidate();
        }
        void release(){
            pause();
            closePage();
            clock.release();
            if(wallpaper!=null&&!wallpaper.isRecycled())wallpaper.recycle();
            wallpaper=null;
            for(Bitmap b:coverCache.values())if(b!=null&&!b.isRecycled())b.recycle();
            coverCache.clear();
            coverShaders.clear();
        }
        void wake(){
            touched=SystemClock.uptimeMillis();
            ambient=false;
            ambientEnteredAt=0;
            updateKeepScreenOn();
            if(saver.target!=0||saver.value!=0){
                saver.target(0);
                scheduleMotion();
            }
            scheduleTick();
        }
        void scheduleMotion(){
            if(!running)return;
            removeCallbacks(frame);
            if(appearance().getInt("motion",1)==0){
                scroll.snap(scroll.target);
                saver.snap(saver.target);
                demo.snap(demo.target);
                rowSpring.snap(rowSpring.target);
                for(int i=0;i<headerFocus.length;i++)headerFocus[i].snap(headerFocus[i].target);
                ambientAmount=saver.value;
                invalidate();
                return;
            }
            lastFrame=SystemClock.uptimeMillis();
            post(frame);
        }
        float previewAmbient(){
            return page==2&&((choice>=7&&choice<=9)||choice==13||choice==16)?1:0;
        }
        void previewChanged(){
            if(page==2&&choice==10){
                demoDirection=-demoDirection;
                demo.target(demoDirection*100);
                scheduleMotion();
            }
            invalidate();
        }
        String selectedId(){
            ArrayList<App> list=activeRowApps();
            return list.isEmpty()?null:list.get(Math.min(selected,list.size()-1)).id;
        }
        void selectId(String id){
            ArrayList<App> list=activeRowApps();
            selected=0;
            if(id!=null)for(int i=0;i<list.size();i++)if(list.get(i).id.equals(id)){
                selected=i;
                break;
            }
            scroll.snap(targetScroll(selected));
        }
        void loadWallpaper(){
            int wall=prefs.getInt("wallpaper",0);
            wallpaperKind=wall;
            if(wall>=3&&wall<=5){
                Bitmap old=wallpaper;
                wallpaper=null;
                if(old!=null&&!old.isRecycled())old.recycle();
                wallTopLeftLum=0.14f;
                wallTopRightLum=0.14f;
                wallShelfLum=0.14f;
                clock.setTopRightLuminance(wallTopRightLum);
                invalidate();
                return;
            }
            Bitmap next=null;
            try{
                BitmapFactory.Options o=new BitmapFactory.Options();
                o.inSampleSize=1;
                o.inPreferredConfig=Bitmap.Config.RGB_565;
                if(wall==2){
                    String activeName=prefs.getString("active_custom_wall","");
                    File custom=null;
                    if(activeName!=null&&activeName.length()>0&&activeName.indexOf('/')<0&&activeName.indexOf('\\')<0&&activeName.indexOf("..")<0){
                        File candidate="custom-wallpaper.jpg".equals(activeName)?new File(getFilesDir(),activeName):new File(new File(getFilesDir(),"wallpapers"),activeName);
                        if(candidate.isFile())custom=candidate;
                    }
                    if(custom==null){
                        ArrayList<String[]> list=customWallpapers();
                        if(!list.isEmpty()){
                            String fn=list.get(0)[0];
                            File candidate="custom-wallpaper.jpg".equals(fn)?new File(getFilesDir(),fn):new File(new File(getFilesDir(),"wallpapers"),fn);
                            if(candidate.isFile())custom=candidate;
                        }
                    }
                    if(custom!=null&&custom.isFile()){
                        FileInputStream in=new FileInputStream(custom);
                        try{
                            next=BitmapFactory.decodeStream(in,null,o);
                        }finally{
                            in.close();
                        }
                    }
                }
                if(next==null){
                    InputStream in=getAssets().open("wallpapers/"+(wall==1?"coast.jpg":"aurora.jpg"));
                    try{
                        next=BitmapFactory.decodeStream(in,null,o);
                    }finally{
                        in.close();
                    }
                }
            }catch(Exception ignored){}
            wallTopLeftLum=next!=null?DesktopContrast.sampleTopLeftLuminance(next):0.16f;
            wallTopRightLum=next!=null?DesktopContrast.sampleTopRightLuminance(next):0.16f;
            wallShelfLum=next!=null?DesktopContrast.sampleShelfLuminance(next):0.16f;
            clock.setTopRightLuminance(wallTopRightLum);
            Bitmap old=wallpaper;
            wallpaper=next;
            if(old!=null&&!old.isRecycled())old.recycle();
            invalidate();
        }
        void drawProceduralWallpaper(Canvas c,int id){
            if(procWallCachedId!=id){
                procWallCachedId=id;
                if(id==3){
                    procWallBase=new LinearGradient(0,0,0,1080,new int[]{0xFF0E162B,0xFF282042,0xFF6B3646,0xFF1A1424},new float[]{0f,0.45f,0.78f,1f},Shader.TileMode.CLAMP);
                    procWallGlow=new RadialGradient(1260,720,860,new int[]{0x66F59B6C,0x22D6686C,0x00000000},new float[]{0f,0.48f,1f},Shader.TileMode.CLAMP);
                }else if(id==4){
                    procWallBase=new LinearGradient(0,0,1920,1080,new int[]{0xFF061320,0xFF0B253A,0xFF10394E,0xFF071624},new float[]{0f,0.38f,0.72f,1f},Shader.TileMode.CLAMP);
                    procWallGlow=new RadialGradient(980,340,920,new int[]{0x554ED8C5,0x22298CA8,0x00000000},new float[]{0f,0.5f,1f},Shader.TileMode.CLAMP);
                }else{
                    procWallBase=new LinearGradient(0,0,0,1080,new int[]{0xFF121721,0xFF28242C,0xFF5A4030,0xFF1B1618},new float[]{0f,0.42f,0.76f,1f},Shader.TileMode.CLAMP);
                    procWallGlow=new RadialGradient(640,680,880,new int[]{0x5CE0A25C,0x20A66C3E,0x00000000},new float[]{0f,0.52f,1f},Shader.TileMode.CLAMP);
                }
            }
            p.setShader(procWallBase);
            p.setColor(Color.WHITE);
            c.drawRect(0,0,1920,1080,p);
            p.setShader(procWallGlow);
            c.drawRect(0,0,1920,1080,p);
            p.setShader(null);
        }
        SharedPreferences appearance(){
            return draft==null?prefs:draft;
        }
        void open(int which){
            if(which==page)return;
            edgeOpen=false;
            if(which==1&&page!=0)closePage();
            history.push(new int[]{page,choice});
            wake();
            page=which;
            choice=0;
            if(which==2)draft=new PreviewPreferences(prefs);
            if(which==3&&history.peek()[0]==0)managedId=selectedId();
            if(which==6){
                allFilter=0;
                allQuery="";
                rebuildFilteredApps();
            }
            if(which==11){
                host.scanManualActivities();
            }
            if((which==7||which==8)&&managedId!=null){
                ArrayList<App> list=activeRowApps();
                for(int i=0;i<list.size();i++)if(list.get(i).id.equals(managedId)){
                    selected=i;
                    topFocus=false;
                    scroll.snap(targetScroll(i));
                    break;
                }
            }
            syncHeaderTargets();
            scheduleTick();
            invalidate();
        }
        void closePage(){
            edgeOpen=false;
            draft=null;
            page=0;
            choice=0;
            history.clear();
            demo.snap(0);
            syncHeaderTargets();
            scheduleTick();
            invalidate();
        }
        void backPage(){
            draft=null;
            demo.snap(0);
            if(history.isEmpty()){
                page=0;
                choice=0;
            }else{
                int[] parent=history.pop();
                page=parent[0];
                choice=Math.max(0,Math.min(Math.max(0,count()-1),parent[1]));
            }
            syncHeaderTargets();
            scheduleTick();
            invalidate();
        }
        void box(Canvas c,float l,float t,float r,float b,float radius,int color){
            p.setShader(null);
            p.setColor(color);
            rect.set(l,t,r,b);
            c.drawRoundRect(rect,radius,radius,p);
        }
        void text(Canvas c,String s,float x,float y,float size,int color,boolean bold){
            p.setShader(null);
            p.setColor(color);
            p.setTypeface(bold?boldFace:normalFace);
            p.setTextSize(size);
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText(s,x,y,p);
        }
        String trim(String s,float width,float size){
            if(s==null)return "";
            String key=s+"/"+width+"/"+size;
            String cached=shortLabels.get(key);
            if(cached!=null)return cached;
            p.setTextSize(size);
            p.setTypeface(normalFace);
            String result=s;
            if(p.measureText(s)>width){
                int n=s.length();
                while(n>0&&p.measureText(s.substring(0,n)+"…")>width)n--;
                result=s.substring(0,n)+"…";
            }
            if(shortLabels.size()>256)shortLabels.clear();
            shortLabels.put(key,result);
            return result;
        }
        int blendColor(int a,int b,float t){
            t=Math.max(0f,Math.min(1f,t));
            int aA=(a>>>24)&255,rA=(a>>>16)&255,gA=(a>>>8)&255,bA=a&255;
            int aB=(b>>>24)&255,rB=(b>>>16)&255,gB=(b>>>8)&255,bB=b&255;
            return Color.argb((int)(aA+(aB-aA)*t),(int)(rA+(rB-rA)*t),(int)(gA+(gB-gA)*t),(int)(bA+(bB-bA)*t));
        }
        void drawHeaderIcon(Canvas c,int idx,float cx,float cy,float focus,boolean bright){
            c.save();
            c.translate(cx,cy);
            int stroke=bright?blendColor(0xCC141A24,DesktopContrast.INK_COLOR,focus):blendColor(0xFFD2D8EA,0xFFFFFFFF,focus);
            int accent=bright?blendColor(0xBB6B46C1,0xFF6B46C1,focus):blendColor(0x88C4A8FF,0xFFD5BEFF,focus);
            if(idx==0){
                // Official MarcoTV 3-layer slanted Purple Stacked Cards brand mark
                float spread=2.2f*focus;
                float s=1f+0.07f*focus;
                c.scale(s,s);
                c.rotate(-14f+3f*focus);
                p.setStyle(Paint.Style.FILL);
                p.setColor(blendColor(0xB49E7DF3,0xFF9E7DF3,focus));
                rect.set(-5.8f+spread*0.8f,3.2f+spread,13.6f+spread*0.8f,11.8f+spread);
                c.drawRoundRect(rect,3.2f,3.2f,p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.2f);
                p.setColor(bright?0x88141A24:0x88E5D2FF);
                c.drawRoundRect(rect,3.2f,3.2f,p);
                p.setStyle(Paint.Style.FILL);
                p.setColor(blendColor(0xD0C7A9FF,0xEED9C4FF,focus));
                rect.set(-10.4f+spread*0.25f,-2.2f+spread*0.3f,12.4f+spread*0.25f,7.0f+spread*0.3f);
                c.drawRoundRect(rect,3.6f,3.6f,p);
                p.setStyle(Paint.Style.STROKE);
                p.setColor(bright?0x88141A24:0xB8FFF8FF);
                c.drawRoundRect(rect,3.6f,3.6f,p);
                p.setStyle(Paint.Style.FILL);
                p.setColor(blendColor(0xF2FCF7FE,Color.WHITE,focus));
                rect.set(-14.8f-spread*0.6f,-11.0f-spread*0.85f,10.2f-spread*0.6f,1.0f-spread*0.85f);
                c.drawRoundRect(rect,4.2f,4.2f,p);
                if(bright){
                    p.setStyle(Paint.Style.STROKE);
                    p.setColor(0x55141A24);
                    c.drawRoundRect(rect,4.2f,4.2f,p);
                }
            }else if(idx==1){
                float spread=3.5f*focus;
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.argb((int)(42+58*focus),182,148,246));
                rect.set(-14f-spread,-9f-spread*0.5f,4f-spread,10f-spread*0.5f);
                c.drawRoundRect(rect,4f,4f,p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.2f);
                p.setColor(stroke);
                c.drawRoundRect(rect,4f,4f,p);
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.argb((int)(58+78*focus),218,196,255));
                rect.set(-4f+spread,-4f+spread*0.5f,14f+spread,14f+spread*0.5f);
                c.drawRoundRect(rect,4f,4f,p);
                p.setStyle(Paint.Style.STROKE);
                p.setColor(stroke);
                c.drawRoundRect(rect,4f,4f,p);
                c.save();
                c.translate(10f+spread*0.5f,-10f-spread*0.5f);
                c.rotate(90f*focus);
                float sr=5.5f+2.5f*focus;
                path.reset();
                path.moveTo(0,-sr);
                path.lineTo(sr*0.28f,-sr*0.28f);
                path.lineTo(sr,0);
                path.lineTo(sr*0.28f,sr*0.28f);
                path.lineTo(0,sr);
                path.lineTo(-sr*0.28f,sr*0.28f);
                path.lineTo(-sr,0);
                path.lineTo(-sr*0.28f,-sr*0.28f);
                path.close();
                p.setStyle(Paint.Style.FILL);
                p.setColor(accent);
                c.drawPath(path,p);
                c.restore();
            }else{
                float gap=9.5f+1.8f*focus;
                float tile=3.1f+0.35f*focus;
                p.setStyle(Paint.Style.FILL);
                for(int r=-1;r<=1;r++){
                    for(int col=-1;col<=1;col++){
                        boolean center=(r==0&&col==0);
                        p.setColor(center?accent:stroke);
                        float tx=col*gap,ty=r*gap;
                        rect.set(tx-tile,ty-tile,tx+tile,ty+tile);
                        c.drawRoundRect(rect,1.6f,1.6f,p);
                    }
                }
            }
            p.setStyle(Paint.Style.FILL);
            c.restore();
        }
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            drawCount++;
            canvas.drawColor(0xFF08121D);
            float scale=Math.min(getWidth()/1920f,getHeight()/1080f);
            canvas.save();
            canvas.translate((getWidth()-1920*scale)/2,(getHeight()-1080*scale)/2);
            canvas.scale(scale,scale);
            if(page==2||page==7||page==8){
                box(canvas,30,170,1250,870,30,0xFF243743);
                canvas.save();
                canvas.translate(40,180);
                canvas.scale(.625f,.625f);
                scene(canvas,page==2?previewAmbient():0f);
                canvas.restore();
                String previewCap=page==7?"卡片尺寸实时预览":page==8?"卡片封面实时预览":(previewAmbient()>0?"屏保预览":"桌面预览");
                text(canvas,previewCap,40,930,28,0xFFB7DCE3,true);
                panel(canvas);
            }else{
                scene(canvas,ambientAmount);
                if(page>0)panel(canvas);
            }
            canvas.restore();
        }
        void scene(Canvas c,float idle){
            c.save();
            c.clipRect(0,0,1920,1080);
            SharedPreferences a=appearance();
            if(wallpaperKind>=3&&wallpaperKind<=5){
                drawProceduralWallpaper(c,wallpaperKind);
            }else if(wallpaper!=null){
                rect.set(0,0,1920,1080);
                p.setAlpha(255);
                c.drawBitmap(wallpaper,null,rect,p);
            }else c.drawColor(0xFF122B40);
            p.setColor(Color.argb(ClockScene.dim(a,idle),0,0,0));
            c.drawRect(0,0,1920,1080,p);
            clock.setTopRightLuminance(wallTopRightLum);
            clock.setWeather(host.localWeather,page==2&&choice>=22&&choice<=28);
            clock.draw(c,a,idle,true);
            if(idle>.8f){
                c.restore();
                return;
            }
            int dimPct=a.getInt("wallpaper_dim",10);
            boolean brightHeader=DesktopContrast.isBright(DesktopContrast.effectiveLuminance(wallTopLeftLum,dimPct));
            boolean brightShelf=DesktopContrast.isBright(DesktopContrast.effectiveLuminance(wallShelfLum,dimPct));
            float[] btnL={100,368,676},btnR={340,648,892};
            String[] btnLabels={"MARCO TV","Magic Desktop","全部应用"};
            for(int i=0;i<3;i++){
                float f=headerFocus[i].value;
                if(f>0.01f){
                    box(c,btnL[i],72,btnR[i],144,20,brightHeader?Color.argb((int)(145*f),20,26,36):Color.argb((int)(185*f),64,54,104));
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2f);
                    box(c,btnL[i],72,btnR[i],144,20,brightHeader?Color.argb((int)(165*f),20,26,36):Color.argb((int)(175*f),206,184,255));
                    p.setStyle(Paint.Style.FILL);
                }
                drawHeaderIcon(c,i,btnL[i]+34,108,f,brightHeader);
                int hdrText=brightHeader?blendColor(0xCC141A24,DesktopContrast.INK_COLOR,f):blendColor(0xFFD5E6EE,Color.WHITE,f);
                if(brightHeader&&f<=0.2f)text(c,btnLabels[i],btnL[i]+66,119.5f,26,0x99FFFFFF,i==0||f>0.35f);
                text(c,btnLabels[i],btnL[i]+66,118,26,hdrText,i==0||f>0.35f);
            }
            int globalHPercent=a.getInt("card_height",100);
            int globalDPercent=a.getInt("card_depth",0);
            int globalR=a.getInt("card_radius",32);
            int surfacePct=LiteSettings.clamp(17,a.getInt("card_surface",55));
            float focusZoom=LiteSettings.focusScale(a.getInt("card_scale",12));
            int labelAlpha=Math.round(255*LiteSettings.clamp(15,a.getInt("label_opacity",75))/100f);
            float baseH=LiteSettings.height(globalHPercent);
            float y=LiteSettings.center(a.getInt("card_row_position",75),globalHPercent);
            if(fillY!=y||fillHeight!=baseH){
                fillY=y;
                fillHeight=baseH;
                cardFill=new LinearGradient(0,y-baseH/2,0,y+baseH/2,new int[]{0xFF395166,0xFF263A4D,0xFF192838},new float[]{0f,0.34f,1f},Shader.TileMode.CLAMP);
                focusFill=new LinearGradient(0,y-baseH/2,0,y+baseH/2,new int[]{0xFF658BA3,0xFF426378,0xFF274054},new float[]{0f,0.32f,1f},Shader.TileMode.CLAMP);
                for(int k=0;k<LiteSettings.COVER_COLORS.length;k++){
                    colorCoverFills[k]=new LinearGradient(0,y-baseH/2,0,y+baseH/2,LiteSettings.COVER_COLORS[k],new float[]{0f,0.42f,1f},Shader.TileMode.CLAMP);
                }
            }
            boolean showingFav=activeRow==1&&!favorites.isEmpty();
            boolean showingInputs=activeRow==2&&!tvInputs.isEmpty();
            ArrayList<App> rowApps=showingFav?favorites:(showingInputs?tvInputs:apps);
            String rowTitle=showingFav?"收藏（"+favorites.size()+"）":(showingInputs?"输入源（"+tvInputs.size()+"）":"我的应用");
            drawShelfBadge(c,showingInputs?2:(showingFav?1:0),130,y-baseH/2-62,brightShelf);
            int shelfTitleColor=brightShelf?DesktopContrast.INK_COLOR:Color.WHITE;
            if(brightShelf)text(c,rowTitle,162,y-baseH/2-50.5f,32,0xB0FFFFFF,true);
            text(c,rowTitle,162,y-baseH/2-52,32,shelfTitleColor,true);
            String switchHint=buildRowHint(showingFav,showingInputs);
            if(switchHint.length()>0){
                p.setTypeface(boldFace);
                p.setTextSize(32);
                float hintX=Math.max(350f,184f+p.measureText(rowTitle));
                text(c,switchHint,hintX,y-baseH/2-54,21,brightShelf?0xCC141A24:0xAA9EC5D6,false);
            }
            if(movingId!=null){
                box(c,520,210,1400,268,28,0xDD1A3A4F);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2f);
                box(c,520,210,1400,268,28,0xFF8CD6F0);
                p.setStyle(Paint.Style.FILL);
                text(c,"◀  拿起移动中 · 左右调整 · 确定放下 · 返回取消  ▶",568,248,24,0xFFE8FAFF,true);
            }
            if(rowApps.isEmpty())text(c,status,112,y,28,brightShelf?0xCC141A24:0xFFC2D3DF,false);
            computeCardLayout(a,rowApps);
            float maxCardHalf=baseH/2f;
            for(int i=0;i<rowApps.size();i++){
                App app=rowApps.get(i);
                float w=cardWidths[i];
                float h=LiteSettings.cardHeightPx(globalHPercent,app.customH);
                if(h/2f>maxCardHalf)maxCardHalf=h/2f;
                float d=LiteSettings.cardDepthPx(globalDPercent,app.customD);
                float x=cardCenters[i]-scroll.value+(page==2&&choice==10?demo.value:0);
                if(x+w<-80||x-w>2000)continue;
                boolean isGrabbed=movingId!=null&&movingId.equals(app.id);
                boolean focus=i==selected&&(!topFocus||(page==2&&choice==10)||page==7||page==8)&&!edgeOpen;
                float z=isGrabbed?Math.max(focusZoom,1.12f):(focus?focusZoom:1f);
                float cy=y-(isGrabbed?22:(focus?10:0));
                float hw=w*z/2f,hh=h*z/2f;
                float radius=Math.min(Math.min(hw,hh),LiteSettings.cardRadiusPx(globalR,app.customR));
                float depthPx=d*z;
                // 2-layer soft floating shadow
                box(c,x-hw-6,cy-hh+10+depthPx,x+hw+6,cy+hh+18+depthPx,radius+4,focus?0x38020710:0x22020710);
                box(c,x-hw-1,cy-hh+5+depthPx,x+hw+1,cy+hh+9+depthPx,radius+1,focus?0x50030A16:0x36030A16);
                // Optional 3D acrylic bottom edge thickness
                if(depthPx>0.6f){
                    box(c,x-hw,cy-hh+depthPx,x+hw,cy+hh+depthPx,radius,focus?0xFF284256:0xFF172736);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(1.5f);
                    box(c,x-hw,cy-hh+depthPx,x+hw,cy+hh+depthPx,radius,focus?0x668CD6F0:0x3376A4BA);
                    p.setStyle(Paint.Style.FILL);
                }
                // Card body: custom image cover > custom color cover > smooth optical glass gradient
                rect.set(x-hw,cy-hh,x+hw,cy+hh);
                String imgName=LiteSettings.coverFileName(app.cover);
                int colorCover=LiteSettings.coverColorIndex(app.cover);
                boolean drewCustomImg=false;
                if(imgName!=null){
                    Bitmap cb=loadCoverBitmap(imgName);
                    BitmapShader sh=coverShaders.get(imgName);
                    if(cb!=null&&sh!=null){
                        float cw=hw*2f,ch=hh*2f;
                        float s=Math.max(cw/cb.getWidth(),ch/cb.getHeight());
                        shaderMatrix.reset();
                        shaderMatrix.setScale(s,s);
                        shaderMatrix.postTranslate(x-cb.getWidth()*s/2f,cy-cb.getHeight()*s/2f);
                        sh.setLocalMatrix(shaderMatrix);
                        p.setColor(Color.WHITE);
                        p.setAlpha(255);
                        p.setShader(sh);
                        c.drawRoundRect(rect,radius,radius,p);
                        p.setShader(null);
                        drewCustomImg=true;
                    }
                }
                if(!drewCustomImg){
                    p.setColor(Color.WHITE);
                    p.setShader(colorCover>=0?colorCoverFills[colorCover]:(focus?focusFill:cardFill));
                    c.drawRoundRect(rect,radius,radius,p);
                    p.setShader(null);
                    if(surfacePct>0){
                        int sheenA=Math.round((focus?64f:38f)*surfacePct/100f);
                        p.setShader(new LinearGradient(x-hw,cy-hh,x+hw*0.4f,cy+hh*0.4f,new int[]{Color.argb(sheenA,255,255,255),0x00FFFFFF},new float[]{0f,1f},Shader.TileMode.CLAMP));
                        c.drawRoundRect(rect,radius,radius,p);
                        p.setShader(null);
                    }
                    if(app.icon!=null&&!app.icon.isRecycled()){
                        float sz=Math.min(104f,Math.min(w,h)-26f)*z;
                        if(sz>20f){
                            rect.set(x-sz/2f,cy-sz/2f,x+sz/2f,cy+sz/2f);
                            p.setAlpha(255);
                            c.drawBitmap(app.icon,null,rect,p);
                        }
                    }else{
                        String ch=app.label.isEmpty()?"?":app.label.substring(0,1);
                        p.setShader(null);
                        p.setColor(Color.WHITE);
                        p.setTypeface(boldFace);
                        p.setTextSize(Math.min(54f,Math.min(w,h)*0.42f));
                        p.setTextAlign(Paint.Align.CENTER);
                        c.drawText(ch,x,cy+18,p);
                        p.setTextAlign(Paint.Align.LEFT);
                    }
                }
                // Rim stroke
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(isGrabbed?4f:(focus?3f:1.2f));
                int unfocusedRim=brightShelf?0x38141A24:0x28FFFFFF;
                box(c,x-hw,cy-hh,x+hw,cy+hh,radius,isGrabbed?0xFF8CD6F0:(focus?(brightShelf?0xE8141A24:0xE0D4F2F8):unfocusedRim));
                p.setStyle(Paint.Style.FILL);
                // Card title below card (respects label_opacity for unfocused cards)
                int lAlpha=focus?255:labelAlpha;
                if(lAlpha>8){
                    float labelY=Math.min(1038f,y+Math.max(baseH,h)/2f+48f);
                    String trimmed=trim(app.label,Math.max(120f,w-10f),26);
                    int haloColor=brightShelf?Color.argb(Math.round(175*lAlpha/255f),255,255,255):Color.argb(Math.round(135*lAlpha/255f),0,0,0);
                    int textInk=brightShelf?(focus?DesktopContrast.INK_COLOR:Color.argb(lAlpha,20,26,36)):(focus?Color.WHITE:Color.argb(lAlpha,189,208,222));
                    text(c,trimmed,x-w/2f+6,labelY+(brightShelf?1.5f:2f),26,haloColor,focus);
                    text(c,trimmed,x-w/2f+5,labelY,26,textInk,focus);
                }
            }
            float bottomLabel=y+maxCardHalf+48f;
            float hintAlpha=Math.max(0f,Math.min(1f,(955f-bottomLabel)/55f));
            if(hintAlpha>0.05f&&movingId==null){
                int hintInk=brightShelf?Color.argb((int)(210*hintAlpha),20,26,36):Color.argb((int)(200*hintAlpha),165,190,206);
                text(c,"↑ 顶栏   ← → 选择（首张左按开快捷栏）   确定打开   长按管理",112,1022,22,hintInk,false);
            }
            if(edgeOpen&&page==0){
                drawEdgeBar(c);
            }
            c.restore();
        }
        String buildRowHint(boolean showingFav,boolean showingInputs){
            if(showingFav){
                return !tvInputs.isEmpty()?"▲ 我的应用 · ▼ 输入源":"▲ 按上键返回「我的应用」";
            }
            if(showingInputs){
                return !favorites.isEmpty()?"▲ 按上键返回「收藏」":"▲ 按上键返回「我的应用」";
            }
            if(!favorites.isEmpty()&&!tvInputs.isEmpty())return "▼ 收藏 / 输入源";
            if(!favorites.isEmpty())return "▼ 按下键查看「收藏（"+favorites.size()+"）」";
            if(!tvInputs.isEmpty())return "▼ 按下键查看「输入源（"+tvInputs.size()+"）」";
            return "";
        }
        void drawShelfBadge(Canvas c,int rowKind,float cx,float cy,boolean bright){
            c.save();
            c.translate(Math.round(cx),Math.round(cy));
            p.setShader(null);
            rect.set(-18,-18,18,18);
            p.setStyle(Paint.Style.FILL);
            p.setColor(bright?0x22141A24:0x28FFFFFF);
            c.drawRoundRect(rect,10,10,p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f);
            p.setColor(bright?0x42141A24:0x4EFFFFFF);
            rect.inset(0.75f,0.75f);
            c.drawRoundRect(rect,9.5f,9.5f,p);
            p.setStyle(Paint.Style.FILL);
            int glyphInk=bright?DesktopContrast.INK_COLOR:Color.WHITE;
            if(rowKind==1){
                p.setColor(bright?0xFFD98800:0xFFFFE082);
                path.reset();
                for(int i=0;i<10;i++){
                    double ang=-Math.PI/2+i*Math.PI/5.0;
                    float r=(i%2==0)?10.5f:4.6f;
                    float px=(float)(Math.cos(ang)*r),py=(float)(Math.sin(ang)*r)+0.6f;
                    if(i==0)path.moveTo(px,py);else path.lineTo(px,py);
                }
                path.close();
                c.drawPath(path,p);
            }else if(rowKind==2){
                p.setColor(glyphInk);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.4f);
                path.reset();
                path.moveTo(-4,-8);
                path.lineTo(8,-8);
                path.lineTo(8,8);
                path.lineTo(-4,8);
                c.drawPath(path,p);
                c.drawLine(-10,0,2,0,p);
                c.drawLine(-2,-4,2,0,p);
                c.drawLine(-2,4,2,0,p);
                p.setStyle(Paint.Style.FILL);
            }else{
                p.setColor(glyphInk);
                rect.set(-9,-9,-1.5f,-1.5f);
                c.drawRoundRect(rect,2.4f,2.4f,p);
                rect.set(-9,1.5f,-1.5f,9);
                c.drawRoundRect(rect,2.4f,2.4f,p);
                rect.set(1.5f,1.5f,9,9);
                c.drawRoundRect(rect,2.4f,2.4f,p);
                p.setColor(bright?0xFF1688D8:0xFF7AD8FF);
                rect.set(1.5f,-9,9,-1.5f);
                c.drawRoundRect(rect,2.4f,2.4f,p);
            }
            c.restore();
        }
        void drawEdgeBar(Canvas c){
            box(c,0,0,1920,1080,0,0x55060D16);
            box(c,28,240,308,770,26,0xEE142230);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f);
            box(c,28,240,308,770,26,0x55A8D8E6);
            p.setStyle(Paint.Style.FILL);
            text(c,"快捷导航",54,290,22,0xFF9EC5D6,true);
            String[] items={"全部应用","添加应用入口","我的壁纸库","桌面天气","设置菜单"};
            for(int i=0;i<items.length;i++){
                float iy=320+i*76;
                boolean foc=i==edgeChoice;
                box(c,46,iy,290,iy+62,14,foc?0xFF335B75:0xFF1D2F40);
                if(foc){
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2f);
                    box(c,46,iy,290,iy+62,14,0xCC9EE0EE);
                    p.setStyle(Paint.Style.FILL);
                }
                text(c,items[i],68,iy+40,23,Color.WHITE,foc);
            }
            text(c,"→ / 返回 收起",54,740,18,0xFF8FAFC0,false);
        }
        int count(){
            if(page==1)return menu.length;
            if(page==2)return LiteSettings.KEYS.length+3;
            if(page==3)return 13;
            if(page==6)return Math.max(1,filteredApps.size());
            if(page==7)return 5;
            if(page==8)return 2+LiteSettings.COVER_NAMES.length;
            if(page==9){
                ArrayList<String[]> cw=host.customWallpapers();
                return 5+cw.size()+(cw.isEmpty()?1:2);
            }
            if(page==10)return 3;
            if(page==11)return (manualScanning||manualActivities.isEmpty())?1:manualActivities.size();
            return 1;
        }
        String value(int i){
            int v=readParam(appearance(),i);
            if(i==3)return ClockScene.STYLES[LiteSettings.clamp(i,v)];
            if(i==18)return DesktopContrast.COLOR_MODE_NAMES[LiteSettings.clamp(i,v)];
            if(i==26)return WeatherSnapshot.PRECIP_MODES[LiteSettings.clamp(i,v)];
            if(i==10||LiteSettings.isBool(i))return v==0?"关闭":"开启";
            if(i==0||i==1||i==11||i==12||i==14||i==15||i==16||i==17||i==20||i==21||i==28)return v+"%";
            if(i==4||i==19)return v+"°";
            return Integer.toString(v);
        }
        String customSizeValue(App a,int idx){
            if(idx==0)return a.customW<=0?"跟随全局":a.customW+"%";
            if(idx==1)return a.customH<=0?"跟随全局":a.customH+"%";
            if(idx==2)return a.customD<0?"跟随全局":a.customD+"%";
            if(idx==3)return a.customR<0?"跟随全局":Integer.toString(a.customR);
            return "";
        }
        void panel(Canvas c){
            float left=page==6?320:(page==2||page==7||page==8?1290:1080),width=1920-left-50;
            box(c,left,45,1870,1035,30,0xFF172432);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f);
            box(c,left,45,1870,1035,30,0x33A8D8E6);
            p.setStyle(Paint.Style.FILL);
            App managed=findApp(managedId);
            String heading;
            if(page==2)heading="Magic Desktop";
            else if(page==3)heading=managed!=null?trim("管理 · "+managed.label,width-64,34):"应用管理";
            else if(page==4)heading="设备与兼容状态";
            else if(page==5)heading="Marco TV Lite";
            else if(page==6)heading="全部应用与管理（"+filteredApps.size()+"/"+allApps.size()+"）";
            else if(page==7)heading=managed!=null?trim("尺寸 · "+managed.label,width-64,32):"自定义卡片尺寸";
            else if(page==8)heading=managed!=null?trim("封面 · "+managed.label,width-64,32):"更换卡片封面";
            else if(page==9)heading="我的壁纸库";
            else if(page==10)heading="桌面天气探测";
            else if(page==11)heading="手动添加应用入口";
            else heading="设置";
            text(c,heading,left+32,108,34,Color.WHITE,true);
            if(page==4||page==5){
                String wallName=LiteSettings.wallpaperName(prefs.getInt("wallpaper",0));
                String[] lines=page==4?new String[]{
                        "版本："+LiteSettings.VERSION+" · Lite（API 14 原生）",
                        "设备："+Build.MANUFACTURER+" / "+Build.MODEL,
                        "Android "+Build.VERSION.RELEASE+" · API "+Build.VERSION.SDK_INT,
                        "画面："+getWidth()+" × "+getHeight(),
                        "应用："+catalog.size()+" 个（收藏 "+favorites.size()+" · 隐藏 "+hiddenCount()+"）",
                        "壁纸："+wallName+" · 排序："+sortModeLabel()+" · 休眠："+LiteSettings.sleepLabel(host.sleepMinutes()),
                        "默认桌面："+LiteSettings.homeStateLabel(defaultHomeState()),
                        "云端视频壁纸 / 投屏 / 手机互联 / 最近任务：Lite 版未提供",
                        "安装说明：小米盒子若提示安装包异常请断网或 ADB 安装"
                }:new String[]{
                        "作者 Pilipala · Marco TV Lite "+LiteSettings.VERSION,
                        "免费开源 · GPL-3.0-only",
                        "如果你付费购买了本软件，请尽快向购买平台申请退款。",
                        "Lite 独立于完整版安装，面向 Android 4.0+ 老设备。",
                        "内置四款数字字体（SIL OFL 1.1）：",
                        "Roboto Flex · Barlow Condensed · Nunito · Bodoni Moda",
                        "支持收藏行、拿起移动、自定义封面与尺寸、系统确认卸载。",
                        "按确定查看随包开源许可全文",
                        "无联网权限、无后台服务，不自动上传任何数据。"
                };
                for(int i=0;i<lines.length;i++)text(c,trim(lines[i],width-64,24),left+32,185+i*68,24,0xFFC1D4DE,false);
                text(c,page==5?"按确定查看许可 · 按返回关闭":"按返回关闭",left+32,986,24,0xFFB3E5E8,false);
                return;
            }
            if(page==3&&managed!=null){
                String meta=managed.id+(managed.version!=null&&managed.version.length()>0?" · v"+managed.version:"")
                        +(managed.canUninstall()?" · 可卸载":" · 系统内置");
                text(c,trim(meta,width-64,20),left+32,140,20,0xFF8FB0C2,false);
            }else if(page==6){
                // Filter pills + search hint
                float px=left+32;
                for(int fi=0;fi<filterNames.length;fi++){
                    boolean active=fi==allFilter;
                    float pw=112f;
                    box(c,px,124,px+pw,160,14,active?0xFF396B84:0xFF213242);
                    if(active){
                        p.setStyle(Paint.Style.STROKE);
                        p.setStrokeWidth(1.5f);
                        box(c,px,124,px+pw,160,14,0xCC9EE0EE);
                        p.setStyle(Paint.Style.FILL);
                    }
                    p.setTypeface(active?boldFace:normalFace);
                    p.setTextSize(20);
                    p.setColor(active?Color.WHITE:0xFF9FB9C8);
                    p.setTextAlign(Paint.Align.CENTER);
                    c.drawText(filterNames[fi],px+pw/2f,149,p);
                    p.setTextAlign(Paint.Align.LEFT);
                    px+=pw+12f;
                }
                String searchPill=allQuery.length()>0?"搜索："+trim(allQuery,220,20):"菜单键搜索";
                box(c,1560,124,1846,160,14,allQuery.length()>0?0xFF345E73:0xFF213242);
                text(c,searchPill,1580,149,20,allQuery.length()>0?Color.WHITE:0xFF9FB9C8,false);
            }
            if(page==10){
                String probeMsg=host.lastWeatherProbe!=null?host.lastWeatherProbe.summary():"尚未探测本机天气，请点击下方“立即探测本机系统天气”。";
                String[] pLines=probeMsg.split("\n");
                for(int pi=0;pi<Math.min(4,pLines.length);pi++){
                    text(c,trim(pLines[pi],width-64,22),left+32,162+pi*46,22,0xFFC1D4DE,false);
                }
            }
            int listTop=page==6?174:(page==10?380:(page==3?158:155));
            int rows=page==10?4:7;
            int rowStep=page==6?108:110;
            int total=count();
            int start=Math.max(0,Math.min(choice-2,total-rows));
            if(page==6&&filteredApps.isEmpty()){
                text(c,allQuery.length()>0?"没有匹配「"+allQuery+"」的应用（按菜单键修改/清除搜索）":"该筛选分类下暂无应用（按左右键切换分类）",left+42,240,25,0xFFC1D4DE,false);
                text(c,"左右键切分类 · 菜单键搜索 · 返回关闭",left+32,986,22,0xFFA5C4D2,false);
                return;
            }
            for(int i=start;i<Math.min(total,start+rows);i++){
                float y=listTop+(i-start)*rowStep;
                boolean focus=i==choice;
                box(c,left+24,y,1846,y+rowStep-14,16,focus?0xFF315268:0xFF213344);
                if(focus){
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2f);
                    box(c,left+24,y,1846,y+rowStep-14,16,0xB89EE0EE);
                    p.setStyle(Paint.Style.FILL);
                }
                if(page==6){
                    App item=filteredApps.get(i);
                    float ix=left+44,iy=y+21;
                    if(item.icon!=null&&!item.icon.isRecycled()){
                        rect.set(ix,iy,ix+52,iy+52);
                        p.setAlpha(255);
                        c.drawBitmap(item.icon,null,rect,p);
                    }else{
                        box(c,ix,iy,ix+52,iy+52,12,0xFF314D63);
                        text(c,item.label.isEmpty()?"?":item.label.substring(0,1),ix+14,iy+37,28,Color.WHITE,true);
                    }
                    StringBuilder tags=new StringBuilder();
                    if(item.pinned)tags.append(" [置顶]");
                    if(item.favorite)tags.append(" [收藏]");
                    if(item.hidden)tags.append(" [已隐藏]");
                    if(item.systemApp)tags.append(item.updatedSystemApp?" [系统·已更新]":" [系统]");
                    if(item.hasCustomSize()||item.hasCustomCover())tags.append(" [自定义]");
                    text(c,trim(item.label+tags.toString(),width-160,25),left+114,y+41,25,Color.WHITE,focus);
                    String sub=item.id+(item.version!=null&&item.version.length()>0?" · v"+item.version:"")
                            +(item.canUninstall()?" · 支持卸载":" · 系统内置");
                    text(c,trim(sub,width-160,20),left+114,y+74,20,focus?0xFFCFE8F2:0xFF8CA8BA,false);
                    continue;
                }
                String title;
                if(page==1){
                    String suffix="";
                    if(i==1)suffix="："+LiteSettings.wallpaperName(prefs.getInt("wallpaper",0));
                    else if(i==2)suffix="："+sortModeLabel();
                    else if(i==3){
                        int hc=hiddenCount();
                        if(hc>0)suffix="（"+hc+" 个）";
                    }else if(i==13)suffix="  "+host.volumePercent()+"%";
                    else if(i==14)suffix="："+LiteSettings.sleepLabel(host.sleepMinutes());
                    title=menu[i]+suffix;
                }else if(page==3){
                    if(i==0)title=(managed!=null&&managed.pinned&&isFirstPinned(managed))?"取消置顶":"置顶到首位";
                    else if(i==1)title=(managed!=null&&managed.hidden)?"取消隐藏":"隐藏应用";
                    else if(i==2)title=(managed!=null&&!managed.canUninstall())?"系统内置应用（查看信息）":(managed!=null&&managed.updatedSystemApp?"卸载更新（系统确认）":"卸载应用（系统确认）");
                    else if(i==3)title="重命名应用"+(managed!=null&&!managed.label.equals(managed.origLabel)?"（已改名）":"");
                    else if(i==4)title="向左移动";
                    else if(i==5)title="向右移动";
                    else if(i==6)title="自定义卡片尺寸"+(managed!=null&&managed.hasCustomSize()?"（已自定义）":"");
                    else if(i==7)title="更换卡片封面"+(managed!=null&&managed.hasCustomCover()?"（已自定义）":"");
                    else if(i==8)title=(managed!=null&&managed.favorite)?"取消收藏":"加入收藏";
                    else if(i==9)title="拿起并移动";
                    else if(i==10)title="恢复默认外观";
                    else if(i==11)title="系统应用信息";
                    else title="打开应用";
                }else if(page==7){
                    String[] szNames={"卡片宽度","卡片高度","立体厚度","卡片圆角","恢复跟随全局默认"};
                    title=i<4&&managed!=null?szNames[i]+"  "+customSizeValue(managed,i):szNames[i];
                }else if(page==8){
                    if(i==0)title="原始应用图标"+(managed!=null&&!managed.hasCustomCover()?"（当前）":"");
                    else if(i==1)title="导入本地图片封面…"+(managed!=null&&LiteSettings.coverFileName(managed.cover)!=null?"（当前）":"");
                    else{
                        int ci=i-2;
                        boolean active=managed!=null&&LiteSettings.coverColorIndex(managed.cover)==ci;
                        title="主题纯色 · "+LiteSettings.COVER_NAMES[ci]+(active?"（当前）":"");
                    }
                }else if(page==9){
                    int[] builtinIds={0,1,3,4,5};
                    ArrayList<String[]> cw=host.customWallpapers();
                    int curWall=prefs.getInt("wallpaper",0);
                    String activeCustom=prefs.getString("active_custom_wall","");
                    if(i<5){
                        int wid=builtinIds[i];
                        title="内置 · "+LiteSettings.wallpaperName(wid)+(curWall==wid?"（使用中）":"");
                    }else if(i<5+cw.size()){
                        String[] ent=cw.get(i-5);
                        boolean active=curWall==2&&(ent[0].equals(activeCustom)||(activeCustom.length()==0&&i==5));
                        title="本地导入 · "+ent[1]+(active?"（使用中）":"");
                    }else if(i==5+cw.size()){
                        title="从本地相册 / 存储导入新壁纸…";
                    }else{
                        title="删除当前导入壁纸";
                    }
                }else if(page==10){
                    if(i==0)title="首页显示天气："+(prefs.getBoolean("weather_enabled",true)?"已开启":"已关闭");
                    else if(i==1)title="立即探测本机系统天气";
                    else title="自定义天气图标与显示项…";
                }else if(page==11){
                    if(manualScanning)title="正在扫描本机可启动 Activity…";
                    else if(manualActivities.isEmpty())title="未发现额外的可启动 Activity";
                    else{
                        String[] entry=manualActivities.get(i);
                        boolean added=prefs.contains("manual:"+entry[0]);
                        title=(added?"[已添加] ":"")+entry[2]+"（"+entry[0]+"）";
                    }
                }else{
                    title=i<LiteSettings.KEYS.length?LiteSettings.NAMES[i]+"  "+value(i):i==LiteSettings.KEYS.length?"保存":i==LiteSettings.KEYS.length+1?"取消":"恢复默认值";
                }
                if(page==8&&i>=2){
                    int ci=i-2;
                    box(c,left+42,y+24,left+82,y+64,10,LiteSettings.COVER_COLORS[ci][0]);
                    text(c,trim(title,width-130,24),left+98,y+52,24,Color.WHITE,focus);
                }else{
                    text(c,trim(title,width-80,25),left+42,y+44,25,Color.WHITE,focus);
                }
                if(page==1&&i==13){
                    float ratio=host.volumePercent()/100f;
                    box(c,left+42,y+68,1825,y+74,3,0xFF566B7D);
                    float x=left+42+(1783-left)*ratio;
                    box(c,left+42,y+68,x,y+74,3,0xFFC4A8FF);
                    p.setColor(0xFFC4A8FF);
                    c.drawCircle(x,y+71,8,p);
                }else if(page==2&&i<LiteSettings.KEYS.length){
                    float ratio=(readParam(appearance(),i)-LiteSettings.MIN[i])/(float)(LiteSettings.MAX[i]-LiteSettings.MIN[i]);
                    box(c,left+42,y+68,1825,y+74,3,0xFF566B7D);
                    float x=left+42+(1783-left)*ratio;
                    box(c,left+42,y+68,x,y+74,3,0xFFCBB2FF);
                    p.setColor(0xFFCBB2FF);
                    c.drawCircle(x,y+71,9,p);
                }else if(page==7&&i<4&&managed!=null){
                    float ratio=customSliderRatio(managed,i);
                    box(c,left+42,y+68,1825,y+74,3,0xFF566B7D);
                    float x=left+42+(1783-left)*ratio;
                    box(c,left+42,y+68,x,y+74,3,0xFFC4A8FF);
                    p.setColor(0xFFC4A8FF);
                    c.drawCircle(x,y+71,9,p);
                }
            }
            String footer=page==2?"左右调整 · 保存才应用 · 返回撤销":page==7?"左右调整即时生效 · 返回上级":page==8?"上下选择封面 · 确定应用 · 返回上级":page==6?"上下选应用 · 左右切分类 · 菜单键搜索 · 确定管理":page==9?"上下选择壁纸 · 确定应用或导入/删除":page==10?"探测本机天气数据 · 自定义图标与温度显示":page==11?"上下选择非桌面 Activity 入口 · 确定添加/移除":"上下选择 · 确定进入 · 返回关闭";
            text(c,footer,left+32,986,22,0xFFA5C4D2,false);
        }
        float customSliderRatio(App a,int idx){
            if(idx==0)return a.customW<=0?0f:(a.customW-60)/100f;
            if(idx==1)return a.customH<=0?0f:(a.customH-60)/90f;
            if(idx==2)return a.customD<0?0f:a.customD/100f;
            return a.customR<0?0f:a.customR/64f;
        }
        void changeCustomSize(int dir){
            App a=findApp(managedId);
            if(a==null||choice>=4)return;
            int w=a.customW,h=a.customH,d=a.customD,r=a.customR;
            if(choice==0){
                int base=w<=0?prefs.getInt("card_width",100):w;
                w=LiteSettings.clampCustomWidth(base+dir*5);
            }else if(choice==1){
                int base=h<=0?prefs.getInt("card_height",100):h;
                h=LiteSettings.clampCustomHeight(base+dir*5);
            }else if(choice==2){
                int base=d<0?prefs.getInt("card_depth",0):d;
                d=LiteSettings.clampCustomDepth(base+dir*5);
            }else if(choice==3){
                int base=r<0?prefs.getInt("card_radius",32):r;
                r=LiteSettings.clampCustomRadius(base+dir*4);
            }
            saveCustomCardSize(a,w,h,d,r);
            scroll.snap(targetScroll(selected));
            invalidate();
        }
        void change(int direction){
            if(page==1&&choice==13){
                host.adjustVolume(direction);
                invalidate();
                return;
            }
            if(page==7){
                changeCustomSize(direction);
                return;
            }
            if(page!=2||choice>=LiteSettings.KEYS.length)return;
            int step=choice==7?10:(choice==19?10:(choice==11||choice==12||choice==15||choice==16||choice==17||choice==20||choice==21||choice==28?5:1));
            int cur=readParam(draft,choice);
            writeDraft(draft,choice,cur+direction*step);
            previewChanged();
        }
        void activate(){
            if(page==0){
                if(edgeOpen){
                    int[] targets={6,11,9,10,1};
                    int targetPage=targets[Math.max(0,Math.min(targets.length-1,edgeChoice))];
                    edgeOpen=false;
                    open(targetPage);
                    return;
                }
                if(movingId!=null){
                    finishMove();
                    return;
                }
                if(topFocus)open(top==0?1:top==1?2:6);
                else{
                    ArrayList<App> list=activeRowApps();
                    if(!list.isEmpty())host.launch(list.get(Math.min(selected,list.size()-1)));
                }
                return;
            }
            if(page==9){
                int[] builtinIds={0,1,3,4,5};
                ArrayList<String[]> cw=host.customWallpapers();
                if(choice<5){
                    prefs.edit().putInt("wallpaper",builtinIds[choice]).apply();
                    loadWallpaper();
                    toast("已切换壁纸："+LiteSettings.wallpaperName(builtinIds[choice]));
                }else if(choice<5+cw.size()){
                    String[] ent=cw.get(choice-5);
                    prefs.edit().putInt("wallpaper",2).putString("active_custom_wall",ent[0]).apply();
                    loadWallpaper();
                    toast("已应用壁纸："+ent[1]);
                }else if(choice==5+cw.size()){
                    host.pickWallpaper();
                }else if(!cw.isEmpty()){
                    String active=prefs.getString("active_custom_wall","");
                    String target=active.length()>0?active:cw.get(0)[0];
                    host.deleteCustomWallpaper(target);
                    choice=Math.max(0,Math.min(choice,count()-1));
                }
                invalidate();
                return;
            }
            if(page==10){
                if(choice==0){
                    boolean next=!prefs.getBoolean("weather_enabled",true);
                    prefs.edit().putBoolean("weather_enabled",next).apply();
                    toast(next?"首页天气已开启":"首页天气已关闭");
                }else if(choice==1){
                    host.probeWeatherAsync(true);
                }else{
                    open(2);
                    choice=22;
                }
                invalidate();
                return;
            }
            if(page==11){
                if(!manualScanning&&choice>=0&&choice<manualActivities.size()){
                    String[] ent=manualActivities.get(choice);
                    boolean exists=prefs.contains("manual:"+ent[0]);
                    SharedPreferences.Editor ed=prefs.edit();
                    if(exists){
                        ed.remove("manual:"+ent[0]);
                        toast("已移除入口："+ent[2]);
                    }else{
                        ed.putString("manual:"+ent[0],ent[1]).putBoolean("hidden:"+ent[0],false);
                        toast("已添加到桌面："+ent[2]);
                    }
                    ed.apply();
                    host.loadApps();
                }
                invalidate();
                return;
            }
            if(page==3){
                host.appAction(choice);
                return;
            }
            if(page==6){
                if(!filteredApps.isEmpty()&&choice>=0&&choice<filteredApps.size()){
                    managedId=filteredApps.get(choice).id;
                    open(3);
                }
                return;
            }
            if(page==7){
                if(choice<4){
                    changeCustomSize(1);
                }else{
                    App a=findApp(managedId);
                    if(a!=null){
                        saveCustomCardSize(a,0,0,-1,-1);
                        toast("已恢复跟随全局默认尺寸");
                    }
                    invalidate();
                }
                return;
            }
            if(page==8){
                App a=findApp(managedId);
                if(a!=null){
                    if(choice==0){
                        setCardCover(a,"");
                        toast("已恢复原始应用图标");
                    }else if(choice==1){
                        host.pickCardCover(a.id);
                    }else{
                        int ci=choice-2;
                        setCardCover(a,"color:"+ci);
                        toast("已应用「"+LiteSettings.COVER_NAMES[ci]+"」主题封面");
                    }
                }
                invalidate();
                return;
            }
            if(page==5){
                host.licenses();
                return;
            }
            if(page==4){
                backPage();
                return;
            }
            if(page==2){
                if(choice<LiteSettings.KEYS.length){
                    change(1);
                    return;
                }
                if(choice==LiteSettings.KEYS.length){
                    SharedPreferences.Editor e=prefs.edit();
                    draft.writeTo(e);
                    e.apply();
                    backPage();
                }else if(choice==LiteSettings.KEYS.length+1)backPage();
                else {
                    for(int i=0;i<LiteSettings.KEYS.length;i++)writeDraft(draft,i,LiteSettings.DEFAULT[i]);
                    invalidate();
                }
                return;
            }
            switch(choice){
                case 0:
                    open(2);
                    break;
                case 1:
                    int nextWall=LiteSettings.nextWallpaper(prefs.getInt("wallpaper",0));
                    prefs.edit().putInt("wallpaper",nextWall).apply();
                    loadWallpaper();
                    break;
                case 2:
                    int nextMode=(sortMode()+1)%3;
                    prefs.edit().putInt("sort_mode",nextMode).putBoolean("alphabetic",nextMode==1).apply();
                    rebuildVisible();
                    break;
                case 3:
                    SharedPreferences.Editor e=prefs.edit();
                    for(String key:prefs.getAll().keySet())if(key.startsWith("hidden:"))e.remove(key);
                    e.apply();
                    rebuildVisible();
                    toast("隐藏应用已恢复");
                    break;
                case 4:
                    host.systemSettings();
                    break;
                case 5:
                    host.backup(false);
                    break;
                case 6:
                    host.backup(true);
                    break;
                case 7:
                    open(4);
                    break;
                case 8:
                    open(5);
                    break;
                case 9:
                    loadApps();
                    toast("正在刷新应用列表");
                    break;
                case 10:
                    open(6);
                    break;
                case 11:
                    host.pickWallpaper();
                    break;
                case 12:
                    host.openHomeSettings();
                    break;
                case 13:
                    host.adjustVolume(1);
                    break;
                case 14: {
                    int cur=host.sleepMinutes();
                    int next=LiteSettings.SLEEP_CHOICES[0];
                    for(int i=0;i<LiteSettings.SLEEP_CHOICES.length;i++){
                        if(LiteSettings.SLEEP_CHOICES[i]==cur){
                            next=LiteSettings.SLEEP_CHOICES[(i+1)%LiteSettings.SLEEP_CHOICES.length];
                            break;
                        }
                    }
                    prefs.edit().putInt("ambient_sleep_minutes",next).apply();
                    updateKeepScreenOn();
                    break;
                }
                case 15:
                    closePage();
                    enterAmbient();
                    break;
                case 16:
                    open(9);
                    break;
                case 17:
                    open(10);
                    break;
                case 18:
                    open(11);
                    break;
            }
            invalidate();
        }
        @Override public boolean onKeyDown(int key,KeyEvent event){
            boolean sleeping=ambient||saver.value>.05f;
            wake();
            if(sleeping){
                wakeKey=true;
                return true;
            }
            if(isConfirmKey(key)){
                if(event.getRepeatCount()==0){
                    event.startTracking();
                    longPress=false;
                }else if((event.isLongPress()||event.getRepeatCount()>=3)&&!longPress&&page==0&&movingId==null&&!topFocus&&!activeRowApps().isEmpty()){
                    longPress=true;
                    open(3);
                }
                return true;
            }
            if(key==KeyEvent.KEYCODE_BACK){
                if(edgeOpen&&page==0){
                    edgeOpen=false;
                    invalidate();
                    return true;
                }
                if(movingId!=null){
                    cancelMove(true);
                    return true;
                }
                if(page!=0){
                    backPage();
                }else if(activeRow!=0){
                    activeRow=0;
                    selected=0;
                    rowSpring.target(0);
                    scroll.target(0);
                    scheduleMotion();
                }else if(topFocus||selected!=0){
                    topFocus=false;
                    selected=0;
                    scroll.target(0);
                    syncHeaderTargets();
                    scheduleMotion();
                }else if(host.isDefaultHome()){
                    enterAmbient();
                }else{
                    host.finish();
                    return true;
                }
                invalidate();
                return true;
            }
            if(key==KeyEvent.KEYCODE_MENU){
                if(page==6)host.searchAllAppsDialog();
                else if(movingId==null)open(1);
                return true;
            }
            if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){
                int d=key==KeyEvent.KEYCODE_DPAD_LEFT?-1:1;
                if(edgeOpen&&page==0){
                    if(key==KeyEvent.KEYCODE_DPAD_RIGHT){
                        edgeOpen=false;
                        invalidate();
                    }
                    return true;
                }
                if(page==2||page==7||(page==1&&choice==13))change(d);
                else if(page==6){
                    allFilter=(allFilter+d+filterNames.length)%filterNames.length;
                    choice=0;
                    rebuildFilteredApps();
                }else if(page==0&&movingId!=null){
                    boolean isFav=activeRow==1&&!favorites.isEmpty();
                    ArrayList<App> list=isFav?favorites:apps;
                    int idx=Math.min(selected,list.size()-1);
                    int next=idx+d;
                    if(next>=0&&next<list.size()&&(isFav||list.get(next).pinned==list.get(idx).pinned)){
                        Collections.swap(list,idx,next);
                        selected=next;
                        scroll.target(targetScroll(selected));
                        scheduleMotion();
                    }
                }else if(page==0){
                    if(topFocus){
                        top=Math.max(0,Math.min(2,top+d));
                        syncHeaderTargets();
                    }else{
                        if(key==KeyEvent.KEYCODE_DPAD_LEFT&&selected==0&&event.getRepeatCount()==0){
                            edgeOpen=true;
                            edgeChoice=0;
                            invalidate();
                            return true;
                        }
                        ArrayList<App> list=activeRowApps();
                        if(!list.isEmpty()){
                            selected=Math.max(0,Math.min(list.size()-1,selected+d));
                            scroll.target(targetScroll(selected));
                            scheduleMotion();
                        }
                    }
                }
                invalidate();
                return true;
            }
            if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN){
                if(movingId!=null)return true;
                if(edgeOpen&&page==0){
                    edgeChoice=Math.max(0,Math.min(4,edgeChoice+(key==KeyEvent.KEYCODE_DPAD_UP?-1:1)));
                    invalidate();
                    return true;
                }
                if(page!=0){
                    choice=Math.max(0,Math.min(count()-1,choice+(key==KeyEvent.KEYCODE_DPAD_UP?-1:1)));
                }else if(key==KeyEvent.KEYCODE_DPAD_UP){
                    if(activeRow==2){
                        activeRow=!favorites.isEmpty()?1:0;
                        selected=Math.min(selected,Math.max(0,activeRowApps().size()-1));
                        rowSpring.target(activeRow);
                        scroll.target(targetScroll(selected));
                        scheduleMotion();
                    }else if(activeRow==1){
                        activeRow=0;
                        selected=Math.min(selected,Math.max(0,apps.size()-1));
                        rowSpring.target(0);
                        scroll.target(targetScroll(selected));
                        scheduleMotion();
                    }else{
                        topFocus=true;
                        syncHeaderTargets();
                    }
                }else{
                    if(topFocus){
                        topFocus=false;
                        syncHeaderTargets();
                    }else if(activeRow==0){
                        if(!favorites.isEmpty()){
                            activeRow=1;
                            selected=Math.min(selected,Math.max(0,favorites.size()-1));
                            rowSpring.target(1);
                            scroll.target(targetScroll(selected));
                            scheduleMotion();
                        }else if(!tvInputs.isEmpty()){
                            activeRow=2;
                            selected=Math.min(selected,Math.max(0,tvInputs.size()-1));
                            rowSpring.target(2);
                            scroll.target(targetScroll(selected));
                            scheduleMotion();
                        }
                    }else if(activeRow==1&&!tvInputs.isEmpty()){
                        activeRow=2;
                        selected=Math.min(selected,Math.max(0,tvInputs.size()-1));
                        rowSpring.target(2);
                        scroll.target(targetScroll(selected));
                        scheduleMotion();
                    }
                }
                previewChanged();
                return true;
            }
            return super.onKeyDown(key,event);
        }
        @Override public boolean onKeyLongPress(int key,KeyEvent event){
            if(isConfirmKey(key)&&!longPress&&page==0&&movingId==null&&!topFocus&&!activeRowApps().isEmpty()){
                longPress=true;
                open(3);
                return true;
            }
            return super.onKeyLongPress(key,event);
        }
        @Override public boolean onKeyUp(int key,KeyEvent event){
            if(wakeKey){
                wakeKey=false;
                return true;
            }
            if(isConfirmKey(key)){
                if(!longPress&&!event.isCanceled())activate();
                longPress=false;
                return true;
            }
            return super.onKeyUp(key,event);
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            if(event.getAction()!=MotionEvent.ACTION_UP)return true;
            boolean sleeping=ambient;
            wake();
            if(sleeping)return true;
            float s=Math.min(getWidth()/1920f,getHeight()/1080f),x=(event.getX()-(getWidth()-1920*s)/2)/s,y=(event.getY()-(getHeight()-1080*s)/2)/s;
            if(page>0){
                float left=page==6?320:(page==2||page==7||page==8?1290:1080);
                if(page==6&&y>=120&&y<=164){
                    if(x>=1560&&x<=1846){
                        host.searchAllAppsDialog();
                        return true;
                    }
                    float px=left+32;
                    for(int fi=0;fi<filterNames.length;fi++){
                        if(x>=px&&x<=px+112){
                            allFilter=fi;
                            choice=0;
                            rebuildFilteredApps();
                            invalidate();
                            return true;
                        }
                        px+=124f;
                    }
                }
                int listTop=page==6?174:(page==10?380:(page==3?158:155));
                int rowStep=page==6?108:110;
                int visibleRows=page==10?4:7;
                if(page==4||page==5){
                    backPage();
                }else if(x>left&&y>=listTop&&y<listTop+visibleRows*rowStep){
                    int start=Math.max(0,Math.min(choice-2,count()-visibleRows));
                    choice=Math.min(count()-1,start+(int)((y-listTop)/rowStep));
                    if(page==2&&choice<LiteSettings.KEYS.length&&y-(listTop+(choice-start)*rowStep)>55){
                        float ratio=Math.max(0,Math.min(1,(x-1332)/493));
                        writeDraft(draft,choice,Math.round(LiteSettings.MIN[choice]+ratio*(LiteSettings.MAX[choice]-LiteSettings.MIN[choice])));
                        previewChanged();
                    }else activate();
                }
                invalidate();
                return true;
            }
            if(movingId!=null){
                finishMove();
                return true;
            }
            if(y<175){
                if(x<355)open(1);
                else if(x<665)open(2);
                else if(x<910)open(6);
                return true;
            }
            SharedPreferences a=appearance();
            float baseH=LiteSettings.height(a.getInt("card_height",100)),cy=LiteSettings.center(a.getInt("card_row_position",75),a.getInt("card_height",100));
            ArrayList<App> list=activeRowApps();
            if(Math.abs(y-cy)<baseH*0.8f+40&&!list.isEmpty()){
                computeCardLayout(a,list);
                float worldX=x+scroll.value;
                int at=-1;
                for(int i=0;i<list.size();i++){
                    if(Math.abs(worldX-cardCenters[i])<=cardWidths[i]/2f+LiteSettings.CARD_GAP/2f){
                        at=i;
                        break;
                    }
                }
                if(at>=0&&at<list.size()){
                    if(at==selected&&!topFocus)host.launch(list.get(at));
                    else{
                        topFocus=false;
                        selected=at;
                        syncHeaderTargets();
                        scroll.target(targetScroll(at));
                        scheduleMotion();
                    }
                }
            }
            return true;
        }
    }
}
