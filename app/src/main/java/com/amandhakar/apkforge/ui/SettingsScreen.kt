package com.amandhakar.apkforge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onBack) { Text("← Back") }
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
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
