// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Grants read access to strictly enumerated single files (verified update APK or active local cast media), never arbitrary paths. */
public final class UpdateApkProvider extends ContentProvider {
    static final String AUTHORITY="com.pilipala.tvhome.updates";
    static final Uri URI=Uri.parse("content://"+AUTHORITY+"/release.apk");
    static final Uri CAST_URI=Uri.parse("content://"+AUTHORITY+"/cast-media");
    private static volatile File activeCastFile;
    static Uri stageCastFile(Context context,File file){
        if(file==null){activeCastFile=null;return null;}
        try{
            String cacheRoot=context.getCacheDir().getCanonicalPath();
            String target=file.getCanonicalPath();
            if(!target.startsWith(cacheRoot+File.separator)||!file.isFile())throw new SecurityException("Invalid cast cache file");
            activeCastFile=file;
            return CAST_URI;
        }catch(IOException e){
            throw new SecurityException("Invalid cast cache file");
        }
    }
    static void clearCastFile(){
        activeCastFile=null;
    }
    public boolean onCreate(){
        return true;
    }
    private File file(Uri uri){
        if(URI.equals(uri))return new File(getContext().getCacheDir(),"updates/release.apk");
        if(CAST_URI.equals(uri)){
            File f=activeCastFile;
            if(f==null||!f.isFile())throw new IllegalArgumentException("No active cast media");
            return f;
        }
        throw new IllegalArgumentException("Unknown update URI");
    }
    @Override public String getType(Uri uri){
        file(uri);
        if(CAST_URI.equals(uri))return "video/*";
        return "application/vnd.android.package-archive";
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
        if(!"r".equals(mode))throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){
        File file=file(uri);
        String displayName=CAST_URI.equals(uri)?file.getName():"MarcoTV-update.apk";
        String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
        MatrixCursor cursor=new MatrixCursor(columns);
        Object[] values=new Object[columns.length];
        for(int i=0;i<columns.length;i++)values[i]=OpenableColumns.DISPLAY_NAME.equals(columns[i])?displayName:OpenableColumns.SIZE.equals(columns[i])?file.length():null;
        cursor.addRow(values);
        return cursor;
    }
    @Override public Uri insert(Uri uri,ContentValues v){
        throw new UnsupportedOperationException();
    }
    @Override public int delete(Uri uri,String s,String[] a){
        throw new UnsupportedOperationException();
    }
    @Override public int update(Uri uri,ContentValues v,String s,String[] a){
        throw new UnsupportedOperationException();
    }
}
