// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

public final class HomeGuideChecks {
    private static int passed;
    private static void check(String name, boolean ok) {
        if(!ok)throw new AssertionError(name);
        passed++;
    }
    public static void main(String[] args) {
        check("trim_case",HomeDeviceGuide.family(" TCL ",null).equals("TCL / 雷鸟"));
        check("falcon_alias",HomeDeviceGuide.family("unknown","FFALCON").equals("TCL / 雷鸟"));
        check("vidda_alias",HomeDeviceGuide.family("VIDDA","").equals("海信 / Vidda"));
        check("coocaa_alias",HomeDeviceGuide.family(null,"coocaa").equals("创维 / 酷开"));
        check("redmi_alias",HomeDeviceGuide.family(null,"Redmi").equals("小米 / Redmi"));
        check("honor_not_assumed_android",HomeDeviceGuide.find(null,"honor").steps.contains("不能确认"));
        check("no_substring_brand_match",HomeDeviceGuide.family("tcl;reboot",null).equals("其他品牌"));
        check("unknown_uses_generic",HomeDeviceGuide.find(null,null).source.contains("developer.android.com"));
        check("sony_generic",HomeDeviceGuide.find("Sony","").evidence.contains("通用"));
        check("tcl_community_label",HomeDeviceGuide.find("tcl","").evidence.contains("尚未核实"));
        check("hisense_no_factory_mutation",HomeDeviceGuide.find("hisense","").steps.contains("不需要修改工厂模式"));
        for(HomeDeviceGuide.Issue issue:HomeDeviceGuide.Issue.values())
            check("issue_message_"+issue,HomeDeviceGuide.explanation(issue).length()>10);
        HomeDeviceGuide.ReturnGate gate=new HomeDeviceGuide.ReturnGate();
        check("no_implicit_resume",!gate.take(1,true));
        gate.arm(1);check("explicit_return",gate.take(1,true));
        check("only_one_attempt",!gate.take(1,true));
        gate.arm(1);check("home_epoch_blocks",!gate.take(2,true));
        check("old_attempt_consumed",!gate.take(1,true));
        gate.arm(2);gate.cancel();check("cancel_blocks",!gate.take(2,true));
        gate.arm(2);check("background_blocks",!gate.take(2,false));
        check("background_not_deferred",!gate.take(2,true));
        System.out.println("Home guide checks passed: "+passed);
    }
}
