// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.JSONObject;

final class BackupPanel {
    private final MainActivity host;private boolean snapshotTaken,busy;private BackupStore.Prepared pending;
    void cancel(){if(pending!=null){pending.close();pending=null;}}
    BackupPanel(MainActivity h){host=h;}
    File folder(){File f=new File(host.getFilesDir(),"backups");f.mkdirs();return f;}
    void autoSnapshot(){
        if(snapshotTaken)return;snapshotTaken=true;
        try{JSONObject state=BackupStore.capture(host,true);host.loader.execute(()->{
            try{BackupStore.write(host,new File(folder(),"auto-"+System.currentTimeMillis()+".zip"),state);trim();}
            catch(Exception e){Diagnostics.event("AUTO_BACKUP_FAILED");}
        });}catch(Exception e){Diagnostics.event("AUTO_BACKUP_FAILED");}
    }
    private void trim(){File[] files=folder().listFiles(f->f.getName().startsWith("auto-"));if(files==null)return;Arrays.sort(files,(a,b)->b.getName().compareTo(a.getName()));for(int i=3;i<files.length;i++)files[i].delete();}
    void show(){host.showDialog(new TvDialog.Builder(host).setPageId("backup").setTitle("备份与恢复").setOrder("backup","settings","images","import","snapshots","create")
        .setMessage("本地保存，不上传云端。自动保留最近 3 次启动后首次进入设置前的快照。\n卸载会删除内部快照，请把重要备份导出到 U 盘或电脑。")
        .setItems(new String[]{"导出备份 · 仅设置","导出备份 · 设置与图片","从文件恢复","本地快照","立即创建本地快照"},(d,w)->{
            if(w<2)create(w==1,true);else if(w==2)host.files.open(uri->prepare(()->host.getContentResolver().openInputStream(uri)));else if(w==3)snapshots();else create(true,false);
        }).setNegativeButton("返回",null).create());}
    void create(boolean images,boolean export){
        host.toast("正在准备备份…");final int epoch=host.menuEpoch;
        host.loader.execute(()->{try{
            String stamp=new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(new Date());
            File target=new File(export?new File(host.getCacheDir(),"exports"):folder(),"MarcoTV-backup-"+stamp+".zip");
            BackupStore.write(host,target,BackupStore.capture(host,images));
            host.handler.post(()->{if(epoch==host.menuEpoch){if(export)host.files.export(target,"application/zip");else{host.toast("本地快照已保存");snapshots();}}});
        }catch(Exception e){Diagnostics.event("BACKUP_FAILED");host.handler.post(()->host.toast("备份失败，请检查空间或图片大小"));}});
    }
    void snapshots(){
        File[] found=folder().listFiles(f->f.getName().endsWith(".zip"));if(found==null)found=new File[0];Arrays.sort(found,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
        final File[] files=found;String[] labels=new String[files.length];for(int i=0;i<files.length;i++)labels[i]=files[i].getName()+"\n"+(files[i].length()/1024)+" KB";
        host.showDialog(new TvDialog.Builder(host).setTitle("本地快照").setMessage(files.length==0?"还没有本地快照。":"自动快照最多保留 3 份；手动快照可单独导出或删除。")
            .setItems(labels,(d,w)->snapshot(files[w])).setNegativeButton("返回",null).create());
    }
    private void snapshot(File file){host.showDialog(new TvDialog.Builder(host).setTitle("快照操作").setMessage(file.getName())
        .setItems(new String[]{"查看并恢复","导出这份快照","删除这份快照"},(d,w)->{
            if(w==0)prepare(()->new FileInputStream(file));else if(w==1)host.files.export(file,"application/zip");else host.showDialog(new TvDialog.Builder(host).setTitle("删除快照？").setMessage("只删除这份备份，当前桌面不变。")
                .setPositiveButton("删除",(x,y)->{if(file.delete())snapshots();else host.toast("删除失败");}).setNegativeButton("返回",null).create());
        }).setNegativeButton("返回",null).create());}
    interface Input {InputStream open()throws Exception;}
    void fromPhone(File staged){prepare(()->new FilterInputStream(new FileInputStream(staged)){
        @Override public void close()throws IOException{try{super.close();}finally{staged.delete();}}
    });}
    private void prepare(Input input){final int epoch=host.menuEpoch;host.toast("正在校验备份…");host.loader.execute(()->{
        try{InputStream source=input.open();if(source==null)throw new IOException();BackupStore.Prepared p=BackupStore.read(host,source);
            host.handler.post(()->{if(epoch!=host.menuEpoch||host.isDestroyed()){p.close();return;}
                cancel();pending=p;host.showDialog(new TvDialog.Builder(host).setTitle("恢复前确认")
                    .setMessage("来源版本："+p.metadata.optString("version")+"\n图片："+p.images.size()+" 张\n未安装应用："+p.missing+" 个（不显示卡片，不代为安装）\n\n将替换桌面外观、卡片和菜单排序。恢复前先保存当前完整配置；系统权限、ADB 和默认桌面授权不导入。")
                    .setPositiveButton("恢复这份备份",(d,w)->restore(p)).setNegativeButton("取消",(d,w)->p.close()).create());
            });
        }catch(Exception e){Diagnostics.event("BACKUP_REJECTED");host.handler.post(()->{if(epoch==host.menuEpoch)host.showDialog(new TvDialog.Builder(host).setTitle("无法恢复这份文件").setMessage("文件可能损坏、版本不支持或图片过大。当前配置没有改变，请选择有效的 Marco TV 备份。") .setPositiveButton("知道了",null).create());});}
    });}
    private void restore(BackupStore.Prepared p){if(busy)return;busy=true;pending=null;host.showDialog(new TvDialog.Builder(host).setTitle("正在恢复").setMessage("正在保存原配置并恢复备份，请稍候…").create());host.loader.execute(()->{
        try{BackupStore.restore(host,p);Diagnostics.event("BACKUP_RESTORED");host.handler.post(()->{
            host.exitMenus();host.home.refreshAppearance();host.loadWallpaper();host.loadApplications();host.playback.refresh();AmbientIntegration.configure(host);host.toast("恢复完成，原配置已存为恢复前快照");
        });}catch(Exception e){Diagnostics.event("RESTORE_FAILED");host.handler.post(()->{host.exitMenus();host.health.diagnostics();host.toast("恢复未完成，已尝试回退；请查看诊断页");});}finally{p.close();host.handler.post(()->busy=false);}
    });}
}
