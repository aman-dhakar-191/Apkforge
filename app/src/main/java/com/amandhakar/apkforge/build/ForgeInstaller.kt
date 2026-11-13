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
    /** What happened, for showing to the user. [token] is Android's INSTALL_* code when it gave one (no app names). */
    class Result(val message: String, val status: Int, val token: String?)

    private val _outcomes = MutableSharedFlow<Result>(extraBufferCapacity = 8)
    val outcomes: SharedFlow<Result> = _outcomes

    /**
     * Also saved, because the app can be restarted while Android's installer is on screen, and then nobody is
     * listening when the answer arrives. The next start picks the saved answer up with [takeSaved].
     */
    internal fun report(context: Context, message: String, status: Int, token: String?) {
        context.applicationContext.getSharedPreferences("install", Context.MODE_PRIVATE).edit()
            .putString("message", message).putInt("status", status).putString("token", token).apply()
        _outcomes.tryEmit(Result(message, status, token))
    }

    fun takeSaved(context: Context): Result? {
        val p = context.applicationContext.getSharedPreferences("install", Context.MODE_PRIVATE)
        val m = p.getString("message", null) ?: return null
        val r = Result(m, p.getInt("status", 0), p.getString("token", null))
        p.edit().clear().apply()
        return r
    }

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
            PackageInstaller.STATUS_SUCCESS -> ForgeInstaller.report(context, "Installed.", status, null)
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                ForgeInstaller.report(context, InstallFailure.explain(status, message), status, InstallFailure.token(message))
            }
        }
    }
}
