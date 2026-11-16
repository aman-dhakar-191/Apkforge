package com.amandhakar.apkforge.build

import com.amandhakar.apkforge.inspect.AxmlException
import java.io.File
import java.util.zip.ZipFile

/**
 * The other parts of a split app (native libraries, screen densities, languages) travel with the base and must match it.
 * [prepare] works out what a part needs when the base is renamed or its version changes; everything else in the part is
 * kept byte for byte.
 */
object SplitSet {
    class Changes(val replacements: Map<String, ByteArray>, val notes: List<String>)

    /**
     * [oldPackage] is the base's package name; a part with another package is refused (it belongs to some other app).
     * With [newPackage] the part's package name (manifest and resource table) is renamed too; [versionCode] is copied
     * into the part when the base's version was changed, because Android refuses a set whose parts disagree.
     */
    fun prepare(part: File, oldPackage: String, newPackage: String?, versionCode: Int?): Changes {
        val out = LinkedHashMap<String, ByteArray>()
        ZipFile(part).use { z ->
            val m = z.getEntry("AndroidManifest.xml") ?: throw AxmlException("${part.name} has no manifest")
            if (m.size > 8 * 1024 * 1024) throw AxmlException("${part.name}: the manifest is too large")
            val manifest = z.getInputStream(m).use { it.readBytes() }
            val editor = AxmlEditor()
            val doc = editor.parse(manifest)
            val root = doc.elements.firstOrNull() ?: throw AxmlException("${part.name}: empty manifest")
            val pkg = root.attrs.firstOrNull { it.name == "package" && it.namespace == null }?.value
            if (pkg != oldPackage) throw AxmlException("${part.name} belongs to another app ($pkg), not $oldPackage")
            val patches = ArrayList<AttrPatch>()
            if (newPackage != null) patches += AttrPatch(root.index, null, "package", newPackage)
            if (versionCode != null) root.attrs.firstOrNull { it.name == "versionCode" && it.namespace != null }?.let { patches += AttrPatch(root.index, it.namespace, "versionCode", 0x10, versionCode) }
            if (patches.isNotEmpty()) out["AndroidManifest.xml"] = editor.apply(manifest, doc, patches)
            if (newPackage != null) {
                val e = z.getEntry("resources.arsc")
                if (e != null) {
                    if (e.size > 96 * 1024 * 1024) throw AxmlException("${part.name}: the resource table is too large")
                    ArscPackage.rename(z.getInputStream(e).use { it.readBytes() }, oldPackage, newPackage)?.let { out["resources.arsc"] = it }
                }
            }
        }
        return Changes(out, emptyList())
    }
}
