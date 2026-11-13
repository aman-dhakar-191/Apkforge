package com.amandhakar.apkforge.inspect

import java.nio.ByteBuffer

/** Reads a string pool chunk (shared by compiled XML and resources.arsc). Every access is bounds-checked. */
internal object StringPool {
    const val CHUNK_TYPE = 0x0001

    /** [start] is the chunk's offset; [limit] is the end of the data it may use. */
    fun read(b: ByteBuffer, start: Int, limit: Int, maxStrings: Int = 4_000_000): List<String> {
        need(start >= 0 && start + 28 <= limit, "short string pool")
        if (b.getShort(start).toInt() and 0xFFFF != CHUNK_TYPE) throw AxmlException("expected a string pool")
        val size = b.getInt(start + 4)
        need(size >= 28 && start + size.toLong() <= limit, "string pool runs past its container")
        val end = start + size
        val count = b.getInt(start + 8)
        need(count in 0..maxStrings, "too many strings")
        val flags = b.getInt(start + 16)
        val stringsStart = b.getInt(start + 20)
        need(stringsStart >= 0, "bad strings offset")
        val utf8 = flags and 0x100 != 0
        need(count.toLong() * 4 + 28 <= size, "string pool too large for its chunk")
        val result = ArrayList<String>(count)
        for (i in 0 until count) {
            val off = b.getInt(start + 28 + i * 4)
            need(off >= 0, "bad string offset")
            val s = start.toLong() + stringsStart + off
            need(s >= start && s < end, "string offset out of range")
            result += if (utf8) utf8(b, s.toInt(), end) else utf16(b, s.toInt(), end)
        }
        return result
    }

    private fun utf8(b: ByteBuffer, start: Int, end: Int): String {
        var p = start
        fun len(): Int {
            need(p < end, "string runs past the chunk")
            var n = b.get(p++).toInt() and 0xFF
            if (n and 0x80 != 0) { need(p < end, "string runs past the chunk"); n = ((n and 0x7F) shl 8) or (b.get(p++).toInt() and 0xFF) }
            return n
        }
        len()
        val byteLen = len()
        need(p + byteLen <= end, "string runs past the chunk")
        val arr = ByteArray(byteLen); b.get(p, arr)
        return String(arr, Charsets.UTF_8)
    }

    private fun utf16(b: ByteBuffer, start: Int, end: Int): String {
        var p = start
        need(p + 2 <= end, "string runs past the chunk")
        var n = b.getShort(p).toInt() and 0xFFFF; p += 2
        if (n and 0x8000 != 0) { need(p + 2 <= end, "string runs past the chunk"); n = ((n and 0x7FFF) shl 16) or (b.getShort(p).toInt() and 0xFFFF); p += 2 }
        need(n >= 0 && p + n.toLong() * 2 <= end, "string runs past the chunk")
        val sb = StringBuilder(n)
        for (i in 0 until n) sb.append((b.getShort(p + i * 2).toInt() and 0xFFFF).toChar())
        return sb.toString()
    }

    private fun need(ok: Boolean, msg: String) { if (!ok) throw AxmlException(msg) }
}
