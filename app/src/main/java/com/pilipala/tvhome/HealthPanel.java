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
    HealthPanel(MainActivity h){
        host=h;
    }
    static String date(long stamp){
        return stamp==0?"尚无记录":DateFormat.getDateTimeInstance().format(new Date(stamp));
    }
    private int statusGeneration;
    private String statusDetail="";
    String summary(){
        return "检查时间："+date(System.currentTimeMillis())+"\n"+StatusSnapshot.local(host)
                +"\n播放授权："+(host.playback.authorized()?"已允许":"未允许")
                +"\n播放卡片："+(host.playback.enabled()?"已开启":"已关闭")+" / 可用会话 "+host.playback.availableCount()+" 个"
                +"\n可用存储："+(host.getFilesDir().getUsableSpace()/1024/1024)+" MB"
                +"\n\n状态检查不会修改配置。网络 ADB、实体 Home、待机、重启和断电恢复仍需逐项实测。";
    }
    void status(){
        final int generation=++statusGeneration,epoch=host.menuEpoch;
        final String local=summary();
        final String serviceLabel=HomeKeyService.connected?"已连接（已绑定）":HomeSetup.serviceEnabled(host)?"已允许未连接":"未连接";
        final String overview="默认桌面："+(BuildEdition.RESTRICTED?"受限版（无 HOME）":SystemIntegration.homeIsOurs(host)?"Marco TV":"其他桌面")
                +" · Home 服务"+serviceLabel
                +"\n系统设置授权："+(SystemIntegration.authorized(host)?"已授权":"未授权")+" · 本页仅查询";
        statusDetail=local+"\n\n正在读取端口与任务服务状态…";
        showStatus(overview+"\n任务服务：读取中…");
        host.loader.execute(()->{
            String extended=StatusSnapshot.extended();
            host.handler.post(()->{
                if(generation!=statusGeneration||epoch!=host.menuEpoch||host.isDestroyed())return;
                statusDetail=local+"\n\n"+extended;
                if(host.isPage("health_status"))showStatus(overview+"\n任务服务："+(extended.contains("任务服务：已连接")?"已连接":"未连接或无法读取"));
                else if(host.isPage("health_details"))details();
            });
        });
    }
    private void details(){
        host.showDialog(new TvDialog.Builder(host).setPageId("health_details").setTitle("详细状态")
                .setMessage(statusDetail).setPositiveButton("返回",null).create());
    }
    private void showStatus(String message){
        host.showDialog(new TvDialog.Builder(host).setPageId("health_status").setTitle("状态中心")
                .setOrder("health","details","refresh","system","playback","diagnostics","guide")
                .setMessage(message).setItems(new String[]{"查看详细状态","重新检查（不修改配置）","前往系统与启动","前往播放卡片设置","查看本地诊断","重新查看首次引导"},(d,w)->{
            if(w==0)details();
            else if(w==1){
                host.playback.refresh();
                status();
            }else if(w==2)host.navigator.systemPanel();
            else if(w==3)host.playback.settings();
            else if(w==4)diagnostics();
            else guide(0);
        }).setNegativeButton("返回",null).create());
    }
    static android.widget.LinearLayout qrInfoCard(MainActivity host,String qrUrl,String qrCaption,String qrDescription,String body){
        android.widget.LinearLayout box=new android.widget.LinearLayout(host);
        box.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        box.setGravity(android.view.Gravity.CENTER_VERTICAL);
        float density=host.getResources().getDisplayMetrics().density;
        int gap=Math.round(20*density);
        try{
            final io.nayuki.qrcodegen.QrCode code=io.nayuki.qrcodegen.QrCode.encodeText(qrUrl,io.nayuki.qrcodegen.QrCode.Ecc.LOW);
            android.widget.LinearLayout left=new android.widget.LinearLayout(host);
            left.setOrientation(android.widget.LinearLayout.VERTICAL);
            left.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            int side=Math.round(168*density);
            android.view.View image=new android.view.View(host){
                @Override protected void onDraw(android.graphics.Canvas canvas){
                    android.graphics.Paint p=new android.graphics.Paint();
                    float s=Math.min(getWidth(),getHeight()),cell=s/(code.size+8);
                    p.setColor(android.graphics.Color.WHITE);
                    canvas.drawRect(0,0,s,s,p);
                    p.setColor(android.graphics.Color.BLACK);
                    for(int y=0;y<code.size;y++)for(int x=0;x<code.size;x++)if(code.getModule(x,y))
                        canvas.drawRect((x+4)*cell,(y+4)*cell,(x+5)*cell,(y+5)*cell,p);
                }
            };
            image.setContentDescription(qrDescription);
            left.addView(image,new android.widget.LinearLayout.LayoutParams(side,side));
            android.widget.TextView cap=new android.widget.TextView(host);
            cap.setText(qrCaption);
            cap.setTextColor(0xFFB5C2D1);
            cap.setTextSize(12.5f);
            cap.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            cap.setPadding(0,Math.round(8*density),0,0);
            left.addView(cap,new android.widget.LinearLayout.LayoutParams(side,-2));
            android.widget.LinearLayout.LayoutParams leftLp=new android.widget.LinearLayout.LayoutParams(-2,-2);
            leftLp.rightMargin=gap;
            box.addView(left,leftLp);
        }catch(Exception ignored){}
        android.widget.TextView text=new android.widget.TextView(host);
        text.setTextColor(0xFFF0F4F8);
        text.setTextSize(15f);
        text.setLineSpacing(Math.round(4*density),1f);
        text.setText(body);
        box.addView(text,new android.widget.LinearLayout.LayoutParams(0,-2,1f));
        return box;
    }
    void markOfficialNoticeSeen(){
        host.getSharedPreferences("onboarding",0).edit().putBoolean("official_notice_seen",true).apply();
    }
    void officialNotice(final boolean offerGuideNext){
        android.widget.LinearLayout card=qrInfoCard(host,ProjectInfo.OFFICIAL_SITE_URL,
                "手机扫码访问 Marco 官网","Marco 官网二维码",ProjectInfo.firstLaunchNotice());
        TvDialog.Builder b=new TvDialog.Builder(host).centered()
                .setPageId("official_notice")
                .setTitle("欢迎使用 Marco TV · 官方渠道说明")
                .setView(card);
        if(offerGuideNext){
            b.setPositiveButton("我知道了，进入引导",(d,w)->{
                markOfficialNoticeSeen();
                guide(0);
            }).setNegativeButton("直接开始使用",(d,w)->{
                markOfficialNoticeSeen();
                finishGuide();
                host.exitMenus();
            });
        }else{
            b.setPositiveButton("我知道了",(d,w)->{
                markOfficialNoticeSeen();
                host.exitMenus();
            }).setNeutralButton("联系作者与交流群",(d,w)->{
                markOfficialNoticeSeen();
                host.navigator.contactAuthor();
            });
        }
        TvDialog dialog=b.create();
        dialog.onLeave(this::markOfficialNoticeSeen);
        host.showDialog(dialog);
    }
    void guide(int step){
        String[] titles={"先试用桌面","默认桌面与 Home","开机与调试恢复","正在播放与本地诊断","完成检查"};
        String[] messages={"方向键选择卡片，确定打开，长按编辑。菜单键进入设置；从设置首页按返回可回桌面。你可以跳过引导，以后从状态页重新打开。",
                "打开「桌面启用与检查」，按电视检测结果继续。系统支持时优先设为原生默认桌面；若系统锁定原厂桌面，无任务桥时可用「窗口回拉」兜底（会短暂闪过原厂桌面），启动任务桥后推荐「快速 Home ＋ 任务桥」以兼顾直接回桌与首次长按音量连调。",
                "完成设置后，先打开其他应用再按 Home 检查。方便时真正重启电视，确认自动进入桌面；关屏再唤醒不算重启。三项状态分别显示，未验证的项目不会标为完成。",
                "正在播放卡片需要可选通知使用权，仅使用媒体会话。本地诊断默认记录自身异常，不记录节目、密码和通知内容，默认不上传；可在问题反馈中预览后提交。",
                "可以查看当前状态、检查需要补齐的授权，再开始使用。备份与恢复可导出配置到 U 盘或电脑；权限不能随备份迁移。"};
        TvDialog.Builder b=new TvDialog.Builder(host).setPageId("guide:"+step).setTitle("首次使用 · "+(step+1)+" / 5 · "+titles[step])
                .setMessage(messages[step]);
        if(step==0)b.setItems(new String[]{"下一步"},(d,w)->guide(1));
        else if(step==1||step==2)b.setItems(new String[]{step==1?"桌面启用与检查":"打开相关设置","下一步"},(d,w)->{
            if(w==0){
                if(step==1)host.defaultHome.show();
                else host.navigator.systemPanel();
            }else guide(step+1);
        });
        else if(step==3)b.setItems(new String[]{"设置播放卡片","设置本地诊断","下一步"},(d,w)->{
            if(w==0)host.playback.settings();
            else if(w==1)diagnostics();
            else guide(4);
        });
        else b.setItems(new String[]{"查看状态","完成引导"},(d,w)->{
            if(w==0)status();
            else{
                finishGuide();
                host.exitMenus();
            }
        });
        b.setNegativeButton("返回",null).setNeutralButton("跳过引导",(d,w)->{
            finishGuide();
            host.exitMenus();
        });
        host.showDialog(b.create());
    }
    void finishGuide(){
        host.getSharedPreferences("onboarding",0).edit().putBoolean("done",true).apply();
    }
    void diagnostics(){
        host.showDialog(new TvDialog.Builder(host).setPageId("diagnostics").setTitle("本地诊断")
                .setOrder("diagnostics","status","preview","export","enabled","clear","feedback")
                .setMessage("自动记录："+(Diagnostics.enabled(host)?"已开启":"已关闭")+"\n本地最多保留 7 天、5 MB。关闭不删除已有记录。报告仅在问题反馈中主动提交或单独同意自动提交时发送。\n\n断电或系统结束进程可能无法记录原因；无响应仅是线索。")
                .setItems(new String[]{"状态中心","预览诊断报告","导出诊断报告",Diagnostics.enabled(host)?"关闭自动记录":"开启自动记录","清除所有记录","问题反馈 / 扫码提交"},(d,w)->{
            if(w==0)status();
            else if(w==1)report(false);
            else if(w==2)report(true);
            else if(w==3){
                Diagnostics.setEnabled(host,!Diagnostics.enabled(host));
                diagnostics();
            }
            else if(w==5)new FeedbackPanel(host).show();
            else host.showDialog(new TvDialog.Builder(host).setTitle("清除本地诊断？").setMessage("删除电视内部异常记录。已导出的文件需要在保存位置另行删除。")
                    .setPositiveButton("清除",(x,y)->{
                Diagnostics.clear(host);
                host.toast("记录正在清除");
            }).setNegativeButton("返回",null).create());
        }).setNegativeButton("返回",null).create());
    }
    String reportText(){
        return reportHeader()+Diagnostics.recent(host);
    }
    String reportHeader(){
        return "Marco TV · Pilipala\n版本："+ProjectInfo.VERSION+"\n设备："+Build.MANUFACTURER+" / "+Build.MODEL+"\nAndroid："+Build.VERSION.RELEASE+" / API "+Build.VERSION.SDK_INT+"\n固件："+Build.DISPLAY+"\n处理器架构："+java.util.Arrays.toString(Build.SUPPORTED_ABIS)+"\n\n"+summary()
                +"\n\n事件说明：CRASH = 本应用异常；UI_UNRESPONSIVE_SUSPECTED = 前台疑似无响应；PREVIOUS_RUN_INTERRUPTED_UNKNOWN = 上次前台运行中断，原因未确定。\n不含网络名称、IP、设备序列号、应用清单、节目标题、图片或密码。\n\n近期事件\n";
    }
    private void report(boolean export){
        final int epoch=host.menuEpoch;
        final String header=reportHeader();
        host.loader.execute(()->{
            String report=header+Diagnostics.recent(host);
            host.handler.post(()->{
                if(epoch!=host.menuEpoch||host.isDestroyed())return;
                host.showDialog(new TvDialog.Builder(host).setPageId("diagnostic_report").setTitle("诊断报告预览")
                        .setMessage(report)
                        .setPositiveButton(export?"选择保存位置":"导出这份报告",(d,w)->{
                    try{
                        File folder=new File(host.getCacheDir(),"exports");
                        folder.mkdirs();
                        File file=new File(folder,"MarcoTV-diagnostic-"+System.currentTimeMillis()+".txt");
                        try(Writer out=new OutputStreamWriter(new FileOutputStream(file),"UTF-8")){
                            out.write(report);
                        }
                        host.files.export(file,"text/plain");
                    }catch(IOException e){
                        host.toast("报告保存失败");
                    }
                }).setNegativeButton("返回",null).create());
            });
        });
    }
}
