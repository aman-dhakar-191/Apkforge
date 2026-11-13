package com.amandhakar.apkforge.build

import com.amandhakar.apkforge.core.FileSniffer
import com.amandhakar.apkforge.core.ResourceEntry
import com.amandhakar.apkforge.core.ResourceTable
import com.amandhakar.apkforge.inspect.AxmlException

/** One picture that makes up the app's icon. [entry] is null when it could not be found. */
class IconPart(val role: String, val entry: ResourceEntry?, val replaceable: Boolean, val note: String?)

/**
 * Finds the app's launcher icon from the manifest (`android:icon`), so the user does not have to hunt for it
 * among thousands of images. Modern icons are "adaptive": a small XML file that points at a foreground and a
 * background; those are the pictures to replace. Older phones use plain image files for the same icon.
 */
object IconFinder {
    private fun plainImages(e: ResourceEntry) = e.files.filter {
        !it.path.endsWith(".9.png") && ImageKind.expectedFor(it.path).let { k -> k == ImageKind.PNG || k == ImageKind.JPEG || k == ImageKind.WEBP }
    }

    /** True when Apkforge can replace this resource with a picture: it has picture files, or a drawing (.xml) it can swap for a PNG. */
    fun replaceable(e: ResourceEntry): Boolean =
        plainImages(e).isNotEmpty() || ((e.type == "drawable" || e.type == "mipmap") && e.files.any { it.path.endsWith(".xml") })

    fun find(manifest: ByteArray, table: ResourceTable, readEntry: (String) -> ByteArray?): List<IconPart> {
        val doc = try { AxmlEditor().parse(manifest) } catch (e: AxmlException) { return emptyList() }
        val app = doc.elements.firstOrNull { it.name == "application" } ?: return emptyList()
        val ref = (app.attrs.firstOrNull { it.name == "icon" && it.namespace != null } ?: app.attrs.firstOrNull { it.name == "roundIcon" && it.namespace != null })?.reference
            ?: return listOf(IconPart("App icon", null, false, "The manifest does not name an icon, so Android uses its default."))
        val icon = table.entries[ref]
            ?: return listOf(IconPart("App icon", null, false, "The manifest's icon is not in the resource table."))

        val parts = ArrayList<IconPart>()
        for (f in icon.files.filter { it.path.endsWith(".xml") }) {
            val bytes = readEntry(f.path) ?: continue
            if (!FileSniffer.isCompiledXml(bytes)) continue
            val x = try { AxmlEditor().parse(bytes) } catch (e: AxmlException) { continue }
            if (x.elements.firstOrNull()?.name != "adaptive-icon") continue
            for (role in listOf("foreground", "background", "monochrome")) {
                val child = x.elements.firstOrNull { it.parent == 0 && it.name == role } ?: continue
                // the picture is on the element itself, or on something wrapped inside it (an <inset>, for example)
                val target = (listOf(child) + x.elements.filter { it.depth > child.depth && isInside(x, it.index, child.index) })
                    .firstNotNullOfOrNull { e -> e.attrs.firstOrNull { it.name == "drawable" && it.namespace != null }?.reference }
                val label = role.replaceFirstChar { it.uppercase() }
                val entry = target?.let { table.entries[it] }
                parts += when {
                    entry == null -> IconPart("Icon $role", null, false, "Could not find what the $role refers to.")
                    entry.type == "color" -> IconPart("Icon $role (colour ${entry.value ?: ""})", entry, false, "This is a single colour, not a picture. Changing colours is planned.")
                    replaceable(entry) -> IconPart("Icon $role (${entry.type}/${entry.name})", entry, true, buildString {
                        if (plainImages(entry).isEmpty()) append("A vector drawing: your picture will replace it. ")
                        if (role == "monochrome") append("Used for themed icons (Android 13 and up).")
                    }.trim().ifEmpty { null })
                    else -> IconPart("Icon $role (${entry.type}/${entry.name})", entry, false, "This is not a picture or a drawing Apkforge can replace.")
                }
                if (label.isEmpty()) continue
            }
        }
        // the whole icon as one picture: every file of the icon resource is replaced, including the adaptive XML
        if (replaceable(icon)) {
            parts += IconPart(
                if (parts.isEmpty()) "App icon (${icon.type}/${icon.name})" else "Whole icon as one picture (${icon.type}/${icon.name})",
                icon, true,
                if (parts.isEmpty()) null else "Replaces the adaptive icon on every Android version with your picture, shown as a flat icon. Replacing only the foreground and background above keeps the adaptive look.",
            )
        } else if (parts.isEmpty()) {
            parts += IconPart("App icon (${icon.type}/${icon.name})", icon, false, "This icon is not a picture or drawing Apkforge can replace.")
        }
        return parts
    }

    private fun isInside(doc: AxmlDoc, index: Int, ancestor: Int): Boolean {
        var p = doc.elements[index].parent
        while (p >= 0) { if (p == ancestor) return true; p = doc.elements[p].parent }
        return false
    }
}
