package com.amandhakar.apkforge

import com.amandhakar.apkforge.build.*
import com.amandhakar.apkforge.inspect.AxmlDecoder
import com.amandhakar.apkforge.inspect.AxmlException
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Builds a tiny compiled manifest by hand, independently of the editor's own pool encoder. */
object TestAxml {
    const val ANDROID = "http://schemas.android.com/apk/res/android"

    fun build(utf8: Boolean, pkg: String = "a.b", label: String = "Name", styleCount: Int = 0): ByteArray {
        val strings = listOf("package", pkg, "manifest", "application", "label", label, ANDROID, "android")
        val pool = pool(strings, utf8, styleCount)
        val body = ByteArrayOutputStream()
        fun chunk(type: Int, header: Int, content: ByteBuffer.() -> Unit, size: Int) {
            val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            b.putShort(type.toShort()).putShort(header.toShort()).putInt(size).putInt(1).putInt(-1)
            b.content(); body.write(b.array())
        }
        chunk(0x0100, 16, { putInt(7); putInt(6) }, 24)
        chunk(0x0102, 16, { putInt(-1); putInt(2); putShort(20); putShort(20); putShort(1); putShort(0); putShort(0); putShort(0)
            putInt(-1); putInt(0); putInt(1); putShort(8); put(0); put(3); putInt(1) }, 56)
        chunk(0x0102, 16, { putInt(-1); putInt(3); putShort(20); putShort(20); putShort(1); putShort(0); putShort(0); putShort(0)
            putInt(6); putInt(4); putInt(5); putShort(8); put(0); put(3); putInt(5) }, 56)
        chunk(0x0103, 16, { putInt(-1); putInt(3) }, 24)
        chunk(0x0103, 16, { putInt(-1); putInt(2) }, 24)
        chunk(0x0101, 16, { putInt(7); putInt(6) }, 24)
        val total = 8 + pool.size + body.size()
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(3).putShort(8).putInt(total).put(pool).put(body.toByteArray())
        return out.array()
    }

    private fun pool(strings: List<String>, utf8: Boolean, styleCount: Int): ByteArray {
        val data = ByteArrayOutputStream(); val offs = ArrayList<Int>()
        for (s in strings) {
            offs += data.size()
            if (utf8) {
                val raw = s.toByteArray(Charsets.UTF_8)
                fun v(n: Int) { if (n > 0x7F) { data.write((n shr 8) or 0x80); data.write(n and 0xFF) } else data.write(n) }
                v(s.length); v(raw.size); data.write(raw); data.write(0)
            } else {
                data.write(s.length and 0xFF); data.write(s.length shr 8)
                for (c in s) { data.write(c.code and 0xFF); data.write(c.code shr 8) }
                data.write(0); data.write(0)
            }
        }
        while (data.size() % 4 != 0) data.write(0)
        val start = 28 + 4 * strings.size + 4 * styleCount
        val size = start + data.size()
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(1).putShort(28).putInt(size).putInt(strings.size).putInt(styleCount).putInt(if (utf8) 0x100 else 0).putInt(start).putInt(0)
        offs.forEach { b.putInt(it) }
        repeat(styleCount) { b.putInt(0) }
        b.put(data.toByteArray())
        return b.array()
    }
}

class AxmlEditorTest {
    private fun res(n: String) = javaClass.getResourceAsStream("/$n")!!.readBytes()
    private val editor = AxmlEditor()
    private val decoder = AxmlDecoder()

    @Test fun parsesElementsAndAttributesOfARealManifest() {
        val doc = editor.parse(res("fx_clone_manifest.bin"))
        assertEquals("manifest", doc.elements[0].name)
        assertEquals("com.example.clonefx", doc.elements[0].attrs.first { it.name == "package" }.value)
        assertTrue(doc.elements.any { it.name == "provider" })
        assertEquals(doc.elements.count { it.name == "activity" }, 2)
        assertEquals(-1, doc.elements[0].parent); assertEquals(0, doc.elements[1].depth.let { if (it == 1) 0 else 1 })
    }

