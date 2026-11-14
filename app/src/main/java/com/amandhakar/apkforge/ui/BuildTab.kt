package com.amandhakar.apkforge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amandhakar.apkforge.BuildUi
import com.amandhakar.apkforge.ProjectDetails
import com.amandhakar.apkforge.build.IconFinder
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
    patchCount: Int,
    onUnstageValue: (Int) -> Unit,
    onCopyAsNew: (Boolean) -> Unit,
    onSuffix: (String) -> Unit,
    onLabel: (String) -> Unit,
    onPick: (ResourceEntry) -> Unit,
    onRemove: (String) -> Unit,
    onBuild: () -> Unit,
    onExport: () -> Unit,
    onInstall: () -> Unit,
    thumbOfResource: suspend (ResourceEntry, Int) -> android.graphics.Bitmap?,
    onOpen: (com.amandhakar.apkforge.ViewerTarget) -> Unit,
) {
    val table = (d.resources as? ResourceResult.Ok)?.table
    var query by remember { mutableStateOf("") }
    val images = remember(table, query) {
        table?.entries?.values.orEmpty().asSequence()
            .filter { (it.type == "drawable" || it.type == "mipmap") && it.files.isNotEmpty() }
            .filter { query.isBlank() || it.name.contains(query, true) }
            .sortedWith(compareBy({ it.type }, { it.name })).take(3000).toList()
    }
    Column(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                "Changes go into a separate copy of the app. The original file in Apkforge is never changed, and the copy is signed with Apkforge's own key.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (blocked != null) {
            item { Card(Modifier.fillMaxWidth()) { Text(blocked, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) } }
            return@LazyColumn
        }

        item {
            val oldPackage = d.metadata?.packageName ?: "the original"
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Install as a separate copy", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (b.copyAsNew) "The copy gets a new package name, so it installs next to the original. The original and its data are not touched; the copy keeps its own data."
                                else "The copy keeps the original's package name. Android will only install it over the original if it has the same signature, which it won't: you would have to uninstall the original first, which deletes its data.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(b.copyAsNew, onCopyAsNew, enabled = !d.project.system && b.running == null)
                    }
                    if (b.copyAsNew) {
                        OutlinedTextField(b.label, onLabel, Modifier.fillMaxWidth(), label = { Text("Name under the icon") }, singleLine = true)
                        OutlinedTextField(b.suffix, onSuffix, Modifier.fillMaxWidth(), label = { Text("Package name ending") }, singleLine = true, supportingText = { Text("New package: $oldPackage.${b.suffix.ifEmpty { "…" }}") })
                        Text(
                            "Things tied to the original (Google sign-in, push notifications, licence checks) usually stop working in the copy, and apps that check their own package name may refuse to run.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (d.project.system) Text("This is a system app: the copy becomes an ordinary app and may not work if it needs privileges only the phone's maker can give.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (d.iconParts.isNotEmpty()) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("App icon", style = MaterialTheme.typography.titleSmall)
                    Text("Found from the app's manifest. This is the picture shown on the home screen.", style = MaterialTheme.typography.bodySmall)
                    d.iconParts.forEach { part ->
                        val staged = part.entry != null && b.staged.any { it.resource == "${part.entry.type}/${part.entry.name}" }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (part.entry != null) { Thumb(part.entry.id, 48.dp, { thumbOfResource(part.entry, 96) }); Spacer(Modifier.width(10.dp)) }
                            Column(Modifier.weight(1f)) {
                                Text(part.role, style = MaterialTheme.typography.bodyMedium)
                                (if (staged) "Will be replaced" else part.note)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (staged) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            if (part.replaceable && part.entry != null) OutlinedButton({ onPick(part.entry) }, enabled = b.running == null) { Text(if (staged) "Change" else "Replace…") }
                        }
                    }
                }
            }
        }
        item { Text("Replace a picture", style = MaterialTheme.typography.titleMedium) }
        item {
            Text(
                "Pick a resource, then a picture from your phone. Apkforge converts it to the same format and pixel size as the original (centre-cropped, not stretched) and uses it for every screen density. " +
                    "A vector or other drawing is replaced by your picture. Images are changed only where the app uses them: names like common_..., abc_... and notification_... come from libraries and are usually not visible. " +
                    "Use the App icon section above for the home-screen icon.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search by name (${images.size} drawables)") }, singleLine = true) }
        if (images.isEmpty()) item { Text("No drawables found.", style = MaterialTheme.typography.bodySmall) }
        items(images, key = { it.id }) { e ->
            val staged = b.staged.any { it.resource == "${e.type}/${e.name}" }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Thumb(e.id, 48.dp, { thumbOfResource(e, 96) }, Modifier.clickable {
                    onOpen(com.amandhakar.apkforge.ViewerTarget("${e.type}/${e.name}", e.files.map { com.amandhakar.apkforge.ViewerFile(it.density ?: "default", it.path) }))
                })
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("${e.type}/${e.name}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        e.files.joinToString { (it.density ?: "default") + if (it.path.endsWith(".xml")) " (drawing)" else if (it.path.endsWith(".9.png")) " (stretchable)" else "" } +
                            (if (!IconFinder.replaceable(e)) "  ·  can't be replaced yet" else "") + if (staged) "  ·  will be replaced" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (staged) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton({ onPick(e) }, enabled = b.running == null && IconFinder.replaceable(e)) { Text(if (staged) "Change" else "Replace…") }
            }
        }

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
                        if (o.newPackage != null) {
                            Text("Installs next to the original as ${o.newPackage}. The original and its data are not touched.", style = MaterialTheme.typography.bodySmall)
                            o.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        } else {
                            Text(
                                "If the original app is installed, Android will refuse to install this copy over it, because the signatures differ. " +
                                    "You would have to uninstall the original first, which deletes its data. Apkforge never uninstalls anything for you.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
    if (blocked == null) BuildBar(d, b, patchCount, onUnstageValue, onRemove, onBuild, onExport, onInstall)
    }
}

