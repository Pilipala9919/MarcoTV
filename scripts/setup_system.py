# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Grant MarcoTV's one-time settings access; optionally select its enabled HOME entry."""
import argparse
import subprocess

p = argparse.ArgumentParser()
p.add_argument('--adb', required=True)
p.add_argument('--serial', required=True, help='Exact endpoint or USB serial from adb devices -l.')
p.add_argument('--select-home', action='store_true', help='Use after enabling the HOME entry in MarcoTV settings.')
a = p.parse_args()
base = [a.adb, '-s', a.serial]
raw_user = subprocess.check_output(base + ['shell', 'am', 'get-current-user'], text=True, encoding='utf-8').strip()
user = raw_user if raw_user.isdigit() else '0'
subprocess.run(base + ['shell', 'pm', 'grant', '--user', user, 'com.pilipala.tvhome', 'android.permission.WRITE_SECURE_SETTINGS'], check=True)
if a.select_home:
    res = subprocess.run(base + ['shell', 'cmd', 'package', 'set-home-activity', '--user', user, 'com.pilipala.tvhome/.HomeActivity'])
    if res.returncode != 0:
        subprocess.run(base + ['shell', 'pm', 'set-home-activity', '--user', user, 'com.pilipala.tvhome/.HomeActivity'], check=True)
print('Authorization complete. Configure ADB recovery and HOME in MarcoTV settings.')
