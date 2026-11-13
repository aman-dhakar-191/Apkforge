package com.amandhakar.apkforge

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object TestZips {
    fun zip(file: File, entries: List<Pair<String, ByteArray>>): File {
        ZipOutputStream(file.outputStream()).use { z ->
            entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
        }
        return file
    }
}

/** Helpers for looking at the raw layout of a zip: where each entry's data starts and how it is stored. */
object ZipLayout {
    class Info(val dataOffset: Long, val method: Int)

    fun read(file: File): Map<String, Info> {
        val b = java.nio.ByteBuffer.wrap(file.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var eocd = b.limit() - 22
        while (eocd >= 0 && b.getInt(eocd) != 0x06054b50) eocd--
        require(eocd >= 0) { "no end-of-directory record" }
        val count = b.getShort(eocd + 10).toInt() and 0xFFFF
        var p = b.getInt(eocd + 16)
        val out = LinkedHashMap<String, Info>()
        repeat(count) {
            require(b.getInt(p) == 0x02014b50)
            val method = b.getShort(p + 10).toInt() and 0xFFFF
            val n = b.getShort(p + 28).toInt() and 0xFFFF
            val m = b.getShort(p + 30).toInt() and 0xFFFF
            val k = b.getShort(p + 32).toInt() and 0xFFFF
            val local = b.getInt(p + 42)
            val name = String(b.array(), p + 46, n, Charsets.UTF_8)
            val ln = b.getShort(local + 26).toInt() and 0xFFFF
            val lm = b.getShort(local + 28).toInt() and 0xFFFF
            out[name] = Info(local.toLong() + 30 + ln + lm, method)
            p += 46 + n + m + k
        }
        return out
    }

    fun stored(file: File, entries: List<Pair<String, ByteArray>>): File {
        java.util.zip.ZipOutputStream(file.outputStream()).use { z ->
            for ((n, bytes) in entries) {
                val e = java.util.zip.ZipEntry(n).apply {
                    method = java.util.zip.ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = bytes.size.toLong()
                    crc = java.util.zip.CRC32().apply { update(bytes) }.value
                }
                z.putNextEntry(e); z.write(bytes); z.closeEntry()
            }
        }
        return file
    }
}
