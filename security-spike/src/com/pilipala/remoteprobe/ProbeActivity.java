// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.remoteprobe;

import android.app.*;
import android.os.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.util.*;
import org.json.JSONObject;

/** Separate app UID; no launcher dependency. No intent, broadcast or network approval route. */
public final class ProbeActivity extends Activity {
    private SessionPolicy policy = new SessionPolicy(SystemClock::elapsedRealtime);
    private DeviceIdentity identity;
    private ProbeServer server;
    private TextView status, pairing;
    private Spinner interfaces;
    private final ArrayList<InetAddress> addresses = new ArrayList<>();
    private AlertDialog approval;
    private boolean resumed, diagnosticMode;
    private long serverEnd;
    private final Handler timer = new Handler();
    private final Runnable tick = new Runnable() {
        public void run() {
            if (server != null && ((!diagnosticMode && policy.state() == SessionPolicy.State.CLOSED)
                    || SystemClock.elapsedRealtime() >= serverEnd)) stop();
            if (!diagnosticMode && policy.state() == SessionPolicy.State.CLOSED) {
                pairing.setText("配对窗口已关闭；不会接收真实桌面输入");
                deleteFile("pairing.json");
            }
            timer.postDelayed(this, 500);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(32,16,32,16);
        setContentView(layout);
        TextView title = new TextView(this); title.setText("Marco G1-B1 安全验证 · 仅假输入计数"); title.setTextSize(24); layout.addView(title);
        interfaces = new Spinner(this); layout.addView(interfaces);
        Button open = new Button(this); open.setText("主动开启配对（120 秒）"); layout.addView(open);
        Button revoke = new Button(this); revoke.setText("拒绝 / 撤销全部临时凭据并关闭"); layout.addView(revoke);
        pairing = new TextView(this); pairing.setTextSize(15); layout.addView(pairing);
        status = new TextView(this); status.setTextSize(17); layout.addView(status);
        open.setOnClickListener(v -> start(false)); revoke.setOnClickListener(v -> { stop(); event("LOCAL_REVOKE"); });
        try {
            identity = new DeviceIdentity();
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) continue;
                for (InetAddress address : Collections.list(nic.getInetAddresses()))
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) addresses.add(address);
            }
            ArrayList<String> labels = new ArrayList<>();
            for (InetAddress a : addresses) labels.add(a.getHostAddress());
            interfaces.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, labels));
            save("identity.pem", identity.pem);
            event("KEYSTORE_OK nonexportable=true hardwareBacked=" + identity.hardwareBacked);
        } catch (Exception error) {
            open.setEnabled(false); event("IDENTITY_ERROR " + error.getClass().getSimpleName());
        }
        // Development-only, handshake-only diagnostic. Cannot open a pairing window or approve.
        // Diagnostic starts once from onResume, after Activity is actually foreground.
    }
    private void start(boolean diagnostic) {
        if ((!diagnostic && (!resumed || !hasWindowFocus() || !((PowerManager)getSystemService(POWER_SERVICE)).isInteractive())) || identity == null || addresses.isEmpty()) { event("START_UNAVAILABLE resumed=" + resumed + " addresses=" + addresses.size()); return; }
        stop();
        try {
            final SessionPolicy current = new SessionPolicy(SystemClock::elapsedRealtime);
            policy = current; diagnosticMode = diagnostic;
            serverEnd = SystemClock.elapsedRealtime() + (diagnostic ? 60_000 : 450_000);
            String ticket = diagnostic ? null : current.openWindow();
            InetAddress address = addresses.get(interfaces.getSelectedItemPosition());
            server = new ProbeServer(identity, address, policy, new ProbeServer.Observer() {
                public void event(String message) { ProbeActivity.this.event(message); }
                public void pending(String connection, String peer) {
                    runOnUiThread(() -> {
                        if (!resumed || !current.pending(connection)) return;
                        deleteFile("pairing.json");
                        approval = new AlertDialog.Builder(ProbeActivity.this)
                                .setTitle("批准此连接进行假输入测试？")
                                .setMessage("来源：" + peer + "\n仅限假输入计数，最长 5 分钟；不会移动桌面。")
                                .setNegativeButton("拒绝", (d,w) -> { current.revoke(); event("LOCAL_DENY"); })
                                .setPositiveButton("批准", (d,w) -> event(current.approve(connection) ? "LOCAL_APPROVE" : "APPROVAL_EXPIRED"))
                                .setOnCancelListener(d -> { current.revoke(); event("LOCAL_DENY"); }).create();
                        approval.show();
                        approval.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
                    });
                }
            }, diagnostic);
            JSONObject bundle = new JSONObject();
            bundle.put("host", address.getHostAddress()); bundle.put("port", server.port());
            bundle.put("device_id", identity.deviceId); bundle.put("pin_sha256", identity.pin); bundle.put("pem", identity.pem);
            if (ticket != null) bundle.put("ticket", ticket);
            // Debug-only out-of-band bootstrap via existing ADB authorization; never served over LAN.
            save(diagnostic ? "diagnostic.json" : "pairing.json", bundle.toString());
            pairing.setText(diagnostic ? "仅 TLS 握手诊断，60 秒后关闭，无配对或指令入口"
                    : "指纹：" + identity.pin + "\n票据：" + ticket + "\n地址：" + address.getHostAddress() + ":" + server.port());
            event(diagnostic ? "DIAGNOSTIC_STARTED" : "LOCAL_PAIR_WINDOW_OPENED"); server.start();
        } catch (Exception error) { stop(); event("START_ERROR " + error.getClass().getSimpleName()); }
    }
    private void stop() {
        policy.revoke();
        if (server != null) { server.close(); server = null; }
        if (approval != null) { approval.dismiss(); approval = null; }
        deleteFile("pairing.json"); deleteFile("diagnostic.json");
        if (pairing != null) pairing.setText("已撤销，需在电视重新开启配对");
    }
    private void save(String name, String value) throws IOException {
        try (FileOutputStream out = openFileOutput(name, MODE_PRIVATE)) { out.write(value.getBytes("UTF-8")); }
    }
    private synchronized void event(String value) {
        String line = System.currentTimeMillis() + " " + value;
        try (FileOutputStream out = openFileOutput("events.txt", MODE_APPEND)) { out.write((line+"\n").getBytes("UTF-8")); }
        catch(IOException ignored) { }
        runOnUiThread(() -> status.setText(value + "\n假输入累计：" + policy.fakeCount()));
    }
    @Override protected void onResume() { super.onResume(); resumed = true; timer.removeCallbacks(tick); timer.post(tick);
        if (identity != null && getIntent().getBooleanExtra("tls_diagnostic", false)) {
            getIntent().removeExtra("tls_diagnostic"); start(true);
        } }
    @Override protected void onPause() { resumed = false;
        if (!diagnosticMode) { timer.removeCallbacks(tick); stop(); }
        // Handshake-only diagnostics may finish their 60s bound without interactive approval.
        super.onPause(); }
    @Override protected void onDestroy() { timer.removeCallbacksAndMessages(null); stop(); super.onDestroy(); }
}
