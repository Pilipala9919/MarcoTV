// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.graphics.*;

/** Small cached wallpaper-only blur, compatible with pre-Android 12 televisions. */
final class PreviewBackdrop {
    private Bitmap source,blurred;
    private int generation,width,height;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final RectF bounds=new RectF();
    void draw(Canvas canvas,Bitmap wallpaper,int viewWidth,int viewHeight){
        canvas.drawColor(0xFF0B121C);
        if(wallpaper==null||wallpaper.isRecycled()||viewWidth<=0||viewHeight<=0){clear();return;}
        if(blurred==null||source!=wallpaper||generation!=wallpaper.getGenerationId()||width!=viewWidth||height!=viewHeight){
            clear();source=wallpaper;generation=wallpaper.getGenerationId();width=viewWidth;height=viewHeight;
            float ratio=Math.min(1,160f/Math.max(viewWidth,viewHeight));
            int w=Math.max(1,Math.round(viewWidth*ratio)),h=Math.max(1,Math.round(viewHeight*ratio));
            blurred=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
            Canvas small=new Canvas(blurred);
            float scale=Math.max(w/(float)wallpaper.getWidth(),h/(float)wallpaper.getHeight());
            float sw=wallpaper.getWidth()*scale,sh=wallpaper.getHeight()*scale;
            bounds.set((w-sw)/2,(h-sh)/2,(w+sw)/2,(h+sh)/2);
            paint.setColor(Color.WHITE);small.drawBitmap(wallpaper,null,bounds,paint);
            int[] pixels=new int[w*h],scratch=new int[w*h];blurred.getPixels(pixels,0,w,0,0,w,h);
            // Three separable box passes soften detail without full-size allocations or GPU blur APIs.
            for(int pass=0;pass<3;pass++){blur(pixels,scratch,w,h,true);blur(scratch,pixels,w,h,false);}
            blurred.setPixels(pixels,0,w,0,0,w,h);
        }
        bounds.set(0,0,viewWidth,viewHeight);paint.setColor(Color.WHITE);canvas.drawBitmap(blurred,null,bounds,paint);
        canvas.drawColor(0x980B121C);
    }
    private static void blur(int[] input,int[] output,int w,int h,boolean horizontal){
        int length=horizontal?w:h,lines=horizontal?h:w,radius=6,divisor=radius*2+1;
        for(int line=0;line<lines;line++){
            int a=0,r=0,g=0,b=0;
            for(int offset=-radius;offset<=radius;offset++){
                int position=Math.max(0,Math.min(length-1,offset));int c=input[horizontal?line*w+position:position*w+line];
                a+=Color.alpha(c);r+=Color.red(c);g+=Color.green(c);b+=Color.blue(c);
            }
            for(int position=0;position<length;position++){
                output[horizontal?line*w+position:position*w+line]=Color.argb(a/divisor,r/divisor,g/divisor,b/divisor);
                int before=Math.max(0,position-radius),after=Math.min(length-1,position+radius+1);
                int old=input[horizontal?line*w+before:before*w+line],next=input[horizontal?line*w+after:after*w+line];
                a+=Color.alpha(next)-Color.alpha(old);r+=Color.red(next)-Color.red(old);g+=Color.green(next)-Color.green(old);b+=Color.blue(next)-Color.blue(old);
            }
        }
    }
    void clear(){if(blurred!=null)blurred.recycle();blurred=null;source=null;}
}
