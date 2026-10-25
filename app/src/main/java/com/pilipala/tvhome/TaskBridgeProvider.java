// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;

/** Shell-only Binder handoff. No files, task data, or commands are exposed by this provider. */
public final class TaskBridgeProvider extends ContentProvider {
    private final Binder lifetime=new Binder();
    @Override public boolean onCreate(){return true;}
    @Override public Bundle call(String method,String arg,Bundle extras){
        if(Binder.getCallingUid()!=2000||!"connect".equals(method)||extras==null)throw new SecurityException("shell setup only");
        IBinder service=extras.getBinder("service");if(service==null)throw new IllegalArgumentException("missing service");
        TaskBridgeClient.attach(service);TaskBridgeClient.sync(getContext());Bundle reply=new Bundle();reply.putBinder("lifetime",lifetime);return reply;
    }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String sort){throw new UnsupportedOperationException();}
    @Override public String getType(Uri uri){return null;}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
