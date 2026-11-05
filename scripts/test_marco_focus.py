# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Compile the production focus reducer and run behavioral checks on a host JVM."""
from pathlib import Path
import json
import os
import subprocess

ROOT = Path(__file__).resolve().parents[1]
config = ROOT / '.tools/toolchain.json'
jdk = Path(json.loads(config.read_text())['jdk'] if config.exists() else os.environ['JAVA_HOME'])
suffix = '.exe' if os.name == 'nt' else ''
out = ROOT / 'build/marco-focus-checks'
out.mkdir(parents=True, exist_ok=True)
# Remove only this suite's generated classes so renamed helpers cannot enter a dex.
for pattern in ('MarcoFocusController*.class', 'MarcoFocusChecks*.class'):
    for path in (out / 'com/pilipala/tvhome').glob(pattern):
        if not path.resolve().is_relative_to(out.resolve()):
            raise RuntimeError('Generated class escapes test output directory')
        path.unlink()
subprocess.run([
    str(jdk / ('bin/javac' + suffix)), '-encoding', 'UTF-8', '--release', '8',
    '-d', str(out),
    str(ROOT / 'app/src/main/java/com/pilipala/tvhome/MarcoFocusController.java'),
    str(ROOT / 'scripts/MarcoFocusChecks.java'),
], check=True)
subprocess.run([
    str(jdk / ('bin/java' + suffix)), '-cp', str(out),
    'com.pilipala.tvhome.MarcoFocusChecks',
], check=True)
