package com.amandhakar.apkforge

import com.amandhakar.apkforge.core.InstalledApp
import com.amandhakar.apkforge.core.InstalledApps
import com.amandhakar.apkforge.workspace.InstalledApkImporter
import com.amandhakar.apkforge.workspace.InstalledImport
import com.amandhakar.apkforge.workspace.Workspace
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InstalledAppsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun app(label: String, pkg: String, system: Boolean = false) =
        InstalledApp(pkg, label, "1.0", 1, "/data/app/x/base.apk", 0, system)

    private val apps = listOf(
        app("Zebra", "com.z"), app("apple", "com.a"), app("Camera", "com.android.camera", system = true),
    )

    @Test fun hidesSystemAppsByDefaultAndSortsByLabel() {
        assertEquals(listOf("apple", "Zebra"), InstalledApps.filter(apps, "", false).map { it.label })
        assertEquals(3, InstalledApps.filter(apps, "", true).size)
    }

    @Test fun searchesLabelAndPackageIgnoringCase() {
        assertEquals(listOf("Zebra"), InstalledApps.filter(apps, "ZEB", false).map { it.label })
        assertEquals(listOf("apple"), InstalledApps.filter(apps, "com.a", false).map { it.label })
    }

    @Test fun pathChecks() {
        assertTrue(InstalledApps.isCopyablePath("/data/app/~~abc/com.x-1/base.apk"))
        assertTrue(InstalledApps.isCopyablePath("/system/app/Foo/Foo.apk"))
        listOf(null, "", "base.apk", "/data/app/x/base.so", "/data/data/com.x/files/a.apk",
            "/data/user/0/com.x/a.apk", "/data/app/../data/x.apk").forEach {
            assertFalse(it.toString(), InstalledApps.isCopyablePath(it))
        }
    }

    @Test fun importCopiesReadableApk() {
        val src = tmp.newFile("base.apk").apply { writeBytes(ByteArray(100) { 7 }) }
        val ws = Workspace(tmp.root.resolve("ws"))
        val r = InstalledApkImporter(ws, 1000).import("Some App", src.path) as InstalledImport.Imported
        assertEquals(100, r.project.sizeBytes)
        assertEquals(1, ws.list().size)
    }

    @Test fun missingFileIsReportedNotThrown() {
        val ws = Workspace(tmp.root.resolve("ws"))
        val r = InstalledApkImporter(ws, 1000).import("x", tmp.root.resolve("gone/base.apk").path)
        assertEquals(InstalledImport.NotReadable("missing"), r)
        assertTrue(ws.list().isEmpty())
    }

    @Test fun refusesPrivateDataAndNonApkPaths() {
        val ws = Workspace(tmp.root.resolve("ws"))
        val imp = InstalledApkImporter(ws, 1000)
        assertEquals(InstalledImport.NoPath, imp.import("x", "/data/data/com.other/databases/a.apk"))
        assertEquals(InstalledImport.NoPath, imp.import("x", null))
    }

    @Test fun tooLargeLeavesNothing() {
        val src = tmp.newFile("big.apk").apply { writeBytes(ByteArray(500)) }
        val ws = Workspace(tmp.root.resolve("ws"))
        assertEquals(InstalledImport.TooLarge, InstalledApkImporter(ws, 100).import("x", src.path))
        assertTrue(ws.list().isEmpty())
    }
}
