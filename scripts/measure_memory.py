# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Same-protocol memory comparison between two installed builds."""
import subprocess, time, re, sys, os
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ADB = r"D:/下载/杂项/澎湃os2解锁root教程等2个文件/澎湃os2解锁root教程/fastboot/fastboot/adb.exe"
HOST = "192.168.1.28:5555"


def adb(*args):
    return subprocess.run([ADB, "-s", HOST, *args], capture_output=True).stdout.decode("utf-8", "replace")


def shell(*args):
    return adb("shell", *args)


def pid():
    out = adb("shell", "pidof", "com.pilipala.tvhome")
    return out.strip().split()[0] if out.strip() else None


def measure():
    shell("kill", "-10", pid())          # ask ART to collect
    time.sleep(4)
    report = shell("dumpsys", "meminfo", "com.pilipala.tvhome")
    values = {}
    for line in report.splitlines():
        m = re.match(r"\s*(TOTAL|Native Heap|Dalvik Heap|Graphics)\s+(\d+)\s+(\d+)\s+(\d+)", line)
        if m:
            values[m.group(1)] = {"pss": int(m.group(2)), "dirty": int(m.group(3))}
    summary = re.search(r"TOTAL PSS:\s+(\d+)", report)
    values["summary_pss"] = int(summary.group(1)) if summary else -1
    return values


def settle(version, samples=3):
    shell("input", "keyevent", "3")
    time.sleep(12)
    readings = [measure() for _ in range(samples)]
    best = min(readings, key=lambda r: r["TOTAL"]["dirty"])
    return best


def install(version):
    print(f"--- Marco TV {version} ---", flush=True)
    print(subprocess.run([ADB, "-s", HOST, "install", "-r", str(ROOT / f"dist/MarcoTV-{version}.apk")],
                         capture_output=True).stdout.decode("utf-8", "replace").strip(), flush=True)
    shell("cmd", "package", "set-home-activity", "--user", "0", "com.pilipala.tvhome/.HomeActivity")
    time.sleep(3)


if __name__ == "__main__":
    subprocess.run([ADB, "connect", HOST], capture_output=True)
    result = {}
    for version in sys.argv[1:]:
        install(version)
        time.sleep(6)
        result[version] = settle(version)
        print(version, result[version], flush=True)
    versions = list(result)
    if len(versions) == 2:
        a, b = versions
        for key in result[a]:
            av = result[a][key] if isinstance(result[a][key], dict) else result[a][key]
            bv = result[b][key] if isinstance(result[b][key], dict) else result[b][key]
            if isinstance(av, dict):
                print(f"{key}: {a}={av['pss']}KB -> {b}={bv['pss']}KB   ({bv['pss']-av['pss']:+d})")
            else:
                print(f"{key}: {a}={av}KB -> {b}={bv}KB   ({bv-av:+d})")
