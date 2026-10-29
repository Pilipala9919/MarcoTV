// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;
import android.content.*;
import android.content.pm.*;
import android.os.Build;
import android.provider.Settings;

/** Standard HOME consent first; advanced recovery remains optional. */
final class DefaultHomePanel {
    final MainActivity host;boolean pending;
    DefaultHomePanel(MainActivity h){host=h;}
    void show(){boolean own=SystemIntegration.homeIsOurs(host);
        host.showDialog(new TvDialog.Builder(host).setPageId("default_home").setTitle(own?"已设为默认桌面":"让 Home 键回到 Marco TV")
            .setMessage(own?"系统当前已选择 Marco TV。请按一次遥控器 Home，确认能直接回来。":"点击下方按钮，在系统页面选择「Marco TV」，再选择「始终」或确认即可。支持此功能的电视不需要电脑授权。")
            .setItems(new String[]{own?"重新选择默认桌面":"设为默认桌面","检查是否设置成功","电视没有这个选项怎么办？"},(d,w)->{if(w==0)request();else if(w==1)show();else help();}).setNegativeButton("返回",null).create());
    }
    void request(){
        SystemIntegration.rememberHome(host);SystemIntegration.enableHome(host,true);
        if(Build.VERSION.SDK_INT>=29)try{Object roles=host.getSystemService("role");if(roles!=null){Class<?> type=Class.forName("android.app.role.RoleManager");if((Boolean)type.getMethod("isRoleAvailable",String.class).invoke(roles,"android.app.role.HOME")){Intent request=(Intent)type.getMethod("createRequestRoleIntent",String.class).invoke(roles,"android.app.role.HOME");pending=true;host.startActivityForResult(request,812);return;}}}catch(Exception ignored){pending=false;}
        try{Intent intent=new Intent(Settings.ACTION_HOME_SETTINGS);ResolveInfo info=host.getPackageManager().resolveActivity(intent,0);if(info!=null&&info.activityInfo!=null&&!info.activityInfo.name.endsWith("EmptyStubActivity")){pending=true;host.startActivity(intent);return;}}catch(RuntimeException ignored){pending=false;}
        help();
    }
    void resumed(){if(pending){pending=false;host.handler.post(()->{if(host.foreground)show();});}}
    void help(){host.showDialog(new TvDialog.Builder(host).setPageId("default_home_help").setTitle("系统没有开放桌面选择")
        .setMessage("有些电视把 Home 固定到原厂桌面，普通应用无法自行改掉。\n\n先在电视设置中找「默认应用／桌面」。如果没有，可使用安装助手完成系统授权，或查看 Home 兼容设置。无需输入激活码，也不需要付费。")
        .setItems(new String[]{"打开电视设置","安装助手说明","查看 Home 兼容设置"},(d,w)->{if(w==0)try{pending=true;host.startActivity(new Intent(Settings.ACTION_SETTINGS));}catch(RuntimeException ex){pending=false;host.toast("电视未提供该入口");}else if(w==1)host.showDialog(new TvDialog.Builder(host).setPageId("home_assistant").setTitle("跟着安装向导操作").setMessage("用手机或电脑浏览器打开：\nmarcotv.wlj9919.top/install/\n\n按页面选择设备和安装路线。系统授权需电视允许调试；具体支持范围以向导说明为准。").setNegativeButton("返回",null).create());else SystemIntegration.show(host,host::showDialog);}).setNegativeButton("返回",null).create());}
}
