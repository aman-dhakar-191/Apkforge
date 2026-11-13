package com.amandhakar.apkforge.log

import java.io.File
import java.io.IOException

/**
 * Optional, local-only diagnostics. Off by default. Nothing here touches the network (the app has no
 * INTERNET permission); the user can read the log in the app and choose to share it.
 *
 * It records what happened (action names, counts, error types), never file names, paths, URIs or
 * APK contents: [clean] strips those from every value.
 */
class DebugLog(
    private val file: File,
    private val isEnabled: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = 256 * 1024,
) {
    @Synchronized
    fun event(name: String, vararg details: Pair<String, Any?>) {
        if (!isEnabled()) return
        val line = buildString {
            append(clock()).append(' ').append(clean(name))
            details.forEach { (k, v) -> append(' ').append(clean(k)).append('=').append(clean(v.toString())) }
            append('\n')
        }
        try {
            file.parentFile?.mkdirs()
            if (file.length() > maxBytes) trim()
            file.appendText(line)
        } catch (_: IOException) {
            // Logging must never crash the app.
        }
    }

    @Synchronized
    fun read(): String = try { if (file.isFile) file.readText() else "" } catch (_: IOException) { "" }

    @Synchronized
    fun clear() { file.delete() }

    private fun trim() {
        val lines = file.readLines()
        file.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
    }

    companion object {
        private val URI = Regex("[A-Za-z][A-Za-z0-9+.-]*://\\S+")
        private val PATH = Regex("(?<![A-Za-z0-9])/[\\w.@%+-]+(/[\\w.@%+-]*)*")
        private val APK_NAME = Regex("[^\\s/]+\\.(apk|apks|xapk|jks|keystore)", RegexOption.IGNORE_CASE)

        /** Keeps a value to one short line and removes anything that looks like a path, URI or file name. */
        fun clean(value: String): String =
            value.replace(URI, "<uri>").replace(PATH, "<path>").replace(APK_NAME, "<file>")
                .filter { !it.isISOControl() }.replace(' ', '_').take(160)
    }
}
