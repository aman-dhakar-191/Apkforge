package com.amandhakar.apkforge

import com.amandhakar.apkforge.core.*
import com.amandhakar.apkforge.inspect.ArscReader
import com.amandhakar.apkforge.inspect.AxmlDecoder
import com.amandhakar.apkforge.inspect.AxmlException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Fixtures were compiled with aapt2; fx_resources.dump.txt is `aapt2 dump resources` of the same APK. */
class ArscReaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun res(n: String) = javaClass.getResourceAsStream("/$n")!!.readBytes()
    private val table = ArscReader().parse(res("fx_resources.arsc"))

    @Test fun everyResourceNameMatchesAapt2() {
        val expected = Regex("resource (0x[0-9a-f]+) (\\w+)/(\\w+)").findAll(String(res("fx_resources.dump.txt")))
            .map { Triple(it.groupValues[1].removePrefix("0x").toLong(16).toInt(), it.groupValues[2], it.groupValues[3]) }.toList()
        assertEquals(12, expected.size)
        for ((id, type, name) in expected) assertEquals("0x%08x".format(id), "$type/$name", table.nameOf(id))
        assertEquals(expected.size, table.entries.size)
    }

    @Test fun defaultValuesMatchAapt2() {
        fun v(id: Int) = table.entries[id]!!.value
        assertEquals("Fixture App", v(0x7f080001))
        assertEquals("Hello & welcome", v(0x7f080002))
        assertEquals("42", v(0x7f050000))
        assertEquals("true", v(0x7f020000))
        assertEquals("#ff2b3a55", v(0x7f030000))
        assertEquals("res/layout/main.xml", v(0x7f060000))
        assertEquals("(2 items)", v(0x7f010000))
        assertEquals("16.0dp", v(0x7f040000))
    }

    @Test fun configurationsAreCountedAndDefaultWins() {
        val e = table.entries[0x7f080001]!!
        assertEquals(2, e.configCount) // default + hi
        assertEquals("Fixture App", e.value)
        assertEquals(1, table.entries[0x7f080002]!!.configCount)
    }

    @Test fun referencesShownByName() {
        assertEquals("@string/app_name", table.displayValue(table.entries[0x7f080000]!!))
        assertEquals(listOf("array", "bool", "color", "dimen", "integer", "layout", "plurals", "string", "style", "xml"), table.types)
    }

    @Test fun manifestReferencesResolveToNames() {
        val xml = AxmlDecoder(resolver = table::nameOf).decode(res("fx_manifest_full.bin"))
        assertTrue(xml, xml.contains("android:label=\"@string/app_name\""))
        assertTrue(xml, xml.contains("android:theme=\"@style/AppTheme\""))
        val plain = AxmlDecoder().decode(res("fx_manifest_full.bin"))
        assertTrue(plain.contains("android:label=\"@0x7f080001\""))
    }

    @Test fun truncatedAndCorruptedInputOnlyFailsCleanly() {
        val bin = res("fx_resources.arsc")
        for (n in 0 until bin.size step 5) try { ArscReader().parse(bin.copyOf(n)) } catch (_: AxmlException) {}
        val rnd = java.util.Random(7)
        repeat(500) {
            val c = bin.copyOf(); repeat(6) { c[rnd.nextInt(c.size)] = rnd.nextInt(256).toByte() }
            try { ArscReader().parse(c) } catch (_: AxmlException) {}
        }
    }

    @Test fun rejectsNonTable() {
        try { ArscReader().parse(ByteArray(40)); fail() } catch (_: AxmlException) {}
    }

    @Test fun entryLimit() {
        try { ArscReader(maxEntries = 3).parse(res("fx_resources.arsc")); fail() } catch (e: AxmlException) { assertTrue(e.message!!.contains("too many")) }
    }

    @Test fun readFromApk() {
        val apk = TestZips.zip(tmp.newFile("a.apk"), listOf("resources.arsc" to res("fx_resources.arsc")))
        assertTrue(ArscReader().read(apk) is ResourceResult.Ok)
        val none = TestZips.zip(tmp.newFile("b.apk"), listOf("x" to ByteArray(1)))
        assertTrue(ArscReader().read(none) is ResourceResult.Failed)
    }
}

/** fx_dens.* were compiled by aapt2 with images at several densities; the dump is aapt2's own listing. */
class ArscFilesTest {
    private fun res(n: String) = javaClass.getResourceAsStream("/$n")!!.readBytes()
    private val table = ArscReader().parse(res("fx_dens.arsc"))
    private fun files(id: Int) = table.entries.getValue(id).files.map { (it.density ?: "default") + ":" + it.path }.toSet()

    @Test fun everyDensityFileIsListed() {
        assertEquals(setOf("default:res/drawable/logo.png", "hdpi:res/drawable-hdpi-v4/logo.png", "xxhdpi:res/drawable-xxhdpi-v4/logo.png"), files(0x7f010000))
        assertEquals(setOf("xhdpi:res/mipmap-xhdpi-v4/logo.png"), files(0x7f020000))
        assertEquals(setOf("anydpi:res/drawable-anydpi-v21/vec.xml"), files(0x7f010001))
        assertTrue(table.entries.getValue(0x7f030000).files.isEmpty()) // a string is not a file
    }

    @Test fun matchesAapt2ListingExactly() {
        // every "(config) (file) path" line of aapt2's dump must appear in our file lists
        val dump = String(res("fx_dens.dump.txt"))
        val expected = Regex("\\((\\w*)\\) \\(file\\) (\\S+)").findAll(dump).map { (if (it.groupValues[1].isEmpty()) "default" else it.groupValues[1]) + ":" + it.groupValues[2] }.toSet()
        val ours = table.entries.values.flatMap { e -> e.files.map { (it.density ?: "default") + ":" + it.path } }.toSet()
        assertEquals(expected, ours)
    }
}
