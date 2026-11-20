// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Build;
import java.util.*;

/** Synthesizes razor-sharp Apple TV-style studio cards directly at high resolution with mipmaps. */
final class ArtworkStyle {
    static Bitmap card(Drawable icon) {
        return card(icon, false);
    }

    static Bitmap card(Drawable icon, boolean lean) {
        int w = lean ? 512 : 640;
        int h = lean ? 288 : 360;
        Bitmap card = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        card.setHasMipMap(true);
        Canvas canvas = new Canvas(card);
        if (Build.VERSION.SDK_INT >= 26 && icon instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable adaptive = (AdaptiveIconDrawable) icon;
            Drawable background = adaptive.getBackground();
            if (background != null) {
                background.setBounds(0, 0, w, h);
                background.draw(canvas);
            }
            Drawable foreground = adaptive.getForeground();
            if (foreground != null) {
                int left = (w - h) / 2;
                foreground.setBounds(left, 0, left + h, h);
                foreground.draw(canvas);
            }
            return card;
        }
        // Probe icon color & edge uniformity at small size without degrading the final draw.
        Bitmap probe = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        icon.setBounds(0, 0, 96, 96);
        icon.draw(new Canvas(probe));
        boolean uniform = uniformEdge(probe);
        int edgeColor = probe.getPixel(0, 0);
        int base = uniform ? edgeColor : background(probe);
        probe.recycle();

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        if (uniform) {
            canvas.drawColor(edgeColor);
            int size = Math.round(h * 0.72f);
            int left = (w - size) / 2;
            int top = (h - size) / 2;
            icon.setBounds(left, top, left + size, top + size);
            icon.draw(canvas);
        } else {
            // Apple TV studio backdrop: luminous radial center tint fading into deeper rich brand corners.
            int centerColor = blendToward(base, Color.WHITE, 0.14f);
            int midColor = base;
            int edgeDark = darken(base, 0.56f);
            paint.setShader(new RadialGradient(
                    w * 0.5f, h * 0.42f, w * 0.68f,
                    new int[]{centerColor, midColor, edgeDark},
                    new float[]{0f, 0.46f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);

            int size = Math.round(h * 0.64f);
            int left = (w - size) / 2;
            int top = (h - size) / 2;

            // Subtle floating contact shadow underneath the logo for tvOS depth.
            float shadowR = size * 0.54f;
            paint.setShader(new RadialGradient(
                    w * 0.5f, top + size * 0.58f, shadowR,
                    new int[]{0x42000000, 0x18000000, 0x00000000},
                    new float[]{0.35f, 0.74f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawCircle(w * 0.5f, top + size * 0.58f, shadowR, paint);
            paint.setShader(null);

            // Render the original high-DPI drawable directly at crisp integer pixel bounds.
            icon.setBounds(left, top, left + size, top + size);
            icon.draw(canvas);
        }
        return card;
    }

    private static int blendToward(int color, int target, float amount) {
        int r = Math.round(Color.red(color) + (Color.red(target) - Color.red(color)) * amount);
        int g = Math.round(Color.green(color) + (Color.green(target) - Color.green(color)) * amount);
        int b = Math.round(Color.blue(color) + (Color.blue(target) - Color.blue(color)) * amount);
        return Color.rgb(Math.max(0, Math.min(255, r)), Math.max(0, Math.min(255, g)), Math.max(0, Math.min(255, b)));
    }

    private static int darken(int color, float factor) {
        return Color.rgb(
                Math.max(0, Math.round(Color.red(color) * factor)),
                Math.max(0, Math.round(Color.green(color) * factor)),
                Math.max(0, Math.round(Color.blue(color) * factor)));
    }

    static boolean uniformEdge(Bitmap b) {
        int reference = b.getPixel(0, 0), w = b.getWidth() - 1, h = b.getHeight() - 1;
        for (int i = 0; i <= 24; i++) for (int side = 0; side < 4; side++) {
            int c = b.getPixel(side < 2 ? i * w / 24 : (side == 2 ? 0 : w), side >= 2 ? i * h / 24 : (side == 0 ? 0 : h));
            if (Color.alpha(c) < 250 || Math.abs(Color.red(c) - Color.red(reference)) > 20
                    || Math.abs(Color.green(c) - Color.green(reference)) > 20
                    || Math.abs(Color.blue(c) - Color.blue(reference)) > 20) return false;
        }
        return true;
    }

    static int background(Bitmap image) {
        HashMap<Integer, Integer> buckets = new HashMap<>();
        HashMap<Integer, long[]> sums = new HashMap<>();
        int w = image.getWidth(), h = image.getHeight();
        for (int i = 0; i < 32; i++) for (int side = 0; side < 4; side++) {
            int x = side < 2 ? Math.min(w - 1, i * w / 32) : (side == 2 ? w / 12 : w - 1 - w / 12);
            int y = side >= 2 ? Math.min(h - 1, i * h / 32) : (side == 0 ? h / 12 : h - 1 - h / 12);
            int c = image.getPixel(x, y);
            if (Color.alpha(c) < 220) continue;
            int key = ((Color.red(c) >> 4) << 8) | ((Color.green(c) >> 4) << 4) | (Color.blue(c) >> 4);
            buckets.put(key, buckets.containsKey(key) ? buckets.get(key) + 1 : 1);
            long[] sum = sums.get(key);
            if (sum == null) {
                sum = new long[3];
                sums.put(key, sum);
            }
            sum[0] += Color.red(c);
            sum[1] += Color.green(c);
            sum[2] += Color.blue(c);
        }
        int best = -1, count = 0;
        for (Map.Entry<Integer, Integer> e : buckets.entrySet()) if (e.getValue() > count) {
            best = e.getKey();
            count = e.getValue();
        }
        if (count < 16) return 0xFF202E3D;
        long[] sum = sums.get(best);
        return Color.rgb((int) (sum[0] / count), (int) (sum[1] / count), (int) (sum[2] / count));
    }
}
