# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Run actual platform-free foundation classes; no TV, network, or settings access."""
import os, subprocess
from build import ROOT, JDK, executable
from build_lite import SDK14

folder = ROOT / 'build/ambient-weather-checks'
folder.mkdir(parents=True, exist_ok=True)
sources = [ROOT / 'app/src/main/java/com/pilipala/tvhome' / name for name in
           ['BackgroundPlaybackSession.java', 'WeatherRefreshPolicy.java', 'DesktopContrast.java', 'WeatherSnapshot.java']]
cp = os.pathsep.join([str(folder), str(SDK14)])
subprocess.run([str(executable(JDK / 'bin', 'javac')), '-encoding', 'UTF-8',
                '-source', '8', '-target', '8', '-Xlint:-options', '-classpath', str(SDK14), '-d', str(folder),
                *map(str, sources), str(ROOT / 'scripts/AmbientWeatherChecks.java')], check=True)
result = subprocess.run([str(executable(JDK / 'bin', 'java')), '-cp', cp,
                         'com.pilipala.tvhome.AmbientWeatherChecks'],
                        capture_output=True, text=True, check=True, timeout=30)
(folder / 'result.txt').write_text(result.stdout, encoding='utf-8')
print(result.stdout)
