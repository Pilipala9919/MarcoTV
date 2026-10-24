# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Explicit public-source allowlist shared by packaging and release checks."""
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
ROOT_FILES=['README.md','LICENSE','NOTICE','assets-notes.md','.gitignore',
            'CONTRIBUTING.md','SECURITY.md','CHANGELOG.md']
SCRIPT_FILES=['bootstrap.py','build.py','setup_system.py','test_motion.py',
              'MotionSpringChecks.java','public_files.py','check_release.py','package.py','measure_memory.py','test_release_tools.py','test_device_regressions.py','DeviceRegressionChecks.java']

def source_files():
    files=[ROOT/name for name in ROOT_FILES]
    files.extend(ROOT/'scripts'/name for name in SCRIPT_FILES)
    for folder in ['app','docs','.github','licenses']:
        files.extend(p for p in (ROOT/folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
    return sorted(files)

if __name__=='__main__':
    print('\n'.join(p.relative_to(ROOT).as_posix() for p in source_files()))
