# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Generate update metadata from an actual signed APK. Publish only after its release asset is available."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path
from build import OFFICIAL_SIGNER_SHA256, TOOLS, executable, signer_sha256

def make(apk, url, prerelease, notes, restricted=False, require_official_signer=False):
    cert=signer_sha256(apk)
    if require_official_signer and cert!=OFFICIAL_SIGNER_SHA256:
        raise ValueError(f'APK signer {cert} does not match official release signer {OFFICIAL_SIGNER_SHA256}')
    output=subprocess.check_output([str(executable(TOOLS,'aapt2')),'dump','badging',str(apk)],text=True,encoding='utf-8')
    package=re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",output)
    sdk=re.search(r"(?:minSdkVersion|sdkVersion):'(\d+)'",output)
    if not package or not sdk or package[1]!='com.pilipala.tvhome':raise ValueError('Unexpected APK identity')
    version=package[3]
    filename=f'MarcoTV-{version}{"-Restricted" if restricted else ""}.apk'
    expected=f'https://github.com/Pilipala9919/MarcoTV/releases/download/v{version}/{filename}'
    mirror=f'https://marcotv.wlj9919.top/{filename}'
    if url not in (expected,mirror):raise ValueError('URL must match the official or mirror versioned release filename')
    if ('-' in version)!=prerelease:raise ValueError('Prerelease flag must agree with version name')
    if len(notes)>8000:raise ValueError('Notes too long')
    xml=subprocess.check_output([str(executable(TOOLS,'aapt2')),'dump','xmltree',str(apk),'--file','AndroidManifest.xml'],text=True,encoding='utf-8')
    marked='com.pilipala.tvhome.EDITION' in xml and '"restricted"' in xml
    if marked!=restricted:raise ValueError('APK edition differs from requested feed')
    if restricted and 'android.intent.category.HOME' in xml:raise ValueError('Restricted APK must not contain HOME')
    return {'schema':1,'edition':'restricted' if restricted else 'standard','versionCode':int(package[2]),'versionName':version,'minSdk':int(sdk[1]),
            'size':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
            'url':url,'prerelease':prerelease,'notes':notes}

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk',type=Path,required=True);parser.add_argument('--url',required=True)
    parser.add_argument('--prerelease',action='store_true');parser.add_argument('--notes',type=Path,required=True)
    parser.add_argument('--restricted',action='store_true',help='Require restricted APK identity and restricted download filename')
    parser.add_argument('--allow-dev-signer',action='store_true',help='Allow non-official signer certificate for local testing only')
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();result=make(args.apk,args.url,args.prerelease,args.notes.read_text(encoding='utf-8'),args.restricted,not args.allow_dev_signer)
    if args.restricted and args.output.name not in ('restricted-stable.json','restricted-preview.json'):
        parser.error('Restricted feeds must use restricted-stable.json or restricted-preview.json')
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f'Created {args.output.name}: {result["versionName"]}, code {result["versionCode"]}; release availability still requires verification.')
