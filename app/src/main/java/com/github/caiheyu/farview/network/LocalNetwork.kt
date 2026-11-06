package com.github.caiheyu.farview.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/** Select the LAN even when Android keeps mobile data as its default network. */
class LocalNetwork(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private data class Available(val network: Network, val name: String, val addresses: List<LanAddress>,
        val lan: Boolean, val excluded: Boolean, val label: String)

    class Path(val host: String, val label: String, private val network: Network? = null,
        private val sourceAddress: String? = null) {
        fun openSocket(): Socket = Socket().also { socket ->
            try {
                if (network != null) network.bindSocket(socket)
                else if (sourceAddress != null) socket.bind(InetSocketAddress(sourceAddress, 0))
            } catch (e: Exception) { socket.close(); throw e }
        }
    }

    private fun available(): List<Available> = connectivity.allNetworks.mapNotNull { network ->
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
        val properties = connectivity.getLinkProperties(network) ?: return@mapNotNull null
        val name = properties.interfaceName ?: return@mapNotNull null
        val excluded = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val ethernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        Available(network, name, properties.linkAddresses.mapNotNull address@{
            val address = it.address as? Inet4Address ?: return@address null
            LanAddress(address.hostAddress ?: return@address null, it.prefixLength, name)
        }, !excluded && (wifi || ethernet), excluded,
            if (wifi) "Wi-Fi" else if (ethernet) "有线局域网" else if (excluded) "移动网络或 VPN" else "其他网络")
    }

    private fun addresses(networks: List<Available>): List<LanAddress> {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
            .flatMap { networkInterface -> networkInterface.interfaceAddresses.mapNotNull { address ->
                val ipv4 = address.address as? Inet4Address ?: return@mapNotNull null
                LanAddress(ipv4.hostAddress ?: return@mapNotNull null, address.networkPrefixLength.toInt(), networkInterface.name)
            } }
        return LanAddress.usable(interfaces, networks.filter { it.excluded }.map { it.name }.toSet(),
            networks.filter { it.lan }.map { it.name }.toSet())
    }

    fun advertisedHosts(): List<String> = addresses(available()).map { it.host }.take(8)

    fun paths(hosts: List<String>): List<Path> {
        val snapshot = available()
        val networks = snapshot.filter { it.lan }
        val local = addresses(snapshot)
        val matching = hosts.flatMap { host -> networks.filter { network -> network.addresses.any { it.contains(host) } }
            .map { Path(host, "${it.label} / ${it.name}", network = it.network) } }
        // Hotspot providers often have no ConnectivityManager Network for their AP.
        // Bind their local interface address while keeping Wi-Fi clients explicitly on Wi-Fi.
        val direct = hosts.flatMap { host -> local.filter { it.contains(host) }
            .map { Path(host, "热点直连 / ${it.interfaceName}", sourceAddress = it.host) } }
        val otherLan = hosts.flatMap { host -> networks.filterNot { network -> network.addresses.any { it.contains(host) } }
            .map { Path(host, "${it.label} / ${it.name}", network = it.network) } }
        return matching + direct + otherLan + hosts.map { Path(it, "系统直连") }
    }

    fun summary(): String = runCatching {
        val networks = available()
        val local = addresses(networks).joinToString { "${it.interfaceName}=${it.host}/${it.prefixLength}" }
        val default = connectivity.activeNetwork?.let { active -> networks.find { it.network == active }?.label } ?: "无"
        "默认网络：$default\n本地接口：${local.ifBlank { "未找到" }}\n可用局域网：${networks.count { it.lan }}"
    }.getOrElse { "无法读取网络信息：${it.javaClass.simpleName}" }
}
