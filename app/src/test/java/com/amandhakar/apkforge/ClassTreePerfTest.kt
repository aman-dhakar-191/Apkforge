package com.amandhakar.apkforge

import com.amandhakar.apkforge.code.ClassBrowser
import com.amandhakar.apkforge.code.ClassBrowser.Scope
import com.amandhakar.apkforge.code.SmaliClass
import org.junit.Assert.*
import org.junit.Test

/** A huge app (the one that made the Code tab stop responding had 228,034 classes) must stay quick. */
class ClassTreePerfTest {
    private fun bigApp(): List<SmaliClass> {
        val out = ArrayList<SmaliClass>(250_000)
        fun add(name: String) { out += SmaliClass("L" + name.replace('.', '/') + ";", "classes${out.size % 32}.dex") }
        // 150k classes in obfuscated packages, 60k in the app's own packages, 40k of libraries (some generated)
        for (i in 0 until 150_000) add("p${i % 40}.q${i % 97}.r${i % 13}.C$i")
        for (i in 0 until 60_000) add("com.bigapp.feature${i % 200}.ui.Screen$i" + if (i % 7 == 0) "\$Inner" else "")
        for (i in 0 until 40_000) add(if (i % 3 == 0) "androidx.core.pkg${i % 300}.Lib$i" else "kotlin.coroutines.internal.Lib$i\$\$ExternalSyntheticLambda1")
        return out
    }

    @Test fun buildingAndAskingStayFast() {
        val classes = bigApp()
        val hints = ClassBrowser.Hints(listOf("com.bigapp"), emptyMap())
        val t0 = System.nanoTime()
        val tree = ClassBrowser.Tree.build(classes, hints)
        val build = (System.nanoTime() - t0) / 1_000_000
        val t1 = System.nanoTime()
        repeat(20) {
            tree.listing("", Scope.App, false); tree.listing("com.bigapp", Scope.App, false); tree.listing("p3.q4", Scope.All, true)
            tree.count(Scope.Libraries, false)
        }
        val ask = (System.nanoTime() - t1) / 20 / 1_000_000
        val t2 = System.nanoTime()
        val found = tree.search("Screen1", Scope.App, false, 300)
        val search = (System.nanoTime() - t2) / 1_000_000
        println("build=${build}ms ask=${ask}ms search=${search}ms")
        assertTrue("build took $build ms", build < 5_000)
        assertTrue("a listing took $ask ms", ask < 400)
        assertTrue("search took $search ms", search < 1_500)
        assertEquals(300, found.size)
        // the counts add up
        assertEquals(classes.size, tree.count(Scope.All, true))
        assertEquals(tree.count(Scope.App, true) + tree.count(Scope.Libraries, true), tree.count(Scope.All, true))
    }
}
