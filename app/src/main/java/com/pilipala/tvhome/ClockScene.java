// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Shared desktop/dream clock with fixed slots, stable anchors and cached glyphs. */
final class ClockScene {
    static final String[] STYLES={"现代宽体","修长窄体","柔圆错倾","雅致衬线"};
    private static final float[] TILT_FACTORS={-.85f,.65f,-.55f,1f};
    private static Typeface[] sharedFaces;
    private final Typeface[] faces;
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG|Paint.LINEAR_TEXT_FLAG);
    private final Paint imagePaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final RectF clockRect=new RectF();
    private final Typeface medium=Typeface.create("sans-serif-medium",Typeface.NORMAL);
    private final SimpleDateFormat time=new SimpleDateFormat("HH:mm",Locale.ROOT);
    private final SimpleDateFormat date=new SimpleDateFormat("M月d日  EEEE",Locale.CHINA);
    private Bitmap clockBitmap;
    private String cachedTime="";
    private int cachedStyle=-1,cachedTilt=-1,bitmapGlyphHeight;
    private Layout layout;
    private int layoutStyle=-1,layoutTilt=-1;

    ClockScene(Context context){
        synchronized(ClockScene.class){
            if(sharedFaces==null){
                sharedFaces=new Typeface[STYLES.length];
                for(int i=0;i<sharedFaces.length;i++){
                    try{sharedFaces[i]=Typeface.createFromAsset(context.getAssets(),"fonts/clock-"+i+".ttf");}
                    catch(RuntimeException e){sharedFaces[i]=Typeface.create("sans-serif",Typeface.BOLD);}
                }
            }
            faces=sharedFaces;
        }
    }
    float size(SharedPreferences prefs,float ambient){
        float small=prefs.getInt("clock_size",96),large=prefs.getInt("ambient_clock_size",176);
        return small+(Math.max(small,large)-small)*ambient;
    }
    private static int style(SharedPreferences prefs){return Math.max(0,Math.min(STYLES.length-1,prefs.getInt("clock_style",0)));}
    private static int tilt(SharedPreferences prefs,int style){return style==2?Math.max(0,Math.min(14,prefs.getInt("clock_tilt",10))):0;}
    private Layout layout(SharedPreferences prefs){
        int s=style(prefs),t=tilt(prefs,s);
        if(layout==null||s!=layoutStyle||t!=layoutTilt){layout=new Layout(faces[s],s,t);layoutStyle=s;layoutTilt=t;}
        return layout;
    }
    float left(SharedPreferences prefs){return 1798-layout(prefs).width*size(prefs,0)/280;}
    void draw(Canvas c,SharedPreferences prefs,float ambient){
        TimeZone currentZone=TimeZone.getDefault();if(!time.getTimeZone().equals(currentZone)){time.setTimeZone(currentZone);date.setTimeZone(currentZone);cachedTime="";}
        Date current=new Date(System.currentTimeMillis());String shown=time.format(current);
        Layout glyphs=layout(prefs);int style=style(prefs),tilt=tilt(prefs,style);
        // Only rebuild on minute/style/tilt changes. The same pixels are scaled
        // throughout a screensaver transition, including the tilted numerals.
        if(clockBitmap==null||!shown.equals(cachedTime)||style!=cachedStyle||tilt!=cachedTilt){
            clockBitmap=glyphs.render(shown);bitmapGlyphHeight=glyphs.height;
            cachedTime=shown;cachedStyle=style;cachedTilt=tilt;
        }
        float ratio=size(prefs,ambient)/280;
        int glow=prefs.getInt("ambient_clock_brightness",100);
        imagePaint.setAlpha(Math.round(160+(Math.round(255*glow/100f)-160)*ambient));
        clockRect.set(1798-(glyphs.width+3)*ratio,72-3*ratio,1798+3*ratio,72+(glyphs.height+3)*ratio);
        c.drawBitmap(clockBitmap,null,clockRect,imagePaint);
        p.setTypeface(medium);p.setTextSize(24);p.setColor(Color.argb(Math.round(155+65*ambient),225,233,239));
        p.setTextAlign(Paint.Align.RIGHT);c.drawText(date.format(current),1798,72+bitmapGlyphHeight*ratio+36,p);
        if(ambient>.01f){
            p.setTextSize(15);p.setColor(Color.argb(Math.round(75*ambient),185,210,221));p.setTextAlign(Paint.Align.LEFT);
            c.drawText("MARCO TV  /  Pilipala",112,985,p);
        }
    }
    /** Bounds reserve every digit at each slot, so 11:11 and 20:48 occupy the
     * same rectangle. Rotated corners keep a three-pixel transparent margin. */
    private static final class Layout {
        final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG|Paint.LINEAR_TEXT_FLAG);
        final float[] centers=new float[5],angles=new float[5];
        final float textSize,baseline,pivot,offsetX,offsetY;
        final int width,height,style;
        Layout(Typeface face,int style,int tilt){
            this.style=style;Rect r=new Rect();
            ink.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));ink.setTextSize(280);
            ink.getTextBounds("0123456789",0,10,r);int reference=r.height();
            ink.setTypeface(face);ink.getTextBounds("0123456789",0,10,r);
            textSize=280f*reference/Math.max(1,r.height());ink.setTextSize(textSize);
            ink.getTextBounds("0123456789",0,10,r);baseline=-r.top;pivot=r.height()*.74f;
            float advance=0;for(int d=0;d<10;d++)advance=Math.max(advance,ink.measureText(Integer.toString(d)));
            if(style==2)advance=Math.max(advance,textSize*.61f);
            float colon=style==2?textSize*.29f:ink.measureText(":");
            float x=0;int digit=0;RectF bounds=new RectF();boolean first=true;
            for(int i=0;i<5;i++){
                float cell=i==2?colon:advance;centers[i]=x+cell/2;x+=cell;
                if(i!=2)angles[i]=style==2?TILT_FACTORS[digit++]*tilt:0;
                ink.setTextSize(i==2&&style==2?textSize*.85f:textSize);
                int choices=i==2?1:10;
                for(int d=0;d<choices;d++){
                    String ch=i==2?":":Integer.toString(d);ink.getTextBounds(ch,0,1,r);
                    float left=centers[i]-ink.measureText(ch)/2;
                    RectF visible=new RectF(left+r.left,baseline+r.top,left+r.right,baseline+r.bottom);
                    Matrix rotation=new Matrix();rotation.setRotate(angles[i],centers[i],pivot);rotation.mapRect(visible);
                    if(first){bounds.set(visible);first=false;}else bounds.union(visible);
                }
            }
            offsetX=-bounds.left;offsetY=-bounds.top;width=(int)Math.ceil(bounds.width());height=(int)Math.ceil(bounds.height());
            ink.setTextAlign(Paint.Align.CENTER);ink.setColor(Color.WHITE);
        }
        Bitmap render(String value){
            Bitmap result=Bitmap.createBitmap(width+6,height+6,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(result);canvas.translate(3+offsetX,3+offsetY);
            for(int i=0;i<5;i++){
                ink.setTextSize(i==2&&style==2?textSize*.85f:textSize);
                canvas.save();canvas.rotate(angles[i],centers[i],pivot);canvas.drawText(value.substring(i,i+1),centers[i],baseline,ink);canvas.restore();
            }
            return result;
        }
    }
    static int dim(SharedPreferences prefs,float ambient){
        float normal=prefs.getInt("wallpaper_dim",10)/100f;
        float idle=Math.max(normal,prefs.getInt("ambient_dim",72)/100f);
        return Math.round(255*(normal+(idle-normal)*ambient));
    }
}
