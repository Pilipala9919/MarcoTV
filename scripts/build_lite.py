# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Build the isolated Lite APK against real API 14 stubs (not just minSdk=14)."""
import os,shutil,zipfile,hashlib,json,xml.etree.ElementTree as ET
from pathlib import Path
from build import ROOT,JDK,TOOLS,ANDROID,env,executable,run

SOURCE=ROOT/'lite/src/main';BUILD=ROOT/'build/lite';ASSETS=BUILD/'assets'
SDK14=Path(os.environ.get('MARCOTV_API14_JAR',str(ROOT/'.tools/lite-legacy/platform/android-4.0.2/android.jar')))
SHARED=['ClockScene.java','MotionSpring.java','PreviewPreferences.java']
def build():
 if not SDK14.is_file():raise RuntimeError('Install official API 14 platform and set MARCOTV_API14_JAR. See lite/README.md.')
 version=ET.parse(SOURCE/'AndroidManifest.xml').getroot().get('{http://schemas.android.com/apk/res/android}versionName')
 for folder in ['classes','dex','assets/fonts','assets/licenses','assets/wallpapers']:(BUILD/folder).mkdir(parents=True,exist_ok=True)
 for folder,pattern in [('classes','*.class'),('dex','*.dex'),('assets/wallpapers','*')]:
  for p in (BUILD/folder).rglob(pattern):
   assert p.resolve().is_relative_to(BUILD.resolve());p.unlink()
 for p in (ROOT/'app/src/main/assets/fonts').glob('*'):shutil.copy2(p,ASSETS/'fonts'/p.name)
 for name in ['aurora.jpg','coast.jpg']:shutil.copy2(SOURCE/'assets/wallpapers'/name,ASSETS/'wallpapers'/name)
 for p in [ROOT/'LICENSE',ROOT/'NOTICE',*list((ROOT/'licenses').glob('*-OFL.txt'))]:shutil.copy2(p,ASSETS/'licenses'/p.name)
 run(executable(TOOLS,'aapt2'),'compile','--dir',SOURCE/'res','-o',BUILD/'resources.zip')
 run(executable(TOOLS,'aapt2'),'link','-o',BUILD/'resources.apk','--manifest',SOURCE/'AndroidManifest.xml','-I',SDK14,'-A',ASSETS,BUILD/'resources.zip')
 sources=list((SOURCE/'java').rglob('*.java'))+[ROOT/'app/src/main/java/com/pilipala/tvhome'/name for name in SHARED]
 run(executable(JDK/'bin','javac'),'-encoding','UTF-8','-source','8','-target','8','-Xlint:-options','-classpath',SDK14,'-d',BUILD/'classes',*sources)
 with zipfile.ZipFile(BUILD/'classes.jar','w') as z:
  for p in (BUILD/'classes').rglob('*.class'):z.write(p,p.relative_to(BUILD/'classes').as_posix())
 run(executable(JDK/'bin','java'),'-cp',TOOLS/'lib/d8.jar','com.android.tools.r8.D8','--min-api','14','--lib',SDK14,'--output',BUILD/'dex',BUILD/'classes.jar')
 with zipfile.ZipFile(BUILD/'resources.apk') as z,zipfile.ZipFile(BUILD/'unsigned.apk','w',zipfile.ZIP_DEFLATED) as out:
  for item in z.infolist():out.writestr(item,z.read(item.filename))
  out.write(BUILD/'dex/classes.dex','classes.dex')
 run(executable(TOOLS,'zipalign'),'-f','4',BUILD/'unsigned.apk',BUILD/'aligned.apk')
 apk=ROOT/f'dist/MarcoTV-Lite-{version}.apk';key=Path(os.environ.get('MARCOTV_KEYSTORE',str(ROOT/'private/development.p12')))
 if not key.exists():raise RuntimeError('Run regular build once to initialize local development signing key, or configure MARCOTV_KEYSTORE.')
 env.setdefault('MARCOTV_STORE_PASSWORD','android')
 run(executable(JDK/'bin','java'),'-jar',TOOLS/'lib/apksigner.jar','sign','--min-sdk-version','14','--ks',key,'--ks-key-alias',os.environ.get('MARCOTV_KEY_ALIAS','tvhome'),'--ks-pass','env:MARCOTV_STORE_PASSWORD','--out',apk,BUILD/'aligned.apk')
 run(executable(JDK/'bin','java'),'-jar',TOOLS/'lib/apksigner.jar','verify','--min-sdk-version','14','--verbose',apk)
 run(executable(TOOLS,'zipalign'),'-c','4',apk)
 sha=hashlib.sha256(apk.read_bytes()).hexdigest();apk.with_suffix('.apk.sha256').write_text(sha+'  '+apk.name+'\n',encoding='utf8')
 report=dict(version=version,package='com.pilipala.tvhome.lite',compile_api=14,min_api=14,shared_sources=SHARED,bytes=apk.stat().st_size,sha256=sha,physical_android4_tv_verified=False)
 (BUILD/'build-report.json').write_text(json.dumps(report,indent=2),encoding='utf8');print(json.dumps(report))
if __name__=='__main__':build()
