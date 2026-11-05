# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Production Java transport + policy on host TLS; simulated approval, NOT a TV result.
Set MARCO_TEST_BIND to a physical private IPv4 owned by the Windows host.
"""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from cryptography import x509
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.serialization import pkcs12
from test_client import certificate, bundle, client

ROOT=Path(__file__).resolve().parents[2]
CFG=json.loads((ROOT/'.tools/toolchain.json').read_text())
JDK=Path(CFG['jdk']); SUFFIX='.exe' if os.name=='nt' else ''
OUT=ROOT/'build/security-transport-tests'


class TransportTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.host=os.environ['MARCO_TEST_BIND'];OUT.mkdir(parents=True,exist_ok=True)
        sources=list((ROOT/'security-spike/tests/host').rglob('*.java'))
        sources += [ROOT/'security-spike/src/com/pilipala/remoteprobe'/name for name in ('ProbeServer.java','SessionPolicy.java','BackgroundClose.java')]
        subprocess.run([str(JDK/('bin/javac'+SUFFIX)),'--release','8','-encoding','UTF-8','-d',str(OUT),*map(str,sources)],check=True)
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        pem,key=certificate();path=Path(self.tmp.name)/'fixture.p12'
        path.write_bytes(pkcs12.serialize_key_and_certificates(b'probe',serialization.load_pem_private_key(key,None),
                         x509.load_pem_x509_certificate(pem.encode()),None,serialization.BestAvailableEncryption(b'fixture')))
        self.pem,self.path=pem,path
    def start(self,mode='approve'):
        process=subprocess.Popen([str(JDK/('bin/java'+SUFFIX)),'-cp',str(OUT),'com.pilipala.remoteprobe.TransportFixture',str(self.path),self.host,mode],
                                 stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        def cleanup():
            if process.poll() is None:
                process.stdin.write('\n');process.stdin.flush()
            try:process.communicate(timeout=5)
            except subprocess.TimeoutExpired:process.kill();process.communicate()
        self.addCleanup(cleanup)
        line=process.stdout.readline().strip().split()
        if len(line)!=2: raise AssertionError('Host fixture failed to start: '+process.stderr.read())
        b=bundle(self.pem,int(line[0]));b.update(host=self.host,ticket=line[1])
        tls=client.connect(b);self.addCleanup(tls.close)
        return tls,b,process
    def granted(self):
        tls,b,p=self.start();client.send(tls,'PAIR '+b['ticket'])
        self.assertEqual(client.receive(tls),'PENDING')
        grant=client.receive(tls);self.assertTrue(grant.startswith('GRANTED '))
        return tls,grant.split()[1],p
    def test_fake_step_replay_and_disconnect(self):
        tls,sid,p=self.granted();client.send(tls,sid+' 1 SNAPSHOT');nonce=client.receive(tls).split()[1]
        client.send(tls,sid+' 2 STEP '+nonce+' RIGHT');self.assertEqual(client.receive(tls),'FAKE_APPLIED 1')
        client.send(tls,sid+' 2 STEP '+nonce+' RIGHT');self.assertEqual(client.receive(tls),'REPLAY')
        with self.assertRaises(EOFError):client.receive(tls)
    def test_refusal_never_grants(self):
        tls,b,p=self.start('deny');client.send(tls,'PAIR '+b['ticket']);self.assertEqual(client.receive(tls),'PENDING')
        self.assertEqual(client.receive(tls),'DENIED_OR_EXPIRED')
    def test_wrong_ticket(self):
        tls,b,p=self.start();client.send(tls,'PAIR '+'0'*64);self.assertEqual(client.receive(tls),'BAD_TICKET')
    def test_stale_command(self):
        tls,sid,p=self.granted();client.send(tls,sid+' 1 SNAPSHOT');nonce=client.receive(tls).split()[1];time.sleep(1.05)
        client.send(tls,sid+' 2 STEP '+nonce+' LEFT');self.assertEqual(client.receive(tls),'STALE_CHALLENGE')
    def test_unauthenticated_command_rejected(self):
        tls,b,p=self.start();client.send(tls,'0'*64+' 1 SNAPSHOT');self.assertEqual(client.receive(tls),'BAD_FRAME')
    def test_partial_frame_deadline(self):
        tls,b,p=self.start();tls.sendall(b'PAIR ')
        with self.assertRaises(EOFError):client.receive(tls,timeout=7)
    def test_explicit_close_revokes(self):
        tls,sid,p=self.granted();client.send(tls,sid+' 1 CLOSE');self.assertEqual(client.receive(tls),'CLOSED')
        with self.assertRaises(EOFError):client.receive(tls)
    def test_remote_eof_revokes(self):
        tls,b,p=self.start();client.send(tls,'PAIR '+b['ticket']);client.receive(tls);client.receive(tls)
        tls.close();time.sleep(.2)
        with self.assertRaises(OSError):client.connect(b)


if __name__=='__main__':unittest.main(verbosity=2)
