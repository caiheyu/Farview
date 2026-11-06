package com.github.caiheyu.farview

import com.github.caiheyu.farview.network.LanAddress
import org.junit.Assert.*
import org.junit.Test

class LanAddressTest {
    @Test fun subnetMatchingUsesTheActualNetworkPrefix() {
        val hotspot = LanAddress("192.168.43.129", 25, "ap0")
        assertTrue(hotspot.contains("192.168.43.200"))
        assertFalse(hotspot.contains("192.168.43.100"))
        assertFalse(hotspot.contains("192.168.44.200"))
        assertTrue(LanAddress("172.20.1.2", 16, "wlan0").contains("172.20.42.5"))
        assertFalse(LanAddress("172.20.1.2", 24, "wlan0").contains("172.20.42.5"))
    }

    @Test fun invalidAddressesAndPrefixesCannotMatchEveryNetwork() {
        assertFalse(LanAddress("192.168.1.2", 0, "wlan0").contains("10.1.2.3"))
        assertFalse(LanAddress("192.168.1.2", 33, "wlan0").contains("192.168.1.3"))
        assertFalse(LanAddress("192.168.1.2", 24, "wlan0").contains("192.168.1.999"))
        assertFalse(LanAddress("192.168.1.2", 24, "wlan0").contains("camera.local"))
    }

    @Test fun privateCellularAndVpnAddressesAreNeverAdvertised() {
        val addresses = listOf(LanAddress("10.10.0.2", 24, "rmnet_data0"),
            LanAddress("10.20.0.2", 24, "tun0"), LanAddress("10.30.0.2", 24, "vendor_cell"),
            LanAddress("192.168.43.2", 24, "wlan0"), LanAddress("8.8.8.8", 24, "eth0"))
        assertEquals(listOf("192.168.43.2"), LanAddress.usable(addresses, setOf("vendor_cell")).map { it.host })
    }

    @Test fun wifiIsPreferredAndDuplicateAddressesAreRemoved() {
        val addresses = listOf(LanAddress("192.168.43.1", 24, "ap0"),
            LanAddress("192.168.1.2", 24, "wlan0"), LanAddress("192.168.1.2", 24, "wlan1"))
        assertEquals(listOf("192.168.1.2", "192.168.43.1"),
            LanAddress.usable(addresses, preferredInterfaces = setOf("wlan0")).map { it.host })
    }

    @Test fun hotspotInterfaceIsUsableWithoutAConnectivityManagerNetwork() {
        val hotspot = LanAddress("192.168.43.1", 24, "ap0")
        assertEquals(listOf(hotspot), LanAddress.usable(listOf(hotspot)))
    }
}
