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
    ROOT / 'app/src/main/java/com/pilipala/tvhome/VolumeRepeatEngine.java',
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
ps_mitv4_header_only = 'USER     PID   PPID  VSIZE  RSS     WCHAN    PC         NAME\n'
ps_valid_without_bridge = (
    'USER     PID   PPID  VSIZE  RSS     WCHAN    PC         NAME\n'
    'root      1     0     1420   804   SyS_epoll_ 00000000 S /init\n'
    'system    921   1     98760  14200 SyS_epoll_ 00000000 S system_server\n'
)
ps_similar_name = (
    'USER           PID  PPID     VSZ    RSS WCHAN            ADDR S NAME\n'
    'root             1     0   12345   2345 SyS_epoll_wait      0 S init\n'
    'shell        18888     1 1345678  45678 0                   0 S marcotv-task-br\n'
    'shell        18889     1 1345678  45678 0                   0 S com.pilipala.tvhome.TaskBridgeServerFake\n'
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
assert bridge.has_ps_header(ps_mitv4_header_only)
assert not bridge.has_valid_ps_listing(ps_mitv4_header_only)
assert not bridge.has_valid_ps_listing('')
assert not bridge.has_valid_ps_listing("bad pid '-A'")
assert not bridge.has_valid_ps_listing(ps_mitv4_header_only + 'not a pid row\n')
assert bridge.has_valid_ps_listing(ps_valid_without_bridge)
assert bridge.has_ps_header(ps_api23) and bridge.has_valid_ps_listing(ps_api23)
assert bridge.has_ps_header(ps_api30) and bridge.has_valid_ps_listing(ps_api30)
assert bridge.has_ps_header(ps_busybox) and bridge.has_valid_ps_listing(ps_busybox)
assert bridge.parse_bridge_pids(ps_mitv4_header_only) == []
assert bridge.parse_bridge_pids(ps_valid_without_bridge) == []
assert bridge.parse_bridge_pids(ps_api23) == ['17574']
assert bridge.parse_bridge_pids(ps_api30) == ['18887']
assert bridge.parse_bridge_pids(ps_busybox) == ['4321']
assert bridge.parse_bridge_pids(ps_similar_name) == ['18888']
assert bridge.is_bridge_cmdline('marcotv-task-bridge\x00')
assert bridge.is_bridge_cmdline('app_process /system/bin com.pilipala.tvhome.TaskBridgeServer 10059 0')
assert not bridge.is_bridge_cmdline('marcotv-task-bridge-other')
assert not bridge.is_bridge_cmdline('app_process /system/bin com.pilipala.tvhome.TaskBridgeServerFake 10059 0')

# Case 1: MiTV4 Android 6.0.1 — ps -A exits 0 with header only, plain ps has real rows.
mitv4_calls = []
def fake_mitv4_try(*cmd):
    mitv4_calls.append(cmd)
    if cmd == ('ps', '-A'):
        return ps_mitv4_header_only
    if cmd == ('ps',):
        return ps_api23
    if cmd == ('cat', '/proc/17574/cmdline'):
        return 'marcotv-task-bridge\x00'
    raise AssertionError(f'Unexpected command: {cmd}')

status, pids = bridge.verify_bridge_started(fake_mitv4_try, sleep_fn=lambda _: None, attempts=4)
assert status == 'started' and pids == ['17574']
assert ('ps', '-A') in mitv4_calls and ('ps',) in mitv4_calls

# Case 2: Valid ps -A listing with normal system processes and no task bridge -> no fallback, reports not_running.
valid_no_bridge_calls = []
def fake_valid_no_bridge(*cmd):
    valid_no_bridge_calls.append(cmd)
    if cmd == ('ps', '-A'):
        return ps_valid_without_bridge
    raise AssertionError(f'Should accept valid ps -A without fallback: {cmd}')

assert bridge.stop_existing_bridges(fake_valid_no_bridge, lambda *_: '') is False
status, pids = bridge.verify_bridge_started(fake_valid_no_bridge, sleep_fn=lambda _: None, attempts=3)
assert status == 'not_running' and pids == []
assert ('ps',) not in valid_no_bridge_calls

# Case 3: Both ps -A and ps unusable (header-only / noise) -> reports unverifiable, never definite started/not_running.
def fake_both_unusable(*cmd):
    if cmd == ('ps', '-A'):
        return ps_mitv4_header_only
    if cmd == ('ps',):
        return ''
    raise AssertionError(f'Unexpected command: {cmd}')

unverifiable_stop = False
try:
    bridge.stop_existing_bridges(fake_both_unusable, lambda *_: '')
except bridge.ProcessListUnavailableError as exc:
    unverifiable_stop = '无法验证进程列表' in str(exc)
assert unverifiable_stop
status, pids = bridge.verify_bridge_started(fake_both_unusable, sleep_fn=lambda _: None, attempts=3)
assert status == 'unverifiable' and pids == []

# Case 4: Similar process name with wrong cmdline identity -> rejected for both stop and start verification.
def fake_similar_wrong_id(*cmd):
    if cmd == ('ps', '-A'):
        return ps_similar_name
    if cmd == ('cat', '/proc/18888/cmdline'):
        return 'marcotv-task-bridge-other\x00'
    return ''

similar_rejected = False
try:
    bridge.stop_existing_bridges(fake_similar_wrong_id, lambda *_: '')
except RuntimeError as exc:
    similar_rejected = 'Unexpected process' in str(exc)
assert similar_rejected
status, pids = bridge.verify_bridge_started(fake_similar_wrong_id, sleep_fn=lambda _: None, attempts=3)
assert status == 'not_running' and pids == []

# Case 5: Service starts (seen once) then exits before consecutive poll -> not_running.
exiting_poll = {'n': 0}
def fake_exiting_bridge(*cmd):
    if cmd == ('ps', '-A'):
        exiting_poll['n'] += 1
        return ps_api30 if exiting_poll['n'] == 1 else ps_valid_without_bridge
    if cmd == ('cat', '/proc/18887/cmdline'):
        return 'marcotv-task-bridge\x00'
    raise AssertionError(f'Unexpected command: {cmd}')

status, pids = bridge.verify_bridge_started(fake_exiting_bridge, sleep_fn=lambda _: None, attempts=4)
assert status == 'not_running' and pids == []

# Case 6 (Fix #10): Poll 1 sees PID, subsequent polls unreadable -> reports unverifiable (not not_running / started).
degrading_poll = {'n': 0}
def fake_seen_then_unreadable(*cmd):
    if cmd == ('ps', '-A'):
        degrading_poll['n'] += 1
        return ps_api30 if degrading_poll['n'] == 1 else ps_mitv4_header_only
    if cmd == ('ps',):
        return ''
    if cmd == ('cat', '/proc/18887/cmdline'):
        return 'marcotv-task-bridge\x00'
    raise AssertionError(f'Unexpected command: {cmd}')

status, pids = bridge.verify_bridge_started(fake_seen_then_unreadable, sleep_fn=lambda _: None, attempts=3)
assert status == 'unverifiable' and pids == [], f'Expected unverifiable, got {(status, pids)}'

# Case 7 (Round-3 Fix #6): Adjacent polls see DIFFERENT PIDs (18887 -> 18888 -> 18889) and final poll still has verified PID ->
# cannot confirm same process stable, yet final poll saw PID -> 'unconfirmed' (neither 'started' nor 'not_running').
changing_pid_poll = {'n': 0}
ps_pid_2 = (
    'USER           PID  PPID     VSZ    RSS WCHAN            ADDR S NAME\n'
    'root             1     0   12345   2345 SyS_epoll_wait      0 S init\n'
    'shell        18888     1 1345678  45678 0                   0 S marcotv-task-bridge\n'
)
ps_pid_3 = (
    'USER           PID  PPID     VSZ    RSS WCHAN            ADDR S NAME\n'
    'root             1     0   12345   2345 SyS_epoll_wait      0 S init\n'
    'shell        18889     1 1345678  45678 0                   0 S marcotv-task-bridge\n'
)
def fake_changing_pids(*cmd):
    if cmd == ('ps', '-A'):
        changing_pid_poll['n'] += 1
        return [ps_api30, ps_pid_2, ps_pid_3][min(changing_pid_poll['n'] - 1, 2)]
    if cmd in (('cat', '/proc/18887/cmdline'), ('cat', '/proc/18888/cmdline'), ('cat', '/proc/18889/cmdline')):
        return 'marcotv-task-bridge\x00'
    raise AssertionError(f'Unexpected command: {cmd}')

status, pids = bridge.verify_bridge_started(fake_changing_pids, sleep_fn=lambda _: None, attempts=3)
assert status == 'unconfirmed' and pids == ['18889'], f'Expected unconfirmed [18889], got {(status, pids)}'

# Case 8 (Fix #10): PID changes once (18887 -> 18888), then stabilizes on SAME PID 18888 -> started with ['18888'].
stabilizing_poll = {'n': 0}
def fake_stabilizing_pid(*cmd):
    if cmd == ('ps', '-A'):
        stabilizing_poll['n'] += 1
        return ps_api30 if stabilizing_poll['n'] == 1 else ps_pid_2
    if cmd in (('cat', '/proc/18887/cmdline'), ('cat', '/proc/18888/cmdline')):
        return 'marcotv-task-bridge\x00'
    raise AssertionError(f'Unexpected command: {cmd}')

status, pids = bridge.verify_bridge_started(fake_stabilizing_pid, sleep_fn=lambda _: None, attempts=4)
assert status == 'started' and pids == ['18888'], f'Expected started [18888], got {(status, pids)}'

# Case 9 (Fix #10): Poll 1 unreadable, Polls 2 & 3 fallback from MiTV4 header-only ps -A to plain ps with same PID -> started.
recovering_poll = {'n': 0}
def fake_unreadable_then_mitv4_stable(*cmd):
    if cmd == ('ps', '-A'):
        recovering_poll['n'] += 1
        return ps_mitv4_header_only
    if cmd == ('ps',):
        return '' if recovering_poll['n'] == 1 else ps_api23
    if cmd == ('cat', '/proc/17574/cmdline'):
        return 'marcotv-task-bridge\x00'
    raise AssertionError(f'Unexpected command: {cmd}')

status, pids = bridge.verify_bridge_started(fake_unreadable_then_mitv4_stable, sleep_fn=lambda _: None, attempts=4)
assert status == 'started' and pids == ['17574'], f'Expected started [17574], got {(status, pids)}'

# Case 10 (Round-3 Fix #6): Earlier polls see NO bridge, LAST poll sees verified PID for the first time -> 'unconfirmed' (not 'not_running').
late_start_poll = {'n': 0}
def fake_late_last_poll_pid(*cmd):
    if cmd == ('ps', '-A'):
        late_start_poll['n'] += 1
        return ps_api30 if late_start_poll['n'] == 3 else ps_valid_without_bridge
    if cmd == ('cat', '/proc/18887/cmdline'):
        return 'marcotv-task-bridge\x00'
    raise AssertionError(f'Unexpected command: {cmd}')

status, pids = bridge.verify_bridge_started(fake_late_last_poll_pid, sleep_fn=lambda _: None, attempts=3)
assert status == 'unconfirmed' and pids == ['18887'], f'Expected unconfirmed [18887], got {(status, pids)}'

# Case 11 (Round-3 Fix #6): Changing PIDs followed by final poll confirming NO bridge process -> 'not_running'.
die_after_respawn = {'n': 0}
def fake_respawn_then_gone(*cmd):
    if cmd == ('ps', '-A'):
        die_after_respawn['n'] += 1
        return [ps_api30, ps_pid_2, ps_valid_without_bridge][min(die_after_respawn['n'] - 1, 2)]
    if cmd in (('cat', '/proc/18887/cmdline'), ('cat', '/proc/18888/cmdline')):
        return 'marcotv-task-bridge\x00'
    raise AssertionError(f'Unexpected command: {cmd}')

status, pids = bridge.verify_bridge_started(fake_respawn_then_gone, sleep_fn=lambda _: None, attempts=3)
assert status == 'not_running' and pids == [], f'Expected not_running, got {(status, pids)}'

# Case 12 (Round-4 Fix #5): Candidate PID in valid ps, but /proc/<pid>/cmdline returns Permission denied -> 'identity_unverifiable'.
def fake_cmdline_permission_denied(*cmd):
    if cmd == ('ps', '-A'):
        return ps_api30
    if cmd == ('cat', '/proc/18887/cmdline'):
        return 'cat: /proc/18887/cmdline: Permission denied'
    raise AssertionError(f'Unexpected command: {cmd}')

status, pids = bridge.verify_bridge_started(fake_cmdline_permission_denied, sleep_fn=lambda _: None, attempts=3)
assert status == 'identity_unverifiable' and pids == ['18887'], f'Expected identity_unverifiable [18887], got {(status, pids)}'

# Case 13 (Round-4 Fix #5): Candidate PID in valid ps, but /proc/<pid>/cmdline returns empty -> 'identity_unverifiable'.
def fake_cmdline_empty(*cmd):
    if cmd == ('ps', '-A'):
        return ps_api30
    if cmd == ('cat', '/proc/18887/cmdline'):
        return ''
    raise AssertionError(f'Unexpected command: {cmd}')

status, pids = bridge.verify_bridge_started(fake_cmdline_empty, sleep_fn=lambda _: None, attempts=3)
assert status == 'identity_unverifiable' and pids == ['18887'], f'Expected identity_unverifiable [18887], got {(status, pids)}'

# Case 14 (Round-4 Fix #5): Candidate PID vanishes with No such file or directory (including BusyBox "can't open ... No such file") -> 'not_running'.
def fake_cmdline_vanished_busybox(*cmd):
    if cmd == ('ps', '-A'):
        return ps_api30
    if cmd == ('cat', '/proc/18887/cmdline'):
        return "cat: can't open '/proc/18887/cmdline': No such file or directory"
    raise AssertionError(f'Unexpected command: {cmd}')

status, pids = bridge.verify_bridge_started(fake_cmdline_vanished_busybox, sleep_fn=lambda _: None, attempts=3)
assert status == 'not_running' and pids == [], f'Expected not_running for vanished proc, got {(status, pids)}'

# Case 15 (Round-4 Fix #5): BusyBox "can't open ... Permission denied" -> 'identity_unverifiable' (never mistaken for vanished!).
def fake_cmdline_cant_open_denied(*cmd):
    if cmd == ('ps', '-A'):
        return ps_api30
    if cmd == ('cat', '/proc/18887/cmdline'):
        return "cat: can't open '/proc/18887/cmdline': Permission denied"
    raise AssertionError(f'Unexpected command: {cmd}')

status, pids = bridge.verify_bridge_started(fake_cmdline_cant_open_denied, sleep_fn=lambda _: None, attempts=3)
assert status == 'identity_unverifiable' and pids == ['18887'], f'Expected identity_unverifiable, got {(status, pids)}'

# Verify stop_existing_bridges refuses to claim stopped when candidate identity is unreadable.
unverifiable_id_stop = False
try:
    bridge.stop_existing_bridges(fake_cmdline_permission_denied, lambda *_: '')
except bridge.BridgeIdentityUnverifiableError as exc:
    unverifiable_id_stop = '发现候选进程' in str(exc) and '身份无法核实' in str(exc)
assert unverifiable_id_stop

# Case 16 (Follow-up #1): Mixed verified + unreadable candidate PIDs on --stop ->
# kills ONLY verified PID, NEVER kills unreadable PID, and explicitly reports both PIDs separately!
ps_mixed_candidates = (
    'USER           PID  PPID     VSZ    RSS WCHAN            ADDR S NAME\n'
    'root             1     0   12345   2345 SyS_epoll_wait      0 S init\n'
    'shell        18887     1 1345678  45678 0                   0 S marcotv-task-bridge\n'
    'shell        18888     1 1345678  45678 0                   0 S marcotv-task-bridge\n'
)
mixed_killed = []
def fake_mixed_stop_try(*cmd):
    if cmd == ('ps', '-A'):
        return ps_mixed_candidates
    if cmd == ('cat', '/proc/18887/cmdline'):
        return 'marcotv-task-bridge\x00'
    if cmd == ('cat', '/proc/18888/cmdline'):
        return 'cat: /proc/18888/cmdline: Permission denied'
    raise AssertionError(f'Unexpected command: {cmd}')

def fake_mixed_stop_checked(*cmd):
    if cmd[0] == 'kill':
        mixed_killed.append(cmd[1])
        return ''
    raise AssertionError(f'Unexpected checked command: {cmd}')

mixed_stop_error = ''
try:
    bridge.stop_existing_bridges(fake_mixed_stop_try, fake_mixed_stop_checked)
except bridge.BridgeIdentityUnverifiableError as exc:
    mixed_stop_error = str(exc)
assert mixed_killed == ['18887'], f'Must only kill verified PID 18887, got {mixed_killed}'
assert '发出停止命令' in mixed_stop_error and '18887' in mixed_stop_error, mixed_stop_error
assert '身份仍无法核实' in mixed_stop_error and '18888' in mixed_stop_error, mixed_stop_error
assert 'Marco TV task bridge stopped' not in mixed_stop_error, mixed_stop_error

# End-to-end real CLI entry test via mock ADB shim (cross-platform Windows/POSIX) for --stop.
import os, pathlib, stat, sys, tempfile
with tempfile.TemporaryDirectory() as tmp_dir:
    tmp_path = pathlib.Path(tmp_dir)
    kill_log = tmp_path / 'kills.txt'
    mock_py = tmp_path / 'mock_adb.py'
    mock_py.write_text(
        'import pathlib, sys\n'
        'args = sys.argv[1:]\n'
        'mode = pathlib.Path(sys.argv[0]).with_name("mode.txt").read_text(encoding="utf-8").strip()\n'
        'cmd = args[args.index("shell") + 1:] if "shell" in args else []\n'
        'if cmd == ["ps", "-A"]:\n'
        '    print("USER           PID  PPID     VSZ    RSS WCHAN            ADDR S NAME")\n'
        '    print("root             1     0   12345   2345 SyS_epoll_wait      0 S init")\n'
        '    if mode in ("mixed", "verified_only"):\n'
        '        print("shell        18887     1 1345678  45678 0                   0 S marcotv-task-bridge")\n'
        '    if mode == "mixed":\n'
        '        print("shell        18888     1 1345678  45678 0                   0 S marcotv-task-bridge")\n'
        'elif cmd == ["cat", "/proc/18887/cmdline"]:\n'
        '    sys.stdout.write("marcotv-task-bridge\\0")\n'
        'elif cmd == ["cat", "/proc/18888/cmdline"]:\n'
        '    sys.stderr.write("cat: /proc/18888/cmdline: Permission denied\\n")\n'
        '    sys.exit(1)\n'
        'elif len(cmd) == 2 and cmd[0] == "kill":\n'
        '    with open(pathlib.Path(sys.argv[0]).with_name("kills.txt"), "a", encoding="utf-8") as fh:\n'
        '        fh.write(cmd[1] + "\\n")\n',
        encoding='utf-8',
    )
    if os.name == 'nt':
        mock_adb = tmp_path / 'mock_adb.cmd'
        mock_adb.write_text(f'@echo off\r\n"{sys.executable}" "{mock_py}" %*\r\n', encoding='utf-8')
    else:
        mock_adb = tmp_path / 'mock_adb.sh'
        mock_adb.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{mock_py}" "$@"\n', encoding='utf-8')
        mock_adb.chmod(mock_adb.stat().st_mode | stat.S_IEXEC)

    # 16a: mixed verified + unreadable candidate PIDs -> only 18887 killed, non-zero exit, reports both PIDs distinctly.
    (tmp_path / 'mode.txt').write_text('mixed', encoding='utf-8')
    cli_mixed = subprocess.run(
        [sys.executable, str(ROOT / 'scripts/start_task_bridge.py'), '--adb', str(mock_adb), '--serial', 'mock-tv', '--stop'],
        capture_output=True, text=True, encoding='utf-8', errors='replace',
    )
    combined_mixed = (cli_mixed.stdout or '') + '\n' + (cli_mixed.stderr or '')
    assert cli_mixed.returncode != 0, f'Expected non-zero exit on mixed --stop, got {combined_mixed}'
    assert kill_log.read_text(encoding='utf-8').splitlines() == ['18887']
    assert '发出停止命令' in combined_mixed and '18887' in combined_mixed, combined_mixed
    assert '身份仍无法核实' in combined_mixed and '18888' in combined_mixed, combined_mixed
    assert 'Marco TV task bridge stopped' not in combined_mixed, combined_mixed

    # 16b: verified-only candidate PID -> 18887 killed, exit 0, reports PID 18887 stopped.
    kill_log.unlink()
    (tmp_path / 'mode.txt').write_text('verified_only', encoding='utf-8')
    cli_ok = subprocess.run(
        [sys.executable, str(ROOT / 'scripts/start_task_bridge.py'), '--adb', str(mock_adb), '--serial', 'mock-tv', '--stop'],
        capture_output=True, text=True, encoding='utf-8', errors='replace',
    )
    assert cli_ok.returncode == 0, cli_ok.stderr
    assert kill_log.read_text(encoding='utf-8').splitlines() == ['18887']
    assert 'Marco TV task bridge stopped (PID 18887).' in cli_ok.stdout, cli_ok.stdout

    # 16c: no bridge running -> nothing killed, exit 0, reports stopped.
    kill_log.unlink()
    (tmp_path / 'mode.txt').write_text('none', encoding='utf-8')
    cli_none = subprocess.run(
        [sys.executable, str(ROOT / 'scripts/start_task_bridge.py'), '--adb', str(mock_adb), '--serial', 'mock-tv', '--stop'],
        capture_output=True, text=True, encoding='utf-8', errors='replace',
    )
    assert cli_none.returncode == 0, cli_none.stderr
    assert not kill_log.exists()
    assert 'Marco TV task bridge stopped.' in cli_none.stdout, cli_none.stdout

# Verify distinct user-facing messages for all 5 outcomes.
assert 'started (PID 18887)' in bridge.format_verify_outcome('started', ['18887'])
assert '已观察到进程' in bridge.format_verify_outcome('unconfirmed', ['18887']) and '稳定性尚未确认' in bridge.format_verify_outcome('unconfirmed', ['18887'])
assert '发现候选进程' in bridge.format_verify_outcome('identity_unverifiable', ['18887']) and '身份无法核实' in bridge.format_verify_outcome('identity_unverifiable', ['18887'])
assert '确认未运行' in bridge.format_verify_outcome('not_running', [], 'log')
assert '无法验证进程列表' in bridge.format_verify_outcome('unverifiable', [])

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
