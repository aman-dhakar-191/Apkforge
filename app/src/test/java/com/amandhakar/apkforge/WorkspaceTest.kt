package com.amandhakar.apkforge

import com.amandhakar.apkforge.workspace.ImportTooLargeException
import com.amandhakar.apkforge.workspace.Workspace
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

class WorkspaceTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun ws() = Workspace(tmp.root.resolve("projects"))

    @Test fun importKeepsBytesAndLayout() {
        val w = ws()
        val data = ByteArray(5000) { it.toByte() }
        val p = w.importApk("My App.apk", data.inputStream(), 1_000_000)
        assertEquals(5000, p.sizeBytes)
        assertEquals("My App.apk", p.displayName)
        assertFalse(p.id.contains("App")) // directory names are generated, not user-supplied
        val dir = tmp.root.resolve("projects/${p.id}")
        listOf("input", "decoded", "build", "output", "logs").forEach { assertTrue(dir.resolve(it).isDirectory) }
        val out = ByteArrayOutputStream().also { w.exportOriginal(p.id, it) }
        assertArrayEquals(data, out.toByteArray())
    }

    @Test fun oversizedImportLeavesNothingBehind() {
        val w = ws()
        try {
            w.importApk("big", ByteArray(2000).inputStream(), 1000)
            fail("expected ImportTooLargeException")
        } catch (_: ImportTooLargeException) {}
        assertTrue(w.list().isEmpty())
        assertEquals(0, tmp.root.resolve("projects").listFiles()!!.size)
    }

    @Test fun listsNewestFirstAndDeletes() {
        var t = 1000L
        val w = Workspace(tmp.root.resolve("p2")) { t++ }
        val a = w.importApk("a", ByteArray(1).inputStream(), 10)
        val b = w.importApk("b", ByteArray(1).inputStream(), 10)
        assertEquals(listOf(b.id, a.id), w.list().map { it.id })
        assertTrue(w.delete(a.id))
        assertNull(w.get(a.id))
    }

    @Test fun badIdsAreIgnored() {
        val w = ws()
        assertNull(w.get("../x"))
        assertNull(w.originalApk("../../etc"))
        assertFalse(w.delete(".."))
    }

    @Test fun controlCharactersRemovedFromName() {
        val p = ws().importApk("a\nb\u0000.apk", ByteArray(1).inputStream(), 10)
        assertEquals("ab.apk", p.displayName)
    }

    @Test fun detectsProbableDuplicate() {
        val w = ws()
        val a = w.importApk("same.apk", ByteArray(10).inputStream(), 100)
        assertFalse(w.hasProbableDuplicate(a))
        val b = w.importApk("same.apk", ByteArray(10).inputStream(), 100)
        val c = w.importApk("same.apk", ByteArray(11).inputStream(), 100)
        assertTrue(w.hasProbableDuplicate(b))
        assertFalse(w.hasProbableDuplicate(c))
    }
}
