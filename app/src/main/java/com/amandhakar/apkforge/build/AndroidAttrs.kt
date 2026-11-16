package com.amandhakar.apkforge.build

import com.amandhakar.apkforge.core.ComplexValue

/** What Android's own `android:` attributes accept: resource id, allowed kinds of value, and names for enum/flag values. */
class AttrInfo(val name: String, val id: Int, val formats: Set<String>, val symbols: Map<String, Int>) {
    val isFlags get() = "flags" in formats
}

/** The public `android:` attributes of Android 15 (read from the platform's android.jar), 1,574 of them. */
object AndroidAttrs {
    const val NS = "http://schemas.android.com/apk/res/android"

    val all: List<AttrInfo> by lazy {
        val text = AndroidAttrs::class.java.getResourceAsStream("/android_attrs.txt")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return@lazy emptyList()
        text.lineSequence().filter { it.isNotBlank() }.map { line ->
            val p = line.split('|')
            AttrInfo(
                p[0], java.lang.Long.decode(p[1]).toInt(), p[2].split(',').filter { it.isNotEmpty() }.toSet(),
                p.getOrElse(3) { "" }.split(',').filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=').toInt() },
            )
        }.toList()
    }
    private val byName by lazy { all.associateBy { it.name } }
    private val byId by lazy { all.associateBy { it.id } }

    fun info(name: String): AttrInfo? = byName[name]
    fun byId(id: Int): AttrInfo? = byId[id]
}

/** Turning what a person types into an attribute value, and an attribute value into what a person reads. */
object AttrValues {
    /** [info] says what the attribute accepts; null (an attribute of the app itself) falls back to the kind of the old value. */
    fun parse(info: AttrInfo?, oldType: Int, input: String, idOf: (String) -> Int?): ManifestParsed {
        val s = input.trim()
        fun slot(type: Int) = ValueSlot("", null, type, 0, 0, null, false, true)
        fun fromValueText(type: Int): ManifestParsed = when (val p = ValueText.parse(slot(type), input, idOf)) {
            is Parsed.Error -> ManifestParsed.Bad(p.message)
            is Parsed.Text -> ManifestParsed.Ok(0x03, 0, p.text)
            is Parsed.Value -> ManifestParsed.Ok(p.dataType, p.data, null)
        }
        if (s.startsWith("@")) return fromValueText(0x01)
        if (info == null) {
            // not one of Android's attributes: keep the kind it already has (a reference may be replaced by text)
            if (oldType == 0x01 && s.isNotEmpty() && !s.startsWith("?")) return ManifestParsed.Ok(0x03, 0, input)
            return fromValueText(oldType)
        }
        if (info.symbols.isNotEmpty()) {
            if (info.isFlags) {
                val parts = s.split('|').map { it.trim() }
                if (parts.isNotEmpty() && parts.all { it in info.symbols }) return ManifestParsed.Ok(0x11, parts.fold(0) { acc, p -> acc or info.symbols.getValue(p) }, null)
            } else info.symbols[s]?.let { return ManifestParsed.Ok(0x10, it, null) }
        }
        val f = info.formats
        if (s.startsWith("#") && ("color" in f || "reference" in f)) return fromValueText(0x1c)
        if (("boolean" in f) && (s.equals("true", true) || s.equals("false", true))) return fromValueText(0x12)
        if ("dimension" in f) ComplexValue.parseDimension(s)?.let { return ManifestParsed.Ok(0x05, it, null) }
        if ("fraction" in f) ComplexValue.parseFraction(s)?.let { return ManifestParsed.Ok(0x06, it, null) }
        if ("integer" in f || "enum" in f || "flags" in f) {
            val p = ValueText.parse(slot(0x10), input, idOf)
            if (p is Parsed.Value) return ManifestParsed.Ok(p.dataType, p.data, null)
        }
        if ("float" in f) s.toFloatOrNull()?.takeIf { it.isFinite() }?.let { return ManifestParsed.Ok(0x04, java.lang.Float.floatToIntBits(it), null) }
        if ("string" in f) {
            if (input.length > ManifestEdit.MAX_TEXT || input.any { it.isISOControl() && it != '\n' && it != '\t' }) return ManifestParsed.Bad("Use up to ${ManifestEdit.MAX_TEXT} characters, without control characters.")
            return ManifestParsed.Ok(0x03, 0, input)
        }
        return ManifestParsed.Bad("This attribute takes: ${describeFormats(info)}.")
    }

    fun describeFormats(info: AttrInfo): String {
        val parts = ArrayList<String>()
        if (info.symbols.isNotEmpty()) parts += (if (info.isFlags) "one or more of " else "one of ") + info.symbols.keys.take(12).joinToString(", ") + if (info.symbols.size > 12) ", …" else ""
        parts += info.formats.filter { it != "enum" && it != "flags" }.map {
            when (it) { "reference" -> "a reference like @string/name"; "color" -> "a colour like #ff0000"; "dimension" -> "a size like 16dp"; "boolean" -> "true or false"; "integer" -> "a whole number"; "float" -> "a number"; "fraction" -> "a fraction like 50%"; "string" -> "text"; else -> it }
        }
        return parts.joinToString("; ").ifEmpty { "a value" }
    }

    fun show(a: XAttr, nameOf: (Int) -> String?): String {
        val info = a.resId.takeIf { it != 0 }?.let { AndroidAttrs.byId(it) }
        if (a.type == 0x10 || a.type == 0x11) {
            if (info != null && info.symbols.isNotEmpty()) {
                if (info.isFlags) {
                    if (a.data == 0) info.symbols.entries.firstOrNull { it.value == 0 }?.let { return it.key }
                    val names = ArrayList<String>(); var rest = a.data
                    for ((n, v) in info.symbols.entries.sortedByDescending { Integer.bitCount(it.value) }) if (v != 0 && rest and v == v) { names += n; rest = rest and v.inv() }
                    if (rest == 0 && names.isNotEmpty()) return names.sorted().joinToString("|")
                } else info.symbols.entries.firstOrNull { it.value == a.data }?.let { return it.key }
            }
        }
        val slot = ValueSlot("", null, a.type, a.data, 0, a.text, false, true)
        return ValueText.show(slot, nameOf)
    }
}
