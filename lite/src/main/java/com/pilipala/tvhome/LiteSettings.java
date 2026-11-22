// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

/** Small, API-independent schema shared by editor, backup validation and tests. */
final class LiteSettings {
    static final String VERSION="0.3.0";
    static final int CORE_COUNT=11;
    static final float CARD_GAP=30f;
    static final float SHELF_START_X=112f;
    static final int[] SLEEP_CHOICES={15,30,60,120,0};
    static final String[] WALLPAPER_NAMES={"极夜山脊","雾蓝海岸","本地图片","暮光峡谷","深海极夜","暖金沙丘"};
    static final String[] COVER_NAMES={"丁香紫","午夜蓝","森林绿","暖琥珀","雾紫","冰海蓝"};
    static final int[][] COVER_COLORS={
            {0xFF7D56D1,0xFF4D3292,0xFF281952},
            {0xFF284B78,0xFF162C4A,0xFF0E1B30},
            {0xFF255C4F,0xFF173D34,0xFF0E2520},
            {0xFF7D5027,0xFF523217,0xFF331D0C},
            {0xFF533B78,0xFF362452,0xFF201533},
            {0xFF246A82,0xFF164759,0xFF0D2B38}
    };
    static final String[] KEYS={
            "card_row_position","card_height","card_radius","clock_style","clock_tilt","clock_size",
            "wallpaper_dim","idle_seconds","ambient_clock_size","ambient_dim","motion",
            "card_width","card_depth","ambient_pure_wallpaper",
            "card_scale","label_opacity","ambient_clock_brightness"
    };
    static final String[] NAMES={
            "卡片行上下位置","卡片高度","卡片圆角","时钟字体","圆体倾斜幅度","桌面时间大小",
            "壁纸压暗","屏保等待（秒，0 关闭）","屏保时间大小","屏保背景压暗","卡片动效",
            "卡片宽度","卡片立体厚度","闲置纯净壁纸",
            "选中放大幅度","未选中名称亮度","屏保时钟亮度"
    };
    static final int[] MIN={0,70,0,0,0,36,0,0,100,0,0,70,0,0,0,0,30};
    static final int[] MAX={100,130,64,3,14,160,90,600,280,100,1,140,100,1,30,100,100};
    static final int[] DEFAULT={75,100,32,0,10,96,10,60,176,72,1,100,0,0,12,75,100};

    static boolean isBool(int i){
        return i==13;
    }
    static int clamp(int i,int v){
        return Math.max(MIN[i],Math.min(MAX[i],v));
    }
    static float width(int percent){
        return 320f*clamp(11,percent)/100f;
    }
    static float height(int percent){
        return 180f*clamp(1,percent)/100f;
    }
    static float depth(int percent){
        return 16f*clamp(12,percent)/100f;
    }
    static float focusScale(int percent){
        return 1f+clamp(14,percent)/100f;
    }
    /** Widened downward shelf range aligned with Marco TV 0.20.0 while keeping title & label safe. */
    static float center(int position,int height){
        float half=height(height)/2f;
        float topY=395f+half,bottomY=925f-half;
        return topY+(bottomY-topY)*clamp(0,position)/100f;
    }
    static float scroll(int selected){
        return Math.max(0,selected-1)*350;
    }
    static float scrollForCenters(float[] centers,int selected){
        if(centers==null||centers.length<=2||selected<=1)return 0f;
        int idx=Math.min(selected,centers.length-1);
        return Math.max(0f,centers[idx]-centers[1]);
    }
    static int clampCustomWidth(int v){
        return v<=0?0:Math.max(60,Math.min(160,v));
    }
    static int clampCustomHeight(int v){
        return v<=0?0:Math.max(60,Math.min(150,v));
    }
    static int clampCustomDepth(int v){
        return v<0?-1:Math.max(0,Math.min(100,v));
    }
    static int clampCustomRadius(int v){
        return v<0?-1:Math.max(0,Math.min(64,v));
    }
    static int[] parseCardSize(String raw){
        int[] out={0,0,-1,-1};
        if(raw==null||raw.length()==0)return out;
        String[] parts=raw.split(",");
        if(parts.length!=4)return out;
        try{
            out[0]=clampCustomWidth(Integer.parseInt(parts[0].trim()));
            out[1]=clampCustomHeight(Integer.parseInt(parts[1].trim()));
            out[2]=clampCustomDepth(Integer.parseInt(parts[2].trim()));
            out[3]=clampCustomRadius(Integer.parseInt(parts[3].trim()));
        }catch(NumberFormatException ignored){
            return new int[]{0,0,-1,-1};
        }
        return out;
    }
    static String formatCardSize(int w,int h,int d,int r){
        w=clampCustomWidth(w);
        h=clampCustomHeight(h);
        d=clampCustomDepth(d);
        r=clampCustomRadius(r);
        if(w==0&&h==0&&d<0&&r<0)return "";
        return w+","+h+","+d+","+r;
    }
    static float cardWidthPx(int globalPercent,int customW){
        return customW>0?320f*clampCustomWidth(customW)/100f:width(globalPercent);
    }
    static float cardHeightPx(int globalPercent,int customH){
        return customH>0?180f*clampCustomHeight(customH)/100f:height(globalPercent);
    }
    static float cardDepthPx(int globalPercent,int customD){
        return customD>=0?16f*clampCustomDepth(customD)/100f:depth(globalPercent);
    }
    static float cardRadiusPx(int globalRadius,int customR){
        return customR>=0?clampCustomRadius(customR):clamp(2,globalRadius);
    }
    static int nextWallpaper(int current){
        if(current==0)return 1;
        if(current==1)return 3;
        if(current==3)return 4;
        if(current==4)return 5;
        return 0;
    }
    static String wallpaperName(int id){
        return id>=0&&id<WALLPAPER_NAMES.length?WALLPAPER_NAMES[id]:WALLPAPER_NAMES[0];
    }
    static String sleepLabel(int minutes){
        return minutes<=0?"不限制":minutes+" 分钟";
    }
    static int coverColorIndex(String cover){
        if(cover==null||!cover.startsWith("color:"))return -1;
        try{
            int idx=Integer.parseInt(cover.substring(6));
            return idx>=0&&idx<COVER_COLORS.length?idx:-1;
        }catch(NumberFormatException ignored){
            return -1;
        }
    }
    static String coverFileName(String cover){
        if(cover==null||!cover.startsWith("image:"))return null;
        String name=cover.substring(6);
        if(name.length()==0||name.indexOf('/')>=0||name.indexOf('\\')>=0||name.indexOf("..")>=0)return null;
        return name;
    }
}
