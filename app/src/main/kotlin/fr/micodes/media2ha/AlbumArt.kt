package fr.micodes.media2ha

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Encodes artwork as base64 JPEG, downscaled to keep MQTT payloads small. */
object AlbumArt {
    private const val MAX_EDGE = 512
    private const val QUALITY = 80

    fun encodeToBase64(bitmap: Bitmap?): String? {
        if (bitmap == null || bitmap.isRecycled) return null
        val scaled = scaleDown(bitmap, MAX_EDGE)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        if (scaled !== bitmap) scaled.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun hash(base64: String?): String? {
        if (base64 == null) return null
        val digest = MessageDigest.getInstance("MD5").digest(base64.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun scaleDown(source: Bitmap, maxEdge: Int): Bitmap {
        val width = source.width
        val height = source.height
        val largest = maxOf(width, height)
        if (largest <= maxEdge || largest == 0) return source
        val ratio = maxEdge.toFloat() / largest
        val targetWidth = (width * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }
}
