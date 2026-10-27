# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Run upgrade/order migration cases against production Java classes; no device required."""
from pathlib import Path
import json
import os
import subprocess

root=Path(__file__).resolve().parents[1]
config=root/'.tools/toolchain.json'
jdk=Path(json.loads(config.read_text())['jdk'] if config.exists() else os.environ['JAVA_HOME'])
suffix='.exe' if os.name=='nt' else ''
output=root/'build/settings-checks'
output.mkdir(parents=True,exist_ok=True)
source=root/'app/src/main/java/com/pilipala/tvhome'
subprocess.run([str(jdk/('bin/javac'+suffix)),'-encoding','UTF-8','-d',str(output),
                str(source/'SettingsCatalog.java'),str(source/'MenuOrder.java'),str(root/'scripts/SettingsMigrationChecks.java')],check=True)
result=subprocess.run([str(jdk/('bin/java'+suffix)),'-cp',str(output),'com.pilipala.tvhome.SettingsMigrationChecks'],check=True,capture_output=True,text=True)
print(result.stdout)
(root/'verification').mkdir(exist_ok=True)
(root/'verification/settings-unit-checks.txt').write_text(result.stdout,encoding='utf-8')
