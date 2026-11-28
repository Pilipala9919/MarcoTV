# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Verify mirror wallpaper catalog validation rules and 50 MB / 12K boundaries on host."""
from pathlib import Path
import json
import os
import subprocess

root = Path(__file__).resolve().parents[1]
config = root / '.tools/toolchain.json'
jdk = Path(json.loads(config.read_text())['jdk'] if config.exists() else os.environ['JAVA_HOME'])
suffix = '.exe' if os.name == 'nt' else ''
output = root / 'build/wallpaper-checks'
output.mkdir(parents=True, exist_ok=True)
subprocess.run([
    str(jdk / ('bin/javac' + suffix)), '-encoding', 'UTF-8', '-d', str(output),
    str(root / 'app/src/main/java/com/pilipala/tvhome/WallpaperCatalogPolicy.java'),
    str(root / 'app/src/main/java/com/pilipala/tvhome/LiveWallpaperPolicy.java'),
    str(root / 'scripts/WallpaperCatalogChecks.java'),
], check=True)
result = subprocess.run(
    [str(jdk / ('bin/java' + suffix)), '-cp', str(output), 'com.pilipala.tvhome.WallpaperCatalogChecks'],
    check=True, capture_output=True, text=True,
)
print(result.stdout)
(root / 'verification').mkdir(exist_ok=True)
(root / 'verification/wallpaper-catalog-checks.txt').write_text(result.stdout, encoding='utf-8')
