# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Exercise production UiMotion with a deterministic frame clock; no device access."""
import os
import subprocess
from build import ROOT,JDK,ANDROID,executable

folder=ROOT/'build/motion-lifecycle';folder.mkdir(parents=True,exist_ok=True)
fixtures={
 'android/content/Context.java': 'package android.content; public class Context {public SharedPreferences getSharedPreferences(String k,int m){return null;}}',
 'android/view/View.java': '''package android.view; import java.util.*;import android.content.*;
 public class View {boolean attached; final Context context; List<OnAttachStateChangeListener> listeners=new ArrayList<>();
 public View(Context c){context=c;}public Context getContext(){return context;}public boolean isAttachedToWindow(){return attached;}
 public interface OnAttachStateChangeListener{void onViewAttachedToWindow(View v);void onViewDetachedFromWindow(View v);}
 public void addOnAttachStateChangeListener(OnAttachStateChangeListener l){listeners.add(l);}public void removeOnAttachStateChangeListener(OnAttachStateChangeListener l){listeners.remove(l);}
 public void attach(){attached=true;for(OnAttachStateChangeListener l:new ArrayList<>(listeners))l.onViewAttachedToWindow(this);}
 public void detach(){attached=false;for(OnAttachStateChangeListener l:new ArrayList<>(listeners))l.onViewDetachedFromWindow(this);}
 }''',
 'android/view/Choreographer.java': '''package android.view;import java.util.*;public class Choreographer {
 public interface FrameCallback{void doFrame(long time);}static final Choreographer INSTANCE=new Choreographer();
 final Set<FrameCallback> queue=new LinkedHashSet<>();long now=1000000000L;
 public static Choreographer getInstance(){return INSTANCE;} public void postFrameCallback(FrameCallback c){queue.add(c);}
 public void removeFrameCallback(FrameCallback c){queue.remove(c);}public int pending(){return queue.size();}
 public void frames(int n){for(int i=0;i<n;i++){now+=16666667;List<FrameCallback> next=new ArrayList<>(queue);queue.clear();for(FrameCallback c:next)c.doFrame(now);}}
 }'''
}
files=[]
for name,code in fixtures.items():
    path=folder/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(code,encoding='utf-8');files.append(path)
source=ROOT/'app/src/main/java/com/pilipala/tvhome'
files += [source/'MotionSpring.java',source/'UiMotion.java',ROOT/'scripts/MotionLifecycleChecks.java']
subprocess.run([str(executable(JDK/'bin','javac')),'-encoding','UTF-8','-cp',str(ANDROID),'-d',str(folder),*[str(f) for f in files]],check=True)
result=subprocess.run([str(executable(JDK/'bin','java')),'-cp',str(folder)+os.pathsep+str(ANDROID),'com.pilipala.tvhome.MotionLifecycleChecks'],check=True,text=True,capture_output=True,timeout=15)
(folder/'result.txt').write_text(result.stdout,encoding='utf-8');print(result.stdout)
