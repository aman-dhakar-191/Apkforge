package com.amandhakar.apkforge

import com.amandhakar.apkforge.build.*
import com.amandhakar.apkforge.inspect.AxmlException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class SplitSetTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A real split part made by aapt2: package com.example.splitfx, versionCode 7, with a resource table. */
    private fun part(): Pair<File, String> {
        val f = tmp.newFile("split_config.apk")
        File(javaClass.getResource("/fx_split.apk")!!.toURI()).copyTo(f, overwrite = true)
        return f to "com.example.splitfx"
    }

    @Test fun renamesPackageAndVersionInAPart() {
        val (f, pkg) = part()
        val c = SplitSet.prepare(f, pkg, "$pkg.forge", 424242)
        val m = AxmlEditor().parse(c.replacements.getValue("AndroidManifest.xml"))
        val root = m.elements[0]
        assertEquals("$pkg.forge", root.attrs.first { it.name == "package" && it.namespace == null }.value)
        assertEquals(424242, root.attrs.first { it.name == "versionCode" }.data)
        assertEquals(listOf("$pkg.forge"), ArscPackage.packageNames(c.replacements.getValue("resources.arsc")))
    }

    @Test fun withoutARenameNothingIsTouched() {
        val (f, pkg) = part()
        assertTrue(SplitSet.prepare(f, pkg, null, null).replacements.isEmpty())
    }

    @Test fun aPartOfAnotherAppIsRefused() {
        val (f, _) = part()
        try { SplitSet.prepare(f, "some.other.app", null, null); fail() } catch (e: AxmlException) { assertTrue(e.message!!.contains("another app")) }
    }
}
