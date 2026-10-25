// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.*;
import android.os.*;
import android.provider.Settings;
import java.io.*;
import java.text.DateFormat;
import java.util.Date;

/** Read-only checks and local diagnostics; never changes system settings during a check. */
final class HealthPanel {
    private final MainActivity host;
    HealthPanel(MainActivity h){host=h;}
    static String date(long stamp){return stamp==0?"尚无记录":DateFormat.getDateTimeInstance().format(new Date(stamp));}
    String summary(){
        android.content.SharedPreferences p=SystemIntegration.prefs(host);
        boolean adb=Settings.Global.getInt(host.getContentResolver(),Settings.Global.ADB_ENABLED,0)==1;
        return "检查时间："+date(System.currentTimeMillis())
            +"\n默认桌面："+(SystemIntegration.homeIsOurs(host)?"Marco TV":"厂商或其他桌面")
            +"\n系统设置授权："+(SystemIntegration.authorized(host)?"已授权":"未授权")
            +"\nHome 兼容："+(p.getBoolean("home_compat",false)?"已开启":"未开启")+" / 服务"+(HomeKeyService.connected?"已连接":"未连接")
            +"\n开机打开："+(p.getBoolean("boot_home",false)?"已开启（下次开机后验证）":"未开启")
            +"\nADB 恢复："+(p.getBoolean("adb_restore",false)?"已开启":"未开启")+" / 调试开关"+(adb?"已打开":"已关闭")
            +"\n恢复服务："+(RecoveryService.running?"运行中":"未运行")
            +"\n最近恢复检查："+date(p.getLong("last_check",0))
            +"\n最近开机记录："+date(p.getLong("last_boot_time",0))
            +"\n恢复结果："+(p.contains("last_error")?"需要处理，请打开开机设置查看":"未记录错误")
            +"\n播放授权："+(host.playback.authorized()?"已允许":"未允许")
            +"\n播放卡片："+(host.playback.enabled()?"已开启":"已关闭")+" / 可用会话 "+host.playback.availableCount()+" 个"
            +"\n可用存储："+(host.getFilesDir().getUsableSpace()/1024/1024)+" MB"
            +"\n\n这是当前状态检查。网络 ADB 端口、实体 Home、重启和断电恢复需实际操作验证。";
    }
    void status(){host.showDialog(new TvDialog.Builder(host).setTitle("设置状态与检查").setMessage(summary())
        .setItems(new String[]{"重新检查","处理默认桌面 / Home / ADB","处理播放卡片权限","查看本地诊断","重新查看首次引导"},(d,w)->{
            if(w==0){host.playback.refresh();status();}else if(w==1)host.navigator.systemPanel();else if(w==2)host.playback.settings();else if(w==3)diagnostics();else guide(0);
        }).setNegativeButton("返回",null).create());}
    void guide(int step){
        String[] titles={"先试用桌面","默认桌面与 Home","开机与调试恢复","正在播放与本地诊断","完成检查"};
        String[] messages={"方向键选择卡片，确定打开，长按编辑。菜单键进入设置；从设置首页按返回可回桌面。你可以跳过引导，以后从状态页重新打开。",
            "需要按 Home 回到 Marco TV 时，先设为默认桌面。厂商限制时再启用 Home 兼容。开关与授权分开显示；请用实体遥控器实际验证。",
            "按需开启开机打开与 ADB 自动恢复。恢复调试开关需要一次电脑授权，不保证所有电视保留网络端口。无需这些功能可以直接下一步。",
            "正在播放卡片需要可选通知使用权，仅使用媒体会话。本地诊断默认记录自身异常，不记录节目、密码和通知内容，不上传；可以随时关闭与清除。",
            "可以查看当前状态、检查需要补齐的授权，再开始使用。备份与恢复可导出配置到 U 盘或电脑；权限不能随备份迁移。"};
        TvDialog.Builder b=new TvDialog.Builder(host).setTitle("首次使用 · "+(step+1)+" / 5 · "+titles[step]).setMessage(messages[step]);
        if(step==0)b.setItems(new String[]{"下一步"},(d,w)->guide(1));
        else if(step==1||step==2)b.setItems(new String[]{"打开相关设置","下一步"},(d,w)->{if(w==0)host.navigator.systemPanel();else guide(step+1);});
        else if(step==3)b.setItems(new String[]{"设置播放卡片","设置本地诊断","下一步"},(d,w)->{if(w==0)host.playback.settings();else if(w==1)diagnostics();else guide(4);});
        else b.setItems(new String[]{"查看状态","完成引导"},(d,w)->{if(w==0)status();else{finishGuide();host.exitMenus();}});
        b.setNegativeButton("返回",null).setNeutralButton("跳过引导",(d,w)->{finishGuide();host.exitMenus();});host.showDialog(b.create());
    }
    void finishGuide(){host.getSharedPreferences("onboarding",0).edit().putBoolean("done",true).apply();}
    void diagnostics(){host.showDialog(new TvDialog.Builder(host).setTitle("本地诊断")
        .setMessage("自动记录："+(Diagnostics.enabled(host)?"开启":"关闭")+"\n仅留电视本地，不上传。最多保留 7 天、5 MB。关闭不删除已有记录，可单独清除。\n\n突然断电或系统结束进程可能无法记录原因；无响应仅是线索，不等于已确认崩溃。")
        .setItems(new String[]{"一键检查","预览诊断报告","导出诊断报告","自动记录："+(Diagnostics.enabled(host)?"关闭":"开启"),"清除所有记录"},(d,w)->{
            if(w==0)status();else if(w==1)report(false);else if(w==2)report(true);else if(w==3){Diagnostics.setEnabled(host,!Diagnostics.enabled(host));diagnostics();}
            else host.showDialog(new TvDialog.Builder(host).setTitle("清除本地诊断？").setMessage("删除电视内部异常记录。已导出的文件需要在保存位置另行删除。")
                .setPositiveButton("清除",(x,y)->{Diagnostics.clear(host);host.toast("记录正在清除");}).setNegativeButton("返回",null).create());
        }).setNegativeButton("返回",null).create());}
    String reportText(){return reportHeader()+Diagnostics.recent(host);}
    String reportHeader(){return "Marco TV · Pilipala\n版本："+ProjectInfo.VERSION+"\n设备："+Build.MANUFACTURER+" / "+Build.MODEL+"\nAndroid："+Build.VERSION.RELEASE+"\n\n"+summary()
        +"\n\n事件说明：CRASH = 本应用异常；UI_UNRESPONSIVE_SUSPECTED = 前台疑似无响应；PREVIOUS_RUN_INTERRUPTED_UNKNOWN = 上次前台运行中断，原因未确定。\n不含网络名称、IP、设备序列号、应用清单、节目标题、图片或密码。\n\n近期事件\n";}
    private void report(boolean export){final int epoch=host.menuEpoch;final String header=reportHeader();host.loader.execute(()->{
        String report=header+Diagnostics.recent(host);host.handler.post(()->{if(epoch!=host.menuEpoch||host.isDestroyed())return;
            host.showDialog(new TvDialog.Builder(host).setTitle("诊断报告预览").setMessage(report)
                .setPositiveButton(export?"选择保存位置":"导出这份报告",(d,w)->{
                    try{File folder=new File(host.getCacheDir(),"exports");folder.mkdirs();File file=new File(folder,"MarcoTV-diagnostic-"+System.currentTimeMillis()+".txt");
                        try(Writer out=new OutputStreamWriter(new FileOutputStream(file),"UTF-8")){out.write(report);}host.files.export(file,"text/plain");
                    }catch(IOException e){host.toast("报告保存失败");}
                }).setNegativeButton("返回",null).create());
        });
    });}
}
