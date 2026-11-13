package com.amandhakar.apkforge

import com.amandhakar.apkforge.build.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.util.Date

class SigningTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun res(n: String) = javaClass.getResourceAsStream("/$n")!!.readBytes()
    private val keys by lazy { SigningKeys(tmp.root.resolve("keys")) }
    private var n = 0
    private fun fixture() = tmp.newFile("fx${n++}.apk").apply { writeBytes(res("fx_img.apk")) }

    // ---- certificate
    @Test fun certificateIsValidAndSelfSigned() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val cert = X509SelfSigned.create("Test CN", pair, Date(now - 1000), Date(now + 86_400_000L), BigInteger.valueOf(12345))
        cert.verify(pair.public) // throws if the signature on the certificate is wrong
        cert.checkValidity()
        assertEquals("CN=Test CN", cert.subjectX500Principal.name)
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        assertEquals(BigInteger.valueOf(12345), cert.serialNumber)
        assertEquals(3, cert.version)
    }

    @Test fun datesFrom2050AreEncodedCorrectly() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val far = Date(java.time.LocalDate.of(2060, 5, 17).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
        val cert = X509SelfSigned.create("x", pair, Date(0), far, BigInteger.ONE)
        assertEquals(far.time / 1000, cert.notAfter.time / 1000)
        assertEquals(0L, cert.notBefore.time / 1000)
    }

    // ---- key storage
    @Test fun keyIsCreatedOnceAndReloadsIdentically() {
        assertFalse(keys.exists())
        val a = keys.loadOrCreate()
        assertTrue(keys.exists())
        val b = SigningKeys(tmp.root.resolve("keys")).loadOrCreate()
        assertEquals(a.fingerprint, b.fingerprint)
        assertEquals(a.certificate.publicKey, b.certificate.publicKey)
        a.certificate.checkValidity()
    }

    @Test fun creatingAgainRefusesInsteadOfReplacing() {
        val a = keys.create()
        try { keys.create(); fail() } catch (_: SigningKeyException) {}
        assertEquals(a.fingerprint, keys.load().fingerprint)
    }

    @Test fun aDamagedKeyIsAnErrorNeverSilentlyReplaced() {
        keys.create()
        val store = tmp.root.resolve("keys/forge-key.p12")
        val before = store.readBytes()
        store.writeBytes(before.copyOf(before.size / 2))
        try { keys.loadOrCreate(); fail() } catch (_: SigningKeyException) {}
        assertEquals(before.size / 2, store.readBytes().size) // untouched, not regenerated
    }

    @Test fun deleteRemovesTheKeyAndLeavesNoTempFiles() {
        keys.create(); keys.delete()
        assertFalse(keys.exists())
        assertEquals(0, tmp.root.resolve("keys").list()!!.size)
    }

    @Test fun passwordAndKeyAreNotLeftWorldReadable() {
        keys.create()
        for (n in listOf("forge-key.p12", "forge-key.pass")) {
            val f = tmp.root.resolve("keys/$n")
            // owner-only (setReadable(false, false) then (true, true)); skip silently on file systems without permissions
            if (f.canRead()) assertTrue(java.nio.file.Files.getPosixFilePermissions(f.toPath()).none { it.name.startsWith("OTHERS") || it.name.startsWith("GROUP") })
        }
    }

    // ---- the whole pipeline: replace -> sign -> verify
    @Test fun rebuiltAndSignedApkVerifies() {
        val id = keys.loadOrCreate()
        val unsigned = tmp.root.resolve("unsigned.apk"); val signed = tmp.root.resolve("signed.apk")
        ApkRebuilder().rebuild(fixture(), unsigned, mapOf("res/drawable/logo.png" to res("new_logo.png")))
        ApkSigning().sign(unsigned, signed, id, minSdk = 26)
        val r = ApkChecks().verify(signed, 26)
        assertTrue(r.errors.toString(), r.verified)
        assertTrue(r.v2); assertTrue(r.v3); assertFalse(r.v1) // no old JAR signature needed from Android 7
        assertEquals(listOf(id.fingerprint), r.certificates)
        // the replaced image is what ended up in the signed file
        java.util.zip.ZipFile(signed).use { z -> assertArrayEquals(res("new_logo.png"), z.getInputStream(z.getEntry("res/drawable/logo.png")).readBytes()) }
    }

    @Test fun oldAndroidVersionsGetTheV1SignatureToo() {
        val id = keys.loadOrCreate()
        val unsigned = tmp.root.resolve("u.apk"); val signed = tmp.root.resolve("s.apk")
        ApkRebuilder().rebuild(fixture(), unsigned, emptyMap())
        ApkSigning().sign(unsigned, signed, id, minSdk = 21)
        val r = ApkChecks().verify(signed, 21)
        assertTrue(r.errors.toString(), r.verified); assertTrue(r.v1)
    }

    @Test fun changingAByteAfterSigningBreaksVerification() {
        val id = keys.loadOrCreate()
        val unsigned = tmp.root.resolve("u.apk"); val signed = tmp.root.resolve("s.apk")
        ApkRebuilder().rebuild(fixture(), unsigned, emptyMap())
        ApkSigning().sign(unsigned, signed, id, 26)
        assertTrue(ApkChecks().verify(signed, 26).verified)
        val at = ZipLayout.read(signed).getValue("res/drawable/logo.png").dataOffset.toInt() + 10
        val bytes = signed.readBytes(); bytes[at] = (bytes[at].toInt() xor 0x55).toByte(); signed.writeBytes(bytes)
        val r = ApkChecks().verify(signed, 26)
        assertFalse(r.verified)
        assertTrue("a failed check must say why: ${r.errors}", r.errors.isNotEmpty())
    }

    @Test fun anUnsignedApkIsNotVerified() {
        val r = ApkChecks().verify(fixture(), 26)
        assertFalse(r.verified); assertTrue(r.errors.isNotEmpty())
        assertFalse(ApkChecks().verify(tmp.newFile("junk.apk").apply { writeText("nope") }).verified)
    }

    @Test fun signedOutputIsStillAlignedAndComplete() {
        val id = keys.loadOrCreate()
        val unsigned = tmp.root.resolve("u.apk"); val signed = tmp.root.resolve("s.apk")
        ApkRebuilder().rebuild(fixture(), unsigned, emptyMap())
        ApkSigning().sign(unsigned, signed, id, 26)
        val layout = ZipLayout.read(signed)
        assertEquals(0L, layout.getValue("resources.arsc").dataOffset % 4)
        assertEquals(0, layout.getValue("resources.arsc").method)
        assertTrue(layout.keys.containsAll(listOf("AndroidManifest.xml", "res/drawable/logo.png", "res/drawable/panel.9.png")))
    }

    @Test fun signingTwiceWithTheSameKeyGivesTheSameIdentity() {
        val id = keys.loadOrCreate()
        val fps = (1..2).map { i ->
            val u = tmp.root.resolve("u$i.apk"); val s = tmp.root.resolve("s$i.apk")
            ApkRebuilder().rebuild(fixture(), u, emptyMap()); ApkSigning().sign(u, s, id, 26)
            ApkChecks().verify(s, 26).certificates.single()
        }
        assertEquals(fps[0], fps[1])
    }
}

class CertificateLocaleTest {
    @Test fun certificateDatesDoNotDependOnThePhonesLanguage() {
        val saved = java.util.Locale.getDefault()
        try {
            // Thai and Arabic locales with their own digits: a date written in those digits is not valid DER time
            for (tag in listOf("th-TH-u-nu-thai", "ar-EG-u-nu-arab", "fa-IR-u-nu-arabext", "hi-IN-u-nu-deva")) {
                java.util.Locale.setDefault(java.util.Locale.forLanguageTag(tag))
                val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                val now = System.currentTimeMillis()
                val cert = X509SelfSigned.create("x", pair, Date(now - 1000), Date(now + 86_400_000L), BigInteger.ONE)
                cert.verify(pair.public)
                assertEquals(tag, now / 100000, cert.notBefore.time / 100000 + 0) // within the same ~100 s window as requested
            }
        } finally { java.util.Locale.setDefault(saved) }
    }
}
