package com.amandhakar.apkforge.build

import com.amandhakar.apkforge.inspect.AxmlException

/** A value for a new or changed attribute: text, or a typed [type]/[data]. */
class NewAttr(val name: String, val type: Int, val data: Int, val text: String?)

/**
 * One planned change to a layout. Paths are positions in the tree as it is at that step: the root is `[]`, its second
 * child is `[1]`, and so on. Steps are applied in order.
 */
sealed interface LayoutOp {
    fun describe(): String

    class SetAttr(val path: List<Int>, val ns: String?, val name: String, val type: Int, val data: Int, val text: String?, val shown: String) : LayoutOp {
        override fun describe() = "Change $name to $shown"
    }
    class AddAttr(val path: List<Int>, val attr: NewAttr, val shown: String) : LayoutOp {
        override fun describe() = "Add ${attr.name} = $shown"
    }
    class RemoveAttr(val path: List<Int>, val ns: String?, val name: String) : LayoutOp {
        override fun describe() = "Remove $name"
    }
    class InsertView(val parent: List<Int>, val index: Int, val tag: String, val attrs: List<NewAttr>) : LayoutOp {
        override fun describe() = "Add a ${tag.substringAfterLast('.')}"
    }
    class RemoveView(val path: List<Int>, val label: String) : LayoutOp {
        override fun describe() = "Remove $label"
    }
    /** Moves the view at [path] into the view at [newParent] (a path in the tree after the view was taken out), at [index]. */
    class MoveView(val path: List<Int>, val newParent: List<Int>, val index: Int, val label: String) : LayoutOp {
        override fun describe() = "Move $label"
    }
    class DuplicateView(val path: List<Int>, val label: String) : LayoutOp {
        override fun describe() = "Copy $label"
    }
}

object LayoutEditor {
    private const val MAX_ELEMENTS = 5_000
    private const val MAX_DEPTH = 40
    const val MAX_OPS = 300

    /** Views that cannot hold other views. */
    private val LEAF_TAGS = setOf(
        "TextView", "Button", "ImageView", "EditText", "View", "Space", "CheckBox", "Switch", "ProgressBar", "RadioButton", "ImageButton",
        "SeekBar", "ToggleButton", "CheckedTextView", "TextClock", "Chronometer", "RatingBar", "AutoCompleteTextView", "SurfaceView", "TextureView",
        "androidx.appcompat.widget.AppCompatTextView", "androidx.appcompat.widget.AppCompatButton", "androidx.appcompat.widget.AppCompatImageView",
        "com.google.android.material.button.MaterialButton", "com.google.android.material.textview.MaterialTextView",
    )
    fun isLeaf(tag: String) = tag in LEAF_TAGS || tag.substringAfterLast('.') in LEAF_TAGS && !tag.contains("Layout")

    fun node(tree: AxmlTree, path: List<Int>): XElement {
        var e = tree.root
        for (i in path) e = e.children.getOrNull(i) as? XElement ?: throw AxmlException("There is no view at that place any more.")
        return e
    }

    fun apply(bytes: ByteArray, ops: List<LayoutOp>): ByteArray {
        if (ops.isEmpty()) return bytes
        val tree = AxmlTree.parse(bytes)
        applyTo(tree, ops)
        return tree.serialize()
    }

    fun applyTo(tree: AxmlTree, ops: List<LayoutOp>) {
        if (ops.size > MAX_OPS) throw AxmlException("Too many changes to one file.")
        for (op in ops) applyOne(tree, op)
        if (tree.elementCount() > MAX_ELEMENTS) throw AxmlException("This layout would have too many views.")
    }

    private fun androidAttr(n: NewAttr): XAttr {
        val info = AndroidAttrs.info(n.name) ?: throw AxmlException("android:${n.name} is not an Android attribute Apkforge knows.")
        return XAttr(AndroidAttrs.NS, n.name, info.id, n.type, if (n.type == 0x03) 0 else n.data, if (n.type == 0x03) n.text else null)
    }

