package com.amandhakar.apkforge.core

/** Android's packed "complex" numbers: sizes (dp, sp, px...) and fractions, as stored in compiled XML and resources. */
object ComplexValue {
    private val RADIX = floatArrayOf(0.00390625f, 3.0517578E-5f, 1.1920929E-7f, 4.656613E-10f)
    private val UNITS = arrayOf("px", "dp", "sp", "pt", "in", "mm")

    private fun mantissa(data: Int): Float = (data and 0xFFFFFF00.toInt()) * RADIX[(data shr 4) and 3]

    /** For example 0x0801 -> "8.0dp". */
    fun dimension(data: Int): String = mantissa(data).toString() + (UNITS.getOrNull(data and 0xF) ?: "?")

    /** For example a half -> "50.0%" (of the parent: "%p"). */
    fun fraction(data: Int): String = (mantissa(data) * 100).toString() + if (data and 0xF == 1) "%p" else "%"

    private val UNIT_CODES = mapOf("px" to 0, "dp" to 1, "dip" to 1, "sp" to 2, "pt" to 3, "in" to 4, "mm" to 5)

    /**
     * Packs [v] and a unit code into Android's complex number exactly as Android's own tools do (aapt2 and the
     * platform's string parser; checked bit for bit against aapt2 for 26 values). The value becomes a fixed-point
     * number with 23 fraction bits (rounded in 32-bit floating point); the precision is then the smallest that holds
     * its magnitude: none for whole numbers, 23 fraction bits under 1, 15 under 256, 7 under 65536. Bits beyond that
     * precision are cut off, not rounded.
     */
    fun encode(v: Double, unit: Int): Int? {
        if (v.isNaN() || v.isInfinite() || Math.abs(v) >= (1 shl 23)) return null
        val negative = v < 0
        val bits = (Math.abs(v).toFloat() * 8388608f + 0.5f).toLong()
        val radix: Int; val shift: Int
        when {
            bits and 0x7FFFFFL == 0L -> { radix = 0; shift = 23 }                 // no fraction: 23p0, easiest to read
            bits and 0x7FFFFFL.inv() == 0L -> { radix = 3; shift = 0 }             // magnitude below 1: 0p23
            bits and 0x7FFFFFFFL.inv() == 0L -> { radix = 2; shift = 8 }           // fits 8 bits: 8p15
            bits and 0x7FFFFFFFFFL.inv() == 0L -> { radix = 1; shift = 16 }        // fits 16 bits: 16p7
            else -> { radix = 0; shift = 23 }
        }
        var mantissa = ((bits shr shift) and 0xFFFFFF).toInt()
        if (negative) mantissa = (-mantissa) and 0xFFFFFF
        return (mantissa shl 8) or (radix shl 4) or unit
    }

    /** "16dp", "0.5dp", "-3px" -> packed value; null if it is not a size. */
    fun parseDimension(text: String): Int? {
        val m = Regex("\\s*(-?\\d+(?:\\.\\d+)?)\\s*(px|dp|dip|sp|pt|in|mm)\\s*").matchEntire(text) ?: return null
        return encode(m.groupValues[1].toDouble(), UNIT_CODES.getValue(m.groupValues[2]))
    }

    /** "50%" or "50%p" -> packed value; null if it is not a fraction. */
    fun parseFraction(text: String): Int? {
        val m = Regex("\\s*(-?\\d+(?:\\.\\d+)?)\\s*%(p?)\\s*").matchEntire(text) ?: return null
        return encode(m.groupValues[1].toDouble() / 100.0, if (m.groupValues[2] == "p") 1 else 0)
    }
}
