package com.amandhakar.apkforge.build

import com.amandhakar.apkforge.inspect.AxmlException
import com.amandhakar.apkforge.inspect.StringPool
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** [value] is the text of a string attribute; [reference] the resource id of a `@resource` attribute (null otherwise). */
class AxmlAttr(val namespace: String?, val name: String, val value: String?, internal val offset: Int, val reference: Int? = null, val dataType: Int = 0x03, val data: Int = 0)
class AxmlElement(val index: Int, val name: String, val depth: Int, val parent: Int, val attrs: List<AxmlAttr>)

/** A parsed compiled-XML file. Keep it to pass back to [AxmlEditor.apply]. */
class AxmlDoc internal constructor(
    val elements: List<AxmlElement>,
    internal val strings: List<String>,
    internal val utf8: Boolean,
    internal val flags: Int,
    internal val poolStart: Int,
    internal val poolEnd: Int,
    internal val total: Int,
)

/** Change an attribute of the [element]-th start tag (0-based, in file order) to the string [newValue]. */
class AttrPatch(val element: Int, val namespace: String?, val attribute: String, val newValue: String) {
    /** A number or flag instead of text: [dataType] 0x10 (decimal), 0x11 (hex) or 0x12 (true/false), with its [data]. */
    constructor(element: Int, namespace: String?, attribute: String, dataType: Int, data: Int) : this(element, namespace, attribute, "") { typed = dataType; typedData = data }
    internal var typed: Int? = null
    internal var typedData: Int = 0
}

/**
 * Changes attribute values in a compiled ("binary") XML file such as AndroidManifest.xml, and nothing else.
 *
 * New strings are added to the end of the string table, so every existing index stays valid. The patched
 * attribute then points at the new string. Nothing is moved, added or removed in the element tree, so
 * attribute order (which Android relies on) is untouched. Every offset is bounds-checked.
 */
class AxmlEditor(private val maxElements: Int = 100_000) {

    fun parse(bytes: ByteArray): AxmlDoc {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        need(bytes.size >= 8 && b.getShort(0).toInt() and 0xFFFF == 0x0003, "not compiled Android XML")
        val total = minOf(b.getInt(4).also { need(it >= 8, "bad file size") }, bytes.size)
        var strings: List<String>? = null
        var utf8 = false; var flags = 0; var poolStart = -1; var poolEnd = -1
        val elements = ArrayList<AxmlElement>()
        val stack = ArrayList<Int>()

        var pos = 8
        while (pos + 8 <= total) {
            val type = b.getShort(pos).toInt() and 0xFFFF
            val header = b.getShort(pos + 2).toInt() and 0xFFFF
            val size = b.getInt(pos + 4)
            need(header >= 8 && size >= header && pos.toLong() + size <= total, "bad chunk size at $pos")
            val end = pos + size
            when (type) {
                0x0001 -> if (strings == null) {
                    need(b.getInt(pos + 12) == 0, "this file's string table has styled text, which is not supported")
                    flags = b.getInt(pos + 16); utf8 = flags and 0x100 != 0
                    strings = StringPool.read(b, pos, end); poolStart = pos; poolEnd = end
                }
                0x0102 -> {
                    val s = strings ?: throw AxmlException("an element comes before the string table")
                    need(pos + 36 <= end, "short element chunk")
                    need(elements.size < maxElements, "too many elements")
                    val name = s.getOrNull(b.getInt(pos + 20)) ?: ""
                    val attrStart = b.getShort(pos + 24).toInt() and 0xFFFF
                    val attrSize = b.getShort(pos + 26).toInt() and 0xFFFF
                    val attrCount = b.getShort(pos + 28).toInt() and 0xFFFF
                    need(attrSize >= 20, "bad attribute size")
                    val first = pos + 16 + attrStart
                    need(first + attrCount.toLong() * attrSize <= end, "attributes run past the chunk")
                    val attrs = (0 until attrCount).map { i ->
                        val a = first + i * attrSize
                        val ns = b.getInt(a)
                        val dataType = b.get(a + 15).toInt() and 0xFF
                        val data = b.getInt(a + 16)
                        AxmlAttr(
                            namespace = if (ns >= 0) s.getOrNull(ns) else null,
                            name = s.getOrNull(b.getInt(a + 4)) ?: "",
                            value = if (dataType == 0x03) s.getOrNull(data) else null,
                            offset = a,
                            reference = if (dataType == 0x01 && data != 0) data else null,
                            dataType = dataType, data = data,
                        )
                    }
                    val index = elements.size
                    elements += AxmlElement(index, name, stack.size, stack.lastOrNull() ?: -1, attrs)
                    stack += index
                }
                0x0103 -> { need(stack.isNotEmpty(), "unbalanced end of element"); stack.removeAt(stack.size - 1) }
            }
            pos = end
        }
        need(stack.isEmpty(), "unclosed element")
        return AxmlDoc(elements, strings ?: throw AxmlException("no string table"), utf8, flags, poolStart, poolEnd, total)
    }

