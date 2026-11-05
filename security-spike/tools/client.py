# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Windows stdlib client. Trust bundle must come from TV via an authenticated OOB channel.
No CERT_NONE, system-root fallback, first-network-certificate adoption, or persistent token.
"""
import argparse
import hashlib
import hmac
import ipaddress
import json
from pathlib import Path
import re
import socket
import ssl
import time


def connect(bundle):
    address = ipaddress.ip_address(bundle['host'])
    if address.version != 4 or not any(address in ipaddress.IPv4Network(n) for n in ((0x0A000000,8), (0xAC100000,12), (0xC0A80000,16))):
        raise ValueError('RFC1918 IPv4 required for this spike')
    if not isinstance(bundle['port'], int) or not 1 <= bundle['port'] <= 65535:
        raise ValueError('Invalid port')
    pin = bundle['pin_sha256']
    if not re.fullmatch('[a-f0-9]{64}', pin): raise ValueError('Full SHA256 pin required')
    if not re.fullmatch(r'marco-[a-f0-9-]{36}', bundle['device_id']): raise ValueError('Invalid identity')
    der = ssl.PEM_cert_to_DER_cert(bundle['pem'])
    if not hmac.compare_digest(hashlib.sha256(der).hexdigest(), pin):
        raise ssl.SSLCertVerificationError('Provisioned certificate/pin mismatch')
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    # Identity is the pinned self-signed device cert + CN, not its mutable LAN IP.
    context.check_hostname = False
    context.verify_mode = ssl.CERT_REQUIRED
    context.minimum_version = context.maximum_version = ssl.TLSVersion.TLSv1_2
    context.set_ciphers('ECDHE-ECDSA-AES128-GCM-SHA256')
    context.load_verify_locations(cadata=bundle['pem'])  # sole trust anchor
    # OpenSSL CHECK_SS_SIGNATURE also verifies the trusted self-signed cert signature.
    context.verify_flags = ssl.VERIFY_X509_TRUSTED_FIRST | 0x4000  # OpenSSL X509_V_FLAG_CHECK_SS_SIGNATURE
    raw = socket.create_connection((str(address), bundle['port']), timeout=5)
    try:
        tls = context.wrap_socket(raw, server_hostname=bundle['device_id'])
        try:
            if not hmac.compare_digest(hashlib.sha256(tls.getpeercert(binary_form=True)).hexdigest(), pin):
                raise ssl.SSLCertVerificationError('Peer pin mismatch')
            names = [v for rdn in tls.getpeercert()['subject'] for k, v in rdn if k == 'commonName']
            if names != [bundle['device_id']]: raise ssl.SSLCertVerificationError('Device identity mismatch')
            # OpenSSL already checked certificate validity and signature in handshake.
            return tls
        except BaseException:
            tls.close(); raise
    except BaseException:
        raw.close(); raise


def receive(tls, timeout=35):
    end = time.monotonic() + timeout
    data = bytearray()
    while True:
        left = end - time.monotonic()
        if left <= 0: raise TimeoutError('Frame deadline')
        tls.settimeout(left)
        value = tls.recv(1)
        if not value: raise EOFError('Session closed')
        if value == b'\n': return data.decode('ascii')
        if len(data) >= 4096 or not 32 <= value[0] <= 126: raise ValueError('Bad frame')
        data.extend(value)


def send(tls, frame):
    encoded = frame.encode('ascii')
    if len(encoded) > 4096 or any(not 32 <= x <= 126 for x in encoded): raise ValueError('Bad frame')
    tls.sendall(encoded + b'\n')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--bundle', required=True, type=Path)
    p.add_argument('--diagnostic', action='store_true')
    p.add_argument('--hold', type=int, default=0, choices=range(0,361), metavar='SECONDS', help='Keep alive for local-TV revocation test')
    p.add_argument('--negative', choices=['replay', 'stale', 'disconnect', 'idle'])
    args = p.parse_args()
    bundle = json.loads(args.bundle.read_text(encoding='utf-8-sig'))
    with connect(bundle) as tls:
        print(json.dumps({'event': 'certificate_verified', 'tls': tls.version(), 'cipher': tls.cipher()[0]}), flush=True)
        if args.diagnostic: return
        ticket = bundle.get('ticket', '')
        if not re.fullmatch('[a-f0-9]{64}', ticket): raise ValueError('Invalid ticket')
        # First application byte is sent ONLY after connect() completes all verification.
        send(tls, 'PAIR ' + ticket)
        first = receive(tls)
        print(first, flush=True)
        if first != 'PENDING': return
        granted = receive(tls)
        if not re.fullmatch('GRANTED [a-f0-9]{64}', granted):
            print(granted); return
        sid = granted.split()[1]
        print('GRANTED (credential not logged)', flush=True)
        send(tls, sid + ' 1 SNAPSHOT'); snapshot = receive(tls)
        if not re.fullmatch('SNAPSHOT [a-f0-9]{64} [0-9]+', snapshot): raise ValueError('Bad snapshot')
        nonce = snapshot.split()[1]
        if args.negative == 'disconnect': return
        if args.negative == 'stale': time.sleep(1.1)
        if args.negative == 'idle': time.sleep(16)
        seq = 1 if args.negative == 'replay' else 2
        send(tls, f'{sid} {seq} STEP {nonce} RIGHT'); print(receive(tls), flush=True)
        if args.negative in ('replay','idle'): return
        seq = 3
        end = time.monotonic() + args.hold
        while time.monotonic() < end:
            time.sleep(max(0, min(5, end-time.monotonic())))
            send(tls, f'{sid} {seq} PING'); print(receive(tls), flush=True); seq += 1
        send(tls, f'{sid} {seq} CLOSE'); print(receive(tls), flush=True)


if __name__ == '__main__': main()
