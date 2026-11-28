// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Pure Canvas anti-aliased vector weather glyphs, API-14 compatible and zero APK asset weight. */
final class WeatherIcons {
    static void drawCondition(Canvas c, Paint p, Path path, RectF oval,
                              int condition, boolean isDay, float cx, float cy, float size,
                              int baseColor, boolean colorful, float alpha01) {
        if (alpha01 <= 0.005f) return;
        float u = size / 24f;
        boolean darkInk = DesktopContrast.isDarkInk(baseColor);
        int mono = DesktopContrast.withAlpha(baseColor, alpha01);
        int sunFill = colorful ? DesktopContrast.withAlpha(darkInk ? 0xFFD98200 : 0xFFFFD166, alpha01) : mono;
        int moonFill = colorful ? DesktopContrast.withAlpha(darkInk ? 0xFF47648E : 0xFFE6EEFF, alpha01) : mono;
        int cloudFill = colorful
                ? DesktopContrast.withAlpha(darkInk ? 0xFF334155 : 0xFFF1F5FB, alpha01)
                : mono;
        int rainColor = colorful ? DesktopContrast.withAlpha(darkInk ? 0xFF0B78C4 : 0xFF6EC6FF, alpha01) : mono;
        int snowColor = colorful ? DesktopContrast.withAlpha(darkInk ? 0xFF1D74B8 : 0xFFC5EBFF, alpha01) : mono;
        int boltColor = colorful ? DesktopContrast.withAlpha(darkInk ? 0xFFD97706 : 0xFFFFD54F, alpha01) : mono;

        c.save();
        c.translate(cx, cy);
        p.setShader(null);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);

