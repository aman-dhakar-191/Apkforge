package com.amandhakar.apkforge.inspect

import com.amandhakar.apkforge.core.readUpTo
import com.amandhakar.apkforge.core.DexScan
import com.amandhakar.apkforge.core.DexScanner
import com.amandhakar.apkforge.core.Markers
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Reads only the class-name table of each classes*.dex (the type ids and their strings), which is
 * enough to tell which libraries the code refers to. It does not decode or run any code.
 */
class DexClassScanner(
    private val maxDexBytes: Int = 64 * 1024 * 1024,
    private val maxTypes: Int = 2_000_000,
) : DexScanner {

    override fun scan(apk: File): DexScan {
        val markers = HashSet<String>()
        var classes = 0
        var short = 0
        var skipped = 0
        try {
            ZipFile(apk).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.filter { DEX_NAME.matches(it) }.toList()
                for (name in names) {
                    val e = zip.getEntry(name) ?: continue
                    if (e.size > maxDexBytes) { skipped++; continue }
                    try {
                        val bytes = zip.getInputStream(e).use { it.readUpTo(maxDexBytes + 1) }
                        if (bytes.size > maxDexBytes) { skipped++; continue }
                        val r = scanDex(bytes)
                        markers += r.markers; classes += r.appClassCount; short += r.shortNameCount
                    } catch (_: AxmlException) { skipped++ } catch (_: IOException) { skipped++ }
                }
            }
        } catch (_: IOException) { skipped++ }
        return DexScan(markers, classes, short, skipped)
    }

    internal fun scanDex(bytes: ByteArray): DexScan {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        need(bytes.size >= 0x70, "dex too short")
        need(bytes[0] == 'd'.code.toByte() && bytes[1] == 'e'.code.toByte() && bytes[2] == 'x'.code.toByte() && bytes[3] == '\n'.code.toByte() && bytes[7] == 0.toByte(), "not a standard dex file")
        val stringCount = b.getInt(0x38); val stringOff = b.getInt(0x3C)
        val typeCount = b.getInt(0x40); val typeOff = b.getInt(0x44)
        need(stringCount >= 0 && typeCount in 0..maxTypes && stringOff >= 0 && typeOff >= 0, "bad dex header")
        need(stringOff.toLong() + stringCount.toLong() * 4 <= bytes.size && typeOff.toLong() + typeCount.toLong() * 4 <= bytes.size, "dex tables run past the file")

        val markers = HashSet<String>()
        var classes = 0
        var short = 0
        for (i in 0 until typeCount) {
            val idx = b.getInt(typeOff + i * 4)
            if (idx < 0 || idx >= stringCount) continue
            val d = descriptor(b, bytes, b.getInt(stringOff + idx * 4)) ?: continue
            val s = d.trimStart('[')
            if (!s.startsWith("L")) continue
            for (m in Markers.all) if (s.startsWith(m.descriptorPrefix)) markers += m.id
            if (!(s.startsWith("Ljava/") || s.startsWith("Ljavax/") || s.startsWith("Landroid/"))) {
                classes++
                val simple = s.removeSuffix(";").substringAfterLast('/').substringBefore('$')
                if (simple.length <= 2) short++
            }
        }
        return DexScan(markers, classes, short, 0)
    }

    /** Reads one string (its first 300 bytes) at [off]; null when out of range. */
    private fun descriptor(b: ByteBuffer, bytes: ByteArray, off: Int): String? {
        if (off < 0 || off >= bytes.size) return null
        var p = off
        // skip the ULEB128 length
        var guard = 0
        while (p < bytes.size && bytes[p].toInt() and 0x80 != 0 && guard++ < 5) p++
        p++
        if (p >= bytes.size) return null
        val sb = StringBuilder()
        while (p < bytes.size && sb.length < 300) {
            val c = bytes[p++].toInt() and 0xFF
            if (c == 0) break
            sb.append(c.toChar()) // descriptors are ASCII in practice; other bytes only affect prefix matching, not safety
        }
        return sb.toString()
    }

    private fun need(ok: Boolean, msg: String) { if (!ok) throw AxmlException(msg) }

    private companion object { val DEX_NAME = Regex("classes\\d*\\.dex") }
}
