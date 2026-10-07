package com.railreach.service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * TLS trust for feed downloads: the JDK's root certificates plus the ones in {@code feed-roots.pem}, for feed hosts
 * whose root CA the JDK doesn't ship (MÁV's e-Szigno root). Only the feed downloader uses it.
 */
final class FeedTrust {

    private static final String EXTRA_ROOTS = "/feed-roots.pem";

    private FeedTrust() {}

    static SSLContext sslContext() {
        try {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            List<X509Certificate> roots = trustedRoots();
            for (int i = 0; i < roots.size(); i++) {
                store.setCertificateEntry("root-" + i, roots.get(i));
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, factory.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Cannot set up TLS trust for feed downloads", e);
        }
    }

    static List<X509Certificate> trustedRoots() throws GeneralSecurityException, IOException {
        List<X509Certificate> roots = new ArrayList<>(Arrays.asList(jdkTrustManager().getAcceptedIssuers()));
        try (InputStream in = FeedTrust.class.getResourceAsStream(EXTRA_ROOTS)) {
            if (in == null) throw new IllegalStateException("Missing classpath resource " + EXTRA_ROOTS);
            CertificateFactory.getInstance("X.509").generateCertificates(in)
                    .forEach(certificate -> roots.add((X509Certificate) certificate));
        }
        return roots;
    }

    private static X509TrustManager jdkTrustManager() throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        return Arrays.stream(factory.getTrustManagers())
                .filter(X509TrustManager.class::isInstance)
                .map(X509TrustManager.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("The JDK has no X.509 trust manager"));
    }
}
