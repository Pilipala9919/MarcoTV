# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Run API 14 instrumentation only on the explicitly selected emulator; no TV changes."""
import argparse,subprocess,zipfile,os
from pathlib import Path
from build import ROOT,JDK,TOOLS,env,executable,run
from build_lite import SDK14
def main():
 p=argparse.ArgumentParser();p.add_argument('--adb',required=True);p.add_argument('--serial',required=True);a=p.parse_args()
 if not a.serial.startswith('emulator-'):raise RuntimeError('This first-stage suite requires an emulator; real TVs need separate explicit validation.')
 adb=[a.adb,'-s',a.serial]
 def call(*args):
  args=list(args)
  if args[0]=='shell':args.insert(1,'-x')
  if args[0]=='install':args.insert(1,'--no-streaming')
  return subprocess.check_output(adb+args,timeout=180).decode('utf8',errors='replace')
 if call('shell','getprop','ro.build.version.sdk').strip()!='14':raise RuntimeError('This suite specifically validates Android API 14')
 f=ROOT/'build/lite-stability-tests'
 for folder in ['classes','dex']:(f/folder).mkdir(parents=True,exist_ok=True)
 (f/'manifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.pilipala.tvhome.lite.tests"><uses-sdk android:minSdkVersion="14" android:targetSdkVersion="28"/><application/><instrumentation android:name="com.pilipala.tvhome.LiteStabilityChecks" android:targetPackage="com.pilipala.tvhome.lite"/></manifest>',encoding='utf8')
 run(executable(TOOLS,'aapt2'),'link','-o',f/'res.apk','--manifest',f/'manifest.xml','-I',SDK14)
 run(executable(JDK/'bin','javac'),'-encoding','UTF-8','-source','8','-target','8','-Xlint:-options','-classpath',str(SDK14)+os.pathsep+str(ROOT/'build/lite/classes.jar'),'-d',f/'classes',ROOT/'lite/tests/LiteStabilityChecks.java')
 with zipfile.ZipFile(f/'classes.jar','w') as z:
  for file in (f/'classes').rglob('*.class'):z.write(file,file.relative_to(f/'classes').as_posix())
 run(executable(JDK/'bin','java'),'-cp',TOOLS/'lib/d8.jar','com.android.tools.r8.D8','--min-api','14','--lib',SDK14,'--classpath',ROOT/'build/lite/classes.jar','--output',f/'dex',f/'classes.jar')
 with zipfile.ZipFile(f/'res.apk') as z,zipfile.ZipFile(f/'unsigned.apk','w') as out:
  for item in z.infolist():out.writestr(item,z.read(item.filename))
  out.write(f/'dex/classes.dex','classes.dex')
 run(executable(TOOLS,'zipalign'),'-f','4',f/'unsigned.apk',f/'aligned.apk');env.setdefault('MARCOTV_STORE_PASSWORD','android')
 run(executable(JDK/'bin','java'),'-jar',TOOLS/'lib/apksigner.jar','sign','--min-sdk-version','14','--ks',os.environ.get('MARCOTV_KEYSTORE',str(ROOT/'private/development.p12')),'--ks-key-alias',os.environ.get('MARCOTV_KEY_ALIAS','tvhome'),'--ks-pass','env:MARCOTV_STORE_PASSWORD','--out',f/'tests.apk',f/'aligned.apk')
 print(call('install','-r',str(f/'tests.apk')),flush=True)
 try:
  result=call('shell','am','instrument','-w','com.pilipala.tvhome.lite.tests/com.pilipala.tvhome.LiteStabilityChecks');(f/'result.txt').write_text(result,encoding='utf8');print(result)
  if 'OK 10 Lite stability checks' not in result or 'FAIL' in result:raise RuntimeError('Lite device checks failed')
 finally:print(call('uninstall','com.pilipala.tvhome.lite.tests'))
if __name__=='__main__':main()
