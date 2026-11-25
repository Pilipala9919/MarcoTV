# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Offline state-policy checks. No device or system settings are modified."""
import os, subprocess
from build import ROOT, JDK, ANDROID, executable

folder=ROOT/'build/home-setup-policy';folder.mkdir(parents=True,exist_ok=True)
subprocess.run([str(executable(JDK/'bin','javac')),'-encoding','UTF-8','-d',str(folder),
    str(ROOT/'app/src/main/java/com/pilipala/tvhome/HomeSetupPolicy.java'),
    str(ROOT/'app/src/main/java/com/pilipala/tvhome/HomeInputGate.java'),
    str(ROOT/'app/src/main/java/com/pilipala/tvhome/OemLauncherExperimentPolicy.java'),
    str(ROOT/'scripts/HomeSetupChecks.java')],check=True)
result=subprocess.run([str(executable(JDK/'bin','java')),'-cp',str(folder),'com.pilipala.tvhome.HomeSetupChecks'],check=True,capture_output=True,text=True)
(folder/'result.txt').write_text(result.stdout,encoding='utf8');print(result.stdout)

# Only Android Base64 is shimmed; the protocol/key/socket code is compiled unchanged.
stub=folder/'android/util/Base64.java';stub.parent.mkdir(parents=True,exist_ok=True)
stub.write_text('package android.util; public class Base64 {public static final int NO_WRAP=2; public static String encodeToString(byte[] b,int flags){return java.util.Base64.getEncoder().encodeToString(b);}}',encoding='utf8')
subprocess.run([str(executable(JDK/'bin','javac')),'-encoding','UTF-8','-d',str(folder),str(stub),
    str(ROOT/'app/src/main/java/com/pilipala/tvhome/LocalAdbWire.java'),str(ROOT/'scripts/LocalAdbWireChecks.java')],check=True)
result=subprocess.run([str(executable(JDK/'bin','java')),'-cp',str(folder),'com.pilipala.tvhome.LocalAdbWireChecks',str(folder/'fixture-key')],check=True,capture_output=True,text=True,timeout=40)
(folder/'transport-result.txt').write_text(result.stdout,encoding='utf8');print(result.stdout)

# End-to-end production flow checks: compiles real DefaultHomePanel, HomeSetup, SystemIntegration,
# HomeSetupSnapshot, BootReceiver, StatusSnapshot, TaskBridgeClient and HomePressTracker against
# in-memory Android service doubles.
flow=ROOT/'build/home-flow-tests';flow.mkdir(parents=True,exist_ok=True)
import hashlib, urllib.request
json_jar=ROOT/'.tools/test-libs/json-20240303.jar'
json_jar.parent.mkdir(parents=True,exist_ok=True)
if not json_jar.exists():
    json_jar.write_bytes(urllib.request.urlopen(
        'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',timeout=30).read())
