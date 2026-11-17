package com.amandhakar.apkforge.code

import com.amandhakar.apkforge.build.ManifestSlot

/** Groups the classes of an app so its own code is easy to find among library code. Pure logic, no Android. */
object ClassBrowser {
    enum class Scope { App, Libraries, All }

    /** [roots] are the app's own packages; [components] maps a class name to "Activity", "Service"... */
    class Hints(val roots: List<String>, val components: Map<String, String>) {
        companion object { val NONE = Hints(emptyList(), emptyMap()) }
    }

    class Folder(val label: String, val path: String, val classes: Int)
    class Entry(val cls: SmaliClass, val inner: Int, val kind: String?)
    class Listing(val folders: List<Folder>, val entries: List<Entry>)

    private val LIBRARY_PREFIXES = listOf(
        "android.", "androidx.", "kotlin.", "kotlinx.", "java.", "javax.", "j$.", "_COROUTINE.", "com.google.", "com.android.",
        "org.jetbrains.", "org.intellij.", "org.apache.", "org.json.", "org.slf4j.", "okhttp3.", "okio.", "retrofit2.", "dagger.", "javax.inject.",
        "io.reactivex.", "com.squareup.", "com.bumptech.", "coil.", "coil3.", "com.facebook.", "com.airbnb.", "com.fasterxml.", "org.bouncycastle.",
        "io.grpc.", "antlr.", "org.antlr.", "org.jf.", "org.abego.", "org.stringtemplate.", "com.beust.", "io.flutter.", "com.unity3d.", "org.conscrypt.", "org.checkerframework.", "com.jakewharton.",
    )

    fun isGenerated(name: String): Boolean {
        val simple = name.substringAfterLast('.')
        return "\$\$ExternalSynthetic" in name || "\$\$Lambda" in name || "\$\$inlined" in name || simple == "R" || simple.startsWith("R\$") ||
            simple == "BuildConfig" || simple.endsWith("\$serializer") || simple == "ComposableSingletons" || simple.startsWith("ComposableSingletons\$")
    }

    fun hintsOf(appPackage: String?, slots: List<ManifestSlot>): Hints {
        val roots = LinkedHashSet<String>(); val comps = LinkedHashMap<String, String>()
        appPackage?.takeIf { it.isNotBlank() }?.let { roots += it }
        for (s in slots) {
            if (s.name != "name" || s.namespace == null || s.text.isNullOrBlank()) continue
            val kind = when (s.tag) { "activity", "activity-alias" -> "Activity"; "service" -> "Service"; "receiver" -> "Receiver"; "provider" -> "Provider"; "application" -> "Application"; else -> continue }
            val n = s.text
            val full = when { n.startsWith(".") -> (appPackage ?: "") + n; '.' in n -> n; else -> (appPackage?.let { "$it." } ?: "") + n }
            comps[full] = kind
            full.substringBeforeLast('.', "").takeIf { '.' in it }?.let { roots += it }
        }
        return Hints(roots.toList(), comps)
    }

    private fun inRoots(name: String, h: Hints) = h.roots.any { name.startsWith("$it.") }
    fun isLibrary(name: String, h: Hints) = !inRoots(name, h) && LIBRARY_PREFIXES.any { name.startsWith(it) }

    fun inScope(name: String, scope: Scope, h: Hints) = when (scope) {
        Scope.All -> true
        Scope.App -> !isLibrary(name, h)
        Scope.Libraries -> isLibrary(name, h)
    }

    /**
     * The classes arranged as a package tree, built once (off the main thread: Instagram has 228,000 classes). Every
     * question afterwards (open a folder, change a filter, count) is answered from the tree without scanning the classes.
     */
    class Tree private constructor(private val classes: List<SmaliClass>, private val hints: Hints) {
        private class Node(val path: String) {
            val kids = HashMap<String, Node>()
            val own = ArrayList<Int>()
            val ownCount = IntArray(6)
            val total = IntArray(6)
        }
        private val root = Node("")
        private val lib = BooleanArray(classes.size)
        private val gen = BooleanArray(classes.size)
        private val names = Array(classes.size) { classes[it].name }

        init {
            for (i in classes.indices) {
                val n = names[i]
                lib[i] = isLibrary(n, hints); gen[i] = isGenerated(n)
                var node = root
                var start = 0
                while (true) {
                    val dot = n.indexOf('.', start)
                    if (dot < 0) break
                    val seg = n.substring(start, dot)
                    node = node.kids.getOrPut(seg) { Node(if (node.path.isEmpty()) seg else node.path + "." + seg) }
                    start = dot + 1
                    visibleSlots(i) { node.total[it]++ }
                }
                node.own += i
                visibleSlots(i) { node.ownCount[it]++ }
            }
            for (i in classes.indices) visibleSlots(i) { root.total[it]++ }
        }

