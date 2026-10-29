# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Fetch the official API 14 platform; no emulator or machine-wide installation."""
from pathlib import Path
import hashlib,urllib.request,zipfile
ROOT=Path(__file__).resolve().parents[1]
URL='https://dl.google.com/android/repository/android-14_r04.zip'
SHA1='d4f1d8fbca25225b5f0e7a0adf0d39c3d6e60b3c'
def prepare():
 dest=ROOT/'.tools/lite-legacy';dest.mkdir(exist_ok=True,parents=True);archive=dest/'platform.zip'
 if not archive.exists():
  with urllib.request.urlopen(URL,timeout=60) as src,archive.open('wb') as out:
   while True:
    chunk=src.read(1024*1024)
    if not chunk:break
    out.write(chunk)
 if hashlib.sha1(archive.read_bytes()).hexdigest()!=SHA1:raise RuntimeError('Official SDK archive checksum mismatch; remove only the corrupt .tools/lite-legacy/platform.zip and retry.')
 target=dest/'platform'
 with zipfile.ZipFile(archive) as z:
  if not all((target/n).resolve().is_relative_to(target.resolve()) for n in z.namelist()):raise RuntimeError('Unsafe archive path')
  z.extractall(target)
 print('Official API 14 platform verified:',target/'android-4.0.2/android.jar')
if __name__=='__main__':prepare()
