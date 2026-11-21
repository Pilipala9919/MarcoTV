// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Downloads and locally caches SHA-256 verified wallpapers from the official mirror. */
final class OnlineWallpaperStore {
    static final class Item {
        final String id, title, subtitle, credit, license, sha256, url, thumbUrl, thumbSha256;
        final int width, height, durationSec;
        final long size, thumbSize;
        final boolean video;

        Item(String id, String title, String subtitle, String credit, String license,
             int width, int height, long size, String sha256, String url,
             String thumbUrl, long thumbSize, String thumbSha256) {
            this(id, title, subtitle, credit, license, width, height, size, sha256, url,
                    thumbUrl, thumbSize, thumbSha256, "image", 0);
        }

        Item(String id, String title, String subtitle, String credit, String license,
             int width, int height, long size, String sha256, String url,
             String thumbUrl, long thumbSize, String thumbSha256, String type, int durationSec) {
            String normalizedType = (type == null || type.isEmpty()) ? "image" : type.trim().toLowerCase(Locale.ROOT);
            WallpaperCatalogPolicy.validateItem(id, title, subtitle, credit, license,
                    width, height, size, sha256, url, thumbUrl, thumbSize, thumbSha256, normalizedType);
            this.id = id;
            this.title = title.trim();
            this.subtitle = subtitle == null ? "" : subtitle.trim();
            this.credit = credit == null ? "" : credit.trim();
            this.license = license == null ? "" : license.trim();
            this.width = width;
            this.height = height;
            this.size = size;
            this.sha256 = sha256;
            this.url = url;
            this.thumbUrl = thumbUrl == null ? "" : thumbUrl;
            this.thumbSize = Math.max(0L, thumbSize);
            this.thumbSha256 = thumbSha256 == null ? "" : thumbSha256;
            this.video = "video".equals(normalizedType);
            this.durationSec = Math.max(0, durationSec);
        }
    }

    static final class Catalog {
        final String updated;
        final List<Item> items;
        final boolean fromCache;

        Catalog(String updated, List<Item> items, boolean fromCache) {
            this.updated = updated == null ? "" : updated;
            this.items = Collections.unmodifiableList(items);
            this.fromCache = fromCache;
        }
    }

    interface Progress {
        void onPercent(int percent);
    }

    private final Context context;
    private final Set<HttpURLConnection> activeConnections = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    OnlineWallpaperStore(Context context) {
        this.context = context.getApplicationContext();
    }

    File dir() {
        File folder = new File(context.getFilesDir(), "online-wallpapers");
        if (!folder.isDirectory()) folder.mkdirs();
        return folder;
    }

    private File catalogFile() {
        return new File(dir(), "catalog.json");
    }

    File wallpaperFile(Item item) {
        if (item.video) {
            String lower = item.url.toLowerCase(Locale.ROOT);
            String ext = lower.endsWith(".webm") ? ".webm" : lower.endsWith(".mov") ? ".mov" : ".mp4";
            return new File(dir(), item.id + ext);
        }
        return new File(dir(), item.id + ".jpg");
    }

    File posterFile(Item item) {
        return new File(dir(), "poster-" + item.id + ".jpg");
    }

    private File thumbFile(Item item) {
        return new File(dir(), "thumb-" + item.id + ".jpg");
    }

    boolean isDownloaded(Item item) {
        File f = wallpaperFile(item);
        return f.isFile() && f.length() == item.size;
    }

    long cacheSizeBytes() {
        File[] files = dir().listFiles();
        if (files == null) return 0L;
        long total = 0L;
        for (File f : files) if (f.isFile()) total += f.length();
        return total;
    }

    long clearCache() {
        File[] files = dir().listFiles();
        if (files == null) return 0L;
        long reclaimed = 0L;
        for (File f : files) {
            if (!f.isFile()) continue;
            long len = f.length();
            if (f.delete()) reclaimed += len;
        }
        return reclaimed;
    }

    void cancelActive() {
        List<HttpURLConnection> snapshot = new ArrayList<>(activeConnections);
        if (!snapshot.isEmpty()) {
            new Thread(() -> {
                for (HttpURLConnection conn : snapshot) {
                    try { conn.disconnect(); } catch (Throwable ignored) {}
                }
            }, "MarcoTV-wp-cancel").start();
        }
    }

