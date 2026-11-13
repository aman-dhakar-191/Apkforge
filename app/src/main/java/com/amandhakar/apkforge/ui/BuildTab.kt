package com.amandhakar.apkforge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amandhakar.apkforge.BuildUi
import com.amandhakar.apkforge.ProjectDetails
import com.amandhakar.apkforge.build.ImageKind
import com.amandhakar.apkforge.core.ResourceEntry
import com.amandhakar.apkforge.core.ResourceResult

private fun isPlainImage(path: String) =
    !path.endsWith(".9.png") && ImageKind.expectedFor(path).let { it == ImageKind.PNG || it == ImageKind.JPEG || it == ImageKind.WEBP }

@Composable
fun BuildTab(
    d: ProjectDetails,
    b: BuildUi,
    blocked: String?,
    onPick: (ResourceEntry) -> Unit,
    onRemove: (String) -> Unit,
    onBuild: () -> Unit,
    onExport: () -> Unit,
    onInstall: () -> Unit,
) {
    val table = (d.resources as? ResourceResult.Ok)?.table
    var query by remember { mutableStateOf("") }
    val images = remember(table, query) {
        table?.entries?.values.orEmpty().asSequence()
            .filter { (it.type == "drawable" || it.type == "mipmap") && it.files.any { f -> isPlainImage(f.path) } }
            .filter { query.isBlank() || it.name.contains(query, true) }
            .sortedWith(compareBy({ it.type }, { it.name })).take(300).toList()
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                "Changes go into a separate copy of the app. The original file in Apkforge is never changed. " +
                    "The copy is signed with Apkforge's own key, not the original developer's, so Android treats it as a different app from the one you have installed.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (blocked != null) {
            item { Card(Modifier.fillMaxWidth()) { Text(blocked, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) } }
            return@LazyColumn
        }

        item { Text("Replace an image", style = MaterialTheme.typography.titleMedium) }
        item {
            Text(
                "Pick an image resource, then a picture from your phone. Apkforge converts it to the same format and pixel size as the original (centre-cropped, not stretched) and uses it for every screen density. " +
                    "App icons are often built from separate foreground and background images: replace those.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search images by name") }, singleLine = true) }
        if (images.isEmpty()) item { Text("No replaceable images found.", style = MaterialTheme.typography.bodySmall) }
        items(images, key = { it.id }) { e ->
            val staged = b.staged.any { it.resource == "${e.type}/${e.name}" }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${e.type}/${e.name}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        e.files.filter { isPlainImage(it.path) }.joinToString { it.density ?: "default" } + if (staged) "  ·  will be replaced" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (staged) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton({ onPick(e) }, enabled = b.running == null) { Text(if (staged) "Change" else "Replace…") }
            }
        }

        if (b.staged.isNotEmpty()) {
            item { HorizontalDivider() }
            item { Text("Planned changes", style = MaterialTheme.typography.titleMedium) }
            items(b.staged, key = { it.resource }) { s ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Replace ${s.resource} (${s.replacements.size} file${if (s.replacements.size == 1) "" else "s"})", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton({ onRemove(s.resource) }, enabled = b.running == null) { Text("Remove") }
                }
            }
            item { Button(onBuild, Modifier.fillMaxWidth(), enabled = b.running == null) { Text("Build, sign and check") } }
        }

        b.running?.let { item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text(it) } } }
        b.problem?.let { item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) } }

        b.outcome?.let { o ->
            item { HorizontalDivider() }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (o.report.verified) "Built and signed. The signature check passed." else "Built, but the signature check FAILED.",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (o.report.verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                        o.changes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Text("Size: ${formatBytes(o.sizeBytes)}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "Signatures: " + listOfNotNull(if (o.report.v1) "v1" else null, if (o.report.v2) "v2" else null, if (o.report.v3) "v3" else null).joinToString().ifEmpty { "none" },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text("Signed with Apkforge's key (SHA-256):\n${o.fingerprint}", style = MaterialTheme.typography.bodySmall)
                        if (o.createdKey) Text("Apkforge made this key just now. It stays on this phone, in the app's private storage.", style = MaterialTheme.typography.bodySmall)
                        o.report.errors.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        Text(
                            "If the original app is installed, Android will refuse to install this copy over it, because the signatures differ. " +
                                "You would have to uninstall the original first, which deletes its data. Apkforge never uninstalls anything for you.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onExport) { Text("Export APK") }
                            OutlinedButton(onInstall, enabled = o.report.verified) { Text("Install…") }
                        }
                    }
                }
            }
        }
    }
}
