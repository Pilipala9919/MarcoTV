// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Wi-Fi arcs drawn at the final display size, including focus contrast. */
final class SignalDrawable extends Drawable {
    private final int level;private final boolean secured,connected;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    SignalDrawable(int level){this(level,false,false);}
    SignalDrawable(int level,boolean secured,boolean connected){this.level=Math.max(0,Math.min(3,level));this.secured=secured;this.connected=connected;}
    @Override public boolean isStateful() { return true; }
    @Override protected boolean onStateChange(int[] state) { invalidateSelf(); return true; }
    @Override public void draw(Canvas canvas) {
        boolean focused = false;
        for (int state : getState()) if (state == android.R.attr.state_focused) focused = true;
        int color = focused ? Color.WHITE : 0xFFDCE1E9;
        Rect bounds = getBounds(); float unit = Math.min(bounds.width()/28f, bounds.height()/24f);
        canvas.save(); canvas.translate(bounds.right-14*unit, bounds.bottom-3*unit);
        paint.setColor(color); paint.setStyle(Paint.Style.FILL); canvas.drawCircle(0, 0, 2*unit, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2.4f*unit); paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i=1;i<=3;i++) {
            paint.setColor((color & 0xFFFFFF) | ((i<=level?255:55)<<24));
            float radius=(5+i*5)*unit;
            canvas.drawArc(new RectF(-radius,-radius,radius,radius),230,80,false,paint);
        }
        paint.setColor(color);
        if(secured){canvas.drawRoundRect(new RectF(-32*unit,-10*unit,-24*unit,-2*unit),unit,unit,paint);canvas.drawArc(new RectF(-31*unit,-16*unit,-25*unit,-5*unit),180,180,false,paint);}
        if(connected){canvas.drawLine(-49*unit,-7*unit,-46*unit,-4*unit,paint);canvas.drawLine(-46*unit,-4*unit,-40*unit,-12*unit,paint);}
        canvas.restore();
    }
    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
