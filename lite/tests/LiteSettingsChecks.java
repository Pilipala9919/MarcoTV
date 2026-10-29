// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;
public final class LiteSettingsChecks {
    static int passed;
    static void check(String s,boolean ok){if(!ok)throw new AssertionError(s);passed++;System.out.println("PASS "+s);}
    public static void main(String[] args){
        check("default_layout_matches_full_edition",LiteSettings.center(75,100)==660&&LiteSettings.height(100)==180);
        check("first_two_cards_do_not_scroll",LiteSettings.scroll(0)==0&&LiteSettings.scroll(1)==0);
        check("third_card_anchors_in_second_slot",LiteSettings.scroll(2)==350);
        check("negative_selection_does_not_scroll",LiteSettings.scroll(-1)==0);
        boolean bounds=true;for(int i=0;i<LiteSettings.KEYS.length;i++)bounds&=LiteSettings.clamp(i,-10000)==LiteSettings.MIN[i]&&LiteSettings.clamp(i,10000)==LiteSettings.MAX[i]&&LiteSettings.clamp(i,LiteSettings.DEFAULT[i])==LiteSettings.DEFAULT[i];
        check("all_parameter_defaults_and_bounds_valid",bounds);
        boolean safe=true,monotonic=true;for(int h=70;h<=130;h++)for(int pos=0;pos<=100;pos++){float cy=LiteSettings.center(pos,h),height=LiteSettings.height(h);safe&=cy-height/2-110>280&&cy+height/2+100<930;if(pos>0)monotonic&=cy>LiteSettings.center(pos-1,h);}
        check("all_6161_layout_combinations_keep_safe_bounds",safe);check("position_always_moves_down_when_increased",monotonic);
        check("header_ranges_keep_clock_visible",LiteSettings.MAX[5]==160&&LiteSettings.MAX[8]==280);
        System.out.println("OK "+passed+" Lite settings checks");
    }
}
