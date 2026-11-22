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
        SharedPreferences.Editor edit = prefs.edit().putStringSet(key, values);
        if ("pinned".equals(key) && !enabled) edit.putString("pin_order", removeOrder(pinOrder(), id));
        edit.apply();
    }
    List<String> pinOrder() {
        String raw = prefs.getString("pin_order", "");
        ArrayList<String> out = new ArrayList<>();
        if (raw != null && !raw.isEmpty()) {
            for (String item : raw.split("\\|")) if (!item.isEmpty() && !out.contains(item)) out.add(item);
        }
        return out;
    }
    void pinFirst(String id) {
        Set<String> pinned = set("pinned");
        pinned.add(id);
        List<String> order = pinOrder();
        order.remove(id);
        order.add(0, id);
        prefs.edit().putStringSet("pinned", pinned).putString("pin_order", joinOrder(order)).apply();
    }
    void unpin(String id) {
        toggle("pinned", id, false);
    }
    void syncPinOrder(List<Tile> tiles) {
        Set<String> pinned = set("pinned");
        ArrayList<String> next = new ArrayList<>();
        for (Tile t : tiles) if (pinned.contains(t.id) && !next.contains(t.id)) next.add(t.id);
        for (String existing : pinOrder()) if (pinned.contains(existing) && !next.contains(existing)) next.add(existing);
        prefs.edit().putString("pin_order", joinOrder(next)).apply();
    }
    private static String removeOrder(List<String> list, String id) {
        list.remove(id);
        return joinOrder(list);
    }
    private static String joinOrder(List<String> list) {
        StringBuilder out = new StringBuilder();
        for (String item : list) {
            if (item == null || item.isEmpty()) continue;
            if (out.length() > 0) out.append('|');
            out.append(item);
        }
        return out.toString();
    }
    static final int MIN_WIDTH = 60, MAX_WIDTH = 160;
    static final int MIN_HEIGHT = 60, MAX_HEIGHT = 150;
    static final int MIN_DEPTH = 0, MAX_DEPTH = 100;
    static final int MIN_RADIUS = 0, MAX_RADIUS = 90;

    static final class Dimensions {
        int width;
        int height;
        int depth = -1;
        int radius = -1;
        boolean isDefault() {
            return width == 0 && height == 0 && depth < 0 && radius < 0;
        }
        String encode() {
            return width + "," + height + "," + depth + "," + radius;
        }
        static Dimensions parse(String raw) {
            Dimensions d = new Dimensions();
            if (!SettingsCatalog.validCardSize(raw)) return d;
            String[] parts = raw.split(",", -1);
            d.width = Integer.parseInt(parts[0]);
            d.height = Integer.parseInt(parts[1]);
            d.depth = Integer.parseInt(parts[2]);
            d.radius = Integer.parseInt(parts[3]);
            return d;
        }
    }

    private interface StreamOpener {
        InputStream open() throws IOException;
    }

    Dimensions dimensions(String id) {
        return Dimensions.parse(prefs.getString("size:" + id, ""));
    }

    void setDimensions(String id, Dimensions d) {
        if (d == null) {
            prefs.edit().remove("size:" + id).apply();
            return;
        }
        Dimensions clean = new Dimensions();
        clean.width = d.width == 0 ? 0 : Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, d.width));
        clean.height = d.height == 0 ? 0 : Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, d.height));
        clean.depth = d.depth < 0 ? -1 : Math.max(MIN_DEPTH, Math.min(MAX_DEPTH, d.depth));
        clean.radius = d.radius < 0 ? -1 : Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, d.radius));
        if (clean.isDefault()) prefs.edit().remove("size:" + id).apply();
        else prefs.edit().putString("size:" + id, clean.encode()).apply();
    }

    void resetDimensions(String id) {
        prefs.edit().remove("size:" + id).apply();
    }

    void reset(String id) {
        prefs.edit().remove("name:" + id).remove("cover:" + id).remove("size:" + id).apply();
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
        int maxSide = ArtworkCache.lean(context) ? 640 : 960;
        while (bounds.outWidth / options.inSampleSize > maxSide || bounds.outHeight / options.inSampleSize > maxSide) options.inSampleSize *= 2;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap decoded;
        try (InputStream in = new AtomicFile(file).openRead()) {
            decoded = BitmapFactory.decodeStream(in, null, options);
        }
        catch (IOException e) {
            return null;
        }
        if (decoded == null) return null;
        decoded.setHasMipMap(true);
        ArtworkCache.put(key, decoded);
        return decoded;
    }
    void importImage(String id, Uri uri) throws IOException {
        Bitmap output = writeCoverStream(id, () -> {
            InputStream in = context.getContentResolver().openInputStream(uri);
            if (in == null) throw new IOException("无法打开图片");
            return in;
        });
        markImageCover(id, output);
    }
    Bitmap saveImageFileOnly(String id, File source) throws IOException {
        return writeCoverStream(id, () -> new FileInputStream(source));
    }
    void markImageCover(String id, Bitmap bitmap) {
        if (bitmap != null) {
            bitmap.setHasMipMap(true);
            ArtworkCache.put("cover:" + id, bitmap);
        } else {
            ArtworkCache.remove("cover:" + id);
        }
        cover(id, "image");
    }
    private Bitmap writeCoverStream(String id, StreamOpener opener) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = opener.open()) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 16384 || bounds.outHeight > 16384
                || (long) bounds.outWidth * bounds.outHeight > 100000000L) throw new IOException("不是可读取的图片");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / options.inSampleSize > 1920 || bounds.outHeight / options.inSampleSize > 1920) options.inSampleSize *= 2;
        Bitmap decoded;
        try (InputStream in = opener.open()) {
            decoded = BitmapFactory.decodeStream(in, null, options);
        }
        if (decoded == null) throw new IOException("无法解码图片");
        int srcW = Math.max(1, decoded.getWidth());
        int srcH = Math.max(1, decoded.getHeight());
        float maxSide = ArtworkCache.lean(context) ? 640f : 960f;
        float fit = Math.min(1f, maxSide / Math.max(srcW, srcH));
        int outW = Math.max(1, Math.round(srcW * fit));
        int outH = Math.max(1, Math.round(srcH * fit));
        Bitmap output = Bitmap.createBitmap(outW, outH, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(0xFF1A2534);
        canvas.drawBitmap(decoded, null, new RectF(0, 0, outW, outH), new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        decoded.recycle();
        AtomicFile target = new AtomicFile(imageFile(id));
        FileOutputStream stream = null;
        boolean saved = false;
        try {
            stream = target.startWrite();
            if (!output.compress(Bitmap.CompressFormat.JPEG, 90, stream)) throw new IOException("保存封面失败");
            target.finishWrite(stream);
            stream = null;
            saved = true;
            output.setHasMipMap(true);
            return output;
        } finally {
            if (stream != null) target.failWrite(stream);
            if (!saved) output.recycle();
        }
    }
}
