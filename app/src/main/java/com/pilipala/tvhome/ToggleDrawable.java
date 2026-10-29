// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Compact switch indicator; the enclosing remote-focusable row owns interaction. */
final class ToggleDrawable extends Drawable {
    private final boolean enabled;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track=new RectF();
    ToggleDrawable(boolean enabled){this.enabled=enabled;}
    @Override public boolean isStateful(){return true;}
    @Override protected boolean onStateChange(int[] state){invalidateSelf();return true;}
    @Override public void draw(Canvas canvas){
        Rect bounds=getBounds();float unit=Math.min(bounds.width()/52f,bounds.height()/32f);
        float x=bounds.centerX()-26*unit,y=bounds.centerY()-16*unit;
        track.set(x+2*unit,y+2*unit,x+50*unit,y+30*unit);
        paint.setStyle(Paint.Style.FILL);paint.setColor(enabled?0xFF34C759:0xFF59616D);
        canvas.drawRoundRect(track,14*unit,14*unit,paint);
        paint.setColor(0x30000000);canvas.drawCircle(x+(enabled?36:16)*unit,y+17*unit,12*unit,paint);
        paint.setColor(Color.WHITE);canvas.drawCircle(x+(enabled?36:16)*unit,y+16*unit,12*unit,paint);
        for(int state:getState())if(state==android.R.attr.state_focused){
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(unit);paint.setColor(0xFFE4F4FF);
            track.inset(-1.5f*unit,-1.5f*unit);canvas.drawRoundRect(track,16*unit,16*unit,paint);break;
        }
        paint.setStyle(Paint.Style.FILL);
    }
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
