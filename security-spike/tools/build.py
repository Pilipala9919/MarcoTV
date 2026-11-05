# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Build isolated local-only probe. No launcher inputs, stable signing key or upload."""
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
CONFIG = json.loads((ROOT / '.tools/toolchain.json').read_text())
JDK, ANDROID, TOOLS = (Path(CONFIG[k]) for k in ('jdk', 'android_jar', 'build_tools'))
OUT = ROOT / 'build/security-spike'
OUT.mkdir(parents=True, exist_ok=True)
suffix = '.exe' if os.name == 'nt' else ''
env = dict(os.environ, JAVA_HOME=str(JDK), PATH=str(JDK / 'bin') + os.pathsep + os.environ.get('PATH', ''))


def run(*args):
    subprocess.run([str(a) for a in args], check=True, env=env)


def main():
    for name in ('classes', 'dex'):
        directory = OUT / name
        directory.mkdir(exist_ok=True)
        for path in directory.rglob('*'):
            if path.is_file():
                if not path.resolve().is_relative_to(OUT.resolve()):
                    raise RuntimeError('Output escapes build directory')
                path.unlink()
    run(TOOLS / ('aapt2'+suffix), 'link', '-o', OUT/'resources.apk', '--manifest',
        ROOT/'security-spike/AndroidManifest.xml', '-I', ANDROID)
    run(JDK/('bin/javac'+suffix), '--release', '8', '-encoding', 'UTF-8', '-cp', ANDROID,
        '-d', OUT/'classes', *sorted((ROOT/'security-spike/src').rglob('*.java')))
    with zipfile.ZipFile(OUT/'classes.jar', 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted((OUT/'classes').rglob('*.class')):
            archive.write(path, path.relative_to(OUT/'classes').as_posix())
    run(JDK/('bin/java'+suffix), '-cp', TOOLS/'lib/d8.jar', 'com.android.tools.r8.D8',
        '--min-api', '23', '--lib', ANDROID, '--output', OUT/'dex', OUT/'classes.jar')
    with zipfile.ZipFile(OUT/'resources.apk') as src, zipfile.ZipFile(OUT/'unsigned.apk', 'w') as dst:
        for info in src.infolist(): dst.writestr(info, src.read(info.filename))
        dst.write(OUT/'dex/classes.dex', 'classes.dex')
    run(TOOLS/('zipalign'+suffix), '-f', '4', OUT/'unsigned.apk', OUT/'aligned.apk')
    private = ROOT/'private/security-spike'; private.mkdir(parents=True, exist_ok=True)
    # Dedicated disposable debug identity only. Never use private/development.p12.
    key, password = private/'debug.p12', private/'password.txt'
    if key.exists() != password.exists(): raise RuntimeError('Incomplete debug key; refusing replacement')
    if not key.exists():
        password.write_text(secrets.token_urlsafe(32), encoding='ascii')
        env['MARCO_SPIKE_PASSWORD'] = password.read_text()
        run(JDK/('bin/keytool'+suffix), '-genkeypair', '-keystore', key, '-storetype', 'PKCS12',
            '-alias', 'probe', '-storepass:env', 'MARCO_SPIKE_PASSWORD', '-keypass:env', 'MARCO_SPIKE_PASSWORD',
            '-keyalg', 'RSA', '-keysize', '2048', '-validity', '365', '-dname', 'CN=Marco LOCAL TLS SPIKE')
    env['MARCO_SPIKE_PASSWORD'] = password.read_text()
    apk = OUT/'MarcoTlsProbe-local.apk'
    run(JDK/('bin/java'+suffix), '-jar', TOOLS/'lib/apksigner.jar', 'sign', '--ks', key,
        '--ks-key-alias', 'probe', '--ks-pass', 'env:MARCO_SPIKE_PASSWORD', '--out', apk, OUT/'aligned.apk')
    run(JDK/('bin/java'+suffix), '-jar', TOOLS/'lib/apksigner.jar', 'verify', '--verbose', apk)
    print('LOCAL ONLY', hashlib.sha256(apk.read_bytes()).hexdigest())


if __name__ == '__main__': main()
