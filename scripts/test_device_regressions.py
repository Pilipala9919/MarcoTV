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
import shlex
import time
import zipfile
from pathlib import Path
from build import ROOT, BUILD, SOURCE, JDK, ANDROID, TOOLS, env, executable, run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--compile-only', action='store_true', help='Build the test APK without connecting to or changing a device')
    modes=parser.add_mutually_exclusive_group()
    modes.add_argument('--bridge-only', action='store_true', help='Read-only task bridge checks; does not open TV UI')
    modes.add_argument('--task-actions', action='store_true', help='Create, switch and remove two temporary fixture tasks; never remove user tasks')
    modes.add_argument('--phone-only', action='store_true', help='Test local pairing and preview rollback; restore preferences')
    modes.add_argument('--appearance-only', action='store_true', help='Exercise entry, return and material rendering; restore preferences')
    modes.add_argument('--updates-only', action='store_true', help='Validate a newer same-signed APK without installing it')
    parser.add_argument('--candidate', help='Device-readable path to a newer same-signed Marco TV APK, required for --updates-only')
    parser.add_argument('--wrong-signer', help='Device-readable path to the same candidate signed with a different test key; never installed')
    args = parser.parse_args()
    if args.updates_only and (not args.candidate or not args.wrong_signer):parser.error('--candidate and --wrong-signer are required for --updates-only')
    folder = BUILD / 'device-regression'
    for name in ['', 'classes', 'dex']:
        (folder / name).mkdir(parents=True, exist_ok=True)
    manifest = folder / 'AndroidManifest.xml'
    manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.pilipala.tvhome.regression">
      <uses-sdk android:minSdkVersion="23" android:targetSdkVersion="28" />
      <application android:label="Marco TV regression checks">
        <activity android:name="com.pilipala.tvhome.DeviceRegressionChecks$FixtureActivity" android:exported="true" />
        <activity-alias android:name=".FixtureAlias" android:targetActivity="com.pilipala.tvhome.DeviceRegressionChecks$FixtureActivity" android:exported="true">
          <intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LEANBACK_LAUNCHER"/></intent-filter>
        </activity-alias>
        <activity-alias android:name=".PrivateFixture" android:targetActivity="com.pilipala.tvhome.DeviceRegressionChecks$FixtureActivity" android:exported="false" />
        <activity-alias android:name=".ProtectedFixture" android:targetActivity="com.pilipala.tvhome.DeviceRegressionChecks$FixtureActivity" android:exported="true" android:permission="android.permission.INSTALL_PACKAGES" />
        <activity-alias android:name=".DisabledFixture" android:targetActivity="com.pilipala.tvhome.DeviceRegressionChecks$FixtureActivity" android:exported="true" android:enabled="false" />
      </application>
      <instrumentation android:name="com.pilipala.tvhome.DeviceRegressionChecks" android:targetPackage="com.pilipala.tvhome" />
    </manifest>''', encoding='utf-8')
    run(executable(TOOLS, 'aapt2'), 'link', '-o', folder / 'resources.apk', '--manifest', manifest, '-I', ANDROID)
    run(executable(JDK / 'bin', 'javac'), '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:-options',
        '-classpath', str(ANDROID) + os.pathsep + str(BUILD / 'classes.jar'), '-d', folder / 'classes',
        ROOT / 'scripts/DeviceRegressionChecks.java', ROOT / 'scripts/FeatureRegressionChecks.java', ROOT / 'scripts/UpdateDeviceChecks.java', ROOT / 'scripts/AppearanceDeviceChecks.java', ROOT / 'scripts/PhoneDeviceChecks.java')
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
    if args.compile_only:
        print('Test APK compiled; no device checks executed.')
        return
    installed = False
    accessibility_before=adb('shell','settings','get','secure','enabled_accessibility_services').strip()
    try:
        if not args.bridge_only and 'mWakefulness=Awake' not in adb('shell','dumpsys','power'):
            raise RuntimeError('TV is not awake. UI tests were not run; no wake command is sent automatically.')
        if 'Success' not in adb('install', '-r', folder / 'checks.apk'): raise RuntimeError('Test APK install failed')
        installed = True
        output = adb('shell', 'am', 'instrument', '-w', '-e', 'phone_only', str(args.phone_only).lower(), '-e', 'appearance_only', str(args.appearance_only).lower(), '-e', 'wrong_signer', args.wrong_signer or 'unused', '-e', 'updates_only', str(args.updates_only).lower(), '-e', 'candidate', args.candidate or 'unused', '-e', 'bridge_only', str(args.bridge_only).lower(), '-e', 'task_actions', str(args.task_actions).lower(),
                     'com.pilipala.tvhome.regression/com.pilipala.tvhome.DeviceRegressionChecks')
        print(output)
        (folder / ('phone-result.txt' if args.phone_only else 'appearance-result.txt' if args.appearance_only else 'update-result.txt' if args.updates_only else 'task-result.txt' if args.task_actions else 'bridge-result.txt' if args.bridge_only else 'result.txt')).write_text(output, encoding='utf-8')
        expected='OK 20 phone checks' if args.phone_only else 'OK 36 appearance checks' if args.appearance_only else 'OK 17 update device checks' if args.updates_only else 'OK 13 task action checks' if args.task_actions else 'OK 4 bridge checks' if args.bridge_only else 'OK 85 device regression checks'
        if expected not in output or 'FAIL' in output:
            raise RuntimeError('Device regression failed; see local result.txt')
    finally:
        if installed:
            adb('uninstall', 'com.pilipala.tvhome.regression')
            # Some Android 9 OEM builds leave a dead accessibility binding after
            # instrumentation restarts the target. Rebind only our previously
            # enabled service, preserving every other service and any user edit.
            current=adb('shell','settings','get','secure','enabled_accessibility_services').strip()
            own={'com.pilipala.tvhome/.HomeKeyService','com.pilipala.tvhome/com.pilipala.tvhome.HomeKeyService'}
            if current==accessibility_before and any(item in own for item in current.split(':')):
                bindings=adb('shell','dumpsys','activity','services','com.pilipala.tvhome.HomeKeyService')
                if 'DEAD com.pilipala.tvhome/' in bindings:
                    remaining=':'.join(item for item in current.split(':') if item not in own)
                    try:
                        adb('shell','settings','put','secure','enabled_accessibility_services',shlex.quote(remaining));time.sleep(1)
                    finally:
                        adb('shell','settings','put','secure','enabled_accessibility_services',shlex.quote(current))
                    time.sleep(2)
            if not args.bridge_only:adb('shell', 'am', 'start', '-n', 'com.pilipala.tvhome/.MainActivity')


if __name__ == '__main__':
    main()
