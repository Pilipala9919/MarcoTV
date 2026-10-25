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
    private final int appUid,user;
    private final Object manager,power;
    private final Class<?> api,powerApi;
    private volatile boolean wakeRecovery;
    private volatile String originalHome="",lastWake="尚无唤醒记录";
    private long lastWakeTime;
    private volatile boolean connected;
    private final Binder binder=new Binder(){@Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
        if(code==INTERFACE_TRANSACTION){reply.writeString(DESCRIPTOR);return true;}
        if(code!=FIRST_CALL_TRANSACTION)return super.onTransact(code,data,reply,flags);
        if(Binder.getCallingUid()!=appUid)throw new SecurityException("Marco TV only");data.enforceInterface(DESCRIPTOR);String json=data.readString();if(json==null||json.length()>4096)throw new SecurityException("request limit");
        long identity=Binder.clearCallingIdentity();try{JSONObject answer;try{answer=handle(new JSONObject(json));}catch(Exception e){answer=new JSONObject();try{answer.put("ok",false).put("error","系统暂不支持此操作或任务已结束");}catch(JSONException impossible){}}
            reply.writeNoException();reply.writeString(answer.toString());return true;
        }finally{Binder.restoreCallingIdentity(identity);}
    }};
    private TaskBridgeServer(int uid,int userId)throws Exception{
        appUid=uid;user=userId;
        String service=Build.VERSION.SDK_INT>=29?"android.app.ActivityTaskManager":"android.app.ActivityManager";
        manager=Class.forName(service).getDeclaredMethod("getService").invoke(null);
        api=Class.forName(Build.VERSION.SDK_INT>=29?"android.app.IActivityTaskManager":"android.app.IActivityManager");
        Object binder=Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"power");
        power=Class.forName("android.os.IPowerManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);powerApi=Class.forName("android.os.IPowerManager");
    }
    private boolean interactive(){try{return (Boolean)powerApi.getMethod("isInteractive").invoke(power);}catch(Exception e){return false;}}
    private boolean canRemove(){try{Object am=Class.forName("android.app.ActivityManager").getDeclaredMethod("getService").invoke(null);Class<?> cls=Class.forName("android.app.IActivityManager");String permission=Build.VERSION.SDK_INT>=29?"android.permission.MANAGE_ACTIVITY_TASKS":"android.permission.REMOVE_TASKS";return ((Integer)cls.getMethod("checkPermission",String.class,int.class,int.class).invoke(am,permission,android.os.Process.myPid(),android.os.Process.myUid()))==0;}catch(Exception e){return false;}}
    private boolean canRemoveSingleStack(){if(Build.VERSION.SDK_INT!=28)return false;try{return ((Integer)api.getMethod("checkPermission",String.class,int.class,int.class).invoke(manager,"android.permission.MANAGE_ACTIVITY_STACKS",android.os.Process.myPid(),android.os.Process.myUid()))==0;}catch(Exception e){return false;}}
    /** Android 9 fallback only: never remove a shared, pinned, home, or other-user stack. */
    private int singleTaskStack(int id)throws Exception{
        if(!canRemoveSingleStack())return -1;
        for(Object stack:(List<?>)api.getMethod("getAllStackInfos").invoke(manager)){
            Object ids=field(stack,"taskIds");if(!(ids instanceof int[])||((int[])ids).length!=1||((int[])ids)[0]!=id||number(stack,"userId",-1)!=user)continue;
            Object config=field(stack,"configuration"),window=field(config,"windowConfiguration");if(window==null)continue;
            int type=(Integer)window.getClass().getMethod("getActivityType").invoke(window),mode=(Integer)window.getClass().getMethod("getWindowingMode").invoke(window);
            if(type==1&&mode==1)return number(stack,"stackId",-1);
        }return -1;
    }
    private List<?> tasks()throws Exception{Object slice=api.getMethod("getRecentTasks",int.class,int.class,int.class).invoke(manager,60,0,user);return (List<?>)slice.getClass().getMethod("getList").invoke(slice);}
    private static Object field(Object object,String name){try{return object.getClass().getField(name).get(object);}catch(Exception e){return null;}}
    private static int number(Object object,String name,int fallback){Object n=field(object,name);return n instanceof Number?((Number)n).intValue():fallback;}
    private boolean excluded(Object task){Intent intent=(Intent)field(task,"baseIntent");String pkg=packageName(task);return pkg.isEmpty()||pkg.equals("com.pilipala.tvhome")||pkg.equals("com.android.systemui")||pkg.equals("android")||pkg.equals(originalHome)||pkg.equals("com.xiaomi.eetv.litehome")||(intent!=null&&intent.hasCategory(Intent.CATEGORY_HOME));}
    private String packageName(Object task){Object component=field(task,"topActivity");if(!(component instanceof ComponentName))component=field(task,"baseActivity");if(component instanceof ComponentName)return ((ComponentName)component).getPackageName();Intent intent=(Intent)field(task,"baseIntent");return intent!=null&&intent.getComponent()!=null?intent.getComponent().getPackageName():"";}
    private Object task(int id)throws Exception{for(Object task:tasks())if(number(task,"persistentId",number(task,"taskId",-1))==id&&!excluded(task)&&number(task,"userId",user)==user)return task;throw new IOException("task unavailable");}
    private JSONObject list()throws Exception{JSONArray rows=new JSONArray();for(Object task:tasks()){if(excluded(task)||number(task,"userId",user)!=user)continue;int id=number(task,"persistentId",number(task,"taskId",-1));if(id<0)continue;
        rows.put(new JSONObject().put("id",id).put("package",packageName(task)).put("removable",canRemove()||singleTaskStack(id)>=0));}return new JSONObject().put("tasks",rows);}
    private JSONObject snapshot(int id)throws Exception{
        task(id);Object snapshot=null;
        for(Method method:api.getMethods())if(method.getName().equals("getTaskSnapshot")){Class<?>[] types=method.getParameterTypes();
            if(types.length==2&&types[0]==int.class&&types[1]==boolean.class){snapshot=method.invoke(manager,id,false);break;}
            if(types.length==3&&types[0]==int.class&&types[1]==boolean.class&&types[2]==boolean.class){snapshot=method.invoke(manager,id,false,false);break;}
        }
        if(snapshot==null)return new JSONObject().put("available",false);
        // Android 9 GraphicBuffer conversion. Other releases safely use an artwork fallback.
        if(Build.VERSION.SDK_INT!=28)return new JSONObject().put("available",false);
        Object buffer=snapshot.getClass().getMethod("getSnapshot").invoke(snapshot);Bitmap hardware=null,small=null;
        try{for(Method method:Bitmap.class.getDeclaredMethods())if(method.getName().equals("createHardwareBitmap")&&method.getParameterTypes().length==1){method.setAccessible(true);hardware=(Bitmap)method.invoke(null,buffer);break;}
            if(hardware==null)return new JSONObject().put("available",false);
            Bitmap copy=hardware.copy(Bitmap.Config.ARGB_8888,false);float ratio=Math.min(1,640f/copy.getWidth());small=Bitmap.createScaledBitmap(copy,Math.max(1,(int)(copy.getWidth()*ratio)),Math.max(1,(int)(copy.getHeight()*ratio)),true);if(small!=copy)copy.recycle();
            ByteArrayOutputStream out=new ByteArrayOutputStream();small.compress(Bitmap.CompressFormat.JPEG,78,out);if(out.size()>300000)return new JSONObject().put("available",false);
            return new JSONObject().put("available",true).put("image",android.util.Base64.encodeToString(out.toByteArray(),android.util.Base64.NO_WRAP));
        }finally{if(small!=null)small.recycle();if(hardware!=null)hardware.recycle();}
    }
    private JSONObject handle(JSONObject request)throws Exception{
        String action=request.getString("action");JSONObject answer;
        if(action.equals("status"))answer=new JSONObject().put("version",ProjectInfo.VERSION).put("wake",wakeRecovery).put("canRemove",canRemove()||canRemoveSingleStack()).put("removeMode",canRemove()?"task":canRemoveSingleStack()?"single_task_stack":"unavailable").put("lastWake",lastWake).put("lastWakeTime",lastWakeTime);
        else if(action.equals("configure")){wakeRecovery=request.optBoolean("wake",false);String home=request.optString("home","");originalHome=home.matches("[A-Za-z0-9_.]{1,200}")?home:"";answer=new JSONObject();}
        else if(action.equals("list"))answer=list();
        else if(action.equals("snapshot"))answer=snapshot(request.getInt("id"));
        else if(action.equals("switch")){int id=request.getInt("id");task(id);if(!interactive())throw new IOException("screen off");api.getMethod("startActivityFromRecents",int.class,Bundle.class).invoke(manager,id,null);answer=new JSONObject();}
        else if(action.equals("remove")){int id=request.getInt("id");task(id);if(canRemove()){boolean removed=(Boolean)api.getMethod("removeTask",int.class).invoke(manager,id);if(!removed)throw new IOException("remove rejected");}
            else{int stack=singleTaskStack(id);if(stack<0||singleTaskStack(id)!=stack)throw new SecurityException("single task stack unavailable");api.getMethod("removeStack",int.class).invoke(manager,stack);}answer=new JSONObject();}
        else throw new IOException("unsupported operation");
        return answer.put("ok",true);
    }
    private void publish(){Object am=null;Class<?> amApi=null;Binder token=new Binder();String authority="com.pilipala.tvhome.taskbridge";
        try{am=Class.forName("android.app.ActivityManager").getDeclaredMethod("getService").invoke(null);amApi=Class.forName("android.app.IActivityManager");Object holder=null;
            for(Method method:amApi.getMethods())if(method.getName().equals("getContentProviderExternal")){if(method.getParameterTypes().length==3)holder=method.invoke(am,authority,user,token);else if(method.getParameterTypes().length==4)holder=method.invoke(am,authority,user,token,"MarcoTV");if(holder!=null)break;}
            if(holder==null)throw new IOException("provider unavailable");Object provider=holder.getClass().getField("provider").get(holder);Class<?> providerApi=Class.forName("android.content.IContentProvider");Bundle extras=new Bundle();extras.putBinder("service",binder);Bundle reply=null;
            for(Method method:providerApi.getMethods())if(method.getName().equals("call")){Class<?>[] types=method.getParameterTypes();
                if(types.length==4&&types[0]==String.class)reply=(Bundle)method.invoke(provider,"com.android.shell","connect",null,extras);
                else if(types.length==5&&types[0]==String.class)reply=(Bundle)method.invoke(provider,"com.android.shell",authority,"connect",null,extras);
                if(reply!=null)break;
            }
            IBinder lifetime=reply==null?null:reply.getBinder("lifetime");if(lifetime!=null){connected=true;lifetime.linkToDeath(()->connected=false,0);}
        }catch(Exception e){connected=false;android.util.Log.w("MarcoTVBridge","Binder handoff unavailable",e);}
        finally{if(am!=null)try{amApi.getMethod("removeContentProviderExternal",String.class,IBinder.class).invoke(am,authority,token);}catch(Exception ignored){}}
    }
    private String foregroundPackage()throws Exception{
        for(Method method:api.getMethods())if(method.getName().equals("getTasks")){Class<?>[] types=method.getParameterTypes();Object[] args=new Object[types.length];boolean known=true;
            for(int i=0;i<types.length;i++){if(types[i]==int.class)args[i]=i==0?1:-1;else if(types[i]==boolean.class)args[i]=false;else{known=false;break;}}
            if(!known)continue;Object result=method.invoke(manager,args);if(result instanceof List&&!((List<?>)result).isEmpty())return packageName(((List<?>)result).get(0));
        }return "";
    }
    private void watchWake(){Thread thread=new Thread(()->{boolean was=interactive();long ready=0,lastPublish=0;while(true){try{Thread.sleep(500);boolean on=interactive();long now=SystemClock.elapsedRealtime();if(!connected&&now-lastPublish>5000){publish();lastPublish=now;}if(on&&!was)ready=now+1200;if(!on)ready=0;was=on;
        if(ready==0||SystemClock.elapsedRealtime()<ready)continue;ready=0;if(!wakeRecovery||originalHome.isEmpty())continue;String foreground=foregroundPackage();
        if(foreground.equals(originalHome)||(originalHome.equals("com.mitv.tvhome")&&foreground.equals("com.xiaomi.eetv.litehome"))){
            java.lang.Process process=new ProcessBuilder("/system/bin/am","start","-n","com.pilipala.tvhome/.MainActivity","--ez","wake_recovery","true").redirectErrorStream(true).start();
            try(InputStream in=process.getInputStream()){byte[] b=new byte[1024];while(in.read(b)!=-1){}}int result=process.waitFor();lastWake=result==0?"已从原桌面恢复":"系统未允许恢复";
        }else lastWake="保留当前应用";lastWakeTime=System.currentTimeMillis();
    }catch(Exception e){lastWake="恢复检查失败";lastWakeTime=System.currentTimeMillis();}}},"MarcoTV-wake");thread.setDaemon(true);thread.start();}
    public static void main(String[] args)throws Exception{
        if(android.os.Process.myUid()!=2000||args.length!=2)throw new SecurityException("requires shell setup");
        Looper.prepareMainLooper();
        try(RandomAccessFile lock=new RandomAccessFile("/data/local/tmp/marcotv-task-bridge.lock","rw");FileLock held=lock.getChannel().tryLock()){
            if(held==null)return;TaskBridgeServer server=new TaskBridgeServer(Integer.parseInt(args[0]),Integer.parseInt(args[1]));
            server.publish();server.watchWake();Looper.loop();
        }
    }
}
