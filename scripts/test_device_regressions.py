# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Install a temporary test APK to exercise cache invalidation and navigation on a connected TV.

Build and install the current Marco TV APK first, using the same signing key. This test restarts
the app and opens its UI, but cleans up its own test card and does not change user settings.
"""
import argparse
import json
import os
import subprocess
import zipfile
from pathlib import Path
from build import ROOT, BUILD, SOURCE, JDK, ANDROID, TOOLS, env, executable, run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    args = parser.parse_args()
    folder = BUILD / 'device-regression'
    for name in ['', 'classes', 'dex']:
        (folder / name).mkdir(parents=True, exist_ok=True)
    manifest = folder / 'AndroidManifest.xml'
    manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.pilipala.tvhome.regression">
      <uses-sdk android:minSdkVersion="23" android:targetSdkVersion="28" />
      <application android:label="Marco TV regression checks" />
      <instrumentation android:name="com.pilipala.tvhome.DeviceRegressionChecks" android:targetPackage="com.pilipala.tvhome" />
    </manifest>''', encoding='utf-8')
    run(executable(TOOLS, 'aapt2'), 'link', '-o', folder / 'resources.apk', '--manifest', manifest, '-I', ANDROID)
    run(executable(JDK / 'bin', 'javac'), '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:-options',
        '-classpath', str(ANDROID) + os.pathsep + str(BUILD / 'classes.jar'), '-d', folder / 'classes',
        ROOT / 'scripts/DeviceRegressionChecks.java')
    with zipfile.ZipFile(folder / 'classes.jar', 'w') as archive:
        for file in (folder / 'classes').rglob('*.class'):
            archive.write(file, file.relative_to(folder / 'classes').as_posix())
    run(executable(JDK / 'bin', 'java'), '-cp', TOOLS / 'lib/d8.jar', 'com.android.tools.r8.D8', '--min-api', '23',
        '--lib', ANDROID, '--classpath', BUILD / 'classes.jar', '--output', folder / 'dex', folder / 'classes.jar')
    with zipfile.ZipFile(folder / 'resources.apk') as source, zipfile.ZipFile(folder / 'unsigned.apk', 'w') as target:
        for entry in source.infolist(): target.writestr(entry, source.read(entry.filename))
        for dex in (folder / 'dex').glob('*.dex'): target.write(dex, dex.name)
    run(executable(TOOLS, 'zipalign'), '-f', '4', folder / 'unsigned.apk', folder / 'aligned.apk')
    env.setdefault('MARCOTV_STORE_PASSWORD', 'android')
    run(executable(JDK / 'bin', 'java'), '-jar', TOOLS / 'lib/apksigner.jar', 'sign', '--ks',
        os.environ.get('MARCOTV_KEYSTORE', str(ROOT / 'private/development.p12')),
        '--ks-key-alias', os.environ.get('MARCOTV_KEY_ALIAS', 'tvhome'), '--ks-pass', 'env:MARCOTV_STORE_PASSWORD',
        '--out', folder / 'checks.apk', folder / 'aligned.apk')
    def adb(*command):
        return subprocess.run([args.adb, '-s', args.serial, *map(str, command)], capture_output=True,
                              text=True, encoding='utf-8', errors='replace', timeout=120, check=True).stdout
    installed = False
    try:
        if 'Success' not in adb('install', '-r', folder / 'checks.apk'): raise RuntimeError('Test APK install failed')
        installed = True
        output = adb('shell', 'am', 'instrument', '-w',
                     'com.pilipala.tvhome.regression/com.pilipala.tvhome.DeviceRegressionChecks')
        print(output)
        (folder / 'result.txt').write_text(output, encoding='utf-8')
        if 'OK 8 device regression checks' not in output or 'FAIL' in output:
            raise RuntimeError('Device regression failed; see local result.txt')
    finally:
        if installed:
            adb('uninstall', 'com.pilipala.tvhome.regression')
            adb('shell', 'am', 'start', '-n', 'com.pilipala.tvhome/.MainActivity')


if __name__ == '__main__':
    main()
