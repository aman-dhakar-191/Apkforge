package com.amandhakar.apkforge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amandhakar.apkforge.ProjectDetails
import com.amandhakar.apkforge.core.*

private val TABS = listOf("Overview", "Files", "Manifest", "Code", "Build")

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
            2 -> Later("Manifest decoding", "Reading the binary AndroidManifest.xml is planned for Phase 3. For now the Overview shows the package details Android reads from it.")
            3 -> Later("DEX and code view", "Smali and Java-like views are planned for Phase 3. The Files tab lists the DEX files in this APK.")
            else -> Later("Build, sign and install", "Rebuilding, signing and installing are later phases. This version only inspects and exports the untouched original.")
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
            Row2("Single or split", if (s.looksLikeSplitApk) "Probably one part of a split APK (no code or resource table). The base APK and other parts are needed too." else "Looks like a complete APK (guessed from its file list)")
            if (s.unsafeNameCount > 0) {
                Text("Warning: ${s.unsafeNameCount} entries have unsafe or duplicate names. They will never be unpacked.", color = MaterialTheme.colorScheme.error)
            }
        }
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
