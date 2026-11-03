# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Run setup status, consent, boot and idle handoff checks on a connected TV."""
import argparse,subprocess,zipfile,os
from pathlib import Path
from build import ROOT,JDK,TOOLS,env,executable,run
from build import ANDROID as SDK14
def main():
 p=argparse.ArgumentParser();p.add_argument('--adb',default='adb');p.add_argument('--serial');p.add_argument('--compile-only',action='store_true');p.add_argument('--feedback',action='store_true',help='Use synthetic cloud feedback checks instead of HOME checks');a=p.parse_args()
 if not a.compile_only and not a.serial:p.error('--serial is required for device checks')

 adb=[a.adb,'-s',a.serial]
 def call(*args):
  args=list(args)
  if args[0]=='shell':args.insert(1,'-x')
  if args[0]=='install':args.insert(1,'--no-streaming')
  return subprocess.check_output(adb+args,timeout=180).decode('utf8',errors='replace')
 original_services=None
 if not a.compile_only:
  if int(call('shell','getprop','ro.build.version.sdk').strip())<23:raise RuntimeError('Requires API 23+')
  if 'mWakefulness=Awake' not in call('shell','dumpsys','power'):
   raise RuntimeError('TV is not awake. No UI checks, wake command or test installation performed.')
  if 'mFinished=false' in call('shell','dumpsys','activity'):
   raise RuntimeError('Another instrumentation is running. Leave the shared TV untouched and retry later.')
  original_services=call('shell','settings','get','secure','enabled_accessibility_services').strip()
 check_class='FeedbackDeviceChecks' if a.feedback else 'HomeSetupDeviceChecks'
 f=ROOT/('build/feedback-device-tests' if a.feedback else 'build/home-setup-tests')
 for folder in ['classes','dex']:(f/folder).mkdir(parents=True,exist_ok=True)
 (f/'manifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.pilipala.tvhome.setup.tests"><uses-sdk android:minSdkVersion="23" android:targetSdkVersion="28"/><application/><instrumentation android:name="com.pilipala.tvhome.HomeSetupDeviceChecks" android:targetPackage="com.pilipala.tvhome"/></manifest>',encoding='utf8')
 if a.feedback:
  manifest=f/'manifest.xml';manifest.write_text(manifest.read_text().replace('HomeSetupDeviceChecks',check_class),encoding='utf8')
 run(executable(TOOLS,'aapt2'),'link','-o',f/'res.apk','--manifest',f/'manifest.xml','-I',SDK14)
 run(executable(JDK/'bin','javac'),'-encoding','UTF-8','-source','8','-target','8','-Xlint:-options','-classpath',str(SDK14)+os.pathsep+str(ROOT/'build/classes.jar'),'-d',f/'classes',ROOT/('scripts/'+check_class+'.java'))
 with zipfile.ZipFile(f/'classes.jar','w') as z:
  for file in (f/'classes').rglob('*.class'):z.write(file,file.relative_to(f/'classes').as_posix())
 run(executable(JDK/'bin','java'),'-cp',TOOLS/'lib/d8.jar','com.android.tools.r8.D8','--min-api','23','--lib',SDK14,'--classpath',ROOT/'build/classes.jar','--output',f/'dex',f/'classes.jar')
 with zipfile.ZipFile(f/'res.apk') as z,zipfile.ZipFile(f/'unsigned.apk','w') as out:
  for item in z.infolist():out.writestr(item,z.read(item.filename))
  out.write(f/'dex/classes.dex','classes.dex')
 run(executable(TOOLS,'zipalign'),'-f','4',f/'unsigned.apk',f/'aligned.apk');env.setdefault('MARCOTV_STORE_PASSWORD','android')
 run(executable(JDK/'bin','java'),'-jar',TOOLS/'lib/apksigner.jar','sign','--min-sdk-version','23','--ks',os.environ.get('MARCOTV_KEYSTORE',str(ROOT/'private/development.p12')),'--ks-key-alias',os.environ.get('MARCOTV_KEY_ALIAS','tvhome'),'--ks-pass','env:MARCOTV_STORE_PASSWORD','--out',f/'tests.apk',f/'aligned.apk')
 if a.compile_only:
  print('Home setup UI test APK compiled; no device connected or changed.');return
 print(call('install','-r',str(f/'tests.apk')),flush=True)
 try:
  result=call('shell','am','instrument','-w','com.pilipala.tvhome.setup.tests/com.pilipala.tvhome.'+check_class);(f/'result.txt').write_text(result,encoding='utf8');print(result)
  expected='OK 16 feedback device checks' if a.feedback else 'OK 37 home setup device checks'
  if expected not in result or 'FAIL' in result:raise RuntimeError('Device checks failed')
 finally:print(call('uninstall','com.pilipala.tvhome.setup.tests'))
 # Instrumentation can pass its UI checks while Android drops the target's
 # accessibility binding. Do not report a healthy HOME setup from UI results alone.
 current_services=call('shell','settings','get','secure','enabled_accessibility_services').strip()
 def components(value):
  return {x.replace('com.pilipala.tvhome/com.pilipala.tvhome.HomeKeyService','com.pilipala.tvhome/.HomeKeyService') for x in value.split(':') if x and x!='null'}
 if components(current_services)!=components(original_services):
  raise RuntimeError('UI checks completed, but accessibility settings changed. HOME recovery is not verified; use the explicit launcher setup flow to restore it. No system settings were overwritten by the runner.')
 if 'com.pilipala.tvhome/.HomeKeyService' in components(original_services):
  binding=call('shell','dumpsys','activity','services','com.pilipala.tvhome.HomeKeyService')
  if 'ProcessRecord' not in binding or 'DEAD com.pilipala.tvhome/' in binding:
   raise RuntimeError('UI checks completed, but the HOME binding is unavailable after instrumentation. Restore it through the launcher setup flow before device acceptance.')
 print('Post-test accessibility settings and HOME binding verified. Physical HOME and wake checks remain separate.')
if __name__=='__main__':main()
