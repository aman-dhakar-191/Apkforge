package com.amandhakar.apkforge.build

import com.amandhakar.apkforge.core.PathSafety
import java.io.BufferedOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class RebuildException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Writes a copy of an APK with some entries replaced, as an UNSIGNED, aligned zip. Everything not
 * replaced keeps its content and storage method. Old signatures are dropped (they would no longer
 * match); the result must be signed before it can be installed.
 *
 * Alignment follows what Android needs: resources.arsc stored uncompressed on a 4-byte boundary, and
 * stored native libraries on a 16 KB boundary.
 */
class ApkRebuilder(private val maxReplacementBytes: Int = 64 * 1024 * 1024) {

    /**
     * [replacements] maps an entry's name in the original to its new bytes. [renames] maps an entry's name in the
     * original to a new name for the same place in the file: used to turn a ".xml" drawing into a ".png" picture
     * (the bytes for it are in [replacements] under the old name).
     */
    fun rebuild(original: File, output: File, replacements: Map<String, ByteArray>, renames: Map<String, String> = emptyMap()) {
        for ((from, to) in renames) {
            if (!PathSafety.isSafeEntryName(to)) throw RebuildException("Unsafe file name: $to")
            if (from !in replacements) throw RebuildException("No new content for renamed file $from")
        }
        for ((name, bytes) in replacements) {
            if (!PathSafety.isSafeEntryName(name)) throw RebuildException("Unsafe file name: $name")
            if (bytes.size > maxReplacementBytes) throw RebuildException("The replacement for $name is too large")
        }
        val tmp = File(output.parentFile, output.name + ".tmp")
        try {
            ZipFile(original).use { zip -> write(zip, tmp, replacements, renames) }
            if (output.exists() && !output.delete()) throw RebuildException("Could not replace the old output file")
            if (!tmp.renameTo(output)) throw RebuildException("Could not store the rebuilt file")
        } catch (e: RebuildException) {
            throw e
        } catch (e: IOException) {
            throw RebuildException("Rebuilding failed: ${e.message}", e)
        } finally {
            tmp.delete()
        }
    }

    private fun write(zip: ZipFile, out: File, replacements: Map<String, ByteArray>, renames: Map<String, String>) {
        val entries = zip.entries().toList()
        val names = HashSet<String>()
        for (e in entries) {
            if (!names.add(e.name) || !PathSafety.isSafeEntryName(e.name.removeSuffix("/"))) {
                throw RebuildException("The APK has an unsafe or duplicate file name, so it will not be rebuilt")
            }
        }
        val missing = replacements.keys - names
        if (missing.isNotEmpty()) throw RebuildException("Not in the APK: ${missing.first()}")
        val taken = names - renames.keys
        val newNames = renames.values.toList()
        if (newNames.size != newNames.toSet().size || newNames.any { it in taken }) throw RebuildException("A renamed file would take the name of another file")

        val counter = CountingOutputStream(BufferedOutputStream(out.outputStream(), 64 * 1024))
        ZipOutputStream(counter).use { zos ->
            zos.setLevel(6)
            for (e in entries) {
                if (isOldSignature(e.name)) continue
                val replacement = replacements[e.name]
                val outName = renames[e.name] ?: e.name
                val stored = e.method == ZipEntry.STORED || e.name == "resources.arsc"
                zos.closeEntry() // so the byte counter is exact before the next header
                if (e.isDirectory) {
                    zos.putNextEntry(ZipEntry(outName).apply { time = FIXED_TIME })
                    zos.closeEntry()
                    continue
                }
                if (stored) {
                    val data = replacement ?: zip.getInputStream(e).use { it.readBytes() }
                    val entry = ZipEntry(outName).apply {
                        method = ZipEntry.STORED
                        size = data.size.toLong(); compressedSize = data.size.toLong()
                        crc = CRC32().apply { update(data) }.value
                        time = FIXED_TIME
                        val align = alignmentFor(outName)
                        if (align > 1) extra = alignExtra(counter.count, outName.toByteArray(Charsets.UTF_8).size, align)
                    }
                    zos.putNextEntry(entry)
                    zos.write(data)
                } else {
                    zos.putNextEntry(ZipEntry(outName).apply { method = ZipEntry.DEFLATED; time = FIXED_TIME })
                    if (replacement != null) zos.write(replacement) else zip.getInputStream(e).use { it.copyTo(zos) }
                }
                zos.closeEntry()
            }
        }
    }

    companion object {
        /** A date inside the range the zip format can store without extra fields (as Android's own tools use). */
        private val FIXED_TIME: Long = LocalDateTime.of(1981, 1, 1, 1, 1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        fun isOldSignature(name: String): Boolean {
            if (!name.startsWith("META-INF/") || name.count { it == '/' } != 1) return false
            val n = name.uppercase()
            return n == "META-INF/MANIFEST.MF" || n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC")
        }

        fun alignmentFor(name: String): Int = when {
            name.startsWith("lib/") && name.endsWith(".so") -> 16 * 1024
            else -> 4
        }

        /**
         * The "extra" bytes for a stored entry's local header so its data starts on a multiple of [align].
         * [offset] is where the header will start; the header is 30 bytes plus name plus extra.
         */
        fun alignExtra(offset: Long, nameBytes: Int, align: Int): ByteArray {
            val base = offset + 30 + nameBytes
            var pad = ((align - base % align) % align).toInt()
            if (pad == 0) return ByteArray(0)
            while (pad < 6) pad += align // the extra field needs at least a 4-byte header and 2 data bytes
            val extra = ByteArray(pad)
            extra[0] = 0x35; extra[1] = 0xD9.toByte() // 0xD935: the id zipalign uses for alignment padding
            val dataLen = pad - 4
            extra[2] = (dataLen and 0xFF).toByte(); extra[3] = (dataLen shr 8).toByte()
            extra[4] = (align and 0xFF).toByte(); extra[5] = ((align shr 8) and 0xFF).toByte()
            return extra
        }
    }
}

private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
    var count = 0L
        private set

    override fun write(b: Int) { out.write(b); count++ }
    override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
}
