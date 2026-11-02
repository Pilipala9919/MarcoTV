# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Run the production automatic HOME coordinator with controlled firmware/Android doubles."""
import subprocess
from build import ROOT, JDK, executable
folder=ROOT/'build/home-auto-tests'
folder.mkdir(parents=True,exist_ok=True)
stubs={
 'android/content/Context.java': '''package android.content;
 public class Context {public Context getApplicationContext(){return this;}
 public java.io.File getNoBackupFilesDir(){return new java.io.File(System.getProperty("fixture.dir"));}}''',
 'android/os/Looper.java': 'package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}',
 'android/os/Handler.java': '''package android.os;
 public class Handler {static volatile Runnable timeout; public Handler(Looper l){}
 public boolean post(Runnable r){r.run();return true;}
 public boolean postDelayed(Runnable r,long delay){timeout=r;return true;}
 public void removeCallbacks(Runnable r){if(timeout==r)timeout=null;}
 public static void fireTimeout(){Runnable r=timeout;if(r!=null)r.run();}}''',
 'android/os/SystemClock.java': 'package android.os; public class SystemClock {public static long elapsedRealtime(){return System.nanoTime()/1000000;}}',
 'android/os/Process.java': 'package android.os; public class Process {public static int myUid(){return 1012345;}}'
}
files=[]
for name,content in stubs.items():
 path=folder/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content,encoding='utf8');files.append(path)
files += [ROOT/'app/src/main/java/com/pilipala/tvhome'/name for name in ['HomeShellSetup.java','LocalHomeAuthorizer.java']]
files += [ROOT/'scripts/HomeAutoChecks.java']
subprocess.run([str(executable(JDK/'bin','javac')),'-encoding','UTF-8','-d',str(folder),*[str(p) for p in files]],check=True)
result=subprocess.run([str(executable(JDK/'bin','java')),f'-Dfixture.dir={folder}', '-cp',str(folder),'com.pilipala.tvhome.HomeAutoChecks'],check=True,capture_output=True,text=True,timeout=30)
(folder/'result.txt').write_text(result.stdout,encoding='utf8')
print(result.stdout)

# Run the actual persisted recovery logic separately; no mocked snapshot implementation.
snap=ROOT/'build/home-snapshot-tests';snap.mkdir(parents=True,exist_ok=True)
android={
 'android/content/SharedPreferences.java': '''package android.content; import java.util.*;
 public class SharedPreferences {public Map<String,Object> data=new HashMap<>();
 public boolean contains(String k){return data.containsKey(k);} public Map<String,?> getAll(){return new HashMap<>(data);}
 public boolean getBoolean(String k,boolean d){return data.containsKey(k)?(Boolean)data.get(k):d;}
 public int getInt(String k,int d){return data.containsKey(k)?(Integer)data.get(k):d;}
 public String getString(String k,String d){return data.containsKey(k)?(String)data.get(k):d;}
 public Editor edit(){return new Editor();} public class Editor {Map<String,Object> updates=new HashMap<>();Set<String> removes=new HashSet<>();
 public Editor putBoolean(String k,boolean v){updates.put(k,v);return this;}public Editor putInt(String k,int v){updates.put(k,v);return this;}
 public Editor putString(String k,String v){updates.put(k,v);return this;}public Editor remove(String k){removes.add(k);return this;}
 public boolean commit(){for(String k:removes)data.remove(k);data.putAll(updates);return true;}}}''',
 'android/content/ComponentName.java': '''package android.content; public class ComponentName {String pkg,name;
 public ComponentName(String p,String n){pkg=p;name=n.startsWith(".")?p+n:n;}
 public ComponentName(Context c,String n){this(c.getPackageName(),n);}
 public String getPackageName(){return pkg;}
 public static ComponentName unflattenFromString(String s){if(s==null||!s.contains("/"))return null;String[] p=s.split("/",2);return new ComponentName(p[0],p[1]);}
 public boolean equals(Object o){return o instanceof ComponentName&&pkg.equals(((ComponentName)o).pkg)&&name.equals(((ComponentName)o).name);}
 public int hashCode(){return (pkg+name).hashCode();}}''',
 'android/content/Context.java': '''package android.content; public class Context {public android.content.pm.PackageManager pm=new android.content.pm.PackageManager();
 public android.content.pm.PackageManager getPackageManager(){return pm;}public String getPackageName(){return "com.pilipala.tvhome";}}''',
 'android/content/Intent.java': '''package android.content; public class Intent {public static final String ACTION_MAIN="MAIN",CATEGORY_HOME="HOME";
 public Intent(String s){}public Intent addCategory(String s){return this;}}''',
 'android/content/pm/PackageManager.java': '''package android.content.pm;import java.util.*;import android.content.*;
 public class PackageManager {public static final int COMPONENT_ENABLED_STATE_DEFAULT=0,DONT_KILL_APP=1;
 public int alias;public boolean failAlias;public List<ResolveInfo> homes=new ArrayList<>();
 public List<ResolveInfo> queryIntentActivities(Intent i,int f){return homes;}
 public int getComponentEnabledSetting(ComponentName c){return alias;}
 public void setComponentEnabledSetting(ComponentName c,int value,int flags){if(failAlias)throw new IllegalStateException();alias=value;}}''',
 'android/content/pm/ResolveInfo.java':'package android.content.pm;public class ResolveInfo {public ActivityInfo activityInfo;}',
 'android/content/pm/ActivityInfo.java':'package android.content.pm;public class ActivityInfo {public String name,packageName;public boolean enabled,exported;public ApplicationInfo applicationInfo;}',
 'android/content/pm/ApplicationInfo.java':'package android.content.pm;public class ApplicationInfo {public boolean enabled;}'
}
files=[]
for name,content in android.items():
 path=snap/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content,encoding='utf8');files.append(path)
files += [ROOT/'app/src/main/java/com/pilipala/tvhome'/name for name in ['HomeShellSetup.java','HomeSetupSnapshot.java']]
files += [ROOT/'scripts/HomeSnapshotChecks.java']
subprocess.run([str(executable(JDK/'bin','javac')),'-encoding','UTF-8','-d',str(snap),*[str(p) for p in files]],check=True)
result=subprocess.run([str(executable(JDK/'bin','java')),'-cp',str(snap),'com.pilipala.tvhome.HomeSnapshotChecks'],check=True,capture_output=True,text=True,timeout=30)
(snap/'result.txt').write_text(result.stdout,encoding='utf8');print(result.stdout)
