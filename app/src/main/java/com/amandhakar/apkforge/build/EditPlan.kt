package com.amandhakar.apkforge.build

import com.amandhakar.apkforge.core.ApkEntry
import com.amandhakar.apkforge.core.PathSafety

/** A change to an APK. Only "replace a file under res/ or assets/" exists so far. */
sealed interface ApkEdit {
    val entryName: String

    class ReplaceFile(override val entryName: String, val bytes: ByteArray) : ApkEdit

    /** Put a PNG where a ".xml" drawing (vector, shape...) was. The file is renamed ".png" and the resource table follows. */
    class ReplaceDrawingWithPicture(override val entryName: String, val bytes: ByteArray) : ApkEdit {
        val newName: String get() = entryName.removeSuffix("xml") + "png"
    }
}

enum class ImageKind { PNG, JPEG, WEBP, GIF, UNKNOWN;
    companion object {
        fun sniff(b: ByteArray): ImageKind = when {
            b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> PNG
            b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> JPEG
            b.size >= 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> WEBP
            b.size >= 6 && String(b, 0, 3, Charsets.ISO_8859_1) == "GIF" -> GIF
            else -> UNKNOWN
        }

        fun expectedFor(entryName: String): ImageKind? = when (entryName.substringAfterLast('.').lowercase()) {
            "png" -> PNG; "jpg", "jpeg" -> JPEG; "webp" -> WEBP; "gif" -> GIF
            else -> null
        }
    }
}

/** Decides whether an edit is allowed, and describes what a set of edits will do. */
object EditPlan {
    const val MAX_REPLACEMENT_BYTES = 20 * 1024 * 1024

    /** Null when the edit is allowed; otherwise a plain-language reason it is not. */
    fun problem(edit: ApkEdit, entries: List<ApkEntry>): String? {
        when (edit) {
            is ApkEdit.ReplaceDrawingWithPicture -> {
                val name = edit.entryName
                if (!PathSafety.isSafeEntryName(name)) return "That file name is not safe."
                if (!name.startsWith("res/") || !name.endsWith(".xml")) return "Only a drawing (an .xml file under res/) can be turned into a picture."
                if (entries.count { it.name == name } != 1) return "That file is missing from the APK, or its name is not unique."
                if (entries.any { it.name == edit.newName }) return "A file named ${edit.newName} already exists."
                if (edit.bytes.isEmpty() || edit.bytes.size > MAX_REPLACEMENT_BYTES) return "The new picture is empty or larger than ${MAX_REPLACEMENT_BYTES / (1024 * 1024)} MB."
                if (ImageKind.sniff(edit.bytes) != ImageKind.PNG) return "The new picture must be a PNG."
                return null
            }
            is ApkEdit.ReplaceFile -> {
                val name = edit.entryName
                if (!PathSafety.isSafeEntryName(name)) return "That file name is not safe."
                val matches = entries.filter { it.name == name }
                if (matches.isEmpty()) return "That file is not in the APK."
                if (matches.size > 1) return "The APK has that name more than once, so it is not changed."
                if (!(name.startsWith("res/") || name.startsWith("assets/"))) {
                    return "Only files under res/ or assets/ can be replaced in this version."
                }
                if (name.startsWith("res/") && name.endsWith(".xml")) {
                    return "This is a compiled layout or other XML. Replacing it with a normal file would break the app; editing these is not available yet."
                }
                if (edit.bytes.isEmpty()) return "The new file is empty."
                if (edit.bytes.size > MAX_REPLACEMENT_BYTES) return "The new file is larger than ${MAX_REPLACEMENT_BYTES / (1024 * 1024)} MB."
                val expected = ImageKind.expectedFor(name)
                if (expected != null) {
                    val actual = ImageKind.sniff(edit.bytes)
                    if (actual != expected) {
                        return "The app expects a ${expected.name} here, but the new file looks like " +
                            (if (actual == ImageKind.UNKNOWN) "something that is not an image" else "a ${actual.name}") +
                            ". Apkforge does not convert images; convert it first."
                    }
                    if (name.endsWith(".9.png") && !hasNinePatchChunk(edit.bytes)) {
                        return "This is a stretchable (nine-patch) image. The new PNG must be a compiled nine-patch too; a plain PNG would draw wrongly or crash."
                    }
                }
                return null
            }
        }
    }

    /** One line per change, shown before anything is built. */
    fun summary(edits: List<ApkEdit>, entries: List<ApkEntry>): List<String> = edits.map { e ->
        when (e) {
            is ApkEdit.ReplaceDrawingWithPicture -> "Replace the drawing ${e.entryName} with a picture (${e.newName}, ${bytes(e.bytes.size.toLong())})"
            is ApkEdit.ReplaceFile -> {
                val old = entries.firstOrNull { it.name == e.entryName }
                "Replace ${e.entryName} (${old?.size?.let(::bytes) ?: "?"} → ${bytes(e.bytes.size.toLong())})"
            }
        }
    }

    private fun bytes(n: Long) = when { n >= 1 shl 20 -> "%.1f MB".format(n / 1048576.0); n >= 1024 -> "%.1f KB".format(n / 1024.0); else -> "$n B" }

    /** Walks the PNG chunks looking for npTc, the chunk a compiled nine-patch carries. */
    fun hasNinePatchChunk(png: ByteArray): Boolean {
        var p = 8
        while (p + 12 <= png.size) {
            val len = ((png[p].toInt() and 0xFF) shl 24) or ((png[p + 1].toInt() and 0xFF) shl 16) or ((png[p + 2].toInt() and 0xFF) shl 8) or (png[p + 3].toInt() and 0xFF)
            if (len < 0 || p.toLong() + 12 + len > png.size) return false
            if (String(png, p + 4, 4, Charsets.ISO_8859_1) == "npTc") return true
            p += 12 + len
        }
        return false
    }
}
