# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Physical RIGHT witness. ADB is used for foreground checks and PNG capture only."""
import argparse
import datetime
import getpass
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'remote'))
import marco_remote as cli


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    args = parser.parse_args()
    base = [args.adb, '-s', args.serial]
    session = cli.pair(getpass.getpass("Paste the optically scanned TV QR here (hidden): ").strip())
    folder = ROOT / 'private/focus-witness' / datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    folder.mkdir(parents=True)
    report = {'case':'physical-RIGHT', 'visual_review':'pending', 'samples':[]}
    def state():
        _, pos = session.state()
        return [int(n) for n in pos.split()]
    def capture(filename):
        window = subprocess.check_output(base+['shell','dumpsys','window','windows'],timeout=5)
        if not any(b'mCurrentFocus=' in line and b'com.pilipala.tvhome.focuslab/com.pilipala.tvhome.MainActivity' in line
                   for line in window.splitlines()):
            raise RuntimeError('Expected side-by-side HomeView foreground')
        png = subprocess.check_output(base+['exec-out','screencap','-p'],timeout=8)
        if not png.startswith(b'\x89PNG\r\n\x1a\n'):
            raise RuntimeError('Invalid frame')
        (folder/filename).write_bytes(png)
        return hashlib.sha256(png).hexdigest()
    try:
        time.sleep(1.5)  # allow the approval dialog/toast to leave the card surface
        before = state()
        report['before'] = before
        report['before_png_sha256'] = capture('before.png')
        # Confirm the screen still corresponds to the exact snapshot used for STEP.
        nonce, pos = session.state()
        if list(map(int,pos.split())) != before:
            raise RuntimeError('Local state changed during capture')
        result = session.request('STEP '+nonce+' RIGHT')
        if not result.startswith('MOVED '):
            raise RuntimeError('RIGHT did not move the card')
        moved = list(map(int,result.split()[1:]))
        report['right_reply'] = moved
        time.sleep(.9)
        report['after_png_sha256'] = capture('after.png')
        after = state()
        report['after'] = after
        if after != moved or moved != before[:2]+[before[2]+1,before[3],before[4]+1]:
            raise RuntimeError('Snapshot/ACK/after mismatch')
        report['position_check']='passed'
        print('RIGHT position verified. Visually compare before.png and after.png in',folder)
    finally:
        try: session.run('CLOSE')
        finally:
            session.sock.close()
            (folder/'observations.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')


if __name__ == '__main__':
    try: main()
    except (OSError,RuntimeError,EOFError,ValueError,KeyError,TypeError,KeyboardInterrupt) as problem:
        sys.exit('Witness incomplete: '+type(problem).__name__)
