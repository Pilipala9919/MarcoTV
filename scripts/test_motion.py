# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
from pathlib import Path
import json
import subprocess
import os

root = Path(__file__).resolve().parents[1]
config=root/'.tools/toolchain.json'
jdk = Path(json.loads(config.read_text())['jdk'] if config.exists() else os.environ['JAVA_HOME'])
suffix='.exe' if os.name=='nt' else ''
output = root / 'build/motion-checks'
output.mkdir(parents=True, exist_ok=True)
subprocess.run([str(jdk / ('bin/javac'+suffix)), '-encoding', 'UTF-8', '-d', str(output),
                str(root / 'app/src/main/java/com/pilipala/tvhome/MotionSpring.java'),
                str(root / 'scripts/MotionSpringChecks.java')], check=True)
result = subprocess.run([str(jdk / ('bin/java'+suffix)), '-cp', str(output), 'com.pilipala.tvhome.MotionSpringChecks'],
                        check=True, capture_output=True, text=True)
print(result.stdout)
(root / 'verification').mkdir(exist_ok=True)
(root / 'verification/motion-unit-checks.txt').write_text(result.stdout, encoding='utf-8')
