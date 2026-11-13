package com.amandhakar.apkforge.core

import java.io.File

data class ResourceEntry(
    val id: Int,
    /** For example "string", "drawable", "layout". */
    val type: String,
    val name: String,
    /** Value in the default configuration (or the first one found), as text; null when not shown. */
    val value: String?,
    /** In how many configurations (languages, densities...) this resource is defined. */
    val configCount: Int,
    /** Files this resource points to, one per configuration (for images: one per screen density). */
    val files: List<ResourceFile> = emptyList(),
)

/** [density] is null for the default configuration, else e.g. "xxhdpi" or "other". */
data class ResourceFile(val path: String, val density: String?)

class ResourceTable(val entries: Map<Int, ResourceEntry>) {
    /** "type/name" for a resource id, or null when the table does not know it. */
    fun nameOf(id: Int): String? = entries[id]?.let { "${it.type}/${it.name}" }

    /** The value, with references to other resources shown by name. */
    fun displayValue(e: ResourceEntry): String? {
        val v = e.value ?: return null
        if (v.startsWith("@0x")) {
            val id = v.substring(3).toLongOrNull(16)?.toInt()
            val n = id?.let { nameOf(it) }
            if (n != null) return "@$n"
        }
        return v
    }

    val types: List<String> get() = entries.values.map { it.type }.distinct().sorted()
}

sealed interface ResourceResult {
    data class Ok(val table: ResourceTable) : ResourceResult
    data class Failed(val reason: String) : ResourceResult
}

/** Reads names and default values from an APK's resources.arsc. Reads only. */
interface ResourceDecoder {
    fun read(apk: File): ResourceResult
}

enum class SplitKind { COMPLETE, BASE_NEEDS_SPLITS, SPLIT_PART }

data class SplitInfo(val kind: SplitKind, val detail: String?)

object Splits {
    /** Decides from the manifest's root attributes (local names, e.g. "split", "requiredSplitTypes"). */
    fun classify(root: Map<String, String>): SplitInfo {
        val split = root["split"]
        if (!split.isNullOrBlank()) return SplitInfo(SplitKind.SPLIT_PART, split)
        val required = root["requiredSplitTypes"]
        if (!required.isNullOrBlank() || root["isSplitRequired"] == "true") {
            return SplitInfo(SplitKind.BASE_NEEDS_SPLITS, required?.takeIf { it.isNotBlank() })
        }
        return SplitInfo(SplitKind.COMPLETE, null)
    }
}
