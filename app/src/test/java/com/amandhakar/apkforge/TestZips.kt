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
