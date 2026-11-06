package com.github.caiheyu.farview.network

data class LanAddress(val host: String, val prefixLength: Int, val interfaceName: String) {
    fun contains(peer: String): Boolean {
        if (prefixLength !in 1..32) return false
        fun number(address: String): Long? {
            val parts = address.split('.')
            if (parts.size != 4) return null
            return parts.fold(0L) { value, part ->
                val octet = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
                (value shl 8) or octet.toLong()
            }
        }
        val local = number(host) ?: return false
        val remote = number(peer) ?: return false
        val mask = (0xffffffffL shl (32 - prefixLength)) and 0xffffffffL
        return (local and mask) == (remote and mask)
    }

    companion object {
        fun usable(addresses: List<LanAddress>, excludedInterfaces: Set<String> = emptySet(),
            preferredInterfaces: Set<String> = emptySet()): List<LanAddress> = addresses
            .filter { address ->
                val name = address.interfaceName.lowercase()
                address.interfaceName !in excludedInterfaces &&
                    !name.startsWith("tun") && !name.startsWith("tap") && !name.startsWith("ppp") &&
                    !name.startsWith("wg") && !name.contains("rmnet") && !name.startsWith("ccmni") &&
                    Pairing.isLocalIpv4(address.host)
            }
            .sortedBy { if (it.interfaceName in preferredInterfaces) 0 else 1 }
            .distinctBy { it.host }
    }
}
