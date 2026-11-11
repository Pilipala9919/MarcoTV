// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;

/**
 * Procedural vector graphics & animated radar views for the Casting Center.
 * Strictly gates self-invalidation on window & view visibility so backgrounded Activities consume zero CPU.
 */
final class CastGraphicView extends View {
    static final int KIND_STATUS_BEACON = 0;
    static final int KIND_CARD_DLNA = 1;
    static final int KIND_CARD_PHONE = 2;
    static final int KIND_CARD_MIRROR = 3;
    static final int KIND_ICON_DLNA_SWITCH = 4;
    static final int KIND_ICON_RADAR_SWITCH = 5;
    static final int KIND_ICON_SHIELD_SWITCH = 6;
    static final int KIND_RADAR_HERO = 7;
    static final int KIND_STEP_WIFI = 8;
    static final int KIND_STEP_VIDEO = 9;
    static final int KIND_STEP_CAST = 10;

    private final int kind;
    private boolean active;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();

    CastGraphicView(Context context, int kind, boolean active) {
        super(context);
        this.kind = kind;
        this.active = active;
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    void setActive(boolean value) {
        if (this.active != value) {
            this.active = value;
            invalidate();
        }
    }

    private boolean canAnimate() {
        if (!isAttachedToWindow() || getWindowVisibility() != VISIBLE || !isShown()) return false;
        Context ctx = getContext();
        if (ctx instanceof MainActivity && !((MainActivity) ctx).foreground) return false;
        return kind == KIND_RADAR_HERO || (kind == KIND_STATUS_BEACON && active);
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE && canAnimate()) invalidate();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE && canAnimate()) invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float cx = w * 0.5f, cy = h * 0.5f;
        float u = Math.min(w, h) / 48f;

