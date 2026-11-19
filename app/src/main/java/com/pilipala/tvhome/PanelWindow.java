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
    private View activeContent;
    private final java.util.IdentityHashMap<View,PageMotion> motions=new java.util.IdentityHashMap<>();
    private UiMotion shade;
    private int shadeR = 11, shadeG = 14, shadeB = 20;
    private int generation;
    private boolean closing;
    private final class PageMotion {
        final UiMotion x,y,alpha;
        PageMotion(View view){
            x=new UiMotion(view,0,26,view::setTranslationX);
            y=new UiMotion(view,0,26,view::setTranslationY);
            alpha=new UiMotion(view,1,26,view::setAlpha);
        }
        void stop(){
            x.dispose();
            y.dispose();
            alpha.dispose();
        }
    }
    PanelWindow(MainActivity host){
        super(host);
        this.host=host;
    }
    private int dp(float n){
        return Math.round(n*getContext().getResources().getDisplayMetrics().density);
    }
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        root=new FrameLayout(getContext()){
            @Override public boolean onInterceptTouchEvent(MotionEvent event){
                if(closing||activeContent==null)return true;
                float x=event.getX()-activeContent.getX(),y=event.getY()-activeContent.getY();
                if(x<0||y<0||x>=activeContent.getWidth()||y>=activeContent.getHeight())return true;
                return super.onInterceptTouchEvent(event);
            }
        };
        setContentView(root);
        setCanceledOnTouchOutside(false);
        shade=new UiMotion(root,0,24,v->root.setBackgroundColor(Color.argb(Math.max(0,Math.min(255,Math.round(v))),shadeR,shadeG,shadeB)));
        Window w=getWindow();
        w.setBackgroundDrawableResource(android.R.color.transparent);
        w.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        w.setWindowAnimations(0);
        w.getDecorView().setSystemUiVisibility(5894);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        w.setLayout(-1,-1);
    }
    void present(TvDialog page,View content,boolean preview,boolean centered,boolean network){
        if(closing&&super.isShowing())dismissNow();
        if(!super.isShowing())show();
        closing=false;
        final int epoch=++generation;
        View outgoing=activeContent;
        // At most two live pages. Interruptions retain the currently visible position.
        for(int i=root.getChildCount()-1;i>=0;i--){
            View v=root.getChildAt(i);
            if(v!=outgoing&&v!=content){
                PageMotion old=motions.remove(v);
                if(old!=null)old.stop();
                root.removeView(v);
            }
        }
        current=page;
        activeContent=content;
        boolean fullscreen=page.isFullscreen();
        boolean inSettings=fullscreen||host.dialogs.hasPage("main");
        boolean floatingNetwork=network&&!inSettings;
        if(preview||floatingNetwork){
            shade.to(0);
        }else if(inSettings){
            shadeR=11;shadeG=14;shadeB=20;
            shade.to(255);
        }else if(centered){
            shadeR=5;shadeG=7;shadeB=11;
            shade.to(148);
        }else{
            shadeR=0;shadeG=0;shadeB=0;
            shade.to(64);
        }
        root.setOnClickListener(floatingNetwork?v->page.onBackPressed():null);
        root.setClickable(floatingNetwork);
        int width=getContext().getResources().getDisplayMetrics().widthPixels;
        int height=getContext().getResources().getDisplayMetrics().heightPixels;
        content.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        if(content instanceof ViewGroup)((ViewGroup)content).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        FrameLayout.LayoutParams p;
        if(fullscreen){
            p=new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT);
            p.gravity=Gravity.FILL;
        }else if(inSettings&&!preview){
            int targetW=centered?800:network?460:540;
            int targetH=centered?476:network?Math.max(page.networkHeight()+44,340):476;
            p=new FrameLayout.LayoutParams(Math.min(dp(targetW),width-dp(64)),Math.min(dp(targetH),height-dp(36)));
            p.gravity=Gravity.CENTER;
            p.rightMargin=0;
        }else{
            p=new FrameLayout.LayoutParams(Math.min(dp(page.isPersonalization()?276:preview?276:centered?800:floatingNetwork?300:360),
                    width-dp(48)),Math.min(dp(page.isPersonalization()?516:preview?252:centered?476:floatingNetwork?page.networkHeight():516),height-dp(24)));
            p.gravity=centered?Gravity.CENTER:Gravity.RIGHT|Gravity.CENTER_VERTICAL;
            p.rightMargin=centered?0:dp(page.isPersonalization()||preview?20:24);
            if(floatingNetwork){
                p.gravity=Gravity.TOP|Gravity.LEFT;
                p.rightMargin=0;
                p.leftMargin=Math.max(dp(16),Math.min(width-p.width-dp(16),Math.round(host.home.wifiAnchorX()-p.width*.5f)));
                p.topMargin=Math.min(Math.round(host.home.wifiAnchorY()+dp(27)),height-p.height-dp(16));
            }
        }
        boolean attached=content.getParent()==root;
        if(!attached){
            if(content.getParent()!=null)((ViewGroup)content.getParent()).removeView(content);
            root.addView(content,p);
        }
        else{
            content.setLayoutParams(p);
            content.bringToFront();
        }
        PageMotion enter=motions.get(content);
        if(enter==null){
            enter=new PageMotion(content);
            motions.put(content,enter);
        }
        if(!attached){
            enter.x.snap(floatingNetwork?0:dp(page.isReturning()?-20:28));
            enter.y.snap(floatingNetwork?-dp(8):0);
            enter.alpha.snap(1);
        }
        enter.x.to(0);
        enter.y.to(0);
        enter.alpha.to(1);
        if(outgoing!=null&&outgoing!=content){
            outgoing.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            if(outgoing instanceof ViewGroup)((ViewGroup)outgoing).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            PageMotion leave=motions.get(outgoing);
            if(leave!=null){
                leave.x.to(dp(page.isReturning()?28:-20));
                leave.alpha.to(0,()->{
                    if(generation==epoch&&activeContent!=outgoing){
                        root.removeView(outgoing);
                        PageMotion old=motions.remove(outgoing);
                        if(old!=null)old.stop();
                    }
                });
            }
        }
        page.restoreFocus();
        content.post(()->{
            if(current==page&&isShowing())page.restoreFocus();
        });
    }
    boolean displays(TvDialog page){
        return current==page&&isShowing();
    }
    @Override public boolean isShowing(){
        return !closing&&super.isShowing();
    }
    @Override public void onBackPressed(){
        if(current!=null)current.onBackPressed();
        else host.exitMenus();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(closing){
            if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getKeyCode()!=KeyEvent.KEYCODE_BACK){
                dismissNow();
                return host.dispatchKeyEvent(event);
            }
            return true;
        }
        if(current!=null&&current.handlePageKey(event))return true;
        return super.dispatchKeyEvent(event);
    }
    void close(){
        if(host.isFinishing()||host.isDestroyed()){
            current=null;
            dismissNow();
            return;
        }
        current=null;
        if(!super.isShowing()||closing)return;
        closing=true;
        final int epoch=++generation;
        shade.to(0);
        PageMotion motion=motions.get(activeContent);
        if(motion==null){
            dismissNow();
            return;
        }
        motion.x.to(dp(24));
        motion.alpha.to(0,()->{
            if(closing&&generation==epoch)dismissNow();
        });
    }
    private void dismissNow(){
        generation++;
        for(PageMotion m:motions.values())m.stop();
        motions.clear();
        if(shade!=null)shade.cancel();
        activeContent=null;
        if(root!=null)root.removeAllViews();
        super.dismiss();
        closing=false;
    }
}
