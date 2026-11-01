# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Check the built restricted APK, unchanged sources and edition-specific update policy."""
import subprocess, hashlib, json
from pathlib import Path
from build import ROOT, TOOLS, JDK, VERSION, executable
from make_update_feed import make

apk=ROOT/'dist'/f'MarcoTV-{VERSION}-Restricted.apk'
if not apk.exists():raise RuntimeError('Run python scripts/build.py --restricted first')
def output(*args):return subprocess.check_output([str(x) for x in args],cwd=ROOT).decode('utf8',errors='replace')
xml=output(executable(TOOLS,'aapt2'),'dump','xmltree',apk,'--file','AndroidManifest.xml')
assert 'android.intent.category.HOME' not in xml, 'HOME declaration survived packaging'
assert 'HomeActivity' not in xml, 'Removed alias survived packaging'
for value in ['android.intent.category.LEANBACK_LAUNCHER','android.intent.category.LAUNCHER','com.pilipala.tvhome.EDITION','restricted','HomeKeyService','BootReceiver']:
    assert value in xml, value
original=(ROOT/'app/src/main/AndroidManifest.xml').read_text(encoding='utf8')
assert 'android.intent.category.HOME' in original, 'Standard manifest was modified'
assert 'RESTRICTED = false' in (ROOT/'app/src/main/java/com/pilipala/tvhome/BuildEdition.java').read_text(encoding='utf8')
edition=ROOT/'build/restricted/generated/BuildEdition.java'
assert 'RESTRICTED = true' in edition.read_text(encoding='utf8')
folder=ROOT/'build/restricted-policy-tests';folder.mkdir(exist_ok=True)
subprocess.run([str(executable(JDK/'bin','javac')),'-encoding','UTF-8','-d',str(folder),str(edition),str(ROOT/'app/src/main/java/com/pilipala/tvhome/UpdatePolicy.java'),str(ROOT/'scripts/UpdatePolicyChecks.java')],check=True)
checks=output(executable(JDK/'bin','java'),'-cp',folder,'com.pilipala.tvhome.UpdatePolicyChecks')
assert 'OK 12 restricted update policy checks' in checks,checks
print(checks)
restricted_cert=output(executable(JDK/'bin','java'),'-jar',TOOLS/'lib/apksigner.jar','verify','--print-certs',apk)
standard=ROOT/'dist'/f'MarcoTV-{VERSION}.apk'
standard_cert=output(executable(JDK/'bin','java'),'-jar',TOOLS/'lib/apksigner.jar','verify','--print-certs',standard)
def cert(text):return next(x for x in text.splitlines() if x.startswith('Signer #1 certificate SHA-256 digest:'))
assert cert(restricted_cert)==cert(standard_cert),'Signing identities differ'
normal_xml=output(executable(TOOLS,'aapt2'),'dump','xmltree',standard,'--file','AndroidManifest.xml')
assert 'android.intent.category.HOME' in normal_xml,'Standard APK lost its HOME declaration'
for directory,expected in [('build/restricted/classes','true'),('build/classes','false')]:
    constants=output(executable(JDK/'bin','javap'),'-constants','-classpath',ROOT/directory,'com.pilipala.tvhome.BuildEdition')
    assert 'RESTRICTED = '+expected in constants,'Wrong compiled edition switch'
feed=make(apk,f'https://marcotv.wlj9919.top/MarcoTV-{VERSION}-Restricted.apk',False,'test only',True)
assert feed['edition']=='restricted'
for file,url,restricted in [(standard,f'https://marcotv.wlj9919.top/MarcoTV-{VERSION}-Restricted.apk',True),(apk,f'https://marcotv.wlj9919.top/MarcoTV-{VERSION}.apk',False)]:
    try:make(file,url,False,'test',restricted)
    except ValueError:pass
    else:raise AssertionError('Feed generator allowed wrong APK edition')
report={'test':'restricted package offline validation','passed':True,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'version':VERSION,'has_home_declaration':False,'same_signer_as_standard':True,'restricted_policy_checks':12,'device_installation':'not tested','home_and_boot':'not tested','public_release':False}
(ROOT/'build/restricted/validation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8')
print('PASS compiled manifest, retained app/service entries, same official signing identity and update isolation')
