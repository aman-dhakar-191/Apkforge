package com.amandhakar.apkforge.workspace

import com.amandhakar.apkforge.core.InstalledApps
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

sealed interface InstalledImport {
    data class Imported(val project: Project) : InstalledImport
    /** Android did not let this app read the file (or it is gone). Not an error in the app. */
    data class NotReadable(val why: String) : InstalledImport
    data object TooLarge : InstalledImport
    data object NoPath : InstalledImport
}

/** Copies an installed app's APK into the workspace, only when Android allows reading it. */
class InstalledApkImporter(private val workspace: Workspace, private val maxBytes: Long) {

    fun import(displayName: String, sourceDir: String?): InstalledImport {
        if (!InstalledApps.isCopyablePath(sourceDir)) return InstalledImport.NoPath
        val file = File(sourceDir!!)
        return try {
            file.inputStream().use { InstalledImport.Imported(workspace.importApk(displayName, it, maxBytes)) }
        } catch (_: ImportTooLargeException) {
            InstalledImport.TooLarge
        } catch (e: SecurityException) {
            InstalledImport.NotReadable("denied")
        } catch (e: FileNotFoundException) {
            // Android reports "Permission denied" as a FileNotFoundException too.
            InstalledImport.NotReadable(if (e.message?.contains("denied", true) == true) "denied" else "missing")
        } catch (e: IOException) {
            InstalledImport.NotReadable("io")
        }
    }
}
