package com.github.caiheyu.farview

import com.github.caiheyu.farview.network.SelfSignedIdentity
import org.junit.Assert.*
import org.junit.Test
import java.security.Signature
import java.security.cert.CertificateFactory

class SelfSignedIdentityTest {
    @Test fun certificateParsesAsASelfSignedSha256RsaCertificateForItsOwnKey() {
        val identity = SelfSignedIdentity.generate()
        val certificate = identity.certificate
        assertEquals("1.2.840.113549.1.1.11", certificate.sigAlgOID)  // sha256WithRSAEncryption
        assertEquals("RSA", certificate.publicKey.algorithm)
        assertEquals(identity.keyPair.public, certificate.publicKey)
        assertEquals("CN=Farview local camera", certificate.subjectX500Principal.name)
        assertEquals(certificate.subjectX500Principal, certificate.issuerX500Principal)
        certificate.verify(identity.keyPair.public)  // the self-signature must actually verify
    }

    @Test fun certificateBytesRoundTripSoViewersCanPinTheExactDer() {
        val identity = SelfSignedIdentity.generate()
        val reparsed = CertificateFactory.getInstance("X.509")
            .generateCertificate(identity.certificate.encoded.inputStream())
        assertEquals(identity.certificate, reparsed)
    }

    @Test fun embeddedSignatureIsExactlyJcaOverTheParsedTbs() {
        // Guards the hand-written DER: the signature stored in the certificate must be the
        // plain JCA signature over the TBSCertificate as any standard parser re-encodes it.
        val identity = SelfSignedIdentity.generate()
        val expected = Signature.getInstance("SHA256withRSA").apply {
            initSign(identity.keyPair.private); update(identity.certificate.tbsCertificate)
        }.sign()
        assertArrayEquals(expected, identity.certificate.signature)
    }

    @Test fun everySessionMintsAnIndependentIdentity() {
        assertNotEquals(SelfSignedIdentity.generate().certificate, SelfSignedIdentity.generate().certificate)
    }
}
