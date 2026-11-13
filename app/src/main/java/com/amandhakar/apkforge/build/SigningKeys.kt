package com.amandhakar.apkforge.build

import com.amandhakar.apkforge.core.Hex
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date

class SigningIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {
    /** SHA-256 fingerprint of the certificate, as shown to the user. */
    val fingerprint: String get() = Hex.sha256(certificate.encoded)
}

class SigningKeyException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The key Apkforge signs rebuilt APKs with. It lives in app-private storage only (never shared storage),
 * is created on demand, and is never logged. A key that exists but cannot be read is an error: it is
 * never silently replaced, because a new key would silently stop updates of apps signed with the old one.
 */
class SigningKeys(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val keyBits: Int = 2048,
) {
    private val store = File(dir, "forge-key.p12")
    private val pass = File(dir, "forge-key.pass")

    fun exists(): Boolean = store.isFile && pass.isFile

    /** Loads the key, creating it first if there is none. */
    @Synchronized
    fun loadOrCreate(): SigningIdentity = if (exists()) load() else create()

    /** Makes a brand-new key. Refuses if one exists: delete it first, on purpose. */
    @Synchronized
    fun create(): SigningIdentity {
        if (exists()) throw SigningKeyException("A signing key already exists")
        dir.mkdirs()
        val password = randomPassword()
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(keyBits, SecureRandom()) }.generateKeyPair()
        val now = clock()
        val cert = X509SelfSigned.create("Apkforge signing key", pair, Date(now - 60_000), Date(now + 10_000L * 86_400_000L), BigInteger(63, SecureRandom()).add(BigInteger.ONE))
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(ALIAS, pair.private, password.toCharArray(), arrayOf(cert))
        }
        // Write both files completely before either becomes visible.
        val tmpStore = File(dir, "forge-key.p12.tmp"); val tmpPass = File(dir, "forge-key.pass.tmp")
        try {
            tmpStore.outputStream().use { ks.store(it, password.toCharArray()) }
            tmpPass.writeText(password)
            for (f in listOf(tmpStore, tmpPass)) { f.setReadable(false, false); f.setWritable(false, false); f.setReadable(true, true); f.setWritable(true, true) }
            if (!tmpStore.renameTo(store) || !tmpPass.renameTo(pass)) { store.delete(); pass.delete(); throw SigningKeyException("Could not store the key") }
        } finally { tmpStore.delete(); tmpPass.delete() }
        return SigningIdentity(pair.private, cert)
    }

    @Synchronized
    fun load(): SigningIdentity {
        if (!exists()) throw SigningKeyException("There is no signing key yet")
        try {
            val password = pass.readText()
            val ks = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, password.toCharArray()) } }
            val key = ks.getKey(ALIAS, password.toCharArray()) as? PrivateKey ?: throw SigningKeyException("The key file has no key in it")
            val cert = ks.getCertificate(ALIAS) as? X509Certificate ?: throw SigningKeyException("The key file has no certificate in it")
            return SigningIdentity(key, cert)
        } catch (e: SigningKeyException) {
            throw e
        } catch (e: Exception) {
            throw SigningKeyException("The signing key could not be read; it was not replaced", e)
        }
    }

    /** Deletes the key. Apps signed with it can no longer be updated by Apkforge. Only call on the user's say-so. */
    @Synchronized
    fun delete() { store.delete(); pass.delete() }

    private fun randomPassword(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        val r = SecureRandom()
        return (1..32).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    private companion object { const val ALIAS = "forge" }
}

