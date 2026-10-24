// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import java.io.*;

final class WallpaperSource {
    static final String[] TITLES={"雾蓝海岸","暖沙丘","极夜山脊","旧版峡谷"};
    static final String[] ASSETS={"wallpapers/coast.png","wallpapers/dunes.png","wallpapers/aurora.png","canyon.png"};
    static Bitmap load(Context context){
        SharedPreferences p=context.getSharedPreferences("home",0);
        int maxWidth=cap(context)[0], maxHeight=cap(context)[1];
        Bitmap.Config leanConfig=ArtworkCache.lean(context)?Bitmap.Config.RGB_565:Bitmap.Config.ARGB_8888;
        String uri=p.getString("wallpaper","");
        if(!uri.isEmpty())try {
            BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;
            try(InputStream in=context.getContentResolver().openInputStream(Uri.parse(uri))){BitmapFactory.decodeStream(in,null,o);}
            o.inSampleSize=1;while(o.outWidth/o.inSampleSize>maxWidth||o.outHeight/o.inSampleSize>maxHeight)o.inSampleSize*=2;
            o.inJustDecodeBounds=false;o.inPreferredConfig=leanConfig;
            try(InputStream in=context.getContentResolver().openInputStream(Uri.parse(uri))){Bitmap b=BitmapFactory.decodeStream(in,null,o);if(b!=null)return b;}
        }catch(Exception ignored){}
        int index=Math.max(0,Math.min(ASSETS.length-1,p.getInt("wallpaper_builtin",0)));
        try {
            BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;
            try(InputStream in=context.getAssets().open(ASSETS[index])){BitmapFactory.decodeStream(in,null,o);}
            o.inSampleSize=1;while(o.outWidth/o.inSampleSize>maxWidth||o.outHeight/o.inSampleSize>maxHeight)o.inSampleSize*=2;
            o.inJustDecodeBounds=false;o.inPreferredConfig=leanConfig;
            try(InputStream in=context.getAssets().open(ASSETS[index])){return BitmapFactory.decodeStream(in,null,o);}
        }catch(IOException ignored){}
        return null;
    }

    /** Wallpapers are drawn full screen, so anything above the real panel size is wasted memory. */
    private static int[] cap(Context context){
        WindowManager manager=(WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics=new DisplayMetrics();
        if(manager!=null&&manager.getDefaultDisplay()!=null)manager.getDefaultDisplay().getRealMetrics(metrics);
        return new int[]{Math.max(metrics.widthPixels,1280),Math.max(metrics.heightPixels,720)};
    }
}
