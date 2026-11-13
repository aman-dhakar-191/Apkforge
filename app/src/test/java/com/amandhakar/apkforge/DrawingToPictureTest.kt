package com.amandhakar.apkforge

import com.amandhakar.apkforge.build.*
import com.amandhakar.apkforge.core.InspectionResult
import com.amandhakar.apkforge.core.ResourceResult
import com.amandhakar.apkforge.inspect.ArscReader
import com.amandhakar.apkforge.inspect.AxmlException
import com.amandhakar.apkforge.inspect.StringPool
import com.amandhakar.apkforge.inspect.ZipApkInspector
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/** fx_icon_b.apk (built by aapt2): an adaptive icon whose foreground is a vector drawing, res/drawable/icon_vec.xml. */
class DrawingToPictureTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun res(n: String) = javaClass.getResourceAsStream("/$n")!!.readBytes()
    private val vec = "res/drawable/icon_vec.xml"
    private val png = "res/drawable/icon_vec.png"
    private fun apk() = tmp.newFile("fx${tmp.root.list()!!.size}.apk").also { it.writeBytes(res("fx_icon_b.apk")) }
    private fun arsc(apk: java.io.File) = ZipFile(apk).use { z -> z.getInputStream(z.getEntry("resources.arsc")).readBytes() }

    @Test fun thePathInTheTableChangesByExactlyThreeBytes() {
        val before = arsc(apk())
        val after = ArscPaths.xmlToPng(before, listOf(vec))
        assertEquals(before.size, after.size)
        assertEquals(3, before.indices.count { before[it] != after[it] })
        val t = ArscReader().parse(after)
        val files = t.entries.values.flatMap { it.files }.map { it.path }
        assertTrue(files.toString(), png in files && vec !in files)
        // everything else reads the same
        val old = ArscReader().parse(before)
        fun summary(x: com.amandhakar.apkforge.core.ResourceTable) = x.entries.mapValues { it.value.type + it.value.name + it.value.value?.replace(".xml", ".png") }
        assertEquals(summary(old), summary(t)) // the only difference between the two tables is that one path
    }

    @Test fun refusesWhatItCannotChangeSafely() {
        val a = arsc(apk())
        for (bad in listOf(listOf("res/nothere.xml"), listOf("res/drawable/icon_vec.png"), listOf("AndroidManifest.xml.bak"))) {
            try { ArscPaths.xmlToPng(a, bad); fail(bad.toString()) } catch (_: AxmlException) {}
        }
        try { ArscPaths.xmlToPng(ByteArray(40), listOf(vec)); fail() } catch (_: AxmlException) {}
    }

    @Test fun charDataFindsStringsInBothEncodings() {
        for (utf8 in listOf(false, true)) for (value in listOf("a.b", "x".repeat(200))) {
            val bytes = TestAxml.build(utf8, pkg = value)
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val (at, len, isUtf8) = StringPool.charData(b, 8, bytes.size, 1) // string 1 is the package name
            assertEquals(utf8, isUtf8)
            assertEquals(value.length * (if (utf8) 1 else 2), len)
            val text = if (utf8) String(bytes, at, len, Charsets.UTF_8) else String(bytes, at, len, Charsets.UTF_16LE)
            assertEquals("utf8=$utf8", value, text)
        }
    }

    @Test fun theWholeWayRenameRebuildSignVerify() {
        val src = apk()
        val entries = (ZipApkInspector().inspect(src) as InspectionResult.Ok).entries
        val edit = ApkEdit.ReplaceDrawingWithPicture(vec, res("new_logo.png"))
        assertNull(EditPlan.problem(edit, entries))
        assertEquals(png, edit.newName)
        val patched = ArscPaths.xmlToPng(arsc(src), listOf(vec))
        val unsigned = tmp.root.resolve("u.apk"); val signed = tmp.root.resolve("s.apk")
        ApkRebuilder().rebuild(src, unsigned, mapOf(vec to res("new_logo.png"), "resources.arsc" to patched), mapOf(vec to png))
        ApkSigning().sign(unsigned, signed, SigningKeys(tmp.root.resolve("keys")).loadOrCreate(), 26)
        assertTrue(ApkChecks().verify(signed, 26).verified)
        ZipFile(signed).use { z ->
            assertNull("the old drawing is gone", z.getEntry(vec))
            assertArrayEquals(res("new_logo.png"), z.getInputStream(z.getEntry(png)).readBytes())
            assertNotNull(z.getEntry("res/mipmap-anydpi-v26/ic_launcher.xml")) // the adaptive icon itself is untouched
        }
        val t = (ArscReader().read(signed) as ResourceResult.Ok).table
        assertEquals(listOf(png), t.entries.values.single { it.name == "icon_vec" }.files.map { it.path })
        assertEquals(0L, ZipLayout.read(signed).getValue("resources.arsc").dataOffset % 4)
    }

    @Test fun combinesWithTheSeparateCopy() {
        val src = apk()
        val patched = ArscPaths.xmlToPng(arsc(src), listOf(vec))
        val clone = PackageClone.prepare(src, CloneSpec("com.example.iconfx", "forge", "Copy"), arscOverride = patched) as CloneResult.Ok
        val all = clone.replacements + mapOf(vec to res("new_logo.png"))
        val out = tmp.root.resolve("o.apk")
        ApkRebuilder().rebuild(src, out, all, mapOf(vec to png))
        ZipFile(out).use { z ->
            val a = z.getInputStream(z.getEntry("resources.arsc")).readBytes()
            assertEquals(listOf("com.example.iconfx.forge"), ArscPackage.packageNames(a)) // package renamed...
            assertTrue(ArscReader().parse(a).entries.values.flatMap { it.files }.any { it.path == png }) // ...and the path too
        }
    }

    @Test fun rebuilderRenameRules() {
        val src = apk()
        val r = ApkRebuilder()
        val ok = mapOf(vec to ByteArray(5) { 1 })
        r.rebuild(src, tmp.root.resolve("a.apk"), ok, mapOf(vec to png))
        assertEquals(ZipFile(src).use { z -> z.entries().toList().map { if (it.name == vec) png else it.name } }, ZipFile(tmp.root.resolve("a.apk")).use { z -> z.entries().toList().map { it.name } }) // same place in the file
        for ((replacements, renames) in listOf(
            ok to mapOf(vec to "res/mipmap-anydpi-v26/ic_launcher.xml"), // takes another file's name
            ok to mapOf(vec to "../evil.png"),                            // unsafe name
            emptyMap<String, ByteArray>() to mapOf(vec to png),           // renamed but no new content
        )) try { r.rebuild(src, tmp.root.resolve("b.apk"), replacements, renames); fail() } catch (_: RebuildException) {}
        assertFalse(tmp.root.resolve("b.apk").exists())
    }

    @Test fun editRules() {
        val entries = (ZipApkInspector().inspect(apk()) as InspectionResult.Ok).entries
        fun p(name: String, bytes: ByteArray = res("new_logo.png")) = EditPlan.problem(ApkEdit.ReplaceDrawingWithPicture(name, bytes), entries)
        assertNull(p(vec))
        assertTrue(p("res/mipmap-anydpi-v26/ic_launcher.xml") == null) // an adaptive icon file is a drawing too
        assertTrue(p("res/drawable/nothere.xml")!!.contains("missing"))
        assertTrue(p("AndroidManifest.xml")!!.contains("under res/"))
        assertTrue(p("../x.xml")!!.contains("not safe"))
        assertTrue(p(vec, "not a png".toByteArray())!!.contains("must be a PNG"))
        assertTrue(p(vec, ByteArray(0))!!.contains("empty"))
        assertTrue(EditPlan.problem(ApkEdit.ReplaceDrawingWithPicture(vec, res("new_logo.png")), entries + com.amandhakar.apkforge.core.ApkEntry(png, 1, 1, com.amandhakar.apkforge.core.EntryKind.RESOURCE, false))!!.contains("already exists"))
        assertTrue(EditPlan.summary(listOf(ApkEdit.ReplaceDrawingWithPicture(vec, res("new_logo.png"))), entries)[0].contains("Replace the drawing $vec with a picture ($png"))
    }
}
