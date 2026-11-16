package com.amandhakar.apkforge.build

import com.amandhakar.apkforge.inspect.AxmlException
import com.amandhakar.apkforge.inspect.StringPool
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One attribute. [type] 0x03 keeps its text in [text]; every other type keeps [data] (and the original [raw] text, if any). */
class XAttr(var ns: String?, var name: String, var resId: Int, var type: Int, var data: Int, var text: String?, var raw: String? = null) {
    fun copy() = XAttr(ns, name, resId, type, data, text, raw)
}

sealed interface XNode

/** Text or CDATA inside an element. */
class XText(var text: String, var line: Int, val type: Int, val data: Int) : XNode

class NsDecl(var prefix: String?, var uri: String, var line: Int)

class XElement(
    var ns: String?, var name: String, val attrs: MutableList<XAttr> = ArrayList(), val children: MutableList<XNode> = ArrayList(),
    var line: Int = 1, var endLine: Int = 1, val nsDecls: MutableList<NsDecl> = ArrayList(),
) : XNode {
    fun copyDeep(): XElement {
        val e = XElement(ns, name, attrs.mapTo(ArrayList()) { it.copy() }, ArrayList(), line, endLine, nsDecls.mapTo(ArrayList()) { NsDecl(it.prefix, it.uri, it.line) })
        for (c in children) e.children += when (c) { is XElement -> c.copyDeep(); is XText -> XText(c.text, c.line, c.type, c.data) }
        return e
    }
    fun attr(ns: String?, name: String) = attrs.firstOrNull { it.name == name && it.ns == ns }
    fun elementCount(): Int = 1 + children.sumOf { (it as? XElement)?.elementCount() ?: 0 }
}

/**
 * A compiled ("binary") XML file as a tree that can be changed structurally and written back. Strings are held as text,
 * and the string table (with the resource-id map Android needs for attribute names) is rebuilt on [serialize].
 * Anything not understood is refused rather than guessed at.
 */
class AxmlTree(val root: XElement, val utf8: Boolean) {
    companion object {
        private const val MAX_ELEMENTS = 20_000

        fun parse(bytes: ByteArray): AxmlTree {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            need(bytes.size >= 8 && b.getShort(0).toInt() and 0xFFFF == 0x0003, "not compiled Android XML")
            val total = minOf(b.getInt(4).also { need(it >= 8, "bad file size") }, bytes.size)
            var strings: List<String>? = null
            var utf8 = false
            var resIds = IntArray(0)
            var root: XElement? = null
            val stack = ArrayList<XElement>()
            val pending = ArrayList<NsDecl>()
            var nsOpen = 0
            var count = 0
            fun str(i: Int): String? = if (i < 0) null else (strings ?: throw AxmlException("a string is used before the string table")).getOrNull(i) ?: throw AxmlException("string $i does not exist")

            var pos = 8
            while (pos + 8 <= total) {
                val type = b.getShort(pos).toInt() and 0xFFFF
                val header = b.getShort(pos + 2).toInt() and 0xFFFF
                val size = b.getInt(pos + 4)
                need(header >= 8 && size >= header && pos.toLong() + size <= total, "bad chunk size at $pos")
                val end = pos + size
                when (type) {
                    0x0001 -> {
                        need(strings == null, "two string tables")
                        need(b.getInt(pos + 12) == 0, "this file's string table has styled text, which is not supported")
                        utf8 = b.getInt(pos + 16) and 0x100 != 0
                        strings = StringPool.read(b, pos, end)
                    }
                    0x0180 -> { need(size >= 8 && (size - 8) % 4 == 0, "bad resource map"); resIds = IntArray((size - 8) / 4) { b.getInt(pos + 8 + it * 4) } }
                    0x0100 -> { need(size >= 24, "short namespace chunk"); pending += NsDecl(str(b.getInt(pos + 16)), str(b.getInt(pos + 20))!!, b.getInt(pos + 8)); nsOpen++ }
                    0x0101 -> { need(size >= 24, "short namespace chunk"); nsOpen-- ; need(nsOpen >= 0, "unbalanced namespace") }
                    0x0102 -> {
                        need(size >= 36, "short element chunk")
                        need(++count <= MAX_ELEMENTS, "too many elements")
                        val attrStart = b.getShort(pos + 24).toInt() and 0xFFFF
                        val attrSize = b.getShort(pos + 26).toInt() and 0xFFFF
                        val attrCount = b.getShort(pos + 28).toInt() and 0xFFFF
                        need(attrSize == 20, "unusual attribute size")
                        val first = pos + 16 + attrStart
                        need(first + attrCount * 20L <= end, "attributes run past the chunk")
                        val el = XElement(str(b.getInt(pos + 16)), str(b.getInt(pos + 20)) ?: "", line = b.getInt(pos + 8))
                        for (i in 0 until attrCount) {
                            val a = first + i * 20
                            val nameIdx = b.getInt(a + 4)
                            val t = b.get(a + 15).toInt() and 0xFF
                            val data = b.getInt(a + 16)
                            val rawIdx = b.getInt(a + 8)
                            el.attrs += XAttr(
                                str(b.getInt(a)), str(nameIdx) ?: "", resIds.getOrElse(nameIdx) { 0 }, t,
                                if (t == 0x03) 0 else data, if (t == 0x03) str(data) else null, if (t == 0x03) null else str(rawIdx),
                            )
                        }
                        el.nsDecls += pending; pending.clear()
                        if (stack.isEmpty()) { need(root == null, "more than one root element"); root = el } else stack.last().children += el
                        stack += el
                    }
                    0x0103 -> { need(stack.isNotEmpty(), "unbalanced end of element"); stack.removeAt(stack.size - 1).endLine = b.getInt(pos + 8) }
                    0x0104 -> {
                        need(stack.isNotEmpty() && size >= 28, "bad text chunk")
                        stack.last().children += XText(str(b.getInt(pos + 16)) ?: "", b.getInt(pos + 8), b.get(pos + 23).toInt() and 0xFF, b.getInt(pos + 24))
                    }
                    else -> throw AxmlException("unknown chunk type 0x${Integer.toHexString(type)}")
                }
                pos = end
            }
            need(stack.isEmpty() && nsOpen == 0 && pending.isEmpty(), "unclosed element or namespace")
            return AxmlTree(root ?: throw AxmlException("no root element"), utf8)
        }

        private fun need(ok: Boolean, msg: String) { if (!ok) throw AxmlException(msg) }
    }

