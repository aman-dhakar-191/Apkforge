package com.amandhakar.apkforge.core

import java.io.File

object PathSafety {
    /** True when an archive entry name is safe to turn into a relative path. */
    fun isSafeEntryName(name: String): Boolean {
        if (name.isEmpty() || name.length > 1024) return false
        if (name.startsWith("/") || name.contains('\\') || name.contains('\u0000')) return false
        if (name.length >= 2 && name[1] == ':') return false // Windows drive letter
        return name.split('/').none { it == ".." }
    }

    /**
     * Resolves [name] under [base]; null when the name is unsafe or the result is outside [base].
     * Every extraction must go through this.
     */
    fun resolveInside(base: File, name: String): File? {
        if (!isSafeEntryName(name)) return null
        val baseCanon = base.canonicalFile
        val target = File(baseCanon, name).canonicalFile
        return if (target.path.startsWith(baseCanon.path + File.separator)) target else null
    }
}
