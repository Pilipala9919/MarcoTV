// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;
public final class LiteSettingsChecks {
    static int passed;
    static void check(String s,boolean ok){
        if(!ok)throw new AssertionError(s);
        passed++;
        System.out.println("PASS "+s);
    }
    public static void main(String[] args){
        check("default_layout_matches_widened_full_edition",LiteSettings.center(75,100)==747.5f&&LiteSettings.height(100)==180f&&LiteSettings.width(100)==320f);
        check("first_two_cards_do_not_scroll",LiteSettings.scroll(0)==0&&LiteSettings.scroll(1)==0);
        check("third_card_anchors_in_second_slot",LiteSettings.scroll(2)==350);
        check("negative_selection_does_not_scroll",LiteSettings.scroll(-1)==0);
        boolean bounds=true;
        for(int i=0;i<LiteSettings.KEYS.length;i++)bounds&=LiteSettings.clamp(i,-10000)==LiteSettings.MIN[i]&&LiteSettings.clamp(i,10000)==LiteSettings.MAX[i]&&LiteSettings.clamp(i,
                LiteSettings.DEFAULT[i])==LiteSettings.DEFAULT[i];
        check("all_parameter_defaults_and_bounds_valid",bounds);
        boolean safe=true,monotonic=true;
        for(int h=70;h<=130;h++)for(int pos=0;pos<=100;pos++){
            float cy=LiteSettings.center(pos,h),height=LiteSettings.height(h);
            safe&=cy-height/2f>=394.9f&&cy+height/2f<=925.1f;
            if(pos>0)monotonic&=cy>LiteSettings.center(pos-1,h);
        }
        check("all_6161_layout_combinations_keep_safe_bounds",safe);
        check("position_always_moves_down_when_increased",monotonic);
        check("header_ranges_keep_clock_visible",LiteSettings.MAX[5]==160&&LiteSettings.MAX[8]==280);
        int[] parsed=LiteSettings.parseCardSize("135,120,60,48");
        int[] bad=LiteSettings.parseCardSize("invalid");
        int[] clamped=LiteSettings.parseCardSize("500,10,999,200");
        check("per_card_size_parse_format_and_clamp",
                parsed[0]==135&&parsed[1]==120&&parsed[2]==60&&parsed[3]==48
                &&bad[0]==0&&bad[1]==0&&bad[2]==-1&&bad[3]==-1
                &&clamped[0]==160&&clamped[1]==60&&clamped[2]==100&&clamped[3]==64
                &&LiteSettings.formatCardSize(0,0,-1,-1).equals("")
                &&LiteSettings.formatCardSize(120,110,40,24).equals("120,110,40,24"));
        float[] uniform={272f,622f,972f,1322f};
        float[] mixed={220f,560f,980f};
        check("variable_width_scroll_centers",
                LiteSettings.scrollForCenters(uniform,0)==0f
                &&LiteSettings.scrollForCenters(uniform,1)==0f
                &&LiteSettings.scrollForCenters(uniform,2)==350f
                &&LiteSettings.scrollForCenters(mixed,2)==420f);
        check("cover_and_wallpaper_helpers_reject_traversal",
                LiteSettings.nextWallpaper(0)==1&&LiteSettings.nextWallpaper(1)==3&&LiteSettings.nextWallpaper(5)==0
                &&LiteSettings.sleepLabel(0).equals("不限制")&&LiteSettings.sleepLabel(30).equals("30 分钟")
                &&LiteSettings.coverColorIndex("color:2")==2&&LiteSettings.coverColorIndex("color:99")==-1
                &&"cover-a.jpg".equals(LiteSettings.coverFileName("image:cover-a.jpg"))
                &&LiteSettings.coverFileName("image:../secret.jpg")==null
                &&LiteSettings.focusScale(12)==1.12f);
        int once=LiteSettings.homeState(true,"com.pilipala.tvhome.lite","android","com.android.internal.app.ResolverActivity",true);
        check("lite_just_once_is_not_default_home",
                once==LiteSettings.HOME_STATE_ONCE&&!LiteSettings.isDefaultHomeState(once)
                &&LiteSettings.homeStateLabel(once).contains("尚未设为默认"));
        int always=LiteSettings.homeState(true,"com.pilipala.tvhome.lite","com.pilipala.tvhome.lite","com.pilipala.tvhome.LiteActivity",true);
        check("lite_always_is_default_home",
                always==LiteSettings.HOME_STATE_DEFAULT&&LiteSettings.isDefaultHomeState(always)
                &&LiteSettings.homeStateLabel(always).contains("已生效"));
        int reverted=LiteSettings.homeState(true,"com.pilipala.tvhome.lite","com.mitv.tvhome","com.mitv.tvhome.MainActivity",true);
        check("lite_reverted_to_oem_not_default_even_with_stale_home_intent",
                reverted==LiteSettings.HOME_STATE_OTHER&&!LiteSettings.isDefaultHomeState(reverted)
                &&LiteSettings.homeStateLabel(reverted).contains("尚未设为默认"));
        int unset=LiteSettings.homeState(true,"com.pilipala.tvhome.lite","android","com.android.internal.app.ResolverActivity",false);
        int unsetNull=LiteSettings.homeState(true,"com.pilipala.tvhome.lite","","",false);
        int unsetFallback=LiteSettings.homeState(true,"com.pilipala.tvhome.lite","com.android.settings","com.android.settings.FallbackHome",false);
        check("lite_no_explicit_default_shows_unconfirmed",
                unset==LiteSettings.HOME_STATE_UNSET&&unsetNull==LiteSettings.HOME_STATE_UNSET&&unsetFallback==LiteSettings.HOME_STATE_UNSET
                &&!LiteSettings.isDefaultHomeState(unset)
                &&(LiteSettings.homeStateLabel(unset).contains("尚未设为默认")||LiteSettings.homeStateLabel(unset).contains("等待系统确认")));
        int restricted=LiteSettings.homeState(false,"com.pilipala.tvhome.lite","com.pilipala.tvhome.lite","com.pilipala.tvhome.LiteActivity",true);
        check("lite_restricted_never_claims_default_home",
                restricted==LiteSettings.HOME_STATE_RESTRICTED&&!LiteSettings.isDefaultHomeState(restricted)
                &&LiteSettings.homeStateLabel(restricted).contains("普通应用"));
        System.out.println("OK "+passed+" Lite settings checks");
    }
}
