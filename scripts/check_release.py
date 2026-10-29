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
    allowed={p.resolve() for p in files}
    for p in files:
        rel=p.relative_to(ROOT).as_posix()
        if not p.exists():errors.append('Missing '+rel);continue
        if any(part in {'private','.tools','build','research','references','verification','.git'} for part in p.relative_to(ROOT).parts):
            errors.append('Local-only path in release: '+rel)
        if p.suffix in {'.p12','.jks','.keystore','.apk','.log'}:errors.append('Unexpected artifact: '+rel)
        if p.suffix in {'.java','.py','.xml','.md','.yml','.json'}:
            text=p.read_text(encoding='utf-8')
            if p.suffix in {'.java','.py','.xml','.yml'}:
                if re.search(r'(?<![A-Za-z])[A-Za-z]:[\\\\/]',text):errors.append('Machine-specific absolute path: '+rel)
                if re.search(r'(?<![\d.])(?:192\.168\.|10\.\d+\.|172\.(?:1[6-9]|2\d|3[01])\.)\d+\.\d+(?![\d.])',text):errors.append('Private network address in source: '+rel)
            if p.suffix in {'.java','.py','.xml'}:
                vendored=rel.startswith('app/src/main/java/io/nayuki/qrcodegen/')
                if vendored:
                    if 'Copyright' not in text or 'Project Nayuki' not in text or 'Permission is hereby granted' not in text:errors.append('Missing upstream license: '+rel)
                elif 'Pilipala' not in text:errors.append('Missing attribution: '+rel)
            if p.suffix=='.md':
                for link in re.findall(r'(?<!!)\[[^\]]*\]\(([^)]+)\)',text):
                    value=urlsplit(link.strip('<>'))
                    if value.scheme or not value.path or value.path.startswith('#'):continue
                    target=(p.parent/unquote(value.path)).resolve()
                    if target not in allowed:errors.append('Link outside public package: '+rel+' -> '+link)
    if (ROOT/'.git').exists():
        tracked=subprocess.check_output(['git','ls-files','-z'],cwd=ROOT).decode().split('\0')
        allowed={p.relative_to(ROOT).as_posix() for p in files}
        errors.extend('Unexpected tracked file: '+p for p in tracked if p and p not in allowed)
    manifest=ET.parse(ROOT/'app/src/main/AndroidManifest.xml').getroot()
    version=manifest.get('{http://schemas.android.com/apk/res/android}versionName')
    info=(ROOT/'app/src/main/java/com/pilipala/tvhome/ProjectInfo.java').read_text(encoding='utf-8')
    if f'VERSION="{version}"' not in info:errors.append('Version mismatch')
    for name in ['README.md','docs/安装说明.md','docs/使用说明.md','docs/常见问题.md','docs/构建说明.md','docs/隐私说明.md','docs/各品牌电视设置入口.md']:
        if version not in '\n'.join((ROOT/name).read_text(encoding='utf-8').splitlines()[2:10]):errors.append('Document version mismatch: '+name)
    validation=json.loads((ROOT/'docs/release-validation.json').read_text(encoding='utf-8'))
    if validation.get('version')!=version:errors.append('Validation summary version mismatch')
    for source,destination in [('licenses/QR-Code-generator-MIT.txt','QR-Code-generator-MIT.txt'),('LICENSE','GPL-3.0-only.txt'),('NOTICE','NOTICE'),('licenses/Material-Icons-Apache-2.0.txt','Material-Icons-Apache-2.0.txt')]:
        if (ROOT/source).read_bytes()!=(ROOT/'app/src/main/assets/licenses'/destination).read_bytes():errors.append('Bundled license mismatch: '+source)
    for license in (ROOT/'licenses').glob('*-OFL.txt'):
        if license.read_bytes()!=(ROOT/'app/src/main/assets/licenses'/license.name).read_bytes():errors.append('Bundled font license mismatch: '+license.name)
    fonts=json.loads((ROOT/'app/src/main/assets/fonts/sources.json').read_text(encoding='utf8'))
    for font in fonts:
        if hashlib.sha256((ROOT/'app/src/main/assets/fonts'/font['file']).read_bytes()).hexdigest()!=font['sha256']:errors.append('Clock font hash mismatch: '+font['file'])
    if '本软件免费开源' not in info or '申请退款' not in info:errors.append('Missing free software notice')
    report={'version':version,'public_source_files':len(files),'passed':not errors,'errors':errors}
    (ROOT/'build').mkdir(exist_ok=True)
    (ROOT/'build/public-checks.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2))
    if errors:raise SystemExit(1)
    return files

if __name__=='__main__':check()
