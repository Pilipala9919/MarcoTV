// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.app.Dialog;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

/** One window for the complete settings journey. Pages retain their views and focus. */
final class PanelWindow extends Dialog {
    private final MainActivity host;
    private FrameLayout root;
    private TvDialog current;
    private boolean popoverVisible;
    PanelWindow(MainActivity host){super(host);this.host=host;}
    private int dp(float n){return Math.round(n*getContext().getResources().getDisplayMetrics().density);}
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);requestWindowFeature(Window.FEATURE_NO_TITLE);
        root=new FrameLayout(getContext());setContentView(root);setCanceledOnTouchOutside(false);
        Window w=getWindow();w.setBackgroundDrawableResource(android.R.color.transparent);
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        w.setWindowAnimations(0);w.getDecorView().setSystemUiVisibility(5894);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        w.setLayout(-1,-1);
    }
    void present(TvDialog page,View content,boolean preview,boolean centered,boolean network){
        if(!isShowing())show();
        // Detach and attach in one UI pass: the same scrim/window remains throughout.
        if(current!=null)current.rememberFocus();
        root.removeAllViews();current=page;
        if(content.getParent()!=null)((ViewGroup)content.getParent()).removeView(content);
        root.setBackgroundColor(preview||network?0x00000000:0x7A050B13);
        root.setOnClickListener(network?v->page.onBackPressed():null);
        root.setClickable(network);
        int width=getContext().getResources().getDisplayMetrics().widthPixels;
        int height=getContext().getResources().getDisplayMetrics().heightPixels;
        content.setAlpha(1);content.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        TvDialog parent=host.settingsRoot();
        int parentWidth=Math.min(dp(470),width-dp(48));
        int bubbleWidth=dp(320);
        popoverVisible=page.isPopover()&&parent!=null&&width-parentWidth-bubbleWidth>=dp(64);
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(Math.min(dp(preview?300:centered?780:network?300:page.pageKey().equals("main")?470:360),width-dp(48)),Math.min(dp(preview?218:centered?425:network?page.networkHeight():500),height-dp(32)));
        p.gravity=centered?Gravity.CENTER:Gravity.RIGHT|Gravity.CENTER_VERTICAL;
        p.rightMargin=centered?0:dp(24);
        if(network){
            p.gravity=Gravity.TOP|Gravity.LEFT;p.rightMargin=0;
            p.leftMargin=Math.max(dp(16),Math.min(width-p.width-dp(16),Math.round(host.home.wifiAnchorX()-p.width*.5f)));
            p.topMargin=Math.min(Math.round(host.home.wifiAnchorY()+dp(27)),height-p.height-dp(16));
        }
        if(popoverVisible){
            View base=parent.preparedPanel();
            if(base.getParent()!=null)((ViewGroup)base.getParent()).removeView(base);
            base.animate().cancel();base.setTranslationX(0);base.setTranslationY(0);base.setAlpha(.42f);
            base.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            // The dimmed parent is context only: neither remote nor touch can escape into it.
            FrameLayout shield=new FrameLayout(getContext()){
                @Override public boolean onInterceptTouchEvent(MotionEvent event){return true;}
                @Override public boolean onTouchEvent(MotionEvent event){return true;}
            };
            shield.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);shield.addView(base,new FrameLayout.LayoutParams(-1,-1));
            FrameLayout.LayoutParams behind=new FrameLayout.LayoutParams(parentWidth,Math.min(dp(500),height-dp(32)));
            behind.gravity=Gravity.RIGHT|Gravity.CENTER_VERTICAL;behind.rightMargin=dp(24);root.addView(shield,behind);
            p.width=bubbleWidth;p.height=Math.min(dp(page.popoverHeight()),height-dp(32));
            p.gravity=Gravity.TOP|Gravity.LEFT;p.rightMargin=0;p.leftMargin=width-dp(36)-parentWidth-bubbleWidth;
            p.topMargin=(height-p.height)/2;
            root.addView(content,p);
            View pointer=new View(getContext()){
                private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override protected void onDraw(Canvas c){Path path=new Path();path.moveTo(0,0);path.lineTo(getWidth(),getHeight()/2f);path.lineTo(0,getHeight());path.close();paint.setColor(0xF51D2028);c.drawPath(path,paint);}
            };
            FrameLayout.LayoutParams tip=new FrameLayout.LayoutParams(dp(12),dp(22));tip.leftMargin=p.leftMargin+p.width;root.addView(pointer,tip);
            root.post(()->{
                if(current!=page||!popoverVisible)return;
                float anchor=parent.itemCenterY(host.settingsCategory());
                if(anchor<dp(32)||anchor>height-dp(32))anchor=height/2f;
                p.topMargin=Math.max(dp(16),Math.min(height-p.height-dp(16),Math.round(anchor-dp(56))));
                tip.topMargin=Math.max(p.topMargin+dp(24),Math.min(p.topMargin+p.height-dp(46),Math.round(anchor-dp(11))));
                content.setLayoutParams(p);pointer.setLayoutParams(tip);
            });
        }else root.addView(content,p);
        page.restoreFocus();
    }
    boolean displays(TvDialog page){return current==page&&isShowing();}
    @Override public void onBackPressed(){if(current!=null)current.onBackPressed();else host.exitMenus();}
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(current!=null&&current.handlePageKey(event))return true;
        if(popoverVisible&&current.rightReturns()&&event.getKeyCode()==KeyEvent.KEYCODE_DPAD_RIGHT){if(event.getAction()==KeyEvent.ACTION_UP)current.onBackPressed();return true;}
        return super.dispatchKeyEvent(event);
    }
    void close(){current=null;popoverVisible=false;dismiss();}
}
