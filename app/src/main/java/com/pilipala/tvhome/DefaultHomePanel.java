// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;
import android.content.*;
import android.content.pm.*;
import android.os.Build;
import android.provider.Settings;

/** One entry for capability-based setup; system pickers remain available without ADB. */
final class DefaultHomePanel {
    final MainActivity host;
    boolean pending;
    boolean compatibilityPending;
    private boolean returnToHelp;
    private int pendingEpoch;
    private LocalHomeAuthorizer authorizer;
    DefaultHomePanel(MainActivity h){
        host=h;
    }
    void show(){
        HomeSetupPolicy.Route route=HomeSetup.route(host);
        boolean ready=route==HomeSetupPolicy.Route.NATIVE_READY||route==HomeSetupPolicy.Route.COMPAT_READY;
        host.showDialog(new TvDialog.Builder(host).setPageId("default_home").setTitle("桌面启用")
                .setMessage(deviceSummary()+"\n\n"+HomeSetup.summary(host))
                .setItems(new String[]{route==HomeSetupPolicy.Route.PAUSED?"继续一键设置":ready?"检查使用效果":"一键启用 Marco TV","更多设置方式"},(d,w)->{
            if(w==0)start();else help();
        }).setNegativeButton("稍后",null).create());
    }
    void start(){
        switch(HomeSetup.route(host)){
            case NATIVE_READY:
                if(!HomeSetup.prefs(host).getBoolean("setup_requested",false))HomeSetup.begin(host);
                HomeSetup.acceptNative(host);verification();break;
            case COMPAT_READY:
                if(!HomeSetup.prefs(host).getBoolean("setup_requested",false))HomeSetup.begin(host);
                verification();break;
            default:localAuthorization();break;
        }
    }
    void verification(){
        host.showDialog(new TvDialog.Builder(host).setPageId("home_verification").setTitle("检查使用效果")
                .setMessage(HomeSetup.summary(host)+"\n\n先检查 Home，再在方便时重启电视。开关屏幕不算重启。")
                .setItems(new String[]{"检查 Home 返回","我已重启，确认开机效果","刷新当前状态"},(d,w)->{
                    if(w==0){HomeSetup.armHomeTest(host);host.exitMenus();host.toast("请打开另一个应用，再按一次 Home；90 秒内返回可记录结果。");}
                    else if(w==1){HomeSetup.confirmBoot(host);if(!HomeSetup.prefs(host).contains("setup_boot_confirmed"))host.toast("还没有记录到实际重启返回；方便时重启电视后再来确认。");verification();}
                    else verification();
                }).setNegativeButton("返回",null).create());
    }
    void request(){
        if(BuildEdition.RESTRICTED){compatibility();return;}
        returnToHelp=false;
        pendingEpoch=host.menuEpoch;
        HomeSetup.begin(host);
        if(Build.VERSION.SDK_INT>=29)try{
            Object roles=host.getSystemService("role");
            if(roles!=null){
                Class<?> type=Class.forName("android.app.role.RoleManager");
                if((Boolean)type.getMethod("isRoleAvailable",String.class).invoke(roles,"android.app.role.HOME")){
                    Intent request=(Intent)type.getMethod("createRequestRoleIntent",String.class).invoke(roles,"android.app.role.HOME");
                    pending=true;
                    host.startActivityForResult(request,812);
                    return;
                }
            }
        }catch(Exception ignored){
            pending=false;
        }
        try{
            Intent intent=new Intent(Settings.ACTION_HOME_SETTINGS);
            ResolveInfo info=host.getPackageManager().resolveActivity(intent,0);
            if(info!=null&&info.activityInfo!=null&&!info.activityInfo.name.endsWith("EmptyStubActivity")){
                pending=true;
                host.startActivity(intent);
                return;
            }
        }catch(RuntimeException ignored){
            pending=false;
        }
        help();
    }
    void resumed(){
        if(pending){
            pending=false;
            if(compatibilityPending){HomeSetup.finishCompatibilityConsent(host);compatibilityPending=false;}
            HomeSetup.reconcile(host);
            host.handler.post(()->{
                if(host.foreground&&host.menuEpoch==pendingEpoch){if(returnToHelp)help();else show();}
            });
        }
    }
    void compatibility(){
        host.showDialog(new TvDialog.Builder(host).setPageId("home_compat_consent").setTitle("启用 Home 兼容")
                .setMessage("用于系统没有开放默认桌面选择的电视。此方式不等于系统默认桌面，开机和唤醒需要实测。\n只管理 Marco TV 自身服务，保留其他辅助服务。")
                .setPositiveButton("继续",(d,w)->{
                    HomeSetup.requestCompatibility(host);
                    if(SystemIntegration.authorized(host)){
                        SystemIntegration.recover(host);
                        HomeSetup.finishCompatibilityConsent(host);
                        SystemIntegration.startRecovery(host);
                        show();
                    }else try{
                        compatibilityPending=true;pending=true;returnToHelp=false;pendingEpoch=host.menuEpoch;
                        host.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    }catch(RuntimeException e){
                        compatibilityPending=false;pending=false;
                        HomeSetup.finishCompatibilityConsent(host);help();
                    }
                }).setNegativeButton("返回",null).create());
    }
    void localAuthorization(){
        host.showDialog(new TvDialog.Builder(host).setPageId("home_local_consent").setTitle("一键启用 Marco TV")
                .setMessage(deviceSummary()+"\n\n"+(BuildEdition.RESTRICTED
                        ?"本版将自动配置 Home 兼容，不申请系统默认桌面身份。"
                        :"优先设置系统默认桌面；电视不支持时，自动尝试 Home 兼容。")
                        +"\n需要时在电视开启网络调试，并在系统弹窗选择“允许连接”。已授权的电视会继续自动设置。"
                        +"\n保留原厂桌面，可恢复设置。完成后还需检查 Home 和实际重启效果。")
                .setPositiveButton("开始自动设置",(d,w)->runAutomatic(false))
                .setNeutralButton("其他设置方式",(d,w)->help())
                .setNegativeButton("返回",null).create());
    }
    String deviceSummary(){
        return "识别电视  ·  "+HomeShellSetup.brand(Build.MANUFACTURER,Build.BRAND)+" / "+Build.MODEL
                +"\n系统  ·  Android "+Build.VERSION.RELEASE+" / "+Build.DISPLAY;
    }
    private void runAutomatic(boolean restore){
        TvDialog progress=new TvDialog.Builder(host).setPageId("home_local_authorize")
                .setTitle(restore?"正在恢复桌面设置":"正在自动设置")
                .setMessage("正在检查本机授权与系统能力。出现调试授权弹窗时，请用遥控器选择允许。\n最多等待 90 秒，可以返回取消；已完成的步骤可能保留。")
                .setNegativeButton("取消",null).create();
        destroy();authorizer=new LocalHomeAuthorizer(host);
        final LocalHomeAuthorizer active=authorizer;
        progress.onLeave(active::cancel);
        host.showDialog(progress);
        LocalHomeAuthorizer.Result callback=result->{
            if(authorizer!=active||!host.foreground||!host.isPage("home_local_authorize"))return;
            host.dialogs.replace(progress,new TvDialog.Builder(host).setPageId("home_local_result").setTitle(restore?"恢复结果":"自动设置结果")
                    .setMessage(result+"\n\n"+HomeSetup.summary(host))
                    .setItems(new String[]{"检查使用效果","其他设置方式","恢复设置前的桌面"},(d,w)->{
                        if(w==0)verification();else if(w==1)help();else restore();
                    }).setNegativeButton("返回",null).create());
        };
        if(restore)active.restore(callback);else active.start(callback);
    }
    void restore(){
        if(!HomeSetup.prefs(host).getBoolean(HomeSetupSnapshot.PREFIX+"saved",false)){
            host.toast("没有一键设置的恢复记录，可使用系统桌面选择。");return;
        }
        host.showDialog(new TvDialog.Builder(host).setPageId("home_auto_restore").setTitle("恢复设置前的桌面？")
                .setMessage("尝试切回一键设置前记录的桌面，并撤回本次启用的 Home 兼容设置。保留其他辅助服务、调试授权与系统设置权限。\n电视仍可能要求确认调试授权；没有可靠记录时会提示手动选择。")
                .setPositiveButton("恢复",(d,w)->runAutomatic(true)).setNegativeButton("取消",null).create());
    }
    void destroy(){if(authorizer!=null){authorizer.cancel();authorizer=null;}}
    void help(){
        host.showDialog(new TvDialog.Builder(host).setPageId("default_home_help").setTitle("更多设置方式")
                .setMessage((BuildEdition.RESTRICTED?"本版使用 Home 兼容；开机和唤醒效果仍需在电视上检查。":"不想使用调试时，可直接打开系统默认桌面选择，或在辅助功能中启用 Home 兼容。")
                        +"\n自动设置目前支持本机普通网络调试（5555 端口），暂不支持配对码。不会开启持续调试恢复；未实测机型按实际系统能力尝试。")
                .setItems(new String[]{"打开电视系统设置","用手机查看设置向导","启用 Home 兼容","一键自动设置","暂停自动恢复","系统默认桌面选择（无需调试）","打开开发者选项","恢复设置前的桌面"},(d,w)->{
            if(w==0)try{
                pending=true;returnToHelp=true;pendingEpoch=host.menuEpoch;
                host.startActivity(new Intent(Settings.ACTION_SETTINGS));
            }catch(RuntimeException ex){
                pending=false;
                host.toast("电视未提供该入口");
            }else if(w==1)assistant();
            else if(w==2)compatibility();
            else if(w==3)localAuthorization();
            else if(w==4)pause();
            else if(w==5)request();
            else if(w==6)try{
                host.startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            }catch(RuntimeException e){host.toast("电视未提供直接入口，请从系统设置中打开开发者选项。");}
            else restore();
        }).setNegativeButton("返回",null).create());
    }
    void assistant(){
        android.widget.LinearLayout box=new android.widget.LinearLayout(host);
        box.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int gap=Math.round(20*host.getResources().getDisplayMetrics().density);
        box.setPadding(0,gap/2,0,gap/2);
        final io.nayuki.qrcodegen.QrCode qr=io.nayuki.qrcodegen.QrCode.encodeText("https://marcotv.wlj9919.top/install/#home",io.nayuki.qrcodegen.QrCode.Ecc.MEDIUM);
        android.view.View code=new android.view.View(host){
            protected void onDraw(android.graphics.Canvas canvas){
                android.graphics.Paint p=new android.graphics.Paint();
                float side=Math.min(getWidth(),getHeight()),unit=side/(qr.size+8),left=(getWidth()-side)/2,top=(getHeight()-side)/2;
                p.setColor(android.graphics.Color.WHITE);canvas.drawRect(left,top,left+side,top+side,p);
                p.setColor(android.graphics.Color.BLACK);
                for(int y=0;y<qr.size;y++)for(int x=0;x<qr.size;x++)if(qr.getModule(x,y))canvas.drawRect(left+(x+4)*unit,top+(y+4)*unit,left+(x+5)*unit,top+(y+5)*unit,p);
            }
        };
        code.setContentDescription("手机扫描打开桌面设置向导");
        box.addView(code,new android.widget.LinearLayout.LayoutParams(gap*8,gap*8));
        android.widget.TextView guide=new android.widget.TextView(host);
        guide.setTextColor(android.graphics.Color.WHITE);guide.setTextSize(16);guide.setPadding(gap,0,0,0);
        guide.setText("手机相机扫一扫\n选择「已经安装，设置默认桌面」\n\n也可在浏览器输入\nmarcotv.wlj9919.top/install/#home\n\n网页提供步骤，系统确认需在电视上完成。");
        box.addView(guide,new android.widget.LinearLayout.LayoutParams(0,-2,1));
        host.showDialog(new TvDialog.Builder(host).setPageId("home_assistant").setTitle("用手机查看设置向导")
                .setView(box)
                .setNegativeButton("返回",null).create());
    }
    void pause(){
        host.showDialog(new TvDialog.Builder(host).setPageId("home_pause_consent").setTitle("暂停自动恢复？")
                .setMessage("将暂停 Home 兼容与开机恢复。系统已经选择的默认桌面仍会保留；若想换回原桌面，请在系统设置中另行选择。")
                .setPositiveButton("暂停恢复",(d,w)->{HomeSetup.pause(host,"你已暂停自动恢复。");SystemIntegration.startRecovery(host);show();})
                .setNegativeButton("返回",null).create());
    }
}
