package com.github.caiheyu.farview.network

import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

data class Pairing(val hosts: List<String>, val port: Int, val token: String, val fingerprint: String) {
    fun encode(): String = JSONObject().put("app", "farview").put("v", 1)
        .put("hosts", JSONArray(hosts)).put("port", port).put("token", token)
        .put("certificate", fingerprint).toString()

    companion object {
        fun parse(text: String): Pairing {
            require(text.length <= 4096) { "二维码内容过长" }
            val json = JSONObject(text)
            require(json.optString("app") == "farview" && json.optInt("v") == 1) { "这不是受支持的遥映配对码" }
            val addresses = json.getJSONArray("hosts")
            require(addresses.length() in 1..8) { "配对地址无效" }
            val hosts = (0 until addresses.length()).map { addresses.getString(it) }
            require(hosts.all(::isLocalIpv4)) { "配对码必须使用热点或局域网地址" }
            val port = json.getInt("port")
            val token = json.getString("token")
            val certificate = json.getString("certificate")
            require(port in 1024..65535 && token.matches(Regex("[0-9a-f]{64}")) &&
                certificate.matches(Regex("[0-9a-f]{64}"))) { "配对凭据无效，请重新扫码" }
            return Pairing(hosts.distinct(), port, token, certificate)
        }

        fun isLocalIpv4(host: String): Boolean {
            val parts = host.split('.')
            if (parts.size != 4 || parts.any { !it.matches(Regex("0|[1-9][0-9]{0,2}")) }) return false
            val p = parts.map { it.toInt() }
            if (p.any { it !in 0..255 } || p[3] == 0 || p[3] == 255) return false
            return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
        }

        fun localAddresses(): List<String> = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
            .filter(::isLocalIpv4).distinct().take(8)

        fun newToken(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.toHex()
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
