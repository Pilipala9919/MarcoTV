// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.*;
import android.content.pm.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Separate network queue; installs only an official same-signed newer APK after explicit user action. */
final class UpdateManager {
    static final class Release {
        final int code,minSdk;final long size;final String version,url,sha,notes;final boolean preview;
        Release(JSONObject o,String channel)throws Exception{
            if(o.getInt("schema")!=1)throw new IOException("Unsupported feed");
            code=o.getInt("versionCode");minSdk=o.getInt("minSdk");size=o.getLong("size");version=o.getString("versionName");
            url=o.getString("url");sha=o.getString("sha256");preview=o.getBoolean("prerelease");notes=o.optString("notes","");
            UpdatePolicy.validate(code,version,minSdk,size,sha,url,preview,channel);if(notes.length()>8000)throw new IOException("Notes too long");
        }
    }
    private static UpdateManager instance;
    static synchronized UpdateManager get(Context c){if(instance==null)instance=new UpdateManager(c.getApplicationContext());return instance;}
    final Context context;final SharedPreferences prefs;
    final ExecutorService worker=Executors.newSingleThreadExecutor();final Handler main=new Handler(Looper.getMainLooper());
    volatile Release available;volatile String message="尚未检查更新";volatile boolean busy;volatile int percent=-1;
    private volatile boolean cancel;private volatile HttpURLConnection activeConnection;private Runnable listener;private long noticedCode;
    boolean cancelled(){return cancel;}
    void resetCancellation(){cancel=false;}
    private UpdateManager(Context c){context=c;prefs=c.getSharedPreferences("updates",0);
        int code=installedCode(c),old=prefs.getInt("installed_code",code);
        if(code>old){prefs.edit().putString("result","已升级至 "+ProjectInfo.VERSION+"。"+bridgeNote()).remove("download_sha").remove("pending_code").apply();apk().delete();}
        else if(prefs.contains("pending_code"))prefs.edit().putString("result","上次安装未完成，当前版本仍可使用。可重新选择安装。").remove("pending_code").apply();
        prefs.edit().putInt("installed_code",code).apply();
        try{available=new Release(new JSONObject(prefs.getString("metadata","")),channel());}catch(Exception ignored){}
        if(available!=null&&(available.code<=code||available.minSdk>Build.VERSION.SDK_INT))available=null;
        message=prefs.getString("result","尚未检查更新");
    }
    static int installedCode(Context c){try{return c.getPackageManager().getPackageInfo(c.getPackageName(),0).versionCode;}catch(Exception e){return Integer.MAX_VALUE;}}
    String channel(){return prefs.getString("channel","stable");}
    String mode(){return prefs.getString("mode","notify");}
    String bridgeNote(){return "若使用最近任务或唤醒恢复，请到状态中心检查任务辅助服务；本次 APK 更新后仍需电脑重新启动该服务。";}
    synchronized void listen(Runnable callback){listener=callback;}
    private void changed(){main.post(()->{Runnable callback; synchronized(this){callback=listener;}if(callback!=null)callback.run();});}
    File folder(){File f=new File(context.getCacheDir(),"updates");f.mkdirs();return f;}
    File apk(){return new File(folder(),"release.apk");}
    boolean ready(){Release r=available;return r!=null&&r.code>installedCode(context)&&apk().isFile()&&apk().length()==r.size&&r.sha.equals(prefs.getString("download_sha",""));}
    boolean consumeNotice(){Release r=available;if(r==null||r.code<=installedCode(context)||noticedCode==r.code||"off".equals(mode()))return false;noticedCode=r.code;return true;}
    void setMode(String value){if(!Arrays.asList("notify","download","off").contains(value))return;prefs.edit().putString("mode",value).apply();if("off".equals(value))cancel=true;changed();}
    synchronized boolean setChannel(String value){if(busy||!Arrays.asList("stable","preview").contains(value))return false;
        prefs.edit().putString("channel",value).remove("metadata").remove("download_sha").remove("last_attempt").apply();available=null;apk().delete();new File(folder(),"release.part").delete();prefs.edit().remove("partial_sha").apply();noticedCode=0;message="渠道已切换，请检查更新";changed();return true;}
    void automatic(){if(!"off".equals(mode())&&System.currentTimeMillis()-prefs.getLong("last_attempt",0)>=6*60*60*1000L)check(false);}
    synchronized void check(boolean manual){if(busy)return;busy=true;cancel=false;message="正在检查更新…";percent=-1;changed();
        final String channel=channel();prefs.edit().putLong("last_attempt",System.currentTimeMillis()).apply();
        worker.execute(()->{try{
            JSONObject data=readRelease(channel);Release r=new Release(data,channel);
            if(cancel)throw new InterruptedIOException();
            if(!UpdatePolicy.newer(r.code,installedCode(context))){clearOffer();message="当前渠道暂无更高版本（本机 "+ProjectInfo.VERSION+"）";}
            else if(r.minSdk>Build.VERSION.SDK_INT){clearOffer();message="发现新版，但其系统版本要求不适合本机，已停止下载";}
            else{available=r;message="发现新版 "+r.version;prefs.edit().putString("metadata",data.toString()).apply();
                if("download".equals(mode())&&!ready())downloadFile(r);}
        }catch(InterruptedIOException e){message=cancel?"已取消，当前版本不受影响":"网络连接超时；若已有下载进度，再次下载可继续";}
        catch(Exception e){message="检查或下载失败，请检查网络后重试；现有版本仍可使用";Diagnostics.event("UPDATE_CHECK_FAILED");}
        finally{busy=false;percent=-1;changed();}});
    }
    synchronized void download(){if(busy||available==null)return;Release r=available;if(r.minSdk>Build.VERSION.SDK_INT||r.code<=installedCode(context))return;
        busy=true;cancel=false;worker.execute(()->{try{downloadFile(r);}catch(InterruptedIOException e){message=cancel?"下载已取消":"下载超时，已保留进度，可再次下载继续";}catch(Exception e){message="下载或校验失败，未安装。请重试或从官方发布页下载";Diagnostics.event("UPDATE_DOWNLOAD_FAILED");}finally{busy=false;percent=-1;changed();}});
    }
    private void clearOffer(){available=null;prefs.edit().remove("metadata").remove("download_sha").apply();apk().delete();}
    void cancel(){cancel=true;HttpURLConnection active=activeConnection;if(active!=null)workerCancel(active);message="正在取消…";changed();}
    private void workerCancel(HttpURLConnection active){new Thread(active::disconnect,"MarcoTV-cancel-update").start();}
    private void downloadFile(Release r)throws Exception{
        if(folder().getUsableSpace()<r.size+16*1024*1024L)throw new IOException("space");
        File part=new File(folder(),"release.part");
        if(!r.sha.equals(prefs.getString("partial_sha",""))||part.length()>r.size){part.delete();prefs.edit().putString("partial_sha",r.sha).commit();}
        percent=(int)(part.length()*100/r.size);boolean verified=false;
        try{
            Exception failure=null;boolean valid=false;
            String[] sources=UpdatePolicy.downloads(r.version);
            for(int source=0;source<sources.length&&!valid;source++){
                for(int attempt=0;attempt<2&&part.length()<r.size;attempt++){
                    if(cancel)throw new InterruptedIOException();
                    message=(source==0?"正在从镜像站下载 ":"镜像暂不可用，改从 GitHub 下载 ")+r.version;changed();
                    try{transfer(r,part,sources[source]);failure=null;}catch(IOException e){failure=e;if(cancel)throw new InterruptedIOException();}
                }
                if(part.length()==r.size){
                    try{verifyApk(context,part,r);valid=true;}catch(Exception e){failure=e;if(!part.delete())throw new IOException("discard invalid APK",e);}
                }
            }
            if(!valid){if(failure!=null)throw failure;throw new IOException("short download");}
            if(cancel)throw new InterruptedIOException();
            if(apk().exists()&&!apk().delete())throw new IOException("replace");if(!part.renameTo(apk()))throw new IOException("rename");
            prefs.edit().putString("download_sha",r.sha).remove("partial_sha").apply();message="新版已下载并校验，选择“安装已下载版本”继续";verified=true;
        }finally{if(cancel){part.delete();prefs.edit().remove("partial_sha").apply();}if(!verified&&!cancel&&part.exists())message="下载中断，已保留进度，可再次下载继续";}
    }
    private void transfer(Release r,File part,String address)throws Exception{
        long offset=part.length();HttpURLConnection conn=open(address,offset);activeConnection=conn;
        try{
            long start=UpdatePolicy.resumeOffset(offset,conn.getResponseCode(),conn.getHeaderField("Content-Range"),r.size);
            long declared=conn.getContentLength();if(declared>0&&declared!=r.size-start)throw new IOException("size");
            try(InputStream in=conn.getInputStream();OutputStream out=new FileOutputStream(part,start>0)){
                byte[] bytes=new byte[32768];long total=start;int n,last=-1;
                while((n=in.read(bytes))!=-1){if(cancel)throw new InterruptedIOException();total+=n;if(total>r.size)throw new IOException("limit");out.write(bytes,0,n);
                    int now=(int)(total*100/r.size);if(now!=last){last=now;percent=now;changed();}}
                if(total!=r.size)throw new IOException("short download");
            }
        }finally{activeConnection=null;conn.disconnect();}
    }
    static void verifyApk(Context c,File file,Release r)throws Exception{
        if(file.length()!=r.size||!sha(file).equals(r.sha))throw new IOException("digest");
        PackageManager pm=c.getPackageManager();PackageInfo next=pm.getPackageArchiveInfo(file.getAbsolutePath(),PackageManager.GET_SIGNATURES);
        PackageInfo current=pm.getPackageInfo(c.getPackageName(),PackageManager.GET_SIGNATURES);
        if(next==null||!c.getPackageName().equals(next.packageName)||next.versionCode!=r.code||next.versionCode<=current.versionCode
            ||!r.version.equals(next.versionName)||next.applicationInfo==null||next.applicationInfo.minSdkVersion>Build.VERSION.SDK_INT)throw new IOException("package identity or compatibility");
        Set<String> a=signatures(current.signatures),b=signatures(next.signatures);if(a.isEmpty()||!a.equals(b))throw new IOException("signer");
    }
    private static Set<String> signatures(android.content.pm.Signature[] signatures){Set<String> result=new HashSet<>();if(signatures!=null)for(android.content.pm.Signature s:signatures)result.add(s.toCharsString());return result;}
    static String sha(File file)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}StringBuilder out=new StringBuilder();for(byte b:digest.digest())out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    static HttpURLConnection open(String address)throws Exception{return open(address,0);}
    private static HttpURLConnection open(String address,long offset)throws Exception{
        for(int hops=0;hops<6;hops++){
            if(!UpdatePolicy.transferUrl(address))throw new IOException("untrusted host");
            HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(12000);c.setReadTimeout(45000);
            if(offset>0)c.setRequestProperty("Range","bytes="+offset+"-");
            c.setRequestProperty("User-Agent","MarcoTV-Updater");c.setRequestProperty("Accept-Encoding","identity");c.setUseCaches(false);
            int status;try{status=c.getResponseCode();}catch(Exception e){c.disconnect();throw e;}
            if(status==200||(offset>0&&status==206))return c;
            if(status==301||status==302||status==303||status==307||status==308){String next=c.getHeaderField("Location");c.disconnect();if(next==null)throw new IOException("redirect");address=new URI(address).resolve(next).toString();}
            else{c.disconnect();throw new IOException("HTTP "+status);}
        }throw new IOException("redirect limit");
    }
    private JSONObject readRelease(String channel)throws Exception{
        Exception failure=null;
        for(String address:UpdatePolicy.feeds(channel)){
            if(cancel)throw new InterruptedIOException();
            try{JSONObject data=new JSONObject(new String(readFeed(address),"UTF-8"));new Release(data,channel);return data;}
            catch(Exception e){failure=e;if(cancel)throw new InterruptedIOException();}
        }
        throw failure==null?new IOException("no update source"):failure;
    }
    private byte[] readFeed(String address)throws Exception{HttpURLConnection c=open(address);activeConnection=c;try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
        byte[] bytes=new byte[4096];int n;while((n=in.read(bytes))!=-1){if(out.size()+n>32768)throw new IOException("feed limit");out.write(bytes,0,n);}return out.toByteArray();
    }finally{activeConnection=null;c.disconnect();}}
}
