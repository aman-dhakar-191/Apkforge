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
import com.amandhakar.apkforge.AccessProblem
import com.amandhakar.apkforge.core.InstalledApp
import com.amandhakar.apkforge.core.InstalledApps

@Composable
fun InstalledAppsScreen(
    apps: List<InstalledApp>?,
    problem: AccessProblem?,
    onPick: (InstalledApp) -> Unit,
    onPickFile: () -> Unit,
    onDismissProblem: () -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var includeSystem by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<InstalledApp?>(null) }
    val shown = remember(apps, query, includeSystem) { apps?.let { InstalledApps.filter(it, query, includeSystem) }.orEmpty() }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onBack) { Text("← Back") }
        Text("Installed apps", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Shows apps with a launcher icon, which is all Android lets Apkforge see. Whether an app's APK can be copied depends on your Android version; if it can't, you can pick the APK file instead.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search by name or package") }, singleLine = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(includeSystem, { includeSystem = it })
            Spacer(Modifier.width(8.dp))
            Text("Show system apps")
        }
        when {
            apps == null -> Text("Loading…")
            shown.isEmpty() -> Text(if (apps.isEmpty()) "Android showed no apps to Apkforge." else "No apps match.")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(shown, key = { it.packageName }) { a ->
                    Column(Modifier.fillMaxWidth().clickable { selected = a }.padding(vertical = 6.dp)) {
                        Text(a.label, style = MaterialTheme.typography.titleSmall)
                        Text("${a.packageName} · ${a.versionName ?: "?"} (${a.versionCode})", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    selected?.let { a ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(a.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Package: ${a.packageName}")
                    Text("Version: ${a.versionName ?: "?"} (${a.versionCode})")
                    Text("APK location reported by Android: ${a.sourceDir ?: "none"}")
                    if (a.splitCount > 0) Text("This app is installed as a base APK plus ${a.splitCount} other parts. Apkforge will try to copy all of them and tells you if Android refuses any.")
                    Text("Apkforge will try to copy the APK into its own storage. Android may refuse.")
                }
            },
            confirmButton = { TextButton({ selected = null; onPick(a) }) { Text("Try to open") } },
            dismissButton = { TextButton({ selected = null }) { Text("Cancel") } },
        )
    }
    problem?.let { p ->
        AlertDialog(
            onDismissRequest = onDismissProblem,
            title = { Text("Can't open ${p.appLabel} directly") },
            text = { Text(p.explanation + "\n\nYou can still analyze it if you have its APK file: pick the file instead.") },
            confirmButton = { TextButton(onPickFile) { Text("Pick APK file") } },
            dismissButton = { TextButton(onDismissProblem) { Text("Close") } },
        )
    }
}
