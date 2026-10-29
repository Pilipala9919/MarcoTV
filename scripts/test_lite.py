# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Check Lite layout boundaries and APK isolation without a device."""
import json,subprocess,zipfile,xml.etree.ElementTree as ET
from build import ROOT,JDK,env,executable,run
f=ROOT/'build/lite-local-tests';f.mkdir(parents=True,exist_ok=True)
run(executable(JDK/'bin','javac'),'-encoding','UTF-8','-d',f,ROOT/'lite/src/main/java/com/pilipala/tvhome/LiteSettings.java',ROOT/'lite/tests/LiteSettingsChecks.java')
out=subprocess.check_output([str(executable(JDK/'bin','java')),'-cp',str(f),'com.pilipala.tvhome.LiteSettingsChecks'],env=env).decode();assert 'OK 8 Lite settings checks' in out;print(out)
m=ET.parse(ROOT/'lite/src/main/AndroidManifest.xml').getroot();a='{http://schemas.android.com/apk/res/android}'
checks={
 'separate_package':m.get('package')=='com.pilipala.tvhome.lite',
 'api14_manifest':m.find('uses-sdk').get(a+'minSdkVersion')=='14',
 'no_privileged_or_network_permissions':not m.findall('uses-permission'),
 'no_background_services_or_receivers':not m.findall('.//service') and not m.findall('.//receiver'),
}
version=m.get(a+'versionName')
with zipfile.ZipFile(ROOT/f'dist/MarcoTV-Lite-{version}.apk') as z:
 dex=z.read('classes.dex');checks['old_dalvik_dex_035']=dex.startswith(b'dex\n035\x00')
 checks['no_full_edition_system_classes']=all(x not in dex for x in [b'Choreographer',b'MediaSessionManager',b'NotificationListenerService',b'TaskBridgeServer',b'NetworkCapabilities'])
 checks['font_licenses_bundled']=len([n for n in z.namelist() if n.startswith('assets/licenses/') and n.endswith('-OFL.txt')])==4
 checks['four_static_fonts_bundled']=len([n for n in z.namelist() if n.startswith('assets/fonts/') and n.endswith('.ttf')])==4
 checks['apk_crc']=z.testzip() is None
 checks['no_native_arch_dependency']=not any(n.startswith('lib/') for n in z.namelist())
 checks['lite_wallpapers_only']=sorted(n for n in z.namelist() if n.startswith('assets/wallpapers/'))==['assets/wallpapers/aurora.jpg','assets/wallpapers/coast.jpg']
 checks['compressed_wallpapers_budget']=sum(i.file_size for i in z.infolist() if i.filename.startswith('assets/wallpapers/'))<300000
for name,ok in checks.items():print(('PASS ' if ok else 'FAIL ')+name)
assert all(checks.values());(f/'result.json').write_text(json.dumps({'layout_checks':8,'apk_checks':checks},indent=2))
print('OK 20 Lite local checks')
