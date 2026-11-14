package com.amandhakar.apkforge.build

import com.amandhakar.apkforge.inspect.AxmlException

/** One attribute of the manifest that can be shown and, if [editable], changed. [element] is the tag's position in the file. */
class ManifestSlot(
    val element: Int, val tag: String, val title: String, val namespace: String?, val name: String,
    val dataType: Int, val data: Int, val text: String?, val editable: Boolean,
) {
    val key get() = "$element/${namespace.orEmpty()}/$name"
    val label get() = (if (namespace != null) "android:" else "") + name
}

/** A planned manifest change: the new text, number or flag of one attribute. */
class ManifestChange(val element: Int, val namespace: String?, val name: String, val dataType: Int, val data: Int, val text: String?) {
    val key get() = "$element/${namespace.orEmpty()}/$name"
    fun patch() = if (text != null) AttrPatch(element, namespace, name, text) else AttrPatch(element, namespace, name, dataType, data)
}

sealed interface ManifestParsed {
    class Ok(val dataType: Int, val data: Int, val text: String?) : ManifestParsed
    class Bad(val message: String) : ManifestParsed
}

/** Lists and rewrites attribute values of the compiled AndroidManifest.xml. Only text, whole numbers and on/off flags. */
object ManifestEdit {
    /** The package name is changed through "Install as a separate app", which also keeps everything that refers to it consistent. */
    private val LOCKED = setOf("package")
    private val EDITABLE_TYPES = setOf(0x03, 0x10, 0x11, 0x12)
    const val MAX_TEXT = 1000

    fun slots(manifest: ByteArray): List<ManifestSlot> {
        val doc = AxmlEditor().parse(manifest)
        val out = ArrayList<ManifestSlot>()
        for (el in doc.elements) {
            val nm = el.attrs.firstOrNull { it.name == "name" && it.namespace != null }?.value
            val title = if (nm != null) "${el.name}  $nm" else el.name
            for (a in el.attrs) out += ManifestSlot(
                el.index, el.name, title, a.namespace, a.name, a.dataType, a.data, a.value,
                editable = a.dataType in EDITABLE_TYPES && !(a.namespace == null && a.name in LOCKED),
            )
        }
        return out
    }

    fun show(s: ManifestSlot): String = when (s.dataType) {
        0x03 -> s.text.orEmpty()
        0x10 -> s.data.toString()
        0x11 -> "0x" + Integer.toHexString(s.data)
        0x12 -> if (s.data != 0) "true" else "false"
        0x01 -> "@0x" + Integer.toHexString(s.data)
        else -> "(type 0x${Integer.toHexString(s.dataType)})"
    }

    fun parse(s: ManifestSlot, input: String): ManifestParsed {
        val t = input.trim()
        return when (s.dataType) {
            0x03 -> when {
                input.length > MAX_TEXT -> ManifestParsed.Bad("Use $MAX_TEXT characters or fewer.")
                input.any { it.isISOControl() } -> ManifestParsed.Bad("No control characters, please.")
                else -> ManifestParsed.Ok(0x03, 0, input)
            }
            0x12 -> when (t.lowercase()) {
                "true" -> ManifestParsed.Ok(0x12, -1, null)
                "false" -> ManifestParsed.Ok(0x12, 0, null)
                else -> ManifestParsed.Bad("Type true or false.")
            }
            0x10, 0x11 -> {
                val hex = t.startsWith("0x", true)
                val n = (if (hex) t.drop(2).toLongOrNull(16) else t.toLongOrNull())
                when {
                    n == null -> ManifestParsed.Bad("Type a whole number, for example 12 or 0x1f.")
                    n !in Int.MIN_VALUE.toLong()..0xFFFFFFFFL -> ManifestParsed.Bad("That number is too large.")
                    else -> ManifestParsed.Ok(if (hex) 0x11 else 0x10, n.toInt(), null)
                }
            }
            else -> ManifestParsed.Bad("This kind of value cannot be edited.")
        }
    }

    fun apply(manifest: ByteArray, changes: List<ManifestChange>): ByteArray {
        val editor = AxmlEditor()
        val doc = editor.parse(manifest)
        if (changes.map { it.key }.toSet().size != changes.size) throw AxmlException("the same value is changed twice")
        return editor.apply(manifest, doc, changes.map { it.patch() })
    }
}