assert hashlib.sha256(json_jar.read_bytes()).hexdigest()=='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
android_stubs={
 'android/os/Build.java': '''package android.os;
 public class Build {
  public static String MANUFACTURER="Xiaomi",BRAND="Xiaomi",MODEL="MiTV",DISPLAY="PPR1";
  public static class VERSION { public static int SDK_INT=28; public static String RELEASE="9"; }
 }''',
 'android/os/SystemClock.java': '''package android.os;
 public class SystemClock { public static long elapsed=10000L; public static long elapsedRealtime(){return elapsed;} }''',
 'android/os/Looper.java': '''package android.os;
 public class Looper { public static Looper getMainLooper(){return new Looper();} }''',
 'android/os/Handler.java': '''package android.os;
 import java.util.*;
 public class Handler {
  private static final class Entry {
   final Runnable task; final long runAt;
   Entry(Runnable t, long at) { task = t; runAt = at; }
  }
  private static final List<Entry> delayed = new ArrayList<>();
  public static final ThreadLocal<Boolean> inMainPost = ThreadLocal.withInitial(() -> Boolean.FALSE);
  public Handler(Looper l){}
  public boolean post(Runnable r){
   boolean prev = inMainPost.get();
   inMainPost.set(true);
   try { r.run(); } finally { inMainPost.set(prev); }
   return true;
  }
  public boolean postDelayed(Runnable r,long d){
   synchronized(delayed){ delayed.add(new Entry(r, SystemClock.elapsedRealtime() + d)); }
   return true;
  }
  public void removeCallbacks(Runnable r){
   synchronized(delayed){
    for(int i=delayed.size()-1;i>=0;i--) if(delayed.get(i).task==r) delayed.remove(i);
   }
  }
  public static void runDue(){
   long now = SystemClock.elapsedRealtime();
   List<Runnable> due = new ArrayList<>();
   synchronized(delayed){
    for(int i=0;i<delayed.size();){
     Entry e = delayed.get(i);
     if(e.runAt <= now){ due.add(e.task); delayed.remove(i); }
     else i++;
    }
   }
   for(Runnable r : due) r.run();
  }
  public static void clearDelayed(){ synchronized(delayed){ delayed.clear(); } }
 }''',
 'android/os/Process.java': '''package android.os;
 public class Process { public static volatile int uid = 1012345; public static int myUid(){ return uid; } }''',
 'android/os/UserManager.java': '''package android.os;
 public class UserManager { public boolean unlocked=true; public boolean isUserUnlocked(){return unlocked;} }''',
 'android/os/PowerManager.java': '''package android.os;
 public class PowerManager { public boolean interactive=true; public boolean isInteractive(){return interactive;} }''',
 'android/os/RemoteException.java': '''package android.os;
 public class RemoteException extends Exception {}''',
 'android/os/Parcel.java': '''package android.os;
 import java.util.*;
 public class Parcel {
  private final ArrayDeque<Object> q=new ArrayDeque<>();
  public static Parcel obtain(){return new Parcel();}
  public void writeInterfaceToken(String s){q.add(s);}
  public String readInterfaceToken(){return (String)q.poll();}
  public void writeString(String s){q.add(s);}
  public String readString(){return (String)q.poll();}
  public void writeNoException(){q.add("OK");}
  public void readException(){q.poll();}
  public void recycle(){}
 }''',
 'android/os/IBinder.java': '''package android.os;
 public interface IBinder {
  int FIRST_CALL_TRANSACTION=1;
  interface DeathRecipient { void binderDied(); }
  boolean isBinderAlive();
  void linkToDeath(DeathRecipient recipient,int flags) throws RemoteException;
  boolean transact(int code,Parcel data,Parcel reply,int flags) throws RemoteException;
 }''',
 'android/util/Log.java': '''package android.util;
 public class Log {
  public static int i(String t,String m){return 0;}
  public static int w(String t,String m){return 0;}
  public static int w(String t,String m,Throwable e){return 0;}
 }''',
 'android/content/ContentResolver.java': '''package android.content; public class ContentResolver {}''',
 'android/content/DialogInterface.java': '''package android.content;
 public interface DialogInterface {
  int BUTTON_POSITIVE=-1,BUTTON_NEGATIVE=-2,BUTTON_NEUTRAL=-3;
  interface OnClickListener { void onClick(DialogInterface dialog,int which); }
 }''',
 'android/provider/Settings.java': '''package android.provider;
 import android.content.ContentResolver; import java.util.*;
 public class Settings {
  public static final String ACTION_SETTINGS="android.settings.SETTINGS";
  public static final String ACTION_HOME_SETTINGS="android.settings.HOME_SETTINGS";
  public static final String ACTION_ACCESSIBILITY_SETTINGS="android.settings.ACCESSIBILITY_SETTINGS";
  public static final String ACTION_APPLICATION_DEVELOPMENT_SETTINGS="android.settings.APPLICATION_DEVELOPMENT_SETTINGS";
  public static class Global {
   public static final String ADB_ENABLED="adb_enabled";
   public static final Map<String,Integer> ints=new HashMap<>();
   public static int getInt(ContentResolver r,String k,int d){return ints.containsKey(k)?ints.get(k):d;}
   public static boolean putInt(ContentResolver r,String k,int v){ints.put(k,v);return true;}
  }
  public static class Secure {
   public static final String ACCESSIBILITY_ENABLED="accessibility_enabled";
   public static final String ENABLED_ACCESSIBILITY_SERVICES="enabled_accessibility_services";
   public static final Map<String,Integer> ints=new HashMap<>();
   public static final Map<String,String> strings=new HashMap<>();
   public static int failNextPutStrings;
   public static int failNextPutInts;
   public static int throwNextPutInts;
   public static int getInt(ContentResolver r,String k,int d){return ints.containsKey(k)?ints.get(k):d;}
   public static boolean putInt(ContentResolver r,String k,int v){if(throwNextPutInts>0){throwNextPutInts--;throw new SecurityException("putInt blocked");}if(failNextPutInts>0){failNextPutInts--;return false;}ints.put(k,v);return true;}
   public static String getString(ContentResolver r,String k){return strings.get(k);}
   public static boolean putString(ContentResolver r,String k,String v){if(failNextPutStrings>0){failNextPutStrings--;return false;}strings.put(k,v);return true;}
  }
 }''',
 'android/content/SharedPreferences.java': '''package android.content;
 import java.util.*;
 public class SharedPreferences {
  public final Map<String,Object> data=Collections.synchronizedMap(new HashMap<>());
  public synchronized boolean contains(String k){return data.containsKey(k);}
  public synchronized Map<String,?> getAll(){return new HashMap<>(data);}
  public synchronized boolean getBoolean(String k,boolean d){return data.containsKey(k)?(Boolean)data.get(k):d;}
  public synchronized int getInt(String k,int d){return data.containsKey(k)?(Integer)data.get(k):d;}
  public synchronized long getLong(String k,long d){return data.containsKey(k)?(Long)data.get(k):d;}
  public synchronized String getString(String k,String d){return data.containsKey(k)?(String)data.get(k):d;}
  public Editor edit(){return new Editor();}
  public class Editor {
   private final Map<String,Object> updates=new HashMap<>();
   private final Set<String> removes=new HashSet<>();
   public Editor putBoolean(String k,boolean v){updates.put(k,v);return this;}
   public Editor putInt(String k,int v){updates.put(k,v);return this;}
   public Editor putLong(String k,long v){updates.put(k,v);return this;}
   public Editor putString(String k,String v){updates.put(k,v);return this;}
   public Editor remove(String k){removes.add(k);updates.remove(k);return this;}
   public void apply(){commit();}
   public boolean commit(){synchronized(SharedPreferences.this){for(String k:removes)data.remove(k);data.putAll(updates);}return true;}
  }
 }''',
 'android/content/ComponentName.java': '''package android.content;
 public class ComponentName {
  private final String pkg,cls;
  public ComponentName(String p,String c){pkg=p;cls=c.startsWith(".")?p+c:c;}
  public ComponentName(Context ctx,String c){this(ctx.getPackageName(),c);}
  public String getPackageName(){return pkg;}
  public String getClassName(){return cls;}
  public String flattenToString(){return pkg+"/"+cls;}
  public static ComponentName unflattenFromString(String s){
   if(s==null||!s.contains("/"))return null;
   String[] p=s.split("/",2);
   if(p[0].isEmpty()||p[1].isEmpty())return null;
   return new ComponentName(p[0],p[1]);
  }
  public boolean equals(Object o){return o instanceof ComponentName&&pkg.equals(((ComponentName)o).pkg)&&cls.equals(((ComponentName)o).cls);}
  public int hashCode(){return (pkg+"/"+cls).hashCode();}
 }''',
 'android/content/IntentFilter.java': '''package android.content;
 import java.util.*;
 public class IntentFilter {
  public final Set<String> actions=new HashSet<>(), categories=new HashSet<>();
  public boolean hasAction(String a){return actions.contains(a);}
  public boolean hasCategory(String c){return categories.contains(c);}
 }''',
 'android/content/Intent.java': '''package android.content;
 import java.util.*;
 public class Intent {
  public static final String ACTION_MAIN="android.intent.action.MAIN";
  public static final String ACTION_BOOT_COMPLETED="android.intent.action.BOOT_COMPLETED";
  public static final String ACTION_LOCKED_BOOT_COMPLETED="android.intent.action.LOCKED_BOOT_COMPLETED";
  public static final String ACTION_MY_PACKAGE_REPLACED="android.intent.action.MY_PACKAGE_REPLACED";
  public static final String CATEGORY_HOME="android.intent.category.HOME";
  public static final String CATEGORY_DEFAULT="android.intent.category.DEFAULT";
  public static final int FLAG_ACTIVITY_NEW_TASK=0x10000000;
  public static final int FLAG_ACTIVITY_CLEAR_TOP=0x04000000;
  public static final int FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY=0x00100000;
  private String action;
  private Class<?> targetClass;
  private int flags;
  private final Set<String> categories=new HashSet<>();
  private final Map<String,Object> extras=new HashMap<>();
  public Intent(){}
  public Intent(String a){action=a;}
  public Intent(Context c,Class<?> cls){targetClass=cls;}
  public String getAction(){return action;}
  public Class<?> getTargetClass(){return targetClass;}
  public Intent addCategory(String c){categories.add(c);return this;}
  public boolean hasCategory(String c){return categories.contains(c);}
  public Intent addFlags(int f){flags|=f;return this;}
  public int getFlags(){return flags;}
  public Intent putExtra(String k,boolean v){extras.put(k,v);return this;}
  public Intent putExtra(String k,String v){extras.put(k,v);return this;}
  public Intent putExtra(String k,int v){extras.put(k,v);return this;}
  public boolean getBooleanExtra(String k,boolean d){Object v=extras.get(k);return v instanceof Boolean?(Boolean)v:d;}
  public String getStringExtra(String k){Object v=extras.get(k);return v instanceof String?(String)v:null;}
  public int getIntExtra(String k,int d){Object v=extras.get(k);return v instanceof Integer?(Integer)v:d;}
 }''',
 'android/content/BroadcastReceiver.java': '''package android.content;
 public abstract class BroadcastReceiver { public abstract void onReceive(Context context,Intent intent); }''',
 'android/content/pm/ApplicationInfo.java': '''package android.content.pm;
 public class ApplicationInfo { public static final int FLAG_SYSTEM=1; public boolean enabled=true; public int flags; }''',
 'android/content/pm/ActivityInfo.java': '''package android.content.pm;
 public class ActivityInfo { public String packageName,name; public boolean enabled=true,exported=true; public ApplicationInfo applicationInfo=new ApplicationInfo(); }''',
 'android/content/pm/ResolveInfo.java': '''package android.content.pm;
 public class ResolveInfo { public ActivityInfo activityInfo; }''',
 'android/content/pm/PackageManager.java': '''package android.content.pm;
 import android.content.*; import java.util.*;
 public class PackageManager {
  public static final int PERMISSION_GRANTED=0,PERMISSION_DENIED=-1,MATCH_DEFAULT_ONLY=0x10000;
  public static final int COMPONENT_ENABLED_STATE_DEFAULT=0,COMPONENT_ENABLED_STATE_ENABLED=1,COMPONENT_ENABLED_STATE_DISABLED=2,COMPONENT_ENABLED_STATE_DISABLED_USER=3,COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED=4,DONT_KILL_APP=1;
  public int alias=COMPONENT_ENABLED_STATE_DISABLED;
  public boolean failAlias,preferredHomeIsOurs,throwOnAppEnabledState,failResolveMainActivity;
  public String currentHome="";
  public final List<ResolveInfo> homes=Collections.synchronizedList(new ArrayList<>());
  public final Map<String,ResolveInfo> actions=Collections.synchronizedMap(new HashMap<>());
  public final Map<String,Integer> appEnabledStates=Collections.synchronizedMap(new HashMap<>());
  public ResolveInfo resolveActivity(Intent i,int flags){
   if(i.getTargetClass()!=null){
    if(failResolveMainActivity)return null;
    ResolveInfo r=new ResolveInfo();r.activityInfo=new ActivityInfo();
    r.activityInfo.packageName="com.pilipala.tvhome";r.activityInfo.name=i.getTargetClass().getName();
    return r;
   }
   if(Intent.ACTION_MAIN.equals(i.getAction())&&i.hasCategory(Intent.CATEGORY_HOME)){
    ComponentName cn=ComponentName.unflattenFromString(currentHome);
    if(cn==null)return null;
    ResolveInfo r=new ResolveInfo();r.activityInfo=new ActivityInfo();
    r.activityInfo.packageName=cn.getPackageName();r.activityInfo.name=cn.getClassName();
    return r;
   }
   return actions.get(i.getAction());
  }
  public List<ResolveInfo> queryIntentActivities(Intent i,int flags){synchronized(homes){return new ArrayList<>(homes);}}
  public int getPreferredActivities(List<IntentFilter> filters,List<ComponentName> names,String pkg){
   if(preferredHomeIsOurs){
    IntentFilter f=new IntentFilter();f.actions.add(Intent.ACTION_MAIN);f.categories.add(Intent.CATEGORY_HOME);
    filters.add(f);names.add(new ComponentName("com.pilipala.tvhome","com.pilipala.tvhome.HomeActivity"));
   }
   return 0;
  }
  public int getComponentEnabledSetting(ComponentName c){return alias;}
  public void setComponentEnabledSetting(ComponentName c,int v,int flags){if(failAlias)throw new IllegalStateException("alias");alias=v;}
  public int getApplicationEnabledSetting(String pkg){
   if(throwOnAppEnabledState)throw new SecurityException("getApplicationEnabledSetting blocked");
   Integer explicit=appEnabledStates.get(pkg);
   if(explicit!=null)return explicit;
   synchronized(homes){
    for(ResolveInfo r:homes){
     if(r.activityInfo!=null&&pkg.equals(r.activityInfo.packageName)&&r.activityInfo.applicationInfo!=null){
      return r.activityInfo.applicationInfo.enabled?COMPONENT_ENABLED_STATE_ENABLED:COMPONENT_ENABLED_STATE_DISABLED;
     }
    }
   }
   return COMPONENT_ENABLED_STATE_DEFAULT;
  }
 }''',
 'android/app/role/RoleManager.java': '''package android.app.role;
 import android.content.Intent;
 public class RoleManager {
  public boolean available;
  public RoleManager(boolean a){available=a;}
  public boolean isRoleAvailable(String role){return available&&"android.app.role.HOME".equals(role);}
  public Intent createRequestRoleIntent(String role){return new Intent("android.app.role.action.REQUEST_ROLE");}
 }''',
 'android/content/Context.java': '''package android.content;
 import android.content.pm.PackageManager; import android.os.*; import java.util.*;
 public class Context {
  public static final int MODE_PRIVATE=0;
  public static final String USER_SERVICE="user",POWER_SERVICE="power";
  public PackageManager pm=new PackageManager();
  public SharedPreferences sysPrefs=new SharedPreferences();
  public SharedPreferences homePrefs=new SharedPreferences();
  public ContentResolver resolver=new ContentResolver();
  public UserManager userManager=new UserManager();
  public PowerManager powerManager=new PowerManager();
  public boolean secureAuthorized,roleAvailable,startActivityThrows;
  public final List<Intent> startedActivities=new ArrayList<>();
  public final List<Integer> startedReqCodes=new ArrayList<>();
  public Context getApplicationContext(){return this;}
  public Context createDeviceProtectedStorageContext(){return this;}
  public String getPackageName(){return "com.pilipala.tvhome";}
  public PackageManager getPackageManager(){return pm;}
  public ContentResolver getContentResolver(){return resolver;}
  public SharedPreferences getSharedPreferences(String name,int mode){return "system_integration".equals(name)?sysPrefs:homePrefs;}
  public int checkSelfPermission(String perm){return secureAuthorized?PackageManager.PERMISSION_GRANTED:PackageManager.PERMISSION_DENIED;}
  public Object getSystemService(String name){
   if("role".equals(name))return new android.app.role.RoleManager(roleAvailable);
   if(USER_SERVICE.equals(name))return userManager;
   if(POWER_SERVICE.equals(name))return powerManager;
   return null;
  }
  public void startActivity(Intent i){if(startActivityThrows)throw new SecurityException("blocked");startedActivities.add(i);startedReqCodes.add(-1);}
  public java.io.File getNoBackupFilesDir(){return new java.io.File(System.getProperty("fixture.dir", "."));}
  public ComponentName startService(Intent i){return null;}
  public ComponentName startForegroundService(Intent i){return null;}
  public boolean stopService(Intent i){return true;}
 }''',
 'android/app/Activity.java': '''package android.app;
 import android.content.*;
 public class Activity extends Context {
  public static final int RESULT_OK=-1,RESULT_CANCELED=0;
  public boolean windowFocus=true,finishing,destroyed;
  public boolean hasWindowFocus(){return windowFocus;}
  public boolean isFinishing(){return finishing;}
  public boolean isDestroyed(){return destroyed;}
  public void startActivityForResult(Intent i,int req){if(startActivityThrows)throw new SecurityException("blocked");startedActivities.add(i);startedReqCodes.add(req);}
  public android.view.Window getWindow(){return null;}
  public android.content.res.Resources getResources(){return null;}
 }''',
 'android/widget/Toast.java': '''package android.widget;
 import android.content.Context;
 public class Toast {
  public static final int LENGTH_LONG=1,LENGTH_SHORT=0;
  public static Toast makeText(Context c,CharSequence t,int d){return new Toast();}
  public void show(){}
 }''',
}
flow_files=[]
for rel,content in android_stubs.items():
    p=flow/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content,encoding='utf8');flow_files.append(p)
