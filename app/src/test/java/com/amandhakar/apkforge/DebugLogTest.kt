package com.amandhakar.apkforge

import com.amandhakar.apkforge.log.DebugLog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DebugLogTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun writesNothingWhenDisabled() {
        val f = tmp.root.resolve("d.log")
        DebugLog(f, { false }).event("import_ok", "bytes" to 5)
        assertFalse(f.exists())
    }

    @Test fun writesWhenEnabledAndClears() {
        val log = DebugLog(tmp.root.resolve("d.log"), { true }, { 42L })
        log.event("import_ok", "bytes" to 5)
        assertEquals("1970-01-01T00:00:00.042Z import_ok bytes=5\n", log.read())
        log.clear()
        assertEquals("", log.read())
    }

    @Test fun stripsPathsUrisAndFileNames() {
        val c = DebugLog.clean("failed /storage/emulated/0/Download/secret.apk content://media/external/file/12 My Bank.apk")
        assertFalse(c.contains("storage")); assertFalse(c.contains("content:")); assertFalse(c.contains("secret"))
        assertFalse(c.contains("Bank"))
    }

    @Test fun valuesStayOneLine() {
        assertFalse(DebugLog.clean("a\nb\rc").contains('\n'))
    }

    @Test fun trimsOldLinesWhenTooBig() {
        val log = DebugLog(tmp.root.resolve("d.log"), { true }, { 1L }, maxBytes = 200)
        repeat(100) { log.event("e", "n" to it) }
        assertTrue(log.read().length < 600)
        assertTrue(log.read().contains("n=99"))
    }
}
