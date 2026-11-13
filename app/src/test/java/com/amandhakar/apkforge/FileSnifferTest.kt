package com.amandhakar.apkforge

import com.amandhakar.apkforge.core.*
import com.amandhakar.apkforge.inspect.AxmlDecoder
import org.junit.Assert.*
import org.junit.Test

class FileSnifferTest {
    private fun res(n: String) = javaClass.getResourceAsStream("/$n")!!.readBytes()

    @Test fun recognisesEachKind() {
        assertEquals(FileKind.IMAGE, FileSniffer.kind(res("new_logo.png")))
        assertEquals(FileKind.COMPILED_XML, FileSniffer.kind(res("fx_layout.bin")))
        assertEquals(FileKind.COMPILED_XML, FileSniffer.kind(res("fx_clone_manifest.bin")))
        assertEquals(FileKind.TEXT_XML, FileSniffer.kind("<?xml version=\"1.0\"?><a/>".toByteArray()))
        assertEquals(FileKind.TEXT_XML, FileSniffer.kind("\n  <resources>\n</resources>".toByteArray()))
        assertEquals(FileKind.TEXT_XML, FileSniffer.kind(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "<a/>".toByteArray()))
        assertEquals(FileKind.TEXT, FileSniffer.kind("{\"a\": 1}\n".toByteArray()))
        assertEquals(FileKind.TEXT, FileSniffer.kind("a < b is not xml".toByteArray()))
        assertEquals(FileKind.BINARY, FileSniffer.kind(ByteArray(64) { it.toByte() }))
        assertEquals(FileKind.BINARY, FileSniffer.kind(ByteArray(0)))
    }

    @Test fun compiledXmlNeedsAHonestHeader() {
        val good = res("fx_layout.bin")
        assertTrue(FileSniffer.isCompiledXml(good))
        assertFalse(FileSniffer.isCompiledXml(good.copyOf(10)))             // too short
        val lying = good.copyOf(); lying[4] = 0xFF.toByte(); lying[5] = 0xFF.toByte(); lying[6] = 0xFF.toByte(); lying[7] = 0x7F
        assertFalse(FileSniffer.isCompiledXml(lying))                       // claims to be bigger than the file
        assertFalse(FileSniffer.isCompiledXml("<?xml version=\"1.0\"?>......".toByteArray()))
    }

    @Test fun textDetectionHandlesRealWorldContent() {
        assertTrue(FileSniffer.looksLikeText("नया ऐप (copy) – ünïcode 日本語\n".toByteArray()))
        assertFalse(FileSniffer.looksLikeText(byteArrayOf(0x41, 0, 0x42)))                 // NUL byte
        assertFalse(FileSniffer.looksLikeText(byteArrayOf(0xC3.toByte(), 0x28)))           // invalid UTF-8
        // a long text file whose sample ends in the middle of a character is still text
        val long = ("é".repeat(5000)).toByteArray()
        assertTrue(FileSniffer.looksLikeText(long, sample = 8193))
        assertFalse(FileSniffer.looksLikeText(ByteArray(0)))
    }

    @Test fun hexDumpLooksLikeAHexDump() {
        val d = FileSniffer.hexDump("Hello, Apkforge!\u0000\u0001".toByteArray())
        val lines = d.lines().filter { it.isNotEmpty() }
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].startsWith("000000  48 65 6c 6c 6f 2c 20 41"))
        assertTrue(lines[0].endsWith("Hello, Apkforge!"))
        assertTrue(lines[1].startsWith("000010  00 01") && lines[1].endsWith(".."))
        assertTrue(FileSniffer.hexDump(ByteArray(1000), 32).contains("968 more bytes not shown"))
    }

    @Test fun sizesAndFractionsReadLikeAndroidSaysThem() {
        assertEquals("8.0dp", ComplexValue.dimension(0x0801))
        assertEquals("14.0sp", ComplexValue.dimension(0x0E02))
        assertEquals("2.0px", ComplexValue.dimension(0x0200))
        // a half is stored with the finest radix (3): 0x40000000 * 2^-23 = 0.5; the low nibble says "of the parent" or not
        assertEquals("50.0%", ComplexValue.fraction(0x40000030))
        assertEquals("50.0%p", ComplexValue.fraction(0x40000031))
    }

    @Test fun aRealCompiledLayoutReadsAsAndroidShowsIt() {
        // checked against `aapt2 dump xmltree` of the same file: padding 8dp, textSize 14sp, width -1 / -2, weight 0.5
        val xml = AxmlDecoder().decode(res("fx_layout.bin"))
        assertTrue(xml, xml.contains("android:layout_width=\"match_parent\""))
        assertTrue(xml.contains("android:layout_height=\"wrap_content\""))
        assertTrue(xml.contains("android:padding=\"8.0dp\""))
        assertTrue(xml.contains("android:textSize=\"14.0sp\""))
        assertTrue(xml.contains("android:layout_width=\"0.0dp\""))
        assertTrue(xml.contains("android:layout_height=\"2.0dp\""))
        assertTrue(xml.contains("android:layout_weight=\"0.5\""))
        assertTrue(xml.contains("android:textColor=\"#ff336699\""))
        assertTrue(xml.contains("android:text=\"Hello\""))
        assertTrue(xml.contains("android:background=\"@0x7f")) // a reference; shown by name when the resource table is known
        assertTrue(xml.contains("<TextView") && xml.contains("</LinearLayout>"))
    }
}
