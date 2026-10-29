// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.animation.ValueAnimator;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.service.dreams.DreamService;
import android.view.*;
import java.util.concurrent.*;

/** System screensaver, shares the desktop clock and artwork without launching HOME. */
public final class MarcoDreamService extends DreamService {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService loader=Executors.newSingleThreadExecutor();
    private AmbientView view;
    private final Runnable tick=new Runnable(){public void run(){if(view!=null){view.invalidate();handler.postDelayed(this,1000);}}};
    @Override public void onAttachedToWindow(){
        super.onAttachedToWindow();setInteractive(false);setFullscreen(true);setScreenBright(false);
        view=new AmbientView();setContentView(view);
        loader.execute(()->{Bitmap bitmap=WallpaperSource.load(this);handler.post(()->{if(view!=null){view.wallpaper=bitmap;view.invalidate();}});});
    }
    @Override public void onDreamingStarted(){super.onDreamingStarted();if(view!=null)view.start();handler.post(tick);}
    @Override public void onDreamingStopped(){handler.removeCallbacksAndMessages(null);if(view!=null)view.stop();super.onDreamingStopped();}
    @Override public void onDetachedFromWindow(){if(view!=null)view.stop();view=null;handler.removeCallbacksAndMessages(null);super.onDetachedFromWindow();}
    @Override public void onDestroy(){loader.shutdownNow();super.onDestroy();}
    private final class AmbientView extends View {
        Bitmap wallpaper;float ambient;final ClockScene clock=new ClockScene(MarcoDreamService.this);final Paint paint=new Paint(3);final RectF rect=new RectF();
        final SharedPreferences prefs=getSharedPreferences("home",0);ValueAnimator animator;
        AmbientView(){super(MarcoDreamService.this);setContentDescription("Marco TV 时钟屏保");}
        void start(){animator=ValueAnimator.ofFloat(0,1);animator.setDuration(prefs.getInt("ambient_transition",3)*1000L);animator.addUpdateListener(a->{ambient=(float)a.getAnimatedValue();invalidate();});animator.start();}
        void stop(){if(animator!=null)animator.cancel();}
        @Override protected void onDraw(Canvas c){
            c.drawColor(Color.BLACK);float scale=Math.min(getWidth()/1920f,getHeight()/1080f);
            if(wallpaper!=null){float k=Math.max(getWidth()/(float)wallpaper.getWidth(),getHeight()/(float)wallpaper.getHeight());float w=k*wallpaper.getWidth(),h=k*wallpaper.getHeight();rect.set((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2);paint.setColor(Color.WHITE);c.drawBitmap(wallpaper,null,rect,paint);}
            c.drawColor(Color.argb(ClockScene.dim(prefs,ambient),0,0,0));c.save();c.translate((getWidth()-1920*scale)/2,(getHeight()-1080*scale)/2);c.scale(scale,scale);clock.draw(c,prefs,ambient);c.restore();
        }
    }
}
