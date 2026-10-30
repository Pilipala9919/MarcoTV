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
    private int pendingEpoch;
    private LocalHomeAuthorizer authorizer;
    DefaultHomePanel(MainActivity h){
        host=h;
    }
    void show(){
        boolean own=SystemIntegration.homeIsOurs(host);
        host.showDialog(new TvDialog.Builder(host).setPageId("default_home").setTitle(own?"已设为默认桌面":"让 Home 键回到 Marco TV")
                .setMessage(HomeSetup.summary(host))
                .setItems(new String[]{own?"重新选择默认桌面":"设为默认桌面","测试 Home 返回","电视没有这个选项怎么办？",
                        "启用 Home 兼容","电视本机增强设置（试验）","确认重启后自动进入","暂停自动恢复"},(d,w)->{
            if(w==0)request();
            else if(w==1){HomeSetup.armHomeTest(host);host.exitMenus();host.toast("请打开其他应用，再按 Home 返回；90 秒内记录结果。");}
            else if(w==2)help();
            else if(w==3)compatibility();
            else if(w==4)localAuthorization();
            else if(w==5){HomeSetup.confirmBoot(host);show();}
            else {HomeSetup.pause(host,"你已暂停自动恢复。");SystemIntegration.startRecovery(host);show();}
        }).setNegativeButton("返回",null).create());
    }
    void request(){
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
                if(host.foreground&&host.menuEpoch==pendingEpoch)show();
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
                        compatibilityPending=true;pending=true;pendingEpoch=host.menuEpoch;
                        host.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    }catch(RuntimeException e){
                        compatibilityPending=false;pending=false;
                        HomeSetup.finishCompatibilityConsent(host);help();
                    }
                }).setNegativeButton("返回",null).create());
    }
    void localAuthorization(){
        host.showDialog(new TvDialog.Builder(host).setPageId("home_local_consent").setTitle("电视本机增强设置（试验）")
                .setMessage("先在电视系统中开启普通网络调试，再点击继续并确认系统授权弹窗。\n仅连接这台电视自身，尝试授权系统设置并选择 Marco TV，不安装或卸载其他应用，不启用持续 ADB 恢复。\n配对码无线调试和非标准端口暂不支持；失败可使用安卓手机或电脑助手。")
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
        host.showDialog(new TvDialog.Builder(host).setPageId("default_home_help").setTitle("系统没有开放桌面选择")
                .setMessage("有些电视把 Home 固定到原厂桌面，普通应用无法自行改掉。\n\n先在电视设置中找「默认应用／桌面」。如果没有，可使用安装助手完成系统授权，或查看 Home 兼容设置。无需输入激活码，也不需要付费。")
                .setItems(new String[]{"打开电视设置","安装助手说明","启用 Home 兼容","电视本机增强设置（试验）"},(d,w)->{
            if(w==0)try{
                pending=true;pendingEpoch=host.menuEpoch;
                host.startActivity(new Intent(Settings.ACTION_SETTINGS));
            }catch(RuntimeException ex){
                pending=false;
                host.toast("电视未提供该入口");
            }else if(w==1)host.showDialog(new TvDialog.Builder(host).setPageId("home_assistant").setTitle("跟着安装向导操作")
                    .setMessage("用手机或电脑浏览器打开：\nmarcotv.wlj9919.top/install/\n\n按页面选择设备和安装路线。系统授权需电视允许调试；具体支持范围以向导说明为准。")
                    .setNegativeButton("返回",null).create());
            else if(w==2)compatibility();
            else localAuthorization();
        }).setNegativeButton("返回",null).create());
    }
}