    fun elementCount() = root.elementCount()

    // ---- writing
    private class Out {
        val o = ByteArrayOutputStream()
        fun u8(v: Int) = o.write(v and 0xFF)
        fun u16(v: Int) { u8(v); u8(v shr 8) }
        fun u32(v: Int) { u16(v); u16(v shr 16) }
        fun bytes(b: ByteArray) = o.write(b)
        fun size() = o.size()
    }

    fun serialize(): ByteArray {
        // string table: attribute names that have a resource id come first (the map aligns with them), then the rest
        val others = LinkedHashMap<String, Int>()
        fun other(s: String?) { if (s != null && s !in others) others[s] = -1 }
        fun walk(e: XElement) {
            for (d in e.nsDecls) { other(d.prefix); other(d.uri) }
            other(e.ns); other(e.name)
            for (a in e.attrs) {
                other(a.ns)
                if (a.resId == 0) other(a.name)
                if (a.type == 0x03) other(a.text) else other(a.raw)
            }
            for (c in e.children) when (c) { is XElement -> walk(c); is XText -> other(c.text) }
        }
        walk(root)
        val mappedList = ArrayList<Pair<Int, String>>()
        run {
            val seen = LinkedHashSet<Pair<Int, String>>()
            fun collect(e: XElement) { for (a in e.attrs) if (a.resId != 0) seen += a.resId to a.name; for (c in e.children) if (c is XElement) collect(c) }
            collect(root)
            mappedList += seen.sortedWith(compareBy({ it.first.toLong() and 0xFFFFFFFFL }, { it.second }))
        }
        val index = HashMap<Pair<Int, String>, Int>(); mappedList.forEachIndexed { i, k -> index[k] = i }
        val pool = ArrayList<String>(mappedList.map { it.second })
        for (s in others.keys) { others[s] = pool.size; pool += s }
        fun ix(s: String?) = if (s == null) -1 else others.getValue(s)
        fun nameIx(a: XAttr) = if (a.resId != 0) index.getValue(a.resId to a.name) else ix(a.name)

        val body = Out()
        if (mappedList.isNotEmpty()) { body.u16(0x0180); body.u16(8); body.u32(8 + 4 * mappedList.size); for (k in mappedList) body.u32(k.first) }
        fun emit(e: XElement) {
            for (d in e.nsDecls) { body.u16(0x0100); body.u16(16); body.u32(24); body.u32(d.line); body.u32(-1); body.u32(ix(d.prefix)); body.u32(ix(d.uri)) }
            body.u16(0x0102); body.u16(16); body.u32(36 + 20 * e.attrs.size); body.u32(e.line); body.u32(-1)
            body.u32(ix(e.ns)); body.u32(ix(e.name)); body.u16(0x14); body.u16(0x14); body.u16(e.attrs.size)
            body.u16(e.attrs.indexOfFirst { it.resId == 0x010100d0 } + 1)
            body.u16(e.attrs.indexOfFirst { it.ns == null && it.name == "class" } + 1)
            body.u16(e.attrs.indexOfFirst { it.ns == null && it.name == "style" } + 1)
            for (a in e.attrs) {
                body.u32(ix(a.ns)); body.u32(nameIx(a))
                body.u32(if (a.type == 0x03) ix(a.text) else ix(a.raw))
                body.u16(8); body.u8(0); body.u8(a.type); body.u32(if (a.type == 0x03) ix(a.text) else a.data)
            }
            for (c in e.children) when (c) {
                is XElement -> emit(c)
                is XText -> { body.u16(0x0104); body.u16(16); body.u32(28); body.u32(c.line); body.u32(-1); body.u32(ix(c.text)); body.u16(8); body.u8(0); body.u8(c.type); body.u32(c.data) }
            }
            body.u16(0x0103); body.u16(16); body.u32(24); body.u32(e.endLine); body.u32(-1); body.u32(ix(e.ns)); body.u32(ix(e.name))
            for (d in e.nsDecls.asReversed()) { body.u16(0x0101); body.u16(16); body.u32(24); body.u32(d.line); body.u32(-1); body.u32(ix(d.prefix)); body.u32(ix(d.uri)) }
        }
        emit(root)
        val poolBytes = AxmlEditor().encodePool(pool, utf8, if (utf8) 0x100 else 0)
        val out = Out()
        out.u16(0x0003); out.u16(8); out.u32(8 + poolBytes.size + body.size())
        out.bytes(poolBytes); out.bytes(body.o.toByteArray())
        return out.o.toByteArray()
    }
}
