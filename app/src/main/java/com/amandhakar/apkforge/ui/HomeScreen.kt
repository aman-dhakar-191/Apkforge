package com.amandhakar.apkforge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amandhakar.apkforge.workspace.Project
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(
    projects: List<Project>,
    onImport: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSettings: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf<Project?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Apkforge", style = MaterialTheme.typography.headlineMedium)
            TextButton(onSettings) { Text("Settings") }
        }
        Text(
            "Inspect APKs you own or are allowed to analyze. Everything stays on this phone.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onImport, Modifier.fillMaxWidth()) { Text("Import APK") }
        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Installed apps (coming in a later version)") }
        Text("Recent projects", style = MaterialTheme.typography.titleMedium)
        if (projects.isEmpty()) {
            Text("No projects yet. Import an APK to start.", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(projects, key = { it.id }) { p ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(p.id) }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.displayName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${formatBytes(p.sizeBytes)} · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(p.createdAtMillis))}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton({ confirmDelete = p }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete project?") },
            text = { Text("This deletes the copy of \"${p.displayName}\" stored inside Apkforge. The original file you picked is not touched.") },
            confirmButton = { TextButton({ onDelete(p.id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun BusyOverlay(text: String) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(text, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> "%.1f MB".format(b / (1024.0 * 1024))
    b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}
