# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Run production feedback transport against fake Android services and deterministic HTTPS responses."""
import hashlib,subprocess,urllib.request
from build import ROOT,JDK,executable
folder=ROOT/'build/feedback-transport-tests';folder.mkdir(parents=True,exist_ok=True)
jar=ROOT/'.tools/test-libs/json-20240303.jar';jar.parent.mkdir(parents=True,exist_ok=True)
if not jar.exists():jar.write_bytes(urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',timeout=30).read())
assert hashlib.sha256(jar.read_bytes()).hexdigest()=='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
stubs={
'android/content/Context.java':'''package android.content;public class Context {SharedPreferences prefs=new SharedPreferences();public Context getApplicationContext(){return this;}public SharedPreferences getSharedPreferences(String n,int m){return prefs;}public java.io.File getNoBackupFilesDir(){return new java.io.File(System.getProperty("fixture"));}}''',
'android/content/SharedPreferences.java':'''package android.content;import java.util.*;public class SharedPreferences {Map<String,Object> data=new java.util.concurrent.ConcurrentHashMap<>();public boolean getBoolean(String k,boolean d){return (Boolean)data.getOrDefault(k,d);}public long getLong(String k,long d){return (Long)data.getOrDefault(k,d);}public String getString(String k,String d){return (String)data.getOrDefault(k,d);}public Editor edit(){return new Editor();}public class Editor{public Editor putBoolean(String k,boolean v){data.put(k,v);return this;}public Editor putLong(String k,long v){data.put(k,v);return this;}public Editor putString(String k,String v){data.put(k,v);return this;}public void apply(){}}}''',
'android/os/Build.java':'''package android.os;public class Build {public static final String MANUFACTURER="Fixture",MODEL="TV",DISPLAY="fixture-build";public static class VERSION{public static final int SDK_INT=28;}}''',
'android/os/Handler.java':'''package android.os;public class Handler {public Handler(Looper l){}public boolean post(Runnable r){r.run();return true;}}''',
'android/os/Looper.java':'''package android.os;public class Looper{public static Looper getMainLooper(){return new Looper();}}''',
'android/util/Base64.java':'''package android.util;public class Base64 {public static final int URL_SAFE=1,NO_WRAP=2,NO_PADDING=4;public static String encodeToString(byte[] b,int f){return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);}}''',
'android/system/Os.java':'''package android.system;public class Os{public static void rename(String a,String b)throws Exception{java.nio.file.Files.move(java.nio.file.Path.of(a),java.nio.file.Path.of(b),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}}'''
}
files=[]
for name,body in stubs.items():
 p=folder/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8');files.append(p)
files += [ROOT/'app/src/main/java/com/pilipala/tvhome'/name for name in ['FeedbackManager.java','FeedbackPolicy.java']]
files.append(ROOT/'scripts/FeedbackTransportChecks.java')
subprocess.run([str(executable(JDK/'bin','javac')),'-encoding','UTF-8','-classpath',str(jar),'-d',str(folder),*[str(p) for p in files]],check=True)
import os
subprocess.run([str(executable(JDK/'bin','java')),f'-Dfixture={folder}','-cp',str(folder)+os.pathsep+str(jar),'com.pilipala.tvhome.FeedbackTransportChecks'],check=True,timeout=30)
