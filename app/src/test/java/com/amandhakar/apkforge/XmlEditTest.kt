package com.amandhakar.apkforge

import com.amandhakar.apkforge.build.*
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.ZipFile

class XmlEditTest {
    private fun layout(): ByteArray = ZipFile(java.io.File(javaClass.getResource("/fx_layout.apk")!!.toURI())).use { z ->
        z.getInputStream(z.entries().asSequence().filter { it.name.startsWith("res/layout") }.maxByOrNull { it.size }!!).readBytes()
    }
    private val none: (Int) -> String? = { null }

    @Test fun listsViewsAndEditsTextColourSizeAndFlagsOnly() {
        val m = layout()
        val before = XmlEdit.slots("res/layout/x.xml", m, none)
        assertTrue(before.any { it.tag == "TextView" })
        fun find(tag: String, name: String) = before.first { it.tag == tag && it.name == name }
        val text = find("TextView", "text"); val color = find("TextView", "textColor"); val size = find("TextView", "textSize")
        val vis = before.firstOrNull { it.name == "visibility" }
        fun change(s: ManifestSlot, input: String): ManifestChange {
            val p = XmlEdit.parse(s, input, { null }) as ManifestParsed.Ok
            return ManifestChange(s.element, s.namespace, s.name, p.dataType, p.data, p.text, s.path)
        }
        val changes = mutableListOf(change(text, "Edited text"), change(color, "#ff0000"), change(size, "20sp"))
        if (vis != null) changes += change(vis, "8")
        val out = ManifestEdit.apply(m, changes)
        val after = XmlEdit.slots("res/layout/x.xml", out, none)
        assertEquals(before.size, after.size)
        for ((b, a) in before.zip(after)) {
            val c = changes.firstOrNull { it.key == b.key }
            if (c == null) assertEquals("${b.key} must be unchanged", XmlEdit.show(b, none), XmlEdit.show(a, none))
        }
        assertEquals("Edited text", XmlEdit.show(after.first { it.key == text.key }, none))
        assertEquals("#ff0000", XmlEdit.show(after.first { it.key == color.key }, none))
        assertEquals("20.0sp", XmlEdit.show(after.first { it.key == size.key }, none).replace("20sp", "20.0sp"))
    }

    @Test fun aReferenceCanBeReplacedByPlainText() {
        val s = ManifestSlot(0, "TextView", "t", "ns", "text", 0x01, 0x7f010000, null, true)
        val p = XmlEdit.parse(s, "Hello", { null }) as ManifestParsed.Ok
        assertEquals(0x03, p.dataType); assertEquals("Hello", p.text)
        assertEquals(0x7f010001, ((XmlEdit.parse(s, "@string/x", { 0x7f010001 }) as ManifestParsed.Ok).data))
        assertTrue(XmlEdit.parse(s, "@string/nope", { null }) is ManifestParsed.Bad)
    }
}
