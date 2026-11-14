package com.amandhakar.apkforge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.amandhakar.apkforge.StagedValue
import com.amandhakar.apkforge.build.Parsed
import com.amandhakar.apkforge.build.SetText
import com.amandhakar.apkforge.build.SetValue
import com.amandhakar.apkforge.build.ValueSlot
import com.amandhakar.apkforge.build.ValueText
import com.amandhakar.apkforge.core.ResourceEntry

/** Change the values of one resource: its text in each language, its colour per theme, a number, a flag... */
@Composable
fun ResourceEditScreen(
    entry: ResourceEntry,
    staged: List<StagedValue>,
    loadSlots: suspend (ResourceEntry) -> List<ValueSlot>,
    idOf: (String) -> Int?,
    nameOf: (Int) -> String?,
    onStage: (List<StagedValue>) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val slots by produceState<List<ValueSlot>?>(null, entry) { value = loadSlots(entry) }
    val resource = "${entry.type}/${entry.name}"
    // what each box shows: the planned text if there is one, else the current value
    val texts = remember(entry, slots) { mutableStateMapOf<Int, String>() }
    val errors = remember(entry, slots) { mutableStateMapOf<Int, String>() }
    var saved by remember(entry) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("← Back") }
            Text(resource, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
        val list = slots
        if (list == null) { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@Column }
        if (list.isEmpty()) { Text("Apkforge could not read the values of this resource.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error); return@Column }

        fun current(s: ValueSlot) = staged.firstOrNull { it.edit.offset == s.offset }?.let { st ->
            when (val e = st.edit) { is SetText -> e.text; is SetValue -> ValueText.show(ValueSlot(s.config, s.itemName, e.dataType, e.data, s.offset, null, false, true), nameOf) }
        } ?: ValueText.show(s, nameOf)

        Text(
            "Changes are made to the copy when you build. The original is never changed. Each language or configuration has its own value; change only the ones you want.",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list, key = { it.offset }) { s ->
                val shown = texts.getOrPut(s.offset) { current(s) }
                val isStaged = staged.any { it.edit.offset == s.offset }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(s.config + (s.itemName?.let { " · $it" } ?: "") + if (isStaged) "  ·  will be changed" else "", style = MaterialTheme.typography.labelLarge, color = if (isStaged) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        when {
                            !s.editable -> Text("This kind of value (${kindName(s)}) cannot be edited yet. Current value: " + ValueText.show(s, nameOf), style = MaterialTheme.typography.bodySmall)
                            s.dataType == 0x12 -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (shown.trim().equals("true", true)) "On (true)" else "Off (false)", Modifier.weight(1f))
                                Switch(shown.trim().equals("true", true), { texts[s.offset] = if (it) "true" else "false"; errors.remove(s.offset); saved = false })
                            }
                            else -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (s.dataType in 0x1c..0x1f) {
                                        val c = parseColor(shown.trim().let { if (it.length == 4 || it.length == 5) "#" + it.drop(1).map { ch -> "$ch$ch" }.joinToString("") else it })
                                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(c ?: MaterialTheme.colorScheme.surfaceVariant))
                                        Spacer(Modifier.width(10.dp))
                                    }
                                    OutlinedTextField(
                                        shown, { texts[s.offset] = it; errors.remove(s.offset); saved = false }, Modifier.weight(1f),
                                        label = { Text(kindName(s)) }, singleLine = s.dataType != 0x03,
                                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = if (s.dataType == 0x03) FontFamily.Default else FontFamily.Monospace),
                                        isError = errors[s.offset] != null,
                                    )
                                }
                                errors[s.offset]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                                if (s.dataType == 0x03 && s.styled) Text("This text has formatting (bold, italic...). Saving a change removes the formatting.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                if (s.dataType == 0x03 && Regex("%\\d*\\$?[sdfx]|%%").containsMatchIn(s.text.orEmpty())) Text("Keep placeholders like %s, %d or %1\$s: the app fills them in, and removing one or changing its order can crash the app.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        Surface(Modifier.fillMaxWidth(), tonalElevation = 4.dp, shadowElevation = 8.dp) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button({
                    val changes = ArrayList<StagedValue>(); errors.clear()
                    for (s in list.filter { it.editable }) {
                        val now = texts[s.offset] ?: continue
                        if (now == current(s)) continue
                        when (val p = ValueText.parse(s, now, idOf)) {
                            is Parsed.Error -> errors[s.offset] = p.message
                            is Parsed.Text -> changes += StagedValue(resource, s.config, s.itemName, SetText(s.offset, p.text), p.text)
                            is Parsed.Value -> changes += StagedValue(resource, s.config, s.itemName, SetValue(s.offset, p.dataType, p.data), now.trim())
                        }
                    }
                    if (errors.isEmpty()) { onStage(changes); saved = true }
                }) { Text("Plan these changes") }
                Text(if (saved) "Planned. Build in the Build tab." else "Nothing is changed until you build.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun kindName(s: ValueSlot) = when (s.dataType) {
    0x03 -> "Text"; 0x01 -> "Reference (@type/name)"; 0x1c, 0x1d, 0x1e, 0x1f -> "Colour (#RRGGBB or #AARRGGBB)"; 0x10, 0x11 -> "Whole number"
    0x12 -> "On / off"; 0x04 -> "Number with decimals"; 0x05 -> "Size (for example 16dp)"; 0x06 -> "Fraction (for example 50%)"; else -> "Value"
}
