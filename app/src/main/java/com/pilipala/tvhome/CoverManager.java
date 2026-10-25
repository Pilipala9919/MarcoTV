// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import java.io.*;
import java.util.*;

/** Local image picking and importing for card covers and the wallpaper. */
final class CoverManager {
    static final int PICK_COVER = 101, READ_COVER = 102;
    private final MainActivity host;
    String pendingId;
    CoverManager(MainActivity host) { this.host = host; }

    void chooseCoverImage() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (host.getPackageManager().resolveActivity(picker, 0) != null) {
            try { host.startActivityForResult(picker, PICK_COVER); return; } catch (RuntimeException ignored) { }
        }
        if (Build.VERSION.SDK_INT >= 33) { host.toast("请安装支持选择图片的文件管理器"); pendingId = null; return; }
        if (host.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            host.requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, READ_COVER); return;
        }
        browseCoverFolder(Environment.getExternalStorageDirectory());
    }

    void browseCoverFolder(File folder) {
        if (pendingId == null) return;
        File root = Environment.getExternalStorageDirectory();
        try {
            String base = root.getCanonicalPath(), path = folder.getCanonicalPath();
            if (!path.equals(base) && !path.startsWith(base + File.separator)) { host.toast("请选择共享存储中的图片"); return; }
        } catch (IOException e) { host.toast("无法读取此目录"); return; }
        File[] found = folder.listFiles(f -> !f.getName().startsWith(".") && (f.isDirectory()
                || f.getName().toLowerCase(Locale.ROOT).matches(".*\\.(png|jpg|jpeg|webp)")));
        ArrayList<File> files = new ArrayList<>(); if (found != null) files.addAll(Arrays.asList(found));
        Collections.sort(files, (a, b) -> a.isDirectory() != b.isDirectory() ? (a.isDirectory() ? -1 : 1) : a.getName().compareToIgnoreCase(b.getName()));
        if (files.size() > 300) { files = new ArrayList<>(files.subList(0, 300)); host.toast("此目录仅显示前 300 项，请将封面放入单独文件夹"); }
        boolean parent = !folder.equals(root);
        ArrayList<String> labels = new ArrayList<>(); if (parent) labels.add("↑ 上一级");
        for (File file : files) labels.add((file.isDirectory() ? "文件夹 · " : "") + file.getName());
        if (labels.isEmpty()) {
            host.showDialog(new TvDialog.Builder(host).setTitle("此文件夹没有图片").setMessage("可把 PNG、JPG 或 WebP 图片放入电视的 Download 或 Pictures 文件夹。")
                    .setPositiveButton("返回目录", (d, w) -> host.handler.post(() -> browseCoverFolder(parent ? folder.getParentFile() : root)))
                    .setNegativeButton("取消", (d, w) -> pendingId = null).create()); return;
        }
        final ArrayList<File> choices = files;
        host.showDialog(new TvDialog.Builder(host).setTitle(folder.equals(root) ? "电视存储 · 选择图片" : folder.getName())
                .setItems(labels.toArray(new String[0]), (d, which) -> host.handler.post(() -> {
                    if (parent && which == 0) { browseCoverFolder(folder.getParentFile()); return; }
                    File selected = choices.get(which - (parent ? 1 : 0));
                    if (selected.isDirectory()) browseCoverFolder(selected); else importCover(Uri.fromFile(selected));
                })).setNegativeButton("取消", (d, w) -> pendingId = null).create());
    }

    void importCover(Uri uri) {
        final String id = pendingId; pendingId = null;
        if (id == null) { host.toast("请重新选择要编辑的卡片"); return; }
        host.toast("正在保存封面…");
        host.loader.execute(() -> {
            try {
                if (id.equals("__wallpaper__")) {
                    BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                    try (InputStream check = host.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(check, null, bounds); }
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Invalid image");
                    android.util.AtomicFile file = new android.util.AtomicFile(new File(host.getFilesDir(), "wallpaper-image"));
                    FileOutputStream output = null;
                    try (InputStream input = host.getContentResolver().openInputStream(uri)) {
                        output = file.startWrite(); byte[] buffer = new byte[8192]; int count; long total = 0;
                        while ((count = input.read(buffer)) >= 0) {
                            total += count; if (total > 32 * 1024 * 1024) throw new IOException("Image too large");
                            output.write(buffer, 0, count);
                        }
                        file.finishWrite(output); output = null;
                    } catch (IOException | RuntimeException error) { if (output != null) file.failWrite(output); throw error; }
                    host.preferences.edit().putString("wallpaper", Uri.fromFile(file.getBaseFile()).toString()).apply();
                    host.handler.post(() -> { if (!host.isDestroyed()) { host.loadWallpaper(); host.toast("壁纸已保存"); } });
                } else {
                    host.cards.importImage(id, uri);
                    host.handler.post(() -> { if (!host.isDestroyed()) { host.loadApplications(); host.toast("封面已保存"); } });
                }
            } catch (IOException | RuntimeException e) {
                Diagnostics.event("IMAGE_IMPORT_FAILED");
                host.handler.post(() -> { if (!host.isDestroyed()) host.toast("图片无法读取，请换一张图片"); });
            }
        });
    }

    /** Returns true when the activity result belonged to cover picking. */
    boolean onActivityResult(int request, int result, Intent data) {
        if (request != PICK_COVER) return false;
        if (result == Activity.RESULT_OK && data != null && data.getData() != null) importCover(data.getData());
        else pendingId = null;
        return true;
    }

    void onPermissionResult(int[] grants) {
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) chooseCoverImage();
        else { pendingId = null; host.toast("未获得图片读取权限，仍可使用内置封面"); }
    }
}
