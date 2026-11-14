package com.amandhakar.apkforge

import com.amandhakar.apkforge.build.*
import com.amandhakar.apkforge.core.ComplexValue
import com.amandhakar.apkforge.inspect.ArscReader
import com.amandhakar.apkforge.inspect.AxmlException
import org.junit.Assert.*
import org.junit.Test

/** fx_values.arsc was compiled by aapt2 and has text in three languages, a styled string, arrays, plurals, colours with a night variant, and every number kind. fx_values.dump.txt is aapt2's own listing. */
class ArscEditorTest {
    private fun res(n: String) = javaClass.getResourceAsStream("/$n")!!.readBytes()
    private val arsc = res("fx_values.arsc")
    private val APP_NAME = 0x7f080001; private val GREETING = 0x7f080003; private val STYLED = 0x7f080004
    private val BRAND = 0x7f030000; private val ARRAY = 0x7f010000; private val PLURALS = 0x7f070000
    private fun slot(id: Int, config: String, item: String? = null) = ArscEditor.slots(arsc, id).single { it.config == config && it.itemName == item }
    private fun readAll(a: ByteArray) = ArscReader().parse(a).entries.mapValues { it.value.type + "/" + it.value.name + "=" + it.value.value + "|" + it.value.configCount }

    // ---- reading
    @Test fun findsTheTextOfEveryLanguage() {
        val s = ArscEditor.slots(arsc, APP_NAME)
        assertEquals(setOf("default", "hi", "de-rCH"), s.map { it.config }.toSet())
        assertEquals("Values Fixture", s.single { it.config == "default" }.text)
        assertEquals("मान फ़िक्स्चर", s.single { it.config == "hi" }.text)
        assertEquals("Werte Fixture CH", s.single { it.config == "de-rCH" }.text)
        assertTrue(s.all { it.editable && !it.styled && it.dataType == 0x03 })
    }

    @Test fun describesConfigurationsLikeAndroidDoes() {
        assertEquals(setOf("default", "night", "hi"), ArscEditor.slots(arsc, BRAND).map { it.config }.toSet())
        val dens = ArscEditor.slots(res("fx_dens.arsc"), 0x7f010000).map { it.config }.toSet() // drawable/logo: default, hdpi, xxhdpi
        assertEquals(setOf("default", "hdpi", "xxhdpi"), dens)
    }

    @Test fun readsArrayAndPluralItemsByName() {
        assertEquals(listOf("red", "blue", "green"), ArscEditor.slots(arsc, ARRAY).map { it.text })
        assertEquals(listOf("item 1", "item 2", "item 3"), ArscEditor.slots(arsc, ARRAY).map { it.itemName })
        assertEquals(mapOf("one" to "%d item", "other" to "%d items"), ArscEditor.slots(arsc, PLURALS).associate { it.itemName to it.text })
    }

    @Test fun recognisesStyledTextAndEveryNumberKind() {
        assertTrue(ArscEditor.slots(arsc, STYLED).single().styled)
        fun type(id: Int) = ArscEditor.slots(arsc, id).single().dataType
        assertEquals(0x12, type(0x7f020000)); assertEquals(0x10, type(0x7f060000)); assertEquals(0x1c, type(0x7f030001))
        assertEquals(0x05, type(0x7f040000)); assertEquals(0x04, type(0x7f040001)); assertEquals(0x06, type(0x7f050000)); assertEquals(0x01, type(0x7f080000))
        assertTrue(ArscEditor.slots(arsc, 0x7f090000).none { it.editable }) // a style: shown, not edited
        assertTrue(ArscEditor.slots(arsc, 0x7f0fffff).isEmpty())            // no such resource
    }

    // ---- editing text
    @Test fun changingOneLanguageChangesOnlyThatLanguage() {
        val edited = ArscEditor.apply(arsc, listOf(SetText(slot(APP_NAME, "default").offset, "Edited name ünï 日本")))
        val s = ArscEditor.slots(edited, APP_NAME)
        assertEquals("Edited name ünï 日本", s.single { it.config == "default" }.text)
        assertEquals("मान फ़िक्स्चर", s.single { it.config == "hi" }.text)
        assertEquals("Werte Fixture CH", s.single { it.config == "de-rCH" }.text)
        // everything else reads exactly as before, and the file only grew
        val before = readAll(arsc).filterKeys { it != APP_NAME }; val after = readAll(edited).filterKeys { it != APP_NAME }
        assertEquals(before, after)
        assertTrue(edited.size > arsc.size)
        assertEquals(edited.size, java.nio.ByteBuffer.wrap(edited).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(4)) // the table's size field follows
    }