        /** Calls [f] with every filter combination (scope x show-generated) in which class [i] is visible. */
        private inline fun visibleSlots(i: Int, f: (Int) -> Unit) {
            for (sc in 0..2) for (show in 0..1) {
                val inScope = when (sc) { 0 -> !lib[i]; 1 -> lib[i]; else -> true }
                if (inScope && (show == 1 || !gen[i])) f(sc * 2 + show)
            }
        }
        private fun slot(scope: Scope, showGenerated: Boolean) = scope.ordinal * 2 + if (showGenerated) 1 else 0

        fun count(scope: Scope, showGenerated: Boolean) = root.total[slot(scope, showGenerated)]

        private fun find(path: String): Node? {
            var node = root
            if (path.isEmpty()) return node
            for (seg in path.split('.')) node = node.kids[seg] ?: return null
            return node
        }

        /** The classes directly in package [path] ("" is the top), plus its sub-packages; chains of single sub-packages are joined. */
        fun listing(path: String, scope: Scope, showGenerated: Boolean): Listing {
            val node = find(path) ?: return Listing(emptyList(), emptyList())
            val k = slot(scope, showGenerated)
            fun ownPackage(p: String) = hints.roots.any { p == it || "$p.".startsWith("$it.") || it.startsWith("$p.") }
            val folders = node.kids.entries.filter { it.value.total[k] > 0 }
                .sortedWith(compareBy({ if (ownPackage(it.value.path)) 0 else 1 }, { it.key.lowercase() }))
                .map { (seg, kid) ->
                    var cur = kid; var label = seg
                    while (cur.ownCount[k] == 0) { // join a -> b -> c when nothing else is in a or b
                        val visible = cur.kids.entries.filter { it.value.total[k] > 0 }
                        if (visible.size != 1) break
                        label += "." + visible[0].key; cur = visible[0].value
                    }
                    Folder(label, cur.path, kid.total[k])
                }
            val here = node.own.filter { i -> val show = showGenerated; val inScope = when (scope) { Scope.App -> !lib[i]; Scope.Libraries -> lib[i]; Scope.All -> true }; inScope && (show || !gen[i]) }
            val present = HashSet<String>(here.size * 2).also { set -> here.forEach { set += names[it] } }
            val inner = HashMap<String, Int>()
            val top = ArrayList<Int>()
            for (i in here) {
                val n = names[i]
                val simple = n.substringAfterLast('.')
                val outer = simple.substringBefore('$', "")
                val outerFull = n.substringBeforeLast('.', "").let { if (it.isEmpty()) outer else "$it.$outer" }
                if (outer.isNotEmpty() && outerFull in present) inner.merge(outerFull, 1, Int::plus) else top += i
            }
            val entries = top.sortedBy { names[it].substringAfterLast('.').lowercase() }.map { Entry(classes[it], inner[names[it]] ?: 0, hints.components[names[it]]) }
            return Listing(folders, entries)
        }

        /** Classes whose name contains [query], app classes first, at most [limit]. */
        fun search(query: String, scope: Scope, showGenerated: Boolean, limit: Int): List<Entry> {
            val q = query.trim()
            val app = ArrayList<Int>(); val libs = ArrayList<Int>()
            for (i in classes.indices) {
                if (app.size >= limit) break
                val inScope = when (scope) { Scope.App -> !lib[i]; Scope.Libraries -> lib[i]; Scope.All -> true }
                if (!inScope || (!showGenerated && gen[i]) || !names[i].contains(q, ignoreCase = true)) continue
                if (lib[i]) { if (libs.size < limit) libs += i } else app += i
            }
            return (app + libs).take(limit).map { Entry(classes[it], 0, hints.components[names[it]]) }
        }

        companion object { fun build(classes: List<SmaliClass>, hints: Hints) = Tree(classes, hints) }
    }

    fun count(classes: List<SmaliClass>, scope: Scope, h: Hints, showGenerated: Boolean) = Tree.build(classes, h).count(scope, showGenerated)
    fun listing(classes: List<SmaliClass>, path: String, scope: Scope, h: Hints, showGenerated: Boolean) = Tree.build(classes, h).listing(path, scope, showGenerated)
    fun search(classes: List<SmaliClass>, query: String, scope: Scope, h: Hints, showGenerated: Boolean, limit: Int) = Tree.build(classes, h).search(query, scope, showGenerated, limit)
}
