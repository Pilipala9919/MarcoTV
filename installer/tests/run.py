# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Offline checks by default; real-device installation requires explicit --install."""
import argparse, os, sys, importlib.util
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('installer_build',ROOT/'installer/build.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
BUILD,JDK,OUT,PAYLOAD_VERSION,executable,run=(getattr(module,n) for n in ('BUILD','JDK','OUT','PAYLOAD_VERSION','executable','run'))

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--tv',help='Your own TV private IPv4 address (ordinary ADB 5555)')
    p.add_argument('--install',action='store_true',help='Also reinstall embedded TV APK, preserving data')
    a=p.parse_args()
    if a.install and not a.tv:p.error('--install requires --tv')
    csc=Path(os.environ['WINDIR'])/'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
    refs=['/r:System.Windows.Forms.dll','/r:System.Drawing.dll','/r:System.Numerics.dll']
    run(csc,'/nologo','/target:exe','/main:WireChecks',f'/out:{BUILD/"wirechecks.exe"}',
        f'/resource:{OUT/f"MarcoTV-{PAYLOAD_VERSION}.apk"},MarcoTV.apk',*refs,
        BUILD/'ReleaseInfo.cs',ROOT/'installer/windows/AdbWire.cs',ROOT/'installer/windows/Program.cs',
        ROOT/'installer/tests/WireChecks.cs')
    run(BUILD/'checks.exe','--self-test')
    run(BUILD/'wirechecks.exe',*([a.tv] if a.tv else []),*(['--install-embedded'] if a.install else []))
    classes=BUILD/'jvm';classes.mkdir(exist_ok=True)
    run(executable(JDK/'bin','javac'),'-encoding','UTF-8','-d',classes,
        ROOT/'installer/android/src/com/pilipala/tvinstaller/AdbWire.java',ROOT/'installer/tests/WireChecks.java')
    run(executable(JDK/'bin','java'),'-cp',classes,'com.pilipala.tvinstaller.WireChecks',
        BUILD/'test-key',*([a.tv] if a.tv else []))

if __name__=='__main__':main()