    @Test fun styledAndOtherStringsSurviveWhenThePoolGrows() {
        val edited = ArscEditor.apply(arsc, listOf(SetText(slot(GREETING, "default").offset, "Hi %1\$s!")))
        val styled = ArscEditor.slots(edited, STYLED).single()
        assertTrue(styled.styled); assertEquals("Hello bold and italic", styled.text) // the bold/italic spans are still attached
        assertEquals("Hi %1\$s!", slot2(edited, GREETING, "default").text)
        assertEquals("नमस्ते %1\$s", slot2(edited, GREETING, "hi").text)
    }
    private fun slot2(a: ByteArray, id: Int, config: String) = ArscEditor.slots(a, id).single { it.config == config }

    @Test fun editingAllLanguagesAtOnceAndSharingText() {
        val offs = ArscEditor.slots(arsc, APP_NAME).map { it.offset }
        val shared = ArscEditor.apply(arsc, offs.map { SetText(it, "Same everywhere") })
        assertEquals(setOf("Same everywhere"), ArscEditor.slots(shared, APP_NAME).map { it.text }.toSet())
        val distinct = ArscEditor.apply(arsc, offs.mapIndexed { i, o -> SetText(o, "Text number $i!") })
        assertTrue(shared.size < distinct.size) // the same text is stored once
    }

    @Test fun editsArrayAndPluralItems() {
        val edited = ArscEditor.apply(arsc, listOf(SetText(slot(ARRAY, "default", "item 2").offset, "azure"), SetText(slot(PLURALS, "default", "other").offset, "%d things")))
        assertEquals(listOf("red", "azure", "green"), ArscEditor.slots(edited, ARRAY).map { it.text })
        assertEquals(mapOf("one" to "%d item", "other" to "%d things"), ArscEditor.slots(edited, PLURALS).associate { it.itemName to it.text })
    }

    @Test fun emptyAndLongTextWork() {
        val e = ArscEditor.apply(arsc, listOf(SetText(slot(APP_NAME, "default").offset, ""), SetText(slot(GREETING, "default").offset, "x".repeat(3000))))
        assertEquals("", slot2(e, APP_NAME, "default").text); assertEquals(3000, slot2(e, GREETING, "default").text!!.length)
    }

    // ---- editing numbers in place
    @Test fun numbersColoursAndFlagsArePatchedInPlace() {
        val edits = listOf(
            SetValue(slot(BRAND, "default").offset, 0x1c, 0xFF112233.toInt()),
            SetValue(slot(BRAND, "night").offset, 0x1d, 0xFFAABBCC.toInt()),
            SetValue(slot(0x7f020000, "default").offset, 0x12, 0),
            SetValue(slot(0x7f060000, "default").offset, 0x10, 99),
            SetValue(slot(0x7f040000, "default").offset, 0x05, ComplexValue.parseDimension("24dp")!!),
            SetValue(slot(0x7f040003, "default").offset, 0x05, ComplexValue.parseDimension("0.25dp")!!),
            SetValue(slot(0x7f040001, "default").offset, 0x04, java.lang.Float.floatToIntBits(0.75f)),
            SetValue(slot(0x7f050000, "default").offset, 0x06, ComplexValue.parseFraction("25%")!!),
            SetValue(slot(0x7f080000, "default").offset, 0x01, 0x7f080002), // the alias now points at string/empty
        )
        val e = ArscEditor.apply(arsc, edits)
        assertEquals(arsc.size, e.size) // nothing moved
        assertTrue("only the 5 bytes of each edited value may change", arsc.indices.count { arsc[it] != e[it] } <= edits.size * 5)
        val t = ArscReader().parse(e)
        assertEquals("#ff112233", t.entries[BRAND]!!.value); assertEquals("false", t.entries[0x7f020000]!!.value); assertEquals("99", t.entries[0x7f060000]!!.value)
        assertEquals("24.0dp", t.entries[0x7f040000]!!.value); assertEquals("0.25dp", t.entries[0x7f040003]!!.value)
        assertEquals("0.75", t.entries[0x7f040001]!!.value); assertEquals("@0x7f080002", t.entries[0x7f080000]!!.value)
        assertEquals("#ffaabbcc", ArscEditor.slots(e, BRAND).single { it.config == "night" }.let { "#%08x".format(it.data) })
        assertEquals("#ff00aa00", ArscEditor.slots(e, BRAND).single { it.config == "hi" }.let { "#%08x".format(it.data) }) // untouched
    }

