// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;
import javax.net.ssl.*;
import java.io.*;
import java.security.*;
/** Host fixture only. Android build compiles src/, never tests/host/. */
final class FocusIdentity {
    final SSLContext context;
    FocusIdentity(String path) throws Exception {
        KeyStore store=KeyStore.getInstance("PKCS12");
        try(InputStream in=new FileInputStream(path)){store.load(in,"fixture".toCharArray());}
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(store,"fixture".toCharArray());
        context=SSLContext.getInstance("TLSv1.2");context.init(km.getKeyManagers(),null,new SecureRandom());
    }
}
