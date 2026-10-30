# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Offline state-policy checks. No device or system settings are modified."""
import subprocess
from build import ROOT,JDK,executable

folder=ROOT/'build/home-setup-policy';folder.mkdir(parents=True,exist_ok=True)
subprocess.run([str(executable(JDK/'bin','javac')),'-encoding','UTF-8','-d',str(folder),
    str(ROOT/'app/src/main/java/com/pilipala/tvhome/HomeSetupPolicy.java'),str(ROOT/'scripts/HomeSetupChecks.java')],check=True)
result=subprocess.run([str(executable(JDK/'bin','java')),'-cp',str(folder),'com.pilipala.tvhome.HomeSetupChecks'],check=True,capture_output=True,text=True)
(folder/'result.txt').write_text(result.stdout,encoding='utf8');print(result.stdout)

# Only Android Base64 is shimmed; the protocol/key/socket code is compiled unchanged.
stub=folder/'android/util/Base64.java';stub.parent.mkdir(parents=True,exist_ok=True)
stub.write_text('package android.util; public class Base64 {public static final int NO_WRAP=2; public static String encodeToString(byte[] b,int flags){return java.util.Base64.getEncoder().encodeToString(b);}}',encoding='utf8')
subprocess.run([str(executable(JDK/'bin','javac')),'-encoding','UTF-8','-d',str(folder),str(stub),
    str(ROOT/'app/src/main/java/com/pilipala/tvhome/LocalAdbWire.java'),str(ROOT/'scripts/LocalAdbWireChecks.java')],check=True)
result=subprocess.run([str(executable(JDK/'bin','java')),'-cp',str(folder),'com.pilipala.tvhome.LocalAdbWireChecks',str(folder/'fixture-key')],check=True,capture_output=True,text=True,timeout=40)
(folder/'transport-result.txt').write_text(result.stdout,encoding='utf8');print(result.stdout)
