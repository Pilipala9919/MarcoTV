// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Build;
import java.util.*;

/** Extend an icon's existing background while preserving its logo proportions. */
final class ArtworkStyle {
    static Bitmap card(Drawable icon) {
        Bitmap card=Bitmap.createBitmap(480,270,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(card);
        if(Build.VERSION.SDK_INT>=26 && icon instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable adaptive=(AdaptiveIconDrawable)icon;
            Drawable background=adaptive.getBackground(); background.setBounds(0,0,480,270);background.draw(canvas);
            Drawable foreground=adaptive.getForeground();foreground.setBounds(105,0,375,270);foreground.draw(canvas);
            return card;
        }
        Bitmap square=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888);
        icon.setBounds(0,0,192,192);icon.draw(new Canvas(square));
        canvas.drawColor(background(square));
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        // Clamp the original edge pixels outward. This also continues gradient
        // backgrounds instead of leaving a square seam inside a flat fill.
        BitmapShader extended=new BitmapShader(square,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
        Matrix matrix=new Matrix();matrix.setScale(188f/192,188f/192);matrix.postTranslate(146,41);
        extended.setLocalMatrix(matrix);paint.setShader(extended);canvas.drawRect(0,0,480,270,paint);
        paint.setShader(null);square.recycle();
        return card;
    }
    static int background(Bitmap image) {
        HashMap<Integer,Integer> buckets=new HashMap<>();HashMap<Integer,long[]> sums=new HashMap<>();
        int w=image.getWidth(),h=image.getHeight();
        for(int i=0;i<32;i++) for(int side=0;side<4;side++) {
            int x=side<2?Math.min(w-1,i*w/32):(side==2?w/12:w-1-w/12);
            int y=side>=2?Math.min(h-1,i*h/32):(side==0?h/12:h-1-h/12);
            int c=image.getPixel(x,y);if(Color.alpha(c)<220)continue;
            int key=((Color.red(c)>>4)<<8)|((Color.green(c)>>4)<<4)|(Color.blue(c)>>4);
            buckets.put(key,buckets.containsKey(key)?buckets.get(key)+1:1);
            long[] sum=sums.get(key);if(sum==null){sum=new long[3];sums.put(key,sum);}
            sum[0]+=Color.red(c);sum[1]+=Color.green(c);sum[2]+=Color.blue(c);
        }
        int best=-1,count=0;
        for(Map.Entry<Integer,Integer> e:buckets.entrySet())if(e.getValue()>count){best=e.getKey();count=e.getValue();}
        if(count<16)return 0xFF243541;
        long[] sum=sums.get(best);return Color.rgb((int)(sum[0]/count),(int)(sum[1]/count),(int)(sum[2]/count));
    }
}
