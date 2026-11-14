package com.amandhakar.apkforge

import com.amandhakar.apkforge.core.readUpTo
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amandhakar.apkforge.build.ApkChecks
import com.amandhakar.apkforge.build.ArscEditor
import com.amandhakar.apkforge.build.ValueEdit
import com.amandhakar.apkforge.build.ValueSlot
import com.amandhakar.apkforge.code.CodeIndex
import com.amandhakar.apkforge.code.PatchDex
import com.amandhakar.apkforge.code.SmaliClass
import com.amandhakar.apkforge.code.SmaliService
import com.amandhakar.apkforge.build.ArscPaths
import com.amandhakar.apkforge.build.IconFinder
import com.amandhakar.apkforge.build.IconPart
import com.amandhakar.apkforge.build.CloneResult
import com.amandhakar.apkforge.build.CloneSpec
import com.amandhakar.apkforge.build.PackageClone
import com.amandhakar.apkforge.build.ApkEdit
import com.amandhakar.apkforge.build.ApkRebuilder
import com.amandhakar.apkforge.build.ApkSigning
import com.amandhakar.apkforge.build.EditPlan
import com.amandhakar.apkforge.build.ForgeInstaller
import com.amandhakar.apkforge.build.ImageConverter
import com.amandhakar.apkforge.build.ImageKind
import com.amandhakar.apkforge.build.SigningKeys
import com.amandhakar.apkforge.build.VerifyReport
import com.amandhakar.apkforge.core.FileKind
import com.amandhakar.apkforge.core.FileSniffer
import com.amandhakar.apkforge.core.ResourceEntry
import com.amandhakar.apkforge.core.SplitKind
import com.amandhakar.apkforge.core.InspectionResult
import com.amandhakar.apkforge.core.EditabilityReport
import com.amandhakar.apkforge.core.Editability
import com.amandhakar.apkforge.core.ManifestResult
import com.amandhakar.apkforge.core.ResourceResult
import com.amandhakar.apkforge.core.SplitInfo
import com.amandhakar.apkforge.core.SplitPart
import com.amandhakar.apkforge.inspect.SplitSetReader
import com.amandhakar.apkforge.core.Splits
import com.amandhakar.apkforge.inspect.ArscReader
import com.amandhakar.apkforge.inspect.DexClassScanner
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
    /** Filled in a moment after the project opens (it reads all the code); null until then. */
    val editability: EditabilityReport? = null,
    /** The other parts of a split app that were copied (empty for a whole APK). */
    val parts: List<SplitPart> = emptyList(),
    /** The app's launcher icon as pictures to replace (adaptive icons have a foreground and a background). */
    val iconParts: List<IconPart> = emptyList(),
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
    val build: BuildUi = BuildUi(),
    /** Code patches of the open project: smali text by class. */
    val patches: Map<String, String> = emptyMap(),
    /** SHA-256 of Apkforge's signing key, null when there is no key yet. */
    val keyFingerprint: String? = null,
)

data class AccessProblem(val appLabel: String, val explanation: String)

/** A picture the user chose for one resource, already converted for every file that resource uses. */
class StagedImage(
    val resource: String,
    /** New bytes keyed by the file's name in the original APK. */
    val replacements: Map<String, ByteArray>,
    /** Files whose name changes too: a ".xml" drawing that becomes a ".png" picture (old name to new name). */
    val renames: Map<String, String> = emptyMap(),
)

class BuildOutcome(
    val apk: File, val report: VerifyReport, val sizeBytes: Long, val fingerprint: String,
    val changes: List<String>, val createdKey: Boolean,
    val newPackage: String? = null, val notes: List<String> = emptyList(),
)

/** A change to one resource value, ready to be built into the copy. */
class StagedValue(val resource: String, val config: String, val item: String?, val edit: ValueEdit, val shown: String) {
    val label: String get() = "$resource (${config}${item?.let { ", $it" } ?: ""})"
}

data class BuildUi(
    /** Install the copy beside the original under a new package name (the default). Off = keep the name, which can only replace the original. */
    val copyAsNew: Boolean = true,
    val suffix: String = "forge",
    val label: String = "",
    val staged: List<StagedImage> = emptyList(),
    /** Resource values (text, colours, numbers...) to change. */
    val values: List<StagedValue> = emptyList(),
    val running: String? = null,
    val outcome: BuildOutcome? = null,
    val problem: String? = null,
)

class ForgeViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val workspace = Workspace(File(app.filesDir, "projects"))
    private val log = DebugLog(File(app.filesDir, "debug.log"), isEnabled = { prefs.getBoolean(KEY_DEBUG, false) })
    private val inspector = ZipApkInspector()
    private val metadataReader = ApkMetadataReader(app)
    private val arscReader = ArscReader()
    private val dexScanner = DexClassScanner()
    private val splitReader = SplitSetReader()
    private val smali = SmaliService()
    private var codeIndexCache: Pair<String, CodeIndex>? = null
    private val keys = SigningKeys(File(app.filesDir, "keys"))
    private val rebuilder = ApkRebuilder()
    private val signing = ApkSigning()
    private val checks = ApkChecks()
    private val installedReader = InstalledAppsReader(app)
    private val installedImporter = InstalledApkImporter(workspace, MAX_IMPORT_BYTES)

    private val _state = MutableStateFlow(UiState(debugLogEnabled = prefs.getBoolean(KEY_DEBUG, false)))
    val state: StateFlow<UiState> = _state

    init {
        refreshProjects()
        refreshKey()
        ForgeInstaller.takeSaved(app)?.let { _state.value = _state.value.copy(message = it.message) }
        viewModelScope.launch {
            ForgeInstaller.outcomes.collect { r ->
                // only Android's INSTALL_* code is logged: the message can contain package names
                log.event("install_result", "status" to r.status, "code" to (r.token ?: "-"))
                ForgeInstaller.takeSaved(getApplication()) // shown now, so it need not be shown again at the next start
                _state.value = _state.value.copy(message = r.message)
            }
        }
    }

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

    fun open(id: String, justImported: Boolean = false, notice: String? = null) {
        _state.value = _state.value.copy(busy = "Reading APK…", message = null, build = BuildUi())
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
                val icon = try {
                    val table = (resources as? ResourceResult.Ok)?.table
                    if (table == null) emptyList() else java.util.zip.ZipFile(apk).use { z ->
                        val m = z.getEntry("AndroidManifest.xml")?.let { e -> z.getInputStream(e).use { it.readUpTo(8 * 1024 * 1024) } }
                        if (m == null) emptyList() else IconFinder.find(m, table) { p -> z.getEntry(p)?.takeIf { it.size < 1024 * 1024 }?.let { e -> z.getInputStream(e).use { it.readUpTo(1024 * 1024) } } }
                    }
                } catch (e: Exception) { emptyList() }
                ProjectDetails(project, inspection, meta, manifest, resources, split, parts = splitReader.read(workspace.splitFiles(id)), iconParts = icon)
            }
            if (details != null) computeEditability(details)
            _state.value = _state.value.copy(
                busy = null,
                details = details,
                build = BuildUi(label = (details?.metadata?.label ?: details?.project?.displayName ?: "App").take(50) + " (copy)"),
                patches = if (details != null) workspace.patches(id) else emptyMap(),
                message = when {
                    details == null -> "That project could not be found."
                    notice != null -> notice
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
            val result = withContext(Dispatchers.IO) { installedImporter.import(app.label, app.sourceDir, app.splitDirs, app.isSystem) }
            // The log records the outcome only, never which app it was.
            log.event("installed_copy", "result" to result.javaClass.simpleName, "why" to ((result as? InstalledImport.NotReadable)?.why ?: "-"), "splits" to ((result as? InstalledImport.Imported)?.let { "${it.splitsCopied}/${it.splitsCopied + it.splitsFailed}" } ?: "-"), "sdk" to Build.VERSION.SDK_INT)
            when (result) {
                is InstalledImport.Imported -> {
                    refreshProjects()
                    open(
                        result.project.id, justImported = true,
                        notice = if (result.splitsFailed > 0) "Android would not let Apkforge copy ${result.splitsFailed} of the app's ${result.splitsCopied + result.splitsFailed} other parts, so the set is incomplete." else null,
                    )
                }
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

    private fun computeEditability(d: ProjectDetails) {
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) {
                val apk = workspace.originalApk(d.project.id) ?: return@withContext null
                val ok = d.inspection as? InspectionResult.Ok ?: return@withContext null
                val scan = try { dexScanner.scan(apk) } catch (e: Exception) { null }
                val app = (d.manifest as? ManifestResult.Ok)?.applicationAttributes.orEmpty()
                val r = Editability.assess(
                    split = d.split,
                    debuggable = app["debuggable"] == "true",
                    hasNativeLibs = ok.summary.nativeAbis.isNotEmpty(),
                    packerLibs = Editability.packerLibraries(ok.entries.map { it.name }),
                    scan = scan,
                    systemApp = d.project.system,
                    splitsDeclared = d.project.declaredSplits,
                    splitsHeld = d.project.copiedSplits,
                )
                // Only generic categories are logged, never which app.
                log.event("editability", "verdict" to r.verdict, "markers" to (scan?.markers?.sorted()?.joinToString(",")?.ifEmpty { "none" } ?: "unscanned"), "skipped" to (scan?.skippedFiles ?: -1))
                r
            }
            val cur = _state.value.details
            if (report != null && cur != null && cur.project.id == d.project.id) {
                _state.value = _state.value.copy(details = cur.copy(editability = report))
            }
        }
    }

    fun close() { _state.value = _state.value.copy(details = null, build = BuildUi()) }

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

    // ---- signing key
    fun refreshKey() {
        viewModelScope.launch {
            val fp = withContext(Dispatchers.IO) { if (!keys.exists()) null else try { keys.load().fingerprint } catch (e: Exception) { "unreadable" } }
            _state.value = _state.value.copy(keyFingerprint = fp)
        }
    }

    fun createKey() {
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) { try { keys.create(); log.event("key_created"); null } catch (e: Exception) { "Could not create the key: ${e.message}" } }
            refreshKey(); if (msg != null) message(msg)
        }
    }

    /** Only called after the user confirms: apps signed with this key can no longer be updated by Apkforge. */
    fun deleteKey() {
        viewModelScope.launch { withContext(Dispatchers.IO) { keys.delete(); log.event("key_deleted") }; refreshKey() }
    }

    // ---- building a modified copy
    private fun setBuild(f: (BuildUi) -> BuildUi) { _state.value = _state.value.copy(build = f(_state.value.build)) }

    /** The reason this app cannot be rebuilt in this version, or null. */
    fun buildBlockedReason(d: ProjectDetails, copyAsNew: Boolean = true): String? = when {
        d.project.system && !copyAsNew -> "This is a system app: a modified copy can only be installed beside it, not over it. Turn on \"Install as a separate copy\"."
        d.split?.kind == SplitKind.BASE_NEEDS_SPLITS || d.split?.kind == SplitKind.SPLIT_PART -> "This is a split app. Rebuilding and re-signing a whole set is not available in this version."
        d.inspection !is InspectionResult.Ok -> "This file could not be read as an APK."
        (d.resources as? ResourceResult.Ok) == null -> "This APK has no readable resource table, so there are no images to pick from."
        else -> null
    }

    fun stageImage(entry: ResourceEntry, uri: Uri) {
        val d = _state.value.details ?: return
        setBuild { it.copy(problem = null, running = "Preparing the picture…", outcome = null) }
        viewModelScope.launch {
            val result: Pair<StagedImage?, String?> = withContext(Dispatchers.IO) {
                try {
                    val apk = workspace.originalApk(d.project.id) ?: return@withContext null to "The project's APK is missing."
                    val entries = (d.inspection as? InspectionResult.Ok)?.entries ?: return@withContext null to "This file could not be read."
                    val picked = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readUpTo(EditPlan.MAX_REPLACEMENT_BYTES + 1) }
                    if (picked == null || picked.isEmpty()) return@withContext null to "The picture could not be read."
                    if (picked.size > EditPlan.MAX_REPLACEMENT_BYTES) return@withContext null to "The picture is larger than ${EditPlan.MAX_REPLACEMENT_BYTES / (1024 * 1024)} MB."
                    val targets = entry.files.filter { f ->
                        !f.path.endsWith(".9.png") && !f.path.endsWith(".xml") && ImageKind.expectedFor(f.path).let { it == ImageKind.PNG || it == ImageKind.JPEG || it == ImageKind.WEBP }
                    }
                    // drawings (vectors, shapes, adaptive icons) become a picture of the same shape
                    val drawings = if (entry.type == "drawable" || entry.type == "mipmap") entry.files.filter { it.path.endsWith(".xml") } else emptyList()
                    if (targets.isEmpty() && drawings.isEmpty()) return@withContext null to "This resource has no picture files or drawings Apkforge can replace (it may be a stretchable image)."
                    val renames = LinkedHashMap<String, String>()
                    val out = LinkedHashMap<String, ByteArray>()
                    val cache = HashMap<Triple<ImageKind, Int, Int>, ByteArray?>()
                    java.util.zip.ZipFile(apk).use { zip ->
                        for (t in targets) {
                            val ze = zip.getEntry(t.path) ?: return@withContext null to "A file this resource uses is missing from the APK."
                            val original = zip.getInputStream(ze).use { it.readUpTo(EditPlan.MAX_REPLACEMENT_BYTES + 1) }
                            val kind = ImageKind.expectedFor(t.path)!!
                            val (w, h) = ImageConverter.size(original) ?: return@withContext null to "Could not read the size of an existing image."
                            out[t.path] = cache.getOrPut(Triple(kind, w, h)) { ImageConverter.convert(picked, kind, w, h) }
                                ?: return@withContext null to "That picture could not be read as an image."
                        }
                    }
                    if (drawings.isNotEmpty()) {
                        val (w, h) = drawingSizePx(d.project.id, apk, entry)
                        val png = ImageConverter.convert(picked, ImageKind.PNG, w, h) ?: return@withContext null to "That picture could not be read as an image."
                        for (f in drawings) { out[f.path] = png; renames[f.path] = f.path.removeSuffix("xml") + "png" }
                    }
                    for ((path, bytes) in out) {
                        val edit = if (path in renames) ApkEdit.ReplaceDrawingWithPicture(path, bytes) else ApkEdit.ReplaceFile(path, bytes)
                        EditPlan.problem(edit, entries)?.let { return@withContext null to it }
                    }
                    log.event("image_staged", "files" to out.size, "drawings" to renames.size)
                    StagedImage("${entry.type}/${entry.name}", out, renames) to null
                } catch (e: OutOfMemoryError) {
                    null to "That picture is too large to process on this phone."
                } catch (e: Exception) {
                    null to "Could not prepare the picture: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            val (staged, problem) = result
            setBuild { b -> b.copy(running = null, problem = problem, staged = if (staged != null) b.staged.filter { it.resource != staged.resource } + staged else b.staged) }
        }
    }

    fun setCopyAsNew(on: Boolean) { setBuild { it.copy(copyAsNew = on, problem = null, outcome = null) } }
    fun setSuffix(s: String) { setBuild { it.copy(suffix = s.take(24), problem = null, outcome = null) } }
    fun setLabel(s: String) { setBuild { it.copy(label = s.take(60), problem = null, outcome = null) } }

    // ---- editing resource values (text, colours, numbers...)
    private var arscCache: Pair<String, ByteArray>? = null

    private fun arscOf(projectId: String, apk: File): ByteArray {
        arscCache?.takeIf { it.first == projectId }?.let { return it.second }
        val bytes = java.util.zip.ZipFile(apk).use { z ->
            val e = z.getEntry("resources.arsc") ?: error("The APK has no resource table")
            z.getInputStream(e).use { it.readUpTo(96 * 1024 * 1024) }
        }
        arscCache = projectId to bytes
        return bytes
    }

    /** Every value of the resource, one per language or configuration (and per array or plural item). */
    suspend fun valueSlots(entry: ResourceEntry): List<ValueSlot> = withContext(Dispatchers.IO) {
        val d = _state.value.details ?: return@withContext emptyList()
        try { ArscEditor.slots(arscOf(d.project.id, workspace.originalApk(d.project.id) ?: return@withContext emptyList()), entry.id) }
        catch (e: Exception) { emptyList() } catch (e: OutOfMemoryError) { emptyList() }
    }

    /** "type/name" (as in @string/app_name) to a resource id, or null. */
    fun resourceId(typeSlashName: String): Int? =
        (_state.value.details?.resources as? ResourceResult.Ok)?.table?.entries?.values?.firstOrNull { "${it.type}/${it.name}" == typeSlashName }?.id

    fun resourceName(id: Int): String? = (_state.value.details?.resources as? ResourceResult.Ok)?.table?.nameOf(id)

    /** Adds or replaces the planned changes for the given values (one per value). */
    fun stageValues(changes: List<StagedValue>) {
        log.event("values_staged", "count" to changes.size)
        setBuild { b -> b.copy(values = b.values.filter { old -> changes.none { it.edit.offset == old.edit.offset } } + changes, outcome = null, problem = null) }
    }

    fun unstageValue(offset: Int) { setBuild { b -> b.copy(values = b.values.filter { it.edit.offset != offset }, outcome = null) } }

    fun unstage(resource: String) { setBuild { b -> b.copy(staged = b.staged.filter { it.resource != resource }, outcome = null) } }

    fun buildCopy() {
        val d = _state.value.details ?: return
        val ui = _state.value.build
        val staged = ui.staged
        val oldPackage = d.metadata?.packageName
        val patches = _state.value.patches
        val values = ui.values
        if (staged.isEmpty() && values.isEmpty() && !ui.copyAsNew && patches.isEmpty()) { setBuild { it.copy(problem = "There is nothing to change yet: pick an image, change a resource, patch some code, or install as a separate copy.") }; return }
        buildBlockedReason(d, ui.copyAsNew)?.let { r -> setBuild { it.copy(problem = r) }; return }
        if (ui.copyAsNew) {
            val p = PackageClone.suffixProblem(ui.suffix) ?: PackageClone.labelProblem(ui.label)
            if (p != null || oldPackage == null) { setBuild { it.copy(problem = p ?: "The app's package name could not be read.") }; return }
        }
        setBuild { it.copy(running = "Building, signing and checking…", problem = null, outcome = null) }
        viewModelScope.launch {
            val result: Pair<BuildOutcome?, String?> = withContext(Dispatchers.IO) {
                val unsigned = workspace.outputFile(d.project.id, "forged-unsigned.apk")
                val signed = workspace.outputFile(d.project.id, "forged.apk")
                try {
                    val apk = workspace.originalApk(d.project.id) ?: return@withContext null to "The project's APK is missing."
                    val entries = (d.inspection as InspectionResult.Ok).entries
                    val renames = LinkedHashMap<String, String>().also { m -> staged.forEach { m.putAll(it.renames) } }
                    val edits: List<ApkEdit> = staged.flatMap { s -> s.replacements.map { (p, b) -> if (p in renames) ApkEdit.ReplaceDrawingWithPicture(p, b) else ApkEdit.ReplaceFile(p, b) } }
                    for (e in edits) EditPlan.problem(e, entries)?.let { return@withContext null to it }
                    val replacements = LinkedHashMap<String, ByteArray>()
                    edits.forEach { replacements[it.entryName] = if (it is ApkEdit.ReplaceFile) it.bytes else (it as ApkEdit.ReplaceDrawingWithPicture).bytes }
                    // the resource table: value edits first (they aim at positions in the original), then the new ".png" names
                    var patchedArsc: ByteArray? = null
                    if (values.isNotEmpty() || renames.isNotEmpty()) {
                        var arsc = arscOf(d.project.id, apk)
                        if (values.isNotEmpty()) arsc = ArscEditor.apply(arsc, values.map { it.edit })
                        if (renames.isNotEmpty()) arsc = ArscPaths.xmlToPng(arsc, renames.keys)
                        patchedArsc = arsc
                    }
                    if (patchedArsc != null) replacements["resources.arsc"] = patchedArsc
                    var clone: CloneResult.Ok? = null
                    if (ui.copyAsNew) {
                        when (val c = PackageClone.prepare(apk, CloneSpec(oldPackage!!, ui.suffix, ui.label), arscOverride = patchedArsc)) {
                            is CloneResult.Unsupported -> return@withContext null to c.reason
                            is CloneResult.Ok -> { clone = c; replacements.putAll(c.replacements) }
                        }
                    }
                    // code patches: a new first dex file, with the existing ones moved up by one
                    var additions: Map<String, ByteArray> = emptyMap()
                    var patchedClasses = 0
                    if (patches.isNotEmpty()) {
                        val (plan, why) = PatchDex.plan(entries.map { it.name })
                        if (plan == null) return@withContext null to (why ?: "The code cannot be patched.")
                        val a = smali.assemble(patches, patchApi())
                        if (a.dex == null) return@withContext null to ("A code patch does not assemble: " + a.errors.firstOrNull())
                        renames.putAll(plan.renames)
                        additions = mapOf(plan.newDexName to a.dex)
                        patchedClasses = patches.size
                    }
                    rebuilder.rebuild(apk, unsigned, replacements, renames, additions)
                    val hadKey = keys.exists()
                    val identity = keys.loadOrCreate()
                    signing.sign(unsigned, signed, identity, d.metadata?.minSdk ?: 23)
                    val report = checks.verify(signed, d.metadata?.minSdk)
                    log.event("build", "files" to edits.size, "verified" to report.verified, "v2" to report.v2, "v3" to report.v3, "bytes" to signed.length(), "copy" to ui.copyAsNew, "values" to values.size)
                    val changes = EditPlan.summary(edits, entries) + values.map { "Change ${it.label}: ${it.shown.take(60)}" } + (if (patchedClasses > 0) listOf("Patch the code of $patchedClasses class${if (patchedClasses == 1) "" else "es"} (a new first code file)") else emptyList()) + (if (clone != null) listOf("Install as a separate app: ${oldPackage}.${ui.suffix}, named \"${ui.label}\"") else emptyList())
                    BuildOutcome(signed, report, signed.length(), identity.fingerprint, changes, createdKey = !hadKey, newPackage = clone?.let { "$oldPackage.${ui.suffix}" }, notes = clone?.notes.orEmpty()) to null
                } catch (e: Exception) {
                    signed.delete()
                    log.event("build_failed", "reason" to e.javaClass.simpleName)
                    null to (e.message ?: e.javaClass.simpleName)
                } finally {
                    unsigned.delete()
                }
            }
            val (outcome, problem) = result
            setBuild { it.copy(running = null, outcome = outcome, problem = problem) }
            refreshKey()
        }
    }

    fun exportForged(dest: Uri) {
        val outcome = _state.value.build.outcome ?: return
        setBuild { it.copy(running = "Exporting…") }
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    getApplication<Application>().contentResolver.openOutputStream(dest)?.use { o -> outcome.apk.inputStream().use { it.copyTo(o) } } ?: error("Android would not open the destination")
                    log.event("forged_exported"); "Exported the modified APK."
                } catch (e: Exception) { "Export failed: ${e.message ?: e.javaClass.simpleName}" }
            }
            setBuild { it.copy(running = null) }; message(msg)
        }
    }

    /** Starts Android's installer for the modified copy. Android shows its own confirmation. */
    fun installForged() {
        val outcome = _state.value.build.outcome ?: return
        if (!outcome.report.verified) { message("The signature check did not pass, so Apkforge will not install this."); return }
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) { try { ForgeInstaller.start(getApplication(), outcome.apk); log.event("install_started"); null } catch (e: Exception) { e.message ?: e.javaClass.simpleName } }
            if (err != null) message("Could not start the install: $err")
        }
    }

    // ---- the app's code: browse as smali, edit, patch
    /** Every class of the open project's code, or null when it cannot be read. Cached per project. */
    suspend fun codeIndex(): CodeIndex? = withContext(Dispatchers.IO) {
        val d = _state.value.details ?: return@withContext null
        codeIndexCache?.takeIf { it.first == d.project.id }?.let { return@withContext it.second }
        try {
            val apk = workspace.originalApk(d.project.id) ?: return@withContext null
            val idx = smali.index(apk)
            log.event("code_index", "classes" to idx.classes.size, "dex" to idx.dexEntries.size, "unreadable" to idx.unreadable.size)
            codeIndexCache = d.project.id to idx
            idx
        } catch (e: Exception) { log.event("code_index_failed", "reason" to e.javaClass.simpleName); null } catch (e: OutOfMemoryError) { null }
    }

    /** The class as smali (text, error): the patched text when the class is patched, otherwise the original code. */
    suspend fun smaliOf(cls: SmaliClass, original: Boolean = false): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val d = _state.value.details ?: return@withContext null to "No project is open."
        if (!original) _state.value.patches[cls.descriptor]?.let { return@withContext it to null }
        try {
            val apk = workspace.originalApk(d.project.id) ?: return@withContext null to "The project's APK is missing."
            smali.disassemble(apk, cls) to null
        } catch (e: Exception) { null to (e.message ?: e.javaClass.simpleName) } catch (e: OutOfMemoryError) { null to "This class is too large to open on this phone." }
    }

    private fun patchApi(): Int = PatchDex.apiLevel(_state.value.details?.metadata?.minSdk)

    /** Assembles [text] without saving it. Empty list = it assembles. */
    suspend fun checkSmali(descriptor: String, text: String): List<String> = withContext(Dispatchers.IO) {
        val r = smali.assemble(mapOf(descriptor to text), patchApi())
        if (r.dex != null) emptyList() else r.errors
    }

    /** Saves the patch if it assembles. Returns the problems (empty = saved). */
    suspend fun savePatch(descriptor: String, text: String): List<String> = withContext(Dispatchers.IO) {
        val d = _state.value.details ?: return@withContext listOf("No project is open.")
        if (text.length > Workspace.MAX_PATCH_CHARS) return@withContext listOf("The class is too large to patch (over ${Workspace.MAX_PATCH_CHARS / 1000} thousand characters).")
        val r = smali.assemble(mapOf(descriptor to text), patchApi())
        if (r.dex == null) { log.event("patch_rejected", "errors" to r.errors.size); return@withContext r.errors }
        try { workspace.savePatch(d.project.id, descriptor, text) } catch (e: Exception) { return@withContext listOf(e.message ?: "Could not save the patch.") }
        log.event("patch_saved", "classes" to workspace.patches(d.project.id).size)
        _state.value = _state.value.copy(patches = workspace.patches(d.project.id), build = _state.value.build.copy(outcome = null))
        emptyList()
    }

    fun removePatch(descriptor: String) {
        val d = _state.value.details ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { workspace.removePatch(d.project.id, descriptor); log.event("patch_removed") }
            _state.value = _state.value.copy(patches = workspace.patches(d.project.id), build = _state.value.build.copy(outcome = null))
        }
    }

    // ---- looking inside the APK: previews and the file viewer
    private val thumbs = android.util.LruCache<String, Bitmap>(80)
    private var resourcesFor: Pair<String, android.content.res.Resources?>? = null

    /** What opening [entryName] shows. Looks at the file's bytes, not its name, to tell images, binary XML, text and other data apart. */
    suspend fun openFile(entryName: String): FileView = withContext(Dispatchers.IO) {
        val d = _state.value.details ?: return@withContext FileView.Failed("No project is open.")
        val apk = workspace.originalApk(d.project.id) ?: return@withContext FileView.Failed("The project's APK is missing.")
        try {
            java.util.zip.ZipFile(apk).use { zip ->
                val ze = zip.getEntry(entryName) ?: return@withContext FileView.Failed("That file is not in the APK.")
                if (ze.size > VIEW_LIMIT) return@withContext FileView.Failed("This file is ${formatSize(ze.size)}; Apkforge opens files up to ${VIEW_LIMIT / (1024 * 1024)} MB.")
                val bytes = zip.getInputStream(ze).use { it.readUpTo(VIEW_LIMIT + 1) }
                val size = bytes.size.toLong()
                when (FileSniffer.kind(bytes)) {
                    FileKind.IMAGE -> decodeForViewing(bytes)
                    FileKind.COMPILED_XML -> {
                        val table = (d.resources as? ResourceResult.Ok)?.table
                        try { FileView.Xml(AxmlDecoder(resolver = table?.let { t -> { id: Int -> t.nameOf(id) } }).decode(bytes), compiled = true, bytes = size) }
                        catch (e: com.amandhakar.apkforge.inspect.AxmlException) { FileView.Binary(FileSniffer.hexDump(bytes), "This looks like Android's binary XML but could not be decoded (${e.message}). The raw bytes are shown instead.", size) }
                    }
                    FileKind.TEXT_XML -> FileView.Xml(String(bytes, Charsets.UTF_8).take(TEXT_LIMIT), compiled = false, bytes = size)
                    FileKind.TEXT -> String(bytes, Charsets.UTF_8).let { FileView.Text(it.take(TEXT_LIMIT), it.length > TEXT_LIMIT, size) }
                    FileKind.BINARY -> FileView.Binary(FileSniffer.hexDump(bytes), if (bytes.isEmpty()) "The file is empty." else null, size)
                }
            }
        } catch (e: OutOfMemoryError) {
            FileView.Failed("This file is too large to open on this phone.")
        } catch (e: Exception) {
            FileView.Failed("Could not open the file: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun decodeForViewing(bytes: ByteArray): FileView {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return FileView.Binary(FileSniffer.hexDump(bytes), "This looks like an image but Android could not decode it.", bytes.size.toLong())
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 2048) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return FileView.Failed("Android could not decode this image.")
        return FileView.Image(bmp, o.outWidth, o.outHeight, ImageKind.sniff(bytes).name, bytes.size.toLong())
    }

    /** A small picture of an image file, or null. Cached. */
    suspend fun thumbnailOfFile(entryName: String, px: Int): Bitmap? = withContext(Dispatchers.IO) {
        val d = _state.value.details ?: return@withContext null
        val key = "${d.project.id}|$entryName|$px"
        thumbs.get(key)?.let { return@withContext it }
        try {
            val apk = workspace.originalApk(d.project.id) ?: return@withContext null
            val bytes = java.util.zip.ZipFile(apk).use { zip ->
                val ze = zip.getEntry(entryName) ?: return@withContext null
                if (ze.size > THUMB_LIMIT) return@withContext null
                zip.getInputStream(ze).use { it.readUpTo(THUMB_LIMIT + 1) }
            }
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth <= 0) return@withContext null
            var sample = 1
            while (minOf(o.outWidth, o.outHeight) / (sample * 2) >= px) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.also { thumbs.put(key, it) }
        } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
    }

    /**
     * A small picture of a drawable or mipmap resource. A plain image file is decoded directly (the density
     * closest to xhdpi). Anything else (vectors, shapes, adaptive icons) is drawn by Android itself from the
     * APK's resources, using the public API; resources that need the app's theme cannot be drawn and give null.
     */
    suspend fun thumbnailOfResource(e: ResourceEntry, px: Int): Bitmap? {
        val files = e.files.filter { f -> ImageKind.expectedFor(f.path).let { it == ImageKind.PNG || it == ImageKind.JPEG || it == ImageKind.WEBP } }
        if (files.isNotEmpty()) return thumbnailOfFile(bestDensity(files.map { it.density to it.path }), px)
        if (e.type != "drawable" && e.type != "mipmap") return null
        return withContext(Dispatchers.IO) {
            val d = _state.value.details ?: return@withContext null
            val key = "${d.project.id}|res|${e.id}|$px"
            thumbs.get(key)?.let { return@withContext it }
            try {
                val apk = workspace.originalApk(d.project.id) ?: return@withContext null
                val res = resourcesOf(d.project.id, apk) ?: return@withContext null
                val drawable = androidx.core.content.res.ResourcesCompat.getDrawable(res, e.id, null) ?: return@withContext null
                val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, px, px)
                drawable.draw(android.graphics.Canvas(bmp))
                thumbs.put(key, bmp); bmp
            } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
        }
    }

    /**
     * The size a picture for a drawing should have. A vector or shape has a size in dp; the picture gets four
     * pixels per dp, which is sharp on most screens. When the size is unknown (shapes often have none) it is 432 px.
     */
    private fun drawingSizePx(projectId: String, apk: File, e: ResourceEntry): Pair<Int, Int> = try {
        val res = resourcesOf(projectId, apk)
        val d = res?.let { androidx.core.content.res.ResourcesCompat.getDrawable(it, e.id, null) }
        val dpi = res?.displayMetrics?.densityDpi ?: 160
        val w = d?.intrinsicWidth ?: -1; val h = d?.intrinsicHeight ?: -1
        if (w > 0 && h > 0) (w * 160f / dpi * 4).toInt().coerceIn(48, 1024) to (h * 160f / dpi * 4).toInt().coerceIn(48, 1024) else 432 to 432
    } catch (x: Exception) { 432 to 432 }

    private fun resourcesOf(projectId: String, apk: File): android.content.res.Resources? {
        resourcesFor?.takeIf { it.first == projectId }?.let { return it.second }
        val pm = getApplication<Application>().packageManager
        val res = try {
            val app = pm.getPackageArchiveInfo(apk.path, 0)?.applicationInfo?.also { it.sourceDir = apk.path; it.publicSourceDir = apk.path }
            app?.let { pm.getResourcesForApplication(it) }
        } catch (e: Exception) { null }
        resourcesFor = projectId to res
        return res
    }

    private fun bestDensity(files: List<Pair<String?, String>>): String {
        fun dpi(label: String?): Int = when (label) {
            null -> 160; "ldpi" -> 120; "mdpi" -> 160; "tvdpi" -> 213; "hdpi" -> 240; "xhdpi" -> 320; "xxhdpi" -> 480; "xxxhdpi" -> 640
            "anydpi", "nodpi", "other" -> 320
            else -> label.removeSuffix("dpi").toIntOrNull() ?: 320
        }
        return files.minBy { kotlin.math.abs(dpi(it.first) - 320) }.second
    }

    fun message(text: String?) { _state.value = _state.value.copy(message = text) }

    private fun displayName(uri: Uri): String? =
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    companion object {
        private const val VIEW_LIMIT = 16 * 1024 * 1024
        private const val THUMB_LIMIT = 4 * 1024 * 1024
        private const val TEXT_LIMIT = 400_000
        private fun formatSize(b: Long) = com.amandhakar.apkforge.ui.formatBytes(b)
        private const val KEY_DEBUG = "debug_log_enabled"
        const val MAX_IMPORT_BYTES = 1024L * 1024 * 1024
    }
}
