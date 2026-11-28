# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Check Lite layout boundaries and APK isolation without a device."""
import json,os,subprocess,zipfile,xml.etree.ElementTree as ET
from build import ROOT,JDK,TOOLS,env,executable,run
from build_lite import SDK14,SHARED
f=ROOT/'build/lite-local-tests';f.mkdir(parents=True,exist_ok=True)
run(executable(JDK/'bin','javac'),'-encoding','UTF-8','-d',f,ROOT/'lite/src/main/java/com/pilipala/tvhome/LiteSettings.java',ROOT/'lite/tests/LiteSettingsChecks.java')
out=subprocess.check_output([str(executable(JDK/'bin','java')),'-cp',str(f),'com.pilipala.tvhome.LiteSettingsChecks'],env=env).decode();assert 'OK 18 Lite settings checks' in out;print(out)

# Exercise the real LiteActivity.isDefaultHome() / defaultHomeState() against in-memory API 14 stubs.
lh=ROOT/'build/lite-home-tests';lh.mkdir(parents=True,exist_ok=True)
lite_stubs={
 'android/os/Handler.java':'package android.os; public class Handler { public boolean post(Runnable r){r.run();return true;} }',
 'android/content/BroadcastReceiver.java':'package android.content; public abstract class BroadcastReceiver { public abstract void onReceive(Context c,Intent i); }',
 'android/content/Intent.java':'''package android.content;
 import java.util.*;
 public class Intent {
  public static final String ACTION_MAIN="android.intent.action.MAIN",ACTION_VIEW="android.intent.action.VIEW";
  public static final String ACTION_PACKAGE_ADDED="added",ACTION_PACKAGE_REMOVED="removed",ACTION_PACKAGE_CHANGED="changed",ACTION_PACKAGE_REPLACED="replaced";
  public static final String ACTION_TIME_CHANGED="time",ACTION_TIMEZONE_CHANGED="tz",ACTION_GET_CONTENT="get",ACTION_DELETE="del";
  public static final String CATEGORY_HOME="android.intent.category.HOME",CATEGORY_DEFAULT="android.intent.category.DEFAULT",CATEGORY_LAUNCHER="launcher",CATEGORY_OPENABLE="openable";
  public static final int FLAG_ACTIVITY_NEW_TASK=1,FLAG_ACTIVITY_RESET_TASK_IF_NEEDED=2;
  private String action,pkg; private final Set<String> cats=new HashSet<String>();
  public Intent(){}
  public Intent(String a){action=a;}
  public Intent(String a,android.net.Uri u){action=a;}
  public String getAction(){return action;}
  public Intent addCategory(String c){cats.add(c);return this;}
  public boolean hasCategory(String c){return cats.contains(c);}
  public Intent setPackage(String p){pkg=p;return this;}
  public String getPackage(){return pkg;}
  public Intent setComponent(ComponentName c){return this;}
  public Intent addFlags(int f){return this;}
  public Intent setType(String t){return this;}
  public android.net.Uri getData(){return null;}
  public android.os.Bundle getExtras(){return null;}
  public static Intent createChooser(Intent i,CharSequence t){return i;}
 }''',
 'android/content/pm/ActivityInfo.java':'package android.content.pm; public class ActivityInfo { public String packageName,name,permission; public int flags; public boolean enabled=true,exported=true; public ApplicationInfo applicationInfo; public CharSequence loadLabel(PackageManager pm){return name!=null?name:""; } }',
 'android/content/pm/ResolveInfo.java':'package android.content.pm; public class ResolveInfo { public ActivityInfo activityInfo; public CharSequence loadLabel(PackageManager pm){return "";} }',
 'android/content/pm/PackageManager.java':'''package android.content.pm;
 import android.content.Intent; import android.content.ComponentName; import java.util.*;
 public class PackageManager {
  public static final int MATCH_DEFAULT_ONLY=0x10000;
  public static final int GET_ACTIVITIES=1;
  public boolean declaresHome=true;
  public String resolvedPkg="",resolvedCls="";
  public boolean sawDefaultCategory;
  public List<ResolveInfo> queryIntentActivities(Intent i,int flags){
   List<ResolveInfo> out=new ArrayList<ResolveInfo>();
   if(declaresHome&&Intent.ACTION_MAIN.equals(i.getAction())&&i.hasCategory(Intent.CATEGORY_HOME)){
    ResolveInfo r=new ResolveInfo();r.activityInfo=new ActivityInfo();
    r.activityInfo.packageName="com.pilipala.tvhome.lite";r.activityInfo.name="com.pilipala.tvhome.LiteActivity";
    out.add(r);
   }
   return out;
  }
  public ResolveInfo resolveActivity(Intent i,int flags){
   sawDefaultCategory=i.hasCategory(Intent.CATEGORY_DEFAULT);
   if(resolvedPkg==null||resolvedPkg.length()==0)return null;
   ResolveInfo r=new ResolveInfo();r.activityInfo=new ActivityInfo();
   r.activityInfo.packageName=resolvedPkg;r.activityInfo.name=resolvedCls;
   return r;
  }
  public PackageInfo getPackageInfo(String p,int f){return null;}
  public List<PackageInfo> getInstalledPackages(int f){return new ArrayList<PackageInfo>();}
  public ActivityInfo getActivityInfo(ComponentName c,int f){return null;}
  public List<ProviderInfo> queryContentProviders(String p,int u,int f){return new ArrayList<ProviderInfo>();}
  public CharSequence getApplicationLabel(ApplicationInfo a){return a!=null?a.packageName:"";}
  public android.graphics.drawable.Drawable getApplicationIcon(String p){return null;}
 }''',
 'android/content/Context.java':'''package android.content;
 import android.content.pm.PackageManager;
 public class Context {
  public static final String AUDIO_SERVICE="audio",ACTIVITY_SERVICE="activity";
  public PackageManager pm=new PackageManager();
  public PackageManager getPackageManager(){return pm;}
  public String getPackageName(){return "com.pilipala.tvhome.lite";}
  public SharedPreferences getSharedPreferences(String n,int m){return null;}
  public Object getSystemService(String n){return null;}
  public java.io.File getFilesDir(){return new java.io.File(".");}
  public android.content.res.AssetManager getAssets(){return null;}
  public android.content.ContentResolver getContentResolver(){return null;}
  public android.content.res.Resources getResources(){return null;}
  public Intent registerReceiver(BroadcastReceiver r,IntentFilter f){return null;}
  public void unregisterReceiver(BroadcastReceiver r){}
  public void startActivity(Intent i){}
 }''',
 'android/app/Activity.java':'''package android.app;
 import android.content.*;
 public class Activity extends Context {
  public static final int RESULT_OK=-1;
  private Intent currentIntent=new Intent();
  public Intent getIntent(){return currentIntent;}
  public void setIntent(Intent i){currentIntent=i;}
  public void startActivityForResult(Intent i,int req){}
  protected void onCreate(android.os.Bundle s){}
  protected void onResume(){}
  protected void onPause(){}
  protected void onDestroy(){}
  protected void onNewIntent(Intent i){}
  protected void onSaveInstanceState(android.os.Bundle b){}
  protected void onActivityResult(int req,int res,Intent data){}
  public void setContentView(android.view.View v){}
  public boolean isFinishing(){return false;}
  public void finish(){}
  public android.view.Window getWindow(){return null;}
 }''',
 'com/pilipala/tvhome/LiteHomeRuntimeChecks.java':'''package com.pilipala.tvhome;
 import android.content.Intent;
 public final class LiteHomeRuntimeChecks {
  static int passed;
  static void check(String name,boolean ok){if(!ok)throw new AssertionError(name);passed++;System.out.println("PASS "+name);}
  public static void main(String[] args){
   LiteActivity lite=new LiteActivity();
   // 1. "Just once" (HOME intent delivered, system still resolves to ResolverActivity)
   lite.pm.declaresHome=true;
   lite.pm.resolvedPkg="android";lite.pm.resolvedCls="com.android.internal.app.ResolverActivity";
   lite.setIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
   check("runtime_just_once_is_not_default",!lite.isDefaultHome()&&lite.defaultHomeState()==LiteSettings.HOME_STATE_ONCE&&lite.pm.sawDefaultCategory);
   // 2. "Always" (system resolves HOME+DEFAULT to Lite)
   lite.pm.resolvedPkg="com.pilipala.tvhome.lite";lite.pm.resolvedCls="com.pilipala.tvhome.LiteActivity";
   check("runtime_always_is_default",lite.isDefaultHome()&&lite.defaultHomeState()==LiteSettings.HOME_STATE_DEFAULT);
   // 3. Reverted to OEM while old HOME intent remains on Activity
   lite.pm.resolvedPkg="com.mitv.tvhome";lite.pm.resolvedCls="com.mitv.tvhome.MainActivity";
   check("runtime_reverted_to_oem_not_default",!lite.isDefaultHome()&&lite.defaultHomeState()==LiteSettings.HOME_STATE_OTHER);
   // 4. No explicit default home (ResolverActivity or null) on ordinary launch
   lite.setIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER));
   lite.pm.resolvedPkg="android";lite.pm.resolvedCls="com.android.internal.app.ResolverActivity";
   check("runtime_no_explicit_default_unset",!lite.isDefaultHome()&&lite.defaultHomeState()==LiteSettings.HOME_STATE_UNSET);
   lite.pm.resolvedPkg="";lite.pm.resolvedCls="";
   check("runtime_null_resolve_unset",!lite.isDefaultHome()&&lite.defaultHomeState()==LiteSettings.HOME_STATE_UNSET);
   // 5. Lite Restricted (no HOME declaration in manifest -> ordinary app launch)
   lite.pm.declaresHome=false;
   lite.pm.resolvedPkg="com.pilipala.tvhome.lite";lite.pm.resolvedCls="com.pilipala.tvhome.LiteActivity";
   check("runtime_lite_restricted_never_default",!lite.isDefaultHome()&&lite.defaultHomeState()==LiteSettings.HOME_STATE_RESTRICTED);
   System.out.println("OK "+passed+" LiteActivity runtime default-home checks");
  }
 }'''
}
lh_files=[]
for rel,content in lite_stubs.items():
 p=lh/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content,encoding='utf8');lh_files.append(p)