        switch (condition) {
            case WeatherSnapshot.COND_CLEAR:
                if (isDay) drawSun(c, p, 0f, 0f, 5.4f * u, 9.2f * u, 1.8f * u, sunFill);
                else drawMoon(c, p, path, oval, 0f, 0f, 7.6f * u, moonFill);
                break;
            case WeatherSnapshot.COND_PARTLY_CLOUDY:
                if (isDay) drawSun(c, p, -4.5f * u, -4.2f * u, 3.6f * u, 6.2f * u, 1.5f * u, sunFill);
                else drawMoon(c, p, path, oval, -4.2f * u, -4.0f * u, 4.8f * u, moonFill);
                drawCloud(c, p, path, oval, 1.8f * u, 2.5f * u, u * 0.92f, cloudFill);
                break;
            case WeatherSnapshot.COND_CLOUDY:
            case WeatherSnapshot.COND_UNKNOWN:
                drawCloud(c, p, path, oval, 0f, 1.2f * u, u, cloudFill);
                break;
            case WeatherSnapshot.COND_FOG:
                drawCloud(c, p, path, oval, 0f, -1.6f * u, u * 0.88f, cloudFill);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.9f * u);
                p.setColor(DesktopContrast.withAlpha(baseColor, alpha01 * 0.8f));
                c.drawLine(-7.5f * u, 5.5f * u, 7.5f * u, 5.5f * u, p);
                c.drawLine(-5.5f * u, 9.0f * u, 5.5f * u, 9.0f * u, p);
                break;
            case WeatherSnapshot.COND_RAIN:
                drawCloud(c, p, path, oval, 0f, -2.0f * u, u * 0.92f, cloudFill);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.0f * u);
                p.setColor(rainColor);
                c.drawLine(-4.5f * u, 5.2f * u, -6.0f * u, 9.5f * u, p);
                c.drawLine(0.5f * u, 5.2f * u, -1.0f * u, 9.5f * u, p);
                c.drawLine(5.5f * u, 5.2f * u, 4.0f * u, 9.5f * u, p);
                break;
            case WeatherSnapshot.COND_THUNDER:
                drawCloud(c, p, path, oval, 0f, -2.5f * u, u * 0.92f, cloudFill);
                path.reset();
                path.moveTo(1.2f * u, 3.5f * u);
                path.lineTo(-2.6f * u, 7.6f * u);
                path.lineTo(0.4f * u, 7.6f * u);
                path.lineTo(-1.4f * u, 11.4f * u);
                path.lineTo(3.6f * u, 6.4f * u);
                path.lineTo(0.5f * u, 6.4f * u);
                path.close();
                p.setStyle(Paint.Style.FILL);
                p.setColor(boltColor);
                c.drawPath(path, p);
                break;
            case WeatherSnapshot.COND_SNOW:
                drawCloud(c, p, path, oval, 0f, -2.2f * u, u * 0.92f, cloudFill);
                p.setStyle(Paint.Style.FILL);
                p.setColor(snowColor);
                c.drawCircle(-4.6f * u, 6.8f * u, 1.5f * u, p);
                c.drawCircle(0.2f * u, 8.6f * u, 1.5f * u, p);
                c.drawCircle(4.8f * u, 6.6f * u, 1.5f * u, p);
                break;
            case WeatherSnapshot.COND_SLEET:
                drawCloud(c, p, path, oval, 0f, -2.2f * u, u * 0.92f, cloudFill);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.9f * u);
                p.setColor(rainColor);
                c.drawLine(-4.2f * u, 5.0f * u, -5.6f * u, 9.2f * u, p);
                c.drawLine(4.6f * u, 5.0f * u, 3.2f * u, 9.2f * u, p);
                p.setStyle(Paint.Style.FILL);
                p.setColor(snowColor);
                c.drawCircle(0.2f * u, 7.8f * u, 1.6f * u, p);
                break;
        }
        p.setStyle(Paint.Style.FILL);
        c.restore();
    }

    static void drawDroplet(Canvas c, Paint p, Path path, float cx, float cy, float size,
                            int baseColor, boolean colorful, float alpha01) {
        if (alpha01 <= 0.005f) return;
        float u = size / 16f;
        boolean darkInk = DesktopContrast.isDarkInk(baseColor);
        int color = colorful
                ? DesktopContrast.withAlpha(darkInk ? 0xFF0B78C4 : 0xFF6EC6FF, alpha01)
                : DesktopContrast.withAlpha(baseColor, alpha01);
        c.save();
        c.translate(cx, cy);
        path.reset();
        path.moveTo(0f, -5.8f * u);
        path.cubicTo(3.4f * u, -1.6f * u, 4.6f * u, 1.2f * u, 4.6f * u, 2.8f * u);
        path.cubicTo(4.6f * u, 5.3f * u, 2.5f * u, 6.8f * u, 0f, 6.8f * u);
        path.cubicTo(-2.5f * u, 6.8f * u, -4.6f * u, 5.3f * u, -4.6f * u, 2.8f * u);
        path.cubicTo(-4.6f * u, 1.2f * u, -3.4f * u, -1.6f * u, 0f, -5.8f * u);
        path.close();
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawPath(path, p);
        c.restore();
    }

    private static void drawSun(Canvas c, Paint p, float cx, float cy, float r, float rayOuter, float rayStroke, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawCircle(cx, cy, r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(rayStroke);
        float inner = r + rayStroke * 1.15f;
        for (int i = 0; i < 8; i++) {
            double ang = i * Math.PI / 4.0;
            float cos = (float) Math.cos(ang), sin = (float) Math.sin(ang);
            c.drawLine(cx + cos * inner, cy + sin * inner, cx + cos * rayOuter, cy + sin * rayOuter, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private static void drawMoon(Canvas c, Paint p, Path path, RectF oval, float cx, float cy, float r, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        path.reset();
        oval.set(cx - r, cy - r, cx + r, cy + r);
        path.addArc(oval, -60f, 250f);
        oval.set(cx - r * 0.25f, cy - r * 0.85f, cx + r * 1.25f, cy + r * 0.65f);
        path.arcTo(oval, 180f, -200f, false);
        path.close();
        c.drawPath(path, p);
    }

    private static void drawCloud(Canvas c, Paint p, Path path, RectF oval, float cx, float cy, float u, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        path.reset();
        path.moveTo(cx - 5.2f * u, cy + 3.6f * u);
        path.lineTo(cx + 5.6f * u, cy + 3.6f * u);
        oval.set(cx + 1.8f * u, cy - 2.6f * u, cx + 8.6f * u, cy + 3.6f * u);
        path.arcTo(oval, 90f, -180f, false);
        oval.set(cx - 4.4f * u, cy - 6.2f * u, cx + 4.4f * u, cy + 2.4f * u);
        path.arcTo(oval, -15f, -170f, false);
        oval.set(cx - 8.8f * u, cy - 2.4f * u, cx - 1.6f * u, cy + 3.6f * u);
        path.arcTo(oval, -120f, -150f, false);
        path.close();
        c.drawPath(path, p);
    }
}
