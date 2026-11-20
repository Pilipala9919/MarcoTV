// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import java.net.URI;
import java.util.Locale;

/** Pure mirror wallpaper catalog validation policy; tested on host without Android. */
final class WallpaperCatalogPolicy {
    static final String MIRROR_BASE = "https://marcotv.wlj9919.top/wallpapers/";
    static final String FALLBACK_BASE = "https://pilipala9919.github.io/MarcoTV/wallpapers/";
    static final int MAX_CATALOG_BYTES = 256 * 1024;
    static final int MAX_THUMB_BYTES = 2 * 1024 * 1024;
    static final long MAX_WALLPAPER_BYTES = 50L * 1024 * 1024;
    static final int MAX_SIDE = 16384;
    static final long MAX_PIXELS = 100_000_000L;
    static final int MAX_ITEMS = 60;

    static String[] catalogUrls() {
        return new String[]{MIRROR_BASE + "catalog.json", FALLBACK_BASE + "catalog.json"};
    }

    static boolean safeId(String id) {
        return id != null && id.matches("[a-z0-9][a-z0-9_-]{0,47}");
    }

    static boolean safeSha256(String sha) {
        return sha != null && sha.matches("[a-f0-9]{64}");
    }

    static boolean validDimensions(int width, int height) {
        return width >= 1 && height >= 1 && width <= MAX_SIDE && height <= MAX_SIDE
                && (long) width * height <= MAX_PIXELS;
    }

    static boolean imageUrl(String value) {
        if (value == null || value.length() > 512) return false;
        try {
            URI u = new URI(value);
            if (!"https".equals(u.getScheme()) || u.getUserInfo() != null || u.getPort() != -1
                    || u.getQuery() != null || u.getFragment() != null) return false;
            String host = u.getHost();
            String path = u.getPath();
            if (host == null || path == null || path.contains("..") || !path.equals(u.getRawPath())) return false;
            String tail;
            if ("marcotv.wlj9919.top".equals(host) && path.startsWith("/wallpapers/")) {
                tail = path.substring("/wallpapers/".length());
            } else if ("pilipala9919.github.io".equals(host) && path.startsWith("/MarcoTV/wallpapers/")) {
                tail = path.substring("/MarcoTV/wallpapers/".length());
            } else {
                return false;
            }
            if (tail.isEmpty() || tail.startsWith("/") || tail.contains("//")
                    || !tail.matches("[A-Za-z0-9._/-]+")) return false;
            String lower = tail.toLowerCase(Locale.ROOT);
            return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                    || lower.endsWith(".png") || lower.endsWith(".webp");
        } catch (Exception e) {
            return false;
        }
    }

    static boolean transferUrl(String value) {
        if (value == null || value.length() > 2048) return false;
        try {
            URI u = new URI(value);
            String host = u.getHost();
            return "https".equals(u.getScheme()) && u.getUserInfo() == null && u.getPort() == -1
                    && ("marcotv.wlj9919.top".equals(host)
                    || "pilipala9919.github.io".equals(host)
                    || "github.com".equals(host)
                    || "release-assets.githubusercontent.com".equals(host)
                    || "objects.githubusercontent.com".equals(host));
        } catch (Exception e) {
            return false;
        }
    }

    static String[] candidateUrls(String url) {
        if (!imageUrl(url)) throw new IllegalArgumentException("Untrusted wallpaper URL");
        if (url.startsWith(MIRROR_BASE)) {
            String tail = url.substring(MIRROR_BASE.length());
            return new String[]{url, FALLBACK_BASE + tail};
        }
        if (url.startsWith(FALLBACK_BASE)) {
            String tail = url.substring(FALLBACK_BASE.length());
            return new String[]{MIRROR_BASE + tail, url};
        }
        return new String[]{url};
    }

    static void validateItem(String id, String title, String subtitle, String credit, String license,
                             int width, int height, long size, String sha256, String url,
                             String thumbUrl, long thumbSize, String thumbSha256) {
        if (!safeId(id)) throw new IllegalArgumentException("Invalid wallpaper id");
        if (title == null || title.trim().isEmpty() || title.length() > 40)
            throw new IllegalArgumentException("Invalid wallpaper title");
        if (subtitle != null && subtitle.length() > 160)
            throw new IllegalArgumentException("Wallpaper subtitle too long");
        if (credit != null && credit.length() > 160)
            throw new IllegalArgumentException("Wallpaper credit too long");
        if (license != null && license.length() > 120)
            throw new IllegalArgumentException("Wallpaper license too long");
        if (!validDimensions(width, height))
            throw new IllegalArgumentException("Invalid wallpaper dimensions");
        if (size < 1 || size > MAX_WALLPAPER_BYTES)
            throw new IllegalArgumentException("Invalid wallpaper size");
        if (!safeSha256(sha256))
            throw new IllegalArgumentException("Invalid wallpaper sha256");
        if (!imageUrl(url))
            throw new IllegalArgumentException("Invalid wallpaper url");
        if (thumbUrl != null && !thumbUrl.isEmpty()) {
            if (!imageUrl(thumbUrl)) throw new IllegalArgumentException("Invalid thumbnail url");
            if (thumbSize < 0 || thumbSize > MAX_THUMB_BYTES)
                throw new IllegalArgumentException("Invalid thumbnail size");
            if (thumbSha256 != null && !thumbSha256.isEmpty() && !safeSha256(thumbSha256))
                throw new IllegalArgumentException("Invalid thumbnail sha256");
        }
    }

    static String formatSize(long bytes) {
        if (bytes < 1024L) return Math.max(0L, bytes) + " B";
        if (bytes < 1024L * 1024L) return Math.max(1L, Math.round(bytes / 1024.0)) + " KB";
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
