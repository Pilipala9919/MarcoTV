# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Exercise actual bundled guidance matching and one-shot return continuation."""
import subprocess
from build import ROOT, JDK, executable

folder = ROOT / 'build/home-guide-tests'
folder.mkdir(parents=True, exist_ok=True)
files = [ROOT/'app/src/main/java/com/pilipala/tvhome/HomeDeviceGuide.java',
         ROOT/'scripts/HomeGuideChecks.java']
subprocess.run([str(executable(JDK/'bin','javac')), '-encoding','UTF-8',
                '-d',str(folder),*[str(p) for p in files]],check=True)
result = subprocess.run([str(executable(JDK/'bin','java')), '-cp',str(folder),
                         'com.pilipala.tvhome.HomeGuideChecks'],check=True,capture_output=True,text=True)
(folder/'result.txt').write_text(result.stdout,encoding='utf-8')
print(result.stdout)
