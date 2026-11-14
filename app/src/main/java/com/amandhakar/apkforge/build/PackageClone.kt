package com.amandhakar.apkforge.build

import com.amandhakar.apkforge.inspect.AxmlDecoder
import com.amandhakar.apkforge.inspect.AxmlException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/** How the copy is named. The copy installs beside the original because its package name differs. */
class CloneSpec(val oldPackage: String, val suffix: String, val newLabel: String?) {
    val newPackage: String get() = "$oldPackage.$suffix"
}

sealed interface CloneResult {
    /** [replacements] maps APK entry names to their new bytes. [renamed] and [notes] are for showing the user. */
    class Ok(val replacements: Map<String, ByteArray>, val renamed: List<String>, val notes: List<String>) : CloneResult
    class Unsupported(val reason: String) : CloneResult
}

/**
 * Renames an app's package so a modified copy installs next to the original.
 *
 * The package name is the app's identity to Android, and it appears in more places than the manifest's
 * `package` attribute: content-provider authorities and custom permissions must be unique on the phone,
 * so they get the new name too. Class names (`android:name` of activities and so on) are NOT renamed:
 * they point at code inside the app, which keeps its original names.
 *
 * What this cannot fix: code that checks its own package name, and anything tied to the original
 * (sign-in with Google, push notifications, licensing). Those usually stop working in the copy.
 */
object PackageClone {
    private val SEGMENT = Regex("[A-Za-z][A-Za-z0-9_]*")
    private val SUFFIX = Regex("[a-z][a-z0-9_]{0,23}")
    private val LAUNCHER_CATEGORIES = setOf("android.intent.category.LAUNCHER", "android.intent.category.LEANBACK_LAUNCHER")

    /** Null if [suffix] can be added to a package name, otherwise why not. */
    fun suffixProblem(suffix: String): String? = when {
        !SUFFIX.matches(suffix) -> "Use 1 to 24 lowercase letters, digits or underscores, starting with a letter."
        else -> null
    }

    fun packageProblem(name: String): String? = when {
        name.length > 200 -> "The package name is too long."
        name.split('.').size < 2 || !name.split('.').all { SEGMENT.matches(it) } -> "That is not a valid package name."
        else -> null
    }

    fun labelProblem(label: String): String? = when {
        label.isBlank() -> "The name cannot be empty."
        label.length > 60 -> "Use 60 characters or fewer."
        label.any { it.isISOControl() } -> "The name cannot contain control characters."
        else -> null
    }

