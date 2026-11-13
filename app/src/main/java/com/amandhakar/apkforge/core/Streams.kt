package com.amandhakar.apkforge.core

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads at most [limit] bytes. (InputStream.readNBytes would do this, but it only exists from Android 13,
 * and this app supports Android 8 and up.) Callers pass `max + 1` to find out whether the data was larger.
 */
fun InputStream.readUpTo(limit: Int): ByteArray {
    val out = ByteArrayOutputStream(minOf(limit, 64 * 1024).coerceAtLeast(16))
    val buf = ByteArray(64 * 1024)
    var left = limit
    while (left > 0) {
        val n = read(buf, 0, minOf(buf.size, left))
        if (n < 0) break
        out.write(buf, 0, n)
        left -= n
    }
    return out.toByteArray()
}
