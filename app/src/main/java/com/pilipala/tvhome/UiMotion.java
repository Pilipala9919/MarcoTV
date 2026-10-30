// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.view.Choreographer;
import android.view.View;

/** Retargetable motion using the same critically damped response as desktop cards. */
final class UiMotion implements Choreographer.FrameCallback {
    interface Update { void apply(float value); }
    final MotionSpring spring;
    private final View owner;
    private final Update update;
    private Runnable completion;
    private boolean scheduled;
    private boolean pending;
    private long lastFrame;
    private final View.OnAttachStateChangeListener attachment;
    UiMotion(View owner,float initial,float frequency,Update update){
        this.owner=owner;this.update=update;spring=new MotionSpring(initial,frequency,.001f);
        attachment=new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View v){if(pending)schedule();}
            public void onViewDetachedFromWindow(View v){Choreographer.getInstance().removeFrameCallback(UiMotion.this);scheduled=false;lastFrame=0;}
        };owner.addOnAttachStateChangeListener(attachment);
    }
    static float speed(View view){android.content.Context context=view.getContext();android.content.SharedPreferences prefs=context instanceof MainActivity?((MainActivity)context).appearance():context.getSharedPreferences("home",0);return 260f/Math.max(120,Math.min(500,prefs.getInt("motion_duration",260)));}
    void snap(float value){cancel();spring.snap(value);update.apply(value);}
    void to(float value){to(value,null);}
    void to(float value,Runnable end){
        completion=end;spring.target(value);pending=true;schedule();
    }
    private void schedule(){if(!scheduled&&owner.isAttachedToWindow()){scheduled=true;lastFrame=0;Choreographer.getInstance().postFrameCallback(this);}}
    void cancel(){Choreographer.getInstance().removeFrameCallback(this);scheduled=false;pending=false;lastFrame=0;completion=null;}
    void dispose(){cancel();owner.removeOnAttachStateChangeListener(attachment);}
    @Override public void doFrame(long nanos){
        if(!owner.isAttachedToWindow()){scheduled=false;lastFrame=0;return;}
        float dt=lastFrame==0?1f/60:Math.min(.05f,(nanos-lastFrame)/1e9f);lastFrame=nanos;
        boolean moving=spring.step(dt*speed(owner));update.apply(spring.value);
        if(moving)Choreographer.getInstance().postFrameCallback(this);
        else{scheduled=false;pending=false;lastFrame=0;Runnable end=completion;completion=null;if(end!=null)end.run();}
    }
}
