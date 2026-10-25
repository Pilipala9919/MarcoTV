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
    static void attach(IBinder binder){service=binder;try{binder.linkToDeath(()->{if(service==binder)service=null;},0);}catch(RemoteException e){service=null;}}
    private static final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
    static void sync(Context c){Context app=c.getApplicationContext();worker.execute(()->{try{configure(app);}catch(Exception ignored){}});}
    static JSONObject call(JSONObject request)throws Exception{
        IBinder binder=service;if(binder==null||!binder.isBinderAlive())throw new IOException("任务服务未连接");
        Parcel data=Parcel.obtain(),result=Parcel.obtain();try{data.writeInterfaceToken(TaskBridgeServer.DESCRIPTOR);data.writeString(request.toString());
            if(!binder.transact(IBinder.FIRST_CALL_TRANSACTION,data,result,0))throw new IOException("unsupported bridge");result.readException();String json=result.readString();if(json==null||json.length()>500000)throw new IOException("response limit");
            JSONObject reply=new JSONObject(json);if(!reply.optBoolean("ok"))throw new IOException(reply.optString("error","桥接请求失败"));return reply;
        }finally{data.recycle();result.recycle();}
    }
    static JSONObject request(String action)throws Exception{return call(new JSONObject().put("action",action));}
    static JSONObject task(String action,int id)throws Exception{return call(new JSONObject().put("action",action).put("id",id));}
    static JSONObject configure(Context context)throws Exception{
        ComponentName original=ComponentName.unflattenFromString(SystemIntegration.prefs(context).getString("original_home",""));
        boolean enabled=SystemIntegration.prefs(context).getBoolean("home_compat",false)&&context.getSharedPreferences("home",0).getBoolean("recent_wake",true);
        call(new JSONObject().put("action","configure").put("wake",enabled).put("home",original==null?"":original.getPackageName()));return request("status");
    }
}
