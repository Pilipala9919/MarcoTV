// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.List;

/** Runs only from a separately installed, same-signed test APK. Never bundled in Marco TV. */
public final class DeviceRegressionChecks extends Instrumentation {
    private int passed;
    private void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
        passed++;
        Bundle result = new Bundle(); result.putString("stream", "PASS " + name + "\n"); sendStatus(0, result);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            cacheChecks();
            MainActivity activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            final Throwable[] failure = new Throwable[1];
            runOnMainSync(() -> {
                try { navigationChecks(activity); } catch (Throwable error) { failure[0] = error; }
            });
            if (failure[0] != null) throw new AssertionError(failure[0]);
            result.putString("stream", "OK " + passed + " device regression checks\n");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL " + error + "\n"); finish(0, result);
        }
    }
    private void cacheChecks() throws Exception {
        Context context = getTargetContext();
        ArtworkCache.configure(context);
        Field retain = field(ArtworkCache.class, "retain");
        boolean previous = retain.getBoolean(null);
        // Explicitly exercise the retained-cache branch even on a low-memory TV.
        retain.setBoolean(null, true);
        String id = "__marcotv_regression_only__";
        CardStore cards = new CardStore(context);
        File file = new File(context.getCacheDir(), "marcotv-regression.png");
        Bitmap red = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888); red.eraseColor(Color.RED);
        Bitmap blue = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888); blue.eraseColor(Color.BLUE);
        try {
            cards.cover(id, "image"); ArtworkCache.put("cover:" + id, red);
            check("retained_cache_branch_active", cards.loadImage(id) == red);
            try (FileOutputStream out = new FileOutputStream(file)) { blue.compress(Bitmap.CompressFormat.PNG, 100, out); }
            cards.importImage(id, Uri.fromFile(file));
            Bitmap loaded = cards.loadImage(id);
            check("reimport_replaces_cached_cover", loaded != red && Color.blue(loaded.getPixel(20, 20)) > 220
                    && Color.red(loaded.getPixel(20, 20)) < 30);
            ArtworkCache.put("pkg:example.one:0", red); ArtworkCache.put("pkg:example.one:1", blue);
            ArtworkCache.put("pkg:example.other:0", blue);
            ArtworkCache.invalidatePackage("example.one");
            check("package_update_evicts_icon_and_banner", ArtworkCache.get("pkg:example.one:0") == null
                    && ArtworkCache.get("pkg:example.one:1") == null);
            check("package_update_keeps_unrelated_artwork", ArtworkCache.get("pkg:example.other:0") == blue
                    && ArtworkCache.get("cover:" + id) == loaded);
        } finally {
            cards.reset(id); file.delete(); ArtworkCache.clear(); retain.setBoolean(null, previous);
            red.recycle(); blue.recycle();
        }
    }
    private void navigationChecks(MainActivity activity) throws Exception {
        NetworkPanel network = activity.network;
        Field settings = field(NetworkPanel.class, "settingsEpoch");
        Field permissions = field(NetworkPanel.class, "permissionEpoch");
        Field history = field(MainActivity.class, "dialogHistory");
        network.show(); settings.setInt(network, activity.menuEpoch);
        check("wifi_result_handled", network.onActivityResult(NetworkPanel.WIFI_SETTINGS));
        List<?> pages = (List<?>) history.get(activity);
        check("wifi_return_restores_network_page", !pages.isEmpty()
                && ((TvDialog) pages.get(pages.size() - 1)).pageKey().equals("网络与 Wi-Fi"));
        settings.setInt(network, activity.menuEpoch); permissions.setInt(network, activity.menuEpoch);
        activity.onNewIntent(new Intent(Intent.ACTION_MAIN));
        network.onActivityResult(NetworkPanel.WIFI_SETTINGS); network.permissionResult(false);
        check("home_discards_wifi_and_permission_results", ((List<?>) history.get(activity)).isEmpty());
        network.show(); settings.setInt(network, activity.menuEpoch - 1);
        int size = ((List<?>) history.get(activity)).size();
        network.onActivityResult(NetworkPanel.WIFI_SETTINGS);
        check("obsolete_result_does_not_add_page", ((List<?>) history.get(activity)).size() == size);
        activity.onNewIntent(new Intent(Intent.ACTION_MAIN));
    }
}
