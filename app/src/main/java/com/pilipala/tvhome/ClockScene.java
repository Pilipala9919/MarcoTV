// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.SharedPreferences;
import android.graphics.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Shared desktop/dream clock: substantial numerals, fixed right/top anchor. */
final class ClockScene {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG|Paint.LINEAR_TEXT_FLAG);
    private final Typeface digits=Typeface.create("sans-serif",Typeface.BOLD);
    private final Rect glyphBounds=new Rect();
    private final Paint imagePaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final RectF clockRect=new RectF();
    private Bitmap clockBitmap;
    private String cachedTime="";
    private int bitmapGlyphHeight;
    private final Typeface medium=Typeface.create("sans-serif-medium",Typeface.NORMAL);
    private final SimpleDateFormat time=new SimpleDateFormat("HH:mm",Locale.ROOT);
    private final SimpleDateFormat date=new SimpleDateFormat("M月d日  EEEE",Locale.CHINA);
    float size(SharedPreferences prefs,float ambient){
        float small=prefs.getInt("clock_size",96),large=prefs.getInt("ambient_clock_size",176);
        return small+(Math.max(small,large)-small)*ambient;
    }
    float left(SharedPreferences prefs){p.setTypeface(digits);p.setTextSize(96);return 1798-p.measureText("00:00")*size(prefs,0)/96;}
    void draw(Canvas c,SharedPreferences prefs,float ambient){
        long now=System.currentTimeMillis();Date current=new Date(now);
        float size=size(prefs,ambient);
        float right=1798;
        // Cache high-resolution glyphs once per minute. Scaling a filtered bitmap
        // prevents GPU font hinting from changing glyph edges on every frame.
        String shown=time.format(current);
        if(clockBitmap==null||!shown.equals(cachedTime)){
            p.setTypeface(digits);p.setTextSize(280);p.setTextAlign(Paint.Align.RIGHT);
            p.getTextBounds("00:00",0,5,glyphBounds);bitmapGlyphHeight=glyphBounds.height();
            Bitmap next=Bitmap.createBitmap((int)Math.ceil(p.measureText(shown))+4,bitmapGlyphHeight+4,Bitmap.Config.ARGB_8888);
            p.setColor(Color.WHITE);new Canvas(next).drawText(shown,next.getWidth()-2,2-glyphBounds.top,p);
            clockBitmap=next;cachedTime=shown;
        }
        int glow=prefs.getInt("ambient_clock_brightness",100);
        int alpha=Math.round(160+(Math.round(255*glow/100f)-160)*ambient);
        float ratio=size/280;
        clockRect.set(right-(clockBitmap.getWidth()-2)*ratio,72-2*ratio,right+2*ratio,72+(clockBitmap.getHeight()-2)*ratio);
        imagePaint.setAlpha(alpha);c.drawBitmap(clockBitmap,null,clockRect,imagePaint);
        p.setTypeface(medium);p.setTextSize(24);p.setColor(Color.argb(Math.round(155+65*ambient),225,233,239));
        p.setTextAlign(Paint.Align.RIGHT);c.drawText(date.format(current),right,72+bitmapGlyphHeight*ratio+36,p);
        if(ambient>.01f){
            p.setTextSize(15);p.setColor(Color.argb(Math.round(75*ambient),185,210,221));p.setTextAlign(Paint.Align.LEFT);
            c.drawText("MARCO TV  /  Pilipala",112,985,p);
        }
    }
    static int dim(SharedPreferences prefs,float ambient){
        float normal=prefs.getInt("wallpaper_dim",10)/100f;
        float idle=Math.max(normal,prefs.getInt("ambient_dim",72)/100f);
        return Math.round(255*(normal+(idle-normal)*ambient));
    }
}
