package com.amandhakar.apkforge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import com.amandhakar.apkforge.BuildUi
import com.amandhakar.apkforge.FileView
import com.amandhakar.apkforge.ViewerFile
import com.amandhakar.apkforge.ViewerTarget
import com.amandhakar.apkforge.ProjectDetails
import com.amandhakar.apkforge.build.ImageKind
import com.amandhakar.apkforge.core.*

private val TABS = listOf("Overview", "Files", "Manifest", "Resources", "Code", "Build")

@Composable
fun ProjectScreen(
    details: ProjectDetails,
    build: BuildUi,
    blockedReason: String?,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onCopyAsNew: (Boolean) -> Unit,
    onSuffix: (String) -> Unit,
    onLabel: (String) -> Unit,
    onPickImage: (ResourceEntry) -> Unit,
    onUnstage: (String) -> Unit,
    onBuild: () -> Unit,
    onExportForged: () -> Unit,
    onInstall: () -> Unit,
    openFile: suspend (String) -> FileView,
    thumbOfResource: suspend (ResourceEntry, Int) -> Bitmap?,
    thumbOfFile: suspend (String, Int) -> Bitmap?,
    patches: Map<String, String>,
    loadIndex: suspend () -> com.amandhakar.apkforge.code.CodeIndex?,
    readSmali: suspend (com.amandhakar.apkforge.code.SmaliClass, Boolean) -> Pair<String?, String?>,
    checkSmali: suspend (String, String) -> List<String>,
    savePatch: suspend (String, String) -> List<String>,
    removePatch: (String) -> Unit,
    loadValueSlots: suspend (ResourceEntry) -> List<com.amandhakar.apkforge.build.ValueSlot>,
    resourceId: (String) -> Int?,
    resourceName: (Int) -> String?,
    onStageValues: (List<com.amandhakar.apkforge.StagedValue>) -> Unit,
    onUnstageValue: (Int) -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    var viewer by remember { mutableStateOf<ViewerTarget?>(null) }
    var editing by remember { mutableStateOf<ResourceEntry?>(null) }
    viewer?.let { FileViewerScreen(it, openFile) { viewer = null }; return }
    editing?.let { ResourceEditScreen(it, build.values, loadValueSlots, resourceId, resourceName, onStageValues) { editing = null }; return }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onBack) { Text("← Back") }
            Text(details.metadata?.label ?: details.project.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
        ScrollableTabRow(tab, edgePadding = 8.dp) {
            TABS.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> Overview(details, onExport, onDelete)
            1 -> Files(details.inspection, thumbOfFile) { viewer = it }
            2 -> ManifestTab(details.manifest)
            3 -> ResourcesTab(details.resources, thumbOfResource, build.values.map { it.resource }.toSet(), { editing = it }) { viewer = it }
            4 -> CodeTab(patches, loadIndex, readSmali, checkSmali, savePatch, removePatch)
            else -> BuildTab(details, build, blockedReason, patches.size, onUnstageValue, onCopyAsNew, onSuffix, onLabel, onPickImage, onUnstage, onBuild, onExportForged, onInstall, thumbOfResource) { viewer = it }
        }
    }
}

