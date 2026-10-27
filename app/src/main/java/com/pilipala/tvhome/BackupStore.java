// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.content.*;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Versioned allowlisted backup format. Imported paths and system permissions are never restored. */
final class BackupStore {
    static final long LIMIT=128L*1024*1024;
    static final String[] GROUPS={"home","cards","menu_order"};
    static final Set<String> HOME=new HashSet<>(Arrays.asList("card_radius","focus_scale","label_opacity","clock_size","ambient_clock_size","ambient_transition","ambient_clock_brightness","ambient_dim","wallpaper_dim","idle_seconds","wallpaper_builtin","ambient_enabled","now_playing","tv_sort","order_tv","order_inputs","order_favorites","order_other","recent_size","recent_spacing","recent_tilt","recent_radius","recent_dim","recent_duration","recent_double_ms","recent_limit"));
    static boolean allowed(String group,String key){
        if(group.equals("home"))return HOME.contains(key)||key.startsWith("opened:");
        if(group.equals("cards"))return Arrays.asList("favorites","hidden","pinned","manual_apps").contains(key)||key.matches("(name|cover|manual):.{1,500}");
        return group.equals("menu_order")&&SettingsCatalog.ORDER_KEYS.contains(key);
    }
    static JSONObject capture(Context c,boolean images)throws Exception{
        JSONObject root=new JSONObject().put("format","MarcoTV-backup").put("schema",1).put("version",ProjectInfo.VERSION)
            .put("created",System.currentTimeMillis()).put("images",images).put("customWallpaper",images&&!c.getSharedPreferences("home",0).getString("wallpaper","").isEmpty());
        for(String group:GROUPS){JSONObject values=new JSONObject();
            for(Map.Entry<String,?> entry:c.getSharedPreferences(group,0).getAll().entrySet())if(allowed(group,entry.getKey())){
                Object value=entry.getValue();String type=value instanceof Boolean?"b":value instanceof Integer?"i":value instanceof Long?"l":value instanceof Set?"set":"s";
                if(!images&&entry.getKey().startsWith("cover:")&&"image".equals(value))value="original";
                values.put(entry.getKey(),new JSONObject().put("type",type).put("value",value instanceof Set?new JSONArray((Set<?>)value):value));
            }root.put(group,values);
        }return root;
    }
    static File write(Context c,File target,JSONObject root)throws Exception{
        target.getParentFile().mkdirs();AtomicFile atomic=new AtomicFile(target);FileOutputStream stream=null;
        try{stream=atomic.startWrite();ZipOutputStream zip=new ZipOutputStream(stream);JSONObject hashes=new JSONObject();long total=0;
            if(root.getBoolean("images")){
                File[] files=c.getFilesDir().listFiles(f->imageName(f.getName()));
                if(files!=null&&files.length>500)throw new IOException("image count limit");
                if(files!=null)for(File f:files){total+=f.length();if(total>LIMIT||f.length()>32L*1024*1024)throw new IOException("backup limit");
                    zip.putNextEntry(new ZipEntry("images/"+f.getName()));MessageDigest hash=MessageDigest.getInstance("SHA-256");
                    try(InputStream in=new FileInputStream(f)){byte[] block=new byte[8192];int n;while((n=in.read(block))!=-1){zip.write(block,0,n);hash.update(block,0,n);}}
                    zip.closeEntry();hashes.put(f.getName(),hex(hash.digest()));
                }
            }
            root.put("hashes",hashes);byte[] data=root.toString().getBytes("UTF-8");if(data.length>1024*1024||total+data.length>LIMIT)throw new IOException("metadata limit");
            zip.putNextEntry(new ZipEntry("settings.json"));zip.write(data);zip.closeEntry();zip.finish();zip.flush();atomic.finishWrite(stream);stream=null;return target;
        }finally{if(stream!=null)atomic.failWrite(stream);}
    }
    static boolean imageName(String name){return name.equals("wallpaper-image")||name.matches("cover-[a-f0-9]{64}\\.jpg");}
    static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    static final class Prepared {
        JSONObject metadata;File folder;final Map<String,File> images=new HashMap<>();int missing;
        void close(){if(folder!=null){File[] files=folder.listFiles();if(files!=null)for(File f:files)f.delete();folder.delete();}}
    }
    static Prepared read(Context c,InputStream source)throws Exception{
        Prepared result=new Prepared();result.folder=new File(c.getCacheDir(),"backup-"+UUID.randomUUID());result.folder.mkdirs();Set<String> names=new HashSet<>();long total=0;int count=0;
        try(ZipInputStream zip=new ZipInputStream(source)){
            ZipEntry entry;while((entry=zip.getNextEntry())!=null){String name=entry.getName();
                if(++count>501||entry.isDirectory()||!names.add(name)||!(name.equals("settings.json")||(name.startsWith("images/")&&imageName(name.substring(7)))))throw new IOException("invalid entry");
                File target=new File(result.folder,name.equals("settings.json")?name:name.substring(7));long size=0;
                try(OutputStream out=new FileOutputStream(target)){byte[] b=new byte[8192];int n;while((n=zip.read(b))!=-1){size+=n;total+=n;if(total>LIMIT||size>(name.equals("settings.json")?1024*1024:32L*1024*1024))throw new IOException("size limit");out.write(b,0,n);}}
                if(name.startsWith("images/"))result.images.put(name.substring(7),target);
            }
            File json=new File(result.folder,"settings.json");if(!json.isFile())throw new IOException("metadata missing");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();LocalFiles.copy(json,bytes);result.metadata=new JSONObject(bytes.toString("UTF-8"));
            validate(c,result);return result;
        }catch(Exception e){result.close();throw e;}
    }
    static void validate(Context c,Prepared p)throws Exception{
        JSONObject root=p.metadata;
        if(!root.getString("format").equals("MarcoTV-backup")||root.getInt("schema")!=1)throw new IOException("unsupported format");
        if(!root.getBoolean("images")&&!p.images.isEmpty())throw new IOException("unexpected images");
        JSONObject hashes=root.getJSONObject("hashes");if(hashes.length()!=p.images.size())throw new IOException("missing image");
        for(Map.Entry<String,File> e:p.images.entrySet()){
            MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(e.getValue())){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)hash.update(b,0,n);}
            if(!hex(hash.digest()).equals(hashes.getString(e.getKey())))throw new IOException("image checksum");
            BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(e.getValue().getPath(),o);
            if(o.outWidth<1||o.outHeight<1||o.outWidth>32768||o.outHeight>32768)throw new IOException("invalid image");
        }
        if(root.getBoolean("customWallpaper")&&!p.images.containsKey("wallpaper-image"))throw new IOException("wallpaper missing");
        Set<String> packages=new HashSet<>();
        for(String group:GROUPS){JSONObject values=root.getJSONObject(group);if(values.length()>5000)throw new IOException("too many settings");
            for(Iterator<String> it=values.keys();it.hasNext();){String key=it.next();if(!allowed(group,key))throw new IOException("unknown setting");JSONObject v=values.getJSONObject(key);String type=v.getString("type");Object value=v.get("value");
                String expected=expectedType(group,key);if(!type.equals(expected))throw new IOException("setting type");
                if(type.equals("b")&&!(value instanceof Boolean))throw new IOException("boolean");
                if(type.equals("i")){if(!(value instanceof Number)||((Number)value).doubleValue()!=((Number)value).intValue())throw new IOException("integer");int number=((Number)value).intValue();int[] range=range(key);if(number<range[0]||number>range[1])throw new IOException("range");}
                if(type.equals("l")&&(!(value instanceof Number)||((Number)value).longValue()<0))throw new IOException("timestamp");
                if(type.equals("s")){if(!(value instanceof String)||((String)value).length()>32768)throw new IOException("text");
                    if(key.equals("tv_sort")&&!Arrays.asList(AppOrder.MODES).contains(value))throw new IOException("sort");
                    if(key.startsWith("cover:")&&!Arrays.asList("original","image","blue","green","amber","purple").contains(value))throw new IOException("cover");
                    if(key.startsWith("manual:")){ComponentName component=ComponentName.unflattenFromString((String)value);if(component==null||!component.getPackageName().equals(key.substring(7)))throw new IOException("component");packages.add(component.getPackageName());}
                }
                if(type.equals("set")){if(!(value instanceof JSONArray)||((JSONArray)value).length()>2000)throw new IOException("set");for(int i=0;i<((JSONArray)value).length();i++){Object id=((JSONArray)value).get(i);if(!(id instanceof String)||((String)id).length()>500)throw new IOException("id");if(!((String)id).contains("/"))packages.add((String)id);}}
            }
        }
        for(String id:packages)try{c.getPackageManager().getApplicationInfo(id,0);}catch(Exception missing){p.missing++;}
    }
    private static String expectedType(String group,String key){
        if(group.equals("menu_order"))return "s";
        if(group.equals("cards"))return key.contains(":")?"s":"set";
        if(key.startsWith("opened:"))return "l";
        if(key.equals("ambient_enabled")||key.equals("now_playing"))return "b";
        if(key.startsWith("order_")||key.equals("tv_sort"))return "s";return "i";
    }
    static int[] range(String key){
        switch(key){case "recent_size":return new int[]{60,120};case "recent_spacing":return new int[]{80,360};case "recent_tilt":return new int[]{0,18};case "recent_radius":return new int[]{8,80};case "recent_dim":return new int[]{0,90};case "recent_duration":return new int[]{120,700};case "recent_double_ms":return new int[]{200,700};case "recent_limit":return new int[]{3,20};}
        switch(key){case "card_radius":return new int[]{0,90};case "focus_scale":return new int[]{0,30};case "clock_size":return new int[]{36,160};case "ambient_clock_size":return new int[]{100,280};case "ambient_transition":return new int[]{1,30};case "idle_seconds":return new int[]{0,600};case "wallpaper_builtin":return new int[]{0,3};case "ambient_clock_brightness":return new int[]{40,100};default:return new int[]{0,100};}
    }
    static synchronized File restore(Context c,Prepared p)throws Exception{
        validate(c,p);File rollback=new File(c.getFilesDir(),"backups/before-restore.zip");write(c,rollback,capture(c,true));
        File journal=new File(c.getFilesDir(),"backup-restore-pending");try(FileOutputStream mark=new FileOutputStream(journal)){mark.write(1);mark.getFD().sync();}
        try{apply(c,p);if(!journal.delete())throw new IOException("journal");return rollback;}
        catch(Exception e){try(PreparedInput old=new PreparedInput(c,rollback)){apply(c,old.value);journal.delete();}throw e;}
    }
    static final class PreparedInput implements AutoCloseable {final Prepared value;PreparedInput(Context c,File f)throws Exception{value=read(c,new FileInputStream(f));}public void close(){value.close();}}
    static void recoverInterrupted(Context c)throws Exception{
        File journal=new File(c.getFilesDir(),"backup-restore-pending");if(!journal.exists())return;
        try(PreparedInput old=new PreparedInput(c,new File(c.getFilesDir(),"backups/before-restore.zip"))){apply(c,old.value);journal.delete();Diagnostics.event("RESTORE_ROLLED_BACK");}
    }
    private static void apply(Context c,Prepared p)throws Exception{
        for(Map.Entry<String,File> e:p.images.entrySet()){
            AtomicFile target=new AtomicFile(new File(c.getFilesDir(),e.getKey()));FileOutputStream stream=null;
            try{stream=target.startWrite();try(InputStream in=new FileInputStream(e.getValue())){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)stream.write(b,0,n);}target.finishWrite(stream);stream=null;}finally{if(stream!=null)target.failWrite(stream);}
        }
        for(String group:GROUPS){SharedPreferences prefs=c.getSharedPreferences(group,0);SharedPreferences.Editor edit=prefs.edit();
            for(String key:prefs.getAll().keySet())if(allowed(group,key))edit.remove(key);
            JSONObject values=p.metadata.getJSONObject(group);for(Iterator<String> it=values.keys();it.hasNext();){String key=it.next();JSONObject v=values.getJSONObject(key);String type=v.getString("type");
                switch(type){case "b":edit.putBoolean(key,v.getBoolean("value"));break;case "i":edit.putInt(key,v.getInt("value"));break;case "l":edit.putLong(key,v.getLong("value"));break;
                    case "set":Set<String> set=new LinkedHashSet<>();JSONArray array=v.getJSONArray("value");for(int i=0;i<array.length();i++)set.add(array.getString(i));edit.putStringSet(key,set);break;
                    default:String value=v.getString("value");if(key.startsWith("cover:")&&value.equals("image")&&!p.images.containsKey(new CardStore(c).imageFile(key.substring(6)).getName()))value="original";edit.putString(key,value);}
            }
            if(group.equals("home")){edit.remove("wallpaper");if(p.metadata.getBoolean("customWallpaper"))edit.putString("wallpaper",Uri.fromFile(new File(c.getFilesDir(),"wallpaper-image")).toString());}
            if(!edit.commit())throw new IOException("preference write");
        }
        MenuMigration.apply(c);
        ArtworkCache.clear();
    }
}
