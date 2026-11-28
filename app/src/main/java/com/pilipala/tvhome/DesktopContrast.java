// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Pure API-14 contrast, color presets, HSL conversion, and wallpaper luminance sampling
 * shared between the full launcher, DreamService, and Marco TV Lite.
 */
final class DesktopContrast {
    static final int MODE_AUTO = 0;
    static final int MODE_WHITE = 1;
    static final int MODE_INK = 2;
    static final int MODE_IVORY = 3;
    static final int MODE_LAVENDER = 4;
    static final int MODE_CYAN = 5;
    static final int MODE_AMBER = 6;
    static final int MODE_MINT = 7;
    static final int MODE_CORAL = 8;
    static final int MODE_CUSTOM = 9;

    static final String[] COLOR_MODE_NAMES = {
            "智能自适应",
            "经典纯白",
            "深邃墨黑",
            "暖象牙白",
            "薰衣草紫",
            "冰川青蓝",
            "流光琥珀",
            "翡翠薄荷",
            "珊瑚樱粉",
            "自定义颜色"
    };

    static final int INK_COLOR = 0xFF141A24;
    static final int WHITE_COLOR = 0xFFFFFFFF;
    static final float BRIGHT_THRESHOLD = 0.56f;

    private static final int[] PRESET_COLORS = {
            WHITE_COLOR,
            WHITE_COLOR,
            INK_COLOR,
            0xFFFFF5E4,
            0xFFD6C2FF,
            0xFF8CE0FF,
            0xFFFFD480,
            0xFF98F5D0,
            0xFFFFB5C2,
            WHITE_COLOR
    };

    static int clampMode(int mode) {
        return Math.max(MODE_AUTO, Math.min(MODE_CUSTOM, mode));
    }

    static int clampHue(int hue) {
        int h = hue % 360;
        return h < 0 ? h + 360 : h;
    }

    static int clampLightness(int lightness) {
        return Math.max(15, Math.min(95, lightness));
    }

    static int clampShadow(int shadow) {
        return Math.max(0, Math.min(100, shadow));
    }

    /** Pure-Java HSL to opaque ARGB so host JVM checks run without Android stubs. */
    static int hslToArgb(int hue, float sat, int lightnessPercent) {
        float h = clampHue(hue) / 360f;
        float s = Math.max(0f, Math.min(1f, sat));
        float l = clampLightness(lightnessPercent) / 100f;
        float q = l < 0.5f ? l * (1f + s) : (l + s - l * s);
        float p = 2f * l - q;
        int r = Math.round(hueChannel(p, q, h + 1f / 3f) * 255f);
        int g = Math.round(hueChannel(p, q, h) * 255f);
        int b = Math.round(hueChannel(p, q, h - 1f / 3f) * 255f);
        return (0xFF << 24)
                | (Math.max(0, Math.min(255, r)) << 16)
                | (Math.max(0, Math.min(255, g)) << 8)
                | Math.max(0, Math.min(255, b));
    }

    private static float hueChannel(float p, float q, float t) {
        if (t < 0f) t += 1f;
        if (t > 1f) t -= 1f;
        if (t < 1f / 6f) return p + (q - p) * 6f * t;
        if (t < 0.5f) return q;
        if (t < 2f / 3f) return p + (q - p) * (2f / 3f - t) * 6f;
        return p;
    }