    @Test fun applyingNothingKeepsTheMeaning() {
        val bytes = res("fx_clone_manifest.bin")
        val again = editor.apply(bytes, editor.parse(bytes), emptyList())
        assertEquals(decoder.decode(bytes), decoder.decode(again))
    }

    @Test fun changesOnlyThePatchedAttribute() {
        val bytes = res("fx_clone_manifest.bin")
        val doc = editor.parse(bytes)
        val out = editor.apply(bytes, doc, listOf(AttrPatch(0, null, "package", "com.example.clonefx.forge")))
        val before = decoder.decode(bytes).lines(); val after = decoder.decode(out).lines()
        val changed = before.indices.filter { before[it] != after[it] }
        assertEquals(1, changed.size)
        assertTrue(after[changed[0]].contains("package=\"com.example.clonefx.forge\""))
    }

    @Test fun worksWithBothStringEncodingsAndAwkwardStrings() {
        val long = "x".repeat(300)
        for (utf8 in listOf(false, true)) for (value in listOf("c.d.e", "नया ऐप (copy)", long, "é ü 日本語")) {
            val bytes = TestAxml.build(utf8)
            val doc = editor.parse(bytes)
            val out = editor.apply(bytes, doc, listOf(AttrPatch(0, null, "package", value), AttrPatch(1, TestAxml.ANDROID, "label", value + "!")))
            val d = decoder.decodeFull(out)
            assertEquals("utf8=$utf8", value, d.rootAttributes["package"])
            assertEquals(value + "!", d.applicationAttributes["label"])
            assertEquals(utf8, editor.parse(out).utf8) // keeps the file's own encoding
        }
    }

    @Test fun sameNewStringIsStoredOnce() {
        val bytes = TestAxml.build(true)
        val doc = editor.parse(bytes)
        val out = editor.apply(bytes, doc, listOf(AttrPatch(0, null, "package", "same"), AttrPatch(1, TestAxml.ANDROID, "label", "same")))
        assertEquals(doc.strings.size + 1, editor.parse(out).strings.size)
        assertEquals(doc.strings.size, editor.parse(editor.apply(bytes, doc, listOf(AttrPatch(0, null, "package", "a.b")))).strings.size) // existing string reused
    }

    @Test fun existingStringIndexesStayValid() {
        val bytes = res("fx_clone_manifest.bin")
        val doc = editor.parse(bytes)
        val out = editor.parse(editor.apply(bytes, doc, listOf(AttrPatch(0, null, "package", "p.q"))))
        assertEquals(doc.strings, out.strings.take(doc.strings.size))
    }

    @Test fun patchingSomethingThatIsNotThereFails() {
        val bytes = TestAxml.build(false); val doc = editor.parse(bytes)
        for (p in listOf(AttrPatch(9, null, "package", "x"), AttrPatch(0, null, "nope", "x"), AttrPatch(0, TestAxml.ANDROID, "package", "x"))) {
            try { editor.apply(bytes, doc, listOf(p)); fail() } catch (_: AxmlException) {}
        }
    }

    @Test fun styledStringTablesAreRefused() {
        try { editor.parse(TestAxml.build(false, styleCount = 1)); fail() } catch (e: AxmlException) { assertTrue(e.message!!.contains("styled")) }
    }

    @Test fun damagedFilesOnlyFailCleanly() {
        val bytes = res("fx_clone_manifest.bin")
        for (n in 0 until bytes.size step 11) try { editor.parse(bytes.copyOf(n)) } catch (_: AxmlException) {}
        val rnd = java.util.Random(9)
        repeat(400) {
            val c = bytes.copyOf(); repeat(5) { c[rnd.nextInt(c.size)] = rnd.nextInt(256).toByte() }
            try { val d = editor.parse(c); editor.apply(c, d, listOf(AttrPatch(0, null, "package", "z.y"))) } catch (_: AxmlException) {}
        }
    }
}
