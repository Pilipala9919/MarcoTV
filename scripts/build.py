# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Build the dependency-free native launcher with Android's official packaging tools."""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
configuration = ROOT / '.tools/toolchain.json'
if configuration.exists():
    CONFIG = json.loads(configuration.read_text())
else:
    sdk = os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
    jdk = os.environ.get('JAVA_HOME')
    if not sdk or not jdk:
        raise RuntimeError('Run scripts/bootstrap.py on Windows, or set JAVA_HOME and ANDROID_SDK_ROOT.')
    CONFIG = {'jdk': jdk, 'android_jar': str(Path(sdk) / 'platforms/android-28/android.jar'),
              'build_tools': str(Path(sdk) / 'build-tools' / os.environ.get('MARCOTV_BUILD_TOOLS', '35.0.0'))}
JDK = Path(CONFIG['jdk'])
ANDROID = Path(CONFIG['android_jar'])
TOOLS = Path(CONFIG['build_tools'])
BUILD = ROOT / 'build'
DIST = ROOT / 'dist'
SOURCE = ROOT / 'app/src/main'
VERSION = ET.parse(SOURCE / 'AndroidManifest.xml').getroot().get('{http://schemas.android.com/apk/res/android}versionName')
env = {**os.environ, 'JAVA_HOME': str(JDK), 'PATH': str(JDK / 'bin') + os.pathsep + os.environ.get('PATH', '')}

def executable(directory, name):
    return directory / (name + ('.exe' if os.name == 'nt' else ''))


def run(*args):
    subprocess.run([str(a) for a in args], check=True, cwd=ROOT, env=env)


def build():
    import shutil
    info = (SOURCE / 'java/com/pilipala/tvhome/ProjectInfo.java').read_text(encoding='utf-8')
    if f'VERSION="{VERSION}"' not in info:
        raise RuntimeError('Manifest and ProjectInfo versions differ')
    licenses = SOURCE / 'assets/licenses'
    licenses.mkdir(exist_ok=True)
    for original, name in [(ROOT/'LICENSE','GPL-3.0-only.txt'), (ROOT/'NOTICE','NOTICE'),
                           (ROOT/'licenses/Material-Icons-Apache-2.0.txt','Material-Icons-Apache-2.0.txt')]:
        shutil.copy2(original, licenses/name)
    for p in [BUILD, BUILD / 'classes', BUILD / 'dex', BUILD / 'resources', DIST]: p.mkdir(exist_ok=True, parents=True)
    # Renamed or removed Java classes must not survive incremental local builds.
    # Only delete compiler outputs inside the verified build directories.
    for folder, pattern in [(BUILD / 'classes', '*.class'), (BUILD / 'dex', '*.dex')]:
        boundary = folder.resolve()
        if not boundary.is_relative_to(BUILD.resolve()):
            raise RuntimeError('Generated output directory escapes build root')
        for previous in folder.rglob(pattern):
            if not previous.resolve().is_relative_to(boundary):
                raise RuntimeError('Generated output escapes its directory')
            previous.unlink()
    run(executable(TOOLS,'aapt2'), 'compile', '--dir', SOURCE / 'res', '-o', BUILD / 'resources.zip')
    run(executable(TOOLS,'aapt2'), 'link', '-o', BUILD / 'resources.apk', '--manifest', SOURCE / 'AndroidManifest.xml',
        '-I', ANDROID, '--auto-add-overlay', '-A', SOURCE / 'assets', BUILD / 'resources.zip')
    sources = sorted((SOURCE / 'java').rglob('*.java'))
    run(executable(JDK/'bin','javac'), '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:-options',
        '-classpath', ANDROID, '-d', BUILD / 'classes', *sources)
    classes = sorted((BUILD / 'classes').rglob('*.class'))
    classes_jar = BUILD / 'classes.jar'
    with zipfile.ZipFile(classes_jar, 'w', zipfile.ZIP_DEFLATED) as z:
        for p in classes: z.write(p, p.relative_to(BUILD / 'classes').as_posix())
    run(executable(JDK/'bin','java'), '-cp', TOOLS / 'lib/d8.jar', 'com.android.tools.r8.D8',
        '--min-api', '23', '--lib', ANDROID, '--output', BUILD / 'dex', classes_jar)
    unsigned = BUILD / 'unsigned.apk'
    with zipfile.ZipFile(BUILD / 'resources.apk') as source, zipfile.ZipFile(unsigned, 'w', zipfile.ZIP_DEFLATED) as target:
        for info in source.infolist(): target.writestr(info, source.read(info.filename))
        for dex in (BUILD / 'dex').glob('*.dex'): target.write(dex, dex.name)
    run(executable(TOOLS,'zipalign'), '-f', '4', unsigned, BUILD / 'aligned.apk')
    keys = ROOT / 'private'
    keys.mkdir(exist_ok=True)
    keystore = Path(os.environ.get('MARCOTV_KEYSTORE', str(keys / 'development.p12')))
    alias = os.environ.get('MARCOTV_KEY_ALIAS','tvhome')
    env.setdefault('MARCOTV_STORE_PASSWORD','android')
    if not keystore.exists():
        if os.environ.get('MARCOTV_KEYSTORE'):
            raise RuntimeError('Configured signing key does not exist')
        run(executable(JDK/'bin','keytool'), '-genkeypair', '-keystore', keystore, '-storetype', 'PKCS12',
            '-alias', alias, '-storepass:env', 'MARCOTV_STORE_PASSWORD', '-keypass:env', 'MARCOTV_STORE_PASSWORD', '-keyalg', 'RSA',
            '-keysize', '2048', '-validity', '10000', '-dname', 'CN=Pilipala, OU=Marco TV')
    apk = DIST / f'MarcoTV-{VERSION}.apk'
    run(executable(JDK/'bin','java'), '-jar', TOOLS / 'lib/apksigner.jar', 'sign', '--ks', keystore,
        '--ks-key-alias', alias, '--ks-pass', 'env:MARCOTV_STORE_PASSWORD', '--out', apk, BUILD / 'aligned.apk')
    run(executable(JDK/'bin','java'), '-jar', TOOLS / 'lib/apksigner.jar', 'verify', '--verbose', apk)
    run(executable(TOOLS,'zipalign'), '-c', '4', apk)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    (DIST / (apk.name + '.sha256')).write_text(f'{digest}  {apk.name}\n')
    print(f'Built: {apk}\nSHA256: {digest}\nSize: {apk.stat().st_size} bytes')


if __name__ == '__main__': build()