prod_names=[
    'HomeSetupPolicy.java','OemLauncherExperimentPolicy.java','HomeDeviceGuide.java','HomeShellSetup.java','HomePressTracker.java',
    'HomeSetup.java','HomeSetupSnapshot.java','SystemIntegration.java','DefaultHomePanel.java',
    'LocalHomeAuthorizer.java','BootReceiver.java','StatusSnapshot.java','TaskBridgeClient.java',
]
flow_files += [ROOT/'app/src/main/java/com/pilipala/tvhome'/n for n in prod_names]
flow_files += [ROOT/'app/src/main/java/io/nayuki/qrcodegen'/n for n in ['BitBuffer.java','DataTooLongException.java','QrSegment.java','QrCode.java']]
flow_files += [ROOT/'scripts/HomeFlowChecks.java']
flow_cp=os.pathsep.join([str(flow),str(json_jar),str(ANDROID)])
subprocess.run([str(executable(JDK/'bin','javac')),'-encoding','UTF-8','-cp',flow_cp,'-d',str(flow),*[str(p) for p in flow_files]],check=True)
result=subprocess.run([str(executable(JDK/'bin','java')),f'-Dfixture.dir={flow}','-cp',flow_cp,'com.pilipala.tvhome.HomeFlowChecks'],check=True,capture_output=True,text=True,timeout=30)
(flow/'result.txt').write_text(result.stdout,encoding='utf8');print(result.stdout)

