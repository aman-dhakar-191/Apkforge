package com.amandhakar.apkforge.build

/**
 * Turns Android's install-failure status and message into words for the user. The status is one of
 * PackageInstaller's STATUS_FAILURE* numbers; the message usually holds an INSTALL_FAILED_* code,
 * which says what actually went wrong. Never guess a cause that Android did not report.
 */
object InstallFailure {
    private val TOKEN = Regex("INSTALL_[A-Z_]+")

    /** The INSTALL_* code in Android's message, e.g. INSTALL_FAILED_DUPLICATE_PERMISSION; safe to log (no app names). */
    fun token(message: String?): String? = message?.let { TOKEN.find(it)?.value }

    private val explanations = mapOf(
        "INSTALL_FAILED_DUPLICATE_PERMISSION" to "The copy defines a permission whose name an installed app (probably the original) already defines.",
        "INSTALL_FAILED_CONFLICTING_PROVIDER" to "A content-provider name in the copy is already used by an installed app.",
        "INSTALL_FAILED_DUPLICATE_PACKAGE" to "An app with this package name is already being installed.",
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE" to "An app with this package name is already installed with a different signature. Choose another package ending, or uninstall that app first (which deletes its data).",
        "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE" to "The app shares a user id with other apps and cannot be installed as a copy.",
        "INSTALL_FAILED_MISSING_SHARED_LIBRARY" to "The app needs a system library that ordinary apps are not given. Apps built into the phone often do.",
        "INSTALL_FAILED_OLDER_SDK" to "The app needs a newer version of Android than this phone has.",
        "INSTALL_FAILED_NEWER_SDK" to "The app was built for an older Android than this phone's.",
        "INSTALL_FAILED_CPU_ABI_INCOMPATIBLE" to "The app's native code does not match this phone's processor.",
        "INSTALL_FAILED_NO_MATCHING_ABIS" to "The app's native code does not match this phone's processor.",
        "INSTALL_FAILED_MISSING_FEATURE" to "The app needs a hardware or software feature this phone does not have.",
        "INSTALL_FAILED_TEST_ONLY" to "The app is marked as a test build, which Android will not install this way.",
        "INSTALL_FAILED_INVALID_APK" to "Android considers the file damaged or malformed.",
        "INSTALL_PARSE_FAILED_NO_CERTIFICATES" to "Android could not verify the signature.",
        "INSTALL_PARSE_FAILED_MANIFEST_MALFORMED" to "Android could not read the app's manifest.",
        "INSTALL_FAILED_INSUFFICIENT_STORAGE" to "There is not enough free storage.",
        "INSTALL_FAILED_VERIFICATION_FAILURE" to "Android's security check rejected the app.",
    )

    fun explain(status: Int, message: String?): String {
        val token = token(message)
        val cause = token?.let { explanations[it] }
        val head = when (status) {
            2 -> "Android blocked the install (for example Play Protect or a phone security setting)."
            3 -> "The install was cancelled."
            4 -> "Android considers the file invalid."
            5 -> "Android refused to install the copy because it conflicts with something already installed."
            6 -> "The install failed because of storage."
            7 -> "Android refused to install the copy because it is not compatible with this phone."
            else -> "The install failed."
        }
        val said = if (message.isNullOrBlank()) "Android gave no detail." else "Android said: $message"
        return listOfNotNull(head, cause, said, "(status $status)").joinToString(" ")
    }
}
