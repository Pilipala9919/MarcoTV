// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.view.*;
import android.widget.TextView;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.function.BiConsumer;

/** Exercises the real local server, pairing UI and reversible preview on a TV. */
final class PhoneDeviceChecks {
    static String authority,cookie="",csrf="";
    static int status;
    static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static String request(String path,JSONObject body,String origin,String token)throws Exception{
        String[] endpoint=authority.split(":");
        try(Socket socket=new Socket()){
            socket.connect(new InetSocketAddress(endpoint[0],Integer.parseInt(endpoint[1])),5000);socket.setSoTimeout(10000);
            byte[] bytes=body==null?new byte[0]:body.toString().getBytes(StandardCharsets.UTF_8);
            String head=(body==null?"GET":"POST")+" "+path+" HTTP/1.1\r\nHost: "+authority+"\r\nConnection: close\r\nCookie: "+cookie+"\r\n";
            if(body!=null)head+="Origin: "+origin+"\r\nX-Marco-Csrf: "+token+"\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\n";
            socket.getOutputStream().write((head+"\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(bytes);
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[4096];int n;while((n=socket.getInputStream().read(block))!=-1){out.write(block,0,n);String received=out.toString("UTF-8");int headerEnd=received.indexOf("\r\n\r\n");if(headerEnd>=0){java.util.regex.Matcher size=java.util.regex.Pattern.compile("Content-Length: ([0-9]+)").matcher(received.substring(0,headerEnd));if(size.find()&&out.size()>=received.substring(0,headerEnd+4).getBytes(StandardCharsets.UTF_8).length+Integer.parseInt(size.group(1)))break;}}
            String response=out.toString("UTF-8");int split=response.indexOf("\r\n\r\n");String headers=response.substring(0,split);status=Integer.parseInt(headers.split(" ")[1]);
            for(String line:headers.split("\r\n"))if(line.startsWith("Set-Cookie: "))cookie=line.substring(12).split(";",2)[0];
            return response.substring(split+4);
        }
    }
    static JSONObject state()throws Exception{return new JSONObject(request("/state",null,"",csrf));}
    static JSONObject action(JSONObject body)throws Exception{return new JSONObject(request("/action",body,"http://"+authority,csrf));}
    static View find(View v,String label){if(v instanceof TextView&&((TextView)v).getText().toString().equals(label))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View r=find(g.getChildAt(i),label);if(r!=null)return r;}}return null;}
    static void run(Instrumentation inst,MainActivity host,BiConsumer<String,Boolean> check)throws Exception{
        SharedPreferences prefs=host.preferences;boolean had=prefs.contains("card_radius");int saved=prefs.getInt("card_radius",32);
        try{
            inst.runOnMainSync(()->{host.exitMenus();host.phone.stop();host.home.setIdle(false);host.phone.show();});Thread.sleep(1000);
            authority=(String)field(host.phone,"authority");String code=(String)field(host.phone,"pairCode");String origin="http://"+authority;
            request("/",null,"","");check.accept("phone_page_is_available",status==200);
            request("/state",null,"","");check.accept("unpaired_state_is_denied",status==401);
            JSONObject pair=new JSONObject().put("code",code).put("nonce","1234567890abcdef1234567890abcdef");
            request("/pair",pair,"http://untrusted.invalid","");check.accept("cross_origin_pair_is_denied",status==403);
            JSONObject pending=new JSONObject(request("/pair",pair,origin,""));check.accept("pair_requires_tv_confirmation",pending.getString("status").equals("pending"));
            request("/state",null,"","");check.accept("pending_pair_cannot_read_state",status==401);
            inst.runOnMainSync(()->{try{TvDialog d=(TvDialog)field(host,"activeDialog");View button=find(d.getWindow().getDecorView(),"允许连接");if(button==null)throw new AssertionError("approval button");button.performClick();}catch(Exception e){throw new RuntimeException(e);}});
            JSONObject paired=new JSONObject(request("/pair",pair,origin,""));csrf=paired.getString("csrf");check.accept("tv_confirmation_establishes_session",paired.getString("status").equals("connected")&&!cookie.isEmpty());
            JSONObject current=state();check.accept("paired_state_lists_rows",status==200&&current.has("rows"));
            JSONObject values=new JSONObject().put("card_radius",saved==50?40:50);
            JSONObject preview=new JSONObject().put("op","preview").put("revision",current.getLong("revision")).put("values",values);
            request("/action",preview,origin,"wrong");check.accept("mutation_requires_csrf",status==403);
            action(preview);check.accept("valid_preview_accepted",status==200);
            inst.runOnMainSync(()->{check.accept("preview_does_not_write_preferences",prefs.getInt("card_radius",32)==saved);check.accept("preview_changes_render_preferences",host.appearance().getInt("card_radius",32)!=saved);});
            action(new JSONObject().put("op","cancel"));inst.runOnMainSync(()->check.accept("cancel_restores_preview",host.appearance().getInt("card_radius",32)==saved));
            current=state();preview.put("revision",current.getLong("revision")-1);action(preview);check.accept("stale_revision_is_rejected",status==409);
            preview.put("revision",current.getLong("revision")).put("values",new JSONObject().put("card_radius",999));action(preview);check.accept("out_of_range_is_rejected",status==400);
            preview.put("values",values);action(preview);
            action(new JSONObject().put("op","save").put("revision",current.getLong("revision")));check.accept("save_commits_preview",status==200&&prefs.getInt("card_radius",32)!=saved);
            current=state();preview.put("revision",current.getLong("revision"));action(preview);
            inst.runOnMainSync(()->prefs.edit().putInt("card_radius",saved).commit());
            inst.runOnMainSync(()->check.accept("tv_change_cancels_phone_preview",!host.phone.previewing()));
            action(new JSONObject().put("op","disconnect"));check.accept("disconnect_acknowledged",status==200);
            Thread.sleep(600);inst.runOnMainSync(()->check.accept("disconnect_stops_server",!host.phone.active()));
        }finally{
            inst.runOnMainSync(()->{host.phone.stop();SharedPreferences.Editor e=prefs.edit();if(had)e.putInt("card_radius",saved);else e.remove("card_radius");e.commit();host.exitMenus();host.home.refreshAppearance();host.home.invalidate();});cookie="";csrf="";
        }
    }
}
