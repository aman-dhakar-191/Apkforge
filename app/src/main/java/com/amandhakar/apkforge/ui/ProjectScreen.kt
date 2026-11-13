package com.amandhakar.apkforge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amandhakar.apkforge.ProjectDetails
import com.amandhakar.apkforge.core.*

private val TABS = listOf("Overview", "Files", "Manifest", "Resources", "Code", "Build")

@Composable
fun ProjectScreen(details: ProjectDetails, onBack: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
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
            1 -> Files(details.inspection)
            2 -> ManifestTab(details.manifest)
            3 -> ResourcesTab(details.resources)
            4 -> Later("DEX and code view", "Smali and Java-like views are planned for Phase 3. The Files tab lists the DEX files in this APK.")
            else -> Later("Build, sign and install", "Rebuilding, signing and installing are later phases. This version only inspects and exports the untouched original.")
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
private fun ResourcesTab(r: ResourceResult?) {
    when (r) {
        null -> Later("No resource table", "This APK has no resources.arsc.")
        is ResourceResult.Failed -> Later("Resources not readable", r.reason)
        is ResourceResult.Ok -> {
            val table = r.table
            var query by remember { mutableStateOf("") }
            var type by remember { mutableStateOf<String?>(null) }
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
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp)) {
                    items(rows, key = { it.id }) { e ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text("${e.type}/${e.name}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                (table.displayValue(e) ?: "(no simple value)") + if (e.configCount > 1) "  ·  ${e.configCount} configurations" else "",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (rows.size == 5000) item { Text("Showing the first 5000. Narrow the search to see more.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)) }
                }
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
private fun Files(ins: InspectionResult) {
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
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp)) {
            items(shown, key = { it.name + it.size + it.compressedSize }) { e ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(e.name, style = MaterialTheme.typography.bodyMedium, color = if (e.unsafeName) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    Text(formatBytes(e.size) + if (e.unsafeName) " · unsafe or duplicate name" else "", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
