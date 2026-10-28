// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.app.Dialog;
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
        if(current!=null)current.rememberFocus();
        root.removeAllViews();current=page;
        if(content.getParent()!=null)((ViewGroup)content.getParent()).removeView(content);
        root.setBackgroundColor(preview||network?0x00000000:0x7A050B13);
        root.setOnClickListener(network?v->page.onBackPressed():null);
        root.setClickable(network);
        int width=getContext().getResources().getDisplayMetrics().widthPixels;
        int height=getContext().getResources().getDisplayMetrics().heightPixels;
        int category=preview||centered||network?-1:host.settingsCategory();
        View presented=content;
        if(category>=0){
            LinearLayout split=new LinearLayout(getContext());split.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout rail=new LinearLayout(getContext());rail.setOrientation(LinearLayout.VERTICAL);rail.setPadding(dp(12),dp(20),dp(12),dp(16));
            rail.setBackground(TvDialog.background(0xFA18232F,dp(24)));
            TextView label=new TextView(getContext());label.setText("设置分类");label.setTextColor(0xFF91ABB8);label.setTextSize(13);label.setPadding(dp(8),0,0,dp(14));rail.addView(label);
            for(int i:MenuOrder.resolve(host.getSharedPreferences("menu_order",0).getString("main_v2",""),SettingsCatalog.ROOT_IDS)){
                final int index=i;TextView tab=new TextView(getContext());tab.setText(SettingsCatalog.ROOT_LABELS[i]);tab.setTextSize(15);tab.setTextColor(0xFFE0EAF0);tab.setGravity(Gravity.CENTER_VERTICAL);tab.setPadding(dp(12),0,dp(8),0);tab.setFocusable(true);tab.setClickable(true);
                tab.setBackground(TvDialog.background(i==category?0xFF30495B:0x0018232F,dp(12)));
                tab.setOnFocusChangeListener((v,on)->{android.graphics.drawable.GradientDrawable bg=TvDialog.background(on?0xFF3A586C:index==category?0xFF30495B:0x0018232F,dp(12));bg.setStroke(dp(1),on?0xFFABD6DF:0);v.setBackground(bg);});
                tab.setOnClickListener(v->host.switchCategory(index));rail.addView(tab,new LinearLayout.LayoutParams(-1,dp(46)));
            }
            ScrollView navigation=new ScrollView(getContext());navigation.setVerticalScrollBarEnabled(false);navigation.addView(rail);split.addView(navigation,new LinearLayout.LayoutParams(dp(160),-1));
            LinearLayout.LayoutParams detail=new LinearLayout.LayoutParams(0,-1,1);detail.leftMargin=dp(12);split.addView(content,detail);presented=split;
        }
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(Math.min(dp(preview?300:centered?780:network?300:category>=0?640:470),width-dp(48)),Math.min(dp(preview?218:centered?425:network?page.networkHeight():500),height-dp(32)));
        p.gravity=centered?Gravity.CENTER:Gravity.RIGHT|Gravity.CENTER_VERTICAL;
        p.rightMargin=centered?0:dp(24);
        if(network){
            p.gravity=Gravity.TOP|Gravity.LEFT;p.rightMargin=0;
            p.leftMargin=Math.max(dp(16),Math.min(width-p.width-dp(16),Math.round(host.home.wifiAnchorX()-p.width*.5f)));
            p.topMargin=Math.min(Math.round(host.home.wifiAnchorY()+dp(27)),height-p.height-dp(16));
        }
        root.addView(presented,p);page.restoreFocus();
    }
    boolean displays(TvDialog page){return current==page&&isShowing();}
    @Override public void onBackPressed(){if(current!=null)current.onBackPressed();else host.exitMenus();}
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(current!=null&&current.handlePageKey(event))return true;
        return super.dispatchKeyEvent(event);
    }
    void close(){current=null;dismiss();}
}