# Verify accessibility service XML never enables key filtering by default while keeping opt-in capability.
xml_text=(ROOT/'app/src/main/res/xml/home_accessibility.xml').read_text(encoding='utf8')
assert 'flagRequestFilterKeyEvents' not in xml_text, 'home_accessibility.xml must not enable flagRequestFilterKeyEvents by default'
assert 'android:canRequestFilterKeyEvents="true"' in xml_text, 'home_accessibility.xml must keep canRequestFilterKeyEvents for explicit opt-in'
assert 'typeWindowStateChanged' in xml_text, 'home_accessibility.xml must keep typeWindowStateChanged for OEM window pullback'
print('PASS home_accessibility_xml_defaults_without_key_filter')
for rel in ['app/src/main/java/com/pilipala/tvhome/SettingsTaxonomy.java', 'app/src/main/java/com/pilipala/tvhome/SystemIntegration.java', 'app/src/main/java/com/pilipala/tvhome/DefaultHomePanel.java']:
    src=(ROOT/rel).read_text(encoding='utf8')
    assert '可保证蓝牙' not in src and '蓝牙长按正常' not in src, f'{rel} must not overclaim Bluetooth repeat guarantee'
    assert '绝不黑屏' not in src, f'{rel} must never promise 绝不黑屏'
    if not rel.endswith('DefaultHomePanel.java'):
        assert '音量和方向键交由系统处理' in src, f'{rel} must state volume and D-pad keys are handled by the system'
