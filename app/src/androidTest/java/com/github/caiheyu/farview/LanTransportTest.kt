package com.github.caiheyu.farview

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.caiheyu.farview.network.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket

@RunWith(AndroidJUnit4::class)
class LanTransportTest {
    @Test fun boundLanSocketKeepsPinnedTlsAndBidirectionalTraffic() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val local = LocalNetwork(context)
        val hosts = local.advertisedHosts()
        assertTrue("The test needs a local IPv4 interface", hosts.isNotEmpty())
        val path = local.paths(hosts).first()
        assertTrue("The first path must bind to a LAN Network: ${path.label}",
            path.label.startsWith("Wi-Fi /") || path.label.startsWith("有线局域网 /"))
        val tls = SecureTransport()
        val server = tls.server()
        val executor = Executors.newSingleThreadExecutor()
        val response = executor.submit {
            (server.accept() as SSLSocket).use { socket ->
                socket.soTimeout = 5000; socket.startHandshake()
                assertEquals("hello", DataInputStream(socket.inputStream).readUTF())
                DataOutputStream(socket.outputStream).apply { writeUTF("farview-lan-ok"); flush() }
            }
        }
        try {
            val pairing = Pairing(hosts, server.localPort, "a".repeat(64), tls.fingerprint)
            val steps = mutableListOf<ConnectStep>()
            SecureTransport.connect(pairing, path.host, path::openSocket, steps::add).use { socket ->
                DataOutputStream(socket.outputStream).apply { writeUTF("hello"); flush() }
                assertEquals("farview-lan-ok", DataInputStream(socket.inputStream).readUTF())
                assertEquals(listOf(ConnectStep.NETWORK, ConnectStep.TLS), steps)
                assertTrue(hosts.contains(socket.localAddress.hostAddress!!))
            }
            response.get(8, TimeUnit.SECONDS)
        } finally { server.close(); executor.shutdownNow() }
    }

    @Test fun refusedConnectionClosesTheBoundSocketAndReportsTheNetworkStep() {
        val unusedPort = ServerSocket(0).use { it.localPort }
        val raw = Socket()
        val pairing = Pairing(listOf("127.0.0.1"), unusedPort, "a".repeat(64), "b".repeat(64))
        val error = assertThrows(LocalConnectException::class.java) { SecureTransport.connect(pairing, "127.0.0.1", { raw }) }
        assertEquals(ConnectStep.NETWORK, error.step)
        assertTrue(raw.isClosed)
    }

    @Test fun closingThePendingSocketCancelsAnUnresponsiveTlsHandshake() {
        val server = ServerSocket(0)
        val raw = AtomicReference<Socket>()
        val handshake = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val pairing = Pairing(listOf("127.0.0.1"), server.localPort, "a".repeat(64), "b".repeat(64))
        val result = executor.submit<Throwable?> {
            try {
                SecureTransport.connect(pairing, "127.0.0.1", { Socket().also(raw::set) },
                    { if (it == ConnectStep.TLS) handshake.countDown() }).close()
                null
            } catch (e: Exception) { e }
        }
        try {
            assertTrue(handshake.await(3, TimeUnit.SECONDS))
            raw.get().close()
            val error = result.get(3, TimeUnit.SECONDS)
            assertTrue(error is LocalConnectException)
            assertEquals(ConnectStep.TLS, (error as LocalConnectException).step)
        } finally { raw.get()?.close(); server.close(); executor.shutdownNow() }
    }
}
