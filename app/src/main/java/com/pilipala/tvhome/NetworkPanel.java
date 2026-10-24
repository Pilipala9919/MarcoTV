// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.*;
import android.net.wifi.*;
import android.os.Build;
import android.provider.Settings;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;
import java.util.*;

/** Local Wi-Fi controls for the Android 9 TV, with explicit unsupported states. */
final class NetworkPanel {
    static final int PERMISSION = 103;
    private final Activity activity;
    private final SystemIntegration.DialogHost host;
    private final WifiManager wifi;
    private TvDialog visible;
    private boolean scanning;
    private boolean registered;
    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (scanning && WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(i.getAction())) {
                scanning = false;
                if (visible != null && visible.isShowing()) {
                    boolean fresh = i.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
                    show(fresh ? "附近的网络" : "上次扫描结果；稍后可再次刷新");
                }
            }
        }
    };
    NetworkPanel(Activity activity, SystemIntegration.DialogHost host) {
        this.activity=activity; this.host=host;
        wifi=(WifiManager)activity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }
    void close() {
        if (registered) { activity.unregisterReceiver(updates); registered=false; }
    }
    private void toast(String text) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show(); }
    private void present(TvDialog dialog) {
        if (visible != null && visible.isShowing()) visible.dismiss(); visible=dialog; host.show(dialog);
    }
    void show() { show("选择网络连接，或刷新附近网络"); }
    private String currentSummary() {
        ConnectivityManager cm=(ConnectivityManager)activity.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo active=cm.getActiveNetworkInfo();
        String state=active != null && active.isConnected() ? active.getType()==ConnectivityManager.TYPE_ETHERNET ? "有线网络已连接" : "无线网络已连接" : "网络未连接";
        WifiInfo info=wifi == null ? null : wifi.getConnectionInfo();
        if (info != null && info.getNetworkId() >= 0) {
            String ssid=info.getSSID();
            if (ssid != null && !ssid.equals("<unknown ssid>")) state += "\n" + unquote(ssid);
            int ip=info.getIpAddress();
            if (ip != 0) state += String.format(Locale.ROOT,"\nIP  %d.%d.%d.%d",ip&255,(ip>>8)&255,(ip>>16)&255,(ip>>24)&255);
            state += "  ·  信号 " + WifiManager.calculateSignalLevel(info.getRssi(), 4) + "/3";
        }
        return state;
    }
    private void show(String subtitle) {
        if (activity.isDestroyed() || activity.isFinishing()) return;
        if (wifi == null) { present(new TvDialog.Builder(activity).setTitle("网络与 Wi-Fi").setMessage("此设备没有可用的 Wi-Fi 模块。").setNegativeButton("返回",null).create()); return; }
        if (!registered) { activity.registerReceiver(updates,new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)); registered=true; }
        final ArrayList<ScanResult> networks = new ArrayList<>();
        boolean permitted=activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
        if (permitted) try {
            HashMap<String,ScanResult> unique=new HashMap<>();
            for (ScanResult r:wifi.getScanResults()) {
                if (r.SSID==null || r.SSID.isEmpty()) continue;
                String key=r.SSID+"|"+security(r);
                if (!unique.containsKey(key) || unique.get(key).level<r.level) unique.put(key,r);
            }
            networks.addAll(unique.values()); Collections.sort(networks,(a,b)->Integer.compare(b.level,a.level));
        } catch (SecurityException ignored) { subtitle="允许附近网络权限后可查看网络"; }
        ArrayList<String> labels=new ArrayList<>();
        labels.add("Wi-Fi："+(wifi.isWifiEnabled()?"已开启":"已关闭"));
        labels.add(permitted?"刷新附近网络":"允许查找附近网络");
        labels.add("查看连接详情");
        for (ScanResult r:networks) labels.add(r.SSID+"  ·  "+security(r)+"  ·  "+(WifiManager.calculateSignalLevel(r.level,4)+1)+" 格");
        if (networks.isEmpty() && permitted) subtitle="暂无扫描结果。刷新前请确认 Wi-Fi 与系统定位已开启。";
        present(new TvDialog.Builder(activity).setTitle("网络与 Wi-Fi").setMessage(currentSummary()+"\n\n"+subtitle)
                .setItems(labels.toArray(new String[0]),(d,index)->{
                    if(index==0) toggle(); else if(index==1) scan(); else if(index==2) details(); else choose(networks.get(index-3));
                }).setNegativeButton("返回",null).create());
    }
    private void details() {
        present(new TvDialog.Builder(activity).setTitle("连接详情").setMessage(currentSummary()+"\n\n网络配置由电视系统保存。Marco TV 不保存 Wi-Fi 密码。")
                .setPositiveButton("返回网络",(d,w)->show()).create());
    }
    private void toggle() {
        if (Build.VERSION.SDK_INT>28) { unavailable(); return; }
        boolean enabled=wifi.isWifiEnabled();
        present(new TvDialog.Builder(activity).setTitle(enabled?"关闭 Wi-Fi？":"开启 Wi-Fi？")
                .setMessage(enabled?"无线网络与无线调试连接会断开。可使用遥控器在这里重新开启。":"开启后可刷新附近网络。")
                .setPositiveButton(enabled?"关闭":"开启",(d,w)->{
                    try { toast(wifi.setWifiEnabled(!enabled)?"已提交，请稍后刷新状态":"系统未允许更改 Wi-Fi 开关"); }
                    catch(RuntimeException e) { toast("系统未允许更改 Wi-Fi 开关"); }
                    show();
                }).setNegativeButton("取消",(d,w)->show()).create());
    }
    private void scan() {
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION); return;
        }
        if (!wifi.isWifiEnabled()) { show("请先开启 Wi-Fi"); return; }
        if (Build.VERSION.SDK_INT>=28 && Settings.Secure.getInt(activity.getContentResolver(),Settings.Secure.LOCATION_MODE,0)==0) {
            present(new TvDialog.Builder(activity).setTitle("扫描附近网络需要定位开关").setMessage("Android 要求开启系统定位才能扫描 Wi-Fi。仅用于列出附近网络，Marco TV 不读取位置坐标。")
                    .setPositiveButton("开启并扫描",(d,w)->{
                        if (SystemIntegration.authorized(activity)) {
                            try { Settings.Secure.putInt(activity.getContentResolver(),Settings.Secure.LOCATION_MODE,Settings.Secure.LOCATION_MODE_HIGH_ACCURACY); scan(); }
                            catch(RuntimeException e) { toast("系统未允许开启定位，请在电视系统中开启后重试"); show(); }
                        } else { toast("请在电视系统中开启定位，再返回刷新网络"); show(); }
                    }).setNegativeButton("取消",(d,w)->show()).create()); return;
        }
        try { scanning=wifi.startScan(); show(scanning?"正在扫描，结果将在完成后更新":"扫描暂不可用，显示上次结果"); }
        catch (RuntimeException e) { scanning=false; show("扫描未获系统允许，请检查权限与定位开关"); }
    }
    void permissionResult(boolean allowed) { if(allowed) scan(); else show("未授权查找附近网络，可继续查看连接状态"); }
    private static String security(ScanResult r) {
        String c=r.capabilities;
        if(c.contains("EAP"))return "企业网络";
        if(c.contains("PSK"))return "密码保护";
        if(c.contains("WEP"))return "WEP";
        if(c.contains("SAE"))return "WPA3";
        if(c.contains("OWE"))return "增强开放网络";
        return "开放网络";
    }
    private void choose(ScanResult result) {
        if(Build.VERSION.SDK_INT>28) { unavailable(); return; }
        String type=security(result);
        if (!type.equals("密码保护") && !type.equals("开放网络")) {
            present(new TvDialog.Builder(activity).setTitle(result.SSID).setMessage("此安全类型需要电视系统的网络配置。当前版本支持开放网络与 WPA/WPA2 个人网络。")
                    .setNegativeButton("返回网络",(d,w)->show()).create()); return;
        }
        WifiConfiguration saved=null;
        try { List<WifiConfiguration> configs=wifi.getConfiguredNetworks();
            if(configs!=null)for(WifiConfiguration c:configs) if(unquote(c.SSID).equals(result.SSID)
                    && (c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)==type.equals("密码保护"))) { saved=c; break; }
        } catch(SecurityException ignored) { }
        final WifiConfiguration existing=saved;
        EditText password=new EditText(activity); password.setSingleLine(true); password.setHint("Wi-Fi 密码");
        password.setContentDescription("Wi-Fi 密码"); password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        TvDialog.Builder b=new TvDialog.Builder(activity).setTitle(result.SSID)
                .setMessage(existing!=null?"此网络已保存，可直接连接。":type.equals("开放网络")?"这是开放网络，无需密码。":"输入密码后连接此网络。");
        if(existing==null && type.equals("密码保护"))b.setView(password);
        present(b.setPositiveButton("连接",(d,w)->{
            String secret=password.getText().toString();
            if(existing==null && type.equals("密码保护") && !(secret.matches("[0-9a-fA-F]{64}") || (secret.length()>=8 && secret.length()<=63))) {
                toast("密码需要 8–63 个字符或 64 位十六进制密钥"); choose(result); return;
            }
            connect(result,existing,secret);
        }).setNegativeButton("取消",(d,w)->show()).create());
    }
    private void connect(ScanResult r, WifiConfiguration existing, String password) {
        try {
            int id;
            if(existing!=null)id=existing.networkId;
            else {
                WifiConfiguration c=new WifiConfiguration(); c.SSID=quote(r.SSID);
                if(security(r).equals("密码保护")) { c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK); c.preSharedKey=password.matches("[0-9a-fA-F]{64}")?password:quote(password); }
                else c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                id=wifi.addNetwork(c);
            }
            if(id<0) { toast("系统拒绝保存网络配置"); show(); return; }
            if(!wifi.enableNetwork(id,true)) {
                if(existing==null)wifi.removeNetwork(id);
                toast("系统未允许连接此网络"); show(); return;
            }
            toast("已请求连接，稍后在连接详情中查看结果"); show();
        } catch(RuntimeException e) { toast("连接请求未获系统允许"); show(); }
    }
    private static String quote(String s) { return "\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\""; }
    private static String unquote(String s) { return s==null?"":s.length()>1 && s.startsWith("\"") && s.endsWith("\"")?s.substring(1,s.length()-1):s; }
    private void unavailable() { present(new TvDialog.Builder(activity).setTitle("此系统限制网络配置")
            .setMessage("当前网络控制适配 Android 9 电视。此系统版本需要在电视系统设置中更改连接，Marco TV 仍可显示网络状态。")
            .setNegativeButton("返回网络",(d,w)->show()).create()); }
}