        switch (kind) {
            case KIND_STATUS_BEACON:
                drawStatusBeacon(c, cx, cy, u);
                break;
            case KIND_CARD_DLNA:
                drawCardDlna(c, cx, cy, u);
                break;
            case KIND_CARD_PHONE:
                drawCardPhone(c, cx, cy, u);
                break;
            case KIND_CARD_MIRROR:
                drawCardMirror(c, cx, cy, u);
                break;
            case KIND_ICON_DLNA_SWITCH:
                drawSwitchDlna(c, cx, cy, u);
                break;
            case KIND_ICON_RADAR_SWITCH:
                drawSwitchRadar(c, cx, cy, u);
                break;
            case KIND_ICON_SHIELD_SWITCH:
                drawSwitchShield(c, cx, cy, u);
                break;
            case KIND_RADAR_HERO:
                drawRadarHero(c, cx, cy, u);
                break;
            case KIND_STEP_WIFI:
                drawStepBadge(c, cx, cy, u, 1);
                break;
            case KIND_STEP_VIDEO:
                drawStepBadge(c, cx, cy, u, 2);
                break;
            case KIND_STEP_CAST:
                drawStepBadge(c, cx, cy, u, 3);
                break;
        }
    }

    private void drawStatusBeacon(Canvas c, float cx, float cy, float u) {
        float phase = (SystemClock.uptimeMillis() % 2200L) / 2200f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(active ? 0x2234C759 : 0x1E6C7A8E);
        c.drawCircle(cx, cy, 22 * u, paint);
        if (active) {
            float r = (10 + phase * 12) * u;
            int alpha = Math.max(0, Math.min(255, Math.round((1f - phase) * 140)));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.2f * u);
            paint.setColor((alpha << 24) | 0x48E59A);
            c.drawCircle(cx, cy, r, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.4f * u);
        paint.setColor(active ? 0xFF8BE8F5 : 0xFF8393A7);
        rect.set(cx - 12 * u, cy - 9 * u, cx + 12 * u, cy + 8 * u);
        c.drawRoundRect(rect, 3.5f * u, 3.5f * u, paint);
        c.drawLine(cx - 5 * u, cy + 12 * u, cx + 5 * u, cy + 12 * u, paint);

        paint.setColor(active ? 0xFF48E59A : 0xFF718096);
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(cx - 7 * u, cy + 3 * u, 2f * u, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * u);
        rect.set(cx - 12 * u, cy - 2 * u, cx - 2 * u, cy + 8 * u);
        c.drawArc(rect, -90, 90, false, paint);
        rect.set(cx - 16 * u, cy - 6 * u, cx + 2 * u, cy + 12 * u);
        c.drawArc(rect, -90, 90, false, paint);
        if (canAnimate()) postInvalidateDelayed(48);
    }

    private void drawCardDlna(Canvas c, float cx, float cy, float u) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x225CE1E6);
        c.drawCircle(cx, cy, 23 * u, paint);

        paint.setColor(0x285CE1E6);
        rect.set(cx - 15 * u, cy - 12 * u, cx + 15 * u, cy + 8 * u);
        c.drawRoundRect(rect, 4 * u, 4 * u, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.4f * u);
        paint.setColor(0xFFB4ECF7);
        c.drawRoundRect(rect, 4 * u, 4 * u, paint);
        c.drawLine(cx - 6 * u, cy + 13 * u, cx + 6 * u, cy + 13 * u, paint);
        c.drawLine(cx, cy + 8 * u, cx, cy + 13 * u, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF5CE1E6);
        path.reset();
        path.moveTo(cx - 1.5f * u, cy - 6.5f * u);
        path.lineTo(cx + 6.5f * u, cy - 2f * u);
        path.lineTo(cx - 1.5f * u, cy + 2.5f * u);
        path.close();
        c.drawPath(path, paint);

        paint.setColor(0xFF48E59A);
        c.drawCircle(cx - 11 * u, cy + 4.5f * u, 1.8f * u, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.9f * u);
        rect.set(cx - 15.5f * u, cy, cx - 6.5f * u, cy + 9 * u);
        c.drawArc(rect, -90, 90, false, paint);
        rect.set(cx - 19f * u, cy - 3.5f * u, cx - 3f * u, cy + 12.5f * u);
        c.drawArc(rect, -90, 90, false, paint);
    }

    private void drawCardPhone(Canvas c, float cx, float cy, float u) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x2264DFDF);
        c.drawCircle(cx, cy, 23 * u, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * u);
        paint.setColor(0x998BE8F5);
        rect.set(cx - 3 * u, cy - 14 * u, cx + 16 * u, cy - 1 * u);
        c.drawRoundRect(rect, 3 * u, 3 * u, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF233647);
        rect.set(cx - 15 * u, cy - 7 * u, cx + 1 * u, cy + 15 * u);
        c.drawRoundRect(rect, 3.5f * u, 3.5f * u, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.3f * u);
        paint.setColor(0xFFB4ECF7);
        c.drawRoundRect(rect, 3.5f * u, 3.5f * u, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF5CE1E6);
        c.drawCircle(cx - 7 * u, cy + 4 * u, 3.2f * u, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.3f * u);
        paint.setColor(0xFF48E59A);
        c.drawLine(cx - 3 * u, cy, cx + 7 * u, cy - 8 * u, paint);
        c.drawLine(cx + 2 * u, cy - 8 * u, cx + 7 * u, cy - 8 * u, paint);
        c.drawLine(cx + 7 * u, cy - 8 * u, cx + 7 * u, cy - 3 * u, paint);
    }

    private void drawCardMirror(Canvas c, float cx, float cy, float u) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x228EC5FC);
        c.drawCircle(cx, cy, 23 * u, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x267FB8D8);
        rect.set(cx - 16 * u, cy - 12 * u, cx + 10 * u, cy + 6 * u);
        c.drawRoundRect(rect, 3.5f * u, 3.5f * u, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.2f * u);
        paint.setColor(0xFF9ACCE6);
        c.drawRoundRect(rect, 3.5f * u, 3.5f * u, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF21374A);
        rect.set(cx - 4 * u, cy - 4 * u, cx + 16 * u, cy + 13 * u);
        c.drawRoundRect(rect, 3.5f * u, 3.5f * u, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.3f * u);
        paint.setColor(0xFF5CE1E6);
        c.drawRoundRect(rect, 3.5f * u, 3.5f * u, paint);

        paint.setStrokeWidth(2f * u);
        paint.setColor(0xFF48E59A);
        c.drawLine(cx + 1 * u, cy + 3 * u, cx + 11 * u, cy + 3 * u, paint);
        c.drawLine(cx + 3 * u, cy + 7 * u, cx + 9 * u, cy + 7 * u, paint);
    }

    private void drawSwitchDlna(Canvas c, float cx, float cy, float u) {
        int mainColor = active ? 0xFF48E59A : 0xFF7B8B9E;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(active ? 0x2248E59A : 0x1A7B8B9E);
        c.drawCircle(cx, cy, 22 * u, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3.2f * u);
        paint.setColor(mainColor);
        rect.set(cx - 13 * u, cy - 10 * u, cx + 13 * u, cy + 10 * u);
        c.drawRoundRect(rect, 4 * u, 4 * u, paint);

        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(cx - 8 * u, cy + 5 * u, 2.2f * u, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.6f * u);
        rect.set(cx - 14 * u, cy - 1 * u, cx - 2 * u, cy + 11 * u);
        c.drawArc(rect, -90, 90, false, paint);
    }

    private void drawSwitchRadar(Canvas c, float cx, float cy, float u) {
        int mainColor = active ? 0xFF5CE1E6 : 0xFF7B8B9E;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(active ? 0x225CE1E6 : 0x1A7B8B9E);
        c.drawCircle(cx, cy, 22 * u, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.8f * u);
        paint.setColor(mainColor);
        c.drawCircle(cx, cy, 13 * u, paint);
        paint.setStrokeWidth(2.2f * u);
        c.drawCircle(cx, cy, 7 * u, paint);
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, 2.8f * u, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.6f * u);
        c.drawLine(cx, cy, cx + 9 * u, cy - 9 * u, paint);
    }

    private void drawSwitchShield(Canvas c, float cx, float cy, float u) {
        int mainColor = active ? 0xFF48E59A : 0xFF7B8B9E;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(active ? 0x2248E59A : 0x1A7B8B9E);
        c.drawCircle(cx, cy, 22 * u, paint);

        path.reset();
        path.moveTo(cx, cy - 14 * u);
        path.lineTo(cx + 12 * u, cy - 9 * u);
        path.lineTo(cx + 11 * u, cy + 3 * u);
        path.quadTo(cx + 9 * u, cy + 12 * u, cx, cy + 15 * u);
        path.quadTo(cx - 9 * u, cy + 12 * u, cx - 11 * u, cy + 3 * u);
        path.lineTo(cx - 12 * u, cy - 9 * u);
        path.close();

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f * u);
        paint.setColor(mainColor);
        c.drawPath(path, paint);

        c.drawLine(cx - 5 * u, cy + 1 * u, cx - 1 * u, cy + 5 * u, paint);
        c.drawLine(cx - 1 * u, cy + 5 * u, cx + 6 * u, cy - 3 * u, paint);
    }

    private void drawRadarHero(Canvas c, float cx, float cy, float u) {
        float t = (SystemClock.uptimeMillis() % 3000L) / 3000f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x165CE1E6);
        c.drawCircle(cx, cy, 22 * u, paint);

        paint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < 3; i++) {
            float p = (t + i / 3f) % 1f;
            float r = (7f + p * 16.5f) * u;
            int alpha = Math.max(0, Math.min(255, Math.round((1f - p) * 165)));
            paint.setStrokeWidth((2.4f - p * 1.2f) * u);
            paint.setColor((alpha << 24) | 0x5CE1E6);
            c.drawCircle(cx, cy, r, paint);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF233B4E);
        c.drawCircle(cx, cy, 10.5f * u, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.6f * u);
        paint.setColor(0xFF7BE4F2);
        c.drawCircle(cx, cy, 10.5f * u, paint);

        rect.set(cx - 6.2f * u, cy - 4.8f * u, cx + 6.2f * u, cy + 3.6f * u);
        paint.setStrokeWidth(1.5f * u);
        paint.setColor(Color.WHITE);
        c.drawRoundRect(rect, 1.8f * u, 1.8f * u, paint);
        c.drawLine(cx - 2.8f * u, cy + 5.8f * u, cx + 2.8f * u, cy + 5.8f * u, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF48E59A);
        c.drawCircle(cx - 3.6f * u, cy + 1.5f * u, 1.1f * u, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.2f * u);
        rect.set(cx - 6.2f * u, cy - 1.1f * u, cx - 1f * u, cy + 4.1f * u);
        c.drawArc(rect, -90, 90, false, paint);

        if (canAnimate()) postInvalidateDelayed(42);
    }

    private void drawStepBadge(Canvas c, float cx, float cy, float u, int step) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x245CE1E6);
        c.drawCircle(cx, cy, 22 * u, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.2f * u);
        paint.setColor(0x885CE1E6);
        c.drawCircle(cx, cy, 21 * u, paint);

        if (step == 1) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF5CE1E6);
            c.drawCircle(cx, cy + 7 * u, 2.5f * u, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.6f * u);
            rect.set(cx - 7 * u, cy, cx + 7 * u, cy + 14 * u);
            c.drawArc(rect, -135, 90, false, paint);
            rect.set(cx - 12 * u, cy - 5 * u, cx + 12 * u, cy + 19 * u);
            c.drawArc(rect, -135, 90, false, paint);
        } else if (step == 2) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF5CE1E6);
            path.reset();
            path.moveTo(cx - 4 * u, cy - 8 * u);
            path.lineTo(cx + 8 * u, cy);
            path.lineTo(cx - 4 * u, cy + 8 * u);
            path.close();
            c.drawPath(path, paint);
        } else {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.5f * u);
            paint.setColor(0xFF48E59A);
            rect.set(cx - 11 * u, cy - 8 * u, cx + 11 * u, cy + 7 * u);
            c.drawRoundRect(rect, 3 * u, 3 * u, paint);
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(cx - 6 * u, cy + 3 * u, 2f * u, paint);
            paint.setStyle(Paint.Style.STROKE);
            rect.set(cx - 11 * u, cy - 2 * u, cx - 1 * u, cy + 8 * u);
            c.drawArc(rect, -90, 90, false, paint);
        }
    }
}
