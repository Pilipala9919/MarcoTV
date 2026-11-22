# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Build the isolated Lite APK against real API 14 stubs (not just minSdk=14)."""
import argparse,os,shutil,zipfile,hashlib,json,xml.etree.ElementTree as ET
from pathlib import Path
from build import OFFICIAL_SIGNER_SHA256,ROOT,JDK,TOOLS,ANDROID,env,executable,run,signer_sha256

SOURCE=ROOT/'lite/src/main'
SDK14=Path(os.environ.get('MARCOTV_API14_JAR',str(ROOT/'.tools/lite-legacy/platform/android-4.0.2/android.jar')))
SHARED=['ClockScene.java','MotionSpring.java','PreviewPreferences.java']

def build(restricted=False,release=False):
 if not SDK14.is_file():raise RuntimeError('Install official API 14 platform and set MARCOTV_API14_JAR. See lite/README.md.')
 BUILD=ROOT/('build/lite-restricted' if restricted else 'build/lite');ASSETS=BUILD/'assets'
 manifest_tree=ET.parse(SOURCE/'AndroidManifest.xml')
 ns='{http://schemas.android.com/apk/res/android}'
 version=manifest_tree.getroot().get(ns+'versionName')
 settings_src=(SOURCE/'java/com/pilipala/tvhome/LiteSettings.java').read_text(encoding='utf-8')
 if f'VERSION="{version}"' not in settings_src:raise RuntimeError('Lite manifest and LiteSettings versions differ')
 for folder in ['classes','dex','assets/fonts','assets/licenses','assets/wallpapers']:(BUILD/folder).mkdir(parents=True,exist_ok=True)
 (ROOT/'dist').mkdir(parents=True,exist_ok=True)
 for folder,pattern in [('classes','*.class'),('dex','*.dex'),('assets/wallpapers','*')]:
  for p in (BUILD/folder).rglob(pattern):
   assert p.resolve().is_relative_to(BUILD.resolve());p.unlink()
 for p in (ROOT/'app/src/main/assets/fonts').glob('*'):shutil.copy2(p,ASSETS/'fonts'/p.name)
 for name in ['aurora.jpg','coast.jpg']:shutil.copy2(SOURCE/'assets/wallpapers'/name,ASSETS/'wallpapers'/name)
 for p in [ROOT/'LICENSE',ROOT/'NOTICE',*list((ROOT/'licenses').glob('*-OFL.txt'))]:shutil.copy2(p,ASSETS/'licenses'/p.name)
 manifest=SOURCE/'AndroidManifest.xml'
 if restricted:
  activity=manifest_tree.getroot().find('application').find('activity')
  for flt in list(activity.findall('intent-filter')):
   if any(c.get(ns+'name')=='android.intent.category.HOME' for c in flt.findall('category')):
    activity.remove(flt)
  if any(c.get(ns+'name')=='android.intent.category.HOME' for c in manifest_tree.iter('category')):
   raise RuntimeError('Restricted Lite package must not declare HOME')
  manifest=BUILD/'AndroidManifest.xml'
  ET.register_namespace('android','http://schemas.android.com/apk/res/android')
  manifest_tree.write(manifest,encoding='utf-8',xml_declaration=True)
 run(executable(TOOLS,'aapt2'),'compile','--dir',SOURCE/'res','-o',BUILD/'resources.zip')
 run(executable(TOOLS,'aapt2'),'link','-o',BUILD/'resources.apk','--manifest',manifest,'-I',SDK14,'-A',ASSETS,BUILD/'resources.zip')
 sources=sorted(list((SOURCE/'java').rglob('*.java'))+[ROOT/'app/src/main/java/com/pilipala/tvhome'/name for name in SHARED])
 run(executable(JDK/'bin','javac'),'-encoding','UTF-8','-source','8','-target','8','-bootclasspath',SDK14,'-extdirs','','-Xlint:-options','-classpath',SDK14,'-d',BUILD/'classes',*sources)
 with zipfile.ZipFile(BUILD/'classes.jar','w',zipfile.ZIP_DEFLATED) as z:
  for p in sorted((BUILD/'classes').rglob('*.class')):
   info=zipfile.ZipInfo(p.relative_to(BUILD/'classes').as_posix(),(2026,1,1,0,0,0))
   info.compress_type=zipfile.ZIP_DEFLATED
   z.writestr(info,p.read_bytes())
 run(executable(JDK/'bin','java'),'-cp',TOOLS/'lib/d8.jar','com.android.tools.r8.D8','--release','--min-api','14','--lib',SDK14,'--output',BUILD/'dex',BUILD/'classes.jar')
 with zipfile.ZipFile(BUILD/'resources.apk') as z,zipfile.ZipFile(BUILD/'unsigned.apk','w',zipfile.ZIP_DEFLATED) as out:
  for item in z.infolist():
   info=zipfile.ZipInfo(item.filename,(2026,1,1,0,0,0))
   info.compress_type=item.compress_type
   out.writestr(info,z.read(item.filename))
  dex_info=zipfile.ZipInfo('classes.dex',(2026,1,1,0,0,0))
  dex_info.compress_type=zipfile.ZIP_DEFLATED
  out.writestr(dex_info,(BUILD/'dex/classes.dex').read_bytes())
 run(executable(TOOLS,'zipalign'),'-f','4',BUILD/'unsigned.apk',BUILD/'aligned.apk')
 suffix='-Restricted' if restricted else ''
 apk=ROOT/f'dist/MarcoTV-Lite-{version}{suffix}.apk';key=Path(os.environ.get('MARCOTV_KEYSTORE',str(ROOT/'private/development.p12')))
 if not key.exists():raise RuntimeError('Run regular build once to initialize local development signing key, or configure MARCOTV_KEYSTORE.')
 env.setdefault('MARCOTV_STORE_PASSWORD','android')
 if env['MARCOTV_STORE_PASSWORD']=='android':print('WARNING: signing with the default development password. Back up the keystore offline and set MARCOTV_STORE_PASSWORD; see the signing section of the build docs.')
 run(executable(JDK/'bin','java'),'-jar',TOOLS/'lib/apksigner.jar','sign','--min-sdk-version','14','--ks',key,'--ks-key-alias',os.environ.get('MARCOTV_KEY_ALIAS','tvhome'),'--ks-pass','env:MARCOTV_STORE_PASSWORD','--out',apk,BUILD/'aligned.apk')
 run(executable(JDK/'bin','java'),'-jar',TOOLS/'lib/apksigner.jar','verify','--min-sdk-version','14','--verbose',apk)
 cert=signer_sha256(apk)
 if (release or os.environ.get('MARCOTV_RELEASE')=='1') and cert!=OFFICIAL_SIGNER_SHA256:
  raise RuntimeError(f'Lite release APK signer {cert} does not match official release signer {OFFICIAL_SIGNER_SHA256}')
 run(executable(TOOLS,'zipalign'),'-c','4',apk)
 sha=hashlib.sha256(apk.read_bytes()).hexdigest();apk.with_suffix('.apk.sha256').write_text(sha+'  '+apk.name+'\n',encoding='utf8')
 report=dict(version=version,restricted=restricted,package='com.pilipala.tvhome.lite',compile_api=14,min_api=14,shared_sources=SHARED,bytes=apk.stat().st_size,sha256=sha,signer_sha256=cert,physical_android4_tv_verified=False)
 (BUILD/'build-report.json').write_text(json.dumps(report,indent=2),encoding='utf8');print(json.dumps(report))

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--restricted',action='store_true',help='Strip CATEGORY_HOME for firmware that blocks third-party launchers')
 parser.add_argument('--release',action='store_true',help='Verify official release signing certificate')
 args=parser.parse_args()
 build(args.restricted,args.release)
