package com.amandhakar.apkforge.build

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import java.io.ByteArrayOutputStream

/**
 * Turns any picture the user picked into exactly what an app file needs: the same format and the same
 * pixel size as the file being replaced, so nothing in the app's layout changes. The picture is
 * centre-cropped to the right shape (not stretched). Returns null when the picture cannot be read.
 */
object ImageConverter {
    private const val MAX_SIDE = 4096

    /** Pixel size of an encoded image, or null when it cannot be decoded. */
    fun size(bytes: ByteArray): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    fun convert(source: ByteArray, kind: ImageKind, width: Int, height: Int): ByteArray? {
        if (kind != ImageKind.PNG && kind != ImageKind.JPEG && kind != ImageKind.WEBP) return null
        if (width !in 1..MAX_SIDE || height !in 1..MAX_SIDE) return null
        val (sw, sh) = size(source) ?: return null
        var sample = 1
        while (sw / (sample * 2) >= width && sh / (sample * 2) >= height) sample *= 2 // decode no bigger than needed
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        var cropped: Bitmap? = null
        var scaled: Bitmap? = null
        var flat: Bitmap? = null
        try {
            // centre-crop to the target shape
            val targetRatio = width.toFloat() / height
            val ratio = decoded.width.toFloat() / decoded.height
            val cw = if (ratio > targetRatio) (decoded.height * targetRatio).toInt().coerceAtLeast(1) else decoded.width
            val ch = if (ratio > targetRatio) decoded.height else (decoded.width / targetRatio).toInt().coerceAtLeast(1)
            cropped = Bitmap.createBitmap(decoded, (decoded.width - cw) / 2, (decoded.height - ch) / 2, cw, ch)
            scaled = Bitmap.createScaledBitmap(cropped, width, height, true)
            var toWrite = scaled
            if (kind == ImageKind.JPEG) { // JPEG has no transparency: put the picture on white
                flat = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(scaled, 0f, 0f, null) }
                toWrite = flat
            }
            val out = ByteArrayOutputStream()
            val ok = when (kind) {
                ImageKind.PNG -> toWrite.compress(Bitmap.CompressFormat.PNG, 100, out)
                ImageKind.JPEG -> toWrite.compress(Bitmap.CompressFormat.JPEG, 95, out)
                else -> if (Build.VERSION.SDK_INT >= 30) toWrite.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
                else @Suppress("DEPRECATION") toWrite.compress(Bitmap.CompressFormat.WEBP, 100, out)
            }
            return if (ok) out.toByteArray() else null
        } finally {
            if (cropped !== decoded) cropped?.recycle()
            if (scaled !== cropped) scaled?.recycle()
            flat?.recycle()
            decoded.recycle()
        }
    }
}
