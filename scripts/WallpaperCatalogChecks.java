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

        System.out.println("OK " + passed + " wallpaper catalog policy checks");
    }
}
