// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.Toast;
import java.util.*;
import java.io.*;
import org.json.*;

/** Separate API-14 entry point. Never loads full-edition services or permissions. */
public final class LiteActivity extends Activity {
    SharedPreferences prefs;LiteView home;ArrayList<App> apps=new ArrayList<App>();
    final Handler handler=new Handler();boolean alive;int loadGeneration;String status="正在读取应用…";
    static final class App {String id,label;Intent intent;Bitmap icon;boolean pinned;long opened;}
    @Override public void onCreate(Bundle state){super.onCreate(state);prefs=getSharedPreferences("lite_home",0);home=new LiteView(this);setContentView(home);}
    @Override protected void onResume(){super.onResume();alive=true;home.resume();loadApps();}
    @Override protected void onPause(){alive=false;home.pause();super.onPause();}
    @Override protected void onDestroy(){loadGeneration++;home.release();super.onDestroy();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);home.closePage();home.wake();}
    void toast(String text){Toast.makeText(this,text,Toast.LENGTH_SHORT).show();}
    void loadApps(){
        final int generation=++loadGeneration;final String selected=home.selectedId();final Map<String,?> saved=prefs.getAll();
        new Thread(new Runnable(){public void run(){
            final ArrayList<App> found=new ArrayList<App>();HashSet<String> packages=new HashSet<String>();PackageManager pm=getPackageManager();
            try{
                ArrayList<ResolveInfo> matches=new ArrayList<ResolveInfo>();
                for(String category:new String[]{"android.intent.category.LEANBACK_LAUNCHER",Intent.CATEGORY_LAUNCHER})matches.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category),0));
                for(ResolveInfo r:matches){ActivityInfo a=r.activityInfo;if(a==null||!a.enabled||!a.exported||!a.applicationInfo.enabled||a.packageName.equals(getPackageName())||packages.contains(a.packageName))continue;
                    packages.add(a.packageName);if(Boolean.TRUE.equals(saved.get("hidden:"+a.packageName)))continue;
                    App app=new App();app.id=a.packageName;app.label=r.loadLabel(pm).toString();app.intent=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(new ComponentName(a.packageName,a.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    app.pinned=Boolean.TRUE.equals(saved.get("pinned:"+app.id));Object opened=saved.get("opened:"+app.id);app.opened=opened instanceof Long?(Long)opened:0;found.add(app);
                }
                Collections.sort(found,new Comparator<App>(){public int compare(App a,App b){if(a.pinned!=b.pinned)return a.pinned?-1:1;if(!Boolean.TRUE.equals(saved.get("alphabetic"))&&a.opened!=b.opened)return a.opened>b.opened?-1:1;return a.label.compareToIgnoreCase(b.label);}});
                // Bound decoded icons independently of installed application count.
                for(int i=0;i<Math.min(64,found.size());i++)try{App a=found.get(i);Drawable d=pm.getApplicationIcon(a.id);a.icon=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888);int w=Math.max(1,d.getIntrinsicWidth()),h=Math.max(1,d.getIntrinsicHeight());float f=84f/Math.max(w,h);d.setBounds((int)(48-w*f/2),(int)(48-h*f/2),(int)(48+w*f/2),(int)(48+h*f/2));d.draw(new Canvas(a.icon));}catch(Exception ignored){}
            }catch(Exception failure){handler.post(new Runnable(){public void run(){if(generation==loadGeneration)toast("读取应用失败，可在设置中刷新重试");}});}
            handler.post(new Runnable(){public void run(){if(generation!=loadGeneration||isFinishing()){recycle(found);return;}ArrayList<App> old=apps;apps=found;home.selectId(selected);status=found.isEmpty()?"暂无可启动应用，可在设置中恢复隐藏应用":"";home.invalidate();recycle(old);}});
        }},"MarcoLite-apps").start();
    }
    static void recycle(List<App> list){for(App a:list)if(a.icon!=null&&!a.icon.isRecycled())a.icon.recycle();}
    void launch(App app){try{startActivity(app.intent);prefs.edit().putLong("opened:"+app.id,System.currentTimeMillis()).apply();}catch(Exception e){toast("系统未允许打开该应用，请尝试原厂桌面");}}
    void systemSettings(){try{startActivity(new Intent(Settings.ACTION_SETTINGS));}catch(Exception e){toast("此设备未提供系统设置入口");}}
    void licenses(){
        StringBuilder text=new StringBuilder("Marco TV Lite · Pilipala\n免费开源 · GPL-3.0-only\n\n");
        try{for(String name:getAssets().list("licenses")){text.append("\n—— ").append(name).append(" ——\n");BufferedReader reader=new BufferedReader(new InputStreamReader(getAssets().open("licenses/"+name),"UTF-8"));try{String line;while((line=reader.readLine())!=null)text.append(line).append('\n');}finally{reader.close();}}}catch(Exception e){text.append("许可文件读取失败，请查看随包源码 LICENSE 与 licenses 目录。");}
        android.widget.TextView body=new android.widget.TextView(this);body.setText(text);body.setTextSize(16);body.setTextColor(Color.WHITE);body.setPadding(24,24,24,24);
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.setBackgroundColor(0xFF192C3B);scroll.addView(body);scroll.setFocusable(true);
        new android.app.AlertDialog.Builder(this).setTitle("开源许可").setView(scroll).setPositiveButton("返回",null).show();
    }
    void backup(boolean restore){
        File file=new File(getFilesDir(),"lite-backup.json");
        try{
            if(!restore){JSONObject o=new JSONObject();o.put("format","MarcoTV-Lite");o.put("schema",1);JSONObject values=new JSONObject();
                for(int i=0;i<LiteSettings.KEYS.length;i++)values.put(LiteSettings.KEYS[i],LiteSettings.clamp(i,prefs.getInt(LiteSettings.KEYS[i],LiteSettings.DEFAULT[i])));
                values.put("wallpaper",prefs.getInt("wallpaper",0));o.put("appearance",values);
                File temp=new File(getFilesDir(),"lite-backup.tmp");FileOutputStream out=new FileOutputStream(temp);try{out.write(o.toString().getBytes("UTF-8"));out.getFD().sync();}finally{out.close();}if(!temp.renameTo(file))throw new IOException("rename");toast("外观备份已保存在本机；卸载会删除");
            }else{
                if(!file.exists()){toast("还没有本机外观备份");return;}if(file.length()>16384)throw new IOException("size");FileInputStream in=new FileInputStream(file);ByteArrayOutputStream data=new ByteArrayOutputStream();try{byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1){data.write(b,0,n);if(data.size()>16384)throw new IOException("size");}}finally{in.close();}
                JSONObject o=new JSONObject(data.toString("UTF-8"));if(!"MarcoTV-Lite".equals(o.getString("format"))||o.getInt("schema")!=1)throw new IOException("format");JSONObject values=o.getJSONObject("appearance");int[] checked=new int[LiteSettings.KEYS.length];
                for(int i=0;i<checked.length;i++){Object raw=values.get(LiteSettings.KEYS[i]);if(!(raw instanceof Integer))throw new IOException("type");checked[i]=(Integer)raw;if(checked[i]!=LiteSettings.clamp(i,checked[i]))throw new IOException("range");}int wall=values.getInt("wallpaper");if(wall<0||wall>1)throw new IOException("wallpaper");
                SharedPreferences.Editor edit=prefs.edit();for(int i=0;i<checked.length;i++)edit.putInt(LiteSettings.KEYS[i],checked[i]);edit.putInt("wallpaper",wall).commit();home.loadWallpaper();home.invalidate();toast("本机外观已恢复");
            }
        }catch(Exception e){toast("备份或恢复失败，原配置保持不变");}
    }
    void appAction(int item){if(apps.isEmpty())return;App a=apps.get(home.selected);
        if(item==0){prefs.edit().putBoolean("pinned:"+a.id,!a.pinned).apply();loadApps();}
        else if(item==1){prefs.edit().putBoolean("hidden:"+a.id,true).apply();loadApps();}
        else if(item==2)try{startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+a.id)));}catch(Exception e){systemSettings();}
        home.closePage();
    }

    final class LiteView extends View {
        final LiteActivity host;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);final RectF rect=new RectF();final ClockScene clock;
        final MotionSpring scroll=new MotionSpring(0,18,.08f);Bitmap wallpaper;PreviewPreferences draft;
        int selected,top=0,page=0,choice=0;boolean topFocus=false,ambient=false,running=false,longPress=false,wakeKey=false;long touched,lastFrame;
        float ambientAmount=0;final MotionSpring saver=new MotionSpring(0,10,.001f);
        final String[] menu={"Magic Desktop","更换内置壁纸","应用排序","恢复所有隐藏应用","系统设置 / 网络","保存本机外观备份","恢复本机外观备份","设备与兼容状态","关于与开源许可"};
        final Runnable frame=new Runnable(){public void run(){if(!running)return;long now=SystemClock.uptimeMillis();float dt=Math.min(.06f,(now-lastFrame)/1000f);lastFrame=now;boolean moving=scroll.step(dt)|saver.step(dt);ambientAmount=saver.value;invalidate();if(moving)postDelayed(this,16);}};
        final Runnable tick=new Runnable(){public void run(){if(!running)return;int seconds=prefs.getInt("idle_seconds",60);if(page==0&&!ambient&&seconds>0&&SystemClock.uptimeMillis()-touched>seconds*1000L){ambient=true;saver.target(1);scheduleMotion();}invalidate();postDelayed(this,1000);}};
        LiteView(LiteActivity h){super(h);host=h;clock=new ClockScene(h);setFocusable(true);setFocusableInTouchMode(true);requestFocus();loadWallpaper();wake();}
        void resume(){running=true;wake();removeCallbacks(tick);post(tick);}
        void pause(){running=false;removeCallbacks(frame);removeCallbacks(tick);closePage();}
        void release(){pause();if(wallpaper!=null)wallpaper.recycle();recycle(apps);}
        void wake(){touched=SystemClock.uptimeMillis();ambient=false;saver.target(0);scheduleMotion();}
        void scheduleMotion(){if(!running)return;if(prefs.getInt("motion",1)==0){scroll.snap(scroll.target);saver.snap(saver.target);ambientAmount=saver.value;invalidate();return;}removeCallbacks(frame);lastFrame=SystemClock.uptimeMillis();post(frame);}
        String selectedId(){return apps.isEmpty()?null:apps.get(Math.min(selected,apps.size()-1)).id;}
        void selectId(String id){selected=0;if(id!=null)for(int i=0;i<apps.size();i++)if(apps.get(i).id.equals(id)){selected=i;break;}scroll.snap(LiteSettings.scroll(selected));}
        void loadWallpaper(){Bitmap next=null;try{BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=2;o.inPreferredConfig=Bitmap.Config.RGB_565;InputStream in=getAssets().open("wallpapers/"+(prefs.getInt("wallpaper",0)==0?"aurora.png":"coast.png"));try{next=BitmapFactory.decodeStream(in,null,o);}finally{in.close();}}catch(Exception ignored){}Bitmap old=wallpaper;wallpaper=next;if(old!=null)old.recycle();invalidate();}
        SharedPreferences appearance(){return draft==null?prefs:draft;}
        void open(int which){wake();page=which;choice=0;if(which==2)draft=new PreviewPreferences(prefs);invalidate();}
        void closePage(){draft=null;page=0;choice=0;invalidate();}
        void box(Canvas c,float l,float t,float r,float b,float radius,int color){p.setShader(null);p.setColor(color);rect.set(l,t,r,b);c.drawRoundRect(rect,radius,radius,p);}
        void text(Canvas c,String s,float x,float y,float size,int color,boolean bold){p.setShader(null);p.setColor(color);p.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));p.setTextSize(size);p.setTextAlign(Paint.Align.LEFT);c.drawText(s,x,y,p);}
        String trim(String s,float width,float size){p.setTextSize(size);p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));if(p.measureText(s)<=width)return s;int n=s.length();while(n>0&&p.measureText(s.substring(0,n)+"…")>width)n--;return s.substring(0,n)+"…";}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);canvas.drawColor(0xFF08121D);float scale=Math.min(getWidth()/1920f,getHeight()/1080f);canvas.save();canvas.translate((getWidth()-1920*scale)/2,(getHeight()-1080*scale)/2);canvas.scale(scale,scale);
            if(page==2){box(canvas,30,170,1250,870,30,0xFF243743);canvas.save();canvas.translate(40,180);canvas.scale(.625f,.625f);scene(canvas,0);canvas.restore();panel(canvas);}else{scene(canvas,ambientAmount);if(page>0)panel(canvas);}canvas.restore();
        }
        void scene(Canvas c,float idle){
            c.save();c.clipRect(0,0,1920,1080);
            SharedPreferences a=appearance();if(wallpaper!=null){rect.set(0,0,1920,1080);p.setAlpha(255);c.drawBitmap(wallpaper,null,rect,p);}else c.drawColor(0xFF122B40);
            p.setColor(Color.argb(ClockScene.dim(a,idle),0,0,0));c.drawRect(0,0,1920,1080,p);clock.draw(c,a,idle);if(idle>.8f){c.restore();return;}
            text(c,"Marco TV Lite",112,115,32,0xFFDCECF1,true);text(c,"by Pilipala · 老电视兼容实验版",112,150,20,0xFF9AB3C1,false);
            if(topFocus&&top==0)box(c,410,70,560,145,16,0xFF36576B);text(c,"设置",442,119,28,0xFFE6F1F6,false);
            if(topFocus&&top==1)box(c,590,70,875,145,16,0xFF36576B);text(c,"Magic Desktop",617,119,28,0xFFE6F1F6,false);
            float h=LiteSettings.height(a.getInt("card_height",100)),y=LiteSettings.center(a.getInt("card_row_position",75),a.getInt("card_height",100));
            text(c,"TV 应用",112,y-h/2-75,32,Color.WHITE,true);
            if(apps.isEmpty())text(c,status,112,y,28,0xFFC2D3DF,false);
            for(int i=0;i<apps.size();i++){float x=272+i*350-scroll.value;if(x<-220||x>2140)continue;App app=apps.get(i);boolean focus=i==selected&&!topFocus;float z=focus?1.08f:1,cy=y-(focus?10:0);float radius=LiteSettings.clamp(2,a.getInt("card_radius",32));
                box(c,x-160*z,cy-h*z/2,x+160*z,cy+h*z/2,radius,focus?0xFF466578:0xFF263C50);
                if(app.icon!=null&&!app.icon.isRecycled()){float sz=Math.min(112,h-24)*z;rect.set(x-sz/2,cy-sz/2,x+sz/2,cy+sz/2);p.setAlpha(255);c.drawBitmap(app.icon,null,rect,p);}else text(c,app.label.isEmpty()?"?":app.label.substring(0,1),x-22,cy+20,54,Color.WHITE,true);
                if(focus){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3);box(c,x-160*z,cy-h*z/2,x+160*z,cy+h*z/2,radius,0xFFC4E7ED);p.setStyle(Paint.Style.FILL);text(c,trim(app.label,315,30),x-160,y+h/2+65,30,Color.WHITE,true);}
                if(app.pinned)text(c,"置顶",x+86,cy-h/2+30,18,0xFFFFD894,false);
            }
            text(c,"↑ 设置   ← → 选择   确定打开   长按确定管理",112,980,24,0xFF96AEBE,false);
            c.restore();
        }
        int count(){return page==1?menu.length:page==2?LiteSettings.KEYS.length+3:page==3?3:1;}
        String value(int i){int v=appearance().getInt(LiteSettings.KEYS[i],LiteSettings.DEFAULT[i]);return i==3?ClockScene.STYLES[LiteSettings.clamp(i,v)]:i==10?(v==0?"关闭":"开启"):Integer.toString(v)+(i<2?"%":i==4?"°":"");}
        void panel(Canvas c){
            float left=page==2?1290:1080,width=1920-left-50;box(c,left,45,1870,1035,30,0xFF1A2836);text(c,page==2?"Magic Desktop":page==3?"应用管理":page==4?"设备与兼容状态":page==5?"Marco TV Lite":"设置",left+32,113,36,Color.WHITE,true);
            if(page==4||page==5){String[] lines=page==4?new String[]{"版本："+LiteSettings.VERSION+" · Lite","设备："+Build.MANUFACTURER+" / "+Build.MODEL,"Android "+Build.VERSION.RELEASE+" · API "+Build.VERSION.SDK_INT,"画面："+getWidth()+" × "+getHeight(),"安装与基础操作：以测试报告为准","原厂 Home / 开机启动：待真机验证","系统最近任务 / 播放卡片：本版未提供","网络与手机互联：本版未提供","默认桌面选择由电视系统决定"}:new String[]{"作者 Pilipala · 0.1.0","免费开源 · GPL-3.0-only","如果付费购买，请尽快申请退款。","Lite 是独立应用，不覆盖 Marco TV。","内置四款数字字体，SIL OFL 1.1。","Roboto Flex · Barlow Condensed","Nunito · Bodoni Moda","按确定查看随包开源许可全文","本版无联网权限，不自动上传数据。"};for(int i=0;i<lines.length;i++)text(c,trim(lines[i],width-64,24),left+32,185+i*66,24,0xFFC1D4DE,false);text(c,"按返回关闭",left+32,982,24,0xFFB3E5E8,false);return;}
            int start=Math.max(0,Math.min(choice-2,count()-7));for(int i=start;i<Math.min(count(),start+7);i++){float y=155+(i-start)*111;boolean focus=i==choice;box(c,left+24,y,1846,y+97,16,focus?0xFF355266:0xFF233545);String title;
                if(page==1)title=menu[i]+(i==2?(prefs.getBoolean("alphabetic",false)?"：名称":"：最近打开"):"");
                else if(page==3)title=i==0?"切换置顶":i==1?"隐藏应用":"系统应用信息";
                else title=i<LiteSettings.KEYS.length?LiteSettings.NAMES[i]+"  "+value(i):i==LiteSettings.KEYS.length?"保存":i==LiteSettings.KEYS.length+1?"取消":"恢复默认值";
                text(c,trim(title,width-80,25),left+42,y+42,25,Color.WHITE,focus);
                if(page==2&&i<LiteSettings.KEYS.length){float ratio=(appearance().getInt(LiteSettings.KEYS[i],LiteSettings.DEFAULT[i])-LiteSettings.MIN[i])/(float)(LiteSettings.MAX[i]-LiteSettings.MIN[i]);box(c,left+42,y+67,1825,y+73,3,0xFF566B7D);float x=left+42+(1783-left)*ratio;box(c,left+42,y+67,x,y+73,3,0xFFAEDDE3);p.setColor(0xFFAEDDE3);c.drawCircle(x,y+70,9,p);}
            }text(c,page==2?"左右调整 · 保存才应用 · 返回撤销":"上下选择 · 确定进入 · 返回关闭",left+32,986,22,0xFFA5C4D2,false);
        }
        void change(int direction){if(page!=2||choice>=LiteSettings.KEYS.length)return;String key=LiteSettings.KEYS[choice];int step=choice==7?10:1;draft.values.put(key,LiteSettings.clamp(choice,draft.getInt(key,LiteSettings.DEFAULT[choice])+direction*step));invalidate();}
        void activate(){if(page==0){if(topFocus)open(top==0?1:2);else if(!apps.isEmpty())host.launch(apps.get(selected));return;}
            if(page==3){host.appAction(choice);return;}if(page==5){host.licenses();return;}if(page==4){page=1;choice=0;invalidate();return;}
            if(page==2){if(choice<LiteSettings.KEYS.length){change(1);return;}if(choice==LiteSettings.KEYS.length){SharedPreferences.Editor e=prefs.edit();for(Map.Entry<String,Integer> x:draft.values.entrySet())e.putInt(x.getKey(),x.getValue());e.apply();closePage();}else if(choice==LiteSettings.KEYS.length+1)closePage();else {for(int i=0;i<LiteSettings.KEYS.length;i++)draft.values.put(LiteSettings.KEYS[i],LiteSettings.DEFAULT[i]);invalidate();}return;}
            switch(choice){case 0:open(2);break;case 1:prefs.edit().putInt("wallpaper",1-prefs.getInt("wallpaper",0)).apply();loadWallpaper();break;case 2:prefs.edit().putBoolean("alphabetic",!prefs.getBoolean("alphabetic",false)).apply();loadApps();break;case 3:SharedPreferences.Editor e=prefs.edit();for(String key:prefs.getAll().keySet())if(key.startsWith("hidden:"))e.remove(key);e.apply();loadApps();toast("隐藏应用已恢复");break;case 4:host.systemSettings();break;case 5:host.backup(false);break;case 6:host.backup(true);break;case 7:open(4);break;case 8:open(5);break;}invalidate();
        }
        @Override public boolean onKeyDown(int key,KeyEvent event){boolean sleeping=ambient||saver.value>.05f;wake();if(sleeping){wakeKey=true;return true;}
            if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER){if(event.getRepeatCount()==0){event.startTracking();longPress=false;}return true;}
            if(key==KeyEvent.KEYCODE_BACK){if(page!=0){if(page==4||page==5){page=1;choice=0;}else closePage();}else{topFocus=false;selected=0;scroll.target(0);scheduleMotion();}invalidate();return true;}
            if(key==KeyEvent.KEYCODE_MENU){open(1);return true;}
            if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){int d=key==KeyEvent.KEYCODE_DPAD_LEFT?-1:1;if(page==2)change(d);else if(page==0){if(topFocus)top=Math.max(0,Math.min(1,top+d));else{selected=Math.max(0,Math.min(apps.size()-1,selected+d));scroll.target(LiteSettings.scroll(selected));scheduleMotion();}}invalidate();return true;}
            if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN){if(page!=0)choice=Math.max(0,Math.min(count()-1,choice+(key==KeyEvent.KEYCODE_DPAD_UP?-1:1)));else topFocus=key==KeyEvent.KEYCODE_DPAD_UP;invalidate();return true;}return super.onKeyDown(key,event);
        }
        @Override public boolean onKeyLongPress(int key,KeyEvent event){if((key==23||key==66)&&page==0&&!topFocus&&!apps.isEmpty()){longPress=true;open(3);return true;}return super.onKeyLongPress(key,event);}
        @Override public boolean onKeyUp(int key,KeyEvent event){if(wakeKey){wakeKey=false;return true;}if(key==23||key==66){if(!longPress&&!event.isCanceled())activate();longPress=false;return true;}return super.onKeyUp(key,event);}
        @Override public boolean onTouchEvent(android.view.MotionEvent event){if(event.getAction()!=android.view.MotionEvent.ACTION_UP)return true;boolean sleeping=ambient;wake();if(sleeping)return true;float s=Math.min(getWidth()/1920f,getHeight()/1080f),x=(event.getX()-(getWidth()-1920*s)/2)/s,y=(event.getY()-(getHeight()-1080*s)/2)/s;
            if(page>0){if(page>=4){page=1;choice=0;}else if(x>(page==2?1290:1080)&&y>=155&&y<932){int start=Math.max(0,Math.min(choice-2,count()-7));choice=Math.min(count()-1,start+(int)((y-155)/111));if(page==2&&choice<LiteSettings.KEYS.length&&y-(155+(choice-start)*111)>55){float ratio=Math.max(0,Math.min(1,(x-1332)/493));draft.values.put(LiteSettings.KEYS[choice],Math.round(LiteSettings.MIN[choice]+ratio*(LiteSettings.MAX[choice]-LiteSettings.MIN[choice])));}else activate();}invalidate();return true;}
            if(y<180){open(x>590?2:1);return true;}float h=LiteSettings.height(prefs.getInt("card_height",100)),cy=LiteSettings.center(prefs.getInt("card_row_position",75),prefs.getInt("card_height",100));if(Math.abs(y-cy)<h/2+30&&!apps.isEmpty()){int at=Math.round((x-272+scroll.value)/350);if(at>=0&&at<apps.size()){if(at==selected&&!topFocus)host.launch(apps.get(at));else{topFocus=false;selected=at;scroll.target(LiteSettings.scroll(at));scheduleMotion();}}}return true;}
    }
}
