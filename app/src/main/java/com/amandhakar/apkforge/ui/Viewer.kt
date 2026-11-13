package com.amandhakar.apkforge.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amandhakar.apkforge.FileView
import com.amandhakar.apkforge.ViewerTarget

/** A small preview that loads when it scrolls into view. Shows a dash when there is nothing to draw. */
@Composable
fun Thumb(key: Any, size: Dp, load: suspend () -> Bitmap?, modifier: Modifier = Modifier) {
    val bmp by produceState<Bitmap?>(null, key) { value = load() }
    Box(
        modifier.size(size).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            ?: Text("–", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Decodes a small preview of picture bytes (the new picture the user chose), or null. */
suspend fun previewOf(bytes: ByteArray, px: Int): Bitmap? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (o.outWidth > 0 && minOf(o.outWidth, o.outHeight) / (sample * 2) >= px) sample *= 2
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) { null }
}

/** A colour square for resource values like "#ff2b3a55" (null when the value is not a colour). */
fun parseColor(value: String?): Color? {
    val v = value ?: return null
    if (!v.startsWith("#") || (v.length != 9 && v.length != 7)) return null
    val n = v.substring(1).toLongOrNull(16) ?: return null
    return if (v.length == 9) Color(((n shr 24) and 0xFF).toInt(), ((n shr 16) and 0xFF).toInt(), ((n shr 8) and 0xFF).toInt(), (n and 0xFF).toInt())
    else Color(((n shr 16) and 0xFF).toInt(), ((n shr 8) and 0xFF).toInt(), (n and 0xFF).toInt())
}

/** Monospace text, one row per line, scrolling sideways (used for decoded XML, text and hex). */
@Composable
fun CodeLines(text: String, modifier: Modifier = Modifier) {
    val lines = remember(text) { text.lines() }
    LazyColumn(modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        items(lines.size) { i -> Text(lines[i], fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false) }
    }
}

/** Opens a file (or one file per screen density of a resource) and says what it is. */
@Composable
fun FileViewerScreen(target: ViewerTarget, open: suspend (String) -> FileView, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var selected by remember(target) { mutableIntStateOf(0) }
    val file = target.files[selected]
    val view by produceState<FileView?>(null, target, selected) { value = null; value = open(file.entry) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("← Back") }
            Text(target.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
        if (target.files.size > 1) {
            androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(target.files.size) { i -> FilterChip(selected == i, { selected = i }, label = { Text(target.files[i].label) }) }
            }
        }
        Text(file.entry, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        when (val v = view) {
            null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is FileView.Failed -> Text(v.reason, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            is FileView.Image -> Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Kind("${v.format} image · ${v.width} × ${v.height} px · ${formatBytes(v.bytes)}")
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp), contentAlignment = Alignment.Center) {
                    Image(v.bitmap.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 480.dp), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
                }
                Text("Not XML: this is a picture.", style = MaterialTheme.typography.bodySmall)
            }
            is FileView.Xml -> Column(Modifier.fillMaxSize()) {
                Kind(
                    (if (v.compiled) "XML: yes. Android's compiled (binary) XML, shown decoded. References appear by name." else "XML: yes. Plain XML text.") + " · ${formatBytes(v.bytes)}",
                    Modifier.padding(horizontal = 16.dp),
                )
                CodeLines(v.text, Modifier.fillMaxSize().padding(top = 6.dp))
            }
            is FileView.Text -> Column(Modifier.fillMaxSize()) {
                Kind("XML: no. Plain text · ${formatBytes(v.bytes)}" + if (v.truncated) " · only the start is shown" else "", Modifier.padding(horizontal = 16.dp))
                CodeLines(v.text, Modifier.fillMaxSize().padding(top = 6.dp))
            }
            is FileView.Binary -> Column(Modifier.fillMaxSize()) {
                Kind("XML: no. Binary data · ${formatBytes(v.bytes)}", Modifier.padding(horizontal = 16.dp))
                v.note?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall) }
                CodeLines(v.hex, Modifier.fillMaxSize().padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun Kind(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}
