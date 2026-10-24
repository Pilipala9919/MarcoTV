# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Fetch a portable, checksum-verified Java/Android toolchain into this project."""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'
PACKAGES = [
    ('jdk', 'https://cdn.azul.com/zulu/bin/zulu17.68.203-ca-jdk17.0.20.1-win_x64.zip', 'sha256',
     '552a335d5342c800fd03b45a23bb1136f5aa5562193542ece506d8c44b0c60d9'),
    ('platform', 'https://dl.google.com/android/repository/platform-28_r06.zip', 'sha1',
     '9a4e52b1d55bd2e24216b150aafae2503d3efba6'),
    ('build-tools', 'https://dl.google.com/android/repository/build-tools_r35_windows.zip', 'sha1',
     'af059bb67cf7786f45ee0db85e2d24985df1b4b6'),
]


def install(package):
    name, url, algorithm, expected = package
    target = TOOLS / name
    marker = target / '.verified'
    if marker.exists() and marker.read_text() == expected:
        print(f'{name}: ready', flush=True)
        return
    archive = TOOLS / f'{name}.zip'
    if not archive.exists() or hashlib.new(algorithm, archive.read_bytes()).hexdigest() != expected:
        print(f'{name}: downloading official package', flush=True)
        request = urllib.request.Request(url, headers={'User-Agent': 'TVLauncher-Build/0.1'})
        with urllib.request.urlopen(request, timeout=90) as response, archive.open('wb') as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
    digest = hashlib.new(algorithm, archive.read_bytes()).hexdigest()
    if digest != expected:
        raise RuntimeError(f'{name}: checksum mismatch; refusing to extract')
    target.mkdir(exist_ok=True)
    with zipfile.ZipFile(archive) as z:
        for item in z.infolist():
            resolved = (target / item.filename).resolve()
            if not resolved.is_relative_to(target.resolve()):
                raise RuntimeError('Unsafe archive path')
        z.extractall(target)
    marker.write_text(expected)
    print(f'{name}: verified and extracted', flush=True)


if __name__ == '__main__':
    TOOLS.mkdir(exist_ok=True)
    with ThreadPoolExecutor(max_workers=3) as pool:
        list(pool.map(install, PACKAGES))
    configuration = {
        'jdk': str(next((TOOLS / 'jdk').glob('*/bin/javac.exe')).parents[1]),
        'android_jar': str(next((TOOLS / 'platform').rglob('android.jar'))),
        'build_tools': str(next((TOOLS / 'build-tools').rglob('aapt2.exe')).parent),
    }
    (TOOLS / 'toolchain.json').write_text(json.dumps(configuration, indent=2))
    print('Portable build tools ready.', flush=True)
