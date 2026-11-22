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

# Exercise start_task_bridge.py host probes against Android 6.0 (API 23), 7-8, and 9-15+ outputs.
import start_task_bridge as bridge

ps_api23 = (
    'USER      PID   PPID  VSIZE  RSS   WCHAN              PC  NAME\n'
    'root      1     0     1420   804   SyS_epoll_ 00000000 S /init\n'
    'shell     17574 1     1523480 48204 SyS_epoll_ 00000000 S marcotv-task-bridge\n'
    'shell     17800 17799 5600   1200  00000000 00000000 R ps\n'
)
ps_api30 = (
    'USER           PID  PPID     VSZ    RSS WCHAN            ADDR S NAME\n'
    'root             1     0   12345   2345 SyS_epoll_wait      0 S init\n'
    'shell        18887     1 1345678  45678 0                   0 S marcotv-task-bridge\n'
)
ps_busybox = (
    '  PID USER       VSZ STAT COMMAND\n'
    '    1 root      1420 S    /init\n'
    ' 4321 shell    48204 S    marcotv-task-br\n'
)
dumpsys_api23 = (
    'Packages:\n'
    '  Package [com.pilipala.tvhome] (3a4b5c6):\n'
    '    userId=10059\n'
    '    pkg=Package{1234 com.pilipala.tvhome}\n'
    '    codePath=/data/app/com.pilipala.tvhome-1\n'
    '    User 0:  installed=true hidden=false stopped=false notLaunched=false enabled=0\n'
)
dumpsys_multiuser = (
    'Packages:\n'
    '  Package [com.pilipala.tvhome] (9f8e7d6):\n'
    '    userId=10059\n'
    '    User 0: ceDataInode=123 installed=true\n'
    '    User 10: ceDataInode=456 installed=true\n'
    '    User 11: ceDataInode=789 installed=false\n'
)

assert not bridge.has_ps_header("bad pid '-A'")
assert bridge.has_ps_header(ps_api23)
assert bridge.has_ps_header(ps_api30)
assert bridge.has_ps_header(ps_busybox)
assert bridge.parse_bridge_pids(ps_api23) == ['17574']
assert bridge.parse_bridge_pids(ps_api30) == ['18887']
assert bridge.parse_bridge_pids(ps_busybox) == ['4321']

# Simulate Android 6.0.1 (MiTV4): ps -A fails, am get-current-user unsupported, pm -U unsupported, pm path --user 0 empty.
calls = []
killed = []

def fake_api23_try(*cmd):
    calls.append(cmd)
    if cmd == ('ps', '-A'):
        return "bad pid '-A'"
    if cmd == ('ps',):
        return ps_api23
    if cmd == ('cat', '/proc/17574/cmdline'):
        return 'marcotv-task-bridge\x00'
    if cmd == ('am', 'get-current-user'):
        return "Error: unknown command 'get-current-user'"
    if cmd == ('pm', 'list', 'users'):
        return 'Users:\n\tUserInfo{0:Owner:13} running\n'
    if cmd == ('pm', 'list', 'packages', '-U', '--user', '0', 'com.pilipala.tvhome'):
        return 'Error: Unknown option: -U'
    if cmd == ('dumpsys', 'package', 'com.pilipala.tvhome'):
        return dumpsys_api23
    if cmd == ('pm', 'path', '--user', '0', 'com.pilipala.tvhome'):
        return ''
    if cmd == ('pm', 'path', 'com.pilipala.tvhome'):
        return 'package:/data/app/com.pilipala.tvhome-1/base.apk'
    raise AssertionError(f'Unexpected command: {cmd}')

def fake_api23_checked(*cmd):
    if cmd[0] == 'kill':
        killed.append(cmd[1])
        return ''
    raise AssertionError(f'Unexpected checked command: {cmd}')

assert bridge.stop_existing_bridges(fake_api23_try, fake_api23_checked) is True
assert killed == ['17574']
assert all(c[0] != 'pidof' for c in calls), 'Must not invoke broken pidof on Android 6'
user = bridge.resolve_current_user(fake_api23_try)
assert user == '0'
assert bridge.resolve_package_uid(fake_api23_try, user) == '10059'
assert bridge.resolve_apk_path(fake_api23_try, user) == '/data/app/com.pilipala.tvhome-1/base.apk'

# Multi-user dumpsys fallback (Android 7-8) and uninstalled-user rejection.
assert bridge.parse_uid_from_dumpsys(dumpsys_multiuser, '0') == '10059'
assert bridge.parse_uid_from_dumpsys(dumpsys_multiuser, '10') == '1010059'
assert bridge.parse_uid_from_dumpsys(dumpsys_multiuser, '11') is None
assert bridge.parse_uid_from_dumpsys('Unable to find package', '0') is None

# Modern Android 9-15+ direct path without fallbacks.
assert bridge.parse_uid_from_packages('package:com.pilipala.tvhome uid:1010059\n') == '1010059'
assert bridge.valid_apk_path('/data/app/~~aBcD12+xYz==/com.pilipala.tvhome-9876AbCd==/base.apk')
assert bridge.valid_apk_path('/mnt/expand/01234567-89ab-cdef/app/com.pilipala.tvhome-1/base.apk')
assert not bridge.valid_apk_path('/data/local/tmp/base.apk')
assert not bridge.valid_apk_path('/data/app/../base.apk')

# Safety guard: unexpected cmdline raises, vanished process is skipped safely.
def fake_unexpected(*cmd):
    if cmd == ('ps', '-A'):
        return ps_api30
    if cmd == ('cat', '/proc/18887/cmdline'):
        return '/system/bin/mediaserver'
    return ''

rejected = False
try:
    bridge.stop_existing_bridges(fake_unexpected, fake_api23_checked)
except RuntimeError as exc:
    rejected = 'Unexpected process' in str(exc)
assert rejected

def fake_vanished(*cmd):
    if cmd == ('ps', '-A'):
        return ps_api30
    if cmd == ('cat', '/proc/18887/cmdline'):
        return 'cat: /proc/18887/cmdline: No such file or directory'
    return ''

assert bridge.stop_existing_bridges(fake_vanished, fake_api23_checked) is False

# Distinguish ADB client transport failures from remote shell stderr (Android 7/8 shell,v2).
assert bridge.is_adb_transport_error("adb.exe: device 'example:5555' not found")
assert bridge.is_adb_transport_error('adb: device unauthorized.')
assert bridge.is_adb_transport_error("error: device 'serial' not found")
assert bridge.is_adb_transport_error('error: no devices/emulators found')
assert not bridge.is_adb_transport_error('Error: Unknown option: -U')
assert not bridge.is_adb_transport_error("Error: unknown command 'get-current-user'")
assert not bridge.is_adb_transport_error("bad pid '-A'")
print('OK host start_task_bridge.py cross-Android (API 23-35+) compatibility checks')
