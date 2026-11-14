package com.amandhakar.apkforge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.amandhakar.apkforge.StagedManifest
import com.amandhakar.apkforge.build.ManifestChange
import com.amandhakar.apkforge.build.ManifestEdit
import com.amandhakar.apkforge.build.ManifestParsed
import com.amandhakar.apkforge.build.ManifestSlot

/** Change attribute values of AndroidManifest.xml (text, numbers, on/off flags). The original file is never touched. */
@Composable
fun ManifestEditScreen(
    staged: List<StagedManifest>,
    loadSlots: suspend () -> List<ManifestSlot>,
    onStage: (List<StagedManifest>) -> Unit,
    onUnstage: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val slots by produceState<List<ManifestSlot>?>(null) { value = loadSlots() }
    var query by remember { mutableStateOf("") }
    var onlyEditable by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<ManifestSlot?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("← Back") }
            Text("Manifest values", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            "Tap a value to change it. Changes are applied to the copy when you build; the original is never changed. The package name is changed with \"Install as a separate app\" in the Build tab. A wrong value can stop the app from installing or opening.",
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), singleLine = true, label = { Text("Search tag, name or value") })
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(onlyEditable, { onlyEditable = it }); Spacer(Modifier.width(8.dp)); Text("Only values that can be changed", style = MaterialTheme.typography.bodySmall)
        }
        val all = slots
        if (all == null) { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@Column }
        val shown = remember(all, query, onlyEditable) {
            val q = query.trim().lowercase()
            all.filter { (!onlyEditable || it.editable) && (q.isEmpty() || (it.title + " " + it.label + " " + ManifestEdit.show(it)).lowercase().contains(q)) }.take(400)
        }
        if (all.isEmpty()) Text("The manifest could not be read.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.key }) { s ->
                val change = staged.firstOrNull { it.change.key == s.key }
                Card(Modifier.fillMaxWidth().then(if (s.editable) Modifier.clickable { editing = s } else Modifier)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(s.title, style = MaterialTheme.typography.labelMedium)
                        Text(s.label + " = " + (change?.shown ?: ManifestEdit.show(s)), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                            color = if (change != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        if (change != null) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("will be changed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            TextButton({ onUnstage(s.key) }) { Text("Undo") }
                        } else if (!s.editable) Text("cannot be edited", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (shown.size == 400) item { Text("Showing the first 400. Search to narrow the list.", style = MaterialTheme.typography.bodySmall) }
        }
    }

    editing?.let { s ->
        var text by remember(s) { mutableStateOf(staged.firstOrNull { it.change.key == s.key }?.shown ?: ManifestEdit.show(s)) }
        var error by remember(s) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(s.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.title, style = MaterialTheme.typography.bodySmall)
                    if (s.dataType == 0x12) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (text.trim().equals("true", true)) "On (true)" else "Off (false)", Modifier.weight(1f))
                        Switch(text.trim().equals("true", true), { text = if (it) "true" else "false"; error = null })
                    } else OutlinedTextField(text, { text = it; error = null }, singleLine = s.dataType != 0x03, isError = error != null, supportingText = { error?.let { Text(it) } })
                    if (s.dataType != 0x03 && s.dataType != 0x12) Text("Some numbers are codes, for example screen orientation. Check what a number means before changing it.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton({
                    when (val p = ManifestEdit.parse(s, text)) {
                        is ManifestParsed.Bad -> error = p.message
                        is ManifestParsed.Ok -> {
                            if (p.dataType == s.dataType && p.data == s.data && p.text == s.text) onUnstage(s.key)
                            else onStage(listOf(StagedManifest(ManifestChange(s.element, s.namespace, s.name, p.dataType, p.data, p.text), s.title, s.label, if (p.text != null) p.text else text.trim())))
                            editing = null
                        }
                    }
                }) { Text("Plan this change") }
            },
            dismissButton = { TextButton({ editing = null }) { Text("Cancel") } },
        )
    }
}
