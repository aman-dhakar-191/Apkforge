package com.amandhakar.apkforge

import com.amandhakar.apkforge.build.ManifestSlot
import com.amandhakar.apkforge.code.ClassBrowser
import com.amandhakar.apkforge.code.ClassBrowser.Scope
import com.amandhakar.apkforge.code.SmaliClass
import org.junit.Assert.*
import org.junit.Test

class ClassBrowserTest {
    private fun cls(vararg names: String) = names.map { SmaliClass("L" + it.replace('.', '/') + ";", "classes.dex") }
    private val all = cls(
        "com.acme.app.MainActivity", "com.acme.app.MainActivity\$Inner", "com.acme.app.MainActivity\$\$ExternalSyntheticLambda1",
        "com.acme.app.ui.Home", "com.acme.app.ui.deep.Widget", "com.acme.app.R\$string", "com.acme.app.BuildConfig",
        "androidx.core.Foo", "kotlin.collections.Bar", "a.b.C",
    )
    private val slots = listOf(ManifestSlot(3, "activity", "activity .MainActivity", "ns", "name", 3, 0, ".MainActivity", true))
    private val hints = ClassBrowser.hintsOf("com.acme.app", slots)

    @Test fun appScopeHidesLibrariesAndGeneratedClasses() {
        val l = ClassBrowser.listing(all, "", Scope.App, hints, false)
        val names = l.entries.map { it.cls.name } + l.folders.map { it.label }
        assertFalse(names.any { it.startsWith("androidx") || it.startsWith("kotlin") })
        assertTrue("obfuscated app code stays in App", names.contains("a.b") || l.folders.any { it.path.startsWith("a") })
        val app = ClassBrowser.listing(all, "com.acme.app", Scope.App, hints, false)
        assertEquals(listOf("MainActivity"), app.entries.map { it.cls.name.substringAfterLast('.') })
        assertEquals("inner class is folded under its outer class, generated ones are hidden", 1, app.entries[0].inner)
        assertEquals("Activity", app.entries[0].kind)
        assertEquals(listOf("ui"), app.folders.map { it.label })
    }

    @Test fun singleChainsAreJoined() {
        val l = ClassBrowser.listing(all, "", Scope.App, hints, false)
        assertTrue(l.folders.any { it.label == "com.acme.app" && it.path == "com.acme.app" })
        assertTrue(l.folders.any { it.label == "a.b" })
    }

    @Test fun librariesScopeAndShowGenerated() {
        assertEquals(2, ClassBrowser.count(all, Scope.Libraries, hints, false))
        assertTrue(ClassBrowser.count(all, Scope.All, hints, true) > ClassBrowser.count(all, Scope.All, hints, false))
        assertEquals(all.size, ClassBrowser.count(all, Scope.All, hints, true))
    }

    @Test fun rootsBeatLibraryPrefixes() {
        val h = ClassBrowser.hintsOf("com.google.mine", emptyList())
        assertFalse(ClassBrowser.isLibrary("com.google.mine.Foo", h))
        assertTrue(ClassBrowser.isLibrary("com.google.gson.Foo", h))
    }

    @Test fun searchPutsAppCodeFirst() {
        val r = ClassBrowser.search(all, "o", Scope.All, hints, false, 50).map { it.cls.name }
        assertTrue(r.indexOf("com.acme.app.MainActivity") < r.indexOf("androidx.core.Foo"))
    }
}
