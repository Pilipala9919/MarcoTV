// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Persistent library for user-imported static and live video wallpapers, plus unified active state. */
final class WallpaperLibrary {
    static final int MAX_ENTRIES = 40;
    static final long MAX_VIDEO_BYTES = WallpaperCatalogPolicy.MAX_VIDEO_BYTES;
    static final long MAX_IMAGE_BYTES = WallpaperCatalogPolicy.MAX_WALLPAPER_BYTES;
    private static final Object LOCK = new Object();

    static final class Entry {
        final String id;
        final String title;
        final boolean video;
        final String fileName;
        final String posterName;
        final String thumbName;
        final int width;
        final int height;
        final int durationSec;
        final long size;
        final long createdAt;

        Entry(String id, String title, boolean video, String fileName, String posterName,
              String thumbName, int width, int height, int durationSec, long size, long createdAt) {
            this.id = id;
            this.title = title;
            this.video = video;
            this.fileName = fileName;
            this.posterName = posterName;
            this.thumbName = thumbName;
            this.width = width;
            this.height = height;
            this.durationSec = durationSec;
            this.size = size;
            this.createdAt = createdAt;
        }

        String unifiedId() {
            return "imported:" + id;
        }
    }

    private final Context context;

    WallpaperLibrary(Context context) {
        this.context = context.getApplicationContext();
    }

    File dir() {
        File folder = new File(context.getFilesDir(), "my-wallpapers");
        if (!folder.isDirectory()) folder.mkdirs();
        return folder;
    }

    private File indexFile() {
        return new File(dir(), "index.json");
    }

    File mediaFile(Entry e) {
        return new File(dir(), e.fileName);
    }

    File posterFile(Entry e) {
        return new File(dir(), e.posterName);
    }

    File thumbFile(Entry e) {
        return new File(dir(), e.thumbName);
    }

    List<Entry> list() {
        synchronized (LOCK) {
            adoptLegacyCustomWallpaperLocked();
            return new ArrayList<>(readIndexLocked());
        }
    }

