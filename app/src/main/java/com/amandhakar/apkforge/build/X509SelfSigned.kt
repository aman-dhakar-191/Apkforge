package com.amandhakar.apkforge.build

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

/**
 * Builds a self-signed X.509 certificate for an RSA key (SHA-256 with RSA). Android has no public API
 * for this, and a library for one line of DER would be a heavy dependency. The result is parsed by the
 * platform's own certificate parser and checked in tests against Android's apksigner.
 */
object X509SelfSigned {
    private val SHA256_RSA_OID = "1.2.840.113549.1.1.11"
    private val COMMON_NAME_OID = "2.5.4.3"

    fun create(commonName: String, keyPair: KeyPair, notBefore: Date, notAfter: Date, serial: BigInteger): X509Certificate {
        require(keyPair.public.algorithm == "RSA") { "Only RSA keys are supported" }
        require(serial.signum() > 0) { "The serial number must be positive" }
        val sigAlg = seq(oid(SHA256_RSA_OID), byteArrayOf(0x05, 0x00)) // AlgorithmIdentifier with NULL parameters
        val name = seq(set(seq(oid(COMMON_NAME_OID), tlv(0x0C, commonName.toByteArray(Charsets.UTF_8)))))
        val tbs = seq(
            tlv(0xA0, integer(BigInteger.valueOf(2))), // version v3
            integer(serial),
            sigAlg,
            name,
            seq(time(notBefore), time(notAfter)),
            name, // self-signed: subject == issuer
            keyPair.public.encoded, // SubjectPublicKeyInfo, already DER
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private); update(tbs); sign()
        }
        val der = seq(tbs, sigAlg, tlv(0x03, byteArrayOf(0) + signature))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    // ---- minimal DER
    private fun len(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    }

    private fun tlv(tag: Int, content: ByteArray) = byteArrayOf(tag.toByte()) + len(content.size) + content
    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    private fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })
    private fun integer(v: BigInteger) = tlv(0x02, v.toByteArray())

    private fun oid(s: String): ByteArray {
        val arcs = s.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((arcs[0] * 40 + arcs[1]).toInt())
        for (a in arcs.drop(2)) {
            val groups = ArrayList<Int>()
            var v = a
            do { groups += (v and 0x7F).toInt(); v = v shr 7 } while (v > 0)
            for (i in groups.indices.reversed()) out.write(groups[i] or if (i > 0) 0x80 else 0)
        }
        return tlv(0x06, out.toByteArray())
    }

    /** UTCTime up to the year 2049, GeneralizedTime from 2050 (RFC 5280). Always ASCII digits, whatever the phone's language. */
    private fun time(d: Date): ByteArray {
        val utc = TimeZone.getTimeZone("UTC")
        val year = java.util.Calendar.getInstance(utc, java.util.Locale.US).apply { time = d }.get(java.util.Calendar.YEAR)
        return if (year < 2050) tlv(0x17, SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US).apply { timeZone = utc }.format(d).toByteArray())
        else tlv(0x18, SimpleDateFormat("yyyyMMddHHmmss'Z'", java.util.Locale.US).apply { timeZone = utc }.format(d).toByteArray())
    }
}
