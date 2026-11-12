package com.github.caiheyu.farview

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.caiheyu.farview.network.Pairing
import com.github.caiheyu.farview.network.SecureTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket

/** Both TLS roles in-process over loopback: exercises the device's own Conscrypt stack. */
@RunWith(AndroidJUnit4::class)
class TlsHandshakeTest {
    @Test fun serverSignsThePinnedHandshakeWithTheDeviceTlsStack() {
        val transport = SecureTransport()
        val server = transport.server()
        val executor = Executors.newSingleThreadExecutor()
        val failure = AtomicReference<Throwable?>()
        val handshake = executor.submit<SSLSocket?> {
            try { (server.accept() as SSLSocket).apply { soTimeout = 5000; startHandshake() } }
            catch (e: Exception) { failure.set(e); null }
        }
        try {
            val pairing = Pairing(listOf("10.0.2.15"), server.localPort, "a".repeat(64), transport.fingerprint)
            SecureTransport.connect(pairing, "127.0.0.1").use { client ->
                assertEquals("TLSv1.3", client.session.protocol)
            }
            assertNotNull("Server handshake failed: ${failure.get()}", handshake.get(10, TimeUnit.SECONDS))
        } finally { server.close(); executor.shutdownNow() }
    }

    @Test fun unpinnedCertificateIsRejectedByTheViewer() {
        val transport = SecureTransport()
        val server = transport.server()
        val executor = Executors.newSingleThreadExecutor()
        val failure = AtomicReference<Throwable?>()
        val handshake = executor.submit<SSLSocket?> {
            try { (server.accept() as SSLSocket).apply { soTimeout = 5000; startHandshake() } }
            catch (e: Exception) { failure.set(e); null }
        }
        try {
            val wrong = Pairing(listOf("10.0.2.15"), server.localPort, "a".repeat(64), "0".repeat(64))
            assertThrows(Exception::class.java) { SecureTransport.connect(wrong, "127.0.0.1") }
            handshake.get(10, TimeUnit.SECONDS)
            assertNotNull("The server must also observe the rejected handshake", failure.get())
        } finally { server.close(); executor.shutdownNow() }
    }
}
