// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

/** Small, API-independent schema shared by editor, backup validation and tests. */
final class LiteSettings {
    static final String VERSION="0.1.0";
    static final String[] KEYS={"card_row_position","card_height","card_radius","clock_style","clock_tilt","clock_size","wallpaper_dim","idle_seconds","ambient_clock_size","ambient_dim","motion"};
    static final String[] NAMES={"卡片行上下位置","卡片高度","卡片圆角","时钟字体","圆体倾斜幅度","桌面时间大小","壁纸压暗","屏保等待（秒，0 关闭）","屏保时间大小","屏保背景压暗","卡片动效"};
    static final int[] MIN={0,70,0,0,0,36,0,0,100,0,0},MAX={100,130,64,3,14,160,90,600,280,100,1},DEFAULT={75,100,32,0,10,96,10,60,176,72,1};
    static int clamp(int i,int v){return Math.max(MIN[i],Math.min(MAX[i],v));}
    static float height(int percent){return 180*clamp(1,percent)/100f;}
    static float center(int position,int height){float extra=height(height)/2-90;return 495+extra+(220-2*extra)*clamp(0,position)/100f;}
    static float scroll(int selected){return Math.max(0,selected-1)*350;}
}
