package com.amandhakar.apkforge

import com.amandhakar.apkforge.core.ManifestResult
import com.amandhakar.apkforge.inspect.AxmlDecoder
import com.amandhakar.apkforge.inspect.AxmlException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AxmlDecoderTest {
    @get:Rule val tmp = TemporaryFolder()

    // A real compiled manifest (Apkforge's own, built by aapt2). Expected values below were read
    // from `aapt2 dump xmltree` for the same file.
    private val bin: ByteArray = javaClass.getResourceAsStream("/AndroidManifest.bin")!!.readBytes()
    private val xml: String = AxmlDecoder().decode(bin)

    @Test fun decodesKnownValues() {
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>"))
        assertTrue(xml, xml.contains("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\""))
        assertTrue(xml.contains("package=\"com.amandhakar.apkforge\""))
        assertTrue(xml.contains("android:versionName=\"0.0.0-dev\""))
        assertTrue(xml.contains("android:versionCode=\"1\""))
        assertTrue(xml.contains("android:minSdkVersion=\"26\""))
        assertTrue(xml.contains("android:targetSdkVersion=\"35\""))
        assertTrue(xml.contains("android:name=\"com.amandhakar.apkforge.MainActivity\""))
        assertTrue(xml.contains("android:exported=\"true\""))
        assertTrue(xml.contains("android:allowBackup=\"false\""))
        assertTrue(xml.contains("android:protectionLevel=\"0x2\""))
        assertTrue(xml.contains("android:label=\"@0x7f090001\"")) // resource references stay as numbers
        assertTrue(xml.contains("android.intent.category.LAUNCHER"))
    }

    @Test fun elementsAreBalanced() {
        fun count(s: String) = Regex(s).findAll(xml).count()
        assertEquals(count("(?m)^\\s*<[a-zA-Z]"), count("(?m)^\\s*</"))
        assertTrue(xml.trimEnd().endsWith("</manifest>"))
    }

    @Test fun noInternetPermissionInOurManifest() {
        assertFalse(xml.contains("android.permission.INTERNET"))
    }

    @Test fun truncatedInputNeverCrashesBadly() {
        for (n in 0 until bin.size step 7) {
            try { AxmlDecoder().decode(bin.copyOf(n)) } catch (_: AxmlException) {}
        }
    }

    @Test fun corruptedBytesOnlyThrowAxmlException() {
        val rnd = java.util.Random(1)
        repeat(300) {
            val c = bin.copyOf()
            repeat(5) { c[rnd.nextInt(c.size)] = rnd.nextInt(256).toByte() }
            try { AxmlDecoder().decode(c) } catch (_: AxmlException) {}
        }
    }

    @Test fun rejectsNonXml() {
        try { AxmlDecoder().decode("hello world, not xml".toByteArray()); fail() } catch (_: AxmlException) {}
    }

    @Test fun elementLimit() {
        try { AxmlDecoder(maxElements = 3).decode(bin); fail() } catch (e: AxmlException) { assertTrue(e.message!!.contains("too many")) }
    }

    @Test fun decodeFromApkAndMissingManifest() {
        val apk = TestZips.zip(tmp.newFile("a.apk"), listOf("AndroidManifest.xml" to bin))
        assertTrue(AxmlDecoder().decode(apk) is ManifestResult.Ok)
        val none = TestZips.zip(tmp.newFile("b.apk"), listOf("x" to ByteArray(1)))
        assertTrue(AxmlDecoder().decode(none) is ManifestResult.Failed)
        val bad = TestZips.zip(tmp.newFile("c.apk"), listOf("AndroidManifest.xml" to "plain text".toByteArray()))
        assertTrue(AxmlDecoder().decode(bad) is ManifestResult.Failed)
    }
}
