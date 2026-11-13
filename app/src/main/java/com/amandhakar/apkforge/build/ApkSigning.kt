package com.amandhakar.apkforge.build

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.amandhakar.apkforge.core.Hex
import java.io.File
import java.io.IOException

class SigningException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Signs APKs with Google's apksig library: v1 (only for old Android), v2 and v3 signatures. */
class ApkSigning {
    fun sign(input: File, output: File, identity: SigningIdentity, minSdk: Int) {
        val signer = ApkSigner.SignerConfig.Builder("forge", identity.privateKey, listOf(identity.certificate)).build()
        val tmp = File(output.parentFile, output.name + ".tmp")
        try {
            ApkSigner.Builder(listOf(signer))
                .setInputApk(input)
                .setOutputApk(tmp)
                .setMinSdkVersion(minSdk.coerceAtLeast(1))
                .setV1SigningEnabled(minSdk < 24) // Android 7+ does not need the old JAR signature
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .setOtherSignersSignaturesPreserved(false)
                .build()
                .sign()
            if (output.exists() && !output.delete()) throw SigningException("Could not replace the old output file")
            if (!tmp.renameTo(output)) throw SigningException("Could not store the signed file")
        } catch (e: SigningException) {
            throw e
        } catch (e: Exception) {
            throw SigningException("Signing failed: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            tmp.delete()
        }
    }
}

data class VerifyReport(
    val verified: Boolean,
    val v1: Boolean,
    val v2: Boolean,
    val v3: Boolean,
    /** SHA-256 of each signing certificate. */
    val certificates: List<String>,
    val errors: List<String>,
    val warnings: List<String>,
)

/** Checks an APK's signatures with apksig (the same checks Android's apksigner tool does). */
class ApkChecks {
    fun verify(apk: File, minSdk: Int? = null): VerifyReport = try {
        val r = ApkVerifier.Builder(apk).apply { if (minSdk != null) setMinCheckedPlatformVersion(minSdk) }.build().verify()
        VerifyReport(
            verified = r.isVerified,
            v1 = r.isVerifiedUsingV1Scheme, v2 = r.isVerifiedUsingV2Scheme, v3 = r.isVerifiedUsingV3Scheme,
            certificates = r.signerCertificates.map { Hex.sha256(it.encoded) },
            // allErrors includes the problems found inside each signature, not just the top-level ones
            errors = r.allErrors.map { it.toString() },
            warnings = r.warnings.map { it.toString() },
        )
    } catch (e: Exception) {
        VerifyReport(false, false, false, false, emptyList(), listOf("Could not check the file: ${e.message ?: e.javaClass.simpleName}"), emptyList())
    }
}
