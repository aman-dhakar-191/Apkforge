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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amandhakar.apkforge.code.CodeIndex
import com.amandhakar.apkforge.code.SmaliClass
import kotlinx.coroutines.launch

private const val EDIT_LIMIT = 300_000

/** Browse the app's classes as smali, and edit one into a patch. */
@Composable
fun CodeTab(
    patches: Map<String, String>,
    loadIndex: suspend () -> CodeIndex?,
    readSmali: suspend (SmaliClass, Boolean) -> Pair<String?, String?>,
    check: suspend (String, String) -> List<String>,
    savePatch: suspend (String, String) -> List<String>,
    removePatch: (String) -> Unit,
) {
    var loaded by remember { mutableStateOf(false) }
    val index by produceState<CodeIndex?>(null) { value = loadIndex(); loaded = true }
    var open by remember { mutableStateOf<SmaliClass?>(null) }
    var query by remember { mutableStateOf("") }

    val idx = index
    open?.let { cls ->
        ClassScreen(cls, patches[cls.descriptor], readSmali, check, savePatch, { removePatch(cls.descriptor); }, onBack = { open = null })
        return
    }
    if (idx == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            if (!loaded) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Reading the app's code…") }
            else Text("This app's code could not be read. It may have no code files, or they may be damaged.", color = MaterialTheme.colorScheme.error)
        }
        return
    }
    val results = remember(idx, query) { idx.search(query, 300) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${idx.classes.size} classes in ${idx.dexEntries.size} code file${if (idx.dexEntries.size == 1) "" else "s"}" +
                    if (idx.unreadable.isNotEmpty()) " (${idx.unreadable.joinToString()} could not be read)" else "",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "Smali is the readable form of an app's code. Tap a class to read it, and edit it to make a patch. " +
                    "Use this on apps you own or may analyze, to understand and fix their code. It is not meant for removing licence checks, paywalls or other protections.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search class names") }, singleLine = true)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp)) {
            if (patches.isNotEmpty()) item {
                Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Patches (${patches.size})", style = MaterialTheme.typography.titleSmall)
                        Text("These classes are rebuilt into a new first code file when you build. Open one to change it.", style = MaterialTheme.typography.bodySmall)
                        patches.keys.forEach { d ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val c = idx.classes.firstOrNull { it.descriptor == d }
                                Text(d.removePrefix("L").removeSuffix(";").replace('/', '.'), Modifier.weight(1f).clickable(enabled = c != null) { open = c }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                TextButton({ removePatch(d) }) { Text("Remove") }
                            }
                        }
                    }
                }
            }
            if (results.isEmpty()) item { Text("No class matches.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)) }
            items(results, key = { it.descriptor }) { c ->
                Column(Modifier.fillMaxWidth().clickable { open = c }.padding(vertical = 5.dp)) {
                    Text(c.name, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Text(c.dexEntry + if (c.descriptor in patches) " · patched" else "", style = MaterialTheme.typography.bodySmall, color = if (c.descriptor in patches) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (results.size == 300) item { Text("Showing the first 300. Narrow the search to see more.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)) }
        }
    }
}

@Composable
private fun ClassScreen(
    cls: SmaliClass,
    patch: String?,
    readSmali: suspend (SmaliClass, Boolean) -> Pair<String?, String?>,
    check: suspend (String, String) -> List<String>,
    savePatch: suspend (String, String) -> List<String>,
    removePatch: () -> Unit,
    onBack: () -> Unit,
) {
    var editing by remember(cls) { mutableStateOf(false) }
    BackHandler { if (editing) editing = false else onBack() }
    val scope = rememberCoroutineScope()
    val current by produceState<Pair<String?, String?>?>(null, cls, patch) { value = null; value = readSmali(cls, false) }
    var field by remember(cls) { mutableStateOf("") }
    var problems by remember(cls) { mutableStateOf<List<String>>(emptyList()) }
    var message by remember(cls) { mutableStateOf<String?>(null) }
    var busy by remember(cls) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ if (editing) editing = false else onBack() }) { Text("← Back") }
            Text(cls.name.substringAfterLast('.'), style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
        Text(cls.name + " · " + cls.dexEntry, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        val text = current?.first
        when {
            current == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            text == null -> Text(current?.second ?: "Could not read the class.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            !editing -> {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (text.length <= EDIT_LIMIT) Button({ field = text; problems = emptyList(); message = null; editing = true }) { Text(if (patch != null) "Edit patch" else "Edit") }
                    else Text("Too large to edit on a phone (${text.length / 1000}k characters).", style = MaterialTheme.typography.bodySmall)
                    if (patch != null) OutlinedButton({ removePatch() }) { Text("Remove patch") }
                }
                Text(
                    if (patch != null) "This class is patched. You are reading the patched version." else "Original code, read from the app. Nothing here is changed until you save a patch and build.",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                )
                CodeLines(text, Modifier.fillMaxSize().padding(top = 6.dp))
            }
            else -> {
                Text(
                    "Keep the .class line as it is. Check assembles your text without saving it; Save patch stores it (only if it assembles) and uses it the next time you build.",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    field, { field = it; message = null }, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                )
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).heightIn(max = 160.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    problems.take(8).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, fontFamily = FontFamily.Monospace) }
                    if (problems.size > 8) Text("… and ${problems.size - 8} more", style = MaterialTheme.typography.bodySmall)
                    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                }
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton({ busy = true; scope.launch { problems = check(cls.descriptor, field); message = if (problems.isEmpty()) "It assembles." else null; busy = false } }, enabled = !busy) { Text("Check") }
                    Button({
                        busy = true
                        scope.launch {
                            problems = savePatch(cls.descriptor, field)
                            if (problems.isEmpty()) { message = "Patch saved. It will be built into the copy."; editing = false }
                            busy = false
                        }
                    }, enabled = !busy) { Text("Save patch") }
                    OutlinedButton({ editing = false }, enabled = !busy) { Text("Cancel") }
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}
