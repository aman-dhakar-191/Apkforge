package com.amandhakar.apkforge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amandhakar.apkforge.UiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: UiState,
    onToggle: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onCreateKey: () -> Unit,
    onDeleteKey: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onBack) { Text("← Back") }
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        var confirmDelete by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Signing key", style = MaterialTheme.typography.titleMedium)
            Text(
                "Modified copies are signed with this key. It stays in the app's private storage and is never logged or shared. " +
                    "Keep it: apps signed with it can only be updated by a copy signed with the same key.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(state.keyFingerprint?.let { "SHA-256: $it" } ?: "No key yet. It is created the first time you build.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.keyFingerprint == null) OutlinedButton(onCreateKey) { Text("Create key") }
                else OutlinedButton({ confirmDelete = true }) { Text("Delete key") }
            }
        }
        if (confirmDelete) AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete the signing key?") },
            text = { Text("Apps you signed with it can no longer be updated by a copy Apkforge signs, because Android only accepts updates signed with the same key. This cannot be undone.") },
            confirmButton = { TextButton({ confirmDelete = false; onDeleteKey() }) { Text("Delete") } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Debug log", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Off by default. When on, Apkforge keeps a short log on this phone of what it did: action names, counts and error types. " +
                        "It never records file names, paths or APK contents, and nothing is sent anywhere. Saving or sharing it is your choice, from here.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(state.debugLogEnabled, onToggle)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onSave, enabled = state.logText.isNotBlank()) { Text("Save log") }
            OutlinedButton(onShare, enabled = state.logText.isNotBlank()) { Text("Share") }
            OutlinedButton(onRefresh) { Text("Refresh") }
            OutlinedButton(onClear, enabled = state.logText.isNotBlank()) { Text("Clear") }
        }
        Text("What the log contains now", style = MaterialTheme.typography.labelLarge)
        Surface(Modifier.fillMaxWidth().weight(1f), tonalElevation = 2.dp, shape = MaterialTheme.shapes.small) {
            Text(
                state.logText.ifBlank { "Empty." },
                Modifier.verticalScroll(rememberScrollState()).padding(8.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
}
