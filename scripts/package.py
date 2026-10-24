# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Package Marco TV's public source, docs and current APK, without local data."""
from pathlib import Path
import hashlib,json,zipfile
import xml.etree.ElementTree as ET
from check_release import check
from public_files import ROOT

def package():
    files=check()
    version=ET.parse(ROOT/'app/src/main/AndroidManifest.xml').getroot().get('{http://schemas.android.com/apk/res/android}versionName')
    apk=ROOT/f'dist/MarcoTV-{version}.apk'; checksum=apk.with_suffix('.apk.sha256')
    digest=hashlib.sha256(apk.read_bytes()).hexdigest()
    if checksum.read_text().split()[0]!=digest:raise RuntimeError('APK checksum mismatch')
    files.extend([apk,checksum]); output=ROOT/f'dist/MarcoTV-{version}-source-and-apk.zip'
    manifest={p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
    with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED,compresslevel=8) as archive:
        for p in files:archive.write(p,'MarcoTV/'+p.relative_to(ROOT).as_posix())
        archive.writestr('MarcoTV/checksums.json',json.dumps(manifest,ensure_ascii=False,indent=2))
    with zipfile.ZipFile(output) as archive:
        if archive.testzip() is not None:raise RuntimeError('ZIP CRC failed')
    print(f'{output.name}: {output.stat().st_size} bytes, {len(files)} files; CRC verified')

if __name__=='__main__':package()
