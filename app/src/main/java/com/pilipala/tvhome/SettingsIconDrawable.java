// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Crisp Apple TV-style vector squircle badges and chevrons drawn directly on Canvas. */
final class SettingsIconDrawable extends Drawable {
    static final int HEADER = -1;
    static final int GENERAL = SettingsTaxonomy.GENERAL;
    static final int APPS = SettingsTaxonomy.APPS;
    static final int HOME = SettingsTaxonomy.HOME;
    static final int NETWORK = SettingsTaxonomy.NETWORK;
    static final int SYSTEM = SettingsTaxonomy.SYSTEM;

    private final int kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    SettingsIconDrawable(int kind) {
        this.kind = kind;
    }

    private static int topColor(int kind) {
        switch (kind) {
            case GENERAL: return 0xFF2894FF;
            case APPS: return 0xFF8B6BF5;
            case HOME: return 0xFF39BCD6;
            case NETWORK: return 0xFF34C759;
            case SYSTEM: return 0xFF8892A2;
            default: return 0xFFE5D2FF;
        }
    }

    private static int bottomColor(int kind) {
        switch (kind) {
            case GENERAL: return 0xFF0A66DB;
            case APPS: return 0xFF6042C8;
            case HOME: return 0xFF1D8EAA;
            case NETWORK: return 0xFF219E43;
            case SYSTEM: return 0xFF5D6676;
            default: return 0xFF7D56D1;
        }
    }

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        float side = Math.min(b.width(), b.height());
        if (side <= 0) return;
        float cx = b.exactCenterX(), cy = b.exactCenterY();
        float half = side * 0.5f;
        float u = side / 32f;

