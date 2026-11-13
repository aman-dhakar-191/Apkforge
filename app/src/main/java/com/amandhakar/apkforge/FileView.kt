package com.amandhakar.apkforge

import android.graphics.Bitmap

/** What opening a file inside the APK shows. */
sealed interface FileView {
    val bytes: Long

    class Image(val bitmap: Bitmap, val width: Int, val height: Int, val format: String, override val bytes: Long) : FileView
    /** [compiled] is true for Android's binary XML (shown decoded); false for XML that was already plain text. */
    class Xml(val text: String, val compiled: Boolean, override val bytes: Long) : FileView
    class Text(val text: String, val truncated: Boolean, override val bytes: Long) : FileView
    class Binary(val hex: String, val note: String?, override val bytes: Long) : FileView
    class Failed(val reason: String) : FileView { override val bytes: Long = 0 }
}

class ViewerFile(val label: String, val entry: String)

/** Something to open in the viewer: one file, or a resource with a file per screen density. */
class ViewerTarget(val title: String, val files: List<ViewerFile>)
