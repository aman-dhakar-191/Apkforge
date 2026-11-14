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

    /** String number [index] of the pool at [start], or null if there is none. Decodes only that one string. */
    fun stringAt(b: ByteBuffer, start: Int, limit: Int, index: Int): String? {
        if (start < 0 || start + 28 > limit) return null
        val size = b.getInt(start + 4)
        if (size < 28 || start + size.toLong() > limit) return null
        val count = b.getInt(start + 8)
        if (index !in 0 until count || 28 + index.toLong() * 4 + 4 > size) return null
        val s = start.toLong() + b.getInt(start + 20) + b.getInt(start + 28 + index * 4)
        if (s < start || s >= start + size) return null
        return if (b.getInt(start + 16) and 0x100 != 0) utf8(b, s.toInt(), start + size) else utf16(b, s.toInt(), start + size)
    }

    /** How many strings at the start of the pool have style spans (bold, italic...). */
    fun styleCount(b: ByteBuffer, start: Int): Int = b.getInt(start + 12)

    /** A string as a pool stores it: length(s), characters, terminator. */
    fun encode(s: String, utf8: Boolean): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        if (utf8) {
            val raw = s.toByteArray(Charsets.UTF_8)
            need(s.length <= 0x7FFF && raw.size <= 0x7FFF, "a string is too long")
            fun v(n: Int) { if (n > 0x7F) { out.write((n shr 8) or 0x80); out.write(n and 0xFF) } else out.write(n) }
            v(s.length); v(raw.size); out.write(raw); out.write(0)
        } else {
            need(s.length <= 0x7FFF, "a string is too long")
            out.write(s.length and 0xFF); out.write(s.length shr 8)
            for (c in s) { out.write(c.code and 0xFF); out.write(c.code shr 8) }
            out.write(0); out.write(0)
        }
        return out.toByteArray()
    }

    /** Where the characters of string [index] sit: (offset, byte length, isUtf8). For patching a string in place. */
    fun charData(b: ByteBuffer, start: Int, limit: Int, index: Int): Triple<Int, Int, Boolean> {
        need(start >= 0 && start + 28 <= limit, "short string pool")
        val size = b.getInt(start + 4)
        need(size >= 28 && start + size.toLong() <= limit, "string pool runs past its container")
        val end = start + size
        val count = b.getInt(start + 8)
        need(index in 0 until count, "no such string")
        val utf8 = b.getInt(start + 16) and 0x100 != 0
        val s = start.toLong() + b.getInt(start + 20) + b.getInt(start + 28 + index * 4)
        need(s >= start && s < end, "string offset out of range")
        var p = s.toInt()
        if (utf8) {
            fun skipLen(): Int { need(p < end, "string runs past the chunk"); var n = b.get(p++).toInt() and 0xFF; if (n and 0x80 != 0) { need(p < end, "string runs past the chunk"); n = ((n and 0x7F) shl 8) or (b.get(p++).toInt() and 0xFF) }; return n }
            skipLen(); val byteLen = skipLen()
            need(p + byteLen <= end, "string runs past the chunk")
            return Triple(p, byteLen, true)
        }
        need(p + 2 <= end, "string runs past the chunk")
        var n = b.getShort(p).toInt() and 0xFFFF; p += 2
        if (n and 0x8000 != 0) { need(p + 2 <= end, "string runs past the chunk"); n = ((n and 0x7FFF) shl 16) or (b.getShort(p).toInt() and 0xFFFF); p += 2 }
        need(p + n.toLong() * 2 <= end, "string runs past the chunk")
        return Triple(p, n * 2, false)
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
        val arr = ByteArray(byteLen); System.arraycopy(b.array(), p, arr, 0, byteLen) // ByteBuffer.get(int, byte[]) needs Android 14
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
