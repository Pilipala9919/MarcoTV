# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Check public licensing, local Markdown links, and package privacy boundaries."""
import hashlib,json,re,subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import unquote,urlsplit
from public_files import ROOT,source_files

def check():
    files=source_files(); errors=[]
    for p in files:
        rel=p.relative_to(ROOT).as_posix()
        if not p.exists():errors.append('Missing '+rel);continue
        if any(part in {'private','.tools','build','research','references','verification','.git'} for part in p.relative_to(ROOT).parts):
            errors.append('Local-only path in release: '+rel)
        if p.suffix in {'.p12','.jks','.keystore','.apk','.log'}:errors.append('Unexpected artifact: '+rel)
        if p.suffix in {'.java','.py','.xml','.md','.yml'}:
            text=p.read_text(encoding='utf-8')
            if p.suffix in {'.java','.py','.xml'} and 'Pilipala' not in text:errors.append('Missing attribution: '+rel)
            if p.suffix=='.md':
                for link in re.findall(r'(?<!!)\[[^\]]*\]\(([^)]+)\)',text):
                    value=urlsplit(link.strip('<>'))
                    if value.scheme or not value.path or value.path.startswith('#'):continue
                    target=(p.parent/unquote(value.path)).resolve()
                    if not target.is_relative_to(ROOT) or not target.exists():errors.append('Broken local link: '+rel+' -> '+link)
    if (ROOT/'.git').exists():
        tracked=subprocess.check_output(['git','ls-files','-z'],cwd=ROOT).decode().split('\0')
        allowed={p.relative_to(ROOT).as_posix() for p in files}
        errors.extend('Unexpected tracked file: '+p for p in tracked if p and p not in allowed)
    manifest=ET.parse(ROOT/'app/src/main/AndroidManifest.xml').getroot()
    version=manifest.get('{http://schemas.android.com/apk/res/android}versionName')
    info=(ROOT/'app/src/main/java/com/pilipala/tvhome/ProjectInfo.java').read_text(encoding='utf-8')
    if f'VERSION="{version}"' not in info:errors.append('Version mismatch')
    for source,destination in [('LICENSE','GPL-3.0-only.txt'),('NOTICE','NOTICE'),('licenses/Material-Icons-Apache-2.0.txt','Material-Icons-Apache-2.0.txt')]:
        if (ROOT/source).read_bytes()!=(ROOT/'app/src/main/assets/licenses'/destination).read_bytes():errors.append('Bundled license mismatch: '+source)
    if '本软件免费开源' not in info or '申请退款' not in info:errors.append('Missing free software notice')
    report={'version':version,'public_source_files':len(files),'passed':not errors,'errors':errors}
    (ROOT/'build').mkdir(exist_ok=True)
    (ROOT/'build/public-checks.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2))
    if errors:raise SystemExit(1)
    return files

if __name__=='__main__':check()
