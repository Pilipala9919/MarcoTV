// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

/** Host-side verification of mirror wallpaper catalog policy, URL boundaries, and 50 MB / 12K limits. */
public final class WallpaperCatalogChecks {
    static int passed;
    static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
        passed++;
        System.out.println("PASS " + name);
    }
    static void expectReject(String name, Runnable action) {
        boolean rejected = false;
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(name, rejected);
    }

    public static void main(String[] args) {
        String sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String mirrorFull = "https://marcotv.wlj9919.top/wallpapers/full/01-bahamas-shoals.jpg";
        String mirrorThumb = "https://marcotv.wlj9919.top/wallpapers/thumbs/01-bahamas-shoals.jpg";
        String fallbackFull = "https://pilipala9919.github.io/MarcoTV/wallpapers/full/01-bahamas-shoals.jpg";

        String[] catalogs = WallpaperCatalogPolicy.catalogUrls();
        check("mirror_catalog_first", catalogs.length == 2 && catalogs[0].equals("https://marcotv.wlj9919.top/wallpapers/catalog.json"));
        check("github_pages_catalog_fallback", catalogs[1].equals("https://pilipala9919.github.io/MarcoTV/wallpapers/catalog.json"));

        check("safe_id_accepts_slug", WallpaperCatalogPolicy.safeId("01-bahamas_shoals"));
        check("safe_id_rejects_traversal", !WallpaperCatalogPolicy.safeId("../evil"));
        check("safe_id_rejects_uppercase", !WallpaperCatalogPolicy.safeId("Bahamas"));
        check("safe_id_rejects_empty", !WallpaperCatalogPolicy.safeId(""));

        check("valid_mirror_image_url", WallpaperCatalogPolicy.imageUrl(mirrorFull));
        check("valid_fallback_image_url", WallpaperCatalogPolicy.imageUrl(fallbackFull));
        check("reject_http_image_url", !WallpaperCatalogPolicy.imageUrl("http://marcotv.wlj9919.top/wallpapers/full/a.jpg"));
        check("reject_untrusted_host", !WallpaperCatalogPolicy.imageUrl("https://evil.example/wallpapers/full/a.jpg"));
        check("reject_wrong_prefix", !WallpaperCatalogPolicy.imageUrl("https://marcotv.wlj9919.top/updates/a.jpg"));
        check("reject_traversal_url", !WallpaperCatalogPolicy.imageUrl("https://marcotv.wlj9919.top/wallpapers/../secret.jpg"));
        check("reject_query_string", !WallpaperCatalogPolicy.imageUrl(mirrorFull + "?token=1"));
        check("reject_non_image_extension", !WallpaperCatalogPolicy.imageUrl("https://marcotv.wlj9919.top/wallpapers/full/a.apk"));
        check("reject_custom_port", !WallpaperCatalogPolicy.imageUrl("https://marcotv.wlj9919.top:8443/wallpapers/full/a.jpg"));

        check("transfer_allows_mirror_and_github", WallpaperCatalogPolicy.transferUrl(mirrorFull)
                && WallpaperCatalogPolicy.transferUrl("https://objects.githubusercontent.com/some/path")
                && !WallpaperCatalogPolicy.transferUrl("http://marcotv.wlj9919.top/wallpapers/a.jpg")
                && !WallpaperCatalogPolicy.transferUrl("https://127.0.0.1/wallpapers/a.jpg"));

        String[] candidates = WallpaperCatalogPolicy.candidateUrls(mirrorFull);
        check("candidate_urls_mirror_then_fallback", candidates.length == 2
                && candidates[0].equals(mirrorFull) && candidates[1].equals(fallbackFull));
        String[] reverse = WallpaperCatalogPolicy.candidateUrls(fallbackFull);
        check("candidate_urls_prefers_mirror_for_fallback_input", reverse.length == 2
                && reverse[0].equals(mirrorFull) && reverse[1].equals(fallbackFull));

        // 12K resolution (11520x6480 = 74.65 MP) and 50 MB ceiling
        check("dimensions_accept_12k", WallpaperCatalogPolicy.validDimensions(11520, 6480));
        check("dimensions_reject_over_100mp", !WallpaperCatalogPolicy.validDimensions(12000, 9000));
        check("dimensions_reject_over_16384_side", !WallpaperCatalogPolicy.validDimensions(20000, 1000));
        check("max_wallpaper_is_50mb", WallpaperCatalogPolicy.MAX_WALLPAPER_BYTES == 50L * 1024 * 1024);

        WallpaperCatalogPolicy.validateItem("01-bahamas-shoals", "巴哈马浅滩", "说明",
                "Contains modified Copernicus Sentinel data 2024", "Copernicus",
                11520, 6480, 50L * 1024 * 1024, sha, mirrorFull, mirrorThumb, 24000, sha);
        check("valid_12k_50mb_item_accepted", true);

        expectReject("reject_over_50mb_item", () -> WallpaperCatalogPolicy.validateItem(
                "01-bahamas-shoals", "巴哈马浅滩", "", "", "",
                3840, 2160, 50L * 1024 * 1024 + 1, sha, mirrorFull, mirrorThumb, 24000, sha));
        expectReject("reject_bad_sha", () -> WallpaperCatalogPolicy.validateItem(
                "01-bahamas-shoals", "巴哈马浅滩", "", "", "",
                3840, 2160, 500000, "not-a-sha", mirrorFull, mirrorThumb, 24000, sha));
        expectReject("reject_untrusted_thumb", () -> WallpaperCatalogPolicy.validateItem(
                "01-bahamas-shoals", "巴哈马浅滩", "", "", "",
                3840, 2160, 500000, sha, mirrorFull, "https://evil.example/wallpapers/t.jpg", 24000, sha));
        expectReject("reject_oversized_thumb", () -> WallpaperCatalogPolicy.validateItem(
                "01-bahamas-shoals", "巴哈马浅滩", "", "", "",
                3840, 2160, 500000, sha, mirrorFull, mirrorThumb, 3L * 1024 * 1024, sha));

        check("format_size_kb_and_mb", WallpaperCatalogPolicy.formatSize(512000).equals("500 KB")
                && WallpaperCatalogPolicy.formatSize(2L * 1024 * 1024).equals("2.0 MB"));

        String mirrorVideo = "https://marcotv.wlj9919.top/wallpapers/videos/06-black-cat.mp4";
        String fallbackVideo = "https://pilipala9919.github.io/MarcoTV/wallpapers/videos/06-black-cat.mp4";
        check("valid_mirror_video_url", WallpaperCatalogPolicy.videoUrl(mirrorVideo)
                && WallpaperCatalogPolicy.videoUrl("https://marcotv.wlj9919.top/wallpapers/videos/06-black-cat.webm")
                && !WallpaperCatalogPolicy.videoUrl("https://marcotv.wlj9919.top/wallpapers/videos/06-black-cat.mkv")
                && !WallpaperCatalogPolicy.videoUrl(mirrorFull));
        String[] videoCandidates = WallpaperCatalogPolicy.candidateMediaUrls(mirrorVideo, true);
        check("video_candidate_urls_mirror_then_fallback", videoCandidates.length == 2
                && videoCandidates[0].equals(mirrorVideo) && videoCandidates[1].equals(fallbackVideo));
        check("max_video_wallpaper_is_80mb", WallpaperCatalogPolicy.MAX_VIDEO_BYTES == 80L * 1024 * 1024);
        WallpaperCatalogPolicy.validateItem("06-black-cat", "黑猫动态壁纸", "循环动态壁纸",
                "Pilipala", "CC0", 1920, 1080, 12L * 1024 * 1024, sha, mirrorVideo,
                mirrorThumb, 24000, sha, "video");
        check("valid_video_item_accepted", true);
        expectReject("reject_over_80mb_video", () -> WallpaperCatalogPolicy.validateItem(
                "06-black-cat", "黑猫", "", "", "",
                1920, 1080, 80L * 1024 * 1024 + 1, sha, mirrorVideo, mirrorThumb, 24000, sha, "video"));
        expectReject("reject_invalid_item_type", () -> WallpaperCatalogPolicy.validateItem(
                "06-black-cat", "黑猫", "", "", "",
                1920, 1080, 1024, sha, mirrorVideo, mirrorThumb, 24000, sha, "audio"));
        expectReject("reject_image_url_for_video_type", () -> WallpaperCatalogPolicy.validateItem(
                "06-black-cat", "黑猫", "", "", "",
                1920, 1080, 1024, sha, mirrorFull, mirrorThumb, 24000, sha, "video"));

        String pixarVideo = "https://marcotv.wlj9919.top/wallpapers/videos/pixar-squirrel.mp4";
        String pixarThumb = "https://marcotv.wlj9919.top/wallpapers/thumbs/pixar-squirrel.jpg";
        WallpaperCatalogPolicy.validateItem("pixar-squirrel", "顽皮小松鼠", "Q版皮克斯 · 蓝调沙发探头微动态",
                "Pilipala · Marco TV 原创动态壁纸", "GPL-3.0-only", 1280, 720, 1363598L, sha,
                pixarVideo, pixarThumb, 17409L, sha, "video");
        check("valid_pixar_live_video_accepted", true);
        check("third_party_gh_proxy_hosts_rejected",
                !WallpaperCatalogPolicy.transferUrl("https://gh-proxy.com/https://github.com/example/a.mov")
                && !WallpaperCatalogPolicy.transferUrl("https://gh-proxy.org/https://github.com/example/a.mov")
                && !WallpaperCatalogPolicy.videoUrl("https://github.com/AerialScreensaver/AerialCommunity/releases/download/mw2-4k-sdr/video_inspire_italy_como_cerano-dintelvi_006.4k-sdr.mov"));
        check("user_video_import_url_blocks_loopback",
                WallpaperCatalogPolicy.userVideoImportUrl("https://cdn.example.com/live/cat.mp4")
                && WallpaperCatalogPolicy.userVideoImportUrl("http://198.51.100.5:8080/clip.mp4")
                && !WallpaperCatalogPolicy.userVideoImportUrl("http://127.0.0.1/clip.mp4")
                && !WallpaperCatalogPolicy.userVideoImportUrl("https://localhost/clip.mp4")
                && !WallpaperCatalogPolicy.userVideoImportUrl("http://[::1]/clip.mp4")
                && !WallpaperCatalogPolicy.userVideoImportUrl("file:///sdcard/a.mp4")
                && !WallpaperCatalogPolicy.userVideoImportUrl("https://user:pass@example.com/a.mp4"));

        System.out.println("OK " + passed + " wallpaper catalog policy checks");
    }
}
