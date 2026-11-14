package com.amandhakar.apkforge

import com.amandhakar.apkforge.code.SmaliClass
import com.amandhakar.apkforge.code.SmaliException
import com.amandhakar.apkforge.code.SmaliService
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The dex fixtures were produced by Android's own d8 from small Java classes. dex_rich has lambdas, switches, try/catch, generics, an interface with a default method. */
class SmaliServiceTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun res(n: String) = javaClass.getResourceAsStream("/$n")!!.readBytes()
    private val svc = SmaliService()
    private val rich = listOf("Lcom/example/rich/Rich;", "Lcom/example/rich/Rich\$Node;", "Lcom/example/rich/Shape;",
        "Lcom/example/rich/Rich\$\$ExternalSyntheticLambda0;", "Lcom/example/rich/Rich\$\$ExternalSyntheticLambda1;", "Lcom/example/rich/Rich\$\$ExternalSyntheticLambda2;")

    private fun apk(vararg dex: Pair<String, ByteArray>) = TestZips.zip(tmp.newFile("a${tmp.root.list()!!.size}.apk"), listOf("AndroidManifest.xml" to ByteArray(8)) + dex)

    @Test fun listsEveryClassAndWhichFileItIsIn() {
        val a = apk("classes.dex" to res("dex_rich.bin"), "classes2.dex" to res("dex_obf.bin"))
        val idx = svc.index(a)
        assertEquals(listOf("classes.dex", "classes2.dex"), idx.dexEntries)
        assertTrue(idx.classes.map { it.descriptor }.containsAll(rich))
        assertEquals("classes2.dex", idx.classes.first { it.descriptor == "La/a;" }.dexEntry)
        assertEquals("com.example.rich.Rich", idx.classes.first { it.descriptor == "Lcom/example/rich/Rich;" }.name)
        assertEquals(setOf("Lcom/example/rich/Rich;"), idx.search("rich", 50).map { it.descriptor }.filter { it == "Lcom/example/rich/Rich;" }.toSet())
        assertEquals(2, idx.search("", 2).size) // the limit holds
        assertTrue(idx.unreadable.isEmpty())
    }

    @Test fun aDamagedDexFileDoesNotHideTheOthers() {
        val a = apk("classes.dex" to res("dex_rich.bin"), "classes2.dex" to ByteArray(500) { 7 })
        val idx = svc.index(a)
        assertEquals(listOf("classes2.dex"), idx.unreadable)
        assertTrue(idx.classes.any { it.descriptor == "Lcom/example/rich/Rich;" })
    }

    @Test fun readsARealClassAsSmali() {
        val text = svc.disassemble(res("dex_rich.bin"), "Lcom/example/rich/Rich;")
        assertTrue(text, text.contains(".class public Lcom/example/rich/Rich;"))
        assertTrue(text.contains(".implements Lcom/example/rich/Shape;"))
        assertTrue(text.contains(".method public grade(I)Ljava/lang/String;"))
        assertTrue(text.contains("switch")) // the score / 10 switch
        assertTrue(text.contains(".catch Ljava/lang/NumberFormatException;"))
        // smali writes non-Latin characters as \u escapes; they assemble back exactly (the round-trip tests below prove it)
        assertTrue(text, text.contains("\\u00fc") && text.contains("\\u65e5\\u672c\\u8a9e"))
        assertTrue(text.contains("shl-long") || text.contains("ushr-long") || text.contains("xor-long"))
    }

    @Test fun smaliAssemblesBackToTheSameCode_everyClass() {
        // disassemble -> assemble -> disassemble must give the identical text: nothing is lost or changed on the way
        for (d in rich) {
            val text = svc.disassemble(res("dex_rich.bin"), d)
            val r = svc.assemble(mapOf(d to text), 26)
            assertNotNull("$d: ${r.errors}", r.dex)
            assertEquals(d, text, svc.disassemble(r.dex!!, d))
        }
    }

    @Test fun severalClassesAssembleTogether() {
        val src = rich.take(3).associateWith { svc.disassemble(res("dex_rich.bin"), it) }
        val r = svc.assemble(src, 26)
        assertNotNull(r.errors.toString(), r.dex)
        for ((d, t) in src) assertEquals(t, svc.disassemble(r.dex!!, d))
    }

    @Test fun aPatchChangesWhatTheCodeDoes() {
        val d = "Lcom/example/Plain;"
        val original = svc.disassemble(res("dex_plain.bin"), d)
        assertTrue(original, original.contains("add-int"))
        val patched = original.replace("add-int", "sub-int")
        val r = svc.assemble(mapOf(d to patched), 26)
        assertNotNull(r.errors.toString(), r.dex)
        val again = svc.disassemble(r.dex!!, d)
        assertTrue(again.contains("sub-int") && !again.contains("add-int"))
    }

    @Test fun mistakesAreReportedWithWhereTheyAre() {
        val d = "Lcom/example/Plain;"
        val bad = svc.disassemble(res("dex_plain.bin"), d).replace("add-int", "add-intt")
        val r = svc.assemble(mapOf(d to bad), 26)
        assertNull(r.dex)
        assertTrue(r.errors.toString(), r.errors.any { Regex("\\[\\d+,\\d+\\]").containsMatchIn(it) }) // c0.smali[line,column]
        assertTrue(r.errors.none { it.contains("/tmp") || it.contains(tmp.root.path) }) // no temp paths shown
    }

    @Test fun changingTheClassLineIsCaught() {
        val d = "Lcom/example/Plain;"
        val renamed = svc.disassemble(res("dex_plain.bin"), d).replace("Lcom/example/Plain;", "Lcom/example/Other;")
        val r = svc.assemble(mapOf(d to renamed), 26)
        assertNull(r.dex)
        assertTrue(r.errors.toString(), r.errors.any { it.contains(".class line must stay") })
    }

    @Test fun theApiLevelPicksTheDexVersionSoOlderPhonesStillLoadIt() {
        val d = "Lcom/example/Plain;"
        val t = svc.disassemble(res("dex_plain.bin"), d)
        fun version(api: Int) = String(svc.assemble(mapOf(d to t), api).dex!!, 4, 3)
        assertEquals("038", version(26)); assertEquals("039", version(28))
    }

    @Test fun nonsenseIsRefusedCleanly() {
        try { svc.disassemble(ByteArray(300), "Lx;"); fail() } catch (_: SmaliException) {}
        try { svc.disassemble(res("dex_plain.bin"), "Lnot/There;"); fail() } catch (e: SmaliException) { assertTrue(e.message!!.contains("not in this dex")) }
        assertNull(svc.assemble(emptyMap(), 26).dex)
        assertNotNull(SmaliClass("Lа/б;", "classes.dex").name)
    }
}
