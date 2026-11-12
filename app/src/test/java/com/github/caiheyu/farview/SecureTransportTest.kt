package com.github.caiheyu.farview

import com.github.caiheyu.farview.network.LocalConnectException
import com.github.caiheyu.farview.network.Pairing
import com.github.caiheyu.farview.network.SecureTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

/** Both TLS roles run in-process, so the handshake path is testable without any device. */
class SecureTransportTest {
    @Test fun loopbackHandshakeServesTlsWithThePinnedCertificate() {
        val transport = SecureTransport()
        val server = transport.server()
        val executor = Executors.newSingleThreadExecutor()
        val handshake = executor.submit<SSLSocket> {
            (server.accept() as SSLSocket).apply { soTimeout = 5000; startHandshake() }
        }
        try {
            val pairing = Pairing(listOf("127.0.0.1"), server.localPort, "a".repeat(64), transport.fingerprint)
            SecureTransport.connect(pairing, "127.0.0.1").use { client ->
                // TLS 1.3 is where the old keystore-backed identity failed to sign server
                // CertificateVerify at all; pinning must accept the new identity there.
                assertEquals("TLSv1.3", client.session.protocol)
                assertNotNull(client.session.cipherSuite)
            }
            handshake.get(10, TimeUnit.SECONDS).close()
        } finally { server.close(); executor.shutdownNow() }
    }

    @Test fun loopbackHandshakeRejectsAnUnpinnedCertificate() {
        val transport = SecureTransport()
        val server = transport.server()
        val executor = Executors.newSingleThreadExecutor()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val handshake = executor.submit<SSLSocket?> {
            try { (server.accept() as SSLSocket).apply { soTimeout = 5000; startHandshake() } }
            catch (e: Exception) { failure.set(e); null }
        }
        try {
            val wrong = Pairing(listOf("127.0.0.1"), server.localPort, "a".repeat(64), "0".repeat(64))
            assertThrows(LocalConnectException::class.java) { SecureTransport.connect(wrong, "127.0.0.1") }
            handshake.get(10, TimeUnit.SECONDS)
            assertNotNull("The server side must also fail the rejected handshake", failure.get())
        } finally { server.close(); executor.shutdownNow() }
    }
}
