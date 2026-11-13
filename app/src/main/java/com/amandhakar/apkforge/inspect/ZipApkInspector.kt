package com.amandhakar.apkforge.inspect

import com.amandhakar.apkforge.core.*
import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** [ApkInspector] built on java.util.zip. Only reads the central directory; nothing is extracted. */
class ZipApkInspector(private val limits: InspectLimits = InspectLimits()) : ApkInspector {

    override fun inspect(apk: File): InspectionResult {
        if (!apk.isFile) return InspectionResult.Rejected(RejectReason.NOT_FOUND, "File does not exist")
        if (apk.length() > limits.maxFileBytes) {
            return InspectionResult.Rejected(RejectReason.TOO_LARGE, "File is larger than ${limits.maxFileBytes} bytes")
        }
        if (!startsWithZipMagic(apk)) {
            return InspectionResult.Rejected(RejectReason.NOT_A_ZIP, "File does not start with a ZIP header")
        }
        return try {
            ZipFile(apk).use { zip -> readEntries(zip) }
        } catch (e: ZipException) {
            InspectionResult.Rejected(RejectReason.NOT_A_ZIP, "Archive is damaged: ${e.message}")
        } catch (e: IOException) {
            InspectionResult.Rejected(RejectReason.UNREADABLE, "Could not read the file: ${e.message}")
        }
    }

    private fun readEntries(zip: ZipFile): InspectionResult {
        if (zip.size() > limits.maxEntries) {
            return InspectionResult.Rejected(RejectReason.TOO_MANY_ENTRIES, "Archive has ${zip.size()} entries")
        }
        val entries = ArrayList<ApkEntry>(zip.size())
        val seen = HashSet<String>()
        var total = 0L
        val e = zip.entries()
        while (e.hasMoreElements()) {
            val z = e.nextElement()
            if (entries.size >= limits.maxEntries) {
                return InspectionResult.Rejected(RejectReason.TOO_MANY_ENTRIES, "Archive has too many entries")
            }
            val size = z.size.coerceAtLeast(0)
            total += size
            if (total > limits.maxTotalUncompressedBytes) {
                return InspectionResult.Rejected(RejectReason.UNCOMPRESSED_TOO_LARGE, "Contents would be too large to unpack")
            }
            val duplicate = !seen.add(z.name)
            entries += ApkEntry(
                name = z.name,
                size = size,
                compressedSize = z.compressedSize.coerceAtLeast(0),
                kind = classify(z.name),
                unsafeName = duplicate || !PathSafety.isSafeEntryName(z.name.removeSuffix("/")),
            )
        }
        return InspectionResult.Ok(entries, summarize(entries, total))
    }

    private fun startsWithZipMagic(f: File): Boolean = try {
        f.inputStream().use { s ->
            val b = ByteArray(4)
            // An empty archive starts with the end-of-directory record (PK\5\6); a normal one with PK\3\4.
            s.read(b) == 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() &&
                ((b[2] == 3.toByte() && b[3] == 4.toByte()) || (b[2] == 5.toByte() && b[3] == 6.toByte()))
        }
    } catch (_: IOException) {
        false
    }

    companion object {
        fun classify(name: String): EntryKind = when {
            name == "AndroidManifest.xml" -> EntryKind.MANIFEST
            name.matches(Regex("classes\\d*\\.dex")) -> EntryKind.DEX
            name == "resources.arsc" -> EntryKind.RESOURCE_TABLE
            name.startsWith("res/") -> EntryKind.RESOURCE
            name.startsWith("assets/") -> EntryKind.ASSET
            name.startsWith("lib/") && name.endsWith(".so") -> EntryKind.NATIVE_LIB
            name.startsWith("META-INF/") && (name.endsWith(".RSA") || name.endsWith(".DSA") ||
                name.endsWith(".EC") || name.endsWith(".SF") || name.endsWith("MANIFEST.MF")) -> EntryKind.SIGNATURE
            else -> EntryKind.OTHER
        }

        fun summarize(entries: List<ApkEntry>, totalBytes: Long): ApkSummary {
            val counts = entries.groupingBy { it.kind }.eachCount()
            val dex = entries.filter { it.kind == EntryKind.DEX }.map { it.name }.sorted()
            val abis = entries.filter { it.kind == EntryKind.NATIVE_LIB }
                .mapNotNull { it.name.split('/').getOrNull(1) }.distinct().sorted()
            val hasManifest = counts.containsKey(EntryKind.MANIFEST)
            val hasTable = counts.containsKey(EntryKind.RESOURCE_TABLE)
            return ApkSummary(
                entryCount = entries.size,
                totalUncompressedBytes = totalBytes,
                countsByKind = counts,
                dexFiles = dex,
                nativeAbis = abis,
                hasManifest = hasManifest,
                hasResourceTable = hasTable,
                signatureFiles = entries.filter { it.kind == EntryKind.SIGNATURE }.map { it.name },
                unsafeNameCount = entries.count { it.unsafeName },
                looksLikeSplitApk = hasManifest && dex.isEmpty() && !hasTable,
            )
        }
    }
}
