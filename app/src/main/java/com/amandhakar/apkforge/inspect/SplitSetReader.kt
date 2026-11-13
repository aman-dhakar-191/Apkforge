package com.amandhakar.apkforge.inspect

import com.amandhakar.apkforge.core.EntryKind
import com.amandhakar.apkforge.core.InspectionResult
import com.amandhakar.apkforge.core.ManifestResult
import com.amandhakar.apkforge.core.SplitPart
import java.io.File

/** Summarises the other parts of a split app (read only). A part that cannot be read is still listed. */
class SplitSetReader(
    private val inspector: ZipApkInspector = ZipApkInspector(),
    private val manifest: AxmlDecoder = AxmlDecoder(),
) {
    fun read(files: List<File>): List<SplitPart> = files.map { f ->
        val ins = inspector.inspect(f)
        if (ins !is InspectionResult.Ok) {
            SplitPart(f.name, f.length(), null, emptyList(), 0, readable = false)
        } else {
            val name = (manifest.decode(f) as? ManifestResult.Ok)?.rootAttributes?.get("split")?.takeIf { it.isNotBlank() }
            SplitPart(f.name, f.length(), name, ins.summary.nativeAbis, ins.summary.countsByKind[EntryKind.RESOURCE] ?: 0, readable = true)
        }
    }
}
