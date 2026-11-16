package com.amandhakar.apkforge.build

/**
 * Lists and changes attribute values of any compiled XML file in an app (layouts, menus, vector drawings...). Only values
 * of attributes that are already there change: nothing is added, removed or moved, so the structure stays exactly as the
 * app expects it.
 */
object XmlEdit {
    private val EDITABLE = setOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x10, 0x11, 0x12, 0x1c, 0x1d, 0x1e, 0x1f)

    fun slots(path: String, bytes: ByteArray, nameOf: (Int) -> String?): List<ManifestSlot> {
        val doc = AxmlEditor().parse(bytes)
        val out = ArrayList<ManifestSlot>()
        for (el in doc.elements) {
            val id = el.attrs.firstOrNull { it.name == "id" && it.namespace != null }?.let { a -> a.reference?.let { nameOf(it) } ?: a.value }
            val title = "  ".repeat(minOf(el.depth, 12)) + el.name + (id?.let { "  $it" } ?: "")
            for (a in el.attrs) out += ManifestSlot(
                el.index, el.name, title, a.namespace, a.name, a.dataType, a.data, a.value, editable = a.dataType in EDITABLE, depth = el.depth, path = path,
            )
        }
        return out
    }

    private fun asValueSlot(s: ManifestSlot) = ValueSlot("", null, s.dataType, s.data, 0, s.text, false, s.dataType in EDITABLE)

    fun show(s: ManifestSlot, nameOf: (Int) -> String?): String = ValueText.show(asValueSlot(s), nameOf)

    /** A reference slot also accepts plain text: that replaces "@string/x" by the literal text. */
    fun parse(s: ManifestSlot, input: String, idOf: (String) -> Int?): ManifestParsed {
        if (s.dataType == 0x01) {
            val t = input.trim()
            if (t.isNotEmpty() && !t.startsWith("@") && !t.startsWith("?")) {
                if (input.length > ManifestEdit.MAX_TEXT || input.any { it.isISOControl() }) return ManifestParsed.Bad("Use plain text without control characters, up to ${ManifestEdit.MAX_TEXT} characters.")
                return ManifestParsed.Ok(0x03, 0, input)
            }
        }
        return when (val p = ValueText.parse(asValueSlot(s), input, idOf)) {
            is Parsed.Error -> ManifestParsed.Bad(p.message)
            is Parsed.Text -> ManifestParsed.Ok(0x03, 0, p.text)
            is Parsed.Value -> ManifestParsed.Ok(p.dataType, p.data, null)
        }
    }
}
