package com.amandhakar.apkforge.build

import com.amandhakar.apkforge.inspect.AxmlException
import com.amandhakar.apkforge.inspect.StringPool
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Edits file paths stored in resources.arsc. Android decides how to load a drawable from its file name: a path
 * ending in ".xml" is read as a drawing (vector, shape...), anything else as a picture. So to put a picture where
 * a vector was, the path must end in ".png". "xml" and "png" are both 3 letters, so the path string is patched in
 * place: nothing in the table moves and no offset changes.
 */
object ArscPaths {
    /** Returns a copy where each of [paths] (all ending ".xml") now ends ".png". Throws if one is not in the table. */
    fun xmlToPng(arsc: ByteArray, paths: Collection<String>): ByteArray {
        val out = arsc.copyOf()
        val b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        if (out.size < 12 || b.getShort(0).toInt() and 0xFFFF != 0x0002) throw AxmlException("not a resource table")
        val total = minOf(b.getInt(4).also { if (it < 12) throw AxmlException("bad table size") }, out.size)
        var pos = b.getShort(2).toInt() and 0xFFFF
        // the value pool is the first string pool in the table
        while (pos + 8 <= total && (b.getShort(pos).toInt() and 0xFFFF) != 0x0001) {
            val size = b.getInt(pos + 4)
            if (size < 8 || pos.toLong() + size > total) throw AxmlException("bad chunk size at $pos")
            pos += size
        }
        if (pos + 8 > total) throw AxmlException("the table has no string pool")
        val strings = StringPool.read(b, pos, total)
        for (path in paths) {
            if (!path.endsWith(".xml")) throw AxmlException("$path is not an XML file")
            val indexes = strings.indices.filter { strings[it] == path }
            if (indexes.isEmpty()) throw AxmlException("$path is not in the resource table")
            for (i in indexes) {
                val (at, len, utf8) = StringPool.charData(b, pos, total, i)
                val unit = if (utf8) 1 else 2
                if (len < 3 * unit) throw AxmlException("$path is too short")
                val ext = len - 3 * unit
                // check the three letters really are "xml" before touching them
                for ((k, c) in "xml".withIndex()) if (out[at + ext + k * unit] != c.code.toByte()) throw AxmlException("the stored path does not end in xml")
                for ((k, c) in "png".withIndex()) out[at + ext + k * unit] = c.code.toByte()
            }
        }
        return out
    }
}
