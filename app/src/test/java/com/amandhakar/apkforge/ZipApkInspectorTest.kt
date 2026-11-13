package com.amandhakar.apkforge

import com.amandhakar.apkforge.core.*
import com.amandhakar.apkforge.inspect.ZipApkInspector
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ZipApkInspectorTest {
    @get:Rule val tmp = TemporaryFolder()
    private val b = ByteArray(10)

    private fun ok(r: InspectionResult) = r as InspectionResult.Ok
    private fun rejected(r: InspectionResult) = (r as InspectionResult.Rejected).reason

    @Test fun listsAndClassifiesEntries() {
        val apk = TestZips.zip(tmp.newFile("a.apk"), listOf(
            "AndroidManifest.xml" to b, "classes.dex" to b, "classes2.dex" to b, "resources.arsc" to b,
            "res/layout/a.xml" to b, "assets/x.bin" to b, "lib/arm64-v8a/libx.so" to b, "lib/x86_64/libx.so" to b,
            "META-INF/CERT.RSA" to b, "other.txt" to b,
        ))
        val s = ok(ZipApkInspector().inspect(apk)).summary
        assertEquals(10, s.entryCount)
        assertEquals(listOf("classes.dex", "classes2.dex"), s.dexFiles)
        assertEquals(listOf("arm64-v8a", "x86_64"), s.nativeAbis)
        assertTrue(s.hasManifest && s.hasResourceTable)
        assertEquals(listOf("META-INF/CERT.RSA"), s.signatureFiles)
        assertFalse(s.looksLikeSplitApk)
        assertEquals(0, s.unsafeNameCount)
        assertEquals(1, s.countsByKind[EntryKind.RESOURCE])
    }

    @Test fun splitHeuristic() {
        val apk = TestZips.zip(tmp.newFile("s.apk"), listOf("AndroidManifest.xml" to b, "res/x.xml" to b))
        assertTrue(ok(ZipApkInspector().inspect(apk)).summary.looksLikeSplitApk)
    }

    @Test fun flagsTraversalAndAbsoluteNames() {
        val apk = TestZips.zip(tmp.newFile("t.apk"), listOf("../evil.txt" to b, "/abs.txt" to b, "good/ok.txt" to b))
        val r = ok(ZipApkInspector().inspect(apk))
        assertEquals(2, r.summary.unsafeNameCount)
        assertEquals(listOf(true, true, false), r.entries.map { it.unsafeName })
    }

    @Test fun missingFile() {
        assertEquals(RejectReason.NOT_FOUND, rejected(ZipApkInspector().inspect(tmp.root.resolve("nope"))))
    }

    @Test fun notAZip() {
        val f = tmp.newFile("x.apk").apply { writeText("hello, this is not an apk") }
        assertEquals(RejectReason.NOT_A_ZIP, rejected(ZipApkInspector().inspect(f)))
    }

    @Test fun truncatedZip() {
        val full = TestZips.zip(tmp.newFile("f.apk"), listOf("classes.dex" to ByteArray(5000)))
        val cut = tmp.newFile("cut.apk").apply { writeBytes(full.readBytes().copyOf(60)) }
        assertEquals(RejectReason.NOT_A_ZIP, rejected(ZipApkInspector().inspect(cut)))
    }

    @Test fun fileSizeLimit() {
        val apk = TestZips.zip(tmp.newFile("big.apk"), listOf("a" to ByteArray(2000)))
        assertEquals(RejectReason.TOO_LARGE, rejected(ZipApkInspector(InspectLimits(maxFileBytes = 100)).inspect(apk)))
    }

    @Test fun entryCountLimit() {
        val apk = TestZips.zip(tmp.newFile("many.apk"), (1..20).map { "f$it" to b })
        assertEquals(RejectReason.TOO_MANY_ENTRIES, rejected(ZipApkInspector(InspectLimits(maxEntries = 10)).inspect(apk)))
    }

    @Test fun unpackedSizeLimit() {
        val apk = TestZips.zip(tmp.newFile("bomb.apk"), listOf("a" to ByteArray(1_000_000)))
        assertEquals(RejectReason.UNCOMPRESSED_TOO_LARGE, rejected(ZipApkInspector(InspectLimits(maxTotalUncompressedBytes = 1000)).inspect(apk)))
    }
}