    static float relativeLuminance(int argb) {
        int r = (argb >>> 16) & 255;
        int g = (argb >>> 8) & 255;
        int b = argb & 255;
        return (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f;
    }

    static boolean isDarkInk(int argb) {
        return relativeLuminance(argb) < 0.45f;
    }

    static float effectiveLuminance(float rawLuminance, int wallpaperDimPercent) {
        float clampedRaw = Math.max(0f, Math.min(1f, rawLuminance));
        float dim = Math.max(0f, Math.min(100f, wallpaperDimPercent)) / 100f;
        return clampedRaw * (1f - dim);
    }

    static boolean isBright(float effectiveLuminance) {
        return effectiveLuminance >= BRIGHT_THRESHOLD;
    }

    static int resolveClockColor(int mode, int hue, int lightness, boolean brightBackground) {
        int m = clampMode(mode);
        if (m == MODE_AUTO) {
            return brightBackground ? INK_COLOR : WHITE_COLOR;
        }
        if (m == MODE_CUSTOM) {
            return hslToArgb(hue, 0.72f, lightness);
        }
        return PRESET_COLORS[m];
    }

    /**
     * When entering the dimmed screensaver, smoothly lift dark ink back toward crisp white
     * so dark clock/weather text never vanishes over a darkened backdrop.
     */
    static int resolveAmbientClockColor(int desktopColor, float ambient, int ambientDimPercent, boolean pureWallpaper) {
        if (pureWallpaper || ambient <= 0.005f || ambientDimPercent < 35 || !isDarkInk(desktopColor)) {
            return desktopColor;
        }
        return blendRgb(desktopColor, WHITE_COLOR, Math.max(0f, Math.min(1f, ambient)));
    }

    static int blendRgb(int from, int to, float t) {
        float k = Math.max(0f, Math.min(1f, t));
        int rA = (from >>> 16) & 255, gA = (from >>> 8) & 255, bA = from & 255;
        int rB = (to >>> 16) & 255, gB = (to >>> 8) & 255, bB = to & 255;
        int r = Math.round(rA + (rB - rA) * k);
        int g = Math.round(gA + (gB - gA) * k);
        int b = Math.round(bA + (bB - bA) * k);
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    static int withAlpha(int argb, float alpha01) {
        int baseA = (argb >>> 24) & 255;
        int outA = Math.max(0, Math.min(255, Math.round(baseA * Math.max(0f, Math.min(1f, alpha01)))));
        return (argb & 0x00FFFFFF) | (outA << 24);
    }

    static int contrastShadowColor(int foregroundColor, int shadowPercent, float alpha01) {
        float strength = clampShadow(shadowPercent) / 100f;
        if (strength <= 0.005f || alpha01 <= 0.005f) return 0;
        int base = isDarkInk(foregroundColor) ? 0xD8FFFFFF : 0xB0000000;
        return withAlpha(base, strength * alpha01);
    }

    static float sampleTopLeftLuminance(Bitmap bmp) {
        return sampleRegionLuminance(bmp, 0.04f, 0.54f, 0.04f, 0.17f);
    }

    static float sampleTopRightLuminance(Bitmap bmp) {
        return sampleRegionLuminance(bmp, 0.66f, 0.96f, 0.05f, 0.28f);
    }

    static float sampleShelfLuminance(Bitmap bmp) {
        return sampleRegionLuminance(bmp, 0.06f, 0.90f, 0.34f, 0.90f);
    }

    static float sampleRegionLuminance(Bitmap bmp, float xStartFrac, float xEndFrac, float yStartFrac, float yEndFrac) {
        if (bmp == null || bmp.isRecycled()) return 0.16f;
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (w <= 0 || h <= 0) return 0.16f;
        int x0 = Math.max(0, Math.min(w - 1, Math.round(w * xStartFrac)));
        int x1 = Math.max(x0, Math.min(w - 1, Math.round(w * xEndFrac)));
        int y0 = Math.max(0, Math.min(h - 1, Math.round(h * yStartFrac)));
        int y1 = Math.max(y0, Math.min(h - 1, Math.round(h * yEndFrac)));
        int cols = 12, rows = 5;
        double sum = 0.0;
        int count = 0;
        for (int r = 0; r < rows; r++) {
            int y = rows == 1 ? y0 : y0 + (y1 - y0) * r / (rows - 1);
            for (int c = 0; c < cols; c++) {
                int x = cols == 1 ? x0 : x0 + (x1 - x0) * c / (cols - 1);
                int px = bmp.getPixel(x, y);
                sum += (0.2126 * Color.red(px) + 0.7152 * Color.green(px) + 0.0722 * Color.blue(px)) / 255.0;
                count++;
            }
        }
        return count == 0 ? 0.16f : (float) (sum / count);
    }
}
