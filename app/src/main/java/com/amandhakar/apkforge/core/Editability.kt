package com.amandhakar.apkforge.core

import java.io.File

enum class Severity { BLOCKER, WARNING, NOTE }

data class Finding(val severity: Severity, val title: String, val detail: String)

enum class Verdict { NOT_EDITABLE_HERE, MAY_BREAK, INCOMPLETE, NOTHING_FOUND }

data class EditabilityReport(val verdict: Verdict, val findings: List<Finding>)

/** What a scan of the code found. [markers] are ids from [Markers]. */
data class DexScan(
    val markers: Set<String>,
    /** Classes the app itself defines or references, not counting the Android/Java platform. */
    val appClassCount: Int,
    /** Of those, how many have a one- or two-character simple name (typical of shrinking tools). */
    val shortNameCount: Int,
    /** Dex files that could not be read (damaged, too big, or an unsupported format). */
    val skippedFiles: Int,
) {
    val looksObfuscated: Boolean get() = appClassCount >= 50 && shortNameCount * 2 > appClassCount
}

/** A class-name prefix whose presence in an app's code says something about protection. */
data class Marker(val id: String, val descriptorPrefix: String, val title: String, val detail: String)

object Markers {
    /**
     * Only markers that proved reliable. A class being present in the code means the app includes that
     * library, not that it calls it. Android's DRM class (MediaDrm) was tried and dropped: it also shows
     * up in ordinary apps through build-tool backports, so it gave false warnings.
     */
    val all = listOf(
        Marker("PLAY_INTEGRITY", "Lcom/google/android/play/core/integrity/", "Contains Google Play Integrity code",
            "An app that uses this asks Google whether it is the genuine, unmodified version. A re-signed copy usually fails that check, and the app may refuse to work."),
        Marker("SAFETYNET", "Lcom/google/android/gms/safetynet/", "Contains SafetyNet attestation code",
            "An older version of the same kind of check: the app asks Google to vouch for the device and the app. A modified copy usually fails."),
        Marker("APP_CHECK", "Lcom/google/firebase/appcheck/", "Contains Firebase App Check code",
            "The app's servers may refuse requests from a copy that is not the original build."),
        Marker("LICENSING", "Lcom/google/android/vending/licensing/", "Contains Google Play licensing code",
            "The app may check that it was bought or installed through Google Play."),
        Marker("ROOTBEER", "Lcom/scottyab/rootbeer/", "Contains a root-detection library",
            "The app may check whether the phone is modified, and refuse to run."),
    )
    fun byId(id: String): Marker? = all.firstOrNull { it.id == id }
}

object Editability {
    /** Native library names that, from memory of common packer products, suggest the code is protected. Name-based guesses only. */
    private val PACKER_LIBS = listOf("libjiagu", "libdexhelper", "libsecexe", "libsecmain", "libapkprotect", "libprotectclass", "libshella")

    fun packerLibraries(entryNames: List<String>): List<String> =
        entryNames.filter { it.startsWith("lib/") && it.endsWith(".so") }
            .map { it.substringAfterLast('/') }
            .filter { n -> PACKER_LIBS.any { n.lowercase().startsWith(it) } }
            .distinct().sorted()