    Catalog loadCachedCatalog() {
        File f = catalogFile();
        if (!f.isFile() || f.length() <= 0 || f.length() > WallpaperCatalogPolicy.MAX_CATALOG_BYTES) return null;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            LocalFiles.copy(f, out);
            return parseCatalog(new String(out.toByteArray(), StandardCharsets.UTF_8), true);
        } catch (Exception ignored) {
            return null;
        }
    }

    Catalog fetchCatalog(AtomicBoolean cancelled) throws Exception {
        Exception lastError = null;
        for (String endpoint : WallpaperCatalogPolicy.catalogUrls()) {
            if (cancelled.get()) throw new InterruptedIOException("cancelled");
            try {
                byte[] raw = downloadBytes(endpoint, WallpaperCatalogPolicy.MAX_CATALOG_BYTES, cancelled);
                String text = new String(raw, StandardCharsets.UTF_8);
                Catalog catalog = parseCatalog(text, false);
                AtomicFile atomic = new AtomicFile(catalogFile());
                FileOutputStream out = null;
                try {
                    out = atomic.startWrite();
                    out.write(raw);
                    atomic.finishWrite(out);
                    out = null;
                } catch (IOException error) {
                    if (out != null) atomic.failWrite(out);
                }
                return catalog;
            } catch (InterruptedIOException e) {
                throw e;
            } catch (Exception e) {
                lastError = e;
            }
        }
        throw lastError != null ? lastError : new IOException("Wallpaper catalog unavailable");
    }

    static Catalog parseCatalog(String raw, boolean fromCache) throws Exception {
        JSONObject root = new JSONObject(raw);
        if (root.optInt("schema", -1) != 1) throw new IOException("Unsupported wallpaper catalog schema");
        String updated = root.optString("updated", "");
        JSONArray array = root.getJSONArray("items");
        ArrayList<Item> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int limit = Math.min(array.length(), WallpaperCatalogPolicy.MAX_ITEMS);
        for (int i = 0; i < limit; i++) {
            try {
                JSONObject o = array.getJSONObject(i);
                Item item = new Item(
                        o.getString("id"),
                        o.getString("title"),
                        o.optString("subtitle", ""),
                        o.optString("credit", ""),
                        o.optString("license", ""),
                        o.getInt("width"),
                        o.getInt("height"),
                        o.getLong("size"),
                        o.getString("sha256"),
                        o.getString("url"),
                        o.optString("thumbUrl", ""),
                        o.optLong("thumbSize", 0L),
                        o.optString("thumbSha256", ""),
                        o.optString("type", "image"),
                        o.optInt("durationSec", 0)
                );
                if (seen.add(item.id)) list.add(item);
            } catch (Exception ignored) {
                // Skip individual unsupported/invalid entries so future catalog additions remain backward compatible.
            }
        }
        if (list.isEmpty()) throw new IOException("Empty wallpaper catalog");
        return new Catalog(updated, list, fromCache);
    }

    File verifiedLocalFile(Item item) {
        File file = wallpaperFile(item);
        if (!file.isFile() || file.length() != item.size) return null;
        try {
            if (item.sha256.equals(sha256(file))) return file;
            file.delete();
        } catch (Exception ignored) {
            file.delete();
        }
        return null;
    }

    File downloadWallpaper(Item item, Progress progress, AtomicBoolean cancelled) throws Exception {
        File existing = verifiedLocalFile(item);
        if (existing != null) {
            if (item.video) ensureVideoPoster(item, existing);
            if (progress != null) progress.onPercent(100);
            return existing;
        }
        File folder = dir();
        if (folder.getUsableSpace() < item.size + 16L * 1024 * 1024) {
            throw new IOException("电视可用存储空间不足");
        }
        cleanupStaleParts(folder);
        Exception lastError = null;
        String[] endpoints = WallpaperCatalogPolicy.candidateMediaUrls(item.url, item.video);
        for (String endpoint : endpoints) {
            if (cancelled.get()) throw new InterruptedIOException("cancelled");
            File part = new File(folder, item.id + ".part");
            part.delete();
            try {
                downloadToFile(endpoint, part, item.size, item.sha256, item.size, progress, cancelled);
                if (item.video) {
                    if (!validVideoFile(part)) {
                        throw new IOException("下载的视频无效或暂不支持");
                    }
                } else {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(part.getPath(), bounds);
                    if (!WallpaperCatalogPolicy.validDimensions(bounds.outWidth, bounds.outHeight)) {
                        throw new IOException("下载的图片无效");
                    }
                }
                File target = wallpaperFile(item);
                if (!part.renameTo(target)) {
                    try (InputStream in = new FileInputStream(part); OutputStream out = new FileOutputStream(target)) {
                        copyStream(in, out);
                    }
                    part.delete();
                }
                if (item.video) ensureVideoPoster(item, target);
                return target;
            } catch (InterruptedIOException e) {
                part.delete();
                throw e;
            } catch (Exception e) {
                part.delete();
                lastError = e;
            }
        }
        throw lastError != null ? lastError : new IOException("壁纸下载失败");
    }

    private static boolean validVideoFile(File file) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(file.getAbsolutePath());
            String w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            int width = w == null ? 0 : Integer.parseInt(w);
            int height = h == null ? 0 : Integer.parseInt(h);
            return width > 0 && height > 0;
        } catch (Throwable ignored) {
            return false;
        } finally {
            try { mmr.release(); } catch (Throwable ignored) {}
        }
    }

    File ensureVideoPoster(Item item, File videoFile) {
        File poster = posterFile(item);
        if (poster.isFile() && poster.length() > 0) return poster;
        if (videoFile != null && videoFile.isFile()) {
            Bitmap frame = WallpaperLibrary.extractVideoFrame(videoFile, 1920, 1080);
            if (frame != null) {
                try {
                    WallpaperLibrary.writeJpeg(frame, poster, 90);
                    return poster;
                } catch (IOException ignored) {
                    poster.delete();
                } finally {
                    frame.recycle();
                }
            }
        }
        File thumb = thumbFile(item);
        return (thumb.isFile() && thumb.length() > 0) ? thumb : null;
    }

    Bitmap loadPreviewBitmap(Item item, AtomicBoolean cancelled) {
        if (!item.video) {
            File source = wallpaperFile(item);
            if (source.isFile() && source.length() == item.size) {
                Bitmap b = decodeBounded(source, 640, 360);
                if (b != null) return b;
            }
        } else {
            File poster = posterFile(item);
            if (poster.isFile() && poster.length() > 0) {
                Bitmap b = decodeBounded(poster, 640, 360);
                if (b != null) return b;
            }
        }
        File thumb = thumbFile(item);
        if (thumb.isFile() && thumb.length() > 0 && (item.thumbSize <= 0 || thumb.length() == item.thumbSize)) {
            Bitmap b = decodeBounded(thumb, 640, 360);
            if (b != null) return b;
        }
        if (item.video) {
            File source = wallpaperFile(item);
            if (source.isFile() && source.length() == item.size) {
                File poster = ensureVideoPoster(item, source);
                if (poster != null && poster.isFile()) {
                    Bitmap b = decodeBounded(poster, 640, 360);
                    if (b != null) return b;
                }
            }
        }
        if (item.thumbUrl.isEmpty() || cancelled.get()) return null;
        for (String endpoint : WallpaperCatalogPolicy.candidateUrls(item.thumbUrl)) {
            if (cancelled.get()) return null;
            File part = new File(dir(), "thumb-" + item.id + ".part");
            part.delete();
            try {
                long limit = item.thumbSize > 0 ? item.thumbSize : -WallpaperCatalogPolicy.MAX_THUMB_BYTES;
                downloadToFile(endpoint, part, limit, item.thumbSha256, item.thumbSize, null, cancelled);
                Bitmap decoded = decodeBounded(part, 640, 360);
                if (decoded != null) {
                    if (!part.renameTo(thumb)) part.delete();
                    return decoded;
                }
                part.delete();
            } catch (Exception ignored) {
                part.delete();
            }
        }
        return null;
    }

    /** Copies the verified cached wallpaper (or extracted poster for video) to a temp file in filesDir ready for atomic publish. */
    File stageCopyForApply(Item item) throws IOException {
        File cached = verifiedLocalFile(item);
        if (cached == null) throw new IOException("本地壁纸校验失败，请重新下载");
        File source = item.video ? ensureVideoPoster(item, cached) : cached;
        if (source == null || !source.isFile()) throw new IOException("无法生成视频壁纸预览帧");
        File staged = File.createTempFile("wallpaper-online-", ".img", context.getFilesDir());
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(staged)) {
            copyStream(in, out);
            return staged;
        } catch (IOException error) {
            staged.delete();
            throw error;
        }
    }

    private static Bitmap decodeBounded(File file, int maxW, int maxH) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            o.inSampleSize = 1;
            while (o.outWidth / o.inSampleSize > maxW || o.outHeight / o.inSampleSize > maxH) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), o);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void cleanupStaleParts(File folder) {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File f : files) if (f.isFile() && f.getName().endsWith(".part")) f.delete();
    }

    private byte[] downloadBytes(String url, int maxBytes, AtomicBoolean cancelled) throws Exception {
        HttpURLConnection conn = open(url, cancelled);
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            long contentLength = conn.getContentLengthLong();
            if (contentLength > maxBytes) throw new IOException("Response too large");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                int total = 0;
                while ((n = in.read(buf)) >= 0) {
                    if (cancelled.get()) throw new InterruptedIOException("cancelled");
                    total += n;
                    if (total > maxBytes) throw new IOException("Response limit exceeded");
                    out.write(buf, 0, n);
                }
            }
            return out.toByteArray();
        } finally {
            activeConnections.remove(conn);
            conn.disconnect();
        }
    }

    /** exactOrNegMax: positive requires exact byte length; negative enforces max byte length = -exactOrNegMax. */
    private void downloadToFile(String url, File target, long exactOrNegMax, String expectedSha256,
                                long estimatedSize, Progress progress, AtomicBoolean cancelled) throws Exception {
        boolean exact = exactOrNegMax > 0;
        long maxBytes = exact ? exactOrNegMax : -exactOrNegMax;
        HttpURLConnection conn = open(url, cancelled);
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            long contentLength = conn.getContentLengthLong();
            if (contentLength > maxBytes) throw new IOException("File exceeds limit");
            if (exact && contentLength > 0 && contentLength != exactOrNegMax) throw new IOException("Size mismatch");
            long progressTotal = exact ? exactOrNegMax : (contentLength > 0 ? contentLength : estimatedSize);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long total = 0L;
            int lastPercent = -1;
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (cancelled.get()) throw new InterruptedIOException("cancelled");
                    total += n;
                    if (total > maxBytes) throw new IOException("File exceeded limit");
                    md.update(buf, 0, n);
                    out.write(buf, 0, n);
                    if (progress != null && progressTotal > 0) {
                        int pct = (int) Math.min(100L, total * 100L / progressTotal);
                        if (pct != lastPercent) {
                            lastPercent = pct;
                            progress.onPercent(pct);
                        }
                    }
                }
            }
            if (exact && total != exactOrNegMax) throw new IOException("Incomplete download");
            if (expectedSha256 != null && !expectedSha256.isEmpty()) {
                String actual = BackupStore.hex(md.digest());
                if (!expectedSha256.equals(actual)) throw new IOException("SHA-256 mismatch");
            }
        } finally {
            activeConnections.remove(conn);
            conn.disconnect();
        }
    }

    private HttpURLConnection open(String startUrl, AtomicBoolean cancelled) throws IOException {
        String current = startUrl;
        for (int hop = 0; hop < 5; hop++) {
            if (cancelled.get()) throw new InterruptedIOException("cancelled");
            if (!WallpaperCatalogPolicy.transferUrl(current)) throw new IOException("Blocked URL");
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(45000);
            conn.setRequestProperty("User-Agent", "MarcoTV/" + ProjectInfo.VERSION);
            conn.setRequestProperty("Accept-Encoding", "identity");
            activeConnections.add(conn);
            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                activeConnections.remove(conn);
                conn.disconnect();
                if (location == null || location.isEmpty()) throw new IOException("Empty redirect");
                current = new URL(new URL(current), location).toString();
                continue;
            }
            return conn;
        }
        throw new IOException("Too many redirects");
    }

    private static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) >= 0) md.update(buf, 0, n);
        }
        return BackupStore.hex(md.digest());
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
    }
}