    private void adoptLegacyCustomWallpaperLocked() {
        SharedPreferences prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE);
        String activeId = prefs.getString("wallpaper_id", "");
        if (activeId != null && !activeId.isEmpty()) return;
        String uri = prefs.getString("wallpaper", "");
        String video = prefs.getString("wallpaper_video", "");
        if (video != null && !video.isEmpty()) return;
        if (uri == null || uri.isEmpty()) return;
        File legacy = new File(context.getFilesDir(), "wallpaper-image");
        if (!legacy.isFile() || !Uri.fromFile(legacy).toString().equals(uri)) return;
        String label = prefs.getString("wallpaper_label", "");
        if (label != null && label.startsWith("在线 · ")) return;
        List<Entry> current = readIndexLocked();
        for (Entry e : current) {
            if ("legacy".equals(e.id)) return;
        }
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(legacy.getPath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return;
            File copy = new File(dir(), "legacy.jpg");
            copyFile(legacy, copy);
            File thumb = new File(dir(), "thumb-legacy.jpg");
            generateThumb(copy, thumb);
            String title = cleanTitle(label == null || label.isEmpty() ? "我的壁纸" : label, "我的壁纸");
            Entry adopted = new Entry("legacy", title, false, copy.getName(), copy.getName(),
                    thumb.getName(), o.outWidth, o.outHeight, 0, copy.length(), System.currentTimeMillis());
            current.add(0, adopted);
            writeIndexLocked(current);
            prefs.edit().putString("wallpaper_id", adopted.unifiedId()).putString("wallpaper_label", title).apply();
        } catch (Exception ignored) {}
    }

    private List<Entry> readIndexLocked() {
        ArrayList<Entry> list = new ArrayList<>();
        File file = indexFile();
        if (!file.isFile() || file.length() <= 0 || file.length() > 512 * 1024) return list;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            LocalFiles.copy(file, out);
            JSONObject root = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            JSONArray items = root.optJSONArray("items");
            if (items == null) return list;
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.getJSONObject(i);
                String id = o.optString("id", "");
                String fileName = o.optString("file", "");
                if (id.isEmpty() || fileName.isEmpty() || fileName.contains("/") || fileName.contains("..")) continue;
                File media = new File(dir(), fileName);
                if (!media.isFile()) continue;
                String posterName = o.optString("poster", fileName);
                String thumbName = o.optString("thumb", posterName);
                list.add(new Entry(
                        id,
                        cleanTitle(o.optString("title", "自定义壁纸"), "自定义壁纸"),
                        o.optBoolean("video", false),
                        fileName,
                        posterName,
                        thumbName,
                        o.optInt("width", 1920),
                        o.optInt("height", 1080),
                        o.optInt("durationSec", 0),
                        media.length(),
                        o.optLong("createdAt", 0L)
                ));
            }
        } catch (Exception ignored) {}
        return list;
    }

    private void writeIndexLocked(List<Entry> items) throws IOException {
        try {
            JSONArray array = new JSONArray();
            for (Entry e : items) {
                array.put(new JSONObject()
                        .put("id", e.id)
                        .put("title", e.title)
                        .put("video", e.video)
                        .put("file", e.fileName)
                        .put("poster", e.posterName)
                        .put("thumb", e.thumbName)
                        .put("width", e.width)
                        .put("height", e.height)
                        .put("durationSec", e.durationSec)
                        .put("createdAt", e.createdAt));
            }
            JSONObject root = new JSONObject().put("schema", 1).put("items", array);
            byte[] data = root.toString().getBytes(StandardCharsets.UTF_8);
            AtomicFile atomic = new AtomicFile(indexFile());
            FileOutputStream out = null;
            try {
                out = atomic.startWrite();
                out.write(data);
                atomic.finishWrite(out);
                out = null;
            } finally {
                if (out != null) atomic.failWrite(out);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Unable to serialize wallpaper library", e);
        }
    }

    Entry importImage(File sourceFile, String rawTitle) throws IOException {
        if (sourceFile == null || !sourceFile.isFile() || sourceFile.length() <= 0 || sourceFile.length() > MAX_IMAGE_BYTES) {
            throw new IOException("图片文件大小超出限制（最大 50 MB）");
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(sourceFile.getPath(), o);
        if (!WallpaperCatalogPolicy.validDimensions(o.outWidth, o.outHeight)) {
            throw new IOException("图片无效或像素过大");
        }
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / o.inSampleSize > 1920 || o.outHeight / o.inSampleSize > 1080) o.inSampleSize *= 2;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap decoded = BitmapFactory.decodeFile(sourceFile.getPath(), o);
        if (decoded == null) throw new IOException("图片无法读取");

        String id = newId();
        File target = new File(dir(), "wp-" + id + ".jpg");
        File thumb = new File(dir(), "thumb-" + id + ".jpg");
        int w = decoded.getWidth(), h = decoded.getHeight();
        try {
            writeJpeg(decoded, target, 92);
        } finally {
            decoded.recycle();
        }
        try {
            generateThumb(target, thumb);
        } catch (IOException ignored) {
            thumb = target;
        }
        String title = cleanTitle(rawTitle, "图片壁纸");
        Entry entry = new Entry(id, title, false, target.getName(), target.getName(), thumb.getName(),
                w, h, 0, target.length(), System.currentTimeMillis());
        addEntry(entry);
        return entry;
    }

    Entry importVideo(File sourceFile, String rawTitle, String originalFileName) throws IOException {
        if (sourceFile == null || !sourceFile.isFile() || sourceFile.length() <= 0 || sourceFile.length() > MAX_VIDEO_BYTES) {
            throw new IOException("动态壁纸文件需小于 80 MB");
        }
        if (dir().getUsableSpace() < sourceFile.length() + 24L * 1024 * 1024) {
            throw new IOException("电视可用存储空间不足");
        }
        int width = 0, height = 0, durationSec = 0;
        Bitmap frame = null;
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(sourceFile.getAbsolutePath());
            String ws = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String hs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String ds = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            width = ws == null ? 0 : Integer.parseInt(ws);
            height = hs == null ? 0 : Integer.parseInt(hs);
            long durationMs = ds == null ? 0L : Long.parseLong(ds);
            durationSec = (int) Math.max(1L, Math.round(durationMs / 1000.0));
            if (width <= 0 || height <= 0) {
                throw new IOException("无法识别视频画面，请上传标准 MP4（H.264）视频");
            }
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                frame = mmr.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1920, 1080);
                if (frame == null) frame = mmr.getScaledFrameAtTime(100_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1920, 1080);
            }
            if (frame == null) frame = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) frame = mmr.getFrameAtTime();
        } catch (IOException e) {
            throw e;
        } catch (Throwable error) {
            throw new IOException("视频格式暂不被当前电视支持，请转为 MP4（H.264）后重试");
        } finally {
            try { mmr.release(); } catch (Throwable ignored) {}
        }
        if (frame == null) {
            throw new IOException("无法提取视频首帧，请换一个 MP4 视频");
        }
        String ext = ".mp4";
        if (originalFileName != null && originalFileName.toLowerCase(Locale.ROOT).endsWith(".webm")) {
            ext = ".webm";
        }
        String id = newId();
        File target = new File(dir(), "vid-" + id + ext);
        File poster = new File(dir(), "poster-" + id + ".jpg");
        File thumb = new File(dir(), "thumb-" + id + ".jpg");
        try {
            Bitmap scaledPoster = scaleDown(frame, 1920, 1080);
            try {
                writeJpeg(scaledPoster, poster, 90);
            } finally {
                if (scaledPoster != frame) scaledPoster.recycle();
            }
            Bitmap scaledThumb = scaleDown(frame, 480, 270);
            try {
                writeJpeg(scaledThumb, thumb, 82);
            } finally {
                if (scaledThumb != frame) scaledThumb.recycle();
            }
        } finally {
            frame.recycle();
        }
        try {
            if (!sourceFile.renameTo(target)) {
                copyFile(sourceFile, target);
            }
        } catch (IOException error) {
            target.delete();
            poster.delete();
            thumb.delete();
            throw error;
        }
        String title = cleanTitle(rawTitle, "动态壁纸");
        Entry entry = new Entry(id, title, true, target.getName(), poster.getName(), thumb.getName(),
                width, height, durationSec, target.length(), System.currentTimeMillis());
        addEntry(entry);
        return entry;
    }

    private void addEntry(Entry entry) throws IOException {
        synchronized (LOCK) {
            List<Entry> list = readIndexLocked();
            list.add(0, entry);
            while (list.size() > MAX_ENTRIES) {
                Entry evicted = list.remove(list.size() - 1);
                deleteFiles(evicted);
            }
            writeIndexLocked(list);
        }
    }

    boolean rename(String id, String newTitle) {
        String clean = cleanTitle(newTitle, "");
        if (clean.isEmpty()) return false;
        synchronized (LOCK) {
            List<Entry> list = readIndexLocked();
            for (int i = 0; i < list.size(); i++) {
                Entry e = list.get(i);
                if (e.id.equals(id)) {
                    list.set(i, new Entry(e.id, clean, e.video, e.fileName, e.posterName, e.thumbName,
                            e.width, e.height, e.durationSec, e.size, e.createdAt));
                    try {
                        writeIndexLocked(list);
                        SharedPreferences prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE);
                        if (e.unifiedId().equals(prefs.getString("wallpaper_id", ""))) {
                            prefs.edit().putString("wallpaper_label", clean).apply();
                        }
                        return true;
                    } catch (IOException ignored) {
                        return false;
                    }
                }
            }
        }
        return false;
    }

    boolean delete(String id) {
        synchronized (LOCK) {
            List<Entry> list = readIndexLocked();
            Entry removed = null;
            for (Iterator<Entry> it = list.iterator(); it.hasNext(); ) {
                Entry e = it.next();
                if (e.id.equals(id)) {
                    removed = e;
                    it.remove();
                    break;
                }
            }
            if (removed == null) return false;
            deleteFiles(removed);
            try {
                writeIndexLocked(list);
            } catch (IOException ignored) {}
            SharedPreferences prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE);
            if (removed.unifiedId().equals(prefs.getString("wallpaper_id", ""))) {
                prefs.edit()
                        .putString("wallpaper", "")
                        .putString("wallpaper_video", "")
                        .putInt("wallpaper_builtin", 0)
                        .putString("wallpaper_id", "builtin:0")
                        .putString("wallpaper_label", WallpaperSource.TITLES[0])
                        .apply();
            }
            return true;
        }
    }

    private void deleteFiles(Entry e) {
        mediaFile(e).delete();
        if (!e.posterName.equals(e.fileName)) posterFile(e).delete();
        if (!e.thumbName.equals(e.fileName) && !e.thumbName.equals(e.posterName)) thumbFile(e).delete();
    }

    static String activeId(SharedPreferences prefs) {
        String id = prefs.getString("wallpaper_id", "");
        if (id != null && !id.isEmpty()) return id;
        String uri = prefs.getString("wallpaper", "");
        if (uri == null || uri.isEmpty()) {
            int idx = Math.max(0, Math.min(WallpaperSource.ASSETS.length - 1, prefs.getInt("wallpaper_builtin", 0)));
            return "builtin:" + idx;
        }
        return "imported:legacy";
    }

    void applyEntry(MainActivity host, Entry entry) throws IOException {
        File posterSrc = posterFile(entry);
        if (!posterSrc.isFile()) posterSrc = mediaFile(entry);
        File staged = File.createTempFile("wallpaper-lib-", ".img", host.getFilesDir());
        try {
            copyFile(posterSrc, staged);
        } catch (IOException e) {
            staged.delete();
            throw e;
        }
        File published = WallpaperFile.publish(staged, host.getFilesDir());
        SharedPreferences.Editor ed = host.preferences.edit()
                .putString("wallpaper", Uri.fromFile(published).toString())
                .putString("wallpaper_id", entry.unifiedId())
                .putString("wallpaper_label", entry.title);
        if (entry.video) {
            ed.putString("wallpaper_video", mediaFile(entry).getAbsolutePath());
        } else {
            ed.putString("wallpaper_video", "");
        }
        ed.apply();
        host.handler.post(host::loadWallpaper);
    }

    static void applyBuiltin(MainActivity host, int index) {
        int safe = Math.max(0, Math.min(WallpaperSource.ASSETS.length - 1, index));
        host.preferences.edit()
                .putString("wallpaper", "")
                .putString("wallpaper_video", "")
                .putInt("wallpaper_builtin", safe)
                .putString("wallpaper_id", "builtin:" + safe)
                .putString("wallpaper_label", WallpaperSource.TITLES[safe])
                .apply();
        host.loadWallpaper();
    }

    static void applyOnline(MainActivity host, OnlineWallpaperStore store, OnlineWallpaperStore.Item item) throws IOException {
        File staged = store.stageCopyForApply(item);
        File published;
        try {
            published = WallpaperFile.publish(staged, host.getFilesDir());
        } catch (IOException error) {
            staged.delete();
            throw error;
        }
        SharedPreferences.Editor ed = host.preferences.edit()
                .putString("wallpaper", Uri.fromFile(published).toString())
                .putString("wallpaper_id", "online:" + item.id)
                .putString("wallpaper_label", item.title);
        if (item.video) {
            ed.putString("wallpaper_video", store.wallpaperFile(item).getAbsolutePath());
        } else {
            ed.putString("wallpaper_video", "");
        }
        ed.apply();
        host.handler.post(host::loadWallpaper);
    }

    static Bitmap loadBuiltinThumb(Context context, int index) {
        int safe = Math.max(0, Math.min(WallpaperSource.ASSETS.length - 1, index));
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = context.getAssets().open(WallpaperSource.ASSETS[safe])) {
                BitmapFactory.decodeStream(in, null, o);
            }
            o.inSampleSize = sampleSize(o.outWidth, o.outHeight, 480, 270);
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream in = context.getAssets().open(WallpaperSource.ASSETS[safe])) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    static Bitmap loadFileThumb(File file) {
        if (file == null || !file.isFile()) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            o.inSampleSize = sampleSize(o.outWidth, o.outHeight, 480, 270);
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), o);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static Bitmap extractVideoFrame(File videoFile, int maxW, int maxH) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(videoFile.getAbsolutePath());
            Bitmap raw = null;
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                raw = mmr.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxW, maxH);
                if (raw == null) raw = mmr.getScaledFrameAtTime(100_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxW, maxH);
            }
            if (raw == null) raw = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (raw == null) raw = mmr.getFrameAtTime(100_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (raw == null) raw = mmr.getFrameAtTime();
            if (raw == null) return null;
            Bitmap scaled = scaleDown(raw, maxW, maxH);
            if (scaled != raw) raw.recycle();
            return scaled;
        } catch (Throwable ignored) {
            return null;
        } finally {
            try { mmr.release(); } catch (Throwable ignored) {}
        }
    }

    static void writeJpeg(Bitmap bitmap, File target, int quality) throws IOException {
        File part = new File(target.getParentFile(), target.getName() + ".part");
        try (OutputStream out = new FileOutputStream(part)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                throw new IOException("JPEG compress failed");
            }
        } catch (IOException e) {
            part.delete();
            throw e;
        }
        if (!part.renameTo(target)) {
            copyFile(part, target);
            part.delete();
        }
    }

    private static void generateThumb(File imageFile, File target) throws IOException {
        Bitmap b = loadFileThumb(imageFile);
        if (b == null) throw new IOException("thumb decode failed");
        try {
            writeJpeg(b, target, 82);
        } finally {
            b.recycle();
        }
    }

    private static Bitmap scaleDown(Bitmap source, int maxW, int maxH) {
        int w = source.getWidth(), h = source.getHeight();
        if (w <= maxW && h <= maxH) return source;
        float ratio = Math.min(maxW / (float) w, maxH / (float) h);
        int nw = Math.max(1, Math.round(w * ratio));
        int nh = Math.max(1, Math.round(h * ratio));
        return Bitmap.createScaledBitmap(source, nw, nh, true);
    }

    private static int sampleSize(int w, int h, int maxW, int maxH) {
        int s = 1;
        while (w / s > maxW * 2 || h / s > maxH * 2) s *= 2;
        return s;
    }

    static String cleanTitle(String raw, String fallback) {
        if (raw == null) return fallback;
        String s = raw.trim();
        int dot = s.lastIndexOf('.');
        if (dot > 0) {
            String ext = s.substring(dot).toLowerCase(Locale.ROOT);
            if (ext.equals(".jpg") || ext.equals(".jpeg") || ext.equals(".png") || ext.equals(".webp")
                    || ext.equals(".mp4") || ext.equals(".webm") || ext.equals(".mov") || ext.equals(".m4v")) {
                s = s.substring(0, dot).trim();
            }
        }
        s = s.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (s.length() > 28) s = s.substring(0, 28).trim();
        return s.isEmpty() ? fallback : s;
    }

    private static String newId() {
        return Long.toString(System.currentTimeMillis(), 36) + "-" + Integer.toString((int) (Math.random() * 46656), 36);
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        }
    }
}
