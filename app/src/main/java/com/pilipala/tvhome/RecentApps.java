// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.animation.ValueAnimator;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.*;
import java.util.*;

/** Real system recent tasks through the optional shell bridge. Never synthesizes usage history. */
final class RecentApps {
    private final MainActivity host;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Typeface bold=Typeface.create("sans-serif",Typeface.BOLD),normal=Typeface.create("sans-serif",Typeface.NORMAL);
    private final RectF rect=new RectF();
    static final class Entry {int taskId;String id,name;Bitmap icon,snapshot,artwork;boolean removable;int background=0xFF243541;BitmapShader artworkShader;final Matrix artworkMatrix=new Matrix();}
    final ArrayList<Entry> entries=new ArrayList<>();
    boolean visible;int selected;private float position;private int generation;private boolean loading;private boolean canRemove;private boolean bridgeConnected;
    private String bridgeStatus="辅助服务尚未连接";
    private ValueAnimator animation,removalAnimation;private boolean removing,dragging;private float touchX,touchY,dragY;private int removingTask=-1;
    private ValueAnimator entranceAnimation,backdropAnimation;
    private float reveal=1,backdrop=1;private boolean entrancePending;
    RecentApps(MainActivity h){host=h;}
    boolean enabled(){return host.preferences.getBoolean("recent_enabled",false);}
    int value(String key,int fallback){return host.appearance().getInt("recent_"+key,fallback);}
    private int count(){return Math.min(entries.size(),value("limit",10));}
    void show(){host.exitMenus();selected=0;stop();visible=true;host.home.setIdle(false);reveal=0;backdrop=0;entrancePending=true;
        backdropAnimation=ValueAnimator.ofFloat(0,1);backdropAnimation.setDuration(Math.min(240,value("duration",280)));
        backdropAnimation.addUpdateListener(a->{backdrop=(float)a.getAnimatedValue();host.home.invalidate();});backdropAnimation.start();
        if(!entries.isEmpty())enter();load();describe();host.home.invalidate();}
    void close(){visible=false;generation++;loading=false;stop();host.home.describeFocus();host.home.invalidate();host.armIdle();}
    void stop(){if(animation!=null)animation.cancel();if(removalAnimation!=null)removalAnimation.cancel();if(entranceAnimation!=null)entranceAnimation.cancel();if(backdropAnimation!=null)backdropAnimation.cancel();entrancePending=false;reveal=1;backdrop=1;removing=false;removingTask=-1;dragY=0;position=selected;}
    private void enter(){entrancePending=false;if(entranceAnimation!=null)entranceAnimation.cancel();
        entranceAnimation=ValueAnimator.ofFloat(0,1);entranceAnimation.setDuration(value("duration",280));entranceAnimation.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
        entranceAnimation.addUpdateListener(a->{reveal=(float)a.getAnimatedValue();host.home.invalidate();});entranceAnimation.start();}
    void load(){final int token=++generation;loading=true;
        host.loader.execute(()->{ArrayList<Entry> result=new ArrayList<>();PackageManager pm=host.getPackageManager();String status;boolean removable=false,connected=false;
            try{org.json.JSONObject state=TaskBridgeClient.configure(host);connected=true;removable=state.optBoolean("canRemove",false);org.json.JSONArray tasks=TaskBridgeClient.request("list").getJSONArray("tasks");status="已连接系统任务服务 · "+state.optString("lastWake");
                for(int i=0;i<tasks.length()&&result.size()<20;i++){
                    org.json.JSONObject task=tasks.getJSONObject(i);String id=task.getString("package");
                    try{ApplicationInfo app=pm.getApplicationInfo(id,0);if(!app.enabled)continue;Entry e=new Entry();e.taskId=task.getInt("id");e.id=id;e.removable=task.optBoolean("removable",false);e.name=String.valueOf(app.loadLabel(pm));
                        Drawable icon=app.loadIcon(pm);e.icon=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);icon.setBounds(0,0,128,128);icon.draw(new Canvas(e.icon));e.artwork=ArtworkStyle.card(icon);e.background=ArtworkStyle.background(e.icon);
                        try{org.json.JSONObject shot=TaskBridgeClient.task("snapshot",e.taskId);if(shot.optBoolean("available")){byte[] bytes=android.util.Base64.decode(shot.getString("image"),android.util.Base64.DEFAULT);e.snapshot=BitmapFactory.decodeByteArray(bytes,0,bytes.length);}}catch(Exception ignored){}
                        result.add(e);
                    }catch(PackageManager.NameNotFoundException ignored){}
                }
            }catch(Exception error){status="任务服务未连接 · 按确定查看恢复方法";Diagnostics.event("TASK_BRIDGE_UNAVAILABLE");}
            final String message=status;final boolean allowRemove=removable,available=connected;
            host.handler.post(()->{if(host.isDestroyed()||generation!=token)return;entries.clear();entries.addAll(result);bridgeStatus=message;bridgeConnected=available;canRemove=allowRemove;selected=0;position=0;loading=false;if(visible&&entrancePending)enter();describe();host.home.invalidate();});
        });
    }
    void describe(){if(visible)host.home.setContentDescription("最近任务，"+(entries.isEmpty()?(loading?"正在读取":"暂无任务"):entries.get(selected).name+"，"+(selected+1)+" / "+count())+"，左右切换，确定返回任务，菜单键设置，返回桌面");}
    void move(int direction){if(entries.isEmpty()||removing)return;selected=Math.max(0,Math.min(count()-1,selected+direction));if(animation!=null)animation.cancel();
        animation=ValueAnimator.ofFloat(position,selected);animation.setDuration(value("duration",280));animation.setInterpolator(new android.view.animation.DecelerateInterpolator());
        animation.addUpdateListener(a->{position=(float)a.getAnimatedValue();host.home.invalidate();});animation.start();describe();}
    void open(){if(entries.isEmpty()){if(!loading)recovery();return;}if(removing)return;Entry e=entries.get(selected);final int token=generation;host.loader.execute(()->{try{TaskBridgeClient.task("switch",e.taskId);host.handler.post(()->{if(generation==token&&!host.isDestroyed())close();});}catch(Exception error){Diagnostics.event("TASK_SWITCH_FAILED");host.handler.post(()->{if(generation!=token||host.isDestroyed())return;host.toast("任务已结束或系统不支持切换，请刷新");load();});}});}
    void remove(){
        if(entries.isEmpty()||removing)return;Entry entry=entries.get(selected);
        if(!entry.removable){dragY=0;host.home.invalidate();host.toast("此任务暂不支持单独清除");return;}
        final int token=++generation;loading=false;removing=true;removingTask=entry.taskId;
        host.loader.execute(()->{try{TaskBridgeClient.task("remove",entry.taskId);host.handler.post(()->{
            if(generation!=token||host.isDestroyed())return;
            removalAnimation=ValueAnimator.ofFloat(dragY,-900);removalAnimation.setDuration(220);removalAnimation.setInterpolator(new android.view.animation.AccelerateInterpolator());
            removalAnimation.addUpdateListener(a->{dragY=(float)a.getAnimatedValue();host.home.invalidate();});
            removalAnimation.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator a){
                if(generation!=token||host.isDestroyed())return;for(Iterator<Entry> it=entries.iterator();it.hasNext();)if(it.next().taskId==entry.taskId)it.remove();selected=Math.max(0,Math.min(selected,count()-1));position=selected;dragY=0;removing=false;removingTask=-1;describe();host.home.invalidate();host.toast("已清除任务");
            }});removalAnimation.start();
        });}catch(Exception error){host.handler.post(()->{if(generation!=token||host.isDestroyed())return;removing=false;removingTask=-1;dragY=0;host.home.invalidate();host.toast("系统未允许清除此任务，已保留卡片");});}});
    }
    boolean key(int code,KeyEvent event){
        if(!visible)return false;
        if(event.getAction()==KeyEvent.ACTION_DOWN){
            if(code==KeyEvent.KEYCODE_DPAD_LEFT)move(-1);else if(code==KeyEvent.KEYCODE_DPAD_RIGHT)move(1);
            else if(event.getRepeatCount()==0){if(code==KeyEvent.KEYCODE_BACK)close();else if(code==KeyEvent.KEYCODE_MENU)settings();else if(code==KeyEvent.KEYCODE_DPAD_UP)remove();}
        }else if(code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER)open();
        return true;
    }
    void touch(int action,float x,float y){
        if(removing)return;
        if(action==MotionEvent.ACTION_DOWN){touchX=x;touchY=y;dragging=x>580&&x<1340&&y>230&&y<850;dragY=0;return;}
        if(action==MotionEvent.ACTION_CANCEL){dragging=false;dragY=0;host.home.invalidate();return;}
        if(action==MotionEvent.ACTION_MOVE){if(dragging){dragY=Math.min(0,y-touchY);host.home.invalidate();}return;}
        if(action!=MotionEvent.ACTION_UP)return;
        float dx=x-touchX,dy=y-touchY;boolean swipe=dragging&&dy<-110&&-dy>Math.abs(dx)*1.2f;dragging=false;
        if(swipe){remove();return;}dragY=0;host.home.invalidate();
        if(Math.abs(dx)>110&&Math.abs(dx)>Math.abs(dy)){move(dx<0?1:-1);return;}
        if(Math.abs(dx)>35||Math.abs(dy)>35)return;
        if(y<200){close();return;}if(x<650)move(-1);else if(x>1270)move(1);else open();
    }
    void draw(Canvas c,boolean preview){
        paint.setShader(null);paint.setColor(Color.argb(Math.round(value("dim",65)*2.55f*(preview?1:backdrop)),0,0,0));c.drawRect(0,0,1920,1080,paint);
        text(c,"最近任务",112,120,46,bold,0xFFF5F7FA,Paint.Align.LEFT);
        text(c,"系统任务 · 返回上次停留的页面",112,168,24,normal,0xBBDCE3EC,Paint.Align.LEFT);
        if(entries.isEmpty()){text(c,loading?"正在读取系统任务…":bridgeConnected?"暂无可显示任务":"需要连接系统任务服务",960,485,32,normal,0xFFD8E1EA,Paint.Align.CENTER);text(c,bridgeStatus,960,545,23,normal,0xBBC7D5E4,Paint.Align.CENTER);}
        else{
            ArrayList<Integer> drawOrder=new ArrayList<>();for(int i=0;i<count();i++)if(Math.abs(i-position)<4)drawOrder.add(i);
            Collections.sort(drawOrder,(a,b)->Float.compare(Math.abs(b-position),Math.abs(a-position)));
            for(int i:drawOrder)card(c,entries.get(i),i-position,preview);
        }
        if(!preview){text(c,"左右切换  ·  确定返回任务  ·  "+(!entries.isEmpty()&&entries.get(selected).removable?"上滑 / 上键清除":(entries.isEmpty()?"确定查看恢复选项":"此任务暂不支持清除"))+"  ·  菜单键自定义  ·  返回桌面",960,976,25,normal,0xFFE2E8EF,Paint.Align.CENTER);
            text(c,"Marco TV  ·  Pilipala",1808,1030,19,normal,0x99DCE3EC,Paint.Align.RIGHT);}
    }
    private void card(Canvas c,Entry e,float offset,boolean preview){
        float progress=preview?1:reveal;
        float abs=Math.abs(offset),size=value("size",100)/100f,zoom=size*(1-Math.min(.28f,abs*.10f))*(.78f+.22f*progress);
        float cx=960+offset*value("spacing",210)*size*(.12f+.88f*progress),cy=555+Math.min(42,abs*15)*size+165*size*(1-progress),w=570*zoom,h=550*zoom;
        int save=c.save();if(e.taskId==removingTask||(dragging&&abs<.5f))c.translate(0,dragY);c.rotate(Math.max(-1,Math.min(1,offset))*value("tilt",7)*progress,cx,cy);
        float radius=value("radius",36)*zoom;rect.set(cx-w/2+10,cy-h/2+16,cx+w/2+10,cy+h/2+16);paint.setColor(0x66000000);c.drawRoundRect(rect,radius,radius,paint);
        rect.set(cx-w/2,cy-h/2,cx+w/2,cy+h/2);paint.setColor(Color.WHITE);paint.setShader(new LinearGradient(cx,cy-h/2,cx,cy+h/2,new int[]{0xFF34485E,0xFF172331},null,Shader.TileMode.CLAMP));c.drawRoundRect(rect,radius,radius,paint);paint.setShader(null);
        if(abs<.5f){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3);paint.setColor(0xDDE0EDFA);c.drawRoundRect(rect,radius,radius,paint);paint.setStyle(Paint.Style.FILL);}
        paint.setColor(Color.WHITE);paint.setAlpha(255);
        RectF frame=new RectF(cx-w/2+20*zoom,cy-h/2+20*zoom,cx+w/2-20*zoom,cy+72*zoom);
        Bitmap visual=e.snapshot!=null?e.snapshot:e.artwork;
        // Draw the curved image boundary with Paint antialiasing. Hardware clipPath
        // can leave stair-stepped edges on older TV GPUs, especially after rotation.
        if(visual!=null){if(e.artworkShader==null)e.artworkShader=new BitmapShader(visual,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
            float ratio=Math.min(frame.width()/visual.getWidth(),frame.height()/visual.getHeight());
            e.artworkMatrix.setScale(ratio,ratio);e.artworkMatrix.postTranslate(frame.centerX()-visual.getWidth()*ratio/2,frame.centerY()-visual.getHeight()*ratio/2);
            e.artworkShader.setLocalMatrix(e.artworkMatrix);paint.setColor(Color.WHITE);paint.setShader(e.artworkShader);
        }else paint.setColor(e.background);
        float imageRadius=Math.max(8,radius-12*zoom);c.drawRoundRect(frame,imageRadius,imageRadius,paint);paint.setShader(null);
        text(c,fit(e.name,w-64*zoom,34*zoom),cx,cy+140*zoom,34*zoom,bold,0xFFF4F7FA,Paint.Align.CENTER);
        text(c,e.snapshot==null?"应用 Logo · 确定返回任务":"确定返回此任务",cx,cy+190*zoom,21*zoom,normal,0xFFCED8E4,Paint.Align.CENTER);c.restoreToCount(save);
    }
    private String fit(String s,float width,float size){paint.setTypeface(bold);paint.setTextSize(size);if(paint.measureText(s)<=width)return s;int end=s.length();while(end>0&&paint.measureText(s.substring(0,end)+"…")>width)end=s.offsetByCodePoints(end,-1);return s.substring(0,end)+"…";}
    private void text(Canvas c,String s,float x,float y,float size,Typeface face,int color,Paint.Align align){paint.setShader(null);paint.setColor(color);paint.setTypeface(face);paint.setTextSize(size);paint.setTextAlign(align);c.drawText(s,x,y,paint);}
    void recovery(){
        host.showDialog(new TvDialog.Builder(host).setPageId("task_recovery").setTitle("最近任务连接")
            .setMessage("完整任务列表需要系统任务服务。电视重启、应用更新或系统清理后，服务可能断开；这不代表没有后台任务。\n\n重新连接：电脑打开安装说明中的“启动任务服务”工具，选择电视并完成授权。随后回到这里重新检测。")
            .setItems(new String[]{"重新检测连接","打开系统最近任务","Home 按键兼容设置","查看状态中心"},(d,w)->{
                if(w==0){host.exitMenus();load();}
                else if(w==1){if(HomeKeyService.openSystemRecents()){close();host.exitMenus();}else host.toast("系统未提供此入口，请检查无障碍服务或使用原厂 Home 按键模式");}
                else if(w==2)SystemIntegration.homeGestures(host,host::showDialog);
                else host.health.status();
            }).setNegativeButton("返回",null).create());
    }
    void settings(){
        host.showDialog(new TvDialog.Builder(host).setPageId("recents").setTitle("最近任务")
            .setOrder("recents","enabled","open","style","double","status")
            .setMessage("双击功能："+(enabled()?"已开启":"未开启")+"。需要默认桌面或 Home 兼容，以及已启动的任务辅助服务。上滑或上键清除所选任务，未保存的页面可能丢失。唤醒恢复请到系统与启动设置。")
            .setItems(new String[]{enabled()?"关闭双击 Home":"开启双击 Home","立即打开最近任务","任务卡片与动画","双击间隔","状态中心"},(d,w)->{
                switch(w){case 0:host.preferences.edit().putBoolean("recent_enabled",!enabled()).apply();settings();break;
                    case 1:show();break;case 2:styleSettings();break;case 3:slider("双击间隔","double_ms",200,700,400," 毫秒");break;case 4:host.health.status();break;}
            }).setNegativeButton("返回",null).create());
    }
    void styleSettings(){if(entries.isEmpty())load();host.showDialog(new TvDialog.Builder(host).setPageId("task_style").setTitle("任务卡片与动画")
        .setOrder("task_style","size","spacing","tilt","radius","dim","duration","limit")
        .setItems(new String[]{"任务卡片大小","任务卡片间距","任务卡片倾斜","任务卡片圆角","任务背景压暗程度","展开与切换动画时长","显示数量"},(d,w)->{
            switch(w){case 0:slider("任务卡片大小","size",60,120,100,"%");break;case 1:slider("任务卡片间距","spacing",80,360,210,"");break;
                case 2:slider("任务卡片倾斜","tilt",0,18,7,"°");break;case 3:slider("任务卡片圆角","radius",8,80,36,"");break;
                case 4:slider("任务背景压暗程度","dim",0,90,65,"%");break;case 5:slider("展开与切换动画时长","duration",120,700,280," 毫秒");break;
                case 6:slider("显示数量","limit",3,20,10," 个");break;}
        }).setNegativeButton("返回",null).create());}
    private void slider(String title,String key,int min,int max,int fallback,String unit){
        host.navigator.slider(title,"recent_"+key,min,max,fallback,unit);
    }
}
