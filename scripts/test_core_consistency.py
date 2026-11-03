# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Behavior checks for visual edit transactions and bounded loading. No TV is touched."""
import subprocess
from build import ROOT,JDK,ANDROID,executable

folder=ROOT/'build/core-consistency';folder.mkdir(parents=True,exist_ok=True)
uri=folder/'android/net/Uri.java';uri.parent.mkdir(parents=True,exist_ok=True)
uri.write_text('package android.net; public final class Uri {private String value; private Uri(String s){value=s;} public static Uri fromFile(java.io.File f){return new Uri(f.toURI().toString());} public String toString(){return value;}}',encoding='utf-8')
system=folder/'android/system';system.mkdir(parents=True,exist_ok=True)
(system/'ErrnoException.java').write_text('package android.system;public class ErrnoException extends Exception {public ErrnoException(Exception e){super(e);}}',encoding='utf-8')
(system/'Os.java').write_text('package android.system;public class Os {public static void rename(String from,String to)throws ErrnoException {try{java.nio.file.Files.move(java.nio.file.Paths.get(from),java.nio.file.Paths.get(to),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}catch(java.io.IOException e){throw new ErrnoException(e);}}}',encoding='utf-8')
sources=[ROOT/'app/src/main/java/com/pilipala/tvhome'/name for name in ['PreviewPreferences.java','AppearanceSession.java','WallpaperFile.java','LatestTaskQueue.java','TaskSelection.java']]
subprocess.run([str(executable(JDK/'bin','javac')),'-encoding','UTF-8','-cp',str(ANDROID),'-d',str(folder),str(uri),*[str(s) for s in system.glob('*.java')],*[str(s) for s in sources],str(ROOT/'scripts/CoreConsistencyChecks.java')],check=True)
import os
result=subprocess.run([str(executable(JDK/'bin','java')),'-cp',str(folder)+os.pathsep+str(ANDROID),'com.pilipala.tvhome.CoreConsistencyChecks'],check=True,capture_output=True,text=True,timeout=30)
(folder/'result.txt').write_text(result.stdout,encoding='utf-8')
print(result.stdout)
