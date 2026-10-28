// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import java.net.InetSocketAddress;
import java.net.Socket;
import org.json.JSONObject;

/** Single read-only source for system status. Never recovers settings or configures the bridge. */
final class StatusSnapshot {
    static String local(Context c){
        SharedPreferences p=SystemIntegration.prefs(c);
        boolean adb=Settings.Global.getInt(c.getContentResolver(),Settings.Global.ADB_ENABLED,0)==1;
        return "默认桌面："+(SystemIntegration.homeIsOurs(c)?"Marco TV":"厂商或其他桌面")
            +"\n系统设置授权："+(SystemIntegration.authorized(c)?"已授权":"未授权")
            +"\nHome 兼容："+(p.getBoolean("home_compat",false)?"已开启":"未开启")+" / 服务"+(HomeKeyService.connected?"已连接":"未连接")
            +"\nHome 原厂按键："+(p.getBoolean("home_native_keys",false)?"放行":"Marco 处理")+" / 长按："+p.getString("home_long_action","system")
            +"\n开机打开："+(p.getBoolean("boot_home",false)?"已开启（待开机实测）":"未开启")
            +"\nADB 自动恢复："+(p.getBoolean("adb_restore",false)?"已开启":"未开启")+" / 调试开关"+(adb?"已打开":"已关闭")
            +"\n恢复服务："+(RecoveryService.running?"运行中":"未运行")
            +"\n最近恢复检查："+HealthPanel.date(p.getLong("last_check",0))
            +"\n最近恢复 ADB："+HealthPanel.date(p.getLong("last_adb_restore",0))
            +"\n最近开机记录："+HealthPanel.date(p.getLong("last_boot_time",0))
            +"\n恢复结果："+(p.contains("last_error")?"需要处理，请查看系统与启动":"未记录错误")
            +"\n唤醒恢复偏好："+(c.getSharedPreferences("home",0).getBoolean("recent_wake",true)?"已开启（还需 Home 兼容与任务服务）":"未开启");
    }
    static String extended(){
        boolean reachable=false;
        try(Socket socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",5555),600);reachable=true;}catch(Exception ignored){}
        String state="本机 5555 端口："+(reachable?"可连接（不等于局域网 ADB 已通过）":"未监听或使用其他端口");
        try{
            JSONObject task=TaskBridgeClient.request("status");
            state+="\n任务服务：已连接\n任务服务版本："+task.optString("version")
                +"\n任务服务唤醒恢复："+(task.optBoolean("wake")?"已启用":"未启用")
                +"\n清除单个任务："+(task.optBoolean("canRemove")?("task".equals(task.optString("removeMode"))?"接口可用，需实际测试":"仅独立任务可清除"):"系统未开放")
                +"\n最近唤醒结果："+task.optString("lastWake","尚无记录");
        }catch(Exception e){state+="\n任务服务：未连接或无法读取，请按安装说明从电脑启动。完整重启或 APK 更新后需重新启动。";}
        return state;
    }
}
