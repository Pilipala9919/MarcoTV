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

/** Grants the system installer read access to exactly one verified cache file, never arbitrary paths. */
public final class UpdateApkProvider extends ContentProvider {
    static final String AUTHORITY="com.pilipala.tvhome.updates";
    static final Uri URI=Uri.parse("content://"+AUTHORITY+"/release.apk");
    public boolean onCreate(){return true;}
    private File file(Uri uri){if(!URI.equals(uri))throw new IllegalArgumentException("Unknown update URI");return new File(getContext().getCacheDir(),"updates/release.apk");}
    @Override public String getType(Uri uri){file(uri);return "application/vnd.android.package-archive";}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
        if(!"r".equals(mode))throw new FileNotFoundException("Read only");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){File file=file(uri);
        String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
        MatrixCursor cursor=new MatrixCursor(columns);Object[] values=new Object[columns.length];
        for(int i=0;i<columns.length;i++)values[i]=OpenableColumns.DISPLAY_NAME.equals(columns[i])?"MarcoTV-update.apk":OpenableColumns.SIZE.equals(columns[i])?file.length():null;
        cursor.addRow(values);return cursor;
    }
    @Override public Uri insert(Uri uri,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