@Composable
private fun ManifestTab(m: ManifestResult?) {
    when (m) {
        null -> Later("No manifest", "This file has no AndroidManifest.xml.")
        is ManifestResult.Failed -> Later("Manifest not readable", m.reason)
        is ManifestResult.Ok -> {
            val lines = remember(m) { m.xml.lines() }
            Column(Modifier.fillMaxSize()) {
                Text(
                    "Decoded from the binary manifest. References are shown by resource name (for example @string/app_name); a number like @0x7f090001 means the name was not found in resources.arsc.",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    items(lines.size) { i ->
                        Text(lines[i], fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.sp, softWrap = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun Later(title: String, body: String) {
    Column(Modifier.padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("Not available yet. $body", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Overview(d: ProjectDetails, onExport: () -> Unit, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val ins = d.inspection
        if (ins is InspectionResult.Rejected) {
            Card { Text("This file can't be inspected: ${ins.detail}", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
        }
        val m = d.metadata
        if (m != null) {
            Row2("Package", m.packageName)
            Row2("Name", m.label ?: "unknown")
            Row2("Version", "${m.versionName ?: "?"} (${m.versionCode})")
            Row2("Min SDK", m.minSdk?.toString() ?: "unknown")
            Row2("Target SDK", m.targetSdk?.toString() ?: "unknown")
            Row2("Signing certificate (SHA-256)", m.certificateSha256.joinToString("\n").ifEmpty { "not available" })
            Row2("Permissions (${m.permissions.size})", m.permissions.joinToString("\n") { it.removePrefix("android.permission.") }.ifEmpty { "none" })
        } else if (ins is InspectionResult.Ok) {
            Card { Text("Android could not read this APK's package details. The archive listing still works.", Modifier.padding(12.dp)) }
        }
        Row2("File size", formatBytes(d.project.sizeBytes))
        if (ins is InspectionResult.Ok) {
            val s = ins.summary
            Row2("Entries", "${s.entryCount} (${formatBytes(s.totalUncompressedBytes)} unpacked)")
            Row2("DEX files", s.dexFiles.joinToString().ifEmpty { "none" })
            Row2("Native libraries", s.nativeAbis.joinToString().ifEmpty { "none" })
            Row2("Single or split", splitText(d.split, s.looksLikeSplitApk, d.parts.size))
            if (s.unsafeNameCount > 0) {
                Text("Warning: ${s.unsafeNameCount} entries have unsafe or duplicate names. They will never be unpacked.", color = MaterialTheme.colorScheme.error)
            }
        }
        if (d.parts.isNotEmpty()) SplitSetCard(d)
        EditabilityCard(d.editability)
        Spacer(Modifier.height(8.dp))
        Button(onExport, Modifier.fillMaxWidth()) { Text("Export original APK (unchanged)") }
        OutlinedButton({ confirm = true }, Modifier.fillMaxWidth()) { Text("Delete project") }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete project?") },
            text = { Text("Deletes the copy stored inside Apkforge. The file you originally picked is not touched.") },
            confirmButton = { TextButton({ confirm = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SplitSetCard(d: ProjectDetails) {
    val total = d.project.sizeBytes + d.parts.sumOf { it.sizeBytes }
    val abis = (((d.inspection as? InspectionResult.Ok)?.summary?.nativeAbis).orEmpty() + d.parts.flatMap { it.abis }).distinct().sorted()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Whole set: base + ${d.parts.size} part(s), ${formatBytes(total)}", style = MaterialTheme.typography.titleSmall)
            Text("Native libraries across the set: ${abis.joinToString().ifEmpty { "none" }}", style = MaterialTheme.typography.bodySmall)
            if (d.project.declaredSplits > d.project.copiedSplits) {
                Text("${d.project.declaredSplits - d.project.copiedSplits} part(s) could not be copied.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            d.parts.forEach { p ->
                Column(Modifier.padding(top = 4.dp)) {
                    Text(p.splitName ?: p.fileName, style = MaterialTheme.typography.labelLarge)
                    Text(
                        if (!p.readable) "${formatBytes(p.sizeBytes)} · could not be read"
                        else "${formatBytes(p.sizeBytes)}" + (if (p.abis.isNotEmpty()) " · native: ${p.abis.joinToString()}" else "") + (if (p.resourceFiles > 0) " · ${p.resourceFiles} resource files" else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun EditabilityCard(r: EditabilityReport?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Can this app be modified?", style = MaterialTheme.typography.titleSmall)
            if (r == null) {
                Text("Checking the code…", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(Editability.headline(r.verdict), style = MaterialTheme.typography.titleMedium,
                    color = when (r.verdict) {
                        Verdict.NOT_EDITABLE_HERE -> MaterialTheme.colorScheme.error
                        Verdict.NOTHING_FOUND -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.tertiary
                    })
                r.findings.forEach { f ->
                    Column {
                        Text(
                            "${when (f.severity) { Severity.BLOCKER -> "Blocker"; Severity.WARNING -> "Warning"; Severity.NOTE -> "Note" }}: ${f.title}",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(f.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(Editability.CANNOT_TELL, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun splitText(split: SplitInfo?, guessSplit: Boolean, heldParts: Int = 0): String = when {
    split == null -> if (guessSplit) "Probably one part of a split APK (no code or resource table). The base APK and other parts are needed too." else "Looks like a complete APK (guessed from its file list, because the manifest could not be read)"
    split.kind == SplitKind.BASE_NEEDS_SPLITS && heldParts > 0 -> "Base APK of a split app" + (split.detail?.let { " (needs: $it)" } ?: "") + ". Apkforge holds this base and $heldParts other part(s): see below. The parts are copies from this phone, so they match this phone's processor, screen density and languages."
    split.kind == SplitKind.BASE_NEEDS_SPLITS -> "Base APK of a split app" + (split.detail?.let { " (needs: $it)" } ?: "") + ". It can't run alone: its other parts (native libraries, screen-density resources, languages) are separate files. Apkforge copies only this part for now."
    split.kind == SplitKind.SPLIT_PART -> "One part of a split app" + (split.detail?.let { " (\"$it\")" } ?: "") + ", not a whole app. It needs the base APK."
    else -> "Complete APK (from its manifest: no split parts required)"
}

@Composable
private fun ResourcesTab(r: ResourceResult?, thumbOfResource: suspend (ResourceEntry, Int) -> Bitmap?, changed: Set<String>, onEdit: (ResourceEntry) -> Unit, onOpen: (ViewerTarget) -> Unit) {
    when (r) {
        null -> Later("No resource table", "This APK has no resources.arsc.")
        is ResourceResult.Failed -> Later("Resources not readable", r.reason)
        is ResourceResult.Ok -> {
            val table = r.table
            var query by remember { mutableStateOf("") }
            var type by remember { mutableStateOf<String?>(null) }
            var showValue by remember { mutableStateOf<ResourceEntry?>(null) }
            val rows = remember(table, query, type) {
                table.entries.values.asSequence()
                    .filter { type == null || it.type == type }
                    .filter { query.isBlank() || it.name.contains(query, true) || (it.value?.contains(query, true) == true) }
                    .sortedWith(compareBy({ it.type }, { it.name })).take(5000).toList()
            }
            Column(Modifier.fillMaxSize()) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), label = { Text("Search name or value (${table.entries.size} resources)") }, singleLine = true)
                androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(type == null, { type = null }, label = { Text("All") }) }
                    items(table.types) { t -> FilterChip(type == t, { type = t }, label = { Text(t) }) }
                }
                Text("Tap a picture or drawing to open it, or any other resource to change its value.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp)) {
                    items(rows, key = { it.id }) { e ->
                        val files = e.files
                        val color = parseColor(e.value)
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (files.isNotEmpty()) onOpen(ViewerTarget("${e.type}/${e.name}", files.map { ViewerFile(it.density ?: "default", it.path) }))
                                else onEdit(e)
                            }.padding(vertical = 4.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            when {
                                e.type == "drawable" || e.type == "mipmap" -> { Thumb(e.id, 48.dp, { thumbOfResource(e, 96) }); Spacer(Modifier.width(10.dp)) }
                                color != null -> { Box(Modifier.size(48.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).background(color)); Spacer(Modifier.width(10.dp)) }
                            }
                            Column(Modifier.weight(1f)) {
                                Text("${e.type}/${e.name}", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    (table.displayValue(e) ?: "(no simple value)") + (if (e.configCount > 1) "  ·  ${e.configCount} configurations" else "") + (if ("${e.type}/${e.name}" in changed) "  ·  will be changed" else ""),
                                    style = MaterialTheme.typography.bodySmall, maxLines = 2,
                                    color = if ("${e.type}/${e.name}" in changed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (rows.size == 5000) item { Text("Showing the first 5000. Narrow the search to see more.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)) }
                }
            }
            showValue?.let { e ->
                AlertDialog(
                    onDismissRequest = { showValue = null },
                    title = { Text("${e.type}/${e.name}") },
                    text = { Text(table.displayValue(e) ?: "(no simple value: styles, arrays and plurals only show an item count)") },
                    confirmButton = { TextButton({ showValue = null }) { Text("Close") } },
                )
            }
        }
    }
}

@Composable
private fun Row2(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Files(ins: InspectionResult, thumbOfFile: suspend (String, Int) -> Bitmap?, onOpen: (ViewerTarget) -> Unit) {
    if (ins !is InspectionResult.Ok) { Later("No file list", "The archive could not be read."); return }
    var filter by remember { mutableStateOf<EntryKind?>(null) }
    val shown = remember(ins, filter) { if (filter == null) ins.entries else ins.entries.filter { it.kind == filter } }
    Column(Modifier.fillMaxSize()) {
        androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(filter == null, { filter = null }, label = { Text("All (${ins.entries.size})") }) }
            items(EntryKind.entries.filter { ins.summary.countsByKind.containsKey(it) }) { k ->
                FilterChip(filter == k, { filter = k }, label = { Text("${k.name.lowercase().replace('_', ' ')} (${ins.summary.countsByKind[k]})") })
            }
        }
        Text("Tap a file to open it and see what it is.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp)) {
            items(shown, key = { it.name + it.size + it.compressedSize }) { e ->
                val picture = ImageKind.expectedFor(e.name).let { it == ImageKind.PNG || it == ImageKind.JPEG || it == ImageKind.WEBP }
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !e.unsafeName) { onOpen(ViewerTarget(e.name.substringAfterLast('/'), listOf(ViewerFile(e.name, e.name)))) }.padding(vertical = 4.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    if (picture && !e.unsafeName) { Thumb(e.name, 40.dp, { thumbOfFile(e.name, 80) }); Spacer(Modifier.width(10.dp)) }
                    Column(Modifier.weight(1f)) {
                        Text(e.name, style = MaterialTheme.typography.bodyMedium, color = if (e.unsafeName) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        Text(formatBytes(e.size) + if (e.unsafeName) " · unsafe or duplicate name (cannot be opened)" else "", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
