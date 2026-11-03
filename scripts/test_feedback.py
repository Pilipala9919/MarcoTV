# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Run actual report allowlist policy and server against an in-memory SQLite database."""
import subprocess
from build import ROOT,JDK,executable
folder=ROOT/'build/feedback-tests'
folder.mkdir(parents=True,exist_ok=True)
subprocess.run([str(executable(JDK/'bin','javac')),'-encoding','UTF-8','-d',str(folder),str(ROOT/'app/src/main/java/com/pilipala/tvhome/FeedbackPolicy.java'),str(ROOT/'scripts/FeedbackPolicyChecks.java')],check=True)
subprocess.run([str(executable(JDK/'bin','java')),'-cp',str(folder),'com.pilipala.tvhome.FeedbackPolicyChecks'],check=True)
subprocess.run(['node','--experimental-sqlite',str(ROOT/'feedback/tests/backend.mjs')],cwd=ROOT,check=True)
subprocess.run(['node',str(ROOT/'feedback/tests/web.mjs')],cwd=ROOT,check=True)