print('PASS settings_wording_states_system_key_handling')

# Real HomeKeyService runtime flag & mode checks against in-memory Android accessibility doubles.
svc=ROOT/'build/home-key-service-tests';svc.mkdir(parents=True,exist_ok=True)
svc_stubs=dict(android_stubs)
svc_stubs['android/os/Handler.java']=svc_stubs['android/os/Handler.java'].replace(
 'public static void clearDelayed(){ synchronized(delayed){ delayed.clear(); } }',
 'public void removeCallbacksAndMessages(Object token){ clearDelayed(); }\n'
 '  public static void clearDelayed(){ synchronized(delayed){ delayed.clear(); } }'
)
svc_stubs['android/content/IntentFilter.java']='''package android.content;
import java.util.*;
public class IntentFilter {
 public final Set<String> actions=new HashSet<>(), categories=new HashSet<>();
 public IntentFilter(){}
 public IntentFilter(String a){actions.add(a);}
 public void addAction(String a){actions.add(a);}
 public boolean hasAction(String a){return actions.contains(a);}
 public boolean hasCategory(String c){return categories.contains(c);}
}'''
svc_stubs['android/content/Intent.java']=svc_stubs['android/content/Intent.java'].replace(
 'public static final String CATEGORY_DEFAULT="android.intent.category.DEFAULT";',
 'public static final String CATEGORY_DEFAULT="android.intent.category.DEFAULT";\n'
 '  public static final String ACTION_CLOSE_SYSTEM_DIALOGS="android.intent.action.CLOSE_SYSTEM_DIALOGS";\n'
 '  public static final String ACTION_SCREEN_OFF="android.intent.action.SCREEN_OFF";\n'
 '  public static final String ACTION_SCREEN_ON="android.intent.action.SCREEN_ON";'
)
svc_stubs['android/content/Context.java']=svc_stubs['android/content/Context.java'].replace(
 'public final List<Integer> startedReqCodes=new ArrayList<>();',
 'public final List<Integer> startedReqCodes=new ArrayList<>();\n'
 '  public final List<BroadcastReceiver> registeredReceivers=new ArrayList<>();\n'
 '  public Intent registerReceiver(BroadcastReceiver r,IntentFilter f){registeredReceivers.add(r);return null;}\n'
 '  public void unregisterReceiver(BroadcastReceiver r){registeredReceivers.remove(r);}\n'
 '  public void dispatchBroadcast(Intent i){for(BroadcastReceiver r:new ArrayList<>(registeredReceivers))r.onReceive(this,i);}'
)
svc_stubs['android/accessibilityservice/AccessibilityServiceInfo.java']='''package android.accessibilityservice;
public class AccessibilityServiceInfo {
 public static final int FLAG_REQUEST_FILTER_KEY_EVENTS=32;
 public int flags;
 public int capabilities;
 public int getCapabilities(){return capabilities;}
}'''
svc_stubs['android/accessibilityservice/AccessibilityService.java']='''package android.accessibilityservice;
import android.content.*;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
public abstract class AccessibilityService extends Context {
 public static final int GLOBAL_ACTION_RECENTS=3;
 public AccessibilityServiceInfo serviceInfo=new AccessibilityServiceInfo();
 public boolean globalActionResult=true;
 public boolean ignoreSetServiceInfo,nullServiceInfoAfterSet;
 private boolean setCalled;
 public void resetSetTracking(){setCalled=false;}
 public final AccessibilityServiceInfo getServiceInfo(){
  if((nullServiceInfoAfterSet&&setCalled)||serviceInfo==null)return null;
  AccessibilityServiceInfo copy=new AccessibilityServiceInfo();
  copy.flags=serviceInfo.flags;
  copy.capabilities=serviceInfo.capabilities;
  return copy;
 }
 public final void setServiceInfo(AccessibilityServiceInfo info){
  setCalled=true;
  if(ignoreSetServiceInfo)return;
  serviceInfo=info;
 }
 public final boolean performGlobalAction(int action){return globalActionResult;}
 protected void onServiceConnected(){}
 protected boolean onKeyEvent(KeyEvent event){return false;}
 public abstract void onAccessibilityEvent(AccessibilityEvent event);
 public abstract void onInterrupt();
 public boolean onUnbind(Intent intent){return false;}
 public void onDestroy(){}
}'''
svc_stubs['android/view/accessibility/AccessibilityEvent.java']='''package android.view.accessibility;
public class AccessibilityEvent {
 public static final int TYPE_WINDOW_STATE_CHANGED=32;
 private final int type; private final CharSequence pkg, cls;
 public AccessibilityEvent(int t,CharSequence p,CharSequence c){type=t;pkg=p;cls=c;}
 public int getEventType(){return type;}
 public CharSequence getPackageName(){return pkg;}
 public CharSequence getClassName(){return cls;}
}'''
svc_stubs['android/view/KeyEvent.java']='''package android.view;
public class KeyEvent {
 public static final int ACTION_DOWN=0,ACTION_UP=1;
 public static final int KEYCODE_HOME=3,KEYCODE_DPAD_RIGHT=22,KEYCODE_VOLUME_UP=24,KEYCODE_VOLUME_DOWN=25,KEYCODE_POWER=26;
 private final int action,code,repeat; private final boolean longPress,canceled;
 public KeyEvent(int a,int c){this(a,c,0,false,false);}
 public KeyEvent(int a,int c,int r,boolean l,boolean x){action=a;code=c;repeat=r;longPress=l;canceled=x;}
 public int getAction(){return action;}
 public int getKeyCode(){return code;}
 public int getRepeatCount(){return repeat;}
 public boolean isLongPress(){return longPress;}
 public boolean isCanceled(){return canceled;}
}'''
svc_stubs['android/widget/Toast.java']='''package android.widget;
import android.content.Context;
public class Toast {
 public static final int LENGTH_LONG=1;
 public static Toast makeText(Context c,CharSequence t,int d){return new Toast();}
 public void show(){}
}'''
svc_files=[]
for rel,content in svc_stubs.items():
    p=svc/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content,encoding='utf8');svc_files.append(p)
svc_prod_names=[
    'HomeSetupPolicy.java','OemLauncherExperimentPolicy.java','HomeInputGate.java','HomePressTracker.java','HomeDeviceGuide.java',
    'HomeShellSetup.java','HomeSetup.java','HomeSetupSnapshot.java','SystemIntegration.java',
    'TaskBridgeClient.java','HomeKeyService.java',
]
svc_files += [ROOT/'app/src/main/java/com/pilipala/tvhome'/n for n in svc_prod_names]
svc_files += [ROOT/'scripts/HomeKeyServiceChecks.java']
svc_cp=os.pathsep.join([str(svc),str(json_jar),str(ANDROID)])
subprocess.run([str(executable(JDK/'bin','javac')),'-encoding','UTF-8','-cp',svc_cp,'-d',str(svc),*[str(p) for p in svc_files]],check=True)
result=subprocess.run([str(executable(JDK/'bin','java')),f'-Dfixture.dir={svc}','-cp',svc_cp,'com.pilipala.tvhome.HomeKeyServiceChecks'],check=True,capture_output=True,text=True,timeout=30)
(svc/'result.txt').write_text(result.stdout,encoding='utf8');print(result.stdout)