    @Test fun textAndNumbersTogether() {
        val e = ArscEditor.apply(arsc, listOf(SetText(slot(APP_NAME, "default").offset, "New"), SetValue(slot(0x7f060000, "default").offset, 0x10, 5)))
        assertEquals("New", slot2(e, APP_NAME, "default").text); assertEquals(5, slot2(e, 0x7f060000, "default").data)
    }

    // ---- refusals
    @Test fun refusesWhatIsNotAValue() {
        for (bad in listOf(listOf(SetValue(0, 0x10, 1)), listOf(SetValue(arsc.size - 4, 0x10, 1)), listOf(SetValue(100, 0x10, 1)),
            listOf(SetValue(slot(BRAND, "default").offset, 0x1c, 1), SetValue(slot(BRAND, "default").offset, 0x1c, 2)))) {
            try { ArscEditor.apply(arsc, bad); fail() } catch (_: AxmlException) {}
        }
        assertArrayEquals(arsc, ArscEditor.apply(arsc, emptyList()))
    }

    @Test fun damagedTablesOnlyFailCleanly() {
        for (n in 0 until arsc.size step 13) try { ArscEditor.slots(arsc.copyOf(n), APP_NAME) } catch (_: AxmlException) {}
        val rnd = java.util.Random(11)
        repeat(300) {
            val c = arsc.copyOf(); repeat(5) { c[rnd.nextInt(c.size)] = rnd.nextInt(256).toByte() }
            try { ArscEditor.slots(c, APP_NAME); ArscEditor.apply(c, listOf(SetText(slot(APP_NAME, "default").offset, "z"))) } catch (_: AxmlException) {}
        }
    }

    // ---- sizes and fractions
    @Test fun everySizeIsEncodedBitForBitLikeAapt2() {
        // fx_dims.arsc holds 26 sizes written by aapt2 (whole, fractional, negative, tiny, huge, several units)
        val arscDims = res("fx_dims.arsc"); val texts = String(res("fx_dims.txt")).lines()
        val byName = ArscReader().parse(arscDims).entries.values.associate { it.name to it.id }
        assertEquals(26, texts.size)
        texts.forEachIndexed { i, text ->
            val expected = ArscEditor.slots(arscDims, byName.getValue("d$i")).single().data
            assertEquals("$text: aapt2 wrote 0x%08x".format(expected), expected, ComplexValue.parseDimension(text))
        }
    }

    @Test fun sizesRoundTripThroughTheirPackedForm() {
        for ((text, shown) in listOf("16dp" to "16.0dp", "14sp" to "14.0sp", "0.5dp" to "0.5dp", "-3px" to "-3.0px", "1.25in" to "1.25in", "100mm" to "100.0mm", "0dp" to "0.0dp", "4000000dp" to "4000000.0dp")) {
            assertEquals(text, shown, ComplexValue.dimension(ComplexValue.parseDimension(text)!!))
        }
        assertEquals("50.0%", ComplexValue.fraction(ComplexValue.parseFraction("50%")!!)); assertEquals("25.0%p", ComplexValue.fraction(ComplexValue.parseFraction("25%p")!!))
        for (bad in listOf("", "dp", "16", "16 dpx", "1e3dp", "99999999999dp")) assertNull(bad, ComplexValue.parseDimension(bad))
        // the encoder must give exactly the bits aapt2 wrote for the same text
        for ((id, text) in listOf(0x7f040000 to "16dp", 0x7f040002 to "14sp", 0x7f040003 to "0.5dp")) {
            val original = ArscEditor.slots(arsc, id).single().data
            assertEquals("$text: aapt2 wrote 0x%08x".format(original), original, ComplexValue.parseDimension(text))
        }
        assertEquals(0x1001, ComplexValue.parseDimension("16dp")); assertEquals(0x0E02, ComplexValue.parseDimension("14sp")); assertEquals(0x40000031, ComplexValue.parseDimension("0.5dp"))
        assertEquals(ArscEditor.slots(arsc, 0x7f050000).single().data, ComplexValue.parseFraction("50%")) // fraction/half
    }
}
