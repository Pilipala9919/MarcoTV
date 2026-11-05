# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Opt-in physical Android diagnostic, NOT pairing. Requires installed debug probe.
Uses authenticated ADB only for launch and out-of-band cert bootstrap; TLS uses LAN.
No simulated local approval, key input, ticket generation or focus change.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import ssl
import re
import subprocess
import sys
import time
import client


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb',required=True,type=Path);p.add_argument('--serial',required=True)
    p.add_argument('--output',required=True,type=Path)
    p.add_argument('--untrusted-pem',required=True,type=Path,help='Unrelated valid test certificate, for negative TLS test')
    args=p.parse_args(); root=Path(__file__).resolve().parents[2]
    base=[str(args.adb),'-s',args.serial]
    def adb(*a):return subprocess.run(base+list(a),check=True,capture_output=True,timeout=20).stdout
    report={'started_utc':datetime.now(timezone.utc).isoformat(),'kind':'physical-TV-TLS-diagnostic-NOT-pairing',
            'device':{},'checks':[],'client_runtime':sys.version,'openssl':ssl.OPENSSL_VERSION,
            'approval':'NOT_RUN_USER_UNAVAILABLE','session':'NOT_VERIFIED','source_sha256':{}}
    for path in sorted((root/'security-spike').rglob('*')):
        if path.suffix in ('.java','.py','.xml'):
            report['source_sha256'][path.relative_to(root).as_posix()]=hashlib.sha256(path.read_bytes()).hexdigest()
    report['apk_sha256']=hashlib.sha256((root/'build/security-spike/MarcoTlsProbe-local.apk').read_bytes()).hexdigest()
    args.output.parent.mkdir(parents=True,exist_ok=True)
    try:
        for prop in ('ro.product.model','ro.build.version.release','ro.build.version.sdk','ro.build.display.id'):
            report['device'][prop]=adb('shell','getprop',prop).decode().strip()
        installed = adb('shell','pm','path','com.pilipala.remoteprobe').decode().strip().removeprefix('package:')
        if not re.fullmatch(r'/data/app/[A-Za-z0-9_./=+\-]+/base.apk', installed): raise RuntimeError('Unexpected package path')
        installed_hash = adb('shell','sha256sum',installed).decode().split()[0]
        assert installed_hash == report['apk_sha256'], 'Installed APK differs from reviewed build'
        report['installed_apk_sha256'] = installed_hash
        pins=[]
        for cycle in range(2):
            adb('shell','am','force-stop','com.pilipala.remoteprobe')
            # Exact developer-owned diagnostic artifact only; avoid stale bootstrap on restart.
            adb('shell','run-as','com.pilipala.remoteprobe','rm','-f','files/diagnostic.json')
            adb('shell','am','start','-n','com.pilipala.remoteprobe/.ProbeActivity','--ez','tls_diagnostic','true')
            for attempt in range(30):
                result=subprocess.run(base+['shell','run-as','com.pilipala.remoteprobe','cat','files/diagnostic.json'],capture_output=True)
                if result.returncode==0:
                    bundle=json.loads(result.stdout); break
                time.sleep(.2)
            else:raise RuntimeError('Diagnostic not started')
            assert 'ticket' not in bundle
            pins.append(bundle['pin_sha256'])
            with client.connect(bundle) as tls:
                report['checks'].append({'test':'trusted_handshake_'+str(cycle),'passed':True,
                                         'tls':tls.version(),'cipher':tls.cipher()[0],'application_bytes_sent':0})
            if cycle==0:
                wrong=dict(bundle,pin_sha256='0'*64)
                try: client.connect(wrong).close()
                except ssl.SSLCertVerificationError: report['checks'].append({'test':'bad_pin','passed':True,'application_bytes_sent':0})
                else: raise AssertionError('bad pin accepted')
                pem=args.untrusted_pem.read_text()
                wrong=dict(bundle,pem=pem,pin_sha256=hashlib.sha256(ssl.PEM_cert_to_DER_cert(pem)).hexdigest())
                try: client.connect(wrong).close()
                except ssl.SSLCertVerificationError: report['checks'].append({'test':'untrusted_real_server_certificate','passed':True,'application_bytes_sent':0})
                else: raise AssertionError('untrusted certificate accepted')
                wrong=dict(bundle,device_id='marco-00000000-0000-0000-0000-000000000000')
                try: client.connect(wrong).close()
                except ssl.SSLCertVerificationError: report['checks'].append({'test':'wrong_device_identity','passed':True,'application_bytes_sent':0})
                else: raise AssertionError('wrong identity accepted')
        assert pins[0]==pins[1]
        report['checks'].append({'test':'keystore_identity_survives_process_restart','passed':True})
        report['diagnostic_passed']=True
    finally:
        report['events']=adb('shell','run-as','com.pilipala.remoteprobe','cat','files/events.txt').decode().splitlines()
        adb('shell','am','force-stop','com.pilipala.remoteprobe')
        report['probe_force_stopped']=True
        time.sleep(.3)
        process = subprocess.run(base+['shell','pidof','com.pilipala.remoteprobe'],capture_output=True)
        report['probe_process_absent'] = not process.stdout.strip()
        if 'bundle' in locals():
            import socket
            closed = False
            for attempt in range(15):
                try:
                    with socket.create_connection((bundle['host'],bundle['port']),timeout=.5): pass
                except OSError:
                    closed = True; break
                time.sleep(.2)
            report['diagnostic_port_closed_after_stop'] = closed
        args.output.write_text(json.dumps(report,indent=2,ensure_ascii=False),encoding='utf-8')
    if not report.get('diagnostic_port_closed_after_stop'): raise RuntimeError('Listener closure unverified')
    print(json.dumps({'diagnostic_passed':True,'checks':report['checks'],'session':'NOT_VERIFIED'},indent=2))


if __name__=='__main__':main()
