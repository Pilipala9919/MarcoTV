// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.net.*;
import android.os.*;
import android.widget.*;
import io.nayuki.qrcodegen.QrCode;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit local management session. All state changes are serialized on the UI thread. */
final class PhoneManager implements PhoneHttp.Handler {
    static final String[] KEYS={"card_row_position","card_width","card_height","card_depth","card_radius","focus_scale","card_surface","label_opacity","clock_size","ambient_clock_size","ambient_clock_brightness",
            "ambient_dim","wallpaper_dim","motion_duration"};
    static final String[] LABELS={"卡片行上下位置","卡片宽度","卡片高度","卡片厚度","卡片圆角","选中放大幅度","卡片高光","名称显示强度","桌面时间大小","屏保时间大小","屏保时间亮度","屏保背景压暗","壁纸压暗","动效时长"};
    static final int[] DEFAULTS={75,100,100,0,32,16,55,0,96,176,100,72,10,260};
    private final MainActivity host;
    private PhoneHttp server;
    private volatile String authority="",pairCode="",nonce="",clientAddress="",cookie="",csrf="",initialTab="desktop",initialCardId="",initialCardLabel="";
    private volatile long until, pairUntil;
    private volatile boolean approved,closed=true;
    private volatile long generation;
    private volatile Runnable wallpaperImportedListener;
    private long revision=1,previewAt;
    void setWallpaperImportedListener(Runnable listener){
        wallpaperImportedListener=listener;
    }
    private boolean ambientPreview;
    boolean ambientPreview(){
        return previewing()&&ambientPreview;
    }
    private boolean ownEdit;
    private final AtomicBoolean transfer=new AtomicBoolean();
    private final SharedPreferences.OnSharedPreferenceChangeListener changed=(p,k)->{
        if(!ownEdit){
            revision++;
            clearPreview();
        }
    };
    private final Runnable timeout=new Runnable(){
        public void run(){
            if(closed)return;
            if(SystemClock.elapsedRealtime()>until){
                stop();
                return;
            }
            if(previewAt>0&&SystemClock.elapsedRealtime()-previewAt>30000){
                revision++;
                clearPreview();
            }
            host.handler.postDelayed(this,1000);
        }
    };
    PhoneManager(MainActivity h){
        host=h;
        for(String g:BackupStore.GROUPS)host.getSharedPreferences(g,0).registerOnSharedPreferenceChangeListener(changed);
    }
    boolean active(){
        return !closed;
    }
    void catalogChanged(){
        revision++;
    }
    boolean previewing(){
        return !host.previewPreferences.values.isEmpty();
    }
    void clearPreview(){
        if(host.previewPreferences.values.isEmpty())return;
        host.previewPreferences.values.clear();
        previewAt=0;
        host.home.refreshAppearance();
        host.home.invalidate();
    }
    void show(){
        initialTab="desktop";
        initialCardId="";
        initialCardLabel="";
        if(active()){
            host.showDialog(new TvDialog.Builder(host).setPageId("phone").setTitle("Marco 互联")
                    .setMessage((approved?"手机已连接":"等待手机扫码确认")+"\n仅限当前局域网；15 分钟未操作自动关闭。\n关闭连接会取消未保存的外观预览。")
                    .setItems(new String[]{"显示连接二维码","无线投屏与镜像","断开并关闭 Marco 互联"},(d,w)->{
                if(w==0)qr(0);
                else if(w==1)host.cast.show();
                else{
                    stop();
                    host.exitMenus();
                    host.toast("手机连接已关闭");
                }
            }).setNegativeButton("返回桌面",null).create());
            return;
        }
        startServerAndShowQr(0);
    }
    void showWallpaperImport(){
        initialTab="wallpaper";
        initialCardId="";
        initialCardLabel="";
        if(active()){
            if(approved){
                host.showDialog(new TvDialog.Builder(host).setPageId("wallpaper_qr_active").setTitle("扫码导入壁纸")
                        .setMessage("手机已连接到 Marco 互联。\n你可以直接在手机浏览器「壁纸」页上传照片或 MP4 动态壁纸；也可以重新生成二维码连接另一台手机。")
                        .setItems(new String[]{"断开并重新扫码连接新手机","关闭手机连接"},(d,w)->{
                    stop();
                    if(w==0)startServerAndShowQr(1);
                    else host.toast("手机连接已关闭");
                }).setNegativeButton("返回",null).create());
                return;
            }
            qr(1);
            return;
        }
        startServerAndShowQr(1);
    }
    void showCardCoverImport(Tile tile){
        if(tile==null)return;
        initialTab="desktop";
        initialCardId=tile.id;
        initialCardLabel=tile.label;
        if(active()){
            if(approved){
                host.showDialog(new TvDialog.Builder(host).setPageId("card_qr_active").setTitle("扫码上传封面 · "+tile.label)
                        .setMessage("手机已连接到 Marco 互联。\n你可以在手机浏览器「桌面」页直接点击「"+tile.label+"」上传图片封面或自定义长宽高；也可以重新生成二维码连接另一台手机。")
                        .setItems(new String[]{"断开并重新扫码连接新手机","关闭手机连接"},(d,w)->{
                    stop();
                    if(w==0){
                        initialTab="desktop";
                        initialCardId=tile.id;
                        initialCardLabel=tile.label;
                        startServerAndShowQr(2);
                    }else host.toast("手机连接已关闭");
                }).setNegativeButton("返回",null).create());
                return;
            }
            qr(2);
            return;
        }
        startServerAndShowQr(2);
    }
    private void startServerAndShowQr(int mode){
        String address=address();
        if(address==null){
            host.toast("请先连接可用的 Wi-Fi 或有线局域网");
            return;
        }
        try{
            pairCode=secret();
            nonce="";
            clientAddress="";
            cookie=secret();
            csrf=secret();
            approved=false;
            pairUntil=SystemClock.elapsedRealtime()+5*60000;
            until=SystemClock.elapsedRealtime()+15*60000;
            // Bind only the chosen physical interface. It is not a VPN or public relay.
            server=new PhoneHttp(InetAddress.getByName(address),0,new File(host.getCacheDir(),"phone-transfer"),this);
            authority=address+":"+server.port();
            closed=false;
            generation++;
            host.armIdle();
            if(host.cast!=null)host.cast.syncReceiver();
            host.handler.post(timeout);
            qr(mode);
        }catch(Exception e){
            stop();
            host.toast(mode!=0?"暂时无法开启扫码上传，请检查网络连接后重试":"暂时无法开启Marco 互联，请重新连接网络后重试");
        }
    }
    private String address(){
        try{
            ConnectivityManager cm=(ConnectivityManager)host.getSystemService(Context.CONNECTIVITY_SERVICE);
            for(Network n:cm.getAllNetworks()){
                NetworkCapabilities caps=cm.getNetworkCapabilities(n);
                LinkProperties link=cm.getLinkProperties(n);
                if(caps==null||link==null||caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)||!(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)))continue;
                for(LinkAddress a:link.getLinkAddresses()){
                    InetAddress ip=a.getAddress();
                    if(ip instanceof Inet4Address&&ip.isSiteLocalAddress())return ip.getHostAddress();
                }
            }
        }catch(Exception ignored){}
        return null;
    }
    private void qr(int mode){
        boolean wallpaperMode=mode==1;
        boolean cardMode=mode==2;
        if(approved){
            host.toast("本次已配对；连接新手机请先断开并重新开启");
            return;
        }
        if(SystemClock.elapsedRealtime()>pairUntil){
            pairCode=secret();
            nonce="";
            clientAddress="";
            pairUntil=SystemClock.elapsedRealtime()+5*60000;
        }
        String query="/";
        if(wallpaperMode)query="/?tab=wallpaper";
        else if(cardMode&&!initialCardId.isEmpty()){
            try{
                query="/?tab=desktop&card="+URLEncoder.encode(initialCardId,"UTF-8");
            }catch(Exception ignored){}
        }
        String url="http://"+authority+query+"#"+pairCode;
        QrCode q=QrCode.encodeText(url,QrCode.Ecc.MEDIUM);
        int side=q.size+8,scale=6;
        Bitmap bmp=Bitmap.createBitmap(side*scale,side*scale,Bitmap.Config.RGB_565);
        Canvas c=new Canvas(bmp);
        c.drawColor(Color.WHITE);
        Paint p=new Paint();
        p.setColor(Color.BLACK);
        for(int y=0;y<q.size;y++)for(int x=0;x<q.size;x++)if(q.getModule(x,y))c.drawRect((x+4)*scale,(y+4)*scale,(x+5)*scale,(y+5)*scale,p);
        LinearLayout box=new LinearLayout(host);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(android.view.Gravity.CENTER_VERTICAL);
        ImageView image=new ImageView(host);
        image.setImageBitmap(bmp);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription(wallpaperMode?"用手机相机扫描导入壁纸":cardMode?"用手机相机扫描上传卡片封面":"用手机相机扫描连接电视");
        box.addView(image,new LinearLayout.LayoutParams(dp(210),dp(210)));
        LinearLayout instructions=new LinearLayout(host);
        instructions.setOrientation(LinearLayout.VERTICAL);
        instructions.setPadding(dp(28),0,0,0);
        TextView intro=new TextView(host);
        intro.setTextColor(Color.WHITE);
        intro.setTextSize(21);
        intro.setText(wallpaperMode?"照片与动态壁纸，扫码直传。":cardMode?"卡片封面与尺寸，扫码直调。":"你的电视，随手整理。");
        intro.setPadding(0,0,0,dp(12));
        instructions.addView(intro);
        TextView text=new TextView(host);
        text.setTextColor(0xFFCBD6E2);
        text.setTextSize(15);
        text.setLineSpacing(dp(5),1);
        text.setText(wallpaperMode
                ?"1  手机与电视连接同一 Wi-Fi\n2  手机相机扫码，浏览器直接进入壁纸页\n3  在电视确认允许后，选择照片或 MP4 视频上传\n\n支持静态壁纸（≤ 50 MB）与 MP4 / WebM 动态壁纸（≤ 80 MB）\n上传后自动保存到「我的壁纸」并立即设为桌面壁纸\n二维码 5 分钟有效 · 无需安装手机 App"
                :cardMode
                ?"1  手机与电视连接同一 Wi-Fi\n2  手机相机扫码，直接打开「"+initialCardLabel+"」卡片编辑页\n3  在电视确认允许后，选图裁切上传或调整长宽高\n\n支持 JPG / PNG / WebP（≤ 50 MB）\n支持所有应用与信号源卡片 · 上传后电视即时生效\n二维码 5 分钟有效 · 无需安装手机 App"
                :"1  手机与电视连接同一网络\n2  用手机相机扫码，浏览器打开\n3  在电视上确认允许连接\n\n整理卡片 · 上传封面 · 调整长宽高 · 投屏与壁纸\n同时已开启 DLNA，视频 App 可直接投屏\n二维码 5 分钟有效 · 无需手机 App");
        instructions.addView(text);
        box.addView(instructions,new LinearLayout.LayoutParams(0,-2,1));
        TvDialog.Builder builder=new TvDialog.Builder(host).centered()
                .setPageId(wallpaperMode?"wallpaper_qr":cardMode?"card_qr":"phone_qr")
                .setTitle(wallpaperMode?"扫码导入壁纸":cardMode?"扫码上传封面 · "+initialCardLabel:"Marco 互联 · 扫码连接")
                .setView(box)
                .setPositiveButton("返回",null);
        if(mode==0)builder.setNeutralButton("无线投屏",(d,w)->host.cast.show());
        final String manualUrl="http://"+authority+query;
        builder.setNeutralButton("手动连接",(d,w)->host.showDialog(new TvDialog.Builder(host)
                .setPageId("phone_manual").setTitle("手动连接电视")
                .setMessage("手机浏览器打开："+manualUrl+"\n\n连接码："+pairCode+"\n\n请勿将连接码分享给他人；电视确认后才会授权访问。")
                .setNegativeButton("返回二维码",null).create()))
                .setNegativeButton("关闭连接",(d,w)->{
            stop();
            if(wallpaperMode&&host.dialogs.hasPage("wallpaper"))host.dialogs.popTo("wallpaper");
            else host.exitMenus();
        });
        host.showDialog(builder.create());
    }
    private int dp(float value){
        return Math.round(value*host.getResources().getDisplayMetrics().density);
    }
    void stop(){
        closed=true;
        generation++;
        approved=false;
        nonce="";
        clientAddress="";
        pairCode="";
        cookie="";
        csrf="";
        initialCardId="";
        initialCardLabel="";
        host.handler.removeCallbacks(timeout);
        clearPreview();
        if(server!=null){
            server.close();
            server=null;
        }
        if(host.cast!=null)host.cast.syncReceiver();
        host.armIdle();
    }
    void destroy(){
        stop();
        for(String g:BackupStore.GROUPS)host.getSharedPreferences(g,0).unregisterOnSharedPreferenceChangeListener(changed);
    }
    private static String secret(){
        byte[] b=new byte[16];
        new SecureRandom().nextBytes(b);
        return BackupStore.hex(b);
    }
    private static boolean secretEquals(String expected,String actual){
        if(expected==null||actual==null||expected.isEmpty())return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),actual.getBytes(StandardCharsets.UTF_8));
    }
    private boolean authorized(PhoneHttp.Request r){
        if(!approved||cookie.isEmpty()||clientAddress.isEmpty()||!clientAddress.equals(r.remoteAddress))return false;
        for(String part:r.header("cookie").split(";\\s*")){
            if(part.startsWith("MarcoSession=")&&secretEquals(cookie,part.substring(13)))return true;
        }
        return false;
    }
    public PhoneHttp.Response check(PhoneHttp.Request r)throws Exception{
        if(closed||SystemClock.elapsedRealtime()>until)return fail(410,"连接已过期，请在电视重新开启Marco 互联");
        if(!authority.equals(r.header("host")))return fail(403,"无效的访问地址");
        if(r.method.equals("POST")&&!r.header("origin").equals("http://"+authority))return fail(403,"请从电视提供的页面操作");
        if(r.path.equals("/")||r.path.equals("/phone.js")||r.path.equals("/phone.css"))return r.method.equals("GET")?null:fail(405,"不支持的操作");
        if(r.path.equals("/pair"))return r.method.equals("POST")?null:fail(405,"不支持的操作");
        if(!authorized(r))return fail(401,"请扫码并在电视确认连接");
        if(r.method.equals("POST")&&!secretEquals(csrf,r.header("x-marco-csrf")))return fail(403,"连接校验失败，请刷新");
        return null;
    }
    public PhoneHttp.Response handle(PhoneHttp.Request r)throws Exception{
        PhoneHttp.Response denied=check(r);
        if(denied!=null)return denied;
        if(r.path.equals("/")||r.path.equals("/phone.js")||r.path.equals("/phone.css")){
            String name=r.path.equals("/")?"index.html":r.path.substring(1);
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream in=host.getAssets().open("phone/"+name)){
                byte[] b=new byte[8192];
                int n;
                while((n=in.read(b))!=-1)out.write(b,0,n);
            }
            return new PhoneHttp.Response(200,name.endsWith("js")?"text/javascript; charset=utf-8":name.endsWith("css")?"text/css; charset=utf-8":"text/html; charset=utf-8",
                    out.toByteArray());
        }
        if(r.path.equals("/pair"))return main(()->pair(r,new JSONObject(r.text())));
        if(r.method.equals("POST"))until=SystemClock.elapsedRealtime()+15*60000;
        if(r.method.equals("GET")){
            switch(r.path){
                case "/state":
                    return main(()->json(state()));
                case "/art":
                    return art(r.param("id"));
                case "/apps":
                    return apps();
                case "/entries":
                    return entries(r.param("id"));
                case "/diagnostics":
                    return PhoneHttp.Response.text(200,main(()->host.health.reportHeader())+Diagnostics.recent(host));
                case "/backup":
                    return backup();
                default:
                    return fail(404,"页面不存在");
            }
        }
        if(r.path.equals("/upload"))return upload(r);
        if(r.path.equals("/upload-cover"))return uploadCover(r);
        if(r.path.equals("/upload-video"))return uploadVideo(r);
        if(r.path.equals("/import-video-url"))return importVideoUrl(r);
        if(r.path.equals("/restore"))return restore(r);
        if(r.path.equals("/cast"))return castMedia(r);
        if(!r.path.equals("/action"))return fail(404,"操作不存在");
        JSONObject body=new JSONObject(r.text());
        return main(()->action(body));
    }
    private PhoneHttp.Response pair(PhoneHttp.Request r,JSONObject b)throws Exception{
        if(closed||SystemClock.elapsedRealtime()>pairUntil||!secretEquals(pairCode,b.optString("code")))return fail(403,"二维码已过期或连接码不正确");
        String supplied=b.optString("nonce");
        if(!supplied.matches("[a-f0-9]{32}")||r.remoteAddress.isEmpty())return fail(400,"请重新打开连接页面");
        if(nonce.isEmpty()){
            if(!host.foreground)return fail(409,"请先回到 Marco TV 桌面");
            nonce=supplied;
            clientAddress=r.remoteAddress;
            final String expected=nonce,expectedIp=clientAddress;
            TvDialog approveDialog=new TvDialog.Builder(host).setPageId("phone_approve").setTitle("允许手机通过 Marco 互联连接？")
                    .setMessage("请求设备："+expectedIp+"\n仅允许你刚刚扫码的手机连接。\n手机可读取应用卡片、调整外观、上传壁纸和下载备份。\n无需 ADB；可随时在“Marco 互联”断开。")
                    .setPositiveButton("允许连接",(d,w)->{
                if(!closed&&nonce.equals(expected)&&clientAddress.equals(expectedIp)){
                    approved=true;
                    if(host.cast!=null)host.cast.trustIp(expectedIp);
                    if("wallpaper".equals(initialTab)&&host.dialogs.popTo("wallpaper")){
                        host.toast("手机已连接，请在手机上传图片或 MP4 动态壁纸");
                    }else if(!initialCardId.isEmpty()){
                        host.exitMenus();
                        host.toast("手机已连接，请在手机编辑「"+initialCardLabel+"」封面与尺寸");
                    }else{
                        host.exitMenus();
                        host.toast("手机已连接，未保存的预览不会写入配置");
                    }
                }
            })
                    .setNegativeButton("拒绝",(d,w)->stop()).create();
            approveDialog.onLeave(()->{
                if(!approved&&nonce.equals(expected)){
                    nonce="";
                    clientAddress="";
                }
            });
            host.showDialog(approveDialog);
        }
        if(!secretEquals(nonce,supplied)||!clientAddress.equals(r.remoteAddress))return fail(409,"已有手机正在连接，请在电视重新开启");
        PhoneHttp.Response result=json(new JSONObject().put("status",approved?"connected":"pending").put("csrf",approved?csrf:""));
        if(approved){
            pairCode="";
            result.headers.put("Set-Cookie","MarcoSession="+cookie+"; HttpOnly; SameSite=Strict; Path=/");
        }
        return result;
    }
    private JSONObject state()throws Exception{
        JSONArray rows=new JSONArray();
        Set<String> visible=new HashSet<>();
        for(Row row:host.home.rows){
            JSONArray list=new JSONArray();
            for(Tile t:row.tiles){
                list.put(tile(t));
                visible.add(t.id);
            }
            rows.put(new JSONObject().put("id",row.orderKey).put("name",row.title).put("tiles",list));
        }
        JSONArray hidden=new JSONArray();
        for(Tile t:host.allTiles)if(host.cards.contains("hidden",t.id))hidden.put(tile(t));
        JSONArray settings=new JSONArray();
        for(int i=0;i<KEYS.length;i++){
            int[] range=BackupStore.range(KEYS[i]);
            settings.put(new JSONObject().put("key",KEYS[i]).put("label",LABELS[i]).put("min",range[0])
                    .put("max",range[1]).put("value",host.appearance().getInt(KEYS[i],DEFAULTS[i])).put("saved",host.preferences.getInt(KEYS[i],
                    DEFAULTS[i])));
        }
        JSONArray myWallpapers=new JSONArray();
        String activeWp=WallpaperLibrary.activeId(host.preferences);
        if(host.wallpaperLibrary!=null){
            for(WallpaperLibrary.Entry e:host.wallpaperLibrary.list()){
                myWallpapers.put(new JSONObject()
                        .put("id",e.id)
                        .put("title",e.title)
                        .put("video",e.video)
                        .put("durationSec",e.durationSec)
                        .put("active",e.unifiedId().equals(activeWp)));
            }
        }
        JSONObject cardDefaults=new JSONObject()
                .put("width",host.appearance().getInt("card_width",100))
                .put("height",host.appearance().getInt("card_height",100))
                .put("depth",host.appearance().getInt("card_depth",0))
                .put("radius",host.appearance().getInt("card_radius",32));
        return new JSONObject().put("version",ProjectInfo.VERSION).put("model",Build.MANUFACTURER+" "+Build.MODEL)
                .put("revision",revision).put("csrf",csrf).put("initialTab",initialTab).put("initialCard",initialCardId)
                .put("cardDefaults",cardDefaults)
                .put("rows",rows).put("hidden",hidden).put("settings",settings).put("preview",previewing())
                .put("wallpapers",myWallpapers).put("activeWallpaper",activeWp)
                .put("sort",host.preferences.getString("tv_sort","recent"))
                .put("cast",host.cast==null?new JSONObject():host.cast.statusJson())
                .put("freeMB",host.getFilesDir().getUsableSpace()/1048576).put("foreground",host.foreground);
    }
    private JSONObject tile(Tile t)throws Exception{
        CardStore.Dimensions d=host.cards.dimensions(t.id);
        return new JSONObject().put("id",t.id).put("name",host.cards.name(t.id,t.label)).put("kind",t.kind)
                .put("cover",host.cards.cover(t.id))
                .put("width",d.width).put("height",d.height).put("depth",d.depth).put("radius",d.radius)
                .put("pinned",host.cards.contains("pinned",t.id))
                .put("hidden",host.cards.contains("hidden",t.id));
    }
    private Tile find(String id){
        for(Tile t:host.allTiles)if(t.id.equals(id))return t;
        return null;
    }
    private PhoneHttp.Response action(JSONObject b)throws Exception{
        if(closed||!approved)return fail(401,"连接已关闭");
        String op=b.getString("op");
        if(op.equals("disconnect")){
            approved=false;
            clearPreview();
            host.handler.postDelayed(()->stop(),300);
            return json(new JSONObject().put("message","连接已关闭"));
        }
        if(op.equals("cancel")){
            clearPreview();
            return json(state());
        }
        if(op.equals("cast_url")){
            if(!host.foreground)return fail(409,"请先回到 Marco TV 桌面");
            String url=b.optString("url","").trim();
            if(!CastProtocol.isAllowedMediaUri(url)||url.length()>2048)return fail(400,"请输入有效的 http 或 https 视频链接");
            String title=b.optString("title","手机网页投屏").trim();
            if(title.length()>80)title=title.substring(0,80);
            if(host.cast!=null)host.cast.playVideo(url,title,clientAddress,true);
            return json(state());
        }
        if(op.equals("cast_control")){
            String cmd=b.optString("cmd","");
            if(!host.foreground&&!("stop".equals(cmd)||"pause".equals(cmd)))return fail(409,"请先回到 Marco TV 桌面");
            if(host.cast!=null)host.cast.remoteControl(cmd);
            return json(state());
        }
        if(b.optLong("revision",-1)!=revision)return fail(409,"电视上的设置已变化，请刷新后再操作");
        if(!host.foreground)return fail(409,"请先回到 Marco TV 桌面");
        if(host.home.isMoving())return fail(409,"电视正在移动卡片，请完成后重试");
        if(host.localPreview!=null&&(op.equals("preview")||op.equals("save")||op.equals("cancel")))return fail(409,"电视正在调整外观，请先在电视保存或取消");
        if(op.equals("preview")){
            JSONObject values=b.getJSONObject("values");
            Map<String,Integer> next=new HashMap<>();
            for(Iterator<String> it=values.keys();it.hasNext();){
                String k=it.next();
                if(!Arrays.asList(KEYS).contains(k))return fail(400,"不支持的设置");
                Object raw=values.get(k);
                int v=values.getInt(k);
                int[] range=BackupStore.range(k);
                if(!(raw instanceof Number)||((Number)raw).doubleValue()!=v||v<range[0]||v>range[1])return fail(400,"参数超出范围");
                next.put(k,v);
            }
            host.previewPreferences.values.clear();
            host.previewPreferences.values.putAll(next);
            ambientPreview=b.optString("key").startsWith("ambient_");
            previewAt=SystemClock.elapsedRealtime();
            host.home.setIdle(false);
            host.home.refreshAppearance();
            if(next.containsKey("card_width")){
                host.home.ensureVisible();
                host.home.animateFocus();
            }
            host.home.invalidate();
            return json(state());
        }
        ownEdit=true;
        try{
            if(op.equals("save")){
                SharedPreferences.Editor e=host.preferences.edit();
                for(Map.Entry<String,Integer> v:host.previewPreferences.values.entrySet())e.putInt(v.getKey(),v.getValue());
                if(!e.commit())return fail(500,"保存失败，请重试");
                clearPreview();
            }
            else if(op.equals("sort")){
                String mode=b.getString("mode");
                if(!Arrays.asList(AppOrder.MODES).contains(mode))return fail(400,"不支持的排序");
                host.preferences.edit().putString("tv_sort",mode).apply();
                host.sortCurrentApps();
            }
            else if(op.equals("reorder")){
                String group=b.getString("group");
                Row row=null;
                for(Row r:host.home.rows)if(r.orderKey.equals(group))row=r;
                if(row==null)return fail(409,"分类已变化，请刷新");
                JSONArray ids=b.getJSONArray("ids");
                Set<String> expected=new HashSet<>();
                for(Tile t:row.tiles)expected.add(t.id);
                if(ids.length()!=expected.size())return fail(409,"应用列表已变化，请刷新");
                StringBuilder order=new StringBuilder();
                ArrayList<Tile> sorted=new ArrayList<>();
                boolean unpinned=false;
                for(int i=0;i<ids.length();i++){
                    String id=ids.getString(i);
                    if(!expected.remove(id))return fail(400,"排序包含重复或未知卡片");
                    Tile t=find(id);
                    if(t==null)return fail(409,"应用列表已变化");
                    if(!t.pinned)unpinned=true;
                    else if(unpinned&&group.equals("order_tv"))return fail(400,"置顶卡片必须排在普通卡片前");
                    if(i>0)order.append('|');
                    order.append(id);
                    for(Tile original:row.tiles)if(original.id.equals(id))sorted.add(original);
                }
                String selected=row.tiles.isEmpty()?null:row.tiles.get(Math.min(row.selected,row.tiles.size()-1)).id;
                SharedPreferences.Editor e=host.preferences.edit().putString(group,order.toString());
                if(group.equals("order_tv"))e.putString("tv_sort","custom");
                e.apply();
                row.tiles=sorted;
                row.selected=0;
                for(int i=0;i<sorted.size();i++)if(sorted.get(i).id.equals(selected))row.selected=i;
                host.home.ensureVisible();
                host.home.animateFocus();
            }else if(op.equals("card")){
                Tile t=find(b.getString("id"));
                if(t==null)return fail(409,"卡片已变化，请刷新");
                String field=b.getString("field");
                if(field.equals("name")){
                    String name=b.getString("value").trim();
                    if(name.length()>60)return fail(400,"名称最多 60 字");
                    host.cards.setName(t.id,name);
                    if(name.isEmpty()){
                        host.loadApplications();
                    }else{
                        t.label=name;
                        t.displayLabel=null;
                        t.coverLabel=null;
                        for(Row row:host.home.rows)for(Tile copy:row.tiles)if(copy.id.equals(t.id)){
                            copy.label=name;
                            copy.displayLabel=null;
                            copy.coverLabel=null;
                        }
                    }
                }
                else if(field.equals("pinned")||field.equals("hidden")){
                    host.cards.toggle(field,t.id,b.getBoolean("value"));
                    t.pinned=host.cards.contains("pinned",t.id);
                    host.loadApplications();
                }
                else if(field.equals("size")){
                    int w=b.optInt("width",0),h=b.optInt("height",0),d=b.optInt("depth",-1),rad=b.optInt("radius",-1);
                    if((w!=0&&(w<CardStore.MIN_WIDTH||w>CardStore.MAX_WIDTH))
                            ||(h!=0&&(h<CardStore.MIN_HEIGHT||h>CardStore.MAX_HEIGHT))
                            ||(d!=-1&&(d<CardStore.MIN_DEPTH||d>CardStore.MAX_DEPTH))
                            ||(rad!=-1&&(rad<CardStore.MIN_RADIUS||rad>CardStore.MAX_RADIUS)))return fail(400,"尺寸参数超出范围");
                    CardStore.Dimensions dim=new CardStore.Dimensions();
                    dim.width=w;
                    dim.height=h;
                    dim.depth=d;
                    dim.radius=rad;
                    host.cards.setDimensions(t.id,dim);
                    host.catalog.applyDimensions(t.id,dim);
                }
                else if(field.equals("cover")){
                    String style=b.optString("value","");
                    if(!Arrays.asList("original","blue","green","amber","purple").contains(style))return fail(400,"不支持的封面样式");
                    host.cards.cover(t.id,style);
                    host.loadApplications();
                }
                else if(field.equals("reset")){
                    host.cards.reset(t.id);
                    host.loadApplications();
                }
                else return fail(400,"不支持的卡片设置");
            }else if(op.equals("add")){
                ComponentName component=ComponentName.unflattenFromString(b.getString("component"));
                if(component==null||component.getPackageName().equals(host.getPackageName()))return fail(400,"无效入口");
                ActivityInfo info=host.getPackageManager().getActivityInfo(component,PackageManager.MATCH_DISABLED_COMPONENTS);
                if(!AppEntries.launchable(host,info))return fail(409,"入口不可用或受权限限制");
                host.cards.manual(info.packageName,component.flattenToString());
                host.cards.toggle("hidden",info.packageName,false);
                host.loadApplications();
            }else if(op.equals("wallpaper")){
                int n=b.getInt("index");
                if(n<0||n>=WallpaperSource.ASSETS.length)return fail(400,"无效壁纸");
                WallpaperLibrary.applyBuiltin(host,n);
                notifyWallpaperChanged();
            }else if(op.equals("wallpaper_entry")){
                String id=b.optString("id","");
                WallpaperLibrary.Entry found=findWallpaperEntry(id);
                if(found==null)return fail(404,"壁纸不存在，请刷新");
                host.wallpaperLibrary.applyEntry(host,found);
                notifyWallpaperChanged();
            }else if(op.equals("wallpaper_delete")){
                String id=b.optString("id","");
                boolean wasActive=("imported:"+id).equals(WallpaperLibrary.activeId(host.preferences));
                if(!host.wallpaperLibrary.delete(id))return fail(404,"壁纸不存在");
                if(wasActive)host.loadWallpaper();
                notifyWallpaperChanged();
            }
            else return fail(400,"不支持的操作");
            revision++;
            host.home.invalidate();
            return json(state());
        }finally{
            ownEdit=false;
        }
    }
    private PhoneHttp.Response art(String id)throws Exception{
        Bitmap b=main(()->{
            Tile t=find(id);
            return t==null||t.artwork==null||t.artwork.isRecycled()?null:t.artwork.copy(Bitmap.Config.RGB_565,false);
        });
        if(b==null)return PhoneHttp.Response.text(404,"");
        try{
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG,80,out);
            return new PhoneHttp.Response(200,"image/jpeg",out.toByteArray());
        }finally{
            b.recycle();
        }
    }
    private PhoneHttp.Response apps()throws Exception{
        JSONArray apps=new JSONArray();
        PackageManager pm=host.getPackageManager();
        for(ApplicationInfo a:pm.getInstalledApplications(0)){
            if(a.packageName.equals(host.getPackageName())||!a.enabled)continue;
            apps.put(new JSONObject().put("id",a.packageName).put("name",String.valueOf(a.loadLabel(pm))));
        }
        return json(new JSONObject().put("apps",apps));
    }
    private PhoneHttp.Response entries(String id)throws Exception{
        if(id.length()>250)return fail(400,"无效应用");
        AppEntries.Scan scan=AppEntries.scan(host,id);
        JSONArray entries=new JSONArray();
        for(ActivityInfo a:scan.all.values())entries.put(new JSONObject().put("component",new ComponentName(a.packageName,a.name)
                .flattenToString()).put("name",String.valueOf(a.loadLabel(host.getPackageManager()))));
        return json(new JSONObject().put("entries",entries).put("message",scan.explanation()));
    }
    private PhoneHttp.Response backup()throws Exception{
        if(!transfer.compareAndSet(false,true))return fail(409,"正在处理文件，请稍候");
        File target=File.createTempFile("phone-backup-",".zip",host.getCacheDir());
        boolean delivered=false;
        try{
            JSONObject config=main(()->{
                if(previewing())throw new IOException("请先保存或取消预览");
                return BackupStore.capture(host,true);
            });
            BackupStore.write(host,target,config);
            PhoneHttp.Response r=PhoneHttp.Response.file(target,"application/zip",true);
            r.headers.put("Content-Disposition","attachment; filename=MarcoTV-backup.zip");
            delivered=true;
            return r;
        }
        finally{
            transfer.set(false);
            if(!delivered)target.delete();
        }
    }
    private WallpaperLibrary.Entry findWallpaperEntry(String id){
        if(host.wallpaperLibrary==null||id==null||id.isEmpty())return null;
        for(WallpaperLibrary.Entry e:host.wallpaperLibrary.list())if(id.equals(e.id))return e;
        return null;
    }
    private void notifyWallpaperChanged(){
        Runnable listener=wallpaperImportedListener;
        if(listener!=null)listener.run();
    }
    private static String decodeHeaderName(String raw,String fallback){
        if(raw==null||raw.isEmpty())return fallback;
        try{
            String decoded=URLDecoder.decode(raw,"UTF-8");
            return WallpaperLibrary.cleanTitle(decoded,fallback);
        }catch(Exception ignored){
            return fallback;
        }
    }
    private PhoneHttp.Response upload(PhoneHttp.Request r)throws Exception{
        if(!transfer.compareAndSet(false,true))return fail(409,"正在处理文件，请稍候");
        WallpaperLibrary.Entry imported=null;
        try{
            if(r.body==null)return fail(400,"请选择图片");
            long expected=Long.parseLong(r.header("x-marco-revision"));
            String title=decodeHeaderName(r.header("x-marco-wallpaper-name"),"手机壁纸");
            try{
                imported=host.wallpaperLibrary.importImage(r.body,title);
            }catch(IOException error){
                return fail(400,error.getMessage()==null?"图片无法读取":error.getMessage());
            }
            final WallpaperLibrary.Entry entry=imported;
            PhoneHttp.Response res=main(()->{
                if(closed||!approved)return fail(401,"连接已关闭");
                if(host.localPreview!=null)return fail(409,"电视正在调整外观，请先退出后再上传");
                if(expected!=revision||!host.foreground)return fail(409,"电视状态已变化，请刷新后上传");
                ownEdit=true;
                try{
                    host.wallpaperLibrary.applyEntry(host,entry);
                    revision++;
                    notifyWallpaperChanged();
                    host.toast("已导入并应用壁纸："+entry.title);
                    return json(state());
                }catch(IOException error){
                    return fail(500,"壁纸保存失败");
                }finally{
                    ownEdit=false;
                }
            });
            if(res.code==200)imported=null;
            return res;
        }finally{
            if(imported!=null)host.wallpaperLibrary.delete(imported.id);
            transfer.set(false);
        }
    }
    private PhoneHttp.Response uploadCover(PhoneHttp.Request r)throws Exception{
        if(!transfer.compareAndSet(false,true))return fail(409,"正在处理文件，请稍候");
        Bitmap prepared=null;
        try{
            if(r.body==null||r.length<=0)return fail(400,"请选择图片");
            long expected=Long.parseLong(r.header("x-marco-revision"));
            String rawId=r.header("x-marco-card-id");
            if(rawId.isEmpty())return fail(400,"缺少卡片标识");
            String cardId;
            try{
                cardId=URLDecoder.decode(rawId,"UTF-8");
            }catch(Exception e){
                return fail(400,"无效卡片标识");
            }
            if(cardId.isEmpty()||cardId.length()>500)return fail(400,"无效卡片标识");
            String precheck=main(()->{
                if(closed||!approved)return "401";
                if(expected!=revision||!host.foreground)return "409";
                Tile found=find(cardId);
                return found==null?"404":"ok:"+found.label;
            });
            if("401".equals(precheck))return fail(401,"连接已关闭");
            if("409".equals(precheck))return fail(409,"电视状态已变化，请刷新后上传");
            if("404".equals(precheck))return fail(404,"卡片不存在，请刷新后重试");
            final String tileName=precheck.substring(3);
            try{
                prepared=host.cards.saveImageFileOnly(cardId,r.body);
            }catch(IOException error){
                return fail(400,error.getMessage()==null?"图片无法读取":error.getMessage());
            }
            final Bitmap bmp=prepared;
            PhoneHttp.Response res=main(()->{
                if(closed||!approved)return fail(401,"连接已关闭");
                if(expected!=revision||!host.foreground)return fail(409,"电视状态已变化，请刷新后上传");
                ownEdit=true;
                try{
                    host.cards.markImageCover(cardId,bmp);
                    host.catalog.applyCoverImage(cardId,bmp);
                    revision++;
                    host.toast("已更新卡片封面："+tileName);
                    return json(state());
                }finally{
                    ownEdit=false;
                }
            });
            if(res.code==200)prepared=null;
            return res;
        }finally{
            if(prepared!=null&&!prepared.isRecycled())prepared.recycle();
            transfer.set(false);
        }
    }
    private PhoneHttp.Response uploadVideo(PhoneHttp.Request r)throws Exception{
        if(!transfer.compareAndSet(false,true))return fail(409,"正在处理文件，请稍候");
        WallpaperLibrary.Entry imported=null;
        try{
            if(r.body==null||r.length<=0)return fail(400,"请选择 MP4 / WebM 视频");
            long expected=Long.parseLong(r.header("x-marco-revision"));
            String rawFile=r.header("x-marco-file-name");
            String fileName="";
            if(!rawFile.isEmpty())try{fileName=URLDecoder.decode(rawFile,"UTF-8");}catch(Exception ignored){}
            String title=decodeHeaderName(r.header("x-marco-wallpaper-name"),
                    fileName.isEmpty()?"动态壁纸":WallpaperLibrary.cleanTitle(fileName,"动态壁纸"));
            try{
                imported=host.wallpaperLibrary.importVideo(r.body,title,fileName);
            }catch(IOException error){
                return fail(400,error.getMessage()==null?"视频无法识别，请使用 MP4（H.264）格式":error.getMessage());
            }
            final WallpaperLibrary.Entry entry=imported;
            PhoneHttp.Response res=main(()->{
                if(closed||!approved)return fail(401,"连接已关闭");
                if(host.localPreview!=null)return fail(409,"电视正在调整外观，请先退出后再上传");
                if(expected!=revision||!host.foreground)return fail(409,"电视状态已变化，请刷新后上传");
                ownEdit=true;
                try{
                    host.wallpaperLibrary.applyEntry(host,entry);
                    revision++;
                    notifyWallpaperChanged();
                    host.toast("已导入动态壁纸："+entry.title);
                    return json(state());
                }catch(IOException error){
                    return fail(500,"动态壁纸保存失败");
                }finally{
                    ownEdit=false;
                }
            });
            if(res.code==200)imported=null;
            return res;
        }finally{
            if(imported!=null)host.wallpaperLibrary.delete(imported.id);
            transfer.set(false);
        }
    }
    private PhoneHttp.Response importVideoUrl(PhoneHttp.Request r)throws Exception{
        if(!transfer.compareAndSet(false,true))return fail(409,"正在处理文件，请稍候");
        File temp=null;
        WallpaperLibrary.Entry imported=null;
        try{
            JSONObject body=new JSONObject(r.text());
            long expected=body.optLong("revision",-1);
            String url=body.optString("url","").trim();
            if(!WallpaperCatalogPolicy.userVideoImportUrl(url))return fail(400,"请输入有效的 http 或 https 视频直链");
            String rawTitle=body.optString("title","").trim();
            String fileName="remote.mp4";
            try{
                String path=new URI(url).getPath();
                if(path!=null){
                    int slash=path.lastIndexOf('/');
                    String tail=slash>=0?path.substring(slash+1):path;
                    if(!tail.isEmpty())fileName=tail;
                }
            }catch(Exception ignored){}
            String title=WallpaperLibrary.cleanTitle(rawTitle.isEmpty()?fileName:rawTitle,"动态壁纸");
            if(host.getCacheDir().getUsableSpace()<WallpaperLibrary.MAX_VIDEO_BYTES+16L*1024*1024){
                return fail(507,"电视可用存储空间不足");
            }
            temp=File.createTempFile("phone-vidurl-",".part",host.getCacheDir());
            try{
                downloadUserVideo(url,temp);
            }catch(IOException error){
                return fail(400,error.getMessage()==null?"下载视频失败，请检查链接是否可直接访问":error.getMessage());
            }
            try{
                imported=host.wallpaperLibrary.importVideo(temp,title,fileName);
            }catch(IOException error){
                return fail(400,error.getMessage()==null?"视频无法识别，请使用标准 MP4（H.264）链接":error.getMessage());
            }
            final WallpaperLibrary.Entry entry=imported;
            PhoneHttp.Response res=main(()->{
                if(closed||!approved)return fail(401,"连接已关闭");
                if(host.localPreview!=null)return fail(409,"电视正在调整外观，请先退出后再导入");
                if(expected!=revision||!host.foreground)return fail(409,"电视状态已变化，请刷新后重试");
                ownEdit=true;
                try{
                    host.wallpaperLibrary.applyEntry(host,entry);
                    revision++;
                    notifyWallpaperChanged();
                    host.toast("已导入动态壁纸："+entry.title);
                    return json(state());
                }catch(IOException error){
                    return fail(500,"动态壁纸保存失败");
                }finally{
                    ownEdit=false;
                }
            });
            if(res.code==200)imported=null;
            return res;
        }finally{
            if(temp!=null&&temp.exists())temp.delete();
            if(imported!=null)host.wallpaperLibrary.delete(imported.id);
            transfer.set(false);
        }
    }
    private void downloadUserVideo(String startUrl,File target)throws IOException{
        String current=startUrl;
        HttpURLConnection conn=null;
        try{
            for(int hop=0;hop<5;hop++){
                if(closed)throw new IOException("连接已关闭");
                if(!WallpaperCatalogPolicy.userVideoImportUrl(current))throw new IOException("不支持跳转至该地址");
                conn=(HttpURLConnection)new URL(current).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent","MarcoTV/"+ProjectInfo.VERSION);
                conn.setRequestProperty("Accept-Encoding","identity");
                int code=conn.getResponseCode();
                if(code==301||code==302||code==303||code==307||code==308){
                    String location=conn.getHeaderField("Location");
                    conn.disconnect();
                    conn=null;
                    if(location==null||location.isEmpty())throw new IOException("跳转地址无效");
                    current=new URL(new URL(current),location).toString();
                    continue;
                }
                if(code!=200)throw new IOException("视频下载失败（HTTP "+code+"）");
                long cl=conn.getContentLengthLong();
                if(cl>WallpaperLibrary.MAX_VIDEO_BYTES)throw new IOException("视频文件超过 80 MB 限制");
                long total=0;
                try(InputStream in=conn.getInputStream();OutputStream out=new FileOutputStream(target)){
                    byte[] buf=new byte[16384];
                    int n;
                    while((n=in.read(buf))>=0){
                        if(closed)throw new IOException("连接已关闭");
                        total+=n;
                        if(total>WallpaperLibrary.MAX_VIDEO_BYTES)throw new IOException("视频文件超过 80 MB 限制");
                        out.write(buf,0,n);
                    }
                }
                if(total<=0)throw new IOException("链接返回内容为空");
                return;
            }
            throw new IOException("视频链接跳转次数过多");
        }finally{
            if(conn!=null)conn.disconnect();
        }
    }
    private PhoneHttp.Response restore(PhoneHttp.Request r)throws Exception{
        if(!transfer.compareAndSet(false,true))return fail(409,"正在处理文件，请稍候");
        try{
            long expected=Long.parseLong(r.header("x-marco-revision"));
            if(r.body==null)return fail(400,"请选择备份");
            // The existing TV restore flow validates, shows the summary and asks on TV before any write.
            File copy=File.createTempFile("phone-restore-",".zip",host.getCacheDir());
            try(InputStream in=new FileInputStream(r.body);OutputStream out=new FileOutputStream(copy)){
                byte[] block=new byte[16384];
                int n;
                while((n=in.read(block))!=-1)out.write(block,0,n);
            }
            try{
                return main(()->{
                    if(closed||!approved||expected!=revision||!host.foreground||host.localPreview!=null){
                        copy.delete();
                        return fail(409,"电视状态已变化，请重试");
                    }
                    clearPreview();
                    host.backups.fromPhone(copy);
                    return json(new JSONObject().put("message","请在电视查看校验结果并确认恢复"));
                });
            }
            catch(Exception e){
                copy.delete();
                throw e;
            }
        }finally{
            transfer.set(false);
        }
    }
    private PhoneHttp.Response castMedia(PhoneHttp.Request r)throws Exception{
        if(!transfer.compareAndSet(false,true))return fail(409,"正在处理文件，请稍候");
        try{
            if(r.body==null||r.length<=0)return fail(400,"请选择照片或视频文件");
            String kind=r.header("x-marco-cast-kind");
            String rawName=r.header("x-marco-cast-name");
            String title="手机快投";
            if(!rawName.isEmpty())try{
                title=URLDecoder.decode(rawName,"UTF-8").trim();
                if(title.length()>80)title=title.substring(0,80);
            }catch(Exception ignored){}
            final String castTitle=title;
            final String ip=r.remoteAddress;
            if("video".equals(kind)){
                File dest=new File(host.getCacheDir(),"cast-upload-"+System.currentTimeMillis()+".mp4");
                if(!r.body.renameTo(dest)){
                    try(InputStream in=new FileInputStream(r.body);OutputStream out=new FileOutputStream(dest)){
                        byte[] block=new byte[32768];
                        int n;
                        while((n=in.read(block))!=-1)out.write(block,0,n);
                    }
                }
                return main(()->{
                    if(closed||!approved||host.cast==null){
                        dest.delete();
                        return fail(401,"连接已关闭");
                    }
                    if(!host.foreground){
                        dest.delete();
                        return fail(409,"请先回到 Marco TV 桌面");
                    }
                    host.cast.stageAndPlayFile(dest,castTitle,ip);
                    return json(state());
                });
            }
            if(r.length>24L*1024*1024)return fail(400,"照片请小于 24 MB");
            BitmapFactory.Options o=new BitmapFactory.Options();
            o.inJustDecodeBounds=true;
            BitmapFactory.decodeFile(r.body.getPath(),o);
            if(o.outWidth<1||o.outHeight<1||o.outWidth>8192||o.outHeight>8192||(long)o.outWidth*o.outHeight>36000000)return fail(400,"图片无法识别或像素过大");
            o.inJustDecodeBounds=false;
            o.inSampleSize=1;
            while(o.outWidth/o.inSampleSize>1920||o.outHeight/o.inSampleSize>1080)o.inSampleSize*=2;
            o.inPreferredConfig=Bitmap.Config.ARGB_8888;
            final Bitmap bmp=BitmapFactory.decodeFile(r.body.getPath(),o);
            if(bmp==null)return fail(400,"图片无法读取");
            return main(()->{
                if(closed||!approved||host.cast==null){
                    bmp.recycle();
                    return fail(401,"连接已关闭");
                }
                if(!host.foreground){
                    bmp.recycle();
                    return fail(409,"请先回到 Marco TV 桌面");
                }
                host.cast.showPhoto(bmp,castTitle,ip);
                return json(state());
            });
        }finally{
            transfer.set(false);
        }
    }
    private <T>T main(Callable<T> call)throws Exception{
        final long expected=generation;
        FutureTask<T> task=new FutureTask<>(()->{
            if(closed||expected!=generation)throw new IOException("session closed");
            return call.call();
        });
        host.handler.post(task);
        try{
            return task.get(8,TimeUnit.SECONDS);
        }catch(Exception e){
            task.cancel(false);
            throw e;
        }
    }
    private static PhoneHttp.Response json(JSONObject o){
        return new PhoneHttp.Response(200,"application/json; charset=utf-8",o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static PhoneHttp.Response fail(int code,String text)throws Exception{
        return jsonError(code,text);
    }
    private static PhoneHttp.Response jsonError(int code,String text)throws Exception{
        PhoneHttp.Response r=json(new JSONObject().put("error",text));
        r.code=code;
        return r;
    }
}
