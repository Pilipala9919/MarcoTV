# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Opt-in ART reducer checks. No APK install, UI input, server or permission change.

This runs the same pure-domain checks under shell app_process, NOT inside HomeView.
It is NOT evidence of authorized LAN control or visible TV focus movement.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True, type=Path)
    parser.add_argument('--serial', required=True, help='Explicit, already authorized device')
    parser.add_argument('--output', required=True, type=Path, help='Local/private JSON report')
    args = parser.parse_args()
    config = ROOT / '.tools/toolchain.json'
    if not config.exists():
        parser.error('Configure .tools/toolchain.json first, as for scripts/build.py')
    cfg = json.loads(config.read_text())
    jdk, tools, android = (Path(cfg[k]) for k in ('jdk', 'build_tools', 'android_jar'))
    java = jdk / ('bin/java.exe' if os.name == 'nt' else 'bin/java')
    subprocess.run([sys.executable, str(ROOT / 'scripts/test_marco_focus.py')], check=True)
    out = ROOT / 'build/marco-focus-art'
    out.mkdir(parents=True, exist_ok=True)
    classes = ROOT / 'build/marco-focus-checks'
    source_jar = out / 'checks-classes.jar'
    with zipfile.ZipFile(source_jar, 'w', zipfile.ZIP_DEFLATED) as archive:
        # Fixed classes from this test's compilation; no unrelated build tree inputs.
        for pattern in ('MarcoFocusController*.class', 'MarcoFocusChecks*.class'):
            for path in sorted((classes / 'com/pilipala/tvhome').glob(pattern)):
                archive.write(path, path.relative_to(classes).as_posix())
    dex_jar = out / 'checks-dex.jar'
    subprocess.run([str(java), '-cp', str(tools / 'lib/d8.jar'), 'com.android.tools.r8.D8',
                    '--min-api', '23', '--lib', str(android), '--output', str(dex_jar),
                    str(source_jar)], check=True)
    digest = hashlib.sha256(dex_jar.read_bytes()).hexdigest()
    remote = '/data/local/tmp/marco-g1-focus-' + digest[:16] + '.jar'

    def adb(*command):
        return subprocess.run([str(args.adb), '-s', args.serial, *command], check=True,
                              capture_output=True, text=True, encoding='utf-8', timeout=60).stdout

    report = {'kind': 'pure-domain-on-ART-not-UI-or-LAN',
              'started_utc': datetime.now(timezone.utc).isoformat(), 'dex_sha256': digest,
              'source_sha256': {}, 'device': {}, 'passed': False, 'cleanup_passed': False}
    for rel in ('app/src/main/java/com/pilipala/tvhome/MarcoFocusController.java',
                'scripts/MarcoFocusChecks.java'):
        report['source_sha256'][rel] = hashlib.sha256((ROOT / rel).read_bytes()).hexdigest()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    try:
        for prop in ('ro.product.model', 'ro.build.version.release', 'ro.build.version.sdk', 'ro.build.display.id'):
            report['device'][prop] = adb('shell', 'getprop', prop).strip()
        adb('push', str(dex_jar), remote)
        stdout = adb('shell', 'env', 'CLASSPATH=' + remote, 'app_process', '/system/bin',
                     'com.pilipala.tvhome.MarcoFocusChecks')
        report['stdout'] = stdout
        # Require both exit 0 (above) and the suite's terminal marker.
        report['passed'] = 'PASS MarcoFocusChecks: 18 scenarios, 2736 enumerated transitions' in stdout
        if not report['passed']:
            raise RuntimeError('ART process returned without the full test completion marker')
        print(stdout, end='')
    finally:
        try:
            adb('shell', 'rm', '-f', remote)  # Exact temporary file; never recursive.
            report['cleanup_passed'] = True
        finally:
            args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
    print('ART domain checks passed; visible focus and secure LAN control remain UNTESTED.')


if __name__ == '__main__':
    main()
