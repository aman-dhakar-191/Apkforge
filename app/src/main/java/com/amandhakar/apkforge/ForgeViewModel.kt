package com.amandhakar.apkforge

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amandhakar.apkforge.core.InspectionResult
import com.amandhakar.apkforge.core.ManifestResult
import com.amandhakar.apkforge.core.ResourceResult
import com.amandhakar.apkforge.core.SplitInfo
import com.amandhakar.apkforge.core.Splits
import com.amandhakar.apkforge.inspect.ArscReader
import com.amandhakar.apkforge.inspect.AxmlDecoder
import com.amandhakar.apkforge.core.InstalledApp
import com.amandhakar.apkforge.inspect.InstalledAppsReader
import com.amandhakar.apkforge.workspace.InstalledApkImporter
import com.amandhakar.apkforge.workspace.InstalledImport
import com.amandhakar.apkforge.inspect.ApkMetadata
import com.amandhakar.apkforge.inspect.ApkMetadataReader
import com.amandhakar.apkforge.inspect.ZipApkInspector
import com.amandhakar.apkforge.log.DebugLog
import com.amandhakar.apkforge.workspace.ImportTooLargeException
import com.amandhakar.apkforge.workspace.Project
import com.amandhakar.apkforge.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ProjectDetails(
    val project: Project,
    val inspection: InspectionResult,
    val metadata: ApkMetadata?,
    val manifest: ManifestResult? = null,
    val resources: ResourceResult? = null,
    /** From the manifest; null when the manifest could not be read. */
    val split: SplitInfo? = null,
)

data class UiState(
    val projects: List<Project> = emptyList(),
    val details: ProjectDetails? = null,
    val busy: String? = null,
    val message: String? = null,
    val debugLogEnabled: Boolean = false,
    val logText: String = "",
    /** Apps Android lets us see; null until loaded. */
    val installed: List<InstalledApp>? = null,
    /** Set when an installed app's APK could not be copied: shows the explanation and the file-picker fallback. */
    val accessProblem: AccessProblem? = null,
)

data class AccessProblem(val appLabel: String, val explanation: String)

class ForgeViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val workspace = Workspace(File(app.filesDir, "projects"))
    private val log = DebugLog(File(app.filesDir, "debug.log"), isEnabled = { prefs.getBoolean(KEY_DEBUG, false) })
    private val inspector = ZipApkInspector()
    private val metadataReader = ApkMetadataReader(app)
    private val arscReader = ArscReader()
    private val installedReader = InstalledAppsReader(app)
    private val installedImporter = InstalledApkImporter(workspace, MAX_IMPORT_BYTES)

    private val _state = MutableStateFlow(UiState(debugLogEnabled = prefs.getBoolean(KEY_DEBUG, false)))
    val state: StateFlow<UiState> = _state

    init { refreshProjects() }

    fun refreshProjects() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { workspace.list() }
            _state.value = _state.value.copy(projects = list)
        }
    }

    fun importApk(uri: Uri) {
        _state.value = _state.value.copy(busy = "Importing APK…", message = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val name = displayName(uri) ?: "APK"
                    val project = getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        workspace.importApk(name, it, MAX_IMPORT_BYTES)
                    } ?: error("Android would not open that file")
                    log.event("import_ok", "bytes" to project.sizeBytes)
                    Result.success(project)
                } catch (e: ImportTooLargeException) {
                    log.event("import_failed", "reason" to "too_large")
                    Result.failure(IllegalStateException("That file is larger than ${MAX_IMPORT_BYTES / (1024 * 1024)} MB, which is the limit."))
                } catch (e: Exception) {
                    log.event("import_failed", "reason" to e.javaClass.simpleName)
                    Result.failure(IllegalStateException("Could not import the file: ${e.message ?: e.javaClass.simpleName}"))
                }
            }
            result.onSuccess { open(it.id, justImported = true) }
            result.onFailure { _state.value = _state.value.copy(busy = null, message = it.message) }
            refreshProjects()
        }
    }

    fun open(id: String, justImported: Boolean = false) {
        _state.value = _state.value.copy(busy = "Reading APK…", message = null)
        viewModelScope.launch {
            var duplicate = false
            val details = withContext(Dispatchers.IO) {
                val project = workspace.get(id) ?: return@withContext null
                if (justImported) duplicate = workspace.hasProbableDuplicate(project)
                val apk = workspace.originalApk(id) ?: return@withContext null
                val inspection = inspector.inspect(apk)
                val meta = if (inspection is InspectionResult.Ok) metadataReader.read(apk) else null
                val ok = inspection as? InspectionResult.Ok
                val resources = if (ok != null && ok.summary.hasResourceTable) arscReader.read(apk) else null
                val resolver: ((Int) -> String?)? = (resources as? ResourceResult.Ok)?.table?.let { t -> { id: Int -> t.nameOf(id) } }
                val manifest = if (ok != null && ok.summary.hasManifest) AxmlDecoder(resolver = resolver).decode(apk) else null
                val split = (manifest as? ManifestResult.Ok)?.let { Splits.classify(it.rootAttributes) }
                log.event(
                    "inspect",
                    "result" to (if (inspection is InspectionResult.Ok) "ok" else (inspection as InspectionResult.Rejected).reason),
                    "entries" to ((inspection as? InspectionResult.Ok)?.summary?.entryCount ?: 0),
                    "metadata" to (meta != null),
                    "manifest" to (if (manifest is ManifestResult.Ok) "ok" else if (manifest == null) "none" else "failed"),
                    "resources" to (if (resources is ResourceResult.Ok) resources.table.entries.size else if (resources == null) "none" else "failed"),
                    "split" to (split?.kind ?: "unknown"),
                )
                ProjectDetails(project, inspection, meta, manifest, resources, split)
            }
            _state.value = _state.value.copy(
                busy = null,
                details = details,
                message = when {
                    details == null -> "That project could not be found."
                    justImported && duplicate -> "You already had a project from a file with this name and size, so this is probably the same APK again. You can delete one."
                    else -> null
                },
            )
        }
    }

    fun loadInstalled() {
        _state.value = _state.value.copy(busy = "Reading installed apps…")
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                try { installedReader.read() } catch (e: Exception) { log.event("installed_list_failed", "reason" to e.javaClass.simpleName); emptyList() }
            }
            log.event("installed_list", "count" to apps.size, "sdk" to Build.VERSION.SDK_INT)
            _state.value = _state.value.copy(busy = null, installed = apps)
        }
    }

    /** Tries to copy the app's APK. Android decides whether that is allowed; a refusal is explained, not worked around. */
    fun importInstalled(app: InstalledApp) {
        _state.value = _state.value.copy(busy = "Copying ${app.label}…", message = null, accessProblem = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { installedImporter.import(app.label, app.sourceDir) }
            // The log records the outcome only, never which app it was.
            log.event("installed_copy", "result" to result.javaClass.simpleName, "why" to ((result as? InstalledImport.NotReadable)?.why ?: "-"), "sdk" to Build.VERSION.SDK_INT)
            when (result) {
                is InstalledImport.Imported -> { refreshProjects(); open(result.project.id, justImported = true) }
                is InstalledImport.TooLarge -> _state.value = _state.value.copy(busy = null, message = "That APK is larger than ${MAX_IMPORT_BYTES / (1024 * 1024)} MB, which is the limit.")
                is InstalledImport.NoPath -> _state.value = _state.value.copy(busy = null, accessProblem = AccessProblem(app.label, "Android did not give a usable file location for this app."))
                is InstalledImport.NotReadable -> _state.value = _state.value.copy(
                    busy = null,
                    accessProblem = AccessProblem(
                        app.label,
                        if (result.why == "missing") "The APK file is no longer where Android said it was."
                        else "Android does not let Apkforge read this app's APK on this phone. This is a platform rule, and Apkforge does not try to get around it.",
                    ),
                )
            }
        }
    }

    fun dismissAccessProblem() { _state.value = _state.value.copy(accessProblem = null) }

    fun close() { _state.value = _state.value.copy(details = null) }

    fun delete(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { workspace.delete(id); log.event("project_deleted") }
            if (_state.value.details?.project?.id == id) close()
            refreshProjects()
        }
    }

    fun exportOriginal(id: String, dest: Uri) {
        _state.value = _state.value.copy(busy = "Exporting…")
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    getApplication<Application>().contentResolver.openOutputStream(dest)?.use { workspace.exportOriginal(id, it) }
                        ?: error("Android would not open the destination")
                    log.event("export_ok")
                    "Exported the original, unchanged APK."
                } catch (e: Exception) {
                    log.event("export_failed", "reason" to e.javaClass.simpleName)
                    "Export failed: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            _state.value = _state.value.copy(busy = null, message = msg)
        }
    }

    fun setDebugLog(on: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG, on).apply()
        if (on) log.event("debug_log_enabled", "sdk" to Build.VERSION.SDK_INT, "app" to BuildConfig.VERSION_NAME)
        _state.value = _state.value.copy(debugLogEnabled = on)
        refreshLog()
    }

    fun refreshLog() {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { log.read() }
            _state.value = _state.value.copy(logText = text)
        }
    }

    fun clearLog() {
        viewModelScope.launch { withContext(Dispatchers.IO) { log.clear() }; refreshLog() }
    }

    /** The log with a short header (app and Android version, device model), or null when empty. */
    private fun logWithHeader(): String? {
        val text = log.read()
        if (text.isBlank()) return null
        return "Apkforge ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n\n" + text
    }

    /** A copy of the log for the user to share on purpose. Null when empty. */
    fun logForSharing(): File? {
        val content = logWithHeader() ?: return null
        val dir = File(getApplication<Application>().cacheDir, "shared_logs").apply { mkdirs() }
        return File(dir, "apkforge-debug-log.txt").also { it.writeText(content) }
    }

    /** Saves the log to a file the user chose. */
    fun saveLog(dest: Uri) {
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) {
                val content = logWithHeader() ?: return@withContext "The log is empty."
                try {
                    getApplication<Application>().contentResolver.openOutputStream(dest)?.use { it.write(content.toByteArray()) }
                        ?: error("Android would not open the destination")
                    "Log saved."
                } catch (e: Exception) {
                    "Could not save the log: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            _state.value = _state.value.copy(message = msg)
        }
    }

    fun message(text: String?) { _state.value = _state.value.copy(message = text) }

    private fun displayName(uri: Uri): String? =
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    companion object {
        private const val KEY_DEBUG = "debug_log_enabled"
        const val MAX_IMPORT_BYTES = 1024L * 1024 * 1024
    }
}
