package com.amandhakar.apkforge

import com.amandhakar.apkforge.build.ApkRebuilder
import com.amandhakar.apkforge.build.RebuildException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class ApkRebuilderTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun res(n: String) = javaClass.getResourceAsStream("/$n")!!.readBytes()
    private fun fixture(): File = tmp.newFile("fx.apk").apply { writeBytes(res("fx_img.apk")) }
    private fun entry(f: File, name: String) = ZipFile(f).use { z -> z.getInputStream(z.getEntry(name)).readBytes() }
    private fun names(f: File) = ZipFile(f).use { z -> z.entries().toList().map { it.name } }

    @Test fun replacesOneEntryAndKeepsTheRest() {
        val src = fixture(); val out = tmp.root.resolve("out.apk")
        val newLogo = res("new_logo.png")
        ApkRebuilder().rebuild(src, out, mapOf("res/drawable/logo.png" to newLogo))
        assertArrayEquals(newLogo, entry(out, "res/drawable/logo.png"))
        for (n in listOf("AndroidManifest.xml", "resources.arsc", "res/drawable/panel.9.png")) assertArrayEquals(n, entry(src, n), entry(out, n))
        assertEquals(names(src), names(out)) // same entries, same order
    }

    @Test fun noReplacementsGivesTheSameContent() {
        val src = fixture(); val out = tmp.root.resolve("out.apk")
        ApkRebuilder().rebuild(src, out, emptyMap())
        for (n in names(src)) assertArrayEquals(n, entry(src, n), entry(out, n))
    }

    @Test fun resourcesArscIsStoredAndAligned() {
        val src = fixture(); val out = tmp.root.resolve("out.apk")
        ApkRebuilder().rebuild(src, out, emptyMap())
        val layout = ZipLayout.read(out)
        for ((n, i) in layout) if (i.method == 0) assertEquals("$n must start on a 4-byte boundary", 0L, i.dataOffset % 4)
        assertEquals(0, layout.getValue("resources.arsc").method)
    }

    @Test fun compressedArscInTheOriginalBecomesStored() {
        val src = TestZips.zip(tmp.newFile("a.apk"), listOf("AndroidManifest.xml" to ByteArray(10), "resources.arsc" to ByteArray(500)))
        val out = tmp.root.resolve("out.apk")
        ApkRebuilder().rebuild(src, out, emptyMap())
        val i = ZipLayout.read(out).getValue("resources.arsc")
        assertEquals(0, i.method); assertEquals(0L, i.dataOffset % 4)
        assertEquals(500, entry(out, "resources.arsc").size)
    }

    @Test fun storedNativeLibrariesAreAligned16K() {
        val src = ZipLayout.stored(tmp.newFile("n.apk"), listOf(
            "AndroidManifest.xml" to ByteArray(37), "lib/arm64-v8a/liba.so" to ByteArray(100), "lib/arm64-v8a/libb.so" to ByteArray(9000), "resources.arsc" to ByteArray(11),
        ))
        val out = tmp.root.resolve("out.apk")
        ApkRebuilder().rebuild(src, out, emptyMap())
        val layout = ZipLayout.read(out)
        assertEquals(0L, layout.getValue("lib/arm64-v8a/liba.so").dataOffset % 16384)
        assertEquals(0L, layout.getValue("lib/arm64-v8a/libb.so").dataOffset % 16384)
        assertEquals(0L, layout.getValue("resources.arsc").dataOffset % 4)
        assertEquals(9000, entry(out, "lib/arm64-v8a/libb.so").size)
    }

    @Test fun compressedEntriesStayCompressed() {
        val src = TestZips.zip(tmp.newFile("c.apk"), listOf("classes.dex" to ByteArray(5000), "resources.arsc" to ByteArray(8)))
        val out = tmp.root.resolve("out.apk")
        ApkRebuilder().rebuild(src, out, emptyMap())
        assertEquals(8, ZipLayout.read(out).getValue("classes.dex").method)
    }

    @Test fun alignmentPaddingIsComputedForAnyOffset() {
        for (align in listOf(4, 16384)) for (offset in 0L..40L) for (name in listOf(1, 2, 5, 17)) {
            val extra = ApkRebuilder.alignExtra(offset, name, align)
            assertEquals("offset=$offset name=$name align=$align", 0L, (offset + 30 + name + extra.size) % align)
            assertTrue(extra.isEmpty() || extra.size >= 6)
        }
    }

    @Test fun oldSignaturesAreDropped() {
        val src = TestZips.zip(tmp.newFile("s.apk"), listOf(
            "AndroidManifest.xml" to ByteArray(3), "META-INF/MANIFEST.MF" to ByteArray(5), "META-INF/CERT.SF" to ByteArray(5), "META-INF/CERT.RSA" to ByteArray(5),
            "META-INF/services/x" to ByteArray(2), "res/a.png" to ByteArray(4),
        ))
        val out = tmp.root.resolve("out.apk")
        ApkRebuilder().rebuild(src, out, emptyMap())
        assertEquals(listOf("AndroidManifest.xml", "META-INF/services/x", "res/a.png"), names(out))
        assertTrue(ApkRebuilder.isOldSignature("META-INF/anything.rsa"))
        assertFalse(ApkRebuilder.isOldSignature("META-INF/services/CERT.SF")) // only top-level signature files
    }

    @Test fun unsafeNamesAreRefused() {
        val src = TestZips.zip(tmp.newFile("u.apk"), listOf("AndroidManifest.xml" to ByteArray(3), "../evil.txt" to ByteArray(3)))
        try { ApkRebuilder().rebuild(src, tmp.root.resolve("out.apk"), emptyMap()); fail() } catch (e: RebuildException) { assertTrue(e.message!!.contains("unsafe")) }
        assertFalse(tmp.root.resolve("out.apk").exists())
    }

    @Test fun badReplacementsAreRefusedAndLeaveNothingBehind() {
        val src = fixture(); val out = tmp.root.resolve("out.apk")
        for (bad in listOf(mapOf("res/nope.png" to ByteArray(3)), mapOf("../x" to ByteArray(3)))) {
            try { ApkRebuilder().rebuild(src, out, bad); fail() } catch (_: RebuildException) {}
        }
        try { ApkRebuilder(maxReplacementBytes = 10).rebuild(src, out, mapOf("res/drawable/logo.png" to ByteArray(11))); fail() } catch (_: RebuildException) {}
        assertFalse(out.exists())
        assertEquals(listOf("fx.apk"), tmp.root.list()!!.sorted())
    }

    @Test fun replacingAnExistingOutputWorks() {
        val src = fixture(); val out = tmp.root.resolve("out.apk")
        ApkRebuilder().rebuild(src, out, emptyMap())
        ApkRebuilder().rebuild(src, out, mapOf("res/drawable/logo.png" to res("new_logo.png")))
        assertArrayEquals(res("new_logo.png"), entry(out, "res/drawable/logo.png"))
    }
}
