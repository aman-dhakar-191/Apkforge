package com.amandhakar.apkforge

import com.amandhakar.apkforge.core.readUpTo
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amandhakar.apkforge.build.ApkChecks
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
    /** SHA-256 of Apkforge's signing key, null when there is no key yet. */
    val keyFingerprint: String? = null,
)

data class AccessProblem(val appLabel: String, val explanation: String)

/** A picture the user chose for one resource, already converted for every file that resource uses. */
class StagedImage(val resource: String, val replacements: Map<String, ByteArray>)

class BuildOutcome(
    val apk: File, val report: VerifyReport, val sizeBytes: Long, val fingerprint: String,
    val changes: List<String>, val createdKey: Boolean,
    val newPackage: String? = null, val notes: List<String> = emptyList(),
)

data class BuildUi(
    /** Install the copy beside the original under a new package name (the default). Off = keep the name, which can only replace the original. */
    val copyAsNew: Boolean = true,
    val suffix: String = "forge",
    val label: String = "",
    val staged: List<StagedImage> = emptyList(),
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
        viewModelScope.launch { ForgeInstaller.results.collect { msg -> _state.value = _state.value.copy(message = msg) } }
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
                ProjectDetails(project, inspection, meta, manifest, resources, split, parts = splitReader.read(workspace.splitFiles(id)))
            }
            if (details != null) computeEditability(details)
            _state.value = _state.value.copy(
                busy = null,
                details = details,
                build = BuildUi(label = (details?.metadata?.label ?: details?.project?.displayName ?: "App").take(50) + " (copy)"),
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
                    if (targets.isEmpty()) return@withContext null to "This resource has no plain image files Apkforge can replace (it may be a stretchable image or a vector)."
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
                    for ((path, bytes) in out) EditPlan.problem(ApkEdit.ReplaceFile(path, bytes), entries)?.let { return@withContext null to it }
                    log.event("image_staged", "files" to out.size)
                    StagedImage("${entry.type}/${entry.name}", out) to null
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

    fun unstage(resource: String) { setBuild { b -> b.copy(staged = b.staged.filter { it.resource != resource }, outcome = null) } }

    fun buildCopy() {
        val d = _state.value.details ?: return
        val ui = _state.value.build
        val staged = ui.staged
        val oldPackage = d.metadata?.packageName
        if (staged.isEmpty() && !ui.copyAsNew) { setBuild { it.copy(problem = "There is nothing to change yet: pick an image, or install as a separate copy.") }; return }
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
                    val edits = staged.flatMap { s -> s.replacements.map { (p, b) -> ApkEdit.ReplaceFile(p, b) } }
                    for (e in edits) EditPlan.problem(e, entries)?.let { return@withContext null to it }
                    val replacements = LinkedHashMap<String, ByteArray>()
                    edits.forEach { replacements[it.entryName] = (it as ApkEdit.ReplaceFile).bytes }
                    var clone: CloneResult.Ok? = null
                    if (ui.copyAsNew) {
                        when (val c = PackageClone.prepare(apk, CloneSpec(oldPackage!!, ui.suffix, ui.label))) {
                            is CloneResult.Unsupported -> return@withContext null to c.reason
                            is CloneResult.Ok -> { clone = c; replacements.putAll(c.replacements) }
                        }
                    }
                    rebuilder.rebuild(apk, unsigned, replacements)
                    val hadKey = keys.exists()
                    val identity = keys.loadOrCreate()
                    signing.sign(unsigned, signed, identity, d.metadata?.minSdk ?: 23)
                    val report = checks.verify(signed, d.metadata?.minSdk)
                    log.event("build", "files" to edits.size, "verified" to report.verified, "v2" to report.v2, "v3" to report.v3, "bytes" to signed.length(), "copy" to ui.copyAsNew)
                    val changes = EditPlan.summary(edits, entries) + (if (clone != null) listOf("Install as a separate app: ${oldPackage}.${ui.suffix}, named \"${ui.label}\"") else emptyList())
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

    fun message(text: String?) { _state.value = _state.value.copy(message = text) }

    private fun displayName(uri: Uri): String? =
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    companion object {
        private const val KEY_DEBUG = "debug_log_enabled"
        const val MAX_IMPORT_BYTES = 1024L * 1024 * 1024
    }
}
