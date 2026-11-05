// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.remoteprobe;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyInfo;
import java.math.BigInteger;
import java.net.Socket;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Date;
import java.util.UUID;
import javax.net.ssl.*;
import javax.security.auth.x500.X500Principal;

final class DeviceIdentity {
    private static final String ALIAS = "marco-g1b1-ec-tls-1";
    final X509Certificate cert;
    final String deviceId, pin, pem;
    final boolean hardwareBacked;
    final SSLContext context;

    DeviceIdentity() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            long wall = System.currentTimeMillis();
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    // Android 9 Conscrypt uses NONEwithECDSA for its already-hashed TLS transcript.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setCertificateSubject(new X500Principal("CN=marco-" + UUID.randomUUID()))
                    .setCertificateSerialNumber(new BigInteger(120, new SecureRandom()).add(BigInteger.ONE))
                    .setCertificateNotBefore(new Date(wall - 60_000))
                    .setCertificateNotAfter(new Date(wall + 365L*24*3600*1000)).build());
            generator.generateKeyPair();
        }
        final PrivateKey key = (PrivateKey) store.getKey(ALIAS, null);
        cert = (X509Certificate) store.getCertificate(ALIAS);
        cert.checkValidity(); cert.verify(cert.getPublicKey());
        if (key.getEncoded() != null) throw new GeneralSecurityException("KEY_EXPORTABLE");
        hardwareBacked = KeyFactory.getInstance("EC", "AndroidKeyStore").getKeySpec(key, KeyInfo.class).isInsideSecureHardware();
        deviceId = cert.getSubjectX500Principal().getName().substring(3);
        pin = SessionPolicy.hex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        pem = "-----BEGIN CERTIFICATE-----\n" + android.util.Base64.encodeToString(cert.getEncoded(), android.util.Base64.NO_WRAP)
                + "\n-----END CERTIFICATE-----\n";
        // JSSE owns TLS. This adapter selects exactly one non-exportable AndroidKeyStore key.
        X509ExtendedKeyManager manager = new X509ExtendedKeyManager() {
            public String[] getClientAliases(String t, Principal[] p) { return null; }
            public String chooseClientAlias(String[] t, Principal[] p, Socket s) { return null; }
            public String[] getServerAliases(String t, Principal[] p) { return "EC".equals(t) ? new String[]{ALIAS} : null; }
            public String chooseServerAlias(String t, Principal[] p, Socket s) { return "EC".equals(t) ? ALIAS : null; }
            public X509Certificate[] getCertificateChain(String a) { return ALIAS.equals(a) ? new X509Certificate[]{cert} : null; }
            public PrivateKey getPrivateKey(String a) { return ALIAS.equals(a) ? key : null; }
        };
        context = SSLContext.getInstance("TLSv1.2");
        context.init(new KeyManager[]{manager}, null, new SecureRandom());
    }
}
