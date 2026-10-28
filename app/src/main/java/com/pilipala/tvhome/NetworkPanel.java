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
    static final int PERMISSION = 103, WIFI_SETTINGS = 104;
    private final MainActivity activity;
    private final SystemIntegration.DialogHost host;
    private final WifiManager wifi;
    private TvDialog visible;
    private boolean scanning;
    private boolean registered;
    private int settingsEpoch = -1, permissionEpoch = -1;
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
    NetworkPanel(MainActivity activity, SystemIntegration.DialogHost host) {
        this.activity=activity; this.host=host;
        wifi=(WifiManager)activity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }
    void close() {
        cancelPending();
        if (registered) { activity.unregisterReceiver(updates); registered=false; }
    }
    void cancelPending() { settingsEpoch = -1; permissionEpoch = -1; scanning = false; }
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
            state += "  ·  信号" + new String[]{"弱","一般","良好","强"}[WifiManager.calculateSignalLevel(info.getRssi(),4)];
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
        WifiInfo connected=wifi.getConnectionInfo();
        final String ssid=connected!=null&&connected.getNetworkId()>=0?unquote(connected.getSSID()):"";
        Collections.sort(networks,(a,b)->{int current=Boolean.compare(b.SSID.equals(ssid),a.SSID.equals(ssid));return current!=0?current:Integer.compare(b.level,a.level);});
        int count=Math.min(4,networks.size());
        ArrayList<String> labels=new ArrayList<>();
        labels.add("Wi-Fi · "+(wifi.isWifiEnabled()?"已开启":"已关闭"));
        int[] signals = new int[count+2]; Arrays.fill(signals,-1);
        boolean[] locks=new boolean[signals.length],current=new boolean[signals.length];
        for (int i=0;i<count;i++) {
            ScanResult r=networks.get(i); labels.add(r.SSID);
            signals[i+1]=WifiManager.calculateSignalLevel(r.level,4);locks[i+1]=!security(r).equals("开放网络");current[i+1]=ssid.equals(r.SSID);
        }
        labels.add("更多网络设置…");
        if (networks.isEmpty() && permitted) subtitle="暂无扫描结果。刷新前请确认 Wi-Fi 与系统定位已开启。";
        String[] summaryLines=currentSummary().split("\n");
        String summary=summaryLines[0];
        if(networks.isEmpty())summary+=ssid.isEmpty()?" · 在更多设置中查找网络":" · "+ssid;
        if(scanning)summary="正在查找附近网络…";
        present(new TvDialog.Builder(activity).network().setPageId("wifi_popover").setTitle("Wi-Fi").setMessage(summary)
                .setItems(labels.toArray(new String[0]),(d,index)->{
                    if(index==0) toggle(); else if(index==labels.size()-1) more(networks); else choose(networks.get(index-1));
                }).setSignals(signals).setNetworkStates(locks,current).create());
    }
    private void more(ArrayList<ScanResult> networks){
        boolean permitted=activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
        present(new TvDialog.Builder(activity).setTitle("更多网络设置")
            .setItems(new String[]{permitted?"刷新附近网络":"允许查找附近网络","全部附近网络（"+networks.size()+"）","连接详情","电视系统网络设置"},(d,index)->{
                if(index==0)scan();else if(index==1)allNetworks(networks);else if(index==2)details();else openWifiSettings();
            }).setNegativeButton("返回",null).create());
    }
    private void allNetworks(ArrayList<ScanResult> networks){
        if(networks.isEmpty()){scan();return;}
        String[] labels=new String[networks.size()];int[] signals=new int[labels.length];
        for(int i=0;i<labels.length;i++){labels[i]=networks.get(i).SSID;signals[i]=WifiManager.calculateSignalLevel(networks.get(i).level,4);}
        present(new TvDialog.Builder(activity).setTitle("全部附近网络").setItems(labels,(d,index)->choose(networks.get(index)))
            .setSignals(signals).setNegativeButton("返回",null).create());
    }
    private void details() {
        present(new TvDialog.Builder(activity).setTitle("连接详情").setMessage(currentSummary()+"\n\n网络配置由电视系统保存。Marco TV 不保存 Wi-Fi 密码。")
                .setPositiveButton("返回网络",(d,w)->show()).create());
    }
    private static final String WIFI_PANEL = "android.settings.panel.action.WIFI";
    private void toggle() {
        if (Build.VERSION.SDK_INT>28) { systemManaged("开关 Wi-Fi", "为兼容此版本 Android，Marco TV 将 Wi-Fi 开关交给电视系统。请在系统设置中切换。"); return; }
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
            permissionEpoch = activity.menuEpoch;
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
    void permissionResult(boolean allowed) {
        int epoch = permissionEpoch; permissionEpoch = -1;
        if (epoch < 0 || epoch != activity.menuEpoch) return;
        if(allowed) scan(); else show("未授权查找附近网络，可继续查看连接状态");
    }
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
        if(Build.VERSION.SDK_INT>28) { systemManaged("连接此 Wi-Fi", "为兼容此版本 Android，Marco TV 将网络连接交给电视系统。请在系统设置中选择网络并输入密码。"); return; }
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
    /** Prefer the system UI on newer Android; manufacturer and target-SDK restrictions vary. */
    private void systemManaged(String action, String reason) {
        present(new TvDialog.Builder(activity).setTitle(action + "需通过电视设置")
                .setMessage(reason + "\n\nMarco TV 仍可随时查看当前网络状态。")
                .setPositiveButton("打开 Wi-Fi 设置",(d,w)->openWifiSettings())
                .setNegativeButton("返回网络",(d,w)->show()).create());
    }
    private void openWifiSettings() {
        String[] actions = Build.VERSION.SDK_INT >= 29
                ? new String[]{WIFI_PANEL, Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_SETTINGS}
                : new String[]{Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_SETTINGS};
        for (String action : actions) {
            Intent intent = new Intent(action);
            if (intent.resolveActivity(activity.getPackageManager()) == null) continue;
            try {
                settingsEpoch = activity.menuEpoch;
                activity.startActivityForResult(intent, WIFI_SETTINGS);
                return;
            } catch (RuntimeException ignored) { settingsEpoch = -1; }
        }
        toast("无法打开电视的 Wi-Fi 设置，请从电视主设置进入");
        show();
    }
    boolean onActivityResult(int request) {
        if (request != WIFI_SETTINGS) return false;
        int epoch = settingsEpoch; settingsEpoch = -1;
        if (epoch >= 0 && epoch == activity.menuEpoch && !activity.isDestroyed() && !activity.isFinishing()) show();
        return true;
    }
}
