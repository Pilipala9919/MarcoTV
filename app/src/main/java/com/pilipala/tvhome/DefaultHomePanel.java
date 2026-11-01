// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;
import android.content.*;
import android.content.pm.*;
import android.os.Build;
import android.provider.Settings;

/** Standard HOME consent first; advanced recovery remains optional. */
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
                .setMessage(HomeSetup.summary(host))
                .setItems(new String[]{route==HomeSetupPolicy.Route.PAUSED?"继续设置":ready?"检查使用效果":"开始设置","更多设置方式"},(d,w)->{
            if(w==0)start();else help();
        }).setNegativeButton("稍后",null).create());
    }
    void start(){
        if(HomeSetup.paused(host))HomeSetup.begin(host);
        switch(HomeSetup.route(host)){
            case NATIVE_READY:
                if(!HomeSetup.prefs(host).getBoolean("setup_requested",false))HomeSetup.begin(host);
                HomeSetup.acceptNative(host);verification();break;
            case COMPAT_READY:
                if(!HomeSetup.prefs(host).getBoolean("setup_requested",false))HomeSetup.begin(host);
                verification();break;
            case SYSTEM_PICKER:request();break;
            case ACCESSIBILITY:compatibility();break;
            default:help();break;
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
        host.showDialog(new TvDialog.Builder(host).setPageId("home_local_consent").setTitle("电视本机增强设置（试验）")
                .setMessage("先在电视系统中开启普通网络调试，再点击继续并确认系统授权弹窗。\n仅连接这台电视自身，尝试授权系统设置"+(BuildEdition.RESTRICTED?"，随后需启用 Home 兼容。本版不申请系统默认桌面身份。":"并选择 Marco TV。")+"\n不安装或卸载其他应用，不启用持续 ADB 恢复。配对码无线调试和非标准端口暂不支持。")
                .setPositiveButton("继续并授权",(d,w)->{
                    HomeSetup.begin(host);
                    TvDialog progress=new TvDialog.Builder(host).setPageId("home_local_authorize").setTitle("等待本机授权")
                            .setMessage("请用遥控器确认电视弹出的调试授权。没有弹窗或长时间无响应时可返回取消。")
                            .setNegativeButton("取消",null).create();
                    destroy();authorizer=new LocalHomeAuthorizer(host);
                    final LocalHomeAuthorizer active=authorizer;
                    progress.onLeave(active::cancel);
                    host.showDialog(progress);
                    active.start(result->{
                        if(authorizer!=active||!host.foreground||!host.isPage("home_local_authorize"))return;
                        host.showDialog(new TvDialog.Builder(host).setPageId("home_local_result").setTitle("本机设置结果")
                                .setMessage(result+"\n\n"+HomeSetup.summary(host))
                                .setPositiveButton("查看启用状态",(dialog,button)->show()).setNegativeButton("返回",null).create());
                    });
                }).setNegativeButton("返回",null).create());
    }
    void destroy(){if(authorizer!=null){authorizer.cancel();authorizer=null;}}
    void help(){
        host.showDialog(new TvDialog.Builder(host).setPageId("default_home_help").setTitle("更多设置方式")
                .setMessage(BuildEdition.RESTRICTED?"本版为受限固件移除了系统桌面声明。请尝试 Home 兼容；开机和唤醒效果仍需在电视上检查。":"优先选择系统默认桌面；电视没有提供这个选项时，可以尝试 Home 兼容。\n不确定如何操作？打开手机向导，按步骤查看。")
                .setItems(new String[]{"打开电视系统设置","用手机查看设置向导","启用 Home 兼容","电视本机增强设置（试验）","暂停自动恢复"},(d,w)->{
            if(w==0)try{
                pending=true;returnToHelp=true;pendingEpoch=host.menuEpoch;
                host.startActivity(new Intent(Settings.ACTION_SETTINGS));
            }catch(RuntimeException ex){
                pending=false;
                host.toast("电视未提供该入口");
            }else if(w==1)assistant();
            else if(w==2)compatibility();
            else if(w==3)localAuthorization();
            else pause();
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
