// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import java.io.*;

final class WallpaperSource {
    static final String[] TITLES={"雾蓝海岸","暖沙丘","极夜山脊","旧版峡谷"};
    static final String[] ASSETS={"wallpapers/coast.png","wallpapers/dunes.png","wallpapers/aurora.png","canyon.png"};
    static Bitmap load(Context context){
        SharedPreferences p=context.getSharedPreferences("home",0);
        String uri=p.getString("wallpaper","");
        if(!uri.isEmpty())try {
            BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;
            try(InputStream in=context.getContentResolver().openInputStream(Uri.parse(uri))){BitmapFactory.decodeStream(in,null,o);}
            int sample=1;while(o.outWidth/sample>2560||o.outHeight/sample>1440)sample*=2;
            o.inSampleSize=sample;o.inJustDecodeBounds=false;o.inPreferredConfig=Bitmap.Config.ARGB_8888;
            try(InputStream in=context.getContentResolver().openInputStream(Uri.parse(uri))){Bitmap b=BitmapFactory.decodeStream(in,null,o);if(b!=null)return b;}
        }catch(Exception ignored){}
        int index=Math.max(0,Math.min(ASSETS.length-1,p.getInt("wallpaper_builtin",0)));
        try(InputStream in=context.getAssets().open(ASSETS[index])){return BitmapFactory.decodeStream(in);}catch(IOException e){return null;}
    }
}
