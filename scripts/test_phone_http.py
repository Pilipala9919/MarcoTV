# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Exercise production HTTP parsing and bounded uploads without Android."""
from pathlib import Path
import json, os, subprocess
root=Path(__file__).resolve().parents[1]
config=root/'.tools/toolchain.json'
jdk=Path(json.loads(config.read_text())['jdk'] if config.exists() else os.environ['JAVA_HOME'])
suffix='.exe' if os.name=='nt' else ''
out=root/'build/phone-http-checks';out.mkdir(parents=True,exist_ok=True)
subprocess.run([str(jdk/('bin/javac'+suffix)),'-encoding','UTF-8','-d',str(out),str(root/'app/src/main/java/com/pilipala/tvhome/PhoneHttp.java'),str(root/'scripts/PhoneHttpChecks.java')],check=True)
subprocess.run([str(jdk/('bin/java'+suffix)),'-cp',str(out),'com.pilipala.tvhome.PhoneHttpChecks'],check=True)