        rect.set(cx - half, cy - half, cx + half, cy + half);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(rect.left, rect.top, rect.left, rect.bottom,
                topColor(kind), bottomColor(kind), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, 8.5f * u, 8.5f * u, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, 0.8f * u));
        paint.setColor(0x2EFFFFFF);
        rect.inset(0.5f * u, 0.5f * u);
        canvas.drawRoundRect(rect, 8f * u, 8f * u, paint);

        canvas.save();
        canvas.translate(cx, cy);
        paint.setColor(Color.WHITE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        switch (kind) {
            case GENERAL:
                drawDisplayIcon(canvas, u);
                break;
            case APPS:
                drawAppsIcon(canvas, u);
                break;
            case HOME:
                drawRemoteIcon(canvas, u);
                break;
            case NETWORK:
                drawNetworkIcon(canvas, u);
                break;
            case SYSTEM:
                drawSystemIcon(canvas, u);
                break;
            default:
                drawBrandMark(canvas, u);
                break;
        }
        canvas.restore();
    }

    /** Official MarcoTV Purple Stacked Cards brand mark (3 slanted layered cards). */
    private void drawBrandMark(Canvas c, float u) {
        c.save();
        c.rotate(-14f);
        // Layer 3 (rear)
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xD49B7AF2);
        rect.set(-4.0f * u, 2.6f * u, 9.8f * u, 8.8f * u);
        c.drawRoundRect(rect, 2.4f * u, 2.4f * u, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.75f * u);
        paint.setColor(0x66FFF8FF);
        c.drawRoundRect(rect, 2.4f * u, 2.4f * u, paint);
        // Layer 2 (middle)
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xE0C6A8FF);
        rect.set(-7.2f * u, -1.6f * u, 9.2f * u, 5.4f * u);
        c.drawRoundRect(rect, 2.6f * u, 2.6f * u, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0x88FFF8FF);
        c.drawRoundRect(rect, 2.6f * u, 2.6f * u, paint);
        // Layer 1 (front ivory card)
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFCF7FE);
        rect.set(-10.2f * u, -7.8f * u, 7.6f * u, 1.0f * u);
        c.drawRoundRect(rect, 3.0f * u, 3.0f * u, paint);
        c.restore();
    }

    private void drawGearIcon(Canvas c, float u) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.2f * u);
        c.drawCircle(0, 0, 5.4f * u, paint);
        paint.setStrokeWidth(2.4f * u);
        for (int i = 0; i < 8; i++) {
            double rad = i * Math.PI / 4.0;
            float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
            c.drawLine(cos * 6.8f * u, sin * 6.8f * u, cos * 9.0f * u, sin * 9.0f * u, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(0, 0, 2.2f * u, paint);
    }

    private void drawDisplayIcon(Canvas c, float u) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.1f * u);
        rect.set(-8.5f * u, -6.5f * u, 8.5f * u, 4.5f * u);
        c.drawRoundRect(rect, 2.4f * u, 2.4f * u, paint);
        c.drawLine(-4.2f * u, 8.2f * u, 4.2f * u, 8.2f * u, paint);
        c.drawLine(0, 4.5f * u, 0, 8.2f * u, paint);
    }

    private void drawAppsIcon(Canvas c, float u) {
        paint.setStyle(Paint.Style.FILL);
        float gap = 1.6f * u, box = 6.2f * u, r = 1.9f * u;
        rect.set(-gap - box, -gap - box, -gap, -gap);
        c.drawRoundRect(rect, r, r, paint);
        rect.set(gap, -gap - box, gap + box, -gap);
        c.drawRoundRect(rect, r, r, paint);
        rect.set(-gap - box, gap, -gap, gap + box);
        c.drawRoundRect(rect, r, r, paint);
        rect.set(gap, gap, gap + box, gap + box);
        c.drawRoundRect(rect, r, r, paint);
    }

    private void drawRemoteIcon(Canvas c, float u) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.1f * u);
        rect.set(-5.2f * u, -8.8f * u, 5.2f * u, 8.8f * u);
        c.drawRoundRect(rect, 3.2f * u, 3.2f * u, paint);
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(0, -3.5f * u, 2.2f * u, paint);
        c.drawCircle(-2.0f * u, 2.4f * u, 1.1f * u, paint);
        c.drawCircle(2.0f * u, 2.4f * u, 1.1f * u, paint);
        c.drawCircle(-2.0f * u, 5.5f * u, 1.1f * u, paint);
        c.drawCircle(2.0f * u, 5.5f * u, 1.1f * u, paint);
    }

    private void drawNetworkIcon(Canvas c, float u) {
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(0, 6.2f * u, 1.8f * u, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.1f * u);
        for (int i = 1; i <= 3; i++) {
            float rad = (3.0f + i * 3.6f) * u;
            rect.set(-rad, 6.2f * u - rad, rad, 6.2f * u + rad);
            c.drawArc(rect, 226, 88, false, paint);
        }
    }

    private void drawSystemIcon(Canvas c, float u) {
        drawGearIcon(c, u);
    }

    @Override public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
        invalidateSelf();
    }

    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    /** Crisp vector chevron that adapts its tint smoothly when a row focuses. */
    static final class Chevron extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int tint = 0xFF6B7689;

        void setTintColor(int color) {
            if (tint != color) {
                tint = color;
                invalidateSelf();
            }
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            float h = b.height(), w = b.width();
            if (h <= 0 || w <= 0) return;
            float cx = b.exactCenterX(), cy = b.exactCenterY();
            float s = Math.min(w, h) * 0.28f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.8f, Math.min(w, h) * 0.11f));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(tint);
            canvas.drawLine(cx - s * 0.45f, cy - s, cx + s * 0.55f, cy, p);
            canvas.drawLine(cx + s * 0.55f, cy, cx - s * 0.45f, cy + s, p);
        }

        @Override public void setAlpha(int alpha) {
            p.setAlpha(alpha);
            invalidateSelf();
        }

        @Override public void setColorFilter(ColorFilter filter) {
            p.setColorFilter(filter);
            invalidateSelf();
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
