package com.github.caiheyu.farview.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey

/**
 * In-process TLS identity: an RSA key pair with a self-signed certificate, DER-encoded here
 * because Android offers no public API that mints certificates for non-keystore keys.
 *
 * The key deliberately does not live in AndroidKeyStore. Keystore keys cannot be exported, so
 * Conscrypt would sign handshakes through its CryptoUpcalls Cipher path, which implements only
 * PKCS1 / none / OAEP padding. A TLS 1.3 server must sign CertificateVerify with RSA-PSS, so
 * every handshake on Android 10+ fails with "RSA routines: internal error" (conscrypt#718).
 * A process-owned key signs natively on every device, and never touching the keystore or disk
 * keeps the identity exactly as long as the pairing session it serves.
 */
class SelfSignedIdentity(val keyPair: KeyPair, val certificate: X509Certificate) {
    companion object {
        fun generate(): SelfSignedIdentity {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(encodeCertificate(keyPair))) as X509Certificate
            return SelfSignedIdentity(keyPair, certificate)
        }

        private fun encodeCertificate(keyPair: KeyPair): ByteArray {
            val public = keyPair.public as RSAPublicKey
            val tbs = Der.sequence(
                Der.tagged(0xA0, Der.integer(2)),  // explicit [0] version tag: X.509 v3
                Der.integer(BigInteger(120, SecureRandom())),
                SHA256_WITH_RSA,
                NAME,
                Der.sequence(Der.utcTime("000101000000Z"), Der.generalizedTime("99991231235959Z")),
                NAME,
                Der.sequence(RSA_ENCRYPTION, Der.bitString(Der.sequence(
                    Der.integer(public.modulus), Der.integer(public.publicExponent)))))
            val signature = Signature.getInstance("SHA256withRSA").apply { initSign(keyPair.private); update(tbs) }.sign()
            return Der.sequence(tbs, SHA256_WITH_RSA, Der.bitString(signature))
        }

        private val NAME = Der.sequence(Der.set(Der.sequence(Der.oid(2, 5, 4, 3), Der.printableString("Farview local camera"))))
        private val SHA256_WITH_RSA = Der.sequence(Der.oid(1, 2, 840, 113549, 1, 1, 11), Der.nullValue())
        private val RSA_ENCRYPTION = Der.sequence(Der.oid(1, 2, 840, 113549, 1, 1, 1), Der.nullValue())
    }
}

/** Just enough DER to emit one certificate; every element is covered by SelfSignedIdentityTest. */
private object Der {
    fun tagged(tag: Int, content: ByteArray): ByteArray {
        val size = content.size
        val header = when {
            size < 0x80 -> byteArrayOf(tag.toByte(), size.toByte())
            size < 0x100 -> byteArrayOf(tag.toByte(), 0x81.toByte(), size.toByte())
            size < 0x10000 -> byteArrayOf(tag.toByte(), 0x82.toByte(), (size shr 8).toByte(), size.toByte())
            else -> throw IllegalArgumentException("DER element too long: $size bytes")
        }
        return header + content
    }
    fun sequence(vararg parts: ByteArray) = tagged(0x30, join(parts))
    fun set(vararg parts: ByteArray) = tagged(0x31, join(parts))
    fun integer(value: BigInteger) = tagged(0x02, value.toByteArray())
    fun integer(value: Int) = integer(BigInteger.valueOf(value.toLong()))
    fun bitString(bytes: ByteArray) = tagged(0x03, byteArrayOf(0) + bytes)
    fun printableString(text: String) = tagged(0x13, text.toByteArray(Charsets.US_ASCII))
    fun utcTime(text: String) = tagged(0x17, text.toByteArray(Charsets.US_ASCII))
    fun generalizedTime(text: String) = tagged(0x18, text.toByteArray(Charsets.US_ASCII))
    fun nullValue() = tagged(0x05, ByteArray(0))
    fun oid(vararg arcs: Int): ByteArray {
        require(arcs.size >= 2 && arcs[0] in 0..2 && arcs[1] >= 0 && arcs[0] * 40 + arcs[1] < 128)
        val content = mutableListOf(arcs[0] * 40 + arcs[1])
        for (arc in arcs.sliceArray(2 until arcs.size)) {
            // Base-128 big-endian with the continuation bit on all but the final octet.
            val octets = mutableListOf(arc and 0x7F)
            var value = arc ushr 7
            while (value != 0) { octets.add(0, (value and 0x7F) or 0x80); value = value ushr 7 }
            content.addAll(octets)
        }
        return tagged(0x06, content.map { it.toByte() }.toByteArray())
    }
    private fun join(parts: Array<out ByteArray>): ByteArray {
        val out = ByteArrayOutputStream(parts.sumOf { it.size })
        parts.forEach(out::writeBytes)
        return out.toByteArray()
    }
}
