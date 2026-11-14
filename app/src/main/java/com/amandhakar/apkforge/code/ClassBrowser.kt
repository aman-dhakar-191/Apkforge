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

    fun count(classes: List<SmaliClass>, scope: Scope, h: Hints, showGenerated: Boolean) =
        classes.count { inScope(it.name, scope, h) && (showGenerated || !isGenerated(it.name)) }

    /** The classes directly in package [path] ("" is the top), plus its sub-packages; chains of single sub-packages are joined. */
    fun listing(classes: List<SmaliClass>, path: String, scope: Scope, h: Hints, showGenerated: Boolean): Listing {
        val subs = LinkedHashMap<String, Int>()
        val here = ArrayList<SmaliClass>()
        val prefix = if (path.isEmpty()) "" else "$path."
        for (c in classes) {
            val n = c.name
            if (!n.startsWith(prefix) || !inScope(n, scope, h) || (!showGenerated && isGenerated(n))) continue
            val rest = n.substring(prefix.length)
            val dot = rest.indexOf('.')
            if (dot < 0) here += c else subs.merge(rest.substring(0, dot), 1, Int::plus)
        }
        fun ownPackage(seg: String): Boolean { val q = prefix + seg; return h.roots.any { q == it || "$q.".startsWith("$it.") || it.startsWith("$q.") } }
        val folders = subs.entries.sortedWith(compareBy({ if (ownPackage(it.key)) 0 else 1 }, { it.key.lowercase() })).map { (seg, cnt) ->
            var label = seg; var p = prefix + seg
            while (true) { // join a -> b -> c when nothing else is in a or b
                val only = singleChild(classes, p, scope, h, showGenerated) ?: break
                label += ".$only"; p += ".$only"
            }
            Folder(label, p, cnt)
        }
        // inner classes (Outer$Inner) are folded under their outer class
        val names = here.map { it.name }.toSet()
        val inner = HashMap<String, Int>()
        val top = ArrayList<SmaliClass>()
        for (c in here) {
            val simple = c.name.substringAfterLast('.')
            val outer = simple.substringBefore('$', "")
            val outerFull = c.name.substringBeforeLast('.', "").let { if (it.isEmpty()) outer else "$it.$outer" }
            if (outer.isNotEmpty() && outerFull in names) inner.merge(outerFull, 1, Int::plus) else top += c
        }
        val entries = top.sortedBy { it.name.substringAfterLast('.').lowercase() }.map { Entry(it, inner[it.name] ?: 0, h.components[it.name]) }
        return Listing(folders, entries)
    }

    /** The only sub-package name of [p] when [p] holds no classes of its own and exactly one sub-package. */
    private fun singleChild(classes: List<SmaliClass>, p: String, scope: Scope, h: Hints, showGenerated: Boolean): String? {
        var child: String? = null
        val prefix = "$p."
        for (c in classes) {
            val n = c.name
            if (!n.startsWith(prefix) || !inScope(n, scope, h) || (!showGenerated && isGenerated(n))) continue
            val rest = n.substring(prefix.length); val dot = rest.indexOf('.')
            if (dot < 0) return null
            val seg = rest.substring(0, dot)
            if (child == null) child = seg else if (child != seg) return null
        }
        return child
    }

    /** Flat results for a search, app code first. */
    fun search(classes: List<SmaliClass>, query: String, scope: Scope, h: Hints, showGenerated: Boolean, limit: Int): List<Entry> {
        val q = query.trim()
        return classes.asSequence()
            .filter { it.name.contains(q, ignoreCase = true) && inScope(it.name, scope, h) && (showGenerated || !isGenerated(it.name)) }
            .sortedBy { if (isLibrary(it.name, h)) 1 else 0 }
            .take(limit).map { Entry(it, 0, h.components[it.name]) }.toList()
    }
}
