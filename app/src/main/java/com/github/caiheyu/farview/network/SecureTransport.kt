package com.github.caiheyu.farview.network

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

class SecureTransport {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    init {
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY or
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    // Android 8 Conscrypt signs pre-hashed TLS handshakes with NONEwithRSA.
                    .setKeySize(2048).setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1, KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .setCertificateSubject(X500Principal("CN=Farview local camera"))
                    .setCertificateSerialNumber(BigInteger(128, SecureRandom()))
                    .setCertificateNotBefore(Date(0))
                    .setCertificateNotAfter(Date(4_102_444_800_000L)).build())
                generateKeyPair()
            }
        }
    }

    val fingerprint: String get() = MessageDigest.getInstance("SHA-256").digest(store.getCertificate(ALIAS).encoded).toHex()

    fun server(): SSLServerSocket {
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, null) }
        val context = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, SecureRandom()) }
        return (context.serverSocketFactory.createServerSocket(0) as SSLServerSocket).apply {
            enabledProtocols = supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        }
    }

    companion object {
        private const val ALIAS = "farview-local-tls-v2"
        fun connect(pairing: Pairing, host: String): SSLSocket {
            val trust = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = error("不接受客户端证书")
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    require(!chain.isNullOrEmpty()) { "拍摄端证书缺失" }
                    val actual = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).toHex()
                    require(MessageDigest.isEqual(actual.toByteArray(), pairing.fingerprint.toByteArray())) { "配对证书不匹配，请重新扫码" }
                }
            }
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), SecureRandom()) }
            val socket = context.socketFactory.createSocket() as SSLSocket
            try {
                socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
                socket.connect(InetSocketAddress(host, pairing.port), 2500)
                socket.soTimeout = 5000
                socket.startHandshake()
                return socket
            } catch (e: Exception) { socket.close(); throw e }
        }
    }
}
