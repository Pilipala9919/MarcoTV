# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Read-only memory sampling; device selection is explicit and never included in reports."""
import argparse
import json
import re
import statistics
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

PACKAGE = "com.pilipala.tvhome"


def parse_meminfo(text):
    values = {}
    for line in text.splitlines():
        match = re.match(r"\s*(TOTAL|Native Heap|Dalvik Heap|Graphics)\s+(\d+)\s+(\d+)\s+(\d+)", line)
        if match:
            name = match[1].lower().replace(" ", "_")
            values[name + "_pss_kb"] = int(match[2])
            values[name + "_private_kb"] = int(match[3]) + int(match[4])
    summary = re.search(r"TOTAL PSS:\s*(\d+)", text)
    if summary:
        values["total_pss_kb"] = int(summary[1])
    if "total_pss_kb" not in values:
        raise ValueError("No TOTAL PSS found; process may have exited or meminfo format changed")
    return values


def summarize(samples):
    keys = set.intersection(*(set(s["memory"]) for s in samples))
    return {key: {"min": min(v), "median": statistics.median(v), "max": max(v),
                  "first": v[0], "last": v[-1]}
            for key in sorted(keys) for v in [[s["memory"][key] for s in samples]]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb", help="ADB executable path, or adb on PATH")
    parser.add_argument("--serial", required=True, help="Connected device serial or IP:port")
    parser.add_argument("--samples", type=int, default=12)
    parser.add_argument("--interval", type=float, default=5)
    parser.add_argument("--label", default="idle-desktop", help="Scenario; do not include personal data")
    parser.add_argument("--output", type=Path, default=Path("build/memory-samples.json"))
    parser.add_argument("--compare", type=Path, help="Earlier report from the same device and scenario")
    args = parser.parse_args()
    if args.samples < 2 or args.interval < 0:
        parser.error("Use at least 2 samples and a non-negative interval")
    def shell(*command):
        result = subprocess.run([args.adb, "-s", args.serial, "shell", *command],
                                capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=45)
        if result.returncode:
            raise RuntimeError("ADB command failed; check device connection and authorization")
        return result.stdout
    package = shell("dumpsys", "package", PACKAGE)
    match = re.search(r"versionName=([^\s]+)", package)
    if not match:
        raise RuntimeError("Marco TV is not installed")
    report = {"version": match[1], "date": datetime.now(timezone.utc).isoformat(),
              "scenario": args.label, "interval_seconds": args.interval, "samples": [],
              "scope": "Observed samples only, not process-wide peaks or a smoothness/long-term stability test."}
    before = shell("pidof", PACKAGE).strip()
    if not before:
        raise RuntimeError("Open Marco TV before sampling")
    started = time.monotonic()
    for index in range(args.samples):
        if shell("pidof", PACKAGE).strip() != before:
            raise RuntimeError("Process restarted during sampling; discard comparison and investigate")
        report["samples"].append({"elapsed_seconds": round(time.monotonic() - started, 2),
                                   "memory": parse_meminfo(shell("dumpsys", "meminfo", PACKAGE))})
        print(f"Sample {index + 1}/{args.samples}: {report['samples'][-1]['memory']['total_pss_kb']} KB PSS", flush=True)
        if index + 1 < args.samples:
            time.sleep(args.interval)
    report["summary"] = summarize(report["samples"])
    if args.compare:
        previous = json.loads(args.compare.read_text(encoding="utf-8"))
        if (previous["scenario"] != args.label or previous["interval_seconds"] != args.interval
                or len(previous["samples"]) != args.samples):
            raise ValueError("Comparison needs the same scenario, interval and sample count")
        report["comparison"] = {"previous_version": previous["version"],
            "same_device_requires_operator_confirmation": True,
            "median_pss_delta_kb": report["summary"]["total_pss_kb"]["median"] - previous["summary"]["total_pss_kb"]["median"]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report["summary"], indent=2))


if __name__ == "__main__":
    main()
