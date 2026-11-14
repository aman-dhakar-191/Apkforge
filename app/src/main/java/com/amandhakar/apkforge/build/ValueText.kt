package com.amandhakar.apkforge.build

import com.amandhakar.apkforge.core.ComplexValue

sealed interface Parsed {
    /** A non-string value, ready to be written in place. */
    class Value(val dataType: Int, val data: Int) : Parsed
    /** New text for a string value. */
    class Text(val text: String) : Parsed
    class Error(val message: String) : Parsed
}

/** Shows a resource value as a person writes it, and turns what they type back into the right kind of value. */
object ValueText {
    const val MAX_TEXT = 4000

    fun show(slot: ValueSlot, nameOf: (Int) -> String?): String = when (slot.dataType) {
        0x03 -> slot.text ?: ""
        0x01 -> if (slot.data == 0) "@null" else nameOf(slot.data)?.let { "@$it" } ?: "@0x%08x".format(slot.data)
        0x1c, 0x1d, 0x1e, 0x1f -> if (slot.dataType == 0x1d) "#%06x".format(slot.data and 0xFFFFFF) else "#%08x".format(slot.data)
        0x10 -> slot.data.toString()
        0x11 -> "0x%x".format(slot.data)
        0x12 -> if (slot.data != 0) "true" else "false"
        0x04 -> java.lang.Float.intBitsToFloat(slot.data).toString()
        0x05 -> ComplexValue.dimension(slot.data)
        0x06 -> ComplexValue.fraction(slot.data)
        else -> "(not editable)"
    }

    /** [idOf] turns "type/name" (as in "@string/app_name") into a resource id, or null. */
    fun parse(slot: ValueSlot, input: String, idOf: (String) -> Int?): Parsed {
        val s = input.trim()
        return when (slot.dataType) {
            0x03 -> when {
                input.length > MAX_TEXT -> Parsed.Error("Use $MAX_TEXT characters or fewer.")
                input.any { it.isISOControl() && it != '\n' && it != '\t' } -> Parsed.Error("The text cannot contain control characters.")
                else -> Parsed.Text(input)
            }
            0x01 -> when {
                s == "@null" -> Parsed.Value(0x01, 0)
                s.startsWith("@0x") -> s.substring(3).toLongOrNull(16)?.let { Parsed.Value(0x01, it.toInt()) } ?: Parsed.Error("That is not a resource id.")
                s.startsWith("@") -> idOf(s.substring(1))?.let { Parsed.Value(0x01, it) } ?: Parsed.Error("No resource named $s in this app. Write it like @string/name, or as @0x7f... for one that is not listed.")
                else -> Parsed.Error("A reference starts with @, for example @string/app_name.")
            }
            0x1c, 0x1d, 0x1e, 0x1f -> color(s)
            0x10, 0x11 -> {
                val hex = s.startsWith("0x") || s.startsWith("-0x")
                val v = if (hex) s.replaceFirst("0x", "").toLongOrNull(16) else s.toLongOrNull()
                if (v == null || v < Int.MIN_VALUE || v > 0xFFFFFFFFL) Parsed.Error("That is not a whole number.")
                else Parsed.Value(if (hex) 0x11 else 0x10, v.toInt())
            }
            0x12 -> when (s.lowercase()) { "true" -> Parsed.Value(0x12, -1); "false" -> Parsed.Value(0x12, 0); else -> Parsed.Error("Write true or false.") }
            0x04 -> s.toFloatOrNull()?.takeIf { it.isFinite() }?.let { Parsed.Value(0x04, java.lang.Float.floatToIntBits(it)) } ?: Parsed.Error("That is not a number.")
            0x05 -> ComplexValue.parseDimension(s)?.let { Parsed.Value(0x05, it) } ?: Parsed.Error("Write a size like 16dp, 14sp, 0.5dp, 2px, 1in or 3mm.")
            0x06 -> ComplexValue.parseFraction(s)?.let { Parsed.Value(0x06, it) } ?: Parsed.Error("Write a fraction like 50% or 25%p.")
            else -> Parsed.Error("This kind of value cannot be edited.")
        }
    }

    private fun color(s: String): Parsed {
        val h = s.removePrefix("#")
        if (!s.startsWith("#") || h.any { it !in "0123456789abcdefABCDEF" }) return Parsed.Error("Write a colour like #RRGGBB or #AARRGGBB.")
        return when (h.length) {
            3 -> Parsed.Value(0x1d, 0xFF000000.toInt() or h.map { "$it$it" }.joinToString("").toInt(16))
            4 -> Parsed.Value(0x1c, h.map { "$it$it" }.joinToString("").toLong(16).toInt())
            6 -> Parsed.Value(0x1d, 0xFF000000.toInt() or h.toInt(16))
            8 -> Parsed.Value(0x1c, h.toLong(16).toInt())
            else -> Parsed.Error("A colour has 3, 4, 6 or 8 hex digits after the #.")
        }
    }
}
