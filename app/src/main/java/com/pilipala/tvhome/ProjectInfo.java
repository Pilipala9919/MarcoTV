// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.Context;
import java.io.*;

/** Author, release identity and bundled licensing for offline access. */
final class ProjectInfo {
    static final String VERSION="0.8.1";
    static final String AUTHOR="Pilipala";
    static final String REPOSITORY="https://github.com/Pilipala9919/MarcoTV";
    static final String FREE_NOTICE="本软件免费开源。如果你付费购买了本软件，请尽快向购买平台申请退款。";
    static String about(){
        return "版本 "+VERSION+"\n作者 "+AUTHOR+"\nCopyright © 2026 Pilipala\n\n免费开源软件 · GPL-3.0-only\n"+FREE_NOTICE
                +"\n\n开源地址\n"+REPOSITORY+"\n\n使用说明、安装说明和各品牌设置入口见项目文档。";
    }
    static String licenses(Context context){
        return "Marco TV\nCopyright © 2026 Pilipala\nGPL-3.0-only\n\n"+FREE_NOTICE
                +"\n\n本软件按开源协议提供，不提供担保。第三方资源保留各自署名与许可。"
                +"\n\n—— 第三方资源声明 ——\n"+read(context,"licenses/NOTICE")
                +"\n\n—— GNU GPL v3 ——\n"+read(context,"licenses/GPL-3.0-only.txt")
                +"\n\n—— Apache License 2.0 ——\n"+read(context,"licenses/Material-Icons-Apache-2.0.txt");
    }
    private static String read(Context context,String name){
        try(InputStream in=context.getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] block=new byte[4096];int size;
            while((size=in.read(block))!=-1)out.write(block,0,size);
            return out.toString("UTF-8");
        }catch(IOException e){return "许可全文也可在项目仓库查看："+REPOSITORY;}
    }
}
