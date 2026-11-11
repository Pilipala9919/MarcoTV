# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Verify TaskBridgeServer cross-Android Binder signatures (API 23-34+) and task slice unwrapping."""
import hashlib
import os
import subprocess
import urllib.request
from build import ANDROID, JDK, ROOT, executable

folder = ROOT / 'build/task-bridge-tests'
folder.mkdir(parents=True, exist_ok=True)
jar = ROOT / '.tools/test-libs/json-20240303.jar'
jar.parent.mkdir(parents=True, exist_ok=True)
if not jar.exists():
    jar.write_bytes(
        urllib.request.urlopen(
            'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar', timeout=30
        ).read()
    )
assert hashlib.sha256(jar.read_bytes()).hexdigest() == '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'

classpath = os.pathsep.join([str(ANDROID), str(jar)])
sources = [
    ROOT / 'app/src/main/java/com/pilipala/tvhome/BuildEdition.java',
    ROOT / 'app/src/main/java/com/pilipala/tvhome/ProjectInfo.java',
    ROOT / 'app/src/main/java/com/pilipala/tvhome/TaskBridgeServer.java',
    ROOT / 'scripts/TaskBridgeCompatChecks.java',
]
subprocess.run(
    [str(executable(JDK / 'bin', 'javac')), '-encoding', 'UTF-8', '-classpath', classpath, '-d', str(folder), *[str(s) for s in sources]],
    check=True,
)
subprocess.run(
    [str(executable(JDK / 'bin', 'java')), '-cp', str(folder) + os.pathsep + classpath, 'com.pilipala.tvhome.TaskBridgeCompatChecks'],
    check=True,
    timeout=20,
)
