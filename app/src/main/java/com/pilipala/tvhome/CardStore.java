// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.util.AtomicFile;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** MarcoTV's own card preferences; no third-party config or code is imported. */
final class CardStore {
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final java.util.concurrent.ConcurrentHashMap<String, String> FILE_NAMES = new java.util.concurrent.ConcurrentHashMap<>();
    private final Context context;
    private final SharedPreferences prefs;
    CardStore(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("cards", Context.MODE_PRIVATE);
    }
    String name(String id, String fallback) {
        return prefs.getString("name:" + id, fallback);
    }
    void setName(String id, String value) {
        if (value.trim().isEmpty()) prefs.edit().remove("name:" + id).apply();
        else prefs.edit().putString("name:" + id, value.trim()).apply();
    }
    String cover(String id) {
        return prefs.getString("cover:" + id, "original");
    }
    String manual(String id) {
        return prefs.getString("manual:"+id, "");
    }
    void manual(String id,String component) {
        prefs.edit().putString("manual:"+id,component).apply();
        toggle("manual_apps",id,true);
    }
    void cover(String id, String style) {
        prefs.edit().putString("cover:" + id, style).apply();
        if (!"image".equals(style)) {
            ArtworkCache.remove("cover:" + id);
            new AtomicFile(imageFile(id)).delete();
        }
    }
    static void pruneOrphanCovers(Context context) {
        SharedPreferences p = context.getSharedPreferences("cards", Context.MODE_PRIVATE);
        Set<String> active = new HashSet<>();
        CardStore store = new CardStore(context);
        for (Map.Entry<String, ?> entry : p.getAll().entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("cover:") && "image".equals(entry.getValue())) {
                active.add(store.imageFile(key.substring(6)).getName());
            }
        }
        File[] files = context.getFilesDir().listFiles(f -> f.getName().matches("cover-[a-f0-9]{64}\\.jpg"));
        if (files != null) {
            for (File f : files) {
                if (!active.contains(f.getName())) new AtomicFile(f).delete();
            }
        }
    }
    Set<String> set(String key) {
        return new LinkedHashSet<>(prefs.getStringSet(key, Collections.emptySet()));
    }
    boolean contains(String key, String id) {
        return prefs.getStringSet(key, Collections.emptySet()).contains(id);
    }
    void toggle(String key, String id, boolean enabled) {
        Set<String> values = set(key);
        if (enabled) values.add(id);
        else values.remove(id);
        prefs.edit().putStringSet(key, values).apply();
    }
    void reset(String id) {
        prefs.edit().remove("name:" + id).remove("cover:" + id).apply();
        ArtworkCache.remove("cover:" + id);
        new AtomicFile(imageFile(id)).delete();
    }
    File imageFile(String id) {
        String name = FILE_NAMES.get(id);
        if (name == null) {
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(id.getBytes("UTF-8"));
                char[] chars = new char[hash.length * 2];
                for (int i = 0; i < hash.length; i++) {
                    int v = hash[i] & 0xFF;
                    chars[i * 2] = HEX[v >>> 4];
                    chars[i * 2 + 1] = HEX[v & 0x0F];
                }
                name = "cover-" + new String(chars) + ".jpg";
                if (FILE_NAMES.size() < 512) FILE_NAMES.put(id, name);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return new File(context.getFilesDir(), name);
    }
    Bitmap loadImage(String id) {
        if (!cover(id).equals("image")) return null;
        String key = "cover:" + id;
        Bitmap cached = ArtworkCache.get(key);
        if (cached != null) return cached;
        File file = imageFile(id);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = new AtomicFile(file).openRead()) {
            BitmapFactory.decodeStream(in, null, bounds);
        } catch (IOException e) {
            return null;
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        // Imported covers are stored as JPEG, so they never carry alpha; RGB_565 halves the cost.
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / options.inSampleSize > 640 || bounds.outHeight / options.inSampleSize > 360) options.inSampleSize *= 2;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap decoded;
        try (InputStream in = new AtomicFile(file).openRead()) {
            decoded = BitmapFactory.decodeStream(in, null, options);
        }
        catch (IOException e) {
            return null;
        }
        if (decoded == null) return null;
        ArtworkCache.put(key, decoded);
        return decoded;
    }
    void importImage(String id, Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("不是可读取的图片");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / options.inSampleSize > 1280 || bounds.outHeight / options.inSampleSize > 1280) options.inSampleSize *= 2;
        Bitmap decoded;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            decoded = BitmapFactory.decodeStream(in, null, options);
        }
        if (decoded == null) throw new IOException("无法解码图片");
        Bitmap cropped = Bitmap.createBitmap(640, 360, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(cropped);
        float scale = Math.max(640f / decoded.getWidth(), 360f / decoded.getHeight());
        float w = decoded.getWidth() * scale, h = decoded.getHeight() * scale;
        canvas.drawBitmap(decoded, null, new RectF((640-w)/2, (360-h)/2, (640+w)/2, (360+h)/2), new Paint(Paint.FILTER_BITMAP_FLAG));
        AtomicFile target = new AtomicFile(imageFile(id));
        FileOutputStream stream = null;
        try {
            stream = target.startWrite();
            if (!cropped.compress(Bitmap.CompressFormat.JPEG, 90, stream)) throw new IOException("保存封面失败");
            target.finishWrite(stream);
            stream = null;
            ArtworkCache.remove("cover:" + id);
            cover(id, "image");
        } finally {
            if (stream != null) target.failWrite(stream);
            decoded.recycle();
            cropped.recycle();
        }
    }
}
