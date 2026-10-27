// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.*;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import java.io.File;

/** User-controlled updates. Background checks never open menus or the installer. */
final class UpdatePanel {
    static final int INSTALL=1401;
    final MainActivity host;final UpdateManager updates;private TvDialog page;private boolean resumed;
    private final Runnable periodic=new Runnable(){public void run(){if(!resumed)return;updates.automatic();host.handler.postDelayed(this,30*60*1000L);}};
    UpdatePanel(MainActivity host){this.host=host;updates=UpdateManager.get(host);}
    void resume(){resumed=true;updates.listen(this::refresh);host.handler.removeCallbacks(periodic);host.handler.postDelayed(periodic,4000);refresh();}
    void pause(){resumed=false;updates.listen(null);host.handler.removeCallbacks(periodic);}
    private String summary(){return "当前 "+ProjectInfo.VERSION+" · "+(updates.channel().equals("stable")?"稳定版渠道":"体验版渠道")
        +"\n"+updates.message+(updates.percent>=0?"（"+updates.percent+"%）":"")
        +"\n更新方式："+modeName(updates.mode());}
    private static String modeName(String mode){return "off".equals(mode)?"仅手动检查":"download".equals(mode)?"自动下载后提醒":"自动检查并提醒";}
    private void refresh(){if(!resumed)return;if(host.isPage("updates")&&page!=null)page.updateMessage(summary());
        else if(host.canShowUpdateNotice()&&updates.consumeNotice())host.toast("发现 Marco TV 新版，可到维护与帮助 → 软件更新查看");}
    void show(){page=new TvDialog.Builder(host).setPageId("updates").setTitle("软件更新").setMessage(summary())
        .setOrder("updates","check","download","install","notes","mode","channel","cancel","help")
        .setItems(new String[]{"立即检查更新","下载新版","安装已下载版本","查看更新内容","更新方式","更新渠道","取消当前下载／检查","更新与任务服务说明"},(d,w)->{
            switch(w){case 0:updates.check(true);show();break;case 1:if(updates.available==null)host.toast("请先检查是否有更高版本");else updates.download();show();break;
                case 2:install();break;case 3:notes();break;case 4:mode();break;case 5:channel();break;case 6:if(updates.busy)updates.cancel();show();break;case 7:help();break;}
        }).setNegativeButton("返回",null).create();host.showDialog(page);}
    private void notes(){UpdateManager.Release r=updates.available;host.showDialog(new TvDialog.Builder(host).setPageId("update_notes").setTitle("更新内容")
        .setMessage(r==null?"当前渠道尚未发现可安装的更高版本。":r.version+"\n\n"+r.notes).setPositiveButton("返回",null).create());}
    private void mode(){String[] values={"notify","download","off"};int selected=java.util.Arrays.asList(values).indexOf(updates.mode());
        host.showDialog(new TvDialog.Builder(host).setPageId("update_mode").setTitle("更新方式")
            .setMessage("自动检查仅在桌面运行时进行，至少间隔 6 小时。不唤醒关机电视，不在其他应用中弹窗。自动下载会使用当前网络，安装仍需确认。")
            .setSingleChoiceItems(new String[]{"自动检查并提醒","自动下载后提醒","关闭自动检查（仍可手动）"},selected,(d,w)->{updates.setMode(values[w]);show();})
            .setNegativeButton("返回",null).create());}
    private void channel(){host.showDialog(new TvDialog.Builder(host).setPageId("update_channel").setTitle("更新渠道")
        .setMessage("稳定版适合日常使用；体验版包含尚待进一步验证的更改。切回稳定渠道不会降级安装，也不会清除配置。")
        .setSingleChoiceItems(new String[]{"稳定版（默认）","体验版"},updates.channel().equals("stable")?0:1,(d,w)->{
            if(!updates.setChannel(w==0?"stable":"preview"))host.toast("请等当前操作结束或取消后再切换渠道");show();
        }).setNegativeButton("返回",null).create());}
    private void help(){host.showDialog(new TvDialog.Builder(host).setPageId("update_help").setTitle("更新说明")
        .setMessage("更新由电视主动检查 GitHub 官方发布，不需要账号或开放公网 ADB。网络受限时可以稍后重试或使用 U 盘安装。\n\n"
            +"下载安装包后核对完整性、包名、版本及本机签名；安装前保存配置到本机备份目录，不清除原数据。厂商可能要求允许 Marco TV 安装未知应用，或不提供安装页面。\n\n"
            +updates.bridgeNote()+"\n\n应用不会静默安装，不会修改 Home、ADB 或无障碍授权。升级失败时可继续使用当前版本。")
        .setPositiveButton("返回",null).create());}
    private void install(){
        if(updates.busy){host.toast("请等当前操作完成");show();return;}
        if(!updates.ready()){host.toast("请先下载并校验新版");show();return;}
        if(Build.VERSION.SDK_INT>=26&&!host.getPackageManager().canRequestPackageInstalls()){
            host.showDialog(new TvDialog.Builder(host).setPageId("update_permission").setTitle("允许安装 Marco TV 更新")
                .setMessage("电视需要你允许 Marco TV 安装未知来源应用。打开后返回本页，再选择安装；此授权不会自动执行安装。")
                .setPositiveButton("打开系统授权页面",(d,w)->{try{host.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+host.getPackageName())));}catch(RuntimeException e){host.toast("此电视未提供授权页面，请使用 U 盘或电脑更新");}})
                .setNegativeButton("返回",null).create());return;
        }
        host.showDialog(new TvDialog.Builder(host).setPageId("update_confirm").setTitle("安装 "+updates.available.version+"？")
            .setMessage("先备份当前配置，再打开系统安装确认。安装将暂时退出 Marco TV；收藏、排序和壁纸保留。\n\n"+updates.bridgeNote())
            .setPositiveButton("备份并继续安装",(d,w)->prepareInstall()).setNegativeButton("稍后",null).create());
    }
    private void prepareInstall(){final UpdateManager.Release r=updates.available;if(r==null||updates.busy)return;
        updates.busy=true;updates.resetCancellation();updates.message="正在校验并备份配置…";show();final int epoch=host.menuEpoch;
        updates.worker.execute(()->{try{
            UpdateManager.verifyApk(host,updates.apk(),r);
            File folder=new File(host.getFilesDir(),"backups");folder.mkdirs();
            BackupStore.write(host,new File(folder,"before-update.zip"),BackupStore.capture(host,true));
            host.handler.post(()->{updates.busy=false;if(!resumed||host.isDestroyed()||epoch!=host.menuEpoch||!host.isPage("updates")||updates.cancelled()){updates.message="配置已备份，返回更新页后可继续安装";refresh();return;}
                try{Intent intent=new Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(UpdateApkProvider.URI,"application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT,true);
                    intent.setClipData(ClipData.newRawUri("Marco TV update",UpdateApkProvider.URI));
                    updates.prefs.edit().putInt("pending_code",r.code).commit();host.startActivityForResult(intent,INSTALL);updates.message="已打开系统安装页面，请确认或返回";
                }catch(RuntimeException e){updates.prefs.edit().remove("pending_code").apply();updates.message="无法打开系统安装器，请使用 U 盘或电脑更新";refresh();}
            });
        }catch(Exception e){host.handler.post(()->{updates.busy=false;updates.message="校验或备份失败，未启动安装；请检查空间并重新下载";Diagnostics.event("UPDATE_INSTALL_PREPARE_FAILED");refresh();});}});
    }
    boolean result(int request){if(request!=INSTALL)return false;updates.prefs.edit().remove("pending_code").apply();
        updates.message="已返回更新页。若取消了安装，当前版本继续保留。";refresh();return true;}
}
