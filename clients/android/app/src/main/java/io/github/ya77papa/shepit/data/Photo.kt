package io.github.ya77papa.shepit.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Підготовка фото до надсилання — як у веб-версії: найбільша сторона до 1600 px, JPEG до ~600 КБ.
 * Bitmap.compress пише чистий JPEG — EXIF (геолокація, модель телефону, час) не потрапляє.
 */
object Photo {
    const val MAX_SIDE = 1600
    const val MAX_BYTES = 600 * 1024

    class Prepared(val jpeg: ByteArray, val w: Int, val h: Int)

    suspend fun prepare(ctx: Context, uri: Uri): Prepared = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("Не вдалося відкрити фото")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val src = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Не вдалося відкрити фото")
        val degrees = runCatching {
            cr.openInputStream(uri)?.use { android.media.ExifInterface(it) }?.let { e ->
                when (e.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        try {
            var side = MAX_SIDE
            while (true) {
                val bmp = render(src, degrees, side)
                try {
                    for (q in intArrayOf(82, 72, 60, 50)) {
                        val out = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
                        if (out.size() <= MAX_BYTES) return@withContext Prepared(out.toByteArray(), bmp.width, bmp.height)
                    }
                } finally {
                    bmp.recycle()
                }
                side = side * 3 / 4
                if (side < 320) error("Фото надто велике")
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } finally {
            src.recycle()
        }
    }

    /** Повернути за EXIF, вписати в side×side, прозорість → білий фон. */
    private fun render(src: Bitmap, degrees: Float, side: Int): Bitmap {
        val rw = if (degrees == 90f || degrees == 270f) src.height else src.width
        val rh = if (degrees == 90f || degrees == 270f) src.width else src.height
        val k = minOf(1f, side.toFloat() / maxOf(rw, rh))
        val w = maxOf(1, Math.round(rw * k))
        val h = maxOf(1, Math.round(rh * k))
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val m = Matrix().apply {
            postTranslate(-src.width / 2f, -src.height / 2f)
            postRotate(degrees)
            postScale(k, k)
            postTranslate(w / 2f, h / 2f)
        }
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        }
        return out
    }

    /** Прев'ю для стрічки: декодувати зменшено (економія пам'яті). */
    fun decode(bytes: ByteArray, maxSide: Int): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
        var sample = 1
        while (maxOf(b.outWidth, b.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