lh_files += [ROOT/'lite/src/main/java/com/pilipala/tvhome'/n for n in ['LiteSettings.java','LiteActivity.java']]
lh_files += [ROOT/'app/src/main/java/com/pilipala/tvhome'/n for n in SHARED]
lh_cp=os.pathsep.join([str(lh),str(SDK14)])
run(executable(JDK/'bin','javac'),'-encoding','UTF-8','-source','8','-target','8','-Xlint:-options','-classpath',lh_cp,'-d',lh,*lh_files)
lh_out=subprocess.check_output([str(executable(JDK/'bin','java')),'-cp',lh_cp,'com.pilipala.tvhome.LiteHomeRuntimeChecks'],env=env).decode()
assert 'OK 6 LiteActivity runtime default-home checks' in lh_out;print(lh_out)
m=ET.parse(ROOT/'lite/src/main/AndroidManifest.xml').getroot();a='{http://schemas.android.com/apk/res/android}'
perms={u.get(a+'name') for u in m.findall('uses-permission')}
checks={
 'separate_package':m.get('package')=='com.pilipala.tvhome.lite',
 'api14_manifest':m.find('uses-sdk').get(a+'minSdkVersion')=='14',
 'no_privileged_or_network_permissions':perms<={'android.permission.REQUEST_DELETE_PACKAGES'},
 'no_background_services_or_receivers':not m.findall('.//service') and not m.findall('.//receiver'),
}
version=m.get(a+'versionName')
standard_apk=ROOT/f'dist/MarcoTV-Lite-{version}.apk'
restricted_apk=ROOT/f'dist/MarcoTV-Lite-{version}-Restricted.apk'
if not standard_apk.exists() or not restricted_apk.exists():
 raise RuntimeError('Build both Lite and Lite Restricted APKs first')
