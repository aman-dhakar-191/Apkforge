package com.amandhakar.apkforge

import com.amandhakar.apkforge.build.*
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.ZipFile

class ManifestEditTest {
    private fun manifest(): ByteArray = ZipFile(java.io.File(javaClass.getResource("/fx_values.apk")!!.toURI())).use { z -> z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes() }

    @Test fun listsAttributesAndLocksThePackageName() {
        val s = ManifestEdit.slots(manifest())
        assertTrue(s.isNotEmpty())
        val pkg = s.first { it.name == "package" }
        assertFalse(pkg.editable)
        assertTrue(s.any { it.editable })
    }

    @Test fun textNumbersAndFlagsAreChangedAndNothingElse() {
        val m = manifest()
        val before = ManifestEdit.slots(m)
        val text = before.first { it.dataType == 0x03 && it.editable }
        val changes = mutableListOf(ManifestChange(text.element, text.namespace, text.name, 0x03, 0, "Edited value"))
        val num = before.firstOrNull { (it.dataType == 0x10 || it.dataType == 0x11) && it.editable }
        if (num != null) changes += ManifestChange(num.element, num.namespace, num.name, 0x10, 77, null)
        val out = ManifestEdit.apply(m, changes)
        val after = ManifestEdit.slots(out)
        assertEquals(before.size, after.size)
        for ((b, a) in before.zip(after)) {
            val c = changes.firstOrNull { it.key == b.key }
            if (c == null) assertEquals("${b.key} must be unchanged", ManifestEdit.show(b), ManifestEdit.show(a))
            else assertEquals(if (c.text != null) c.text else "77", ManifestEdit.show(a))
        }
    }

    @Test fun parsingChecksInput() {
        fun slot(t: Int) = ManifestSlot(0, "x", "x", null, "n", t, 0, "", true)
        assertTrue(ManifestEdit.parse(slot(0x12), "maybe") is ManifestParsed.Bad)
        assertEquals(-1, (ManifestEdit.parse(slot(0x12), "True") as ManifestParsed.Ok).data)
        assertEquals(31, (ManifestEdit.parse(slot(0x10), "0x1f") as ManifestParsed.Ok).data)
        assertEquals(0x11, (ManifestEdit.parse(slot(0x10), "0x1f") as ManifestParsed.Ok).dataType)
        assertTrue(ManifestEdit.parse(slot(0x10), "abc") is ManifestParsed.Bad)
        assertTrue(ManifestEdit.parse(slot(0x03), "a\u0000b") is ManifestParsed.Bad)
    }
}
