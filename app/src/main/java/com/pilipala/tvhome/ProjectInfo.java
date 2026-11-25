// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.Context;
import java.io.*;

/** Author, release identity and bundled licensing for offline access. */
final class ProjectInfo {
    static final String VERSION="0.21.3";
    static final String AUTHOR="Pilipala";
    static final String SOCIAL_ACCOUNT="PILIPALA来信";
    static final String SOCIAL_PLATFORMS="小红书、B站、抖音、公众号、视频号";
    static final String OFFICIAL_SITE_HOST="marcotv.wlj9919.top";
    static final String OFFICIAL_SITE_URL="https://marcotv.wlj9919.top";
    static final String COMMUNITY_GROUP_URL="https://marcotv.wlj9919.top/tv/#group";
    static final String REPOSITORY="https://github.com/Pilipala9919/MarcoTV";
    static final String OFFICIAL_DOWNLOAD_WARNING="下载软件请认准官网地址，以防不明渠道软件添加病毒等不良内容。";
    static final String FREE_NOTICE="本软件免费开源。如果你付费购买了本软件，请尽快向购买平台申请退款。";
    private static volatile String cachedLicenses;
    static boolean shouldShowFirstLaunchNotice(boolean noticeSeen,boolean recentApps,boolean wakeRecovery,boolean ambient){
        return !noticeSeen&&!recentApps&&!wakeRecovery&&!ambient;
    }
    static String firstLaunchNotice(){
        return "感谢安装 Marco TV！\n\n"
                +"作者各社媒账号名："+SOCIAL_ACCOUNT+"\n"
                +"（"+SOCIAL_PLATFORMS+"）\n\n"
                +"Marco 官网："+OFFICIAL_SITE_HOST+"\n"
                +"官网交流群："+COMMUNITY_GROUP_URL+"\n\n"
                +OFFICIAL_DOWNLOAD_WARNING+"\n"
                +FREE_NOTICE;
    }
    static String contactAuthor(){
        return "作者各社媒账号名："+SOCIAL_ACCOUNT+"\n"
                +"覆盖平台："+SOCIAL_PLATFORMS+"\n\n"
                +"Marco 官网："+OFFICIAL_SITE_HOST+"\n"
                +"官网交流群："+COMMUNITY_GROUP_URL+"\n"
                +"开源仓库："+REPOSITORY+"\n\n"
                +OFFICIAL_DOWNLOAD_WARNING+"\n"
                +"手机扫描左侧二维码可直接进入官网交流群；遇到软件问题也可直接点击「提交问题反馈」发送脱敏报告。";
    }
    static String about(){
        return "版本 "+VERSION+(BuildEdition.RESTRICTED?" · 受限系统兼容版（待实测）":"")+"\n作者 "+AUTHOR
                +"\n社媒账号 "+SOCIAL_ACCOUNT+"（"+SOCIAL_PLATFORMS+"）\nCopyright © 2026 Pilipala\n\n免费开源软件 · GPL-3.0-only\n"+FREE_NOTICE
                +"\n\nMarco 官网\n"+OFFICIAL_SITE_HOST+"\n官网交流群\n"+COMMUNITY_GROUP_URL
                +"\n\n"+OFFICIAL_DOWNLOAD_WARNING
                +"\n\n开源地址\n"+REPOSITORY+"\n\n使用说明、安装说明和各品牌设置入口见项目文档。";
    }
    static String licenses(Context context){
        String cached=cachedLicenses;
        if(cached!=null)return cached;
        cached="Marco TV\nCopyright © 2026 Pilipala\nGPL-3.0-only\n\n"+FREE_NOTICE
                +"\n\n本软件按开源协议提供，不提供担保。第三方资源保留各自署名与许可。"
                +"\n\n—— 第三方资源声明 ——\n"+read(context,"licenses/NOTICE")
                +"\n\n—— GNU GPL v3 ——\n"+read(context,"licenses/GPL-3.0-only.txt")
                +"\n\n—— Apache License 2.0 ——\n"+read(context,"licenses/Material-Icons-Apache-2.0.txt")
                +"\n\n—— QR Code generator (MIT) ——\n"+read(context,"licenses/QR-Code-generator-MIT.txt")
                +"\n\n—— 时钟字体 (SIL OFL 1.1) ——\n"+read(context,"licenses/robotoflex-OFL.txt")+"\n\n"+read(context,"licenses/barlowcondensed-OFL.txt")+"\n\n"+read(context,
                "licenses/nunito-OFL.txt")+"\n\n"+read(context,"licenses/bodonimoda-OFL.txt");
        cachedLicenses=cached;
        return cached;
    }
    private static String read(Context context,String name){
        try(InputStream in=context.getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] block=new byte[4096];
            int size;
            while((size=in.read(block))!=-1)out.write(block,0,size);
            return out.toString("UTF-8");
        }catch(IOException e){
            return "许可全文也可在项目仓库查看："+REPOSITORY;
        }
    }
}
