package com.amandhakar.apkforge.build

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File

/**
 * Hands a rebuilt APK to Android's own installer, which shows its own confirmation. Apkforge never
 * installs, replaces or uninstalls anything silently.
 */
object ForgeInstaller {
    private val _results = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Plain-language messages about how an install went, for showing to the user. */
    val results: SharedFlow<String> = _results

    internal fun report(message: String) { _results.tryEmit(message) }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun start(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("forged.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val intent = Intent(context, InstallReceiver::class.java)
            // The system adds the result extras to this intent, so it must be mutable.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> ForgeInstaller.report("Installed.")
            PackageInstaller.STATUS_FAILURE_ABORTED -> ForgeInstaller.report("Install cancelled.")
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> ForgeInstaller.report(
                "Android refused to install it: the installed copy of this app is signed with a different key than the modified copy. " +
                    "To install the modified copy you would have to uninstall the original first, which deletes the app's data. Apkforge will not do that for you.",
            )
            else -> ForgeInstaller.report("Install failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"}")
        }
    }
}
