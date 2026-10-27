# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Generate update metadata from an actual signed APK. Publish only after its release asset is available."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path
from build import TOOLS, executable

def make(apk, url, prerelease, notes):
    output=subprocess.check_output([str(executable(TOOLS,'aapt2')),'dump','badging',str(apk)],text=True,encoding='utf-8')
    package=re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",output)
    sdk=re.search(r"(?:minSdkVersion|sdkVersion):'(\d+)'",output)
    if not package or not sdk or package[1]!='com.pilipala.tvhome':raise ValueError('Unexpected APK identity')
    version=package[3]
    expected=f'https://github.com/Pilipala9919/MarcoTV/releases/download/v{version}/MarcoTV-{version}.apk'
    if url!=expected:raise ValueError('URL must match the official versioned release filename')
    if ('-' in version)!=prerelease:raise ValueError('Prerelease flag must agree with version name')
    if len(notes)>8000:raise ValueError('Notes too long')
    return {'schema':1,'versionCode':int(package[2]),'versionName':version,'minSdk':int(sdk[1]),
            'size':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
            'url':url,'prerelease':prerelease,'notes':notes}

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk',type=Path,required=True);parser.add_argument('--url',required=True)
    parser.add_argument('--prerelease',action='store_true');parser.add_argument('--notes',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();result=make(args.apk,args.url,args.prerelease,args.notes.read_text(encoding='utf-8'))
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f'Created {args.output.name}: {result["versionName"]}, code {result["versionCode"]}; release availability still requires verification.')
