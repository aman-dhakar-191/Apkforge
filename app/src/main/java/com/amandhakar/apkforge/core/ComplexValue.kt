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
}
