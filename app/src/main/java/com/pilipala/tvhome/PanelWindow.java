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
        root.removeAllViews();current=page;
        if(content.getParent()!=null)((ViewGroup)content.getParent()).removeView(content);
        root.setBackgroundColor(preview||network?0x00000000:0x7A050B13);
        root.setOnClickListener(network?v->page.onBackPressed():null);
        root.setClickable(network);
        int width=getContext().getResources().getDisplayMetrics().widthPixels;
        int height=getContext().getResources().getDisplayMetrics().heightPixels;
        content.setAlpha(1);content.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(Math.min(dp(page.isPersonalization()?290:preview?290:centered?780:network?300:320),width-dp(48)),Math.min(dp(page.isPersonalization()?500:preview?218:centered?425:network?page.networkHeight():500),height-dp(32)));
        p.gravity=centered?Gravity.CENTER:Gravity.RIGHT|Gravity.CENTER_VERTICAL;
        p.rightMargin=centered?0:dp(24);
        if(network){
            p.gravity=Gravity.TOP|Gravity.LEFT;p.rightMargin=0;
            p.leftMargin=Math.max(dp(16),Math.min(width-p.width-dp(16),Math.round(host.home.wifiAnchorX()-p.width*.5f)));
            p.topMargin=Math.min(Math.round(host.home.wifiAnchorY()+dp(27)),height-p.height-dp(16));
        }
        root.addView(content,p);
        page.restoreFocus();
        content.post(()->{if(current==page&&isShowing())page.restoreFocus();});
    }
    boolean displays(TvDialog page){return current==page&&isShowing();}
    @Override public void onBackPressed(){if(current!=null)current.onBackPressed();else host.exitMenus();}
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(current!=null&&current.handlePageKey(event))return true;
        return super.dispatchKeyEvent(event);
    }
    void close(){current=null;dismiss();}
}
