package com.amandhakar.apkforge

import com.amandhakar.apkforge.inspect.AxmlException
import com.amandhakar.apkforge.inspect.DexClassScanner
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The dex fixtures were produced by Android's own d8 from small Java classes. */
class DexClassScannerTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun dex(n: String) = javaClass.getResourceAsStream("/$n")!!.readBytes()
    private val scanner = DexClassScanner()

    @Test fun plainAppHasNoMarkers() {
        val r = scanner.scanDex(dex("dex_plain.bin"))
        assertTrue(r.markers.isEmpty())
        assertEquals(1, r.appClassCount) // com/example/Plain; java/lang/Object is platform
    }

    @Test fun findsIntegrityAndSafetyNetButNotAPlainMediaDrmReference() {
        // dex_protected also refers to android.media.MediaDrm; that alone is deliberately not a marker
        // (build tools add such references to ordinary apps, which gave false warnings on a real APK).
        val r = scanner.scanDex(dex("dex_protected.bin"))
        assertEquals(setOf("PLAY_INTEGRITY", "SAFETYNET"), r.markers)
    }

    @Test fun countsShortNames() {
        val r = scanner.scanDex(dex("dex_obf.bin"))
        assertEquals(4, r.appClassCount)
        assertEquals(3, r.shortNameCount)
        assertFalse(r.looksObfuscated) // too few classes to call it
    }

    @Test fun truncatedOrCorruptedDexOnlyFailsCleanly() {
        val d = dex("dex_protected.bin")
        for (n in 0 until d.size step 9) try { scanner.scanDex(d.copyOf(n)) } catch (_: AxmlException) {}
        val rnd = java.util.Random(3)
        repeat(500) {
            val c = d.copyOf(); repeat(6) { c[rnd.nextInt(c.size)] = rnd.nextInt(256).toByte() }
            try { scanner.scanDex(c) } catch (_: AxmlException) {}
        }
    }

    @Test fun rejectsNonDex() {
        try { scanner.scanDex(ByteArray(200)); fail() } catch (_: AxmlException) {}
    }

    @Test fun scansEveryDexInAnApkAndCountsUnreadableOnes() {
        val apk = TestZips.zip(tmp.newFile("a.apk"), listOf(
            "classes.dex" to dex("dex_plain.bin"),
            "classes2.dex" to dex("dex_protected.bin"),
            "classes3.dex" to ByteArray(300), // damaged
            "assets/classes9.dex" to dex("dex_protected.bin"), // not a real app dex name: ignored
        ))
        val r = scanner.scan(apk)
        assertEquals(setOf("PLAY_INTEGRITY", "SAFETYNET"), r.markers)
        assertEquals(1, r.skippedFiles)
    }

    @Test fun oversizedDexIsSkipped() {
        val apk = TestZips.zip(tmp.newFile("b.apk"), listOf("classes.dex" to dex("dex_protected.bin")))
        assertEquals(1, DexClassScanner(maxDexBytes = 100).scan(apk).skippedFiles)
    }
}