    /** Keeps attributes sorted by resource id (Android relies on it); attributes without an id stay last. */
    private fun insertSorted(e: XElement, a: XAttr) {
        val at = e.attrs.indexOfFirst { it.resId == 0 || (it.resId.toLong() and 0xFFFFFFFFL) > (a.resId.toLong() and 0xFFFFFFFFL) }
        e.attrs.add(if (at < 0) e.attrs.size else at, a)
    }

    private fun ensureAndroidNamespace(tree: AxmlTree) {
        fun has(e: XElement): Boolean = e.nsDecls.any { it.uri == AndroidAttrs.NS } || e.children.any { it is XElement && has(it) }
        if (!tree.root.nsDecls.any { it.uri == AndroidAttrs.NS }) tree.root.nsDecls.add(0, NsDecl("android", AndroidAttrs.NS, tree.root.line))
    }

    private fun depthOf(e: XElement): Int = 1 + (e.children.maxOfOrNull { (it as? XElement)?.let(::depthOf) ?: 0 } ?: 0)

    private fun applyOne(tree: AxmlTree, op: LayoutOp) {
        when (op) {
            is LayoutOp.SetAttr -> {
                val a = node(tree, op.path).attr(op.ns, op.name) ?: throw AxmlException("${op.name} is not there any more.")
                a.type = op.type; a.data = if (op.type == 0x03) 0 else op.data; a.text = if (op.type == 0x03) op.text else null; a.raw = null
            }
            is LayoutOp.AddAttr -> {
                val e = node(tree, op.path)
                if (e.attr(AndroidAttrs.NS, op.attr.name) != null) throw AxmlException("${op.attr.name} is already there.")
                ensureAndroidNamespace(tree)
                insertSorted(e, androidAttr(op.attr))
            }
            is LayoutOp.RemoveAttr -> {
                val e = node(tree, op.path)
                if (!e.attrs.removeAll { it.name == op.name && it.ns == op.ns }) throw AxmlException("${op.name} is not there any more.")
            }
            is LayoutOp.InsertView -> {
                val parent = node(tree, op.parent)
                if (op.tag.isBlank() || op.tag.length > 200 || !op.tag.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '$' }) throw AxmlException("That is not a valid view name.")
                ensureAndroidNamespace(tree)
                val el = XElement(null, op.tag, line = parent.line, endLine = parent.line)
                for (n in op.attrs) insertSorted(el, androidAttr(n))
                parent.children.add(op.index.coerceIn(0, parent.children.size), el)
            }
            is LayoutOp.RemoveView -> {
                if (op.path.isEmpty()) throw AxmlException("The outermost view cannot be removed.")
                val parent = node(tree, op.path.dropLast(1))
                if (op.path.last() !in parent.children.indices) throw AxmlException("There is no view at that place any more.")
                parent.children.removeAt(op.path.last())
            }
            is LayoutOp.MoveView -> {
                if (op.path.isEmpty()) throw AxmlException("The outermost view cannot be moved.")
                val from = node(tree, op.path.dropLast(1))
                val moved = from.children.getOrNull(op.path.last()) ?: throw AxmlException("There is no view at that place any more.")
                from.children.removeAt(op.path.last())
                val to = node(tree, op.newParent)
                to.children.add(op.index.coerceIn(0, to.children.size), moved)
            }
            is LayoutOp.DuplicateView -> {
                if (op.path.isEmpty()) throw AxmlException("The outermost view cannot be copied.")
                val parent = node(tree, op.path.dropLast(1))
                val src = parent.children.getOrNull(op.path.last()) as? XElement ?: throw AxmlException("There is no view at that place any more.")
                val copy = src.copyDeep()
                fun dropIds(e: XElement) { e.attrs.removeAll { it.resId == 0x010100d0 }; e.children.forEach { if (it is XElement) dropIds(it) } } // two views must not share an id
                dropIds(copy)
                parent.children.add(op.path.last() + 1, copy)
            }
        }
        if (depthOf(tree.root) > MAX_DEPTH) throw AxmlException("The layout would be nested too deeply.")
    }
}
