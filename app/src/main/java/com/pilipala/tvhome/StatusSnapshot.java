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
        String currentHome=SystemIntegration.currentHome(c);
        String tracked=SystemIntegration.trackedHome(c);
        String trackedLine=SystemIntegration.trackedHomeInvalid(c)
                ?"已失效（"+SystemIntegration.trackedHomeInvalidName(c)+"），无可靠替代，已停止窗口回拉"
                :tracked.isEmpty()?"未记录":tracked;
        String confirmed=HomeSetup.bootConfirmationStatus(c);
        HomeSetupPolicy.DesktopState state=SystemIntegration.desktopState(c);
        String oemExp=SystemIntegration.hasDisabledOemExperiment(c)
                ?"已停用原厂桌面（"+SystemIntegration.disabledOemExperimentPackage(c)+"），可随时恢复"
                :"未启用";
        String keyFilterStatus;
        if(!HomeKeyService.connected){
            keyFilterStatus="未绑定";
        }else if(HomeKeyService.runtimeVolumeYieldActive){
            keyFilterStatus="无桥临时让渡中";
        }else if(HomeKeyService.runtimeVolumeYieldFailed){
            keyFilterStatus="系统拒绝撤销过滤（让渡不可用）";
        }else if(HomeKeyService.runtimeKeyFilterRequested){
            keyFilterStatus="已请求";
        }else if(SystemIntegration.guardMode(c)==HomeSetupPolicy.GuardMode.KEY_FILTER&&!SystemIntegration.homeIsOurs(c)&&!HomeSetup.paused(c)&&p.getBoolean("home_compat",false)){
            keyFilterStatus="系统拒绝开启过滤（未生效）";
        }else{
            keyFilterStatus="未请求";
        }
        return "用户选择："+state.userChoiceLabel
                +"\n当前实际生效："+state.effectiveModeLabel
                +"\n降级原因："+state.fallbackReason
                +"\n原生默认桌面："+(BuildEdition.RESTRICTED?"受限兼容版不含 HOME 声明":SystemIntegration.homeIsOurs(c)?"Marco TV":"厂商或其他桌面（"+(currentHome.isEmpty()?"未指定":currentHome)+"）")
                +"\n系统设置授权："+(SystemIntegration.authorized(c)?"已授权":"未授权")
                +"\nHome 兼容："+(p.getBoolean("home_compat",false)?"已开启":"未开启")+" / 服务："+HomeSetup.serviceStatusLabel(c)
                +"\n兼容回拉目标："+trackedLine
                +"\nHome 返回观察："+HomeSetup.homeReturnStatus(c)
                +"\nHome 守护模式："+SystemIntegration.guardModeLabel(SystemIntegration.guardMode(c))
                +" / 状态："+SystemIntegration.rebindStatusLabel(c)
                +" / 按键过滤："+keyFilterStatus
                +"（flags="+HomeKeyService.runtimeFlags+"） / 长按："+p.getString("home_long_action","system")
                +"\n停用原厂桌面实验："+oemExp
                +"\n开机打开："+(p.getBoolean("boot_home",false)?"已开启":"未开启")+" / 状态："+HomeSetup.bootStatus(c)
                +(confirmed.isEmpty()?"":"\n开机人工确认："+confirmed)
                +"\nADB 自动恢复："+SystemIntegration.adbRestoreLabel(c)+" / 调试开关"+(adb?"已打开":"已关闭")
                +"\n恢复服务："+(RecoveryService.running?"运行中":"未运行")
                +"\n最近恢复检查："+HealthPanel.date(p.getLong("last_check",0))
                +"\n最近恢复 ADB："+HealthPanel.date(p.getLong("last_adb_restore",0))
                +"\n最近开机记录："+HealthPanel.date(p.getLong("last_boot_time",0))
                +"\n恢复结果："+(p.contains("last_error")?"需要处理，请查看系统与启动":"未记录错误")
                +(p.contains("last_notice")?"\n提示："+p.getString("last_notice",""):"")
                +"\n唤醒恢复偏好："+(c.getSharedPreferences("home",0).getBoolean("recent_wake",true)?"已开启（还需 Home 兼容与任务服务）":"未开启");
    }
    static String extended(){
        boolean reachable=false;
        try(Socket socket=new Socket()){
            socket.connect(new InetSocketAddress("127.0.0.1",5555),600);
            reachable=true;
        }catch(Exception ignored){}
        String state="本机 5555 端口："+(reachable?"可连接（不等于局域网 ADB 已通过）":"未监听或使用其他端口");
        try{
            JSONObject task=TaskBridgeClient.request("status");
            boolean volCfg=task.optBoolean("volumeAssist",false);
            boolean volAct=task.optBoolean("volumeAssistActive",false);
            boolean volAudio=task.optBoolean("volumeAudioReady",false);
            int devCount=task.optInt("volumeInputDeviceCount",0);
            String volReason=task.optString("volumeAssistReason",volAct?"ready":"unavailable");
            state+="\n任务服务：已连接\n任务服务版本："+task.optString("version")
                    +"\n任务服务唤醒恢复："+(task.optBoolean("wake")?"已启用":"未启用")
                    +"\n任务桥音量长按辅助："+(volAct?"已生效（音量输入设备 "+devCount+" 个，音频能力就绪，已补偿 "+task.optLong("volumeAdjustCount",0)+" 次）":volCfg?"已请求但未就绪（原因："+volReason+"，音量输入设备 "+devCount+" 个，音频="+(volAudio?"就绪":"不可用")+"）":"未启用（原因："+volReason+"）")
                    +"\n音量补偿最近停止原因："+task.optString("volumeLastStopReason","idle")
                    +"\n清除单个任务："+(task.optBoolean("canRemove")?("task".equals(task.optString("removeMode"))?"接口可用，需实际测试":"仅独立任务可清除"):"系统未开放")
                    +"\n最近唤醒结果："+task.optString("lastWake","尚无记录");
        }catch(Exception e){
            state+="\n任务服务：未连接或无法读取，可在最近任务中选择「本机一键启动任务服务」或按安装说明从电脑启动。完整重启或 APK 更新后需重新启动。";
        }
        return state;
    }
}
