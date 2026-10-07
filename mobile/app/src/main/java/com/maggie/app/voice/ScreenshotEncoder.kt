package com.maggie.app.voice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The assist screenshot as the model gets it (MAG-214): its long side brought down
 * to [MAX_LONG_SIDE] — the size above which Claude downscales anyway, so more is
 * bytes nobody sees — and JPEG [QUALITY], ~150–300 KB instead of a 2–4 MB PNG.
 */
object ScreenshotEncoder {
    const val MAX_LONG_SIDE = 1568
    const val QUALITY = 80

    /**
     * Where the session leaves the screenshot for the overlay. One file, overwritten
     * by each invocation: a screen the user did not send is never kept past the next.
     */
    fun file(context: Context): File = File(context.cacheDir, "assist/screenshot.jpg")

    /** The size to encode at: never upscaled, aspect ratio kept. */
    fun targetSize(width: Int, height: Int, maxLongSide: Int = MAX_LONG_SIDE): Pair<Int, Int> {
        val longSide = maxOf(width, height)
        if (longSide <= maxLongSide) return width to height
        val scale = maxLongSide.toDouble() / longSide
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }

    fun encode(bitmap: Bitmap): ByteArray {
        // A hardware bitmap cannot be drawn into a software one nor always compressed.
        val source = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        val (width, height) = targetSize(source.width, source.height)
        val scaled = if (width == source.width && height == source.height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, width, height, true)
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        if (scaled !== bitmap) scaled.recycle()
        if (source !== bitmap && source !== scaled) source.recycle()
        return out.toByteArray()
    }

    /** The largest power-of-two subsampling that keeps the height at least [maxHeight]. */
    fun sampleSize(height: Int, maxHeight: Int): Int {
        var sample = 1
        while (height / (sample * 2) >= maxHeight) sample *= 2
        return sample
    }

    /** A thumbnail-sized decode: a bubble does not need the 1568 px original in memory. */
    fun decodeThumbnail(bytes: ByteArray, maxHeight: Int = THUMBNAIL_HEIGHT_PX): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outHeight, maxHeight) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private const val THUMBNAIL_HEIGHT_PX = 480
}
