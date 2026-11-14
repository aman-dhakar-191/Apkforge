package com.amandhakar.apkforge

import com.amandhakar.apkforge.build.*
import com.amandhakar.apkforge.code.PatchDex
import com.amandhakar.apkforge.code.SmaliService
import com.amandhakar.apkforge.core.InspectionResult
import com.amandhakar.apkforge.inspect.ZipApkInspector
import com.amandhakar.apkforge.workspace.Workspace
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class PatchPipelineTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun res(n: String) = javaClass.getResourceAsStream("/$n")!!.readBytes()
    private val svc = SmaliService()
    private val plain = "Lcom/example/Plain;"
    private fun app() = TestZips.zip(tmp.newFile("app${tmp.root.list()!!.size}.apk"), listOf(
        "AndroidManifest.xml" to res("fx_manifest_full.bin"), "resources.arsc" to res("fx_resources.arsc"),
        "classes.dex" to res("dex_plain.bin"), "classes2.dex" to res("dex_obf.bin"),
    ))

    // ---- where the patch goes
    @Test fun existingDexFilesShiftUpByOne() {
        val (plan, why) = PatchDex.plan(listOf("AndroidManifest.xml", "classes2.dex", "classes.dex", "classes3.dex", "res/a.png"))
        assertNull(why)
        assertEquals(mapOf("classes.dex" to "classes2.dex", "classes2.dex" to "classes3.dex", "classes3.dex" to "classes4.dex"), plan!!.renames)
        assertEquals("classes.dex", plan.newDexName)
    }

    @Test fun refusesWhenShiftingWouldBeUnsafe() {
        assertTrue(PatchDex.plan(listOf("AndroidManifest.xml")).second!!.contains("no code"))
        assertTrue(PatchDex.plan(listOf("classes.dex", "classes3.dex")).second!!.contains("gaps")) // Android stops loading at a gap
        assertNull(PatchDex.plan(listOf("classes2.dex")).first) // a part without classes.dex
    }

    @Test fun apiLevelNeverGoesBelowAndroid8OrAboveWhatPhonesRun() {
        assertEquals(26, PatchDex.apiLevel(null)); assertEquals(26, PatchDex.apiLevel(21)); assertEquals(28, PatchDex.apiLevel(28)); assertEquals(34, PatchDex.apiLevel(36))
    }

    // ---- the whole way: edit a class, rebuild, sign, verify
    @Test fun patchedClassRunsFirstAndTheOriginalCodeIsUntouched() {
        val src = app()
        val edited = svc.disassemble(res("dex_plain.bin"), plain).replace("add-int", "sub-int")
        val a = svc.assemble(mapOf(plain to edited), PatchDex.apiLevel(26))
        assertNotNull(a.errors.toString(), a.dex)
        val entries = (ZipApkInspector().inspect(src) as InspectionResult.Ok).entries
        val plan = PatchDex.plan(entries.map { it.name }).first!!
        val unsigned = tmp.root.resolve("u.apk"); val signed = tmp.root.resolve("s.apk")
        ApkRebuilder().rebuild(src, unsigned, emptyMap(), plan.renames, mapOf(plan.newDexName to a.dex!!))
        ApkSigning().sign(unsigned, signed, SigningKeys(tmp.root.resolve("keys")).loadOrCreate(), 26)
        assertTrue(ApkChecks().verify(signed, 26).verified)

        ZipFile(signed).use { z ->
            fun bytes(n: String) = z.getInputStream(z.getEntry(n)).readBytes()
            assertArrayEquals(a.dex, bytes("classes.dex"))                 // the patch is first
            assertArrayEquals(res("dex_plain.bin"), bytes("classes2.dex")) // the old code is exactly as it was
            assertArrayEquals(res("dex_obf.bin"), bytes("classes3.dex"))
            assertEquals(1, z.entries().toList().count { it.name == "AndroidManifest.xml" })
        }
        // what the app would run: for Plain, the first dex that defines it
        val idx = svc.index(signed)
        assertEquals(listOf("classes.dex", "classes2.dex", "classes3.dex"), idx.dexEntries)
        assertEquals("classes.dex", idx.classes.first { it.descriptor == plain }.dexEntry)
        assertEquals("classes3.dex", idx.classes.first { it.descriptor == "La/a;" }.dexEntry)
        assertEquals(1, idx.classes.count { it.descriptor == plain }) // the shadowed old copy is not listed twice
        assertTrue(svc.disassemble(signed, idx.classes.first { it.descriptor == plain }).contains("sub-int"))
    }

    @Test fun rebuilderRulesForAddedAndRenamedFiles() {
        val src = app(); val r = ApkRebuilder()
        r.rebuild(src, tmp.root.resolve("ok.apk"), emptyMap(), mapOf("classes.dex" to "classes9.dex"), mapOf("extra.bin" to ByteArray(3)))
        ZipFile(tmp.root.resolve("ok.apk")).use { z -> assertNotNull(z.getEntry("classes9.dex")); assertNull(z.getEntry("classes.dex")); assertNotNull(z.getEntry("extra.bin")) }
        for ((ren, add) in listOf(
            emptyMap<String, String>() to mapOf("classes.dex" to ByteArray(3)),          // an addition takes an existing name
            mapOf("classes.dex" to "x.bin") to mapOf("x.bin" to ByteArray(3)),            // addition and rename collide
            mapOf("nothere.dex" to "classes9.dex") to emptyMap(),                         // renaming what is not there
            emptyMap<String, String>() to mapOf("../evil.dex" to ByteArray(3)),           // unsafe name
        )) try { r.rebuild(src, tmp.root.resolve("bad.apk"), emptyMap(), ren, add); fail(ren.toString()) } catch (_: RebuildException) {}
        assertFalse(tmp.root.resolve("bad.apk").exists())
    }

    // ---- storing patches in a project
    @Test fun patchesAreStoredPerProjectAndComeBackIdentically() {
        val w = Workspace(tmp.root.resolve("ws")); val p = w.importApk("x", ByteArray(4).inputStream(), 100)
        assertTrue(w.patches(p.id).isEmpty())
        val text = svc.disassemble(res("dex_plain.bin"), plain)
        w.savePatch(p.id, plain, text); w.savePatch(p.id, "Lcom/other/B;", "x")
        assertEquals(mapOf(plain to text, "Lcom/other/B;" to "x"), w.patches(p.id))
        w.savePatch(p.id, plain, "changed"); assertEquals("changed", w.patches(p.id)[plain])
        w.removePatch(p.id, plain); assertEquals(setOf("Lcom/other/B;"), w.patches(p.id).keys)
        assertTrue(w.delete(p.id)); assertTrue(w.patches(p.id).isEmpty())
    }

    @Test fun patchStorageRefusesBadInput() {
        val w = Workspace(tmp.root.resolve("ws2")); val p = w.importApk("x", ByteArray(4).inputStream(), 100)
        for (bad in listOf("com.example.Foo", "Lcom/ex ample;", "L;", "../../etc/passwd", "Lx;" + "a".repeat(400))) try { w.savePatch(p.id, bad, "x"); fail(bad) } catch (_: IllegalArgumentException) {}
        try { w.savePatch(p.id, plain, "x".repeat(Workspace.MAX_PATCH_CHARS + 1)); fail() } catch (_: IllegalArgumentException) {}
        repeat(Workspace.MAX_PATCHES) { w.savePatch(p.id, "Lc/C$it;", "x") }
        try { w.savePatch(p.id, "Lone/TooMany;", "x"); fail() } catch (_: IllegalArgumentException) {}
        w.savePatch(p.id, "Lc/C0;", "replaced") // an existing patch can still be changed at the limit
        // nothing was written outside the project, and file names are hashes
        assertTrue(tmp.root.resolve("ws2/${p.id}/patches").list()!!.all { it == "index.properties" || Regex("[0-9a-f]{16}\\.smali").matches(it) })
    }
}
