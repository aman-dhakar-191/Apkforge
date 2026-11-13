package com.amandhakar.apkforge

import com.amandhakar.apkforge.core.Hex
import com.amandhakar.apkforge.core.PathSafety
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PathSafetyTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun rejectsEscapes() {
        listOf("../a", "a/../../b", "/etc/passwd", "a\\b", "C:/x", "", "a\u0000b").forEach {
            assertFalse(it, PathSafety.isSafeEntryName(it))
        }
    }

    @Test fun acceptsNormalNames() {
        listOf("classes.dex", "res/layout/main.xml", "lib/arm64-v8a/libx.so", "a..b/c").forEach {
            assertTrue(it, PathSafety.isSafeEntryName(it))
        }
    }

    @Test fun resolveInsideStaysInside() {
        val base = tmp.newFolder("out")
        assertNotNull(PathSafety.resolveInside(base, "res/a.xml"))
        assertNull(PathSafety.resolveInside(base, "../x"))
    }

    @Test fun sha256() {
        assertEquals(
            "BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23:B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD",
            Hex.sha256("abc".toByteArray()),
        )
    }
}
