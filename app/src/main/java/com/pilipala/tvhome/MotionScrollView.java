// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.MotionEvent;
import android.widget.ScrollView;

/** D-pad focus scrolls retarget from the current position instead of restarting. */
final class MotionScrollView extends ScrollView {
    private final UiMotion motion;
    private final Paint edgePaint=new Paint();
    private int fadeHeight;
    private int fadeColor = 0xFF171B23;
    private LinearGradient topFade,bottomFade;
    MotionScrollView(Context context){
        super(context);
        setSmoothScrollingEnabled(false);
        motion=new UiMotion(this,0,28,v->scrollTo(0,Math.round(v)));
    }
    void setFadeColor(int color){
        if(fadeColor!=color){
            fadeColor=color;
            fadeHeight=-1;
            invalidate();
        }
    }
    void snapTop(){
        motion.snap(0);
        scrollTo(0,0);
    }
    private void follow(int before,int target){
        if(before==target)return;
        scrollTo(0,before);
        if(Math.abs(motion.spring.value-before)>1)motion.snap(before);
        motion.to(target);
    }
    @Override public void requestChildFocus(View child,View focused){
        int before=getScrollY();
        super.requestChildFocus(child,focused);
        if(isLaidOut())follow(before,getScrollY());
    }
    @Override public boolean requestChildRectangleOnScreen(View child,Rect rectangle,boolean immediate){
        int before=getScrollY();
        boolean changed=super.requestChildRectangleOnScreen(child,rectangle,true);
        int target=getScrollY();
        if(changed&&!immediate&&isLaidOut()){
            follow(before,target);
        }else if(changed)motion.snap(target);
        return changed;
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN)motion.cancel();
        return super.onTouchEvent(event);
    }
    @Override protected void dispatchDraw(Canvas canvas){
        super.dispatchDraw(canvas);
        int h=getHeight(),y=getScrollY();
        float fade=18*getResources().getDisplayMetrics().density;
        if(fadeHeight!=h){
            fadeHeight=h;
            int opaque=(fadeColor&0x00FFFFFF)|0xFF000000;
            int transparent=fadeColor&0x00FFFFFF;
            topFade=new LinearGradient(0,0,0,fade,opaque,transparent,Shader.TileMode.CLAMP);
            bottomFade=new LinearGradient(0,h-fade,0,h,transparent,opaque,Shader.TileMode.CLAMP);
        }
        int save=canvas.save();
        canvas.translate(0,y);
        if(y>0){
            edgePaint.setShader(topFade);
            canvas.drawRect(0,0,getWidth(),fade,edgePaint);
        }
        if(getChildCount()>0&&y+h<getChildAt(0).getBottom()){
            edgePaint.setShader(bottomFade);
            canvas.drawRect(0,h-fade,getWidth(),h,edgePaint);
        }
        edgePaint.setShader(null);
        canvas.restoreToCount(save);
    }
}
