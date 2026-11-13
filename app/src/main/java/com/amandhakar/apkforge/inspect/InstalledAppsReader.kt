package com.amandhakar.apkforge.inspect

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.amandhakar.apkforge.core.InstalledApp

/**
 * Lists the apps Android lets this app see. Because of package visibility rules (and because the
 * manifest asks only for launchable apps, not QUERY_ALL_PACKAGES) this is the apps with a launcher icon.
 */
class InstalledAppsReader(private val context: Context) {
    fun read(): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
        }
        return resolved.map { it.activityInfo.packageName }.distinct().mapNotNull { pkg ->
            try {
                val info = if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                else @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0)
                val app = info.applicationInfo ?: return@mapNotNull null
                InstalledApp(
                    packageName = pkg,
                    label = app.loadLabel(pm).toString(),
                    versionName = info.versionName,
                    versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong(),
                    sourceDir = app.sourceDir,
                    splitCount = app.splitSourceDirs?.size ?: 0,
                    isSystem = app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                )
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
    }
}
