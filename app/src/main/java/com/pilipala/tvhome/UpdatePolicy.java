// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import java.net.URI;

/** Public update metadata policy, also tested without Android. */
final class UpdatePolicy {
    static final long MAX_APK=128L*1024*1024;
    static final String FEED="https://pilipala9919.github.io/MarcoTV/updates/";
    static boolean apkUrl(String value){try{URI u=new URI(value);return "https".equals(u.getScheme())&&"github.com".equals(u.getHost())
        &&u.getUserInfo()==null&&u.getPort()==-1&&u.getQuery()==null&&u.getFragment()==null
        &&u.getPath().startsWith("/Pilipala9919/MarcoTV/releases/download/")&&u.getPath().endsWith(".apk")
        &&!u.getPath().contains("..")&&u.getRawPath().equals(u.getPath());}catch(Exception e){return false;}}
    static boolean transferUrl(String value){try{URI u=new URI(value);String h=u.getHost();return "https".equals(u.getScheme())&&u.getUserInfo()==null&&u.getPort()==-1
        &&("pilipala9919.github.io".equals(h)||"github.com".equals(h)||"release-assets.githubusercontent.com".equals(h)||"objects.githubusercontent.com".equals(h));}catch(Exception e){return false;}}
    static void validate(int code,String version,int minSdk,long size,String sha,String url,boolean preview,String channel){
        if(code<=0||version==null||!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-zA-Z0-9.]+)?")||version.length()>48
            ||minSdk<23||minSdk>100||size<1||size>MAX_APK||sha==null||!sha.matches("[a-f0-9]{64}")||!apkUrl(url)
            ||(!"stable".equals(channel)&&!"preview".equals(channel))||("stable".equals(channel)&&preview))throw new IllegalArgumentException("Invalid update metadata");
    }
    static boolean newer(int offered,int installed){return offered>installed;}
    static long resumeOffset(long requested,int status,String range,long size)throws java.io.IOException{
        if(requested<0||requested>=size)throw new java.io.IOException("invalid offset");
        if(status==200)return 0;
        if(status!=206||requested==0||range==null)throw new java.io.IOException("invalid range response");
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matcher(range);
        try{if(!m.matches()||Long.parseLong(m.group(1))!=requested||Long.parseLong(m.group(2))!=size-1||Long.parseLong(m.group(3))!=size)throw new java.io.IOException("range mismatch");}
        catch(NumberFormatException e){throw new java.io.IOException("range number",e);}return requested;
    }
}
