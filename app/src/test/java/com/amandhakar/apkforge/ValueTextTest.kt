package com.amandhakar.apkforge

import com.amandhakar.apkforge.build.*
import org.junit.Assert.*
import org.junit.Test

class ValueTextTest {
    private fun slot(type: Int, data: Int = 0, text: String? = null) = ValueSlot("default", null, type, data, 0, text, false, true)
    private val ids = mapOf("string/app_name" to 0x7f080001, "color/brand" to 0x7f030000)
    private fun parse(type: Int, input: String) = ValueText.parse(slot(type), input) { ids[it] }
    private fun value(type: Int, input: String) = parse(type, input) as Parsed.Value
    private fun error(type: Int, input: String) = (parse(type, input) as Parsed.Error).message

    @Test fun showsValuesTheWayPeopleWriteThem() {
        val none: (Int) -> String? = { null }
        assertEquals("hello", ValueText.show(slot(0x03, text = "hello"), none))
        assertEquals("#ff2b3a55", ValueText.show(slot(0x1c, 0xFF2B3A55.toInt()), none)); assertEquals("#2b3a55", ValueText.show(slot(0x1d, 0xFF2B3A55.toInt()), none))
        assertEquals("true", ValueText.show(slot(0x12, -1), none)); assertEquals("-7", ValueText.show(slot(0x10, -7), none)); assertEquals("0xff", ValueText.show(slot(0x11, 255), none))
        assertEquals("16.0dp", ValueText.show(slot(0x05, 0x1001), none)); assertEquals("0.25", ValueText.show(slot(0x04, java.lang.Float.floatToIntBits(0.25f)), none))
        assertEquals("@string/app_name", ValueText.show(slot(0x01, 0x7f080001), { ids.entries.firstOrNull { e -> e.value == it }?.key })); assertEquals("@0x7f099999", ValueText.show(slot(0x01, 0x7f099999), none))
        assertEquals("@null", ValueText.show(slot(0x01, 0), none))
    }

    @Test fun parsesColours() {
        assertEquals(0xFF112233.toInt(), value(0x1c, "#112233").data); assertEquals(0x1d, value(0x1c, "#112233").dataType)
        assertEquals(0x80112233.toInt(), value(0x1c, "#80112233").data); assertEquals(0x1c, value(0x1c, "#80112233").dataType)
        assertEquals(0xFFAABBCC.toInt(), value(0x1c, "#abc").data); assertEquals(0xAABBCCDD.toInt(), value(0x1c, "#ABCD").data)
        for (bad in listOf("112233", "#12", "#12345", "#gg0000", "red", "")) assertTrue(bad, parse(0x1c, bad) is Parsed.Error)
    }

    @Test fun parsesNumbersFlagsAndSizes() {
        assertEquals(42, value(0x10, " 42 ").data); assertEquals(-7, value(0x10, "-7").data); assertEquals(0x11, value(0x10, "0x2a").dataType); assertEquals(0x2a, value(0x10, "0x2a").data)
        assertEquals(0xFFFFFFFFL.toInt(), value(0x11, "0xffffffff").data)
        for (bad in listOf("1.5", "abc", "99999999999", "")) assertTrue(bad, parse(0x10, bad) is Parsed.Error)
        assertEquals(-1, value(0x12, "TRUE").data); assertEquals(0, value(0x12, "false").data); assertTrue(parse(0x12, "yes") is Parsed.Error)
        assertEquals(java.lang.Float.floatToIntBits(0.5f), value(0x04, "0.5").data); assertTrue(parse(0x04, "NaN") is Parsed.Error)
        assertEquals(0x1001, value(0x05, "16dp").data); assertTrue(error(0x05, "16").contains("16dp"))
        assertTrue(parse(0x06, "50%") is Parsed.Value); assertTrue(parse(0x06, "half") is Parsed.Error)
    }

    @Test fun parsesReferencesByNameOrId() {
        assertEquals(0x7f080001, value(0x01, "@string/app_name").data); assertEquals(0, value(0x01, "@null").data); assertEquals(0x7f0a0001, value(0x01, "@0x7f0a0001").data)
        assertTrue(error(0x01, "@string/nothing").contains("No resource named")); assertTrue(parse(0x01, "string/app_name") is Parsed.Error); assertTrue(parse(0x01, "@0xzz") is Parsed.Error)
    }

    @Test fun textKeepsEverythingExceptControlCharacters() {
        assertEquals("  Hello, %1\$s!\n  日本語 ", (parse(0x03, "  Hello, %1\$s!\n  日本語 ") as Parsed.Text).text) // not trimmed: spaces can matter
        assertTrue(parse(0x03, "a\u0000b") is Parsed.Error); assertTrue(parse(0x03, "x".repeat(ValueText.MAX_TEXT + 1)) is Parsed.Error)
        assertTrue(parse(0x03, "") is Parsed.Text)
        assertTrue(parse(0x20, "anything") is Parsed.Error) // a kind that is not editable
    }
}
