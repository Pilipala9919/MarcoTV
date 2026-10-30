# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Explicit public-source allowlist shared by packaging and release checks."""
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
ROOT_FILES=['README.md','LICENSE','NOTICE','assets-notes.md','.gitignore',
            'CONTRIBUTING.md','SECURITY.md','CHANGELOG.md']
SCRIPT_FILES=['bootstrap.py','build.py','setup_system.py','start_task_bridge.py','test_motion.py',
              'MotionSpringChecks.java','public_files.py','check_release.py','package.py','measure_memory.py','test_release_tools.py','test_device_regressions.py','DeviceRegressionChecks.java','FeatureRegressionChecks.java','SettingsMigrationChecks.java','test_settings.py','test_updates.py','UpdatePolicyChecks.java','UpdateDeviceChecks.java','make_update_feed.py','package_mirror.py','AppearanceDeviceChecks.java','PhoneDeviceChecks.java','PhoneHttpChecks.java','test_phone_http.py']

def source_files():
    files=[ROOT/name for name in ROOT_FILES]
    files.extend(ROOT/'scripts'/name for name in SCRIPT_FILES)
    files.extend(ROOT/'scripts'/name for name in ['bootstrap_lite.py','build_lite.py','test_lite.py','test_lite_device.py','test_lite_stability.py','test_drawer_home.py','DrawerHomeChecks.java','MotionDeviceChecks.java','test_ui_motion.py'])
    for folder in ['app','lite','docs','.github','licenses']:
        files.extend(p for p in (ROOT/folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
    files.extend(p for p in (ROOT/'installer').rglob('*') if p.is_file()
                 and p.suffix in {'.cs','.java','.py','.xml','.md','.json'}
                 and '__pycache__' not in p.parts)
    return sorted(files)

if __name__=='__main__':
    print('\n'.join(p.relative_to(ROOT).as_posix() for p in source_files()))
