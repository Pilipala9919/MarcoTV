# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Unified offline verification runner for all host-side checks and release guards."""
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
SUITES = [
    'test_marco_focus.py',
    'test_home_setup.py',
    'test_home_auto.py',
    'test_home_guide.py',
    'test_feedback.py',
    'test_feedback_transport.py',
    'test_motion.py',
    'test_motion_lifecycle.py',
    'test_core_consistency.py',
    'test_ambient_weather.py',
    'test_release_tools.py',
    'test_settings.py',
    'test_updates.py',
    'test_phone_http.py',
    'test_restricted.py',
    'test_lite.py',
    'check_release.py',
]


def main():
    started = time.monotonic()
    for name in SUITES:
        t0 = time.monotonic()
        result = subprocess.run([sys.executable, str(ROOT / 'scripts' / name)], cwd=ROOT)
        elapsed = time.monotonic() - t0
        if result.returncode != 0:
            raise SystemExit(f'FAILED: {name} ({elapsed:.2f}s)')
        print(f'==> PASS {name} ({elapsed:.2f}s)', flush=True)
    total = time.monotonic() - started
    print(f'All {len(SUITES)} offline suites passed in {total:.2f}s.')


if __name__ == '__main__':
    main()
