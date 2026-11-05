# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
from pathlib import Path
import json
import os
import subprocess

root = Path(__file__).resolve().parents[2]
jdk = Path(json.loads((root/'.tools/toolchain.json').read_text())['jdk'])
out = root/'build/security-policy'; out.mkdir(parents=True, exist_ok=True)
suffix = '.exe' if os.name == 'nt' else ''
subprocess.run([str(jdk/('bin/javac'+suffix)), '--release', '8', '-encoding', 'UTF-8', '-d', str(out),
                str(root/'security-spike/src/com/pilipala/remoteprobe/SessionPolicy.java'),
                str(root/'security-spike/tests/SessionPolicyChecks.java')], check=True)
subprocess.run([str(jdk/('bin/java'+suffix)), '-cp', str(out), 'com.pilipala.remoteprobe.SessionPolicyChecks'], check=True)
