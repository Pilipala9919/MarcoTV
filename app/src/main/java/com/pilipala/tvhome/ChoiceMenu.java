// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;

/** Discrete choices use a menu; only continuous values use a slider. */
final class ChoiceMenu extends LinearLayout {
    interface Listener { void changed(int value); }
    private final String title;
    private final String[] names;
    private final Listener listener;
    private final Runnable focused;
    private final Button heading;
    private final LinearLayout menu;
    private final RadioButton[] options;
    private int value;
    ChoiceMenu(Context context,String title,String[] names,int value,Listener listener,Runnable focused){
        super(context);this.title=title;this.names=names.clone();this.listener=listener;this.focused=focused;
        this.value=Math.max(0,Math.min(names.length-1,value));setOrientation(VERTICAL);
        heading=new Button(context);heading.setAllCaps(false);heading.setTextSize(13);heading.setTextColor(Color.WHITE);
        heading.setGravity(android.view.Gravity.LEFT|android.view.Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(12),dp(8),dp(12),dp(8));heading.setMinHeight(0);heading.setMinimumHeight(0);
        heading.setOnClickListener(v->{if(isExpanded())closeMenu();else openMenu();});
        heading.setOnFocusChangeListener((v,hasFocus)->{refresh();if(hasFocus)focused.run();});
        addView(heading,new LinearLayout.LayoutParams(-1,dp(66)));
        menu=new LinearLayout(context);menu.setOrientation(VERTICAL);menu.setVisibility(GONE);menu.setPadding(dp(6),dp(4),dp(6),dp(4));
        menu.setBackground(TvDialog.background(0xFF192532,dp(12)));addView(menu,new LinearLayout.LayoutParams(-1,-2));
        options=new RadioButton[names.length];
        for(int i=0;i<names.length;i++){
            final int selected=i;RadioButton row=new RadioButton(context);options[i]=row;
            row.setText(names[i]);row.setTextSize(13);row.setTextColor(Color.WHITE);row.setPadding(dp(8),0,dp(8),0);
            row.setButtonTintList(ColorStateList.valueOf(0xFFA9DCE2));row.setFocusable(true);row.setFocusableInTouchMode(true);
            row.setOnFocusChangeListener((v,hasFocus)->{refresh();if(hasFocus)focused.run();});
            row.setOnClickListener(v->{setValue(selected);closeMenu();});
            menu.addView(row,new LinearLayout.LayoutParams(-1,dp(42)));
        }
        refresh();
    }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    int getValue(){return value;}
    void setValue(int next){next=Math.max(0,Math.min(names.length-1,next));boolean changed=next!=value;value=next;refresh();if(changed)listener.changed(value);}
    boolean isExpanded(){return menu.getVisibility()==VISIBLE;}
    void openMenu(){
        menu.setVisibility(VISIBLE);refresh();options[value].requestFocus();
        post(()->requestRectangleOnScreen(new Rect(0,0,getWidth(),getHeight()),true));
    }
    void closeMenu(){menu.setVisibility(GONE);refresh();heading.requestFocus();}
    void focusHeading(){heading.requestFocus();}
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(isExpanded()&&event.getKeyCode()==KeyEvent.KEYCODE_BACK){if(event.getAction()==KeyEvent.ACTION_UP)closeMenu();return true;}
        return super.dispatchKeyEvent(event);
    }
    private void refresh(){
        if(heading==null)return;
        heading.setText(title+"\n"+names[value]+(menu!=null&&isExpanded()?"  ▴":"  ▾"));
        heading.setContentDescription(title+"，"+names[value]+"，按确定选择");
        heading.setBackground(TvDialog.background(heading.hasFocus()?0xFF354B5D:0xFF242D38,dp(12)));
        if(options!=null)for(int i=0;i<options.length;i++)if(options[i]!=null){options[i].setChecked(i==value);options[i].setBackground(TvDialog.background(options[i].hasFocus()?0xFF354B5D:0xFF192532,dp(8)));}
    }
}
