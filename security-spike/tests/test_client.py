# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Real loopback TLS, synthetic certificates. NEVER a physical-TV pairing result.
Requires the already reviewed G0 cryptography==50.0.2 developer dependency.
"""
import datetime
import hashlib
import importlib.util
import json
import ipaddress
from pathlib import Path
import socket
import ssl
import tempfile
import threading
import unittest
from unittest.mock import patch
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('client', ROOT/'tools/client.py')
client = importlib.util.module_from_spec(spec); spec.loader.exec_module(client)
DEVICE = 'marco-12345678-1234-1234-1234-123456789abc'


def certificate(expired=False, future=False, bad_signature=False):
    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, DEVICE)])
    now = datetime.datetime.now(datetime.timezone.utc)
    start = now + datetime.timedelta(days=1) if future else now-datetime.timedelta(days=2)
    end = now-datetime.timedelta(days=1) if expired else now+datetime.timedelta(days=2)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(start).not_valid_after(end)
            .sign(key, hashes.SHA256()))
    if bad_signature:
        der = bytearray(cert.public_bytes(serialization.Encoding.DER)); der[-1] ^= 1
        cert = x509.load_der_x509_certificate(bytes(der))
    return cert.public_bytes(serialization.Encoding.PEM).decode(), key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())


def bundle(pem, port):
    return {'host':str(ipaddress.IPv4Address(bytes((10,0,0,2)))),'port':port,'pem':pem,'device_id':DEVICE,
            'pin_sha256':hashlib.sha256(ssl.PEM_cert_to_DER_cert(pem)).hexdigest(), 'ticket':'f'*64}


class ClientTests(unittest.TestCase):
    def exchange(self, pem, key, mutate=lambda b: None, success=False):
        with tempfile.TemporaryDirectory() as tmp:
            certfile, keyfile = Path(tmp)/'cert.pem', Path(tmp)/'key.pem'
            certfile.write_text(pem); keyfile.write_bytes(key)
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); ctx.load_cert_chain(certfile, keyfile)
            ctx.minimum_version = ctx.maximum_version = ssl.TLSVersion.TLSv1_2
            ctx.set_ciphers('ECDHE-ECDSA-AES128-GCM-SHA256')
            received = []
            with socket.socket() as listener:
                listener.bind(('127.0.0.1',0)); listener.listen(1); listener.settimeout(2)
                port = listener.getsockname()[1]
                def server():
                    try:
                        with listener.accept()[0] as raw:
                            raw.settimeout(2)
                            with ctx.wrap_socket(raw,server_side=True) as tls:
                                received.append(tls.recv(512))
                    except (ssl.SSLError, OSError): pass
                worker=threading.Thread(target=server); worker.start()
                values=bundle(pem,port); mutate(values)
                real_connect=socket.create_connection
                with patch.object(client.socket,'create_connection',side_effect=lambda *a,**k: real_connect(('127.0.0.1',port),timeout=2)):
                    if success:
                        with client.connect(values) as tls: client.send(tls,'PAIR '+values['ticket'])
                    else:
                        with self.assertRaises(ssl.SSLCertVerificationError):
                            with client.connect(values) as tls: client.send(tls,'PAIR '+values['ticket'])
                worker.join(3); self.assertFalse(worker.is_alive())
                self.assertEqual(b''.join(received), b'PAIR '+b'f'*64+b'\n' if success else b'')

    def test_verified_before_ticket(self): self.exchange(*certificate(),success=True)
    def test_wrong_pin_no_connection(self):
        pem,_=certificate(); b=bundle(pem,1234); b['pin_sha256']='0'*64
        with patch.object(client.socket,'create_connection') as connect:
            with self.assertRaises(ssl.SSLCertVerificationError): client.connect(b)
            connect.assert_not_called()
    def test_unknown_server_cert_no_ticket(self):
        other,_=certificate()
        self.exchange(*certificate(),mutate=lambda b:b.update(pem=other,pin_sha256=hashlib.sha256(ssl.PEM_cert_to_DER_cert(other)).hexdigest()))
    def test_expired_cert_no_ticket(self): self.exchange(*certificate(expired=True))
    def test_future_cert_no_ticket(self): self.exchange(*certificate(future=True))
    def test_invalid_self_signature_no_ticket(self): self.exchange(*certificate(bad_signature=True))
    def test_wrong_identity_no_ticket(self): self.exchange(*certificate(),mutate=lambda b:b.update(device_id='marco-00000000-0000-0000-0000-000000000000'))
    def test_invalid_frame(self):
        with self.assertRaises(ValueError): client.send(None,'PAIR secret\nSTEP')
        with self.assertRaises(ValueError): client.send(None,'x'*4097)
    def test_receive_eof(self):
        a,b=socket.socketpair(); b.close()
        with a, self.assertRaises(EOFError): client.receive(a)
    def test_receive_oversize(self):
        a,b=socket.socketpair()
        with a,b:
            b.sendall(b'x'*4097+b'\n')
            with self.assertRaises(ValueError): client.receive(a)
    def test_frame_deadline(self):
        a,b=socket.socketpair()
        with a,b,self.assertRaises(TimeoutError): client.receive(a,timeout=.05)


if __name__=='__main__': unittest.main(verbosity=2)
