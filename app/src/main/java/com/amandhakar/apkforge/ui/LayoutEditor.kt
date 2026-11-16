package com.amandhakar.apkforge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amandhakar.apkforge.build.*
import com.amandhakar.apkforge.build.LayoutOp.*

private class Row(val path: List<Int>, val el: XElement)

private fun flatten(e: XElement, path: List<Int>, out: MutableList<Row>) {
    out += Row(path, e)
    e.children.forEachIndexed { i, c -> if (c is XElement) flatten(c, path + i, out) }
}

private fun short(tag: String) = tag.substringAfterLast('.')
private fun prefixOf(a: XAttr) = if (a.ns == AndroidAttrs.NS) "android:" else if (a.ns != null) a.ns!!.substringAfterLast('/') + ":" else ""
private val EDITABLE = setOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x10, 0x11, 0x12, 0x1c, 0x1d, 0x1e, 0x1f)

/**
 * A layout (or any compiled XML file) as a tree of views: change values, add or remove attributes, add, copy, move and remove
 * views. Each action is one planned step; nothing is written until you build, and the original is never changed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LayoutEditorScreen(
    path: String,
    loadBytes: suspend (String) -> ByteArray?,
    ops: List<LayoutOp>,
    onOps: (List<LayoutOp>) -> Unit,
    nameOf: (Int) -> String?,
    idOf: (String) -> Int?,
    onBack: () -> Unit,
) {
    val bytes by produceState<ByteArray?>(null, path) { value = loadBytes(path) }
    var attrsOf by remember(path) { mutableStateOf<List<Int>?>(null) }
    var selected by remember(path) { mutableStateOf<List<Int>?>(null) }
    var adding by remember(path) { mutableStateOf<Pair<List<Int>, Int>?>(null) }
    var removing by remember(path) { mutableStateOf<List<Int>?>(null) }
    var message by remember(path) { mutableStateOf<String?>(null) }
    BackHandler { if (attrsOf != null) attrsOf = null else onBack() }

    val data = bytes
    if (data == null) { Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    val tree = remember(data, ops) { runCatching { AxmlTree.parse(data).also { LayoutEditor.applyTo(it, ops) } } }
    val t = tree.getOrNull()
    if (t == null) {
        Column(Modifier.padding(16.dp)) {
            TextButton(onBack) { Text("← Back") }
            Text("This file cannot be edited here: ${tree.exceptionOrNull()?.message ?: "unknown problem"}", color = MaterialTheme.colorScheme.error)
            if (ops.isNotEmpty()) TextButton({ onOps(emptyList()) }) { Text("Discard the planned changes to this file") }
        }
        return
    }
    fun commit(op: LayoutOp): Boolean = try {
        val next = ops + op
        AxmlTree.parse(data).also { LayoutEditor.applyTo(it, next) }
        onOps(next); message = null; true
    } catch (e: Exception) { message = e.message ?: "That change is not possible."; false }

    fun labelOf(e: XElement): String = short(e.name) + (e.attr(AndroidAttrs.NS, "id")?.let { a -> a.resId.let { nameOf(a.data) } }?.let { " $it" } ?: "")

    attrsOf?.let { p ->
        val el = runCatching { LayoutEditor.node(t, p) }.getOrNull()
        if (el == null) { attrsOf = null } else {
            AttributesPanel(el, labelOf(el), nameOf, idOf, { attrsOf = null }, ::commit, p)
            return
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("← Back") }
            Text(path.substringAfterLast('/'), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            TextButton({ onOps(ops.dropLast(1)); message = null }, enabled = ops.isNotEmpty()) { Text("Undo") }
            TextButton({ onOps(emptyList()); message = null; selected = null }, enabled = ops.isNotEmpty()) { Text("Revert all") }
        }
        Text(
            (if (ops.isEmpty()) "No changes yet. " else "${ops.size} change${if (ops.size == 1) "" else "s"} planned. ") +
                "Tap a view to change it. Nothing is written until you build; the original is never changed. Code may look views up by id, so removing a view can crash the app.",
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
        )
        message?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        val rows = remember(t, ops) { ArrayList<Row>().also { flatten(t.root, emptyList(), it) } }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(rows, key = { i, r -> i.toString() + r.path.joinToString(",") + r.el.name }) { _, r ->
                val isSel = selected == r.path
                Card(Modifier.fillMaxWidth().padding(start = (minOf(r.path.size, 8) * 14).dp).clickable { selected = if (isSel) null else r.path },
                    colors = if (isSel) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(labelOf(r.el), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        val hint = listOf("text", "hint", "contentDescription", "src").firstNotNullOfOrNull { n -> r.el.attr(AndroidAttrs.NS, n)?.let { n to AttrValues.show(it, nameOf) } }
                        if (hint != null) Text("${hint.first}: ${hint.second}".take(80), style = MaterialTheme.typography.bodySmall)
                        if (isSel) {
                            val parentPath = r.path.dropLast(1); val index = r.path.lastOrNull() ?: 0
                            val parent = if (r.path.isEmpty()) null else LayoutEditor.node(t, parentPath)
                            val prev = parent?.children?.getOrNull(index - 1) as? XElement
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton({ attrsOf = r.path }) { Text("Attributes (${r.el.attrs.size})") }
                                OutlinedButton({ adding = r.path to r.el.children.size }, enabled = !LayoutEditor.isLeaf(r.el.name)) { Text("Add inside") }
                                OutlinedButton({ adding = parentPath to index + 1 }, enabled = parent != null) { Text("Add after") }
                                OutlinedButton({ if (commit(DuplicateView(r.path, labelOf(r.el)))) selected = null }, enabled = parent != null) { Text("Copy") }
                                OutlinedButton({ if (commit(MoveView(r.path, parentPath, index - 1, labelOf(r.el)))) selected = parentPath + (index - 1) }, enabled = parent != null && index > 0) { Text("↑ Up") }
                                OutlinedButton({ if (commit(MoveView(r.path, parentPath, index + 1, labelOf(r.el)))) selected = parentPath + (index + 1) }, enabled = parent != null && index < (parent?.children?.size ?: 0) - 1) { Text("↓ Down") }
                                OutlinedButton({ if (commit(MoveView(r.path, parentPath + (index - 1), prev?.children?.size ?: 0, labelOf(r.el)))) selected = null }, enabled = prev != null && !LayoutEditor.isLeaf(prev.name)) { Text("→ Into the one above") }
                                OutlinedButton({ if (commit(MoveView(r.path, parentPath.dropLast(1), parentPath.last() + 1, labelOf(r.el)))) selected = null }, enabled = parentPath.isNotEmpty()) { Text("← Out of its parent") }
                                OutlinedButton({ removing = r.path }, enabled = parent != null) { Text("Remove") }
                            }
                        }
                    }
                }
            }
        }
    }

    adding?.let { (parentPath, index) ->
        val parent = runCatching { LayoutEditor.node(t, parentPath) }.getOrNull()
        AddViewDialog(parent?.name.orEmpty(), { adding = null }) { tag, attrs ->
            if (commit(InsertView(parentPath, index, tag, attrs))) { adding = null; selected = null }
        }
    }
    removing?.let { p ->
        val el = runCatching { LayoutEditor.node(t, p) }.getOrNull()
        if (el == null) removing = null else {
            val ids = ArrayList<String>(); fun collect(e: XElement) { e.attr(AndroidAttrs.NS, "id")?.let { a -> ids += nameOf(a.data) ?: "@0x${Integer.toHexString(a.data)}" }; e.children.forEach { if (it is XElement) collect(it) } }
            collect(el)
            AlertDialog(
                onDismissRequest = { removing = null }, title = { Text("Remove ${labelOf(el)}?") },
                text = { Text(
                    "This removes the view and everything inside it (${el.elementCount()} view${if (el.elementCount() == 1) "" else "s"})." +
                        if (ids.isNotEmpty()) "\n\nThe app's code may look these up by id, and a missing one can crash the app: ${ids.take(6).joinToString()}${if (ids.size > 6) ", …" else ""}." else "",
                ) },
                confirmButton = { TextButton({ if (commit(RemoveView(p, labelOf(el)))) { removing = null; selected = null } }) { Text("Remove") } },
                dismissButton = { TextButton({ removing = null }) { Text("Cancel") } },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddViewDialog(parentTag: String, onDismiss: () -> Unit, onAdd: (String, List<NewAttr>) -> Unit) {
    val presets = listOf("TextView", "Button", "ImageView", "EditText", "View", "Space", "CheckBox", "Switch", "ProgressBar", "LinearLayout", "FrameLayout", "RelativeLayout", "ScrollView")
    var tag by remember { mutableStateOf("TextView") }
    var custom by remember { mutableStateOf("") }
    var width by remember { mutableStateOf("wrap_content") }
    var height by remember { mutableStateOf("wrap_content") }
    var text by remember { mutableStateOf("New text") }
    val finalTag = custom.trim().ifEmpty { tag }
    val hasText = short(finalTag) in setOf("TextView", "Button", "EditText", "CheckBox", "Switch", "RadioButton")
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Add a view") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(presets.size) { i -> FilterChip(custom.isBlank() && tag == presets[i], { tag = presets[i]; custom = "" }, label = { Text(presets[i]) }) } }
                OutlinedTextField(custom, { custom = it }, singleLine = true, label = { Text("or a full class name") }, placeholder = { Text("com.example.MyView") })
                Text("Width", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("wrap_content", "match_parent").forEach { FilterChip(width == it, { width = it }, label = { Text(it) }) } }
                Text("Height", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("wrap_content", "match_parent").forEach { FilterChip(height == it, { height = it }, label = { Text(it) }) } }
                if (hasText) OutlinedTextField(text, { text = it }, label = { Text("Text") })
                if (short(parentTag).contains("Constraint")) Text("The parent is a ConstraintLayout: a new view with no constraints sits at the top left until you add them in Attributes.", style = MaterialTheme.typography.bodySmall)
                if (LayoutEditor.isLeaf(parentTag)) Text("$parentTag cannot hold other views.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton({
                val attrs = ArrayList<NewAttr>()
                fun size(v: String) = NewAttr("", 0x10, if (v == "match_parent") -1 else -2, null)
                attrs += NewAttr("layout_width", 0x10, size(width).data, null); attrs += NewAttr("layout_height", 0x10, size(height).data, null)
                if (hasText && text.isNotEmpty()) attrs += NewAttr("text", 0x03, 0, text)
                if (short(finalTag) == "LinearLayout") attrs += NewAttr("orientation", 0x10, 1, null)
                // framework views are written without a package, like a layout file would
                onAdd(finalTag, attrs)
            }, enabled = finalTag.isNotBlank()) { Text("Add") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttributesPanel(
    el: XElement, label: String, nameOf: (Int) -> String?, idOf: (String) -> Int?, onBack: () -> Unit,
    commit: (LayoutOp) -> Boolean, path: List<Int>,
) {
    var editing by remember { mutableStateOf<XAttr?>(null) }
    var picking by remember { mutableStateOf(false) }
    var newAttr by remember { mutableStateOf<AttrInfo?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("← Views") }
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            TextButton({ picking = true }) { Text("+ Attribute") }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(el.attrs.toList(), key = { _, a -> (a.ns ?: "") + a.name }) { _, a ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable(enabled = a.type in EDITABLE) { editing = a }) {
                            Text(prefixOf(a) + a.name, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                            Text(AttrValues.show(a, nameOf), style = MaterialTheme.typography.bodyMedium)
                            if (a.type !in EDITABLE) Text("cannot be edited", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton({ commit(RemoveAttr(path, a.ns, a.name)) }) { Text("Remove") }
                    }
                }
            }
        }
    }
    editing?.let { a ->
        val info = if (a.ns == AndroidAttrs.NS) AndroidAttrs.info(a.name) else null
        var text by remember(a) { mutableStateOf(AttrValues.show(a, nameOf)) }
        var error by remember(a) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { editing = null }, title = { Text(prefixOf(a) + a.name) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(text, { text = it; error = null }, isError = error != null, supportingText = { error?.let { Text(it) } }, singleLine = a.type != 0x03)
                info?.let { Text("Accepts: " + AttrValues.describeFormats(it), style = MaterialTheme.typography.bodySmall) }
            } },
            confirmButton = { TextButton({
                when (val p = AttrValues.parse(info, a.type, text, idOf)) {
                    is ManifestParsed.Bad -> error = p.message
                    is ManifestParsed.Ok -> if (commit(SetAttr(path, a.ns, a.name, p.dataType, p.data, p.text, text.trim().take(40)))) editing = null
                }
            }) { Text("Plan this change") } },
            dismissButton = { TextButton({ editing = null }) { Text("Cancel") } },
        )
    }
    if (picking) {
        var q by remember { mutableStateOf("") }
        val have = el.attrs.filter { it.ns == AndroidAttrs.NS }.map { it.name }.toSet()
        val list = remember(q, have) { AndroidAttrs.all.filter { it.name !in have && (q.isBlank() || it.name.contains(q.trim(), true)) }.take(40) }
        AlertDialog(
            onDismissRequest = { picking = false }, title = { Text("Add an attribute") },
            text = { Column {
                OutlinedTextField(q, { q = it }, singleLine = true, label = { Text("Search, for example text or margin") })
                if (AndroidAttrs.all.isEmpty()) Text("The list of Android attributes could not be loaded, so attributes cannot be added.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    itemsIndexed(list, key = { _, i -> i.name }) { _, info ->
                        Column(Modifier.fillMaxWidth().clickable { newAttr = info; picking = false }.padding(vertical = 6.dp)) {
                            Text("android:" + info.name, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                            Text(AttrValues.describeFormats(info), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                }
            } },
            confirmButton = {}, dismissButton = { TextButton({ picking = false }) { Text("Cancel") } },
        )
    }
    newAttr?.let { info ->
        var text by remember(info) { mutableStateOf("") }
        var error by remember(info) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { newAttr = null }, title = { Text("android:" + info.name) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(text, { text = it; error = null }, isError = error != null, supportingText = { error?.let { Text(it) } })
                Text("Accepts: " + AttrValues.describeFormats(info), style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton({
                when (val p = AttrValues.parse(info, 0x03, text, idOf)) {
                    is ManifestParsed.Bad -> error = p.message
                    is ManifestParsed.Ok -> if (commit(AddAttr(path, NewAttr(info.name, p.dataType, p.data, p.text), text.trim().take(40)))) newAttr = null
                }
            }, enabled = text.isNotBlank()) { Text("Add") } },
            dismissButton = { TextButton({ newAttr = null }) { Text("Cancel") } },
        )
    }
}
