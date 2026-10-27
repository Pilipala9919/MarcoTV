// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

public final class UpdatePolicyChecks {
    static int passed;
    static void check(String name,boolean ok){if(!ok)throw new AssertionError(name);passed++;System.out.println("PASS "+name);}
    static String good="https://github.com/Pilipala9919/MarcoTV/releases/download/v0.12.0/MarcoTV-0.12.0.apk";
    static void invalid(String name,int code,String version,int sdk,long size,String hash,String url,boolean preview,String channel){boolean failed=false;
        try{UpdatePolicy.validate(code,version,sdk,size,hash,url,preview,channel);}catch(IllegalArgumentException e){failed=true;}check(name,failed);}
    public static void main(String[] args)throws Exception{String hash=new String(new char[64]).replace('\0','a');
        UpdatePolicy.validate(21,"0.12.0",23,123,hash,good,false,"stable");check("official_release_allowed",UpdatePolicy.apkUrl(good));
        check("higher_code_is_update",UpdatePolicy.newer(21,20));check("same_code_not_reinstalled",!UpdatePolicy.newer(20,20));check("downgrade_rejected",!UpdatePolicy.newer(19,20));
        invalid("preview_not_sent_to_stable",21,"0.12.0-rc.2",23,123,hash,good,true,"stable");
        invalid("invalid_channel_rejected",21,"0.12.0",23,123,hash,good,false,"other");
        invalid("invalid_version_rejected",21,"../../bad",23,123,hash,good,false,"stable");
        invalid("zero_size_rejected",21,"0.12.0",23,0,hash,good,false,"stable");
        invalid("large_size_rejected",21,"0.12.0",23,UpdatePolicy.MAX_APK+1,hash,good,false,"stable");
        invalid("bad_hash_rejected",21,"0.12.0",23,123,"bad",good,false,"stable");
        for(String url:new String[]{good.replace("https:","http:"),good.replace("github.com","github.com.evil.test"),good.replace("Pilipala9919","someone"),good+"?other=1",good+"#other",good.replace("github.com","user@github.com"),good.replace("github.com","github.com:443"),good.replace("v0.12.0/","v0.12.0/../"),good.replace("v0.12.0/","v0.12.0/%2e%2e/")})
            check("untrusted_apk_url_"+passed,!UpdatePolicy.apkUrl(url));
        check("github_asset_redirect_allowed",UpdatePolicy.transferUrl("https://release-assets.githubusercontent.com/asset?token=test"));
        check("redirect_to_http_rejected",!UpdatePolicy.transferUrl("http://github.com/asset"));
        check("redirect_to_localhost_rejected",!UpdatePolicy.transferUrl("https://localhost/asset"));
        check("redirect_to_other_site_rejected",!UpdatePolicy.transferUrl("https://example.org/asset"));
        String mirror="https://marcotv.wlj9919.top/MarcoTV-0.12.0-rc.4.apk";
        check("mirror_apk_allowed",UpdatePolicy.apkUrl(mirror));
        check("mirror_transfer_allowed",UpdatePolicy.transferUrl(mirror));
        check("mirror_feed_first",UpdatePolicy.feeds("preview")[0].equals("https://marcotv.wlj9919.top/updates/preview.json"));
        check("official_feed_fallback",UpdatePolicy.feeds("stable")[1].equals(UpdatePolicy.FEED+"stable.json"));
        check("mirror_download_first",UpdatePolicy.downloads("0.12.0-rc.4")[0].equals(mirror));
        check("official_download_fallback",UpdatePolicy.downloads("0.12.0")[1].equals(good));
        for(String url:new String[]{mirror.replace("wlj9919","wlj999"),mirror.replace(".top/",".top.evil/"),mirror.replace("/MarcoTV-","/nested/MarcoTV-"),mirror+"?token=a",mirror.replace("https:","http:"),mirror.replace("/MarcoTV-","/%4darcoTV-"),mirror.replace("/MarcoTV-","/../MarcoTV-")})
            check("unsafe_mirror_url_"+passed,!UpdatePolicy.apkUrl(url));
        boolean rejectedVersion=false;try{UpdatePolicy.downloads("../../evil");}catch(IllegalArgumentException e){rejectedVersion=true;}check("download_version_injection_rejected",rejectedVersion);
        check("valid_range_continues",UpdatePolicy.resumeOffset(20,206,"bytes 20-99/100",100)==20);
        check("ignored_range_restarts_without_append",UpdatePolicy.resumeOffset(20,200,null,100)==0);
        for(String range:new String[]{"bytes 0-99/100","bytes 20-98/100","bytes 20-99/101",null,"bytes 20-999999999999999999999999/100"}){
            boolean rejected=false;try{UpdatePolicy.resumeOffset(20,206,range,100);}catch(java.io.IOException e){rejected=true;}check("bad_range_rejected_"+passed,rejected);
        }
        System.out.println("OK "+passed+" update policy checks");
    }
}
