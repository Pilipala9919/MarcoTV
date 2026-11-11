# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Run pure-JVM DLNA/UPnP framing, CSRF/rebinding defense, trust policy, and XML checks."""
import subprocess
from build import ROOT, JDK, executable

folder = ROOT / 'build/cast-protocol-tests'
folder.mkdir(parents=True, exist_ok=True)
sources = [
    ROOT / 'app/src/main/java/com/pilipala/tvhome/CastProtocol.java',
    ROOT / 'scripts/CastProtocolChecks.java',
]
subprocess.run(
    [str(executable(JDK / 'bin', 'javac')), '-encoding', 'UTF-8', '-d', str(folder), *[str(s) for s in sources]],
    check=True,
)
subprocess.run(
    [str(executable(JDK / 'bin', 'java')), '-cp', str(folder), 'com.pilipala.tvhome.CastProtocolChecks'],
    check=True,
    timeout=20,
)
