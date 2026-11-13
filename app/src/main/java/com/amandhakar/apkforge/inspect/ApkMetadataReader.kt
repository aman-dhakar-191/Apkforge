package com.amandhakar.apkforge.inspect

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.amandhakar.apkforge.core.Hex
import java.io.File

data class ApkMetadata(
    val packageName: String,
    val label: String?,
    val versionName: String?,
    val versionCode: Long,
    val minSdk: Int?,
    val targetSdk: Int?,
    val permissions: List<String>,
    /** SHA-256 of each signing certificate; empty when Android could not read any. */
    val certificateSha256: List<String>,
)

/** Reads package metadata through Android's own parser. It does not install or run the APK. */
class ApkMetadataReader(private val context: Context) {

    fun read(apk: File): ApkMetadata? {
        val pm = context.packageManager
        val info = readInfo(pm, apk.path) ?: return null
        val app = info.applicationInfo
        // Needed so the label can be loaded from the archive and not from an installed copy.
        app?.sourceDir = apk.path
        app?.publicSourceDir = apk.path
        val label = try { app?.loadLabel(pm)?.toString() } catch (_: Exception) { null }
        return ApkMetadata(
            packageName = info.packageName,
            label = label,
            versionName = info.versionName,
            versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong(),
            minSdk = app?.minSdkVersion,
            targetSdk = app?.targetSdkVersion,
            permissions = info.requestedPermissions?.toList().orEmpty().sorted(),
            certificateSha256 = certificates(info),
        )
    }

    private fun readInfo(pm: PackageManager, path: String): PackageInfo? {
        return try {
            @Suppress("DEPRECATION")
            val flags = PackageManager.GET_PERMISSIONS or
                if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            pm.getPackageArchiveInfo(path, flags)
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo): List<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo
            when {
                si == null -> null
                si.hasMultipleSigners() -> si.apkContentsSigners
                else -> si.signingCertificateHistory
            }
        } else {
            info.signatures
        }
        return sigs?.map { Hex.sha256(it.toByteArray()) }.orEmpty()
    }
}