    fun apply(manifest: ByteArray, arsc: ByteArray?, spec: CloneSpec): CloneResult {
        suffixProblem(spec.suffix)?.let { return CloneResult.Unsupported(it) }
        packageProblem(spec.newPackage)?.let { return CloneResult.Unsupported(it) }
        spec.newLabel?.let { l -> labelProblem(l)?.let { return CloneResult.Unsupported(it) } }
        val editor = AxmlEditor()
        val doc = try { editor.parse(manifest) } catch (e: AxmlException) { return CloneResult.Unsupported("The manifest could not be read: ${e.message}") }

        val root = doc.elements.firstOrNull() ?: return CloneResult.Unsupported("The manifest is empty.")
        val pkg = root.attrs.firstOrNull { it.name == "package" && it.namespace == null }?.value
        if (pkg != spec.oldPackage) return CloneResult.Unsupported("The manifest's package name does not match the app.")
        if (root.attrs.any { it.name == "sharedUserId" && !it.value.isNullOrBlank() }) {
            return CloneResult.Unsupported("This app shares a user id with other apps of the same developer (sharedUserId). A renamed copy could not join them, so it would not work.")
        }

        val old = spec.oldPackage; val new = spec.newPackage
        fun swap(v: String): String? = when { v == old -> new; v.startsWith("$old.") -> new + v.substring(old.length); else -> null }
        val renamed = ArrayList<String>(); val notes = ArrayList<String>()
        val patches = ArrayList<AttrPatch>()
        fun patch(el: AxmlElement, a: AxmlAttr, value: String, what: String) { patches += AttrPatch(el.index, a.namespace, a.name, value); renamed += what }

        // Every permission, group or tree the app DEFINES must be unique on the phone, wherever its name comes from:
        // two apps defining the same name make Android refuse the install. References are renamed to match, and
        // only references to names this app defines (a permission some other app owns must keep its name).
        val definedPermissions = doc.elements.filter { it.name in setOf("permission", "permission-group", "permission-tree") }
            .mapNotNull { e -> e.attrs.firstOrNull { it.name == "name" && it.namespace != null }?.value }.toSet()
        fun permission(v: String): String? = if (v in definedPermissions) (swap(v) ?: "$v.${spec.suffix}") else null

        val launchers = launcherActivities(doc)
        for (el in doc.elements) for (a in el.attrs) {
            val v = a.value ?: continue
            val isAndroid = a.namespace != null
            when {
                el.index == root.index && a.name == "package" && !isAndroid -> patch(el, a, new, "package: $old → $new")
                el.name == "provider" && a.name == "authorities" && isAndroid -> {
                    val parts = v.split(';').map { it.trim() }.filter { it.isNotEmpty() }
                    val out = parts.map { swap(it) ?: "$it.${spec.suffix}".also { n -> notes += "Provider authority \"$it\" is not under the app's package name; renamed to \"$n\" so two copies can coexist. Code that uses the old name directly will not find it." } }
                    patch(el, a, out.joinToString(";"), "authorities: $v → ${out.joinToString(";")}")
                }
                el.name in setOf("permission", "permission-group", "permission-tree", "uses-permission", "uses-permission-sdk-23") && a.name == "name" && isAndroid ->
                    permission(v)?.let { patch(el, a, it, "${el.name}: $v → $it") }
                a.name in setOf("permission", "readPermission", "writePermission", "permissionGroup") && isAndroid ->
                    permission(v)?.let { patch(el, a, it, "${el.name} ${a.name}: $v → $it") }
                a.name in setOf("taskAffinity", "process") && isAndroid ->
                    swap(v)?.let { patch(el, a, it, "${el.name} ${a.name}: $v → $it") }
                el.name == "instrumentation" && a.name == "targetPackage" && isAndroid -> swap(v)?.let { patch(el, a, it, "instrumentation target: $v → $it") }
            }
        }
        if (spec.newLabel != null) {
            var labelled = false
            for (el in doc.elements) {
                if (!(el.name == "application" || (el.name in setOf("activity", "activity-alias") && el.index in launchers))) continue
                val a = el.attrs.firstOrNull { it.name == "label" && it.namespace != null } ?: continue
                patch(el, a, spec.newLabel, "${el.name} name → \"${spec.newLabel}\""); labelled = true
            }
            if (!labelled) notes += "The app has no name attribute to change, so the copy keeps the original name on the home screen."
        }

        val out = mutableMapOf<String, ByteArray>()
        try { out["AndroidManifest.xml"] = editor.apply(manifest, doc, patches) } catch (e: AxmlException) { return CloneResult.Unsupported("The manifest could not be rewritten: ${e.message}") }
        if (arsc != null) {
            val n = try { ArscPackage.rename(arsc, old, new) } catch (e: AxmlException) { return CloneResult.Unsupported("The resource table could not be rewritten: ${e.message}") }
            if (n == null) notes += "The resource table has no package named $old; it was left alone."
            else { out["resources.arsc"] = n; renamed += "resources.arsc package name → $new" }
        }
        notes += "The copy is a separate app with its own data: you will have to sign in again. Anything tied to the original (Google sign-in, push notifications, licence checks) usually stops working."
        return CloneResult.Ok(out, renamed, notes)
    }

