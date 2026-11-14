package com.amandhakar.apkforge.build

import java.nio.ByteBuffer

/** Describes a resource configuration (language, night mode, screen density...) the way Android names it. */
object ConfigLabel {
    /** [at] is where the configuration starts (its size field); [size] is its size in bytes. */
    fun describe(b: ByteBuffer, at: Int, size: Int): String {
        fun u8(o: Int) = if (o + 1 <= size) b.get(at + o).toInt() and 0xFF else 0
        fun u16(o: Int) = if (o + 2 <= size) b.getShort(at + o).toInt() and 0xFFFF else 0
        val parts = ArrayList<String>()
        fun ch(o: Int) = u8(o).let { if (it in 'a'.code..'z'.code || it in 'A'.code..'Z'.code) it.toChar().toString() else "" }
        val lang = ch(8) + ch(9)
        val country = ch(10) + ch(11)
        if (lang.length == 2) parts += lang + if (country.length == 2) "-r$country" else ""
        else if (u8(8) != 0 || u8(9) != 0) parts += "locale"
        if (u16(0 + 4) != 0) parts += "mcc${u16(4)}"
        when (u8(12)) { 1 -> parts += "port"; 2 -> parts += "land" }
        when (u8(29) and 0x30) { 0x20 -> parts += "night"; 0x10 -> parts += "notnight" }
        if (u8(28) and 0xC0 == 0x80) parts += "ldrtl"
        val density = u16(14)
        if (density != 0) parts += when (density) { 120 -> "ldpi"; 160 -> "mdpi"; 213 -> "tvdpi"; 240 -> "hdpi"; 320 -> "xhdpi"; 480 -> "xxhdpi"; 640 -> "xxxhdpi"; 0xFFFE -> "anydpi"; 0xFFFF -> "nodpi"; else -> "${density}dpi" }
        if (u16(30) != 0) parts += "sw${u16(30)}dp"
        if (u16(32) != 0) parts += "w${u16(32)}dp"
        if (u16(34) != 0) parts += "h${u16(34)}dp"
        if (u16(24) != 0) parts += "v${u16(24)}"
        // any other non-zero setting we do not name
        val named = setOf(4, 5, 8, 9, 10, 11, 12, 14, 15, 24, 25, 28, 29, 30, 31, 32, 33, 34, 35)
        val other = (4 until minOf(size, 36)).any { it !in named && b.get(at + it).toInt() != 0 }
        if (other && parts.isEmpty()) parts += "other"
        return if (parts.isEmpty()) "default" else parts.joinToString("-")
    }
}
