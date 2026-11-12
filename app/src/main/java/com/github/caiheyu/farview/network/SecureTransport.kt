package com.github.caiheyu.farview.network

import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

class SecureTransport {
    private val identity = SelfSignedIdentity.generate()

    val fingerprint: String get() = MessageDigest.getInstance("SHA-256").digest(identity.certificate.encoded).toHex()

    fun server(): SSLServerSocket {
        // One key, one certificate: alias selection has no choices to make, and native TLS
        // already refuses cipher suites this identity cannot serve.
        val context = SSLContext.getInstance("TLS").apply {
            init(arrayOf<KeyManager>(object : X509KeyManager {
                override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
                override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
                override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
                override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
                override fun getCertificateChain(alias: String?) = arrayOf(identity.certificate)
                override fun getPrivateKey(alias: String?): PrivateKey = identity.keyPair.private
            }), null, SecureRandom())
        }
        return (context.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
            enabledProtocols = supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
            bind(InetSocketAddress("0.0.0.0", 0))
        }
    }

    companion object {
        private const val ALIAS = "farview-local-tls"
        fun connect(pairing: Pairing, host: String, openSocket: () -> Socket = { Socket() },
            onStep: (ConnectStep) -> Unit = {}): SSLSocket {
            val trust = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = error("不接受客户端证书")
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    if (chain.isNullOrEmpty()) throw CertificateException("拍摄端证书缺失")
                    val actual = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).toHex()
                    if (!MessageDigest.isEqual(actual.toByteArray(), pairing.fingerprint.toByteArray()))
                        throw CertificateException("配对证书不匹配，请重新扫码")
                }
            }
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), SecureRandom()) }
            var raw: Socket? = null
            var socket: SSLSocket? = null
            var step = ConnectStep.NETWORK
            try {
                onStep(step)
                raw = openSocket()
                raw.connect(InetSocketAddress(host, pairing.port), 2500)
                step = ConnectStep.TLS; onStep(step)
                socket = context.socketFactory.createSocket(raw, host, pairing.port, true) as SSLSocket
                socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
                socket.soTimeout = 5000
                socket.startHandshake()
                return socket
            } catch (e: Exception) {
                runCatching { socket?.close() }; runCatching { raw?.close() }
                if (e is java.util.concurrent.CancellationException) throw e
                throw LocalConnectException(step, e)
            }
        }
    }
}
