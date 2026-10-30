// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.ArrayList;

/** Remote-first Marco TV panel. No platform AlertDialog layouts or buttons. */
final class TvDialog extends Dialog {
    private final Builder spec;
    private LinearLayout body;
    private LinearLayout panel;
    private boolean returning;
    private PanelWindow surface;
    private boolean showing;
    private View savedFocus;
    private DialogInterface.OnDismissListener dismissListener;
    private Runnable leaveAction;
    void onLeave(Runnable action){leaveAction=action;}
    void leave(){if(leaveAction!=null){Runnable action=leaveAction;leaveAction=null;action.run();}}
    void focusItem(int index){for(TextView item:itemViews)if(item.getTag() instanceof Integer&&(Integer)item.getTag()==index){savedFocus=item;break;}}
    void rememberFocus(){if(showing&&panel!=null){View v=panel.findFocus();if(v!=null)savedFocus=v;}}
    void restoreFocus(){if(savedFocus!=null&&savedFocus.requestFocus())return;if(spec.single&&spec.selected>=0&&spec.selected<choices.size()&&choices.get(spec.selected).requestFocus())return;for(View v:choices)if(v.requestFocus())return;}
    @Override public void show(){
        forwardDismiss=false;
        if(panel==null)super.create();
        surface=((MainActivity)spec.activity).panelWindow();showing=true;
        surface.present(this,panel,spec.preview!=null,spec.centered,spec.network);onStart();
    }
    @Override public boolean isShowing(){return showing&&surface!=null&&surface.displays(this);}
    @Override public void dismiss(){if(!showing)return;if(!forwardDismiss)rememberFocus();showing=false;onStop();if(dismissListener!=null)dismissListener.onDismiss(this);}
    @Override public void setOnDismissListener(DialogInterface.OnDismissListener listener){dismissListener=listener;}
    @Override public Window getWindow(){return surface==null?super.getWindow():surface.getWindow();}
    @Override public View getCurrentFocus(){return panel==null?null:panel.findFocus();}
    private static final android.animation.ArgbEvaluator COLORS=new android.animation.ArgbEvaluator();
    void returning(boolean value){returning=value;}
    private final ArrayList<View> choices = new ArrayList<>();
    private final ArrayList<TextView> itemViews = new ArrayList<>();
    private ArrayList<Integer> itemOrder;
    private Runnable backAction;
    private TextView hint;
    private TextView messageView;
    void updateMessage(String value){spec.message=value;if(messageView!=null)messageView.setText(value);}
    private int moving=-1, lastItem, itemsOffset;
    private boolean swallowMoveRelease;
    private boolean forwardDismiss;
    boolean isForwardDismiss(){return forwardDismiss;}
    String pageKey(){return spec.pageId!=null?spec.pageId:spec.orderKey==null?spec.title:spec.orderKey;}
    boolean reorderable(){return spec.orderKey!=null;}
    String previewKey(){return spec.preview;}
    void setPreviewScene(String key){spec.preview=key;if(isShowing())((MainActivity)spec.activity).home.preview(key);}
    boolean isPersonalization(){return "personalization".equals(pageKey());}
    int networkHeight(){return Math.min(350,94+(spec.items==null?0:spec.items.length)*43);}
    void setBackAction(Runnable action){backAction=action;}
    @Override public void onBackPressed(){if(moving>=0){finishMove();return;}if(backAction!=null)backAction.run();else super.onBackPressed();}
    private TvDialog(Builder spec) { super(spec.activity); this.spec = spec; }
    private int dp(float value) { return Math.round(value * getContext().getResources().getDisplayMetrics().density); }
    static GradientDrawable background(int color, float radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(radius); return d;
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); requestWindowFeature(Window.FEATURE_NO_TITLE);
        panel = new LinearLayout(getContext()); panel.setOrientation(LinearLayout.VERTICAL);
        // Let the window renderer update scrolling rows without a panel-sized software cache.
        panel.setLayerType(View.LAYER_TYPE_NONE, null);
        boolean preview=spec.preview!=null;
        panel.setPadding(dp(spec.network?14:preview?16:24), dp(spec.network?14:preview?14:22), dp(spec.network?14:preview?16:24), dp(spec.network?10:preview?14:18));
        panel.setClickable(true);
        GradientDrawable bg = background(0xF51D2028, dp(24)); bg.setStroke(dp(1), 0x20FFFFFF); panel.setBackground(bg);
        TextView brand = text("M A R C O T V", 10, 0xFFB0B8C6); brand.setLetterSpacing(.1f);
        if(!preview&&!spec.network)panel.addView(brand);
        TextView title = text(spec.title, preview||spec.network?19:25, Color.WHITE); title.setTypeface(Typeface.create("sans-serif-medium", 0));
        title.setMaxLines(2); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(dp(spec.network?6:0), dp(preview||spec.network?0:8), 0, dp(preview||spec.network?8:14)); panel.addView(title);
        ScrollView scroll = new MotionScrollView(getContext()); scroll.setFillViewport(false); scroll.setClipToPadding(true);
        scroll.setVerticalScrollBarEnabled(false);
        body = new LinearLayout(getContext()); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(3), dp(3), dp(3), dp(3));
        scroll.addView(body);
        if (spec.message != null && !preview) {
            TextView message = text(spec.message, spec.network?12:16, 0xFFBDC4CF); message.setLineSpacing(dp(spec.network?2:5), 1);
            messageView=message;
            message.setPadding(dp(6),0,dp(6),dp(spec.network?8:18));
            body.addView(message);
        }
        if (spec.view != null) {
            View input = spec.view;
            if (input instanceof EditText) {
                EditText edit = (EditText) input; edit.setTextColor(Color.WHITE); edit.setHintTextColor(0xFF919BAD);
                edit.setTextSize(18); edit.setPadding(dp(18), dp(13), dp(18), dp(13));
                edit.setBackground(background(0xFF303642, dp(12)));
            }
            body.addView(input, new LinearLayout.LayoutParams(-1, -2)); choices.add(input);
        }
        itemsOffset=body.getChildCount();
        if(spec.items!=null){
            String[] ids=spec.ids;
            if(ids==null){ids=new String[spec.items.length];for(int i=0;i<ids.length;i++)ids[i]=String.valueOf(i);}
            itemOrder=MenuOrder.resolve(spec.orderKey==null?"":getContext().getSharedPreferences("menu_order",0).getString(spec.orderKey,""),ids);
        }
        if (spec.items != null) for (int position = 0; position < itemOrder.size(); position++) {
            final int index = itemOrder.get(position);
            TextView item = row(spec.items[index], spec.single && index == spec.selected);
            if(spec.icons!=null&&index<spec.icons.length&&spec.icons[index]!=null){
                android.graphics.drawable.Drawable icon=spec.icons[index];icon.setBounds(0,0,dp(32),dp(32));item.setCompoundDrawables(icon,null,null,null);item.setCompoundDrawablePadding(dp(14));item.setMaxLines(2);item.setEllipsize(android.text.TextUtils.TruncateAt.END);
            }
            if (spec.signals != null && index < spec.signals.length && spec.signals[index] >= 0) {
                item.setSingleLine(true); item.setEllipsize(android.text.TextUtils.TruncateAt.END);
                SignalDrawable signal = new SignalDrawable(spec.signals[index],spec.secured!=null&&spec.secured[index],spec.connected!=null&&spec.connected[index]);
                signal.setBounds(0, 0, dp(spec.network?68:28), dp(24));
                item.setCompoundDrawables(null, null, signal, null); item.setCompoundDrawablePadding(dp(spec.network?8:20));
                item.setContentDescription(spec.items[index] + "，信号" + new String[]{"弱", "一般", "良好", "强"}[spec.signals[index]]);
            }
            if (spec.single) item.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info); info.setCheckable(true); info.setChecked(index == spec.selected);
                }
            });
            if(spec.network&&index==0&&spec.wifiEnabled!=null){
                ToggleDrawable toggle=new ToggleDrawable(spec.wifiEnabled);toggle.setBounds(0,0,dp(52),dp(32));
                item.setCompoundDrawables(null,null,toggle,null);item.setCompoundDrawablePadding(dp(12));
                item.setContentDescription("Wi-Fi，"+(spec.wifiEnabled?"已开启":"已关闭"));
                item.setAccessibilityDelegate(new View.AccessibilityDelegate(){
                    @Override public void onInitializeAccessibilityNodeInfo(View view,android.view.accessibility.AccessibilityNodeInfo info){
                        super.onInitializeAccessibilityNodeInfo(view,info);info.setClassName("android.widget.Switch");
                        info.setCheckable(true);info.setChecked(spec.wifiEnabled);
                    }
                });
            }
            item.setOnClickListener(v -> {
                if(!isShowing())return;
                savedFocus=item;
                // Close before invoking a callback so nested panels retain their focus/lifecycle.
                forwardDismiss=true;dismiss(); if (spec.itemClick != null) spec.itemClick.onClick(this, index);
            });
            item.setTag(index);itemViews.add(item);
            if(spec.orderKey!=null)item.setOnLongClickListener(v->{beginMove(index,true);return true;});
            if(spec.network){item.setTextSize(14);item.setSingleLine(true);item.setEllipsize(android.text.TextUtils.TruncateAt.END);item.setMinHeight(dp(37));item.setPadding(dp(10),dp(6),dp(10),dp(6));}
            body.addView(item,rowParams());choices.add(item);
        }
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout buttons = new LinearLayout(getContext()); buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(13), 0, 0);
        for (ButtonSpec action : spec.buttons) {
            TextView button = row(action.text, false); button.setGravity(Gravity.CENTER); button.setTextSize(15);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(preview?34:44), 1); p.setMargins(dp(3), 0, dp(3), 0);
            buttons.addView(button, p); choices.add(button);
            button.setOnClickListener(v -> { if(!isShowing())return;forwardDismiss=false;if(!action.stayOpen)dismiss(); if (action.callback != null) action.callback.onClick(this, action.which); });
        }
        if(spec.orderKey!=null){
            TextView sort=row("调整顺序",false);sort.setTextSize(15);sort.setGravity(Gravity.CENTER);
            buttons.addView(sort,new LinearLayout.LayoutParams(0,dp(44),1));choices.add(sort);
            sort.setOnClickListener(v->beginMove(lastItem,false));
        }
        if (buttons.getChildCount()>0) panel.addView(buttons);
        hint = text(spec.orderKey==null?"返回上一级  ·  Home 回桌面":"长按确定调整顺序  ·  返回上一级  ·  Home 回桌面", 11, 0xFF8993A4);
        hint.setPadding(dp(3), dp(14), 0, 0); if(!preview&&!spec.network)panel.addView(hint);
        setContentView(panel);
        Window w = getWindow();
        w.setBackgroundDrawableResource(android.R.color.transparent);
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        w.setDimAmount(preview?0:.48f);
        w.getDecorView().setSystemUiVisibility(5894);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        int screenW = getContext().getResources().getDisplayMetrics().widthPixels;
        int screenH = getContext().getResources().getDisplayMetrics().heightPixels;
        w.setLayout(Math.min(dp(preview?620:510), screenW - dp(80)), Math.min(dp(preview?188:458), screenH - dp(48)));
        w.setGravity(preview?Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL:Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        WindowManager.LayoutParams attrs = w.getAttributes(); attrs.x = preview?0:dp(32);attrs.y=preview?dp(12):0; w.setAttributes(attrs);
        if (!choices.isEmpty()) choices.get(spec.single && spec.selected >= 0 && spec.selected < choices.size() ? spec.selected : 0).requestFocus();
        w.setWindowAnimations(getContext().getResources().getIdentifier("PanelMotion","style",getContext().getPackageName()));
    }
    private int motionDuration(){return Math.max(120,Math.min(500,getContext().getSharedPreferences("home",0).getInt("motion_duration",260)));}
    @Override protected void onStart(){
        super.onStart();
        // PanelWindow owns both sides of the transition.
    }
    @Override protected void onStop(){super.onStop();}
    boolean isReturning(){return returning;}
    private void beginMove(int index,boolean fromHold){
        moving=index;swallowMoveRelease=fromHold;
        hint.setText("上下移动  ·  确定放下  ·  返回结束排序");
        for(TextView item:itemViews)if((Integer)item.getTag()==index){item.setText("↕  "+spec.items[index]);item.requestFocus();break;}
    }
    private void finishMove(){
        for(TextView item:itemViews){item.setText(spec.items[(Integer)item.getTag()]);item.setPressed(false);}
        moving=-1;swallowMoveRelease=false;hint.setText("长按确定调整顺序  ·  返回上一级  ·  Home 回桌面");
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){return surface!=null?surface.dispatchKeyEvent(event):super.dispatchKeyEvent(event);}
    boolean handlePageKey(KeyEvent event){
        int key=event.getKeyCode();
        // Some TV remotes report a long hold as a repeated key event rather than
        // allowing the View's delayed long-click callback to run first.
        if(moving<0&&spec.orderKey!=null&&event.getAction()==KeyEvent.ACTION_DOWN
                &&(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER)
                &&event.getRepeatCount()>0){
            View focused=getCurrentFocus();
            if(focused!=null&&focused.getTag() instanceof Integer){
                focused.cancelLongPress();focused.setPressed(false);
                beginMove((Integer)focused.getTag(),true);return true;
            }
        }
        if(moving>=0){
            if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN){
                if(event.getAction()==KeyEvent.ACTION_DOWN){
                    int at=itemOrder.indexOf(moving),next=at+(key==KeyEvent.KEYCODE_DPAD_UP?-1:1);
                    if(next>=0&&next<itemOrder.size()){
                        itemOrder.remove(at);itemOrder.add(next,moving);
                        TextView selected=null;for(TextView item:itemViews)if((Integer)item.getTag()==moving)selected=item;
                        body.removeView(selected);body.addView(selected,itemsOffset+next,rowParams());selected.requestFocus();
                        getContext().getSharedPreferences("menu_order",0).edit().putString(spec.orderKey,MenuOrder.encode(itemOrder,spec.ids)).apply();
                    }
                }return true;
            }
            if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER){
                if(event.getAction()==KeyEvent.ACTION_UP){if(swallowMoveRelease)swallowMoveRelease=false;else finishMove();}return true;
            }
            if(key==KeyEvent.KEYCODE_BACK){if(event.getAction()==KeyEvent.ACTION_UP)finishMove();return true;}
        }
        return false;
    }
    private TextView text(String value, int size, int color) {
        TextView t = new TextView(getContext()); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setFontFeatureSettings("kern"); return t;
    }
    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, dp(3), 0, dp(3)); return p;
    }
    private TextView row(String value, boolean checked) {
        TextView t = text(value, 17, checked ? 0xFFE0EBFF : 0xFFDCE1E9);
        t.setMinHeight(dp(47)); t.setGravity(Gravity.CENTER_VERTICAL); t.setPadding(dp(17), dp(10), dp(17), dp(10));
        t.setFocusable(true); t.setFocusableInTouchMode(true); t.setClickable(true); t.setBackground(background(checked ? 0xFF344258 : 0xFF292E38, dp(12)));
        t.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { v.requestFocus(); return true; }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) { v.performClick(); return true; }
            return true;
        });
        final GradientDrawable fill=background(checked?0xFF344258:0xFF292E38,dp(12));t.setBackground(fill);
        UiMotion focusMotion=new UiMotion(t,0,30,progress->{
            fill.setColor((Integer)COLORS.evaluate(progress,checked?0xFF344258:0xFF292E38,0xFF3A586C));
            fill.setStroke(dp(1),(Integer)COLORS.evaluate(progress,0x00ABD6DF,0xFFABD6DF));
            t.setTextColor((Integer)COLORS.evaluate(progress,0xFFDCE1E9,Color.WHITE));
        });
        t.setOnFocusChangeListener((v, focus) -> {
            if(focus&&v.getTag() instanceof Integer)lastItem=(Integer)v.getTag();
            focusMotion.to(focus?1:0);
        });
        return t;
    }
    private static final class ButtonSpec {
        String text; int which; boolean stayOpen; DialogInterface.OnClickListener callback;
        ButtonSpec(String t, int w, DialogInterface.OnClickListener c) { text=t; which=w; callback=c; }
    }
    static final class Builder {
        final Activity activity; String title="", message; String[] items; int selected=-1;
        boolean single,centered,network; Boolean wifiEnabled; View view; DialogInterface.OnClickListener itemClick;
        boolean[] secured,connected;
        String pageId,orderKey,preview;String[] ids; int[] signals; android.graphics.drawable.Drawable[] icons;
        final ArrayList<ButtonSpec> buttons = new ArrayList<>();
        Builder(Activity activity) { this.activity=activity; }
        Builder setTitle(String s) { title=s; return this; }
        Builder setPageId(String s) { pageId=s; return this; }
        Builder setMessage(String s) { message=s; return this; }
        Builder setView(View v) { view=v; return this; }
        Builder centered(){centered=true;return this;}
        Builder network(){network=true;return this;}
        Builder setWifiEnabled(boolean enabled){wifiEnabled=enabled;return this;}
        Builder setPreview(String key){preview=key;return this;}
        Builder setOrder(String key,String... stableIds){orderKey=key;ids=stableIds;return this;}
        Builder setItems(String[] a, DialogInterface.OnClickListener c) { items=a; itemClick=c; return this; }
        Builder setIcons(android.graphics.drawable.Drawable[] values){icons=values;return this;}
        Builder setNetworkStates(boolean[] locks,boolean[] current){secured=locks;connected=current;return this;}
        Builder setSignals(int[] levels) { signals=levels; return this; }
        Builder setSingleChoiceItems(String[] a, int i, DialogInterface.OnClickListener c) { single=true; selected=i; return setItems(a,c); }
        Builder setPositiveButton(String s, DialogInterface.OnClickListener c) { buttons.add(new ButtonSpec(s, DialogInterface.BUTTON_POSITIVE,c)); return this; }
        Builder setNegativeButton(String s, DialogInterface.OnClickListener c) { buttons.add(new ButtonSpec(s, DialogInterface.BUTTON_NEGATIVE,c)); return this; }
        Builder setNeutralButton(String s, DialogInterface.OnClickListener c) { buttons.add(new ButtonSpec(s, DialogInterface.BUTTON_NEUTRAL,c)); return this; }
        Builder setInlineButton(String s,DialogInterface.OnClickListener c){ButtonSpec b=new ButtonSpec(s,DialogInterface.BUTTON_NEUTRAL,c);b.stayOpen=true;buttons.add(b);return this;}
        TvDialog create() { return new TvDialog(this); }
    }
}
