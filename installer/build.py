# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Build standalone helpers with a verified, pinned official TV APK embedded."""
import hashlib,json,os,subprocess,sys,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'scripts'))
from build import JDK,ANDROID,TOOLS,env,executable
VERSION='1.0.0'
PAYLOAD_VERSION='0.12.0'
PAYLOAD_SHA='72224b15f6ece6c024ccfecbeab7b9a8dc5a8b40204407d310889ba0fd1db218'
OUT=ROOT/'dist';BUILD=ROOT/'build/installer'
def run(*args):subprocess.run([str(x) for x in args],cwd=ROOT,env=env,check=True)
def write(path,text):path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(text.encode('utf-8'))
def main():
    apk=OUT/f'MarcoTV-{PAYLOAD_VERSION}.apk'
    if not apk.exists() or hashlib.sha256(apk.read_bytes()).hexdigest()!=PAYLOAD_SHA:raise RuntimeError('Download the official 0.12.0 release APK first; payload digest must match.')
    BUILD.mkdir(parents=True,exist_ok=True);size=apk.stat().st_size
    write(BUILD/'ReleaseInfo.cs',f'// Copyright (C) 2026 Pilipala\nnamespace MarcoInstaller {{ static class ReleaseInfo {{ public const string Version="{PAYLOAD_VERSION}", Sha="{PAYLOAD_SHA}"; public const long Size={size}; }} }}\n')
    csc=Path(os.environ['WINDIR'])/'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
    windows=OUT/f'MarcoTV-Installer-{VERSION}-Windows.exe'
    sources=[BUILD/'ReleaseInfo.cs',ROOT/'installer/windows/AdbWire.cs',ROOT/'installer/windows/Program.cs']
    run(csc,'/nologo','/target:winexe','/platform:anycpu','/optimize+',f'/out:{windows}',f'/resource:{apk},MarcoTV.apk','/r:System.Windows.Forms.dll','/r:System.Drawing.dll','/r:System.Numerics.dll',*sources)
    # Console QA uses the identical code/resources; it is not distributed as a second helper.
    run(csc,'/nologo','/target:exe',f'/out:{BUILD/"checks.exe"}',f'/resource:{apk},MarcoTV.apk','/r:System.Windows.Forms.dll','/r:System.Drawing.dll','/r:System.Numerics.dll',*sources)
    run(BUILD/'checks.exe','--self-test')
    src=ROOT/'installer/android';assets=BUILD/'assets';assets.mkdir(exist_ok=True)
    import shutil
    shutil.copy2(apk,assets/'MarcoTV.apk');shutil.copy2(ROOT/'LICENSE',assets/'LICENSE.txt')
    write(BUILD/'java/com/pilipala/tvinstaller/ReleaseInfo.java',f'// Copyright (C) 2026 Pilipala\npackage com.pilipala.tvinstaller; final class ReleaseInfo {{ static final String VERSION="{PAYLOAD_VERSION}",SHA="{PAYLOAD_SHA}";static final long SIZE={size}L; }}\n')
    classes=BUILD/'classes';dex=BUILD/'dex'
    for folder in [classes,dex]:
        folder.mkdir(exist_ok=True)
        assert folder.resolve().is_relative_to(BUILD.resolve())
        for f in folder.rglob('*'):
            if f.is_file() and f.suffix in ('.class','.dex'):f.unlink()
    run(executable(TOOLS,'aapt2'),'link','-o',BUILD/'resources.apk','--manifest',src/'AndroidManifest.xml','-I',ANDROID,'-A',assets)
    run(executable(JDK/'bin','javac'),'-encoding','UTF-8','-source','8','-target','8','-Xlint:-options','-classpath',ANDROID,'-d',classes,*sorted((src/'src').rglob('*.java')),*sorted((BUILD/'java').rglob('*.java')))
    with zipfile.ZipFile(BUILD/'classes.jar','w') as z:
        for f in classes.rglob('*.class'):z.write(f,f.relative_to(classes).as_posix())
    run(executable(JDK/'bin','java'),'-cp',TOOLS/'lib/d8.jar','com.android.tools.r8.D8','--min-api','26','--lib',ANDROID,'--output',dex,BUILD/'classes.jar')
    with zipfile.ZipFile(BUILD/'resources.apk') as srczip,zipfile.ZipFile(BUILD/'unsigned.apk','w',zipfile.ZIP_DEFLATED) as z:
        for f in srczip.infolist():z.writestr(f,srczip.read(f.filename))
        for f in dex.glob('*.dex'):z.write(f,f.name)
    run(executable(TOOLS,'zipalign'),'-f','4',BUILD/'unsigned.apk',BUILD/'aligned.apk')
    android=OUT/f'MarcoTV-Installer-{VERSION}-Android.apk';env.setdefault('MARCOTV_STORE_PASSWORD','android')
    key=Path(os.environ.get('MARCOTV_KEYSTORE',str(ROOT/'private/development.p12')))
    alias=os.environ.get('MARCOTV_KEY_ALIAS','tvhome')
    if not key.exists():
        if os.environ.get('MARCOTV_KEYSTORE'):raise RuntimeError('Configured signing key does not exist')
        key.parent.mkdir(exist_ok=True)
        run(executable(JDK/'bin','keytool'),'-genkeypair','-keystore',key,'-storetype','PKCS12','-alias',alias,
            '-storepass:env','MARCOTV_STORE_PASSWORD','-keypass:env','MARCOTV_STORE_PASSWORD','-keyalg','RSA',
            '-keysize','2048','-validity','10000','-dname','CN=Pilipala, OU=Marco TV Installer')
    run(executable(JDK/'bin','java'),'-jar',TOOLS/'lib/apksigner.jar','sign','--ks',key,'--ks-key-alias',alias,'--ks-pass','env:MARCOTV_STORE_PASSWORD','--out',android,BUILD/'aligned.apk')
    run(executable(JDK/'bin','java'),'-jar',TOOLS/'lib/apksigner.jar','verify','--verbose',android)
    for f in [windows,android]:
        sha=hashlib.sha256(f.read_bytes()).hexdigest();write(f.with_suffix(f.suffix+'.sha256'),sha+'  '+f.name+'\n');print(f.name,f.stat().st_size,sha)
if __name__=='__main__':main()
