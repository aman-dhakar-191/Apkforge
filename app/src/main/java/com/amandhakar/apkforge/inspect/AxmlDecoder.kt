package com.amandhakar.apkforge.inspect

import com.amandhakar.apkforge.core.readUpTo
import com.amandhakar.apkforge.core.ManifestDecoder
import com.amandhakar.apkforge.core.ManifestResult
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

class AxmlException(message: String) : Exception(message)

/**
 * Reader for Android's compiled ("binary") XML, as used by AndroidManifest.xml.
 * Every offset and length is bounds-checked, so damaged or hostile input ends in [AxmlException]
 * and not in a crash, a huge allocation or a loop.
 * Resource references (for example @0x7f0e0001) are shown as numbers: names need resources.arsc.
 */
class AxmlDecoder(
    private val maxBytes: Int = 8 * 1024 * 1024,
    private val maxElements: Int = 100_000,
    private val maxDepth: Int = 256,
    /** Turns a resource id into "type/name" (from resources.arsc) so references can be shown by name. */
    private val resolver: ((Int) -> String?)? = null,
) : ManifestDecoder {

    /** Result of decoding: the readable XML and the root element's attributes (local names). */
    class Decoded(val xml: String, val rootAttributes: Map<String, String>, val applicationAttributes: Map<String, String> = emptyMap())


    override fun decode(apk: File): ManifestResult = try {
        val bytes = ZipFile(apk).use { zip ->
            val e = zip.getEntry("AndroidManifest.xml") ?: return ManifestResult.Failed("The APK has no AndroidManifest.xml")
            if (e.size > maxBytes) return ManifestResult.Failed("The manifest is larger than $maxBytes bytes")
            zip.getInputStream(e).use { it.readUpTo(maxBytes + 1) }
        }
        if (bytes.size > maxBytes) ManifestResult.Failed("The manifest is larger than $maxBytes bytes")
        else decodeFull(bytes).let { ManifestResult.Ok(it.xml, it.rootAttributes, it.applicationAttributes) }
    } catch (e: AxmlException) {
        ManifestResult.Failed("The manifest could not be decoded: ${e.message}")
    } catch (e: IOException) {
        ManifestResult.Failed("The manifest could not be read: ${e.message}")
    }

    fun decode(bytes: ByteArray): String = decodeFull(bytes).xml

    fun decodeFull(bytes: ByteArray): Decoded {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 8) throw AxmlException("file too short")
        if (b.getShort(0).toInt() and 0xFFFF != RES_XML) throw AxmlException("not compiled Android XML")
        val total = minOf(u32(b, 4), bytes.size)
        var strings: List<String> = emptyList()
        var attrResIds = IntArray(0)
        val prefixes = LinkedHashMap<String, String>() // uri -> prefix
        val pendingNs = ArrayList<Pair<String, String>>() // declared on the next element
        val out = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        var depth = 0
        var elements = 0
        val root = LinkedHashMap<String, String>()
        val application = LinkedHashMap<String, String>()

        var pos = 8
        while (pos + 8 <= total) {
            val type = b.getShort(pos).toInt() and 0xFFFF
            val headerSize = b.getShort(pos + 2).toInt() and 0xFFFF
            val size = u32(b, pos + 4)
            if (headerSize < 8 || size < headerSize || pos + size > total) throw AxmlException("bad chunk size at $pos")
            val end = pos + size
            when (type) {
                STRING_POOL -> strings = StringPool.read(b, pos, end)
                RESOURCE_MAP -> {
                    val n = (size - headerSize) / 4
                    attrResIds = IntArray(n) { b.getInt(pos + headerSize + it * 4) }
                }
                START_NS -> {
                    need(pos + 24 <= end, "short namespace chunk")
                    val prefix = str(strings, b.getInt(pos + 16))
                    val uri = str(strings, b.getInt(pos + 20))
                    prefixes[uri] = prefix
                    pendingNs += prefix to uri
                }
                END_NS -> {}
                START_ELEMENT -> {
                    need(pos + 36 <= end, "short element chunk")
                    if (++elements > maxElements) throw AxmlException("too many elements")
                    if (++depth > maxDepth) throw AxmlException("elements nested too deeply")
                    val ns = b.getInt(pos + 16)
                    val name = str(strings, b.getInt(pos + 20))
                    val attrStart = b.getShort(pos + 24).toInt() and 0xFFFF
                    val attrSize = b.getShort(pos + 26).toInt() and 0xFFFF
                    val attrCount = b.getShort(pos + 28).toInt() and 0xFFFF
                    if (attrSize < 20) throw AxmlException("bad attribute size")
                    val first = pos + 16 + attrStart
                    need(first + attrCount.toLong() * attrSize <= end, "attributes run past the chunk")
                    out.append("  ".repeat(depth - 1)).append('<').append(qualified(ns, name, strings, prefixes))
                    for ((p, u) in pendingNs) out.append(" xmlns:").append(p).append("=\"").append(esc(u)).append('"')
                    pendingNs.clear()
                    for (i in 0 until attrCount) {
                        val a = first + i * attrSize
                        val ans = b.getInt(a)
                        var aname = str(strings, b.getInt(a + 4))
                        if (aname.isEmpty()) {
                            val idx = b.getInt(a + 4)
                            aname = if (idx in attrResIds.indices) "attr_0x%08x".format(attrResIds[idx]) else "attr_$idx"
                        }
                        val raw = b.getInt(a + 8)
                        val dataType = b.get(a + 15).toInt() and 0xFF
                        val data = b.getInt(a + 16)
                        val text = value(raw, dataType, data, strings)
                        if (depth == 1) root[aname] = text
                        if (depth == 2 && name == "application") application[aname] = text
                        out.append("\n").append("  ".repeat(depth)).append(qualified(ans, aname, strings, prefixes))
                            .append("=\"").append(esc(text)).append('"')
                    }
                    out.append(">\n")
                }
                END_ELEMENT -> {
                    need(pos + 24 <= end, "short element chunk")
                    if (depth == 0) throw AxmlException("unbalanced end of element")
                    val ns = b.getInt(pos + 16)
                    val name = str(strings, b.getInt(pos + 20))
                    out.append("  ".repeat(depth - 1)).append("</").append(qualified(ns, name, strings, prefixes)).append(">\n")
                    depth--
                }
                CDATA -> {
                    need(pos + 24 <= end, "short text chunk")
                    out.append("  ".repeat(depth)).append(esc(str(strings, b.getInt(pos + 16)))).append('\n')
                }
                else -> {} // unknown chunk types are skipped, as Android does
            }
            pos = end
        }
        if (depth != 0) throw AxmlException("unclosed element")
        return Decoded(out.toString(), root, application)
    }

    private fun qualified(ns: Int, name: String, strings: List<String>, prefixes: Map<String, String>): String {
        if (ns < 0) return name
        val p = prefixes[str(strings, ns)]
        return if (p.isNullOrEmpty()) name else "$p:$name"
    }

    private fun value(raw: Int, type: Int, data: Int, strings: List<String>): String = when (type) {
        T_STRING -> str(strings, data)
        T_REFERENCE -> if (data == 0) "@null" else resolver?.invoke(data)?.let { "@$it" } ?: "@0x%08x".format(data)
        T_ATTRIBUTE -> "?0x%08x".format(data)
        T_FLOAT -> java.lang.Float.intBitsToFloat(data).toString()
        T_INT_DEC -> data.toString()
        T_INT_HEX -> "0x%x".format(data)
        T_BOOL -> if (data != 0) "true" else "false"
        in T_COLOR_FIRST..T_COLOR_LAST -> "#%08x".format(data)
        T_DIMENSION, T_FRACTION -> "0x%08x (${if (type == T_DIMENSION) "dimension" else "fraction"})".format(data)
        else -> if (raw >= 0 && raw < strings.size) strings[raw] else "0x%x (type 0x%x)".format(data, type)
    }

    private fun str(strings: List<String>, i: Int): String = if (i in strings.indices) strings[i] else ""

    private fun u32(b: ByteBuffer, at: Int): Int {
        if (at < 0 || at + 4 > b.limit()) throw AxmlException("read past the end")
        val v = b.getInt(at)
        if (v < 0) throw AxmlException("size too large")
        return v
    }

    private fun need(ok: Boolean, msg: String) { if (!ok) throw AxmlException(msg) }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private companion object {
        const val RES_XML = 0x0003; const val STRING_POOL = 0x0001; const val RESOURCE_MAP = 0x0180
        const val START_NS = 0x0100; const val END_NS = 0x0101; const val START_ELEMENT = 0x0102
        const val END_ELEMENT = 0x0103; const val CDATA = 0x0104
        const val T_REFERENCE = 0x01; const val T_ATTRIBUTE = 0x02; const val T_STRING = 0x03; const val T_FLOAT = 0x04
        const val T_DIMENSION = 0x05; const val T_FRACTION = 0x06; const val T_INT_DEC = 0x10; const val T_INT_HEX = 0x11
        const val T_BOOL = 0x12; const val T_COLOR_FIRST = 0x1c; const val T_COLOR_LAST = 0x1f
    }
}
