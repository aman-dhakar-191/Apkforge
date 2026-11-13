package com.amandhakar.apkforge

import com.amandhakar.apkforge.build.IconFinder
import com.amandhakar.apkforge.core.ResourceResult
import com.amandhakar.apkforge.inspect.ArscReader
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile

/** fx_icon_a: adaptive icon (PNG foreground, colour background) plus old-style PNG icons. fx_icon_b: adaptive with a vector foreground. Both built by aapt2. */
class IconFinderTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun res(n: String): ByteArray = javaClass.getResourceAsStream("/$n")!!.readBytes() // (inside apply { } javaClass would be the File's)
    private fun parts(name: String): List<com.amandhakar.apkforge.build.IconPart> {
        val apk = tmp.newFile("$name.apk").also { it.writeBytes(res("fx_icon_$name.apk")) }
        val table = (ArscReader().read(apk) as ResourceResult.Ok).table
        return ZipFile(apk).use { z ->
            val manifest = z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes()
            IconFinder.find(manifest, table) { path -> z.getEntry(path)?.let { z.getInputStream(it).readBytes() } }
        }
    }

    @Test fun findsTheForegroundBackgroundAndOldStyleIcon() {
        val p = parts("a")
        assertEquals(listOf("Icon foreground (drawable/icon_fg)", "Icon background (colour #ff123456)", "Whole icon as one picture (mipmap/ic_launcher)"), p.map { it.role })
        assertEquals(listOf(true, false, true), p.map { it.replaceable })
        // the entries to replace are the real ones: every density file of each
        assertEquals(setOf("res/drawable-hdpi-v4/icon_fg.png", "res/drawable-xhdpi-v4/icon_fg.png"), p[0].entry!!.files.map { it.path }.toSet())
        assertEquals(setOf("res/mipmap-hdpi-v4/ic_launcher.png", "res/mipmap-xhdpi-v4/ic_launcher.png"), p[2].entry!!.files.map { it.path }.filter { it.endsWith(".png") }.toSet())
        assertTrue(p[1].note!!.contains("colour"))
    }

    @Test fun aVectorForegroundCanBeReplacedByAPicture() {
        val p = parts("b")
        assertEquals("Icon foreground (drawable/icon_vec)", p[0].role)
        assertTrue(p[0].replaceable)
        assertTrue(p[0].note!!.contains("vector drawing"))
        assertFalse(p[1].replaceable) // the colour background
        assertEquals("Whole icon as one picture (mipmap/ic_launcher)", p[2].role) // only the adaptive XML exists, which can also be replaced
        assertTrue(p[2].replaceable)
        assertEquals(3, p.size)
    }

    @Test fun noIconInTheManifestIsSaidPlainly() {
        val apk = tmp.newFile("noicon.apk").also { it.writeBytes(res("fx_img.apk")) }
        // fx_img has android:icon="@drawable/logo": a plain picture, no adaptive XML
        val table = (ArscReader().read(apk) as ResourceResult.Ok).table
        val p = ZipFile(apk).use { z ->
            IconFinder.find(z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes(), table) { path -> z.getEntry(path)?.let { z.getInputStream(it).readBytes() } }
        }
        assertEquals(1, p.size); assertEquals("App icon (drawable/logo)", p[0].role); assertTrue(p[0].replaceable)
        assertTrue(IconFinder.find(ByteArray(10), table) { null }.isEmpty()) // damaged manifest: nothing, no crash
    }
}
