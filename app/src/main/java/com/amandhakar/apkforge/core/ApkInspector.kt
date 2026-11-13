package com.amandhakar.apkforge.core

import java.io.File

/** What kind of thing an entry in the APK archive is. */
enum class EntryKind { MANIFEST, DEX, RESOURCE_TABLE, RESOURCE, ASSET, NATIVE_LIB, SIGNATURE, OTHER }

data class ApkEntry(
    val name: String,
    val size: Long,
    val compressedSize: Long,
    val kind: EntryKind,
    /** True when the name could escape a folder if extracted (".." or absolute path), or is a duplicate. */
    val unsafeName: Boolean,
)

data class ApkSummary(
    val entryCount: Int,
    val totalUncompressedBytes: Long,
    val countsByKind: Map<EntryKind, Int>,
    val dexFiles: List<String>,
    val nativeAbis: List<String>,
    val hasManifest: Boolean,
    val hasResourceTable: Boolean,
    val signatureFiles: List<String>,
    val unsafeNameCount: Int,
    /** A guess from the file list only: a manifest but no code and no resource table. */
    val looksLikeSplitApk: Boolean,
)

enum class RejectReason { NOT_FOUND, TOO_LARGE, NOT_A_ZIP, TOO_MANY_ENTRIES, UNCOMPRESSED_TOO_LARGE, UNREADABLE }

sealed interface InspectionResult {
    data class Ok(val entries: List<ApkEntry>, val summary: ApkSummary) : InspectionResult
    data class Rejected(val reason: RejectReason, val detail: String) : InspectionResult
}

/** Limits that keep a hostile or broken file from exhausting memory or time. */
data class InspectLimits(
    val maxFileBytes: Long = 2L * 1024 * 1024 * 1024,
    val maxEntries: Int = 100_000,
    val maxTotalUncompressedBytes: Long = 8L * 1024 * 1024 * 1024,
)

/** Reads the archive structure of an APK. Never extracts or runs anything. */
interface ApkInspector {
    fun inspect(apk: File): InspectionResult
}