    fun apply(bytes: ByteArray, doc: AxmlDoc, patches: List<AttrPatch>): ByteArray {
        val work = bytes.copyOf()
        val b = ByteBuffer.wrap(work).order(ByteOrder.LITTLE_ENDIAN)
        val strings = ArrayList(doc.strings)
        val index = HashMap<String, Int>().also { m -> strings.forEachIndexed { i, s -> m.putIfAbsent(s, i) } }
        for (p in patches) {
            val el = doc.elements.getOrNull(p.element) ?: throw AxmlException("no such element ${p.element}")
            val attr = el.attrs.firstOrNull { it.name == p.attribute && it.namespace == p.namespace }
                ?: throw AxmlException("<${el.name}> has no attribute ${p.attribute}")
            p.typed?.let { t ->
                require(t == 0x10 || t == 0x11 || t == 0x12) { "unsupported manifest value type" }
                b.putInt(attr.offset + 8, -1); b.put(attr.offset + 15, t.toByte()); b.putInt(attr.offset + 16, p.typedData)
                continue
            }
            val i = index.getOrPut(p.newValue) { strings.add(p.newValue); strings.size - 1 }
            b.putInt(attr.offset + 8, i)          // the raw value
            b.put(attr.offset + 15, 0x03)         // typed value: a string...
            b.putInt(attr.offset + 16, i)         // ...with this index
        }
        val pool = encodePool(strings, doc.utf8, doc.flags and 0x01.inv()) // appended strings are not sorted
        val out = ByteArrayOutputStream(doc.total + pool.size)
        out.write(work, 0, 8)
        out.write(work, 8, doc.poolStart - 8)
        out.write(pool)
        out.write(work, doc.poolEnd, doc.total - doc.poolEnd)
        val result = out.toByteArray()
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(4, result.size)
        return result
    }

    private fun encodePool(strings: List<String>, utf8: Boolean, flags: Int): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = IntArray(strings.size)
        strings.forEachIndexed { i, s ->
            offsets[i] = data.size()
            if (utf8) {
                val raw = s.toByteArray(Charsets.UTF_8)
                need(s.length <= 0x7FFF && raw.size <= 0x7FFF, "a string is too long")
                varint(data, s.length); varint(data, raw.size); data.write(raw); data.write(0)
            } else {
                need(s.length <= 0x7FFF, "a string is too long")
                data.write(s.length and 0xFF); data.write(s.length shr 8)
                for (c in s) { data.write(c.code and 0xFF); data.write(c.code shr 8) }
                data.write(0); data.write(0)
            }
        }
        while (data.size() % 4 != 0) data.write(0)
        val stringsStart = 28 + 4 * strings.size
        val size = stringsStart + data.size()
        val out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(0x0001).putShort(28).putInt(size).putInt(strings.size).putInt(0).putInt(flags).putInt(stringsStart).putInt(0)
        offsets.forEach { out.putInt(it) }
        out.put(data.toByteArray())
        return out.array()
    }

    private fun varint(o: ByteArrayOutputStream, v: Int) { if (v > 0x7F) { o.write((v shr 8) or 0x80); o.write(v and 0xFF) } else o.write(v) }
    private fun need(ok: Boolean, msg: String) { if (!ok) throw AxmlException(msg) }
}
