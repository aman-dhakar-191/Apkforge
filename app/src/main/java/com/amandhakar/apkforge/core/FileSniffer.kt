package com.amandhakar.apkforge.core

import com.amandhakar.apkforge.build.ImageKind
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

enum class FileKind { IMAGE, COMPILED_XML, TEXT_XML, TEXT, BINARY }

/** Works out what a file inside an APK is by looking at its bytes, not its name. */
object FileSniffer {
    fun kind(bytes: ByteArray): FileKind = when {
        ImageKind.sniff(bytes) != ImageKind.UNKNOWN -> FileKind.IMAGE
        isCompiledXml(bytes) -> FileKind.COMPILED_XML
        looksLikeText(bytes) -> if (startsLikeXml(bytes)) FileKind.TEXT_XML else FileKind.TEXT
        else -> FileKind.BINARY
    }

    /** Android's binary XML starts with a chunk of type 3 (XML) with an 8-byte header, and says how long it is. */
    fun isCompiledXml(b: ByteArray): Boolean {
        if (b.size < 16) return false
        fun u16(i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        val size = (b[4].toLong() and 0xFF) or ((b[5].toLong() and 0xFF) shl 8) or ((b[6].toLong() and 0xFF) shl 16) or ((b[7].toLong() and 0xFF) shl 24)
        return u16(0) == 0x0003 && u16(2) == 8 && size in 16..b.size.toLong()
    }

    /** Valid UTF-8 with no control characters other than tab, newline, carriage return and form feed. */
    fun looksLikeText(b: ByteArray, sample: Int = 8192): Boolean {
        if (b.isEmpty()) return false
        val n = minOf(b.size, sample)
        for (i in 0 until n) {
            val c = b[i].toInt() and 0xFF
            if (c < 0x20 && c != 9 && c != 10 && c != 13 && c != 12) return false
        }
        // a multi-byte character may be cut at the end of the sample: try shorter cuts too
        for (cut in 0..3) {
            val len = n - cut
            if (len <= 0) break
            try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b, 0, len))
                return true
            } catch (_: CharacterCodingException) {
                if (n == b.size) return false // the whole file: a cut cannot explain it
            }
        }
        return false
    }

    fun startsLikeXml(b: ByteArray): Boolean {
        var i = 0
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) i = 3
        while (i < b.size && (b[i].toInt() == 32 || b[i].toInt() in 9..13)) i++
        if (i + 1 >= b.size || b[i] != '<'.code.toByte()) return false
        val c = b[i + 1].toInt().toChar()
        return c == '?' || c == '!' || c.isLetter()
    }

    /** Classic hex view: offset, 16 bytes in hex, then the printable characters. */
    fun hexDump(b: ByteArray, max: Int = 512): String {
        val n = minOf(b.size, max)
        val sb = StringBuilder()
        var i = 0
        while (i < n) {
            sb.append("%06x  ".format(i))
            val end = minOf(i + 16, n)
            for (j in i until i + 16) sb.append(if (j < end) "%02x ".format(b[j].toInt() and 0xFF) else "   ").also { if (j == i + 7) sb.append(' ') }
            sb.append(" ")
            for (j in i until end) { val c = b[j].toInt() and 0xFF; sb.append(if (c in 32..126) c.toChar() else '.') }
            sb.append('\n')
            i += 16
        }
        if (b.size > n) sb.append("… ${b.size - n} more bytes not shown\n")
        return sb.toString()
    }
}
