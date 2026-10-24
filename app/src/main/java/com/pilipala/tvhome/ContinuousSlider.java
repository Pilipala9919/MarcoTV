// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;

/** Fine-grained remote slider, one unit per press with accelerated key repeat. */
final class ContinuousSlider extends View {
    interface Listener { void changed(int value); }
    private final int min,max;
    private int value;
    private final String title,unit;
    private final Listener listener;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect=new RectF();
    private boolean compact;
    void compact(){compact=true;setMinimumHeight(dp(96));requestLayout();}
    ContinuousSlider(Context c,String title,String unit,int min,int max,int value,Listener listener) {
        super(c);this.title=title;this.unit=unit;this.min=min;this.max=max;
        this.value=Math.max(min,Math.min(max,value));this.listener=listener;
        setFocusable(true);setFocusableInTouchMode(true);setMinimumHeight(dp(146));describe();
    }
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private String label(){return title.equals("进入屏保等待")&&value==0?"不自动进入":value+unit;}
    private void describe(){setContentDescription(title+"，"+label()+"，左右键连续调整");}
    private void update(int next){next=Math.max(min,Math.min(max,next));if(value==next)return;value=next;describe();listener.changed(value);invalidate();}
    @Override protected void onMeasure(int w,int h){setMeasuredDimension(MeasureSpec.getSize(w),dp(compact?96:146));}
    @Override protected void onDraw(Canvas c){
        float p=dp(20), y=dp(compact?47:77), right=getWidth()-p;
        paint.setColor(isFocused()?0xFF354B5D:0xFF293541);rect.set(0,0,getWidth(),getHeight());c.drawRoundRect(rect,dp(16),dp(16),paint);
        paint.setTypeface(Typeface.create("sans-serif-medium",0));paint.setTextSize(dp(27));paint.setColor(Color.WHITE);paint.setTextAlign(Paint.Align.CENTER);
        c.drawText(label(),getWidth()/2f,dp(compact?29:43),paint);
        paint.setColor(0xFF536373);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeWidth(dp(4));c.drawLine(p,y,right,y,paint);
        float knob=p+(right-p)*(value-min)/(float)(max-min);paint.setColor(0xFFA9DCE2);c.drawLine(p,y,knob,y,paint);
        c.drawCircle(knob,y,dp(isFocused()?9:7),paint);
        paint.setTextSize(dp(12));paint.setColor(0xFFB8C6D2);paint.setTextAlign(Paint.Align.LEFT);c.drawText(min+unit,p,dp(compact?70:108),paint);
        paint.setTextAlign(Paint.Align.RIGHT);c.drawText(max+unit,right,dp(compact?70:108),paint);
        paint.setTextAlign(Paint.Align.CENTER);c.drawText("左右微调 · 长按加速 · 返回上一级",getWidth()/2f,dp(compact?89:134),paint);
    }
    @Override public boolean onKeyDown(int key,KeyEvent event){
        if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){
            int step=event.getRepeatCount()>12?Math.max(1,(max-min)/50):1;update(value+(key==KeyEvent.KEYCODE_DPAD_RIGHT?step:-step));return true;
        }return super.onKeyDown(key,event);
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN||event.getActionMasked()==MotionEvent.ACTION_MOVE||event.getActionMasked()==MotionEvent.ACTION_UP){
            requestFocus();float p=dp(20);update(min+Math.round((event.getX()-p)/(getWidth()-2*p)*(max-min)));return true;
        }return true;
    }
    @Override protected void onFocusChanged(boolean focused,int direction,Rect old){super.onFocusChanged(focused,direction,old);invalidate();}
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){
        super.onInitializeAccessibilityNodeInfo(info);info.setClassName("android.widget.SeekBar");
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,min,max,value));
        info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
    }
    @Override public boolean performAccessibilityAction(int action,android.os.Bundle args){
        if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD){update(value+1);return true;}
        if(action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD){update(value-1);return true;}
        return super.performAccessibilityAction(action,args);
    }
}
