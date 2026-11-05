package com.github.caiheyu.farview

import com.github.caiheyu.farview.network.Pairing
import org.junit.Assert.*
import org.junit.Test

class PairingTest {
    private val valid = Pairing(listOf("192.168.43.1", "10.0.0.5"), 23819, "a".repeat(64), "b".repeat(64))
    @Test fun pairingSurvivesQrRoundTrip() { assertEquals(valid, Pairing.parse(valid.encode())) }
    @Test fun internetAndLoopbackAddressesAreRejected() {
        listOf("8.8.8.8", "127.0.0.1", "localhost", "192.168.0.256", "192.168.1.0", "10.0.0.255", "192.168.01.2", "169.254.1.1").forEach {
            assertFalse("Unexpected address accepted: $it", Pairing.isLocalIpv4(it))
        }
    }
    @Test fun allPrivateHotspotRangesAreAccepted() {
        listOf("192.168.43.1", "10.42.0.1", "172.16.0.1", "172.31.255.254").forEach { assertTrue(Pairing.isLocalIpv4(it)) }
    }
    @Test fun unrelatedAndFutureQrCodesAreRejected() {
        listOf("https://example.com", valid.encode().replace("farview", "other"), valid.encode().replace("\"v\":1", "\"v\":2")).forEach {
            assertThrows(Exception::class.java) { Pairing.parse(it) }
        }
    }
    @Test fun credentialsAndPortsMustBeValid() {
        listOf(valid.copy(port = 80), valid.copy(port = 65536), valid.copy(token = "short"), valid.copy(fingerprint = "g".repeat(64)), valid.copy(hosts = emptyList())).forEach {
            assertThrows(Exception::class.java) { Pairing.parse(it.encode()) }
        }
    }
    @Test fun refreshedSessionHasIndependentCredentials() {
        val first = Pairing.newToken(); val second = Pairing.newToken()
        assertEquals(64, first.length); assertNotEquals(first, second)
    }
}
