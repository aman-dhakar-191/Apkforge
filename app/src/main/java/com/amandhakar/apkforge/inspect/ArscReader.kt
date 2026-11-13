package com.amandhakar.apkforge.inspect

import com.amandhakar.apkforge.core.ResourceDecoder
import com.amandhakar.apkforge.core.ResourceEntry
import com.amandhakar.apkforge.core.ResourceResult
import com.amandhakar.apkforge.core.ResourceTable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Reads resources.arsc: resource names (type/name) and their default-configuration values.
 * Read-only. Damaged input ends in [AxmlException] (reported as a failure), never a crash.
 */
class ArscReader(
    private val maxBytes: Int = 96 * 1024 * 1024,
    private val maxEntries: Int = 3_000_000,
) : ResourceDecoder {

    override fun read(apk: File): ResourceResult = try {
        val bytes = ZipFile(apk).use { zip ->
            val e = zip.getEntry("resources.arsc") ?: return ResourceResult.Failed("This APK has no resources.arsc")
            if (e.size > maxBytes) return ResourceResult.Failed("resources.arsc is larger than $maxBytes bytes")
            zip.getInputStream(e).use { it.readNBytes(maxBytes + 1) }
        }
        if (bytes.size > maxBytes) ResourceResult.Failed("resources.arsc is larger than $maxBytes bytes")
        else ResourceResult.Ok(parse(bytes))
    } catch (e: AxmlException) {
        ResourceResult.Failed("The resource table could not be read: ${e.message}")
    } catch (e: IOException) {
        ResourceResult.Failed("The resource table could not be read: ${e.message}")
    }

    fun parse(bytes: ByteArray): ResourceTable {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        need(bytes.size >= 12, "file too short")
        need(b.getShort(0).toInt() and 0xFFFF == TABLE, "not a resource table")
        val headerSize = b.getShort(2).toInt() and 0xFFFF
        val total = minOf(b.getInt(4).also { need(it >= 12, "bad table size") }, bytes.size)
        var globalStrings: List<String> = emptyList()
        val entries = HashMap<Int, ResourceEntry>()

        var pos = headerSize
        var first = true
        while (pos + 8 <= total) {
            val (type, size) = chunk(b, pos, total)
            when (type) {
                StringPool.CHUNK_TYPE -> if (first) { globalStrings = StringPool.read(b, pos, pos + size); first = false }
                PACKAGE -> readPackage(b, pos, pos + size, globalStrings, entries)
            }
            pos += size
        }
        return ResourceTable(entries)
    }

    private fun readPackage(b: ByteBuffer, start: Int, end: Int, globals: List<String>, out: MutableMap<Int, ResourceEntry>) {
        val headerSize = b.getShort(start + 2).toInt() and 0xFFFF
        need(headerSize >= 284 && start + headerSize <= end, "short package header")
        val pkgId = b.getInt(start + 8)
        val typeStringsOff = b.getInt(start + 268)
        val keyStringsOff = b.getInt(start + 276)
        need(typeStringsOff > 0 && keyStringsOff > 0 && start.toLong() + typeStringsOff < end && start.toLong() + keyStringsOff < end, "bad string pool offsets")
        val typeNames = StringPool.read(b, start + typeStringsOff, end)
        val keyNames = StringPool.read(b, start + keyStringsOff, end)

        var pos = start + headerSize
        while (pos + 8 <= end) {
            val (type, size) = chunk(b, pos, end)
            if (type == TYPE) readType(b, pos, pos + size, pkgId, typeNames, keyNames, globals, out)
            pos += size
        }
    }

    private fun readType(
        b: ByteBuffer, start: Int, end: Int, pkgId: Int,
        typeNames: List<String>, keyNames: List<String>, globals: List<String>, out: MutableMap<Int, ResourceEntry>,
    ) {
        val headerSize = b.getShort(start + 2).toInt() and 0xFFFF
        need(headerSize >= 24 && start + headerSize <= end, "short type header")
        val typeId = b.get(start + 8).toInt() and 0xFF
        val flags = b.get(start + 9).toInt() and 0xFF
        val count = b.getInt(start + 12)
        val entriesStart = b.getInt(start + 16)
        need(count in 0..0x10000 && entriesStart >= 0, "bad type chunk")
        val configSize = b.getInt(start + 20)
        need(configSize >= 4 && 20 + configSize <= headerSize, "bad configuration size")
        var isDefault = true
        for (i in 24 until 20 + configSize) if (b.get(start + i).toInt() != 0) { isDefault = false; break }
        val typeName = typeNames.getOrNull(typeId - 1) ?: return

        val sparse = flags and 0x01 != 0
        val offset16 = flags and 0x02 != 0
        val tableStart = start + headerSize
        val slot = if (offset16) 2 else 4
        need(tableStart.toLong() + count.toLong() * slot <= end, "entry table runs past the chunk")

        for (i in 0 until count) {
            val index: Int
            val off: Long
            if (sparse) {
                val v = b.getInt(tableStart + i * 4)
                index = v and 0xFFFF; off = (v ushr 16).toLong() * 4
            } else if (offset16) {
                val v = b.getShort(tableStart + i * 2).toInt() and 0xFFFF
                if (v == 0xFFFF) continue
                index = i; off = v.toLong() * 4
            } else {
                val v = b.getInt(tableStart + i * 4)
                if (v == -1) continue
                index = i; off = v.toLong() and 0xFFFFFFFFL
            }
            val e = start.toLong() + entriesStart + off
            if (e < start || e + 8 > end) continue // an entry pointing outside the chunk is ignored
            val ep = e.toInt()
            val eflags = b.getShort(ep + 2).toInt() and 0xFFFF
            if (eflags and 0x08 != 0) continue // compact entries: not supported
            val keyIdx = b.getInt(ep + 4)
            val key = keyNames.getOrNull(keyIdx) ?: continue
            val value: String? = if (eflags and 0x01 != 0) {
                if (ep + 16 <= end) "(${b.getInt(ep + 12).coerceAtLeast(0)} items)" else null
            } else if (ep + 16 <= end) {
                format(b.get(ep + 11).toInt() and 0xFF, b.getInt(ep + 12), globals)
            } else null

            val id = (pkgId shl 24) or (typeId shl 16) or index
            val old = out[id]
            if (old == null) {
                if (out.size >= maxEntries) throw AxmlException("too many resources")
                out[id] = ResourceEntry(id, typeName, key, value, 1)
            } else {
                out[id] = old.copy(value = if (isDefault) value else old.value, configCount = old.configCount + 1)
            }
        }
    }

    private fun format(type: Int, data: Int, globals: List<String>): String? = when (type) {
        0x03 -> globals.getOrNull(data)?.let { if (it.length > 200) it.take(200) + "…" else it }
        0x01 -> if (data == 0) "@null" else "@0x%08x".format(data)
        0x04 -> java.lang.Float.intBitsToFloat(data).toString()
        0x05 -> dimension(data)
        0x10 -> data.toString()
        0x11 -> "0x%x".format(data)
        0x12 -> if (data != 0) "true" else "false"
        in 0x1c..0x1f -> "#%08x".format(data)
        else -> null
    }

    private fun dimension(data: Int): String {
        val radix = (data shr 4) and 3
        val mult = floatArrayOf(0.00390625f, 3.0517578E-5f, 1.1920929E-7f, 4.656613E-10f)[radix]
        val v = (data and 0xFFFFFF00.toInt()) * mult
        val unit = arrayOf("px", "dp", "sp", "pt", "in", "mm").getOrNull(data and 0xF) ?: "?"
        return "$v$unit"
    }

    /** Returns (type, size) of the chunk at [pos], checked against [limit]. */
    private fun chunk(b: ByteBuffer, pos: Int, limit: Int): Pair<Int, Int> {
        val type = b.getShort(pos).toInt() and 0xFFFF
        val header = b.getShort(pos + 2).toInt() and 0xFFFF
        val size = b.getInt(pos + 4)
        need(header >= 8 && size >= header && pos.toLong() + size <= limit, "bad chunk size at $pos")
        return type to size
    }

    private fun need(ok: Boolean, msg: String) { if (!ok) throw AxmlException(msg) }

    private companion object { const val TABLE = 0x0002; const val PACKAGE = 0x0200; const val TYPE = 0x0201 }
}
