package com.amandhakar.apkforge.core

data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    /** Path Android reports for the base APK. Reporting a path does not mean this app may read it. */
    val sourceDir: String?,
    val splitCount: Int,
    val isSystem: Boolean,
    /** Paths Android reports for the other parts of a split app. */
    val splitDirs: List<String> = emptyList(),
)

object InstalledApps {
    /** Search by label or package name, ignoring case; hides system apps unless asked. Sorted by label. */
    fun filter(apps: List<InstalledApp>, query: String, includeSystem: Boolean): List<InstalledApp> {
        val q = query.trim()
        return apps.asSequence()
            .filter { includeSystem || !it.isSystem }
            .filter { q.isEmpty() || it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
            .toList()
    }

    /**
     * Only APK files outside other apps' private data may be copied. [path] comes from PackageManager,
     * but it is still checked before any file is opened.
     */
    fun isCopyablePath(path: String?): Boolean {
        if (path == null || !path.startsWith("/") || !path.endsWith(".apk")) return false
        if (path.split('/').any { it == ".." }) return false
        return !(path.startsWith("/data/data/") || path.startsWith("/data/user/") || path.startsWith("/data/user_de/"))
    }
}
