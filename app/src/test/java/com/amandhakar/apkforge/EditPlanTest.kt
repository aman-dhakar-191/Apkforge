package com.amandhakar.apkforge

import com.amandhakar.apkforge.build.*
import com.amandhakar.apkforge.core.ApkEntry
import com.amandhakar.apkforge.core.EntryKind
import com.amandhakar.apkforge.core.InspectionResult
import com.amandhakar.apkforge.inspect.ZipApkInspector
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile

class EditPlanTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun res(n: String) = javaClass.getResourceAsStream("/$n")!!.readBytes()
    private val entries: List<ApkEntry> by lazy {
        val f = tmp.newFile("fx.apk").apply { writeBytes(res("fx_img.apk")) }
        (ZipApkInspector().inspect(f) as InspectionResult.Ok).entries
    }
    private val png = res("new_logo.png")
    private fun problem(name: String, bytes: ByteArray) = EditPlan.problem(ApkEdit.ReplaceFile(name, bytes), entries)

    @Test fun aPngCanReplaceAPng() { assertNull(problem("res/drawable/logo.png", png)) }

    @Test fun wrongImageTypeIsExplained() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0, 0, 0)
        val p = problem("res/drawable/logo.png", jpeg)!!
        assertTrue(p, p.contains("PNG") && p.contains("JPEG"))
        assertTrue(problem("res/drawable/logo.png", "hello, this is text".toByteArray())!!.contains("not an image"))
    }

    @Test fun ninePatchNeedsACompiledNinePatch() {
        assertTrue(problem("res/drawable/panel.9.png", png)!!.contains("nine-patch"))
        val real = ZipFile(tmp.newFile("x.apk").apply { writeBytes(res("fx_img.apk")) }).use { z -> z.getInputStream(z.getEntry("res/drawable/panel.9.png")).readBytes() }
        assertNull(problem("res/drawable/panel.9.png", real))
        assertTrue(EditPlan.hasNinePatchChunk(real)); assertFalse(EditPlan.hasNinePatchChunk(png))
    }

    @Test fun onlyResAndAssetsFilesCanBeReplaced() {
        for (n in listOf("AndroidManifest.xml", "resources.arsc")) assertTrue(n, problem(n, png)!!.contains("res/ or assets/"))
    }

    @Test fun compiledXmlIsNotReplaceable() {
        val withXml = entries + ApkEntry("res/layout/main.xml", 10, 10, EntryKind.RESOURCE, false)
        assertTrue(EditPlan.problem(ApkEdit.ReplaceFile("res/layout/main.xml", ByteArray(5) { 1 }), withXml)!!.contains("compiled"))
    }

    @Test fun missingEmptyHugeAndUnsafe() {
        assertTrue(problem("res/drawable/none.png", png)!!.contains("not in the APK"))
        assertTrue(problem("res/drawable/logo.png", ByteArray(0))!!.contains("empty"))
        assertTrue(problem("res/drawable/logo.png", ByteArray(EditPlan.MAX_REPLACEMENT_BYTES + 1) { 1 })!!.contains("larger"))
        assertTrue(problem("../res/drawable/logo.png", png)!!.contains("not safe"))
    }

    @Test fun duplicateNamesAreNotEdited() {
        val dup = entries + entries.first { it.name == "res/drawable/logo.png" }
        assertTrue(EditPlan.problem(ApkEdit.ReplaceFile("res/drawable/logo.png", png), dup)!!.contains("more than once"))
    }

    @Test fun summaryShowsSizes() {
        val s = EditPlan.summary(listOf(ApkEdit.ReplaceFile("res/drawable/logo.png", ByteArray(2048) { 1 })), entries)
        assertEquals(listOf("Replace res/drawable/logo.png (75 B → 2.0 KB)"), s)
    }

    @Test fun imageSniffing() {
        assertEquals(ImageKind.PNG, ImageKind.sniff(png))
        assertEquals(ImageKind.UNKNOWN, ImageKind.sniff(ByteArray(3)))
        assertEquals(ImageKind.WEBP, ImageKind.sniff("RIFF....WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(ImageKind.PNG, ImageKind.expectedFor("res/x.9.png")); assertNull(ImageKind.expectedFor("assets/data.bin"))
    }
}