/** Pinned under the list, so the planned changes and the Build button never need scrolling to. */
@Composable
private fun BuildBar(
    d: ProjectDetails,
    b: BuildUi,
    patchCount: Int,
    onUnstageValue: (Int) -> Unit,
    onRemove: (String) -> Unit,
    onBuild: () -> Unit,
    onExport: () -> Unit,
    onInstall: () -> Unit,
) {
    Surface(Modifier.fillMaxWidth(), tonalElevation = 4.dp, shadowElevation = 8.dp) {
        Column(
            Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Planned changes", style = MaterialTheme.typography.titleSmall)
            if (b.copyAsNew) Text("• Install as a separate app: ${d.metadata?.packageName ?: "?"}.${b.suffix}, named \"${b.label}\"", style = MaterialTheme.typography.bodySmall)
            b.staged.forEach { s ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Thumb(s.resource, 36.dp, { previewOf(s.replacements.values.first(), 72) })
                    Spacer(Modifier.width(8.dp))
                    Text("Replace ${s.resource} (${s.replacements.size} file${if (s.replacements.size == 1) "" else "s"}" + (if (s.renames.isNotEmpty()) ", drawing → picture" else "") + ")", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton({ onRemove(s.resource) }, enabled = b.running == null) { Text("Remove") }
                }
            }
            b.values.forEach { v ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Change ${v.label}: ${v.shown.take(40)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton({ onUnstageValue(v.edit.offset) }, enabled = b.running == null) { Text("Remove") }
                }
            }
            if (patchCount > 0) Text("• Patch the code of $patchCount class${if (patchCount == 1) "" else "es"} (Code tab)", style = MaterialTheme.typography.bodySmall)
            if (!b.copyAsNew && b.staged.isEmpty() && b.values.isEmpty() && patchCount == 0) Text("Nothing yet. Pick an image above, change a resource in the Resources tab, or patch some code in the Code tab.", style = MaterialTheme.typography.bodySmall)
            Button(onBuild, Modifier.fillMaxWidth(), enabled = b.running == null && (b.staged.isNotEmpty() || b.values.isNotEmpty() || b.copyAsNew || patchCount > 0)) { Text("Build, sign and check") }
            b.running?.let { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text(it, style = MaterialTheme.typography.bodySmall) } }
            b.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            b.outcome?.let { o ->
                Text(
                    if (o.report.verified) "Built and signed. The signature check passed." else "Built, but the signature check FAILED.",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (o.report.verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Text(
                    formatBytes(o.sizeBytes) + (o.newPackage?.let { " · installs beside the original as $it" } ?: "") + " · full details at the bottom of the list",
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
