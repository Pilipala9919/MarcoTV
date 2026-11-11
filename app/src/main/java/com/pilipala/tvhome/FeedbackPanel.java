// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.graphics.*;
import android.view.View;
import android.widget.*;
import org.json.JSONObject;

/** One report is held across preview, send and QR; the user approves actual contents. */
final class FeedbackPanel {
    final MainActivity host;
    JSONObject report;
    FeedbackPanel(MainActivity host){this.host=host;}
    void show(){
        final int epoch=host.menuEpoch;
        host.showDialog(new TvDialog.Builder(host).setPageId("feedback_loading").setTitle("问题反馈")
                .setMessage("正在生成脱敏报告，仅在本机处理…").setNegativeButton("返回",null).create());
        host.loader.execute(()->{
            try{
                JSONObject value=FeedbackManager.snapshot(host,"manual");
                host.handler.post(()->{if(host.foreground&&host.menuEpoch==epoch&&host.isPage("feedback_loading")){
                    report=value;FeedbackManager.prefs(host).edit().putString("prepared_id",value.optString("id")).apply();page();
                }});
            }catch(Exception e){host.handler.post(()->{if(host.foreground&&host.menuEpoch==epoch&&host.isPage("feedback_loading"))host.toast("报告生成失败，可继续导出本地诊断。");});}
        });
    }
    void page(){
        TvDialog dialog=new TvDialog.Builder(host).setPageId("feedback").setTitle("问题反馈")
                .setMessage("提交前可以预览，也可扫码用手机确认。\n自动提交："+(FeedbackManager.automatic(host)?"已开启":"已关闭")+"\n"+FeedbackManager.status(host))
                .setItems(new String[]{"预览本次报告","确认提交这份报告","扫码用手机提交","重试上次未提交的报告","查询最近反馈进度",FeedbackManager.automatic(host)?"关闭自动提交":"开启可选自动提交","清除本机待提交报告"},(d,w)->{
                    if(w==0)host.showDialog(new TvDialog.Builder(host).setPageId("feedback_preview").setTitle("报告预览")
                            .setMessage(report.toString()).setNegativeButton("返回",null).create());
                    else if(w==1)consent(false);
                    else if(w==2)qr();
                    else if(w==3)retryConsent();
                    else if(w==4)status();
                    else if(w==6)host.showDialog(new TvDialog.Builder(host).setTitle("清除待提交报告？")
                            .setMessage("仅清除本机重试文件，已被服务器收到的反馈不会撤回。")
                            .setPositiveButton("清除",(x,y)->{FeedbackManager.clearPending(host);page();}).setNegativeButton("取消",null).create());
                    else if(FeedbackManager.automatic(host)){FeedbackManager.setAutomatic(host,false);page();}
                    else consent(true);
                }).setNegativeButton("返回",null).create();
        if(host.isPage("feedback_loading"))host.dialogs.replace(host.dialogs.active(),dialog);
        else host.showDialog(dialog);
    }
    String privacy(){return "提交给 Pilipala 的 Marco TV 反馈服务（marcotv.wlj9919.top，由 Cloudflare 托管）。\n包含型号、固件、版本、故障代码与本应用代码位置；不含账号、密码、Wi-Fi、节目、截图、序列号或应用列表。Cloudflare 会处理连接 IP 等网络信息。";}
    void consent(boolean automatic){
        host.showDialog(new TvDialog.Builder(host).centered().setPageId("feedback_consent").setTitle(automatic?"允许自动提交异常？":"提交这份报告？")
                .setMessage(privacy()+(automatic?"\n仅在开启本地诊断且发现异常时尝试，每24小时最多一次；相同报告不重复提交，可随时关闭。关闭后正在发送的请求可能已经到达。":"\n仅提交本次预览的报告，失败时保留在本机供重试。"))
                .setPositiveButton(automatic?"同意并开启":"同意并提交",(d,w)->{
                    if(automatic){FeedbackManager.setAutomatic(host,true);page();}
                    else send(false);
                }).setNegativeButton("取消",null).create());
    }
    void retryConsent(){
        host.showDialog(new TvDialog.Builder(host).setPageId("feedback_retry").setTitle("重试待提交报告？")
                .setMessage(privacy()+"\n发送上次失败时保留的脱敏报告，沿用原编号避免重复。")
                .setPositiveButton("同意并重试",(d,w)->send(true)).setNegativeButton("取消",null).create());
    }
    void send(boolean retry){
        final int epoch=host.menuEpoch;
        host.showDialog(new TvDialog.Builder(host).setPageId("feedback_sending").setTitle("正在提交")
                .setMessage("正在等待接收端回执…\n离开页面不会撤回已发送的报告。").setNegativeButton("返回",null).create());
        FeedbackManager.Result result=text->{
            if(host.foreground&&host.menuEpoch==epoch&&host.isPage("feedback_sending"))
                host.showDialog(new TvDialog.Builder(host).setPageId("feedback_result").setTitle("提交结果").setMessage(text).setNegativeButton("返回",null).create());
        };
        if(retry)FeedbackManager.retry(host,result);else FeedbackManager.send(host,report,result);
    }
    void status(){
        final int epoch=host.menuEpoch;
        host.showDialog(new TvDialog.Builder(host).setPageId("feedback_status").setTitle("反馈进度").setMessage("正在查询…").setNegativeButton("返回",null).create());
        FeedbackManager.checkStatus(host,text->{if(host.foreground&&host.menuEpoch==epoch&&host.isPage("feedback_status"))
            host.showDialog(new TvDialog.Builder(host).setPageId("feedback_status").setTitle("反馈进度").setMessage(text).setNegativeButton("返回",null).create());});
    }
    void qr(){
        try{
            JSONObject qrReport=FeedbackManager.qrSnapshot(report);
            FeedbackManager.prefs(host).edit().putString("query_id",qrReport.optString("id")).apply();
            final io.nayuki.qrcodegen.QrCode code=io.nayuki.qrcodegen.QrCode.encodeText(FeedbackManager.link(qrReport),io.nayuki.qrcodegen.QrCode.Ecc.LOW);
            LinearLayout box=new LinearLayout(host);box.setGravity(android.view.Gravity.CENTER_VERTICAL);
            int unit=Math.round(20*host.getResources().getDisplayMetrics().density);
            View image=new View(host){protected void onDraw(Canvas canvas){
                Paint p=new Paint();float side=Math.min(getWidth(),getHeight()),cell=side/(code.size+8);
                p.setColor(Color.WHITE);canvas.drawRect(0,0,side,side,p);p.setColor(Color.BLACK);
                for(int y=0;y<code.size;y++)for(int x=0;x<code.size;x++)if(code.getModule(x,y))canvas.drawRect((x+4)*cell,(y+4)*cell,(x+5)*cell,(y+5)*cell,p);
            }};
            image.setContentDescription("手机扫描预览并提交反馈");box.addView(image,new LinearLayout.LayoutParams(unit*9,unit*9));
            TextView text=new TextView(host);text.setTextColor(Color.WHITE);text.setTextSize(16);text.setPadding(unit,0,0,0);
            text.setText("手机扫一扫\n先预览，再确认提交\n\n二维码含脱敏报告\n请勿公开转发\n网页显示成功才算收到");box.addView(text,new LinearLayout.LayoutParams(0,-2,1));
            host.showDialog(new TvDialog.Builder(host).centered().setPageId("feedback_qr").setTitle("用手机提交问题").setView(box).setNegativeButton("返回",null).create());
        }catch(Exception e){host.toast("二维码生成失败，请使用电视提交或导出报告。");}
    }
    static void maybeOffer(MainActivity host){
        if(!Diagnostics.enabled(host)||FeedbackManager.automatic(host))return;
        final int epoch=host.menuEpoch;
        host.loader.execute(()->{
            java.util.List<String> codes=FeedbackPolicy.codes(Diagnostics.recent(host));
            if(!codes.contains("CRASH")&&!codes.contains("UI_UNRESPONSIVE_SUSPECTED"))return;
            String fingerprint=codes.toString();
            android.content.SharedPreferences p=FeedbackManager.prefs(host);
            if(fingerprint.equals(p.getString("offered","")))return;
            host.handler.postDelayed(()->{
                if(!host.foreground||host.menuEpoch!=epoch||host.dialogs.open()||host.recents.visible)return;
                p.edit().putString("offered",fingerprint).apply();
                host.showDialog(new TvDialog.Builder(host).setPageId("feedback_offer").setTitle("发现了一条异常线索")
                        .setMessage("Marco TV 记录到自身异常或疑似卡住。可预览脱敏报告后提交给作者，不用连接电脑。现在还没有上传。")
                        .setPositiveButton("查看问题反馈",(d,w)->new FeedbackPanel(host).show()).setNegativeButton("稍后",null).create());
            },3000);
        });
    }
}
