package com.amandhakar.apkforge

import com.amandhakar.apkforge.build.*
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.ZipFile

class AxmlTreeTest {
    private fun dump(e: XElement, sb: StringBuilder = StringBuilder(), depth: Int = 0): String {
        sb.append("  ".repeat(depth)).append(e.ns).append(':').append(e.name).append(" ns=").append(e.nsDecls.map { it.prefix + "=" + it.uri }).append('\n')
        for (a in e.attrs) sb.append("  ".repeat(depth + 1)).append(a.ns).append(':').append(a.name).append('#').append(a.resId).append(" t=").append(a.type).append(" d=").append(a.data).append(" s=").append(a.text).append('\n')
        for (c in e.children) when (c) { is XElement -> dump(c, sb, depth + 1); is XText -> sb.append("  ".repeat(depth + 1)).append("text:").append(c.text).append('\n') }
        return sb.toString()
    }

    private fun roundTrips(bytes: ByteArray): Boolean {
        val t = AxmlTree.parse(bytes)
        return dump(t.root) == dump(AxmlTree.parse(t.serialize()).root)
    }

    @Test fun fixturesRoundTrip() {
        for (name in listOf("/fx_layout.apk", "/fx_values.apk", "/fx_split.apk", "/fx_authority.apk")) ZipFile(java.io.File(javaClass.getResource(name)!!.toURI())).use { z ->
            for (e in z.entries()) if (e.name.endsWith(".xml") && (e.name.startsWith("res/") || e.name == "AndroidManifest.xml"))
                assertTrue("$name ${e.name}", roundTrips(z.getInputStream(e).readBytes()))
        }
    }

    /** Set AXML_CORPUS to an android.jar (it holds about 1,800 real compiled layouts) to run this on all of them. */
    @Test fun realWorldFilesRoundTrip() {
        val jar = System.getenv("AXML_CORPUS") ?: return
        var n = 0
        ZipFile(jar).use { z ->
            for (e in z.entries()) if (e.name.startsWith("res/") && e.name.endsWith(".xml")) { assertTrue(e.name, roundTrips(z.getInputStream(e).readBytes())); n++ }
        }
        assertTrue(n > 500)
    }

    @Test fun garbageIsRefused() {
        for (bad in listOf(ByteArray(0), ByteArray(40) { 7 }, byteArrayOf(3, 0, 8, 0, 100, 0, 0, 0)))
            try { AxmlTree.parse(bad); fail() } catch (e: com.amandhakar.apkforge.inspect.AxmlException) { }
    }
}