    /** Indexes of activities (and aliases) that have a launcher entry: the label Android shows on the home screen. */
    private fun launcherActivities(doc: AxmlDoc): Set<Int> {
        val children = doc.elements.groupBy { it.parent }
        fun value(e: AxmlElement) = e.attrs.firstOrNull { it.name == "name" && it.namespace != null }?.value
        return doc.elements.filter { it.name == "activity" || it.name == "activity-alias" }.filter { act ->
            children[act.index].orEmpty().filter { it.name == "intent-filter" }.any { f ->
                val kids = children[f.index].orEmpty()
                kids.any { it.name == "action" && value(it) == "android.intent.action.MAIN" } &&
                    kids.any { it.name == "category" && value(it) in LAUNCHER_CATEGORIES }
            }
        }.map { it.index }.toSet()
    }

    /** Reads the manifest and resource table from [apk] and works out the changes. */
    fun prepare(apk: File, spec: CloneSpec, arscOverride: ByteArray? = null, manifestOverride: ByteArray? = null): CloneResult = try {
        ZipFile(apk).use { z ->
            val m = z.getEntry("AndroidManifest.xml") ?: return CloneResult.Unsupported("The APK has no manifest.")
            if (m.size > 8 * 1024 * 1024) return CloneResult.Unsupported("The manifest is too large.")
            val manifest = manifestOverride ?: z.getInputStream(m).use { it.readBytes() }
            val arscEntry = z.getEntry("resources.arsc")
            if (arscEntry != null && arscEntry.size > 96 * 1024 * 1024) return CloneResult.Unsupported("The resource table is too large.")
            apply(manifest, arscOverride ?: arscEntry?.let { e -> z.getInputStream(e).use { it.readBytes() } }, spec)
        }
    } catch (e: java.io.IOException) {
        CloneResult.Unsupported("The APK could not be read: ${e.message}")
    }
}

/** Renames the package inside resources.arsc: a fixed-size name field, patched in place. */
object ArscPackage {
    private const val NAME_CHARS = 128

    /** Number of bytes changed or null when no package has the old name. Returns a patched copy. */
    fun rename(arsc: ByteArray, old: String, new: String): ByteArray? {
        if (new.length >= NAME_CHARS) throw AxmlException("the new package name is too long for the resource table")
        val out = arsc.copyOf()
        val b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        if (out.size < 12 || b.getShort(0).toInt() and 0xFFFF != 0x0002) throw AxmlException("not a resource table")
        val total = minOf(b.getInt(4).also { if (it < 12) throw AxmlException("bad table size") }, out.size)
        var pos = b.getShort(2).toInt() and 0xFFFF
        var changed = 0
        while (pos + 8 <= total) {
            val type = b.getShort(pos).toInt() and 0xFFFF
            val header = b.getShort(pos + 2).toInt() and 0xFFFF
            val size = b.getInt(pos + 4)
            if (header < 8 || size < header || pos.toLong() + size > total) throw AxmlException("bad chunk size at $pos")
            if (type == 0x0200 && header >= 12 + NAME_CHARS * 2) {
                val name = buildString { for (i in 0 until NAME_CHARS) { val c = b.getShort(pos + 12 + i * 2).toInt() and 0xFFFF; if (c == 0) break; append(c.toChar()) } }
                if (name == old) {
                    for (i in 0 until NAME_CHARS) b.putShort(pos + 12 + i * 2, (if (i < new.length) new[i].code else 0).toShort())
                    changed++
                }
            }
            pos += size
        }
        return if (changed > 0) out else null
    }

    fun packageNames(arsc: ByteArray): List<String> {
        val b = ByteBuffer.wrap(arsc).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<String>()
        var pos = b.getShort(2).toInt() and 0xFFFF
        val total = minOf(b.getInt(4), arsc.size)
        while (pos + 8 <= total) {
            val size = b.getInt(pos + 4)
            if (size < 8 || pos.toLong() + size > total) break
            if ((b.getShort(pos).toInt() and 0xFFFF) == 0x0200) {
                out += buildString { for (i in 0 until NAME_CHARS) { val c = b.getShort(pos + 12 + i * 2).toInt() and 0xFFFF; if (c == 0) break; append(c.toChar()) } }
            }
            pos += size
        }
        return out
    }
}
