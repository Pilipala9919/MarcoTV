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
    private final RectF weatherOval=new RectF();
    private final Path weatherPath=new Path();
    private final Typeface medium=Typeface.create("sans-serif-medium",Typeface.NORMAL);
    private SimpleDateFormat time=new SimpleDateFormat("HH:mm",Locale.ROOT);
    private final SimpleDateFormat date=new SimpleDateFormat("M月d日  EEEE",Locale.CHINA);
    private final Date nowDate=new Date();
    private Bitmap clockBitmap;
    private String cachedTime="",cachedDate="";
    private long cachedDay=Long.MIN_VALUE;
    private int cachedStyle=-1,cachedTilt=-1,bitmapGlyphHeight;
    private int cachedFilterColor,cachedShadowColor;
    private PorterDuffColorFilter colorFilter,shadowFilter;
    private float topRightLuminance=0.16f;
    private WeatherSnapshot weather;
    private boolean forceWeatherPreview;
    private Layout layout;
    private int layoutStyle=-1,layoutTilt=-1;
    private final Map<Integer,Layout> layoutCache=new LinkedHashMap<Integer,Layout>(16,0.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<Integer,Layout> eldest){
            return size()>24;
        }
    };

    ClockScene(Context context){
        synchronized(ClockScene.class){
            if(sharedFaces==null){
                sharedFaces=new Typeface[STYLES.length];
                for(int i=0;i<sharedFaces.length;i++){
                    try{
                        sharedFaces[i]=Typeface.createFromAsset(context.getAssets(),"fonts/clock-"+i+".ttf");
                    }
                    catch(RuntimeException e){
                        sharedFaces[i]=Typeface.create("sans-serif",Typeface.BOLD);
                    }
                }
            }
            faces=sharedFaces;
        }
    }
    float size(SharedPreferences prefs,float ambient){
        float small=prefs.getInt("clock_size",96),large=prefs.getInt("ambient_clock_size",176);
        return small+(Math.max(small,large)-small)*ambient;
    }
    private static int style(SharedPreferences prefs){
        return Math.max(0,Math.min(STYLES.length-1,prefs.getInt("clock_style",0)));
    }
    private static int tilt(SharedPreferences prefs,int style){
        return style==2?Math.max(0,Math.min(14,prefs.getInt("clock_tilt",10))):0;
    }
    private Layout layout(SharedPreferences prefs){
        int s=style(prefs),t=tilt(prefs,s);
        if(layout==null||s!=layoutStyle||t!=layoutTilt){
            int cacheKey=(s<<8)|t;
            Layout cached=layoutCache.get(cacheKey);
            if(cached==null){
                cached=new Layout(faces[s],s,t);
                layoutCache.put(cacheKey,cached);
            }
            layout=cached;
            layoutStyle=s;
            layoutTilt=t;
        }
        return layout;
    }
    float left(SharedPreferences prefs){
        return 1798-layout(prefs).width*size(prefs,0)/280;
    }
    void setTopRightLuminance(float luminance){
        topRightLuminance=Math.max(0f,Math.min(1f,luminance));
    }
    void setWeather(WeatherSnapshot snapshot,boolean previewMode){
        weather=snapshot;
        forceWeatherPreview=previewMode;
    }
    static boolean prefBool(SharedPreferences prefs,String key,boolean fallback){
        try{
            return prefs.getBoolean(key,fallback);
        }catch(ClassCastException ignored){
            return prefs.getInt(key,fallback?1:0)!=0;
        }
    }
    boolean isBrightClockArea(SharedPreferences prefs){
        int wallDim=prefs.getInt("wallpaper_dim",10);
        int colorMode=prefs.getInt("clock_color_mode",DesktopContrast.MODE_AUTO);
        boolean brightBg=DesktopContrast.isBright(DesktopContrast.effectiveLuminance(topRightLuminance,wallDim));
        if(colorMode==DesktopContrast.MODE_AUTO)return brightBg;
        int chosen=DesktopContrast.resolveClockColor(colorMode,prefs.getInt("clock_hue",210),prefs.getInt("clock_lightness",82),brightBg);
        return DesktopContrast.isDarkInk(chosen);
    }
    private int resolvedInk(SharedPreferences prefs,float ambient,boolean pure){
        int wallDim=prefs.getInt("wallpaper_dim",10);
        int colorMode=prefs.getInt("clock_color_mode",DesktopContrast.MODE_AUTO);
        boolean brightBg=DesktopContrast.isBright(DesktopContrast.effectiveLuminance(topRightLuminance,wallDim));
        int desktopColor=DesktopContrast.resolveClockColor(colorMode,prefs.getInt("clock_hue",210),prefs.getInt("clock_lightness",82),brightBg);
        return DesktopContrast.resolveAmbientClockColor(desktopColor,ambient,prefs.getInt("ambient_dim",72),pure);
    }
    private PorterDuffColorFilter inkFilter(int rgbColor){
        int opaque=rgbColor|0xFF000000;
        if(colorFilter==null||cachedFilterColor!=opaque){
            cachedFilterColor=opaque;
            colorFilter=new PorterDuffColorFilter(opaque,PorterDuff.Mode.SRC_IN);
        }
        return colorFilter;
    }
    private PorterDuffColorFilter shadowColorFilter(int rgbColor){
        int opaque=rgbColor|0xFF000000;
        if(shadowFilter==null||cachedShadowColor!=opaque){
            cachedShadowColor=opaque;
            shadowFilter=new PorterDuffColorFilter(opaque,PorterDuff.Mode.SRC_IN);
        }
        return shadowFilter;
    }
    void draw(Canvas c,SharedPreferences prefs,float ambient){
        draw(c,prefs,ambient,false);
    }
    /** Slow anti burn-in drift for long idle displays, in 1920x1080 design pixels.
     * Two unrelated periods trace a Lissajous path, so no pixel stays lit at one spot. */
    static float driftX(long now){
        return (float)(DRIFT_X*Math.sin(now*2*Math.PI/DRIFT_PERIOD_X));
    }
    static float driftY(long now){
        return (float)(DRIFT_Y*Math.sin(now*2*Math.PI/DRIFT_PERIOD_Y));
    }
    static final float DRIFT_X=36,DRIFT_Y=22;
    static final long DRIFT_PERIOD_X=7*60*1000L,DRIFT_PERIOD_Y=11*60*1000L;
    /** drift=true only for the real screensaver, never for settings previews. */
    void draw(Canvas c,SharedPreferences prefs,float ambient,boolean drift){
        if(drift&&ambient>0){
            long now=System.currentTimeMillis();
            int saved=c.save();
            c.translate(-Math.abs(driftX(now))*ambient,(driftY(now)+DRIFT_Y)*ambient);
            drawClock(c,prefs,ambient);
            c.restoreToCount(saved);
            return;
        }
        drawClock(c,prefs,ambient);
    }
    private void drawClock(Canvas c,SharedPreferences prefs,float ambient){
        boolean pure=prefBool(prefs,"ambient_pure_wallpaper",false);
        float clockVis=pure?Math.max(0f,1f-ambient*2.5f):1f;
        if(clockVis<=0.005f)return;
        TimeZone currentZone=TimeZone.getDefault();
        if(!time.getTimeZone().equals(currentZone)){
            time.setTimeZone(currentZone);
            date.setTimeZone(currentZone);
            cachedTime="";
            cachedDate="";
            cachedDay=Long.MIN_VALUE;
        }
        long now=System.currentTimeMillis();
        long day=(now+currentZone.getOffset(now))/86400000L;
        nowDate.setTime(now);
        String shown=time.format(nowDate);
        Layout glyphs=layout(prefs);
        int style=style(prefs),tilt=tilt(prefs,style);
        // Only rebuild on minute/style/tilt changes. The same pixels are scaled
        // throughout a screensaver transition, including the tilted numerals.
        if(clockBitmap==null||!shown.equals(cachedTime)||style!=cachedStyle||tilt!=cachedTilt){
            clockBitmap=glyphs.render(shown,clockBitmap);
            bitmapGlyphHeight=glyphs.height;
            cachedTime=shown;
            cachedDate=date.format(nowDate);
            cachedDay=day;
            cachedStyle=style;
            cachedTilt=tilt;
        }else if(day!=cachedDay||cachedDate.isEmpty()){
            cachedDate=date.format(nowDate);
            cachedDay=day;
        }
        float ratio=size(prefs,pure?0f:ambient)/280;
        int glow=prefs.getInt("ambient_clock_brightness",100);
        int ink=resolvedInk(prefs,ambient,pure);
        boolean darkInk=DesktopContrast.isDarkInk(ink);
        int colorMode=prefs.getInt("clock_color_mode",DesktopContrast.MODE_AUTO);
        int desktopAlpha=darkInk?236:(colorMode>DesktopContrast.MODE_WHITE?218:172);
        int mainAlpha=Math.round((desktopAlpha+(Math.round(255*glow/100f)-desktopAlpha)*ambient)*clockVis);
        int shadowPct=DesktopContrast.clampShadow(prefs.getInt("clock_shadow",35));
        clockRect.set(1798-(glyphs.width+3)*ratio,72-3*ratio,1798+3*ratio,72+(glyphs.height+3)*ratio);
        if(shadowPct>0){
            int shadowArg=DesktopContrast.contrastShadowColor(ink,shadowPct,(1f-0.55f*ambient)*clockVis);
            int sa=Color.alpha(shadowArg);
            if(sa>1){
                float dy=2.2f*ratio;
                clockRect.offset(0f,dy);
                imagePaint.setColorFilter(shadowColorFilter(shadowArg));
                imagePaint.setAlpha(sa);
                c.drawBitmap(clockBitmap,null,clockRect,imagePaint);
                clockRect.offset(0f,-dy);
            }
        }
        imagePaint.setColorFilter(inkFilter(ink));
        imagePaint.setAlpha(mainAlpha);
        c.drawBitmap(clockBitmap,null,clockRect,imagePaint);
        imagePaint.setColorFilter(null);
        float dateY=72+bitmapGlyphHeight*ratio+36;
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(medium);
        p.setTextSize(24);
        p.setTextAlign(Paint.Align.RIGHT);
        float dateAlpha01=(darkInk?(0.86f+0.10f*ambient):(0.66f+0.24f*ambient))*clockVis;
        if(shadowPct>0){
            int sc=DesktopContrast.contrastShadowColor(ink,shadowPct,0.85f*clockVis);
            if(Color.alpha(sc)>1){
                p.setColor(sc);
                c.drawText(cachedDate,1798,dateY+1.6f,p);
            }
        }
        p.setColor(DesktopContrast.withAlpha(ink,dateAlpha01));
        c.drawText(cachedDate,1798,dateY,p);
        drawWeather(c,prefs,ambient,pure,clockVis,ink,shadowPct,dateY,now);
        if(!pure&&ambient>.01f){
            p.setTextSize(15);
            p.setColor(Color.argb(Math.round(75*ambient),185,210,221));
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText("MARCO TV  /  Pilipala",112,985,p);
        }
    }
    private void drawWeather(Canvas c,SharedPreferences prefs,float ambient,boolean pure,
                             float clockVis,int ink,int shadowPct,float dateY,long now){
        if(!prefBool(prefs,"weather_enabled",false)||pure)return;
        boolean showAmbient=prefBool(prefs,"ambient_weather",true);
        float weatherVis=(ambient<=0.01f?1f:(showAmbient?1f:Math.max(0f,1f-ambient*2.5f)))*clockVis;
        if(weatherVis<=0.01f)return;
        WeatherSnapshot snap=weather;
        if(snap!=null&&snap.fetchedAtMs>0&&now-snap.fetchedAtMs>6L*3600L*1000L&&!forceWeatherPreview){
            snap=null;
        }
        if(snap==null&&forceWeatherPreview){
            snap=WeatherSnapshot.samplePreview();
        }
        if(snap==null)return;
        boolean showIcon=prefBool(prefs,"weather_show_icon",true);
        boolean showCurrent=prefBool(prefs,"weather_show_current",true);
        boolean showRange=prefBool(prefs,"weather_show_range",true);
        int precipMode=Math.max(0,Math.min(2,prefs.getInt("weather_precip_mode",WeatherSnapshot.PRECIP_WHEN_POSITIVE)));
        boolean showCity=prefBool(prefs,"weather_show_city",false);
        boolean colorful=prefBool(prefs,"weather_icon_colorful",true);
        float wScale=Math.max(75,Math.min(135,prefs.getInt("weather_size",100)))/100f;
        String[] parts=snap.buildDisplayParts(showCity,showCurrent,showRange,precipMode);
        String mainText=parts[0],precipText=parts[1];
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(medium);
        p.setTextSize(21f*wScale);
        p.setTextAlign(Paint.Align.RIGHT);
        float baselineY=dateY+33f*wScale;
        float iconCenterY=baselineY-7.4f*wScale;
        float alpha01=(DesktopContrast.isDarkInk(ink)?0.88f:0.84f)*weatherVis;
        float cursorX=1798f;
        if(!precipText.isEmpty()){
            if(shadowPct>0){
                int sc=DesktopContrast.contrastShadowColor(ink,shadowPct,0.8f*weatherVis);
                if(Color.alpha(sc)>1){
                    p.setColor(sc);
                    c.drawText(precipText,cursorX,baselineY+1.4f,p);
                }
            }
            p.setColor(DesktopContrast.withAlpha(ink,alpha01));
            c.drawText(precipText,cursorX,baselineY,p);
            cursorX-=p.measureText(precipText)+4f*wScale;
            float dropSize=13f*wScale;
            WeatherIcons.drawDroplet(c,p,weatherPath,cursorX-dropSize*0.42f,iconCenterY,dropSize,ink,colorful,alpha01);
            cursorX-=dropSize*0.85f+(mainText.isEmpty()?0f:14f*wScale);
        }
        if(!mainText.isEmpty()){
            p.setTextAlign(Paint.Align.RIGHT);
            if(shadowPct>0){
                int sc=DesktopContrast.contrastShadowColor(ink,shadowPct,0.8f*weatherVis);
                if(Color.alpha(sc)>1){
                    p.setColor(sc);
                    c.drawText(mainText,cursorX,baselineY+1.4f,p);
                }
            }
            p.setColor(DesktopContrast.withAlpha(ink,alpha01));
            c.drawText(mainText,cursorX,baselineY,p);
            cursorX-=p.measureText(mainText);
        }
        if(showIcon){
            float gap=(!mainText.isEmpty()||!precipText.isEmpty())?10f*wScale:0f;
            float iconSize=22f*wScale;
            WeatherIcons.drawCondition(c,p,weatherPath,weatherOval,snap.condition,snap.isDay,
                    cursorX-gap-iconSize*0.5f,iconCenterY,iconSize,ink,colorful,alpha01);
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
            this.style=style;
            Rect r=new Rect();
            ink.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
            ink.setTextSize(280);
            ink.getTextBounds("0123456789",0,10,r);
            int reference=r.height();
            ink.setTypeface(face);
            ink.getTextBounds("0123456789",0,10,r);
            textSize=280f*reference/Math.max(1,r.height());
            ink.setTextSize(textSize);
            ink.getTextBounds("0123456789",0,10,r);
            baseline=-r.top;
            pivot=r.height()*.74f;
            float advance=0;
            for(int d=0;d<10;d++)advance=Math.max(advance,ink.measureText(Integer.toString(d)));
            if(style==2)advance=Math.max(advance,textSize*.61f);
            float colon=style==2?textSize*.29f:ink.measureText(":");
            float x=0;
            int digit=0;
            RectF bounds=new RectF();
            boolean first=true;
            for(int i=0;i<5;i++){
                float cell=i==2?colon:advance;
                centers[i]=x+cell/2;
                x+=cell;
                if(i!=2)angles[i]=style==2?TILT_FACTORS[digit++]*tilt:0;
                ink.setTextSize(i==2&&style==2?textSize*.85f:textSize);
                int choices=i==2?1:10;
                for(int d=0;d<choices;d++){
                    String ch=i==2?":":Integer.toString(d);
                    ink.getTextBounds(ch,0,1,r);
                    float left=centers[i]-ink.measureText(ch)/2;
                    RectF visible=new RectF(left+r.left,baseline+r.top,left+r.right,baseline+r.bottom);
                    Matrix rotation=new Matrix();
                    rotation.setRotate(angles[i],centers[i],pivot);
                    rotation.mapRect(visible);
                    if(first){
                        bounds.set(visible);
                        first=false;
                    }else bounds.union(visible);
                }
            }
            offsetX=-bounds.left;
            offsetY=-bounds.top;
            width=(int)Math.ceil(bounds.width());
            height=(int)Math.ceil(bounds.height());
            ink.setTextAlign(Paint.Align.CENTER);
            ink.setColor(Color.WHITE);
        }
        Bitmap render(String value,Bitmap existing){
            int targetW=width+6,targetH=height+6;
            Bitmap result;
            if(existing!=null&&!existing.isRecycled()&&existing.isMutable()&&existing.getWidth()==targetW&&existing.getHeight()==targetH){
                result=existing;
                result.eraseColor(Color.TRANSPARENT);
            }else{
                if(existing!=null&&!existing.isRecycled())existing.recycle();
                result=Bitmap.createBitmap(targetW,targetH,Bitmap.Config.ARGB_8888);
            }
            Canvas canvas=new Canvas(result);
            canvas.translate(3+offsetX,3+offsetY);
            for(int i=0;i<5;i++){
                ink.setTextSize(i==2&&style==2?textSize*.85f:textSize);
                canvas.save();
                canvas.rotate(angles[i],centers[i],pivot);
                canvas.drawText(value.substring(i,i+1),centers[i],baseline,ink);
                canvas.restore();
            }
            return result;
        }
    }
    void release(){
        if(clockBitmap!=null&&!clockBitmap.isRecycled())clockBitmap.recycle();
        clockBitmap=null;
        cachedTime="";
    }
    static int dim(SharedPreferences prefs,float ambient){
        float normal=prefs.getInt("wallpaper_dim",10)/100f;
        if(prefs.getBoolean("ambient_pure_wallpaper",false)){
            return Math.round(255*normal*(1f-Math.max(0f,Math.min(1f,ambient))));
        }
        float idle=Math.max(normal,prefs.getInt("ambient_dim",72)/100f);
        return Math.round(255*(normal+(idle-normal)*ambient));
    }
}