    fun assess(
        split: SplitInfo?,
        debuggable: Boolean,
        hasNativeLibs: Boolean,
        packerLibs: List<String>,
        scan: DexScan?,
        /** Imported from an installed system app. */
        systemApp: Boolean = false,
        /** Other parts Android listed for the app, and how many of them Apkforge copied. */
        splitsDeclared: Int = 0,
        splitsHeld: Int = 0,
    ): EditabilityReport {
        val f = ArrayList<Finding>()

        if (systemApp) f += Finding(Severity.WARNING, "Part of the phone's own software",
            "This is a system app. A copy installs beside it as an ordinary app, so it can't use privileges only the phone's maker can grant. If the app needs those, the copy may not work.")

        when (split?.kind) {
            SplitKind.BASE_NEEDS_SPLITS -> f += Finding(Severity.BLOCKER, "Split app: whole-set editing is not available",
                when {
                    splitsDeclared > 0 && splitsHeld == splitsDeclared ->
                        "This is a split app. Apkforge holds the base and all $splitsHeld other parts, but changing and re-signing a whole set is not available in this version."
                    splitsHeld > 0 ->
                        "This is a split app. Apkforge holds the base and $splitsHeld of $splitsDeclared other parts; the rest could not be copied. Changing and re-signing a whole set is not available in this version."
                    else ->
                        "This is the base of a split app. It cannot run without its other APK files, and Apkforge only holds this one."
                })
            SplitKind.SPLIT_PART -> f += Finding(Severity.BLOCKER, "Only one part of an app",
                "This file is not a whole app. It needs the base APK.")
            else -> {}
        }

        if (scan != null) {
            for (id in scan.markers.sorted()) Markers.byId(id)?.let { f += Finding(Severity.WARNING, it.title, it.detail) }
        }
        if (packerLibs.isNotEmpty()) f += Finding(Severity.WARNING, "Looks protected by a code packer",
            "Native libraries named like known protection products: ${packerLibs.joinToString()}. The real code may be encrypted and only unpacked when the app runs. This is a guess from file names.")

        if (scan == null) f += Finding(Severity.NOTE, "Code was not checked", "The checks for integrity and DRM libraries need the app's code, which could not be scanned.")
        else if (scan.skippedFiles > 0) f += Finding(Severity.NOTE, "Part of the code was not checked", "${scan.skippedFiles} code file(s) could not be read, so a protection library in them would be missed.")
        if (scan?.looksObfuscated == true) f += Finding(Severity.NOTE, "Names look shortened",
            "Most class names are one or two letters (typical of R8/ProGuard). Code can still be read, but it is harder to follow.")
        if (hasNativeLibs) f += Finding(Severity.NOTE, "Has native code", "Native libraries (.so files) cannot be turned back into source, and Apkforge does not edit them.")
        if (debuggable) f += Finding(Severity.NOTE, "Marked debuggable", "The app declares itself a debug build.")
        f += Finding(Severity.NOTE, "A copy is a different app",
            "Apkforge installs the modified copy beside the original, under a new package name and signed with its own key. The original and its data are not touched. " +
                "Anything tied to the original (Google sign-in, push notifications, licence checks) usually stops working in the copy, and apps that check their own package name or signature may refuse to run.")

        val verdict = when {
            f.any { it.severity == Severity.BLOCKER } -> Verdict.NOT_EDITABLE_HERE
            f.any { it.severity == Severity.WARNING } -> Verdict.MAY_BREAK
            scan == null || scan.skippedFiles > 0 -> Verdict.INCOMPLETE // never say "nothing found" about code we did not read
            else -> Verdict.NOTHING_FOUND
        }
        return EditabilityReport(verdict, f.sortedBy { it.severity })
    }

    fun headline(v: Verdict): String = when (v) {
        Verdict.NOT_EDITABLE_HERE -> "Can't be modified with this version"
        Verdict.MAY_BREAK -> "May stop working if modified"
        Verdict.INCOMPLETE -> "Could not check everything"
        Verdict.NOTHING_FOUND -> "No protection found by these checks"
    }

    const val CANNOT_TELL = "What this cannot tell you: custom signature checks written by the app's own developers, copy protection (DRM), and code that is decrypted while the app runs cannot be found reliably by reading the files. A library being present also does not prove the app uses it. " +
        "\"No protection found\" is not a promise that a modified copy will work; trying it is the only proof. Apkforge is for apps you own or are allowed to analyze."
}

/** Scans an APK's DEX files for protection markers. Reads only. */
interface DexScanner {
    fun scan(apk: File): DexScan
}

/** One other part of a split app that Apkforge holds, with what it carries. */
data class SplitPart(
    val fileName: String,
    val sizeBytes: Long,
    /** The part's own name from its manifest, for example "config.arm64_v8a". */
    val splitName: String?,
    val abis: List<String>,
    val resourceFiles: Int,
    val readable: Boolean,
)
