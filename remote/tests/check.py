# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Host security and CLI checks. Requires reviewed G0 cryptography and MARCO_TEST_BIND."""
import ipaddress
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'remote'))
sys.path.insert(0, str(ROOT / 'security-spike/tests'))
import marco_remote as cli
import tls_link
import test_client
from cryptography import x509
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.serialization import pkcs12

# Run the existing certificate-negative suite against the shipping CLI TLS module.
test_client.client = tls_link
ClientTests = test_client.ClientTests
JDK = Path(json.loads((ROOT / '.tools/toolchain.json').read_text())['jdk']) / 'bin'
OUT = ROOT / 'build/focus-host'
SOURCE = ROOT / 'app/src/main/java/com/pilipala/tvhome'


class WireTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        OUT.mkdir(exist_ok=True, parents=True)
        sources = [SOURCE / (name + '.java') for name in ('MarcoFocusController','FocusSession','FocusTransport')]
        sources += list(Path(__file__).parent.glob('*.java'))
        sources.append(ROOT / 'security-spike/tests/host/android/os/SystemClock.java')
        subprocess.run([JDK/'javac','--release','8','-encoding','UTF-8','-d',OUT,*sources],check=True)
        subprocess.run([JDK/'java','-cp',OUT,'com.pilipala.tvhome.FocusSessionChecks'],check=True)
        cls.host = os.environ.get('MARCO_TEST_BIND')
        if not cls.host:
            private = [ipaddress.IPv4Network(spec) for spec in ((0x0A000000,8),(0xAC100000,12),(0xC0A80000,16))]
            local = socket.getaddrinfo(socket.gethostname(),None,socket.AF_INET)
            cls.host = next((info[4][0] for info in local
                             if any(ipaddress.IPv4Address(info[4][0]) in net for net in private)),None)
        if not cls.host:
            raise RuntimeError('Set MARCO_TEST_BIND to a local RFC1918 IPv4 address')

    def fixture(self, mode='approve'):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        cert, key = test_client.certificate()
        path = Path(tmp.name) / 'host.p12'
        path.write_bytes(pkcs12.serialize_key_and_certificates(b'host',serialization.load_pem_private_key(key,None),
                         x509.load_pem_x509_certificate(cert.encode()),None,serialization.BestAvailableEncryption(b'fixture')))
        proc = subprocess.Popen([JDK/'java','-cp',OUT,'com.pilipala.tvhome.FocusTransportFixture',path,self.host,mode],
                                text=True,stdin=subprocess.PIPE,stdout=subprocess.PIPE)
        def stop():
            try: proc.communicate('\n',timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill(); proc.communicate()
        self.addCleanup(stop)
        port,ticket = proc.stdout.readline().split()
        data = test_client.bundle(cert,int(port))
        data.update(host=self.host,ticket=ticket,version=2)
        session = cli.pair(json.dumps(data))
        self.addCleanup(session.sock.close)
        return session, proc

    def test_directions_and_close(self):
        s,_ = self.fixture()
        self.assertTrue(s.run('STATE').endswith(' 0 0'))
        for direction,position in [('RIGHT','0 1'),('DOWN','1 0'),('UP','0 1'),('LEFT','0 0')]:
            answer = s.run(direction)
            self.assertTrue(answer.startswith('MOVED ') and answer.endswith(position),answer)
        self.assertEqual(s.run('CLOSE'),'CLOSED')

    def test_denied(self):
        with self.assertRaises((OSError,EOFError,ValueError)): self.fixture('deny')

    def test_replay(self):
        s,_ = self.fixture()
        s.run('RIGHT')
        tls_link.send(s.sock,f'{s.sid} {s.seq} PING')
        self.assertEqual(tls_link.receive(s.sock),'REPLAY')
        with self.assertRaises((OSError,EOFError)): tls_link.receive(s.sock)

    def test_queue_does_not_renew_ttl(self):
        s,_ = self.fixture('queue')
        self.assertEqual(s.run('RIGHT'),'COMMAND_EXPIRED')
        self.assertTrue(s.run('STATE').endswith(' 0 0'))

    def test_disconnect_cancels_queued_step(self):
        s,proc = self.fixture('queue')
        nonce,_ = s.state()
        tls_link.send(s.sock,f'{s.sid} 2 STEP {nonce} RIGHT')
        s.sock.shutdown(socket.SHUT_RDWR)
        s.sock.close()
        # The real reader observes EOF while the model UI thread is delayed.
        import concurrent.futures
        with concurrent.futures.ThreadPoolExecutor(1) as worker:
            result = worker.submit(proc.stdout.readline)
            try: self.assertEqual(result.result(timeout=4).strip(),'STEP UNAUTHORIZED 0')
            finally:
                if not result.done(): proc.kill()


if __name__ == '__main__':
    unittest.main(verbosity=2)
