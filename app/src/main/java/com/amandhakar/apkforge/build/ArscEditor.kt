package com.amandhakar.apkforge.build

import com.amandhakar.apkforge.inspect.AxmlException
import com.amandhakar.apkforge.inspect.StringPool
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One value of a resource in one configuration (for example the Hindi text of `string/app_name`).
 * [offset] is where its 8-byte value sits in resources.arsc: that is what an edit aims at.
 */
class ValueSlot(
    /** "default", "hi", "night", "xxhdpi", "de-rCH"... */
    val config: String,
    /** For array and plural items: "item 1", "one", "other"... null for a plain value. */
    val itemName: String?,
    val dataType: Int,
    val data: Int,
    val offset: Int,
    /** The text of a string value; null for other types. */
    val text: String?,
    /** A string with bold/italic spans: replacing it drops the spans. */
    val styled: Boolean,
    /** False for kinds Apkforge does not edit (styles and other maps). */
    val editable: Boolean,
)

sealed interface ValueEdit { val offset: Int }
/** Set a non-string value in place. */
class SetValue(override val offset: Int, val dataType: Int, val data: Int) : ValueEdit
/** Replace a string value with new text. */
class SetText(override val offset: Int, val text: String) : ValueEdit

/**
 * Reads and edits the values in resources.arsc. Numbers, colours, booleans, sizes and references are patched in
 * place (the 8-byte value keeps its size). New text is appended to the shared string pool and the value is
 * pointed at it: the pool grows, everything after it moves, and nothing else in the table holds an absolute
 * position, so only the file size changes. Existing strings, including styled ones, are carried over untouched.
 */
