package com.railreach.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FeedTrustTest {

    @Test
    void feedDownloadsTrustTheJdkRootsAndMavsRoot() throws GeneralSecurityException, IOException {
        List<X509Certificate> roots = FeedTrust.trustedRoots();

        assertThat(roots).extracting(root -> root.getSubjectX500Principal().getName())
                .anyMatch(name -> name.contains("CN=Microsec e-Szigno Root CA 2009"))
                .anyMatch(name -> name.contains("CN=ISRG Root X1"));
    }
}
