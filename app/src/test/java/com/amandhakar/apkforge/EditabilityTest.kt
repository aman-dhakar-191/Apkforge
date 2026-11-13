package com.amandhakar.apkforge

import com.amandhakar.apkforge.core.*
import org.junit.Assert.*
import org.junit.Test

class EditabilityTest {
    private fun scan(vararg m: String, skipped: Int = 0, classes: Int = 100, short: Int = 0) = DexScan(m.toSet(), classes, short, skipped)
    private fun assess(split: SplitInfo? = SplitInfo(SplitKind.COMPLETE, null), debuggable: Boolean = false, native: Boolean = false,
                       packers: List<String> = emptyList(), scan: DexScan? = scan()) = Editability.assess(split, debuggable, native, packers, scan)

    @Test fun cleanAppReportsNothingFoundButStillWarnsAboutSignature() {
        val r = assess()
        assertEquals(Verdict.NOTHING_FOUND, r.verdict)
        assertTrue(r.findings.any { it.title.contains("different app") })
    }

    @Test fun splitBaseIsABlocker() {
        val r = assess(split = SplitInfo(SplitKind.BASE_NEEDS_SPLITS, "base__abi"))
        assertEquals(Verdict.NOT_EDITABLE_HERE, r.verdict)
        assertEquals(Severity.BLOCKER, r.findings.first().severity)
    }

    @Test fun integrityLibraryMeansMayBreak() {
        val r = assess(scan = scan("PLAY_INTEGRITY"))
        assertEquals(Verdict.MAY_BREAK, r.verdict)
        assertTrue(r.findings.any { it.severity == Severity.WARNING && it.title.contains("Play Integrity") })
    }

    @Test fun blockerBeatsWarning() {
        assertEquals(Verdict.NOT_EDITABLE_HERE, assess(split = SplitInfo(SplitKind.SPLIT_PART, "config.x"), scan = scan("SAFETYNET")).verdict)
    }

    @Test fun neverSaysNothingFoundAboutCodeItDidNotRead() {
        assertEquals(Verdict.INCOMPLETE, assess(scan = null).verdict)
        assertEquals(Verdict.INCOMPLETE, assess(scan = scan(skipped = 2)).verdict)
        // but a real finding still wins over "incomplete"
        assertEquals(Verdict.MAY_BREAK, assess(scan = scan("SAFETYNET", skipped = 2)).verdict)
    }

    @Test fun packerLibraryNames() {
        val libs = Editability.packerLibraries(listOf("lib/arm64-v8a/libjiagu_64.so", "lib/armeabi-v7a/libDexHelper.so", "lib/arm64-v8a/libfoo.so", "assets/libjiagu.so"))
        assertEquals(listOf("libDexHelper.so", "libjiagu_64.so"), libs)
        assertEquals(Verdict.MAY_BREAK, assess(packers = libs).verdict)
    }

    @Test fun notesForNativeDebuggableAndObfuscation() {
        val r = assess(debuggable = true, native = true, scan = scan(classes = 1000, short = 800))
        val titles = r.findings.map { it.title }
        assertTrue(titles.any { it.contains("native code", true) })
        assertTrue(titles.any { it.contains("debuggable", true) })
        assertTrue(titles.any { it.contains("shortened", true) })
        assertEquals(Verdict.NOTHING_FOUND, r.verdict)
    }

    @Test fun findingsAreSortedMostSeriousFirst() {
        val r = assess(split = SplitInfo(SplitKind.BASE_NEEDS_SPLITS, null), debuggable = true, scan = scan("LICENSING"))
        assertEquals(r.findings.sortedBy { it.severity }, r.findings)
    }

    @Test fun manifestApplicationAttributesAreRead() {
        val full = com.amandhakar.apkforge.inspect.AxmlDecoder().decodeFull(javaClass.getResourceAsStream("/fx_manifest_full.bin")!!.readBytes())
        assertTrue(full.applicationAttributes.containsKey("label"))
        assertFalse(full.applicationAttributes.containsKey("debuggable"))
    }
}
