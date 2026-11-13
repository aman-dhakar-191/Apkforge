package com.amandhakar.apkforge

import com.amandhakar.apkforge.core.*
import com.amandhakar.apkforge.inspect.SplitSetReader
import com.amandhakar.apkforge.workspace.InstalledApkImporter
import com.amandhakar.apkforge.workspace.InstalledImport
import com.amandhakar.apkforge.workspace.Workspace
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SplitSetTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun res(n: String) = javaClass.getResourceAsStream("/$n")!!.readBytes()
    private fun ws() = Workspace(tmp.root.resolve("ws"))
    private fun file(dir: String, name: String, size: Int = 100): File =
        tmp.root.resolve(dir).apply { mkdirs() }.resolve(name).apply { writeBytes(ByteArray(size) { 5 }) }

    // ---- importing a split app
    @Test fun copiesBaseAndAllParts() {
        val base = file("app", "base.apk"); val a = file("app", "split_config.arm64_v8a.apk", 50); val b = file("app", "split_config.xxhdpi.apk", 60)
        val w = ws()
        val r = InstalledApkImporter(w, 1000).import("Some App", base.path, listOf(a.path, b.path), isSystem = false) as InstalledImport.Imported
        assertEquals(2, r.splitsCopied); assertEquals(0, r.splitsFailed)
        assertEquals(listOf("split_config.arm64_v8a.apk", "split_config.xxhdpi.apk"), w.splitFiles(r.project.id).map { it.name })
        val saved = w.get(r.project.id)!!
        assertEquals(2, saved.declaredSplits); assertEquals(2, saved.copiedSplits); assertFalse(saved.system)
    }

    @Test fun oneRefusedPartDoesNotUndoTheRest() {
        val base = file("app", "base.apk"); val ok = file("app", "split_a.apk")
        val missing = tmp.root.resolve("app/split_gone.apk").path
        val w = ws()
        val r = InstalledApkImporter(w, 1000).import("x", base.path, listOf(missing, ok.path)) as InstalledImport.Imported
        assertEquals(1, r.splitsCopied); assertEquals(1, r.splitsFailed)
        assertEquals(1, w.splitFiles(r.project.id).size)
        assertEquals(2, w.get(r.project.id)!!.declaredSplits); assertEquals(1, w.get(r.project.id)!!.copiedSplits)
        assertTrue(w.originalApk(r.project.id)!!.isFile)
    }

    @Test fun privateDataPathsForPartsAreRefused() {
        val base = file("app", "base.apk")
        val r = InstalledApkImporter(ws(), 1000).import("x", base.path, listOf("/data/data/com.other/files/split.apk")) as InstalledImport.Imported
        assertEquals(0, r.splitsCopied); assertEquals(1, r.splitsFailed)
    }

    @Test fun tooLargePartIsSkippedAndLeavesNoPartialFile() {
        val base = file("app", "base.apk", 10); val big = file("app", "split_big.apk", 500)
        val w = ws()
        val r = InstalledApkImporter(w, 100).import("x", base.path, listOf(big.path)) as InstalledImport.Imported
        assertEquals(1, r.splitsFailed)
        assertTrue(w.splitFiles(r.project.id).isEmpty())
        assertEquals(0, tmp.root.resolve("ws/${r.project.id}/input/splits").list()?.size ?: 0) // no .part leftovers
    }

    @Test fun totalSizeOfPartsIsCapped() {
        val base = file("app", "base.apk", 10)
        val p = (1..4).map { file("app", "split_$it.apk", 80).path }
        val w = ws()
        val r = InstalledApkImporter(w, maxBytes = 100, maxSplitsTotalBytes = 200).import("x", base.path, p) as InstalledImport.Imported
        assertEquals(2, r.splitsCopied); assertEquals(2, r.splitsFailed)
    }

    @Test fun systemFlagIsStored() {
        val base = file("app", "base.apk")
        val r = InstalledApkImporter(ws(), 1000).import("Camera", base.path, isSystem = true) as InstalledImport.Imported
        assertTrue(r.project.system)
        assertTrue(ws().get(r.project.id)!!.system)
    }

    @Test fun deletingProjectDeletesItsParts() {
        val base = file("app", "base.apk"); val a = file("app", "split_a.apk")
        val w = ws()
        val r = InstalledApkImporter(w, 1000).import("x", base.path, listOf(a.path)) as InstalledImport.Imported
        assertTrue(w.delete(r.project.id))
        assertFalse(tmp.root.resolve("ws/${r.project.id}").exists())
    }

    // ---- file names
    @Test fun splitNamesAreSanitised() {
        assertEquals("split_config.en.apk", Workspace.safeSplitName("/data/app/x/split_config.en.apk", 1, emptySet()))
        assertEquals("split_2.apk", Workspace.safeSplitName("/data/app/x/..", 2, emptySet()))
        assertEquals("split_3.apk", Workspace.safeSplitName("/x/we ird$(name).apk", 3, emptySet()))
        assertEquals("split_4.apk", Workspace.safeSplitName("/x/.hidden.apk", 4, emptySet()))
        assertEquals("split_5.apk", Workspace.safeSplitName(null, 5, emptySet()))
        assertEquals("split_1.apk", Workspace.safeSplitName("/x/split_a.apk", 1, setOf("split_a.apk"))) // duplicate name
        assertEquals("split_2.apk", Workspace.safeSplitName("/x/split_a.apk", 1, setOf("split_a.apk", "split_1.apk")))
    }

    @Test fun oldProjectsWithoutNewFieldsStillLoad() {
        val w = ws()
        val p = w.importApk("old", ByteArray(5).inputStream(), 100)
        val f = tmp.root.resolve("ws/${p.id}/project.properties")
        f.writeText(f.readLines().filter { !it.startsWith("system") && !it.startsWith("declared") && !it.startsWith("copied") }.joinToString("\n"))
        val loaded = w.get(p.id)!!
        assertFalse(loaded.system); assertEquals(0, loaded.declaredSplits)
    }

    // ---- reading the parts
    @Test fun summarisesEachPart() {
        val cfg = TestZips.zip(tmp.newFile("split_config.arm64_v8a.apk"), listOf("AndroidManifest.xml" to res("fx_manifest_part.bin"), "lib/arm64-v8a/libx.so" to ByteArray(10), "res/a.xml" to ByteArray(3)))
        val dens = TestZips.zip(tmp.newFile("split_config.xxhdpi.apk"), listOf("AndroidManifest.xml" to res("fx_manifest_part.bin"), "res/a.png" to ByteArray(3), "res/b.png" to ByteArray(3)))
        val broken = tmp.newFile("split_bad.apk").apply { writeText("not a zip") }
        val parts = SplitSetReader().read(listOf(cfg, dens, broken))
        assertEquals("config.arm64_v8a", parts[0].splitName)
        assertEquals(listOf("arm64-v8a"), parts[0].abis)
        assertEquals(1, parts[0].resourceFiles)
        assertEquals(2, parts[1].resourceFiles)
        assertFalse(parts[2].readable)
        assertEquals("split_bad.apk", parts[2].fileName)
    }

    // ---- verdicts
    private val base = SplitInfo(SplitKind.BASE_NEEDS_SPLITS, "base__abi")
    private fun assess(split: SplitInfo?, system: Boolean = false, declared: Int = 0, held: Int = 0) =
        Editability.assess(split, false, false, emptyList(), DexScan(emptySet(), 100, 0, 0), system, declared, held)

    @Test fun systemAppIsABlockerEvenIfItLooksComplete() {
        val r = assess(SplitInfo(SplitKind.COMPLETE, null), system = true)
        assertEquals(Verdict.NOT_EDITABLE_HERE, r.verdict)
        assertTrue(r.findings.first().title.contains("phone's own software"))
    }

    @Test fun splitWordingFollowsHowManyPartsAreHeld() {
        fun text(declared: Int, held: Int) = assess(base, declared = declared, held = held).findings.first { it.severity == Severity.BLOCKER }.detail
        assertTrue(text(3, 3).contains("all 3 other parts"))
        assertTrue(text(3, 2).contains("2 of 3"))
        assertTrue(text(0, 0).contains("only holds this one"))
        listOf(text(3, 3), text(3, 2), text(0, 0)).forEach { assertEquals(Verdict.NOT_EDITABLE_HERE, assess(base).verdict) }
    }
}