def dump_manifest(apk_path):
 return subprocess.check_output([str(executable(TOOLS,'aapt2')),'dump','xmltree',str(apk_path),'--file','AndroidManifest.xml'],cwd=ROOT,env=env).decode('utf8',errors='replace')
std_xml=dump_manifest(standard_apk)
rst_xml=dump_manifest(restricted_apk)
checks['lite_standard_has_home']='android.intent.category.HOME' in std_xml
checks['lite_standard_no_service_or_receiver']=all(x not in std_xml for x in ('E: service','E: receiver','HomeKeyService','BootReceiver'))
checks['lite_restricted_no_home']='android.intent.category.HOME' not in rst_xml
checks['lite_restricted_has_launcher']='android.intent.category.LAUNCHER' in rst_xml and 'android.intent.category.LEANBACK_LAUNCHER' in rst_xml
checks['lite_restricted_no_service_or_receiver']=all(x not in rst_xml for x in ('E: service','E: receiver','HomeKeyService','BootReceiver'))
with zipfile.ZipFile(standard_apk) as z:
 dex=z.read('classes.dex');checks['old_dalvik_dex_035']=dex.startswith(b'dex\n035\x00')
 checks['no_full_edition_system_classes']=all(x not in dex for x in [b'Choreographer',b'MediaSessionManager',b'NotificationListenerService',b'TaskBridgeServer',b'NetworkCapabilities'])
 checks['font_licenses_bundled']=len([n for n in z.namelist() if n.startswith('assets/licenses/') and n.endswith('-OFL.txt')])==4
 checks['four_static_fonts_bundled']=len([n for n in z.namelist() if n.startswith('assets/fonts/') and n.endswith('.ttf')])==4
 checks['apk_crc']=z.testzip() is None
 checks['no_native_arch_dependency']=not any(n.startswith('lib/') for n in z.namelist())
 checks['lite_wallpapers_only']=sorted(n for n in z.namelist() if n.startswith('assets/wallpapers/'))==['assets/wallpapers/aurora.jpg','assets/wallpapers/coast.jpg']
 checks['compressed_wallpapers_budget']=sum(i.file_size for i in z.infolist() if i.filename.startswith('assets/wallpapers/'))<300000
 png_icons={'res/drawable-mdpi-v4/app_icon.png','res/drawable-hdpi-v4/app_icon.png','res/drawable-xhdpi-v4/app_icon.png','res/drawable-xxhdpi-v4/app_icon.png'}
 alt_icons={'res/drawable-mdpi/app_icon.png','res/drawable-hdpi/app_icon.png','res/drawable-xhdpi/app_icon.png','res/drawable-xxhdpi/app_icon.png'}
 names=set(z.namelist())
 checks['png_launcher_icons_bundled']=(png_icons.issubset(names) or alt_icons.issubset(names)) and 'res/drawable/app_icon.xml' not in names
 checks['valid_zip_extract_versions']=all(i.extract_version>=10 for i in z.infolist())
with zipfile.ZipFile(restricted_apk) as zr:
 checks['lite_restricted_dex_035']=zr.read('classes.dex').startswith(b'dex\n035\x00')
 checks['lite_restricted_apk_crc']=zr.testzip() is None
for name,ok in checks.items():print(('PASS ' if ok else 'FAIL ')+name)
assert all(checks.values());(f/'result.json').write_text(json.dumps({'layout_checks':18,'apk_checks':checks},indent=2))
print(f'OK {18+len(checks)} Lite local checks')
