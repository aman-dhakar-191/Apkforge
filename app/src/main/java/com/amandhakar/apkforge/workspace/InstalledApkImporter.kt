package com.amandhakar.apkforge.workspace

import com.amandhakar.apkforge.core.InstalledApps
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

sealed interface InstalledImport {
    data class Imported(val project: Project, val splitsCopied: Int = 0, val splitsFailed: Int = 0) : InstalledImport
    /** Android did not let this app read the file (or it is gone). Not an error in the app. */
    data class NotReadable(val why: String) : InstalledImport
    data object TooLarge : InstalledImport
    data object NoPath : InstalledImport
}

/** Copies an installed app's APK into the workspace, only when Android allows reading it. */
class InstalledApkImporter(
    private val workspace: Workspace,
    private val maxBytes: Long,
    /** Limit for all the other parts of one app together. */
    private val maxSplitsTotalBytes: Long = 2L * maxBytes,
) {
    fun import(displayName: String, sourceDir: String?, splitDirs: List<String> = emptyList(), isSystem: Boolean = false): InstalledImport {
        if (!InstalledApps.isCopyablePath(sourceDir)) return InstalledImport.NoPath
        val base = try {
            File(sourceDir!!).inputStream().use { workspace.importApk(displayName, it, maxBytes) }
        } catch (_: ImportTooLargeException) {
            return InstalledImport.TooLarge
        } catch (e: SecurityException) {
            return InstalledImport.NotReadable("denied")
        } catch (e: FileNotFoundException) {
            // Android reports "Permission denied" as a FileNotFoundException too.
            return InstalledImport.NotReadable(if (e.message?.contains("denied", true) == true) "denied" else "missing")
        } catch (e: IOException) {
            return InstalledImport.NotReadable("io")
        }

        // The other parts are best effort: the base is already stored, so one refused part does not undo it.
        var copied = 0
        var failed = 0
        var remaining = maxSplitsTotalBytes
        splitDirs.forEachIndexed { i, path ->
            if (!InstalledApps.isCopyablePath(path) || remaining <= 0) { failed++; return@forEachIndexed }
            try {
                val before = workspace.splitFiles(base.id).sumOf { it.length() }
                File(path).inputStream().use { workspace.addSplit(base.id, path, i + 1, it, minOf(maxBytes, remaining)) }
                remaining -= workspace.splitFiles(base.id).sumOf { it.length() } - before
                copied++
            } catch (_: Exception) {
                failed++ // not readable, too large, or storage error
            }
        }
        val project = base.copy(system = isSystem, declaredSplits = splitDirs.size, copiedSplits = copied)
        workspace.save(project)
        return InstalledImport.Imported(project, copied, failed)
    }
}