object ArscEditor {
    private const val TABLE = 0x0002; private const val POOL = 0x0001; private const val PACKAGE = 0x0200; private const val TYPE = 0x0201
    private val PLURALS = mapOf(0x01000004 to "other", 0x01000005 to "zero", 0x01000006 to "one", 0x01000007 to "two", 0x01000008 to "few", 0x01000009 to "many")
    private val EDITABLE_TYPES = setOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x10, 0x11, 0x12, 0x1c, 0x1d, 0x1e, 0x1f)

    private fun need(ok: Boolean, msg: String) { if (!ok) throw AxmlException(msg) }

    private fun open(arsc: ByteArray): Triple<ByteBuffer, Int, Int> {
        val b = ByteBuffer.wrap(arsc).order(ByteOrder.LITTLE_ENDIAN)
        need(arsc.size >= 12 && b.getShort(0).toInt() and 0xFFFF == TABLE, "not a resource table")
        val total = minOf(b.getInt(4).also { need(it >= 12, "bad table size") }, arsc.size)
        return Triple(b, b.getShort(2).toInt() and 0xFFFF, total)
    }

    private fun chunk(b: ByteBuffer, pos: Int, limit: Int): Triple<Int, Int, Int> {
        val type = b.getShort(pos).toInt() and 0xFFFF
        val header = b.getShort(pos + 2).toInt() and 0xFFFF
        val size = b.getInt(pos + 4)
        need(header >= 8 && size >= header && pos.toLong() + size <= limit, "bad chunk size at $pos")
        return Triple(type, header, size)
    }

    private fun globalPool(b: ByteBuffer, first: Int, total: Int): Int {
        var pos = first
        while (pos + 8 <= total) {
            val (type, _, size) = chunk(b, pos, total)
            if (type == POOL) return pos
            pos += size
        }
        throw AxmlException("the table has no string pool")
    }

    /** Every value of resource [id], one per configuration (and per item for arrays and plurals). */
    fun slots(arsc: ByteArray, id: Int): List<ValueSlot> {
        val (b, first, total) = open(arsc)
        val pool = globalPool(b, first, total)
        val styled = StringPool.styleCount(b, pool)
        val out = ArrayList<ValueSlot>()
        var pos = first
        while (pos + 8 <= total) {
            val (type, header, size) = chunk(b, pos, total)
            if (type == PACKAGE && b.getInt(pos + 8) == (id ushr 24) && header >= 284) {
                val typeNames = StringPool.read(b, pos + b.getInt(pos + 268), pos + size)
                val typeName = typeNames.getOrNull(((id shr 16) and 0xFF) - 1)
                var p = pos + header
                while (p + 8 <= pos + size) {
                    val (t, h, sz) = chunk(b, p, pos + size)
                    if (t == TYPE && (b.get(p + 8).toInt() and 0xFF) == ((id shr 16) and 0xFF)) readType(b, p, p + sz, h, id and 0xFFFF, typeName, pool, styled, out)
                    p += sz
                }
            }
            pos += size
        }
        return out
    }

    private fun readType(b: ByteBuffer, start: Int, end: Int, header: Int, index: Int, typeName: String?, pool: Int, styled: Int, out: MutableList<ValueSlot>) {
        need(header >= 24 && start + header <= end, "short type header")
        val flags = b.get(start + 9).toInt() and 0xFF
        val count = b.getInt(start + 12)
        val entriesStart = b.getInt(start + 16)
        val configSize = b.getInt(start + 20)
        need(count in 0..0x10000 && entriesStart >= 0 && configSize >= 4 && 20 + configSize <= header, "bad type chunk")
        val config = ConfigLabel.describe(b, start + 20, configSize)
        val table = start + header
        val sparse = flags and 1 != 0; val off16 = flags and 2 != 0
        need(table.toLong() + count.toLong() * (if (off16) 2 else 4) <= end, "entry table runs past the chunk")
        var offset = -1L
        for (i in 0 until count) {
            if (sparse) { val v = b.getInt(table + i * 4); if ((v and 0xFFFF) == index) { offset = (v ushr 16).toLong() * 4; break } }
            else if (i == index) {
                offset = if (off16) (b.getShort(table + i * 2).toInt() and 0xFFFF).let { if (it == 0xFFFF) -1L else it * 4L }
                else b.getInt(table + i * 4).let { if (it == -1) -1L else it.toLong() and 0xFFFFFFFFL }
                break
            }
        }
        if (offset < 0) return
        val e = start.toLong() + entriesStart + offset
        if (e < start || e + 16 > end) return
        val ep = e.toInt()
        val eflags = b.getShort(ep + 2).toInt() and 0xFFFF
        if (eflags and 0x08 != 0) return // compact entries are not supported
        fun slot(valueAt: Int, item: String?, editableMap: Boolean): ValueSlot {
            val dt = b.get(valueAt + 3).toInt() and 0xFF
            val data = b.getInt(valueAt + 4)
            val text = if (dt == 0x03) StringPool.stringAt(b, pool, b.limit(), data) else null
            return ValueSlot(config, item, dt, data, valueAt, text, styled = dt == 0x03 && data in 0 until styled, editable = editableMap && dt in EDITABLE_TYPES)
        }
        if (eflags and 0x01 == 0) {
            if (ep + 16 <= end && b.getShort(ep + 8).toInt() == 8) out += slot(ep + 8, null, true)
        } else {
            val n = b.getInt(ep + 12)
            val items = typeName == "array" || typeName == "plurals"
            if (n !in 0..100_000 || ep.toLong() + 16 + n.toLong() * 12 > end) return
            for (k in 0 until n) {
                val at = ep + 16 + k * 12
                if (b.getShort(at + 4).toInt() != 8) continue
                val name = b.getInt(at)
                out += slot(at + 4, if (typeName == "plurals") (PLURALS[name] ?: "0x%08x".format(name)) else "item ${k + 1}", items)
            }
        }
    }

    /** Applies the edits to a copy of the table. Each edit's [ValueEdit.offset] must be one found by [slots]. */
    fun apply(arsc: ByteArray, edits: List<ValueEdit>): ByteArray {
        if (edits.isEmpty()) return arsc.copyOf()
        val (_, first, total) = open(arsc)
        val work = arsc.copyOf()
        val b = ByteBuffer.wrap(work).order(ByteOrder.LITTLE_ENDIAN)
        need(edits.map { it.offset }.toSet().size == edits.size, "a value is edited twice")
        for (e in edits) need(e.offset in 12..(total - 8) && b.getShort(e.offset).toInt() == 8 && b.get(e.offset + 2).toInt() == 0, "that is not a value in the table")
        for (e in edits) if (e is SetValue) { b.put(e.offset + 3, e.dataType.toByte()); b.putInt(e.offset + 4, e.data) }
        val texts = edits.filterIsInstance<SetText>()
        if (texts.isEmpty()) return work

        val pool = globalPool(b, first, total)
        val poolSize = b.getInt(pool + 4)
        val poolEnd = pool + poolSize
        val stringCount = b.getInt(pool + 8); val styleCount = b.getInt(pool + 12); val flags = b.getInt(pool + 16)
        val stringsStart = b.getInt(pool + 20); val stylesStart = b.getInt(pool + 24)
        val utf8 = flags and 0x100 != 0
        need(stringCount >= 0 && styleCount in 0..stringCount && stringsStart >= 28 + 4 * (stringCount + styleCount), "bad string pool")
        val dataEnd = if (styleCount > 0) stylesStart else poolSize
        need(stringsStart <= dataEnd && dataEnd <= poolSize && (styleCount == 0 || stylesStart >= stringsStart), "bad string pool")

        // new strings (the same text is stored once)
        val newIndex = LinkedHashMap<String, Int>()
        for (t in texts) newIndex.getOrPut(t.text) { stringCount + newIndex.size }
        val encoded = newIndex.keys.map { StringPool.encode(it, utf8) }
        for (t in texts) { b.put(t.offset + 3, 0x03); b.putInt(t.offset + 4, newIndex.getValue(t.text)) }

        val oldData = ByteArray(dataEnd - stringsStart).also { System.arraycopy(work, pool + stringsStart, it, 0, it.size) }
        val styleData = if (styleCount > 0) ByteArray(poolSize - stylesStart).also { System.arraycopy(work, pool + stylesStart, it, 0, it.size) } else ByteArray(0)
        val addedOffsets = IntArray(encoded.size); var run = oldData.size
        encoded.forEachIndexed { i, e -> addedOffsets[i] = run; run += e.size }
        val padding = (4 - run % 4) % 4
        val newCount = stringCount + encoded.size
        val newStringsStart = 28 + 4 * (newCount + styleCount)
        val newStylesStart = if (styleCount > 0) newStringsStart + run + padding else 0
        val newSize = newStringsStart + run + padding + styleData.size

        val np = ByteBuffer.allocate(newSize).order(ByteOrder.LITTLE_ENDIAN)
        np.putShort(POOL.toShort()).putShort(28).putInt(newSize).putInt(newCount).putInt(styleCount).putInt(flags and 0x01.inv()).putInt(newStringsStart).putInt(newStylesStart)
        for (i in 0 until stringCount) np.putInt(b.getInt(pool + 28 + 4 * i))
        addedOffsets.forEach { np.putInt(it) }
        for (i in 0 until styleCount) np.putInt(b.getInt(pool + 28 + 4 * stringCount + 4 * i))
        np.put(oldData); encoded.forEach { np.put(it) }; repeat(padding) { np.put(0) }
        np.put(styleData)

        val out = ByteArrayOutputStream(total + newSize)
        out.write(work, 0, pool)
        out.write(np.array())
        out.write(work, poolEnd, total - poolEnd)
        val result = out.toByteArray()
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(4, result.size)
        return result
    }
}
