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
OFFICIAL_SIGNER_SHA256 = '484874b8ec541bfb73f10bb584e8f814cb81ba08ec0926cebe4aa94f407c74c9'
env = {**os.environ, 'JAVA_HOME': str(JDK), 'PATH': str(JDK / 'bin') + os.pathsep + os.environ.get('PATH', '')}

def executable(directory, name):
    return directory / (name + ('.exe' if os.name == 'nt' else ''))


def run(*args):
    subprocess.run([str(a) for a in args], check=True, cwd=ROOT, env=env)


def signer_sha256(apk):
    out = subprocess.check_output(
        [str(executable(JDK / 'bin', 'java')), '-jar', str(TOOLS / 'lib/apksigner.jar'), 'verify', '--print-certs', str(apk)],
        cwd=ROOT, env=env, text=True, encoding='utf-8')
    for line in out.splitlines():
        if line.startswith('Signer #1 certificate SHA-256 digest:'):
            return line.split(':', 1)[1].strip().lower()
    raise RuntimeError('Unable to read APK signer certificate digest')


def build(restricted=False, release=False):
    global BUILD
    BUILD = ROOT / 'build' / 'restricted' if restricted else ROOT / 'build'
    import shutil
    info = (SOURCE / 'java/com/pilipala/tvhome/ProjectInfo.java').read_text(encoding='utf-8')
    if f'VERSION="{VERSION}"' not in info:
        raise RuntimeError('Manifest and ProjectInfo versions differ')
    licenses = SOURCE / 'assets/licenses'
    licenses.mkdir(exist_ok=True)
    for original, name in [(ROOT/'licenses/QR-Code-generator-MIT.txt','QR-Code-generator-MIT.txt'), (ROOT/'LICENSE','GPL-3.0-only.txt'), (ROOT/'NOTICE','NOTICE'),
                           (ROOT/'licenses/Material-Icons-Apache-2.0.txt','Material-Icons-Apache-2.0.txt')]:
        shutil.copy2(original, licenses/name)
    for original in (ROOT/'licenses').glob('*-OFL.txt'): shutil.copy2(original, licenses/original.name)
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
    manifest = SOURCE / 'AndroidManifest.xml'
    if restricted:
        tree = ET.parse(manifest)
        app = tree.getroot().find('application')
        ns = '{http://schemas.android.com/apk/res/android}'
        for alias in list(app.findall('activity-alias')):
            if alias.get(ns+'name') == '.HomeActivity': app.remove(alias)
        if any(c.get(ns+'name') == 'android.intent.category.HOME' for c in tree.iter('category')):
            raise RuntimeError('Restricted package must not declare HOME')
        ET.SubElement(app, 'meta-data', {ns+'name':'com.pilipala.tvhome.EDITION', ns+'value':'restricted'})
        manifest = BUILD / 'AndroidManifest.xml'
        ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')
        tree.write(manifest, encoding='utf-8', xml_declaration=True)
    run(executable(TOOLS,'aapt2'), 'link', '-o', BUILD / 'resources.apk', '--manifest', manifest,
        '-I', ANDROID, '--auto-add-overlay', '-A', SOURCE / 'assets', BUILD / 'resources.zip')
    excluded_from_apk = {'BackgroundPlaybackSession.java', 'WeatherRefreshPolicy.java'}
    sources = sorted(p for p in (SOURCE / 'java').rglob('*.java') if p.name not in excluded_from_apk)
    if restricted:
        edition = BUILD / 'generated/BuildEdition.java'
        edition.parent.mkdir(parents=True, exist_ok=True)
        original = SOURCE / 'java/com/pilipala/tvhome/BuildEdition.java'
        edition.write_text(original.read_text(encoding='utf-8').replace('RESTRICTED = false', 'RESTRICTED = true'), encoding='utf-8')
        sources = [s for s in sources if s != original] + [edition]
    run(executable(JDK/'bin','javac'), '-encoding', 'UTF-8', '--release', '8', '-Xlint:-options',
        '-classpath', ANDROID, '-d', BUILD / 'classes', *sources)
    classes = sorted((BUILD / 'classes').rglob('*.class'))
    classes_jar = BUILD / 'classes.jar'
    with zipfile.ZipFile(classes_jar, 'w', zipfile.ZIP_DEFLATED) as z:
        for p in classes:
            info = zipfile.ZipInfo(p.relative_to(BUILD / 'classes').as_posix(), (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, p.read_bytes())
    run(executable(JDK/'bin','java'), '-cp', TOOLS / 'lib/d8.jar', 'com.android.tools.r8.D8',
        '--release', '--min-api', '23', '--lib', ANDROID, '--output', BUILD / 'dex', classes_jar)
    unsigned = BUILD / 'unsigned.apk'
    with zipfile.ZipFile(BUILD / 'resources.apk') as source, zipfile.ZipFile(unsigned, 'w', zipfile.ZIP_DEFLATED) as target:
        for info in source.infolist():
            entry = zipfile.ZipInfo(info.filename, (2026, 1, 1, 0, 0, 0))
            entry.compress_type = info.compress_type
            entry.extract_version = 10 if info.compress_type == zipfile.ZIP_STORED else 20
            entry.create_version = entry.extract_version
            target.writestr(entry, source.read(info.filename))
        for dex in sorted((BUILD / 'dex').glob('*.dex')):
            dex_entry = zipfile.ZipInfo(dex.name, (2026, 1, 1, 0, 0, 0))
            dex_entry.compress_type = zipfile.ZIP_DEFLATED
            dex_entry.extract_version = 20
            dex_entry.create_version = 20
            target.writestr(dex_entry, dex.read_bytes())
    run(executable(TOOLS,'zipalign'), '-f', '4', unsigned, BUILD / 'aligned.apk')
    keys = ROOT / 'private'
    keys.mkdir(exist_ok=True)
    keystore = Path(os.environ.get('MARCOTV_KEYSTORE', str(keys / 'development.p12')))
    alias = os.environ.get('MARCOTV_KEY_ALIAS','tvhome')
    env.setdefault('MARCOTV_STORE_PASSWORD','android')
    if env['MARCOTV_STORE_PASSWORD']=='android':print('WARNING: signing with the default development password. Back up the keystore offline and set MARCOTV_STORE_PASSWORD; see the signing section of the build docs.')
    if not keystore.exists():
        if release or os.environ.get('MARCOTV_RELEASE') == '1':
            raise RuntimeError('Release build requires the existing official signing key; refusing to generate a new keystore.')
        if os.environ.get('MARCOTV_KEYSTORE'):
            raise RuntimeError('Configured signing key does not exist')
        print('WARNING: private/development.p12 not found; generating a local-only development key that CANNOT upgrade official releases.')
        run(executable(JDK/'bin','keytool'), '-genkeypair', '-keystore', keystore, '-storetype', 'PKCS12',
            '-alias', alias, '-storepass:env', 'MARCOTV_STORE_PASSWORD', '-keypass:env', 'MARCOTV_STORE_PASSWORD', '-keyalg', 'RSA',
            '-keysize', '2048', '-validity', '10000', '-dname', 'CN=Pilipala, OU=Marco TV')
    apk = DIST / f'MarcoTV-{VERSION}{"-Restricted" if restricted else ""}.apk'
    run(executable(JDK/'bin','java'), '-jar', TOOLS / 'lib/apksigner.jar', 'sign',
        '--min-sdk-version', '23', '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true',
        '--ks', keystore, '--ks-key-alias', alias, '--ks-pass', 'env:MARCOTV_STORE_PASSWORD', '--out', apk, BUILD / 'aligned.apk')
    run(executable(JDK/'bin','java'), '-jar', TOOLS / 'lib/apksigner.jar', 'verify', '--min-sdk-version', '23', '--verbose', apk)
    cert = signer_sha256(apk)
    if (release or os.environ.get('MARCOTV_RELEASE') == '1') and cert != OFFICIAL_SIGNER_SHA256:
        raise RuntimeError(f'Release APK signer {cert} does not match official release signer {OFFICIAL_SIGNER_SHA256}')
    run(executable(TOOLS,'zipalign'), '-c', '4', apk)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    (DIST / (apk.name + '.sha256')).write_text(f'{digest}  {apk.name}\n')
    print(f'Built: {apk}\nSHA256: {digest}\nSigner SHA256: {cert}\nSize: {apk.stat().st_size} bytes')


if __name__ == '__main__':
    import argparse
    parser=argparse.ArgumentParser()
    parser.add_argument('--restricted', action='store_true', help='Build without HOME declaration for restricted firmware')
    parser.add_argument('--release', action='store_true', help='Require existing official release signing key and verify certificate digest')
    args=parser.parse_args()
    build(args.restricted, args.release)
