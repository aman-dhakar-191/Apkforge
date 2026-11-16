package com.amandhakar.apkforge

import com.amandhakar.apkforge.build.*
import com.amandhakar.apkforge.build.LayoutOp.*
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.ZipFile

class LayoutEditorTest {
    private val none: (Int) -> String? = { null }
    private fun layout(): ByteArray = ZipFile(java.io.File(javaClass.getResource("/fx_layout.apk")!!.toURI())).use { z ->
        z.getInputStream(z.entries().asSequence().filter { it.name.startsWith("res/layout") }.maxByOrNull { it.size }!!).readBytes()
    }
    private fun names(t: AxmlTree) = t.root.children.filterIsInstance<XElement>().map { it.name }

    @Test fun parseAndWriteAreLossless() {
        val m = layout()
        val t = AxmlTree.parse(m)
        val again = AxmlTree.parse(t.serialize())
        assertEquals(names(t), names(again))
        assertEquals(t.root.attrs.map { it.name to it.type }, again.root.attrs.map { it.name to it.type })
    }

    @Test fun attributesAreAddedInResourceIdOrder() {
        val t = AxmlTree.parse(layout())
        LayoutEditor.applyTo(t, listOf(AddAttr(listOf(0), NewAttr("background", 0x1c, 0xff00ff00.toInt(), null), "#00ff00"), AddAttr(listOf(0), NewAttr("alpha", 0x04, java.lang.Float.floatToIntBits(0.5f), null), "0.5")))
        val ids = LayoutEditor.node(t, listOf(0)).attrs.map { it.resId }
        assertEquals("sorted by id", ids.sorted(), ids)
        val back = AxmlTree.parse(t.serialize())
        assertEquals(LayoutEditor.node(t, listOf(0)).attrs.map { it.name }, LayoutEditor.node(back, listOf(0)).attrs.map { it.name })
    }

    @Test fun viewsCanBeInsertedMovedCopiedAndRemoved() {
        val m = layout()
        val before = names(AxmlTree.parse(m))
        val wrap = NewAttr("layout_width", 0x10, -2, null); val hig = NewAttr("layout_height", 0x10, -2, null)
        val txt = NewAttr("text", 0x03, 0, "New label")
        val out = LayoutEditor.apply(m, listOf(
            InsertView(listOf(), 0, "TextView", listOf(wrap, hig, txt)),
            DuplicateView(listOf(0), "TextView"),
            MoveView(listOf(1), listOf(), 3, "TextView"),
            RemoveView(listOf(0), "TextView"),
        ))
        val t = AxmlTree.parse(out)
        // [TextView, Button] -> insert -> [new, TextView, Button] -> copy -> [new, copy, TextView, Button] -> move copy to the end -> remove first
        assertEquals(before + "TextView", names(t))
        val last = LayoutEditor.node(t, listOf(names(t).size - 1))
        assertEquals("New label", last.attr(AndroidAttrs.NS, "text")!!.text)
        assertTrue("the copy has no id", last.attrs.none { it.resId == 0x010100d0 })
    }

    @Test fun badOperationsAreRefusedAndTheOutermostViewIsProtected() {
        val m = layout()
        for (op in listOf<LayoutOp>(RemoveView(listOf(), "root"), MoveView(listOf(), listOf(0), 0, "root"), DuplicateView(listOf(), "root"), RemoveView(listOf(9), "x"),
                SetAttr(listOf(0), AndroidAttrs.NS, "nonexistent", 3, 0, "x", "x"), InsertView(listOf(), 0, "bad name!", emptyList()), AddAttr(listOf(), NewAttr("notAnAttr", 3, 0, "x"), "x")))
            try { LayoutEditor.apply(m, listOf(op)); fail(op.describe()) } catch (e: com.amandhakar.apkforge.inspect.AxmlException) { }
    }

    @Test fun attributeValuesUseAndroidsOwnNames() {
        val w = AndroidAttrs.info("layout_width")!!
        assertEquals(-1, (AttrValues.parse(w, 0x10, "match_parent", { null }) as ManifestParsed.Ok).data)
        assertEquals(0x05, (AttrValues.parse(w, 0x10, "120dp", { null }) as ManifestParsed.Ok).dataType)
        val g = AndroidAttrs.info("gravity")!!
        val p = AttrValues.parse(g, 0x11, "center_horizontal|top", { null }) as ManifestParsed.Ok
        assertEquals(0x11, p.dataType); assertEquals(1 or 48, p.data)
        assertTrue(AttrValues.parse(g, 0x11, "sideways", { null }) is ManifestParsed.Bad)
        assertEquals("match_parent", AttrValues.show(XAttr(AndroidAttrs.NS, "layout_width", w.id, 0x10, -1, null), none).let { if (it == "fill_parent") "match_parent" else it })
        assertTrue(AndroidAttrs.all.size > 1500)
    }
}
