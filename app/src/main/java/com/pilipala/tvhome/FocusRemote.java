// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.app.AlertDialog;
import android.graphics.*;
import android.os.SystemClock;
import android.view.WindowManager;
import android.widget.ImageView;
import java.net.*;
import java.util.Collections;
import org.json.JSONObject;
import io.nayuki.qrcodegen.QrCode;

/** Activity-owned. No intent, file, service, preference or legacy HTTP pairing route. */
final class FocusRemote implements FocusTransport.Events, FocusSession.Surface {
    private final MainActivity host;
    private long generation;
    private FocusTransport current;
    private AlertDialog dialog;
    private boolean active;

    FocusRemote(MainActivity host) { this.host = host; }

    void settings() {
        close();
        AlertDialog open = new AlertDialog.Builder(host).setTitle("安全卡片遥控")
                .setMessage("允许一台电脑申请移动此桌面的卡片焦点。请在两分钟内扫描下一页的二维码。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开启配对", (d,w) -> openWindow()).create();
        present(open, false);
    }

    private void openWindow() {
        close();
        host.home.resetToApps();
        final long expected = generation;
        new Thread(() -> {
            FocusTransport transport = null;
            try {
                InetAddress address = lanAddress();
                FocusIdentity identity = new FocusIdentity();
                FocusSession policy = new FocusSession(SystemClock::elapsedRealtime);
                String ticket = policy.openWindow();
                transport = new FocusTransport(identity, address, policy, this);
                FocusTransport opened = transport;
                // Scanned optically by the user. Never written to disk, logs, HTTP or intents.
                String bundle = new JSONObject().put("version",2).put("host",address.getHostAddress())
                        .put("port",opened.listener.getLocalPort()).put("device_id",identity.deviceId)
                        .put("pin_sha256",identity.pin).put("pem",identity.pem).put("ticket",ticket).toString();
                Bitmap qr = qr(bundle);
                host.handler.post(() -> {
                    if (generation != expected || !host.foreground || !host.hasWindowFocus()) { opened.close(); return; }
                    current = opened;
                    ImageView image = new ImageView(host);
                    image.setImageBitmap(qr);
                    image.setAdjustViewBounds(true);
                    image.setMaxHeight(host.getResources().getDisplayMetrics().heightPixels * 2/3);
                    AlertDialog scan = new AlertDialog.Builder(host).setTitle("扫描配对码 · 120 秒")
                            .setView(image).setNegativeButton("撤销并关闭",(d,w)->close()).create();
                    scan.setOnCancelListener(d -> close());
                    present(scan,true);
                    opened.start();
                    checkExpiry(opened);
                });
            } catch (Exception failed) {
                if (transport != null) transport.close();
                host.handler.post(() -> { if (generation == expected) { close(); host.toast("无法开启局域网配对"); } });
            }
        }, "marco-focus-identity").start();
    }
    private static InetAddress lanAddress() throws Exception {
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) continue;
            for (InetAddress ip : Collections.list(nic.getInetAddresses()))
                if (ip instanceof Inet4Address && ip.isSiteLocalAddress()) return ip;
        }
        throw new SocketException("NO_LAN");
    }
    private static Bitmap qr(String bundle) {
        QrCode code = QrCode.encodeText(bundle,QrCode.Ecc.MEDIUM);
        int side = code.size+8, scale = 6;
        Bitmap bitmap = Bitmap.createBitmap(side*scale,side*scale,Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.WHITE);
        Paint black = new Paint(); black.setColor(Color.BLACK);
        for(int y=0;y<code.size;y++) for(int x=0;x<code.size;x++)
            if(code.getModule(x,y)) canvas.drawRect((x+4)*scale,(y+4)*scale,(x+5)*scale,(y+5)*scale,black);
        bitmap.setDensity(Bitmap.DENSITY_NONE);
        return bitmap;
    }
    private void present(AlertDialog next, boolean secret) {
        dialog = next;
        if (secret) next.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        next.show();
        if (next.getButton(AlertDialog.BUTTON_POSITIVE) != null)
            next.getButton(AlertDialog.BUTTON_POSITIVE).setFilterTouchesWhenObscured(true);
        next.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
    }
    private void dismiss() {
        AlertDialog old = dialog; dialog = null;
        if (old != null) old.dismiss();
    }
    @Override public void pending(FocusTransport source,String channel,String peer) {
        host.handler.post(() -> {
            if (current != source || !host.foreground || !source.policy.pending(channel)
                    || dialog == null || !dialog.isShowing() || !dialog.getWindow().getDecorView().hasWindowFocus()) {
                source.close(); return;
            }
            dismiss();
            AlertDialog approve = new AlertDialog.Builder(host).setTitle("允许电脑移动桌面卡片？")
                    .setMessage("电脑地址 " + peer + "\n仅限上下左右，最长五分钟。打开桌面设置可随时撤销。")
                    .setNegativeButton("拒绝",(d,w)->close())
                    .setPositiveButton("批准",(d,w)->{
                        dismiss();
                        activate(source,channel,SystemClock.elapsedRealtime()+1000);
                    }).create();
            approve.setOnCancelListener(d -> close());
            present(approve,false);
        });
    }
    private void activate(FocusTransport source,String channel,long deadline) {
        if (current != source || !host.foreground || !source.policy.pending(channel)) { source.close(); return; }
        if (!host.home.canControlCards()) {
            if (SystemClock.elapsedRealtime() >= deadline) { close(); host.toast("请先返回桌面卡片区"); }
            else host.handler.postDelayed(()->activate(source,channel,deadline),32);
            return;
        }
        try {
            synchronized (source.policy) {
                if (!source.policy.approve(channel)) { close(); return; }
                try { host.home.beginRemoteFocus(); }
                catch (RuntimeException failed) { source.policy.revoke(); throw failed; }
                active = true;
            }
            android.util.Log.i("MarcoFocus","TV_APPROVED");
            host.toast("电脑已连接，打开桌面设置可撤销");
        } catch (RuntimeException blocked) { close(); }
    }
    private void checkExpiry(FocusTransport source) {
        if (current != source) return;
        if (source.policy.state() == FocusSession.State.CLOSED || (active && !host.home.canControlCards())) {
            close(); return;
        }
        host.handler.postDelayed(()->checkExpiry(source),200);
    }
    @Override public void frame(FocusTransport source,String channel,String[] fields,long received) {
        host.handler.post(() -> {
            if (source != current || !active || !host.home.canControlCards()) { source.close(); return; }
            try {
                String result = source.policy.frame(channel,fields[0],Long.parseLong(fields[1]),fields[2],
                        fields.length==4 ? fields[3] : "",received,this);
                boolean terminal = source.policy.state()==FocusSession.State.CLOSED || result.equals("UNAUTHORIZED");
                if (fields[2].equals("STEP"))
                    android.util.Log.i("MarcoFocus","STEP " + result.split(" ")[0]);
                source.reply(result,terminal);
            } catch (RuntimeException invalid) { source.close(); }
        });
    }
    public MarcoFocusController.Snapshot snapshot() { return host.home.remoteSnapshot(); }
    public MarcoFocusController.Result move(MarcoFocusController.Snapshot expected,
            MarcoFocusController.Direction direction,long deadline) {
        return host.home.remoteStep(expected,direction,deadline);
    }
    public void ended(FocusTransport source) {
        host.handler.post(()->{ if (current == source) close(); });
    }
    void windowLost() { if (active) close(); }
    void close() {
        generation++;
        if (active) android.util.Log.i("MarcoFocus","LOCAL_SESSION_ENDED");
        FocusTransport source = current; current = null; active = false;
        if (source != null) source.close();
        if (host.home != null) host.home.endRemoteFocus();
        dismiss();
    }
}
