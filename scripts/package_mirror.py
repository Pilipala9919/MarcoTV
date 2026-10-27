# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Build a verified mirror overlay ZIP; never upload or change a channel automatically."""
import argparse
import hashlib
import json
import zipfile
from pathlib import Path
from make_update_feed import make

ROOT=Path(__file__).resolve().parents[1]

def package(stable, preview, apk_dir, output):
    entries={}
    for channel,feed in [('stable',stable),('preview',preview)]:
        source=json.loads(feed.read_text(encoding='utf-8'))
        version=source['versionName']
        # Filename must be a single safe segment before any filesystem access.
        import re
        if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[a-zA-Z0-9.]+)?',version) or len(version)>48:
            raise ValueError('Invalid version')
        name=f'MarcoTV-{version}.apk';apk=apk_dir/name
        expected=make(apk,source['url'],source['prerelease'],source.get('notes',''))
        if any(source.get(k)!=v for k,v in expected.items()):raise ValueError('Feed does not match actual APK: '+channel)
        if channel=='stable' and source['prerelease']:raise ValueError('Stable channel cannot contain prerelease')
        mirrored=dict(expected,url=f'https://marcotv.wlj9919.top/{name}')
        entries['updates/'+channel+'.json']=(json.dumps(mirrored,ensure_ascii=False,indent=2)+'\n').encode('utf-8')
        entries[name]=apk.read_bytes()
        entries[name+'.sha256']=(hashlib.sha256(entries[name]).hexdigest()+'  '+name+'\n').encode('ascii')
    for name in ['LICENSE','NOTICE']:
        entries[name]=(ROOT/name).read_bytes()
    entries['MIRROR-DEPLOY.md']=(ROOT/'docs/镜像更新部署.md').read_bytes()
    output.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as archive:
        for name,data in entries.items():archive.writestr(name,data)
    with zipfile.ZipFile(output) as archive:
        if archive.testzip():raise ValueError('ZIP CRC failed')
    print(f'Created {output.name}: {len(entries)} files. Upload APKs first, then channel JSON; deployment not performed.')

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--stable-feed',type=Path,default=ROOT/'docs/updates/stable.json')
    p.add_argument('--preview-feed',type=Path,default=ROOT/'docs/updates/preview.json')
    p.add_argument('--apk-dir',type=Path,default=ROOT/'dist')
    p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();package(a.stable_feed,a.preview_feed,a.apk_dir,a.output)
