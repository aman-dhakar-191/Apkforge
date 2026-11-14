package com.amandhakar.apkforge.code

/**
 * How a patch gets into an app. Android loads classes.dex, classes2.dex, classes3.dex... in that order and the
 * first one that defines a class wins. So the patched classes go into a new classes.dex and every existing dex
 * file moves up by one (classes.dex becomes classes2.dex, and so on). The original code is not touched.
 *
 * What this cannot do: callers that were compiled with the old method copied into them ("inlined") keep the old
 * behaviour, and code that fails Android's verifier will crash when the class loads.
 */
object PatchDex {
    class Plan(val renames: Map<String, String>, val newDexName: String = "classes.dex")

    /** Null (with a reason) when the APK has no usable dex files. */
    fun plan(entryNames: List<String>): Pair<Plan?, String?> {
        val dex = entryNames.filter { Regex("classes\\d*\\.dex").matches(it) }.sortedBy { SmaliService.dexNumber(it) }
        if (dex.isEmpty()) return null to "This APK has no code (no classes.dex)."
        val numbers = dex.map { SmaliService.dexNumber(it) }
        if (numbers != (1..dex.size).toList()) return null to "The APK's code files are not numbered without gaps (${dex.joinToString()}), so adding one in front is not safe."
        return Plan(dex.associateWith { SmaliService.dexName(SmaliService.dexNumber(it) + 1) }) to null
    }

    /** The dex version / instruction set for the patch: the app's own minimum, but at least Android 8. */
    fun apiLevel(minSdk: Int?): Int = (minSdk ?: 26).coerceIn(26, 34)
}
