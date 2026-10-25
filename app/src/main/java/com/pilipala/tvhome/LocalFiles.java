// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.util.*;

/** User-selected file import/export, with a usable local fallback on TVs without a picker. */
final class LocalFiles {
    static final int SAVE=310,OPEN=311,READ=312;
    interface Selected {void accept(Uri uri);}
    private final MainActivity host;private File pending;private Selected selected;private int epoch;
    LocalFiles(MainActivity h){host=h;}
    void export(File file,String mime){
        pending=file;epoch=host.menuEpoch;
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE,file.getName());
        try{host.startActivityForResult(intent,SAVE);}catch(RuntimeException error){fallbackExport(file);}
    }
    private void fallbackExport(File file){
        pending=null;host.loader.execute(()->{
            try{File folder=host.getExternalFilesDir("exports");if(folder==null)throw new IOException();folder.mkdirs();File target=new File(folder,file.getName());copy(file,new FileOutputStream(target));
                host.handler.post(()->{if(epoch==host.menuEpoch)host.showDialog(new TvDialog.Builder(host).setTitle("文件已导出")
                    .setMessage("电视没有文件保存页面，已保存至：\n"+target.getAbsolutePath()+"\n\n可用文件管理器复制到 U 盘或电脑。此目录会随卸载删除，请及时移出电视。")
                    .setPositiveButton("知道了",null).create());});
            }catch(Exception e){host.handler.post(()->host.toast("导出失败，请检查存储空间"));Diagnostics.event("EXPORT_FAILED");}
        });
    }
    void open(Selected callback){
        selected=callback;epoch=host.menuEpoch;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        try{host.startActivityForResult(intent,OPEN);}catch(RuntimeException e){browseStart();}
    }
    private void browseStart(){
        if(Build.VERSION.SDK_INT>=33){host.toast("请安装支持选择文件的文件管理器后重试");selected=null;return;}
        if(host.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){host.requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE},READ);return;}
        browse(Environment.getExternalStorageDirectory());
    }
    private void browse(File folder){
        File root=Environment.getExternalStorageDirectory();
        try{if(!folder.getCanonicalPath().equals(root.getCanonicalPath())&&!folder.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))return;}catch(IOException e){return;}
        ArrayList<File> files=new ArrayList<>();File[] found=folder.listFiles(f->!f.getName().startsWith(".")&&(f.isDirectory()||f.getName().endsWith(".zip")));
        if(found!=null)files.addAll(Arrays.asList(found));Collections.sort(files,(a,b)->a.isDirectory()!=b.isDirectory()?(a.isDirectory()?-1:1):a.getName().compareToIgnoreCase(b.getName()));
        if(files.size()>300)files=new ArrayList<>(files.subList(0,300));
        final ArrayList<File> choices=files;ArrayList<String> labels=new ArrayList<>();boolean parent=!folder.equals(root);if(parent)labels.add("↑ 上一级");
        for(File f:files)labels.add((f.isDirectory()?"文件夹 · ":"")+f.getName());
        host.showDialog(new TvDialog.Builder(host).setTitle("选择备份 · "+folder.getName()).setMessage("选择 Marco TV 的 ZIP 备份文件。")
            .setItems(labels.toArray(new String[0]),(d,w)->{if(parent&&w==0){browse(folder.getParentFile());return;}File f=choices.get(w-(parent?1:0));if(f.isDirectory())browse(f);else{Selected callback=selected;selected=null;if(callback!=null)callback.accept(Uri.fromFile(f));}})
            .setNegativeButton("返回",null).create());
    }
    boolean result(int request,int result,Intent data){
        if(request!=SAVE&&request!=OPEN)return false;
        if(epoch!=host.menuEpoch||result!=Activity.RESULT_OK||data==null||data.getData()==null){pending=null;selected=null;return true;}
        Uri uri=data.getData();
        if(request==OPEN){Selected callback=selected;selected=null;if(callback!=null)callback.accept(uri);}
        else {File file=pending;pending=null;if(file!=null)host.loader.execute(()->{
            try{OutputStream out=host.getContentResolver().openOutputStream(uri,"wt");if(out==null)throw new IOException();copy(file,out);host.handler.post(()->host.toast("文件已保存"));}
            catch(Exception e){Diagnostics.event("EXPORT_FAILED");host.handler.post(()->host.toast("保存失败，请重新选择位置"));}
        });}return true;
    }
    void permission(int[] grants){if(epoch==host.menuEpoch&&selected!=null&&grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)browseStart();else selected=null;}
    static void copy(File file,OutputStream output)throws IOException{try(InputStream in=new FileInputStream(file);OutputStream out=output){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}}
}
