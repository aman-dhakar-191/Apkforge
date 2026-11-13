package com.amandhakar.apkforge

import com.amandhakar.apkforge.core.SplitKind
import com.amandhakar.apkforge.core.Splits
import com.amandhakar.apkforge.inspect.AxmlDecoder
import org.junit.Assert.*
import org.junit.Test

class SplitsTest {
    private fun root(n: String) = AxmlDecoder().decodeFull(javaClass.getResourceAsStream("/$n")!!.readBytes()).rootAttributes

    @Test fun completeApp() {
        val s = Splits.classify(root("fx_manifest_full.bin"))
        assertEquals(SplitKind.COMPLETE, s.kind)
    }

    @Test fun baseThatNeedsSplits() { // same attributes as the Netflix base APK seen on a phone
        val s = Splits.classify(root("fx_manifest_base.bin"))
        assertEquals(SplitKind.BASE_NEEDS_SPLITS, s.kind)
        assertEquals("base__abi,base__density", s.detail)
    }

    @Test fun splitPart() {
        val s = Splits.classify(root("fx_manifest_part.bin"))
        assertEquals(SplitKind.SPLIT_PART, s.kind)
        assertEquals("config.arm64_v8a", s.detail)
    }

    @Test fun emptySplitTypesDoesNotCount() {
        assertEquals(SplitKind.COMPLETE, Splits.classify(mapOf("splitTypes" to "", "requiredSplitTypes" to "")).kind)
    }

    @Test fun rootAttributesUseLocalNames() {
        assertEquals("com.example.fixture", root("fx_manifest_full.bin")["package"])
    }
}
