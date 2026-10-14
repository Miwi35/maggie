package com.maggie.app.voice

/** A rectangle in pixels of the image it was computed for. */
data class PixelRect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * The zone of the screenshot the user circled before asking (MAG-316), as fractions of
 * the image (0..1): the overlay shows the image at another size than the file's, so the
 * selection travels independent of both.
 *
 * It is the bounding rectangle of the stroke — the exact free-hand outline is out of
 * scope — grown to at least [MIN_SPAN] on each side, so a line or a tiny loop still
 * selects something one can read.
 */
data class CropRegion(val left: Float, val top: Float, val right: Float, val bottom: Float) {

    fun toPixels(width: Int, height: Int): PixelRect {
        val x = (left * width).toInt().coerceIn(0, width - 1)
        val y = (top * height).toInt().coerceIn(0, height - 1)
        val endX = Math.round(right * width).coerceIn(x + 1, width)
        val endY = Math.round(bottom * height).coerceIn(y + 1, height)
        return PixelRect(x, y, endX - x, endY - y)
    }

    companion object {
        const val MIN_SPAN = 0.15f

        /** Below this on both axes a stroke is a tap, not a selection. */
        const val TAP_SPAN = 0.02f

        /**
         * The region around a stroke whose extremes are given in pixels of an image
         * [width] × [height]. Null for a tap, or an image with no size.
         */
        fun around(minX: Float, minY: Float, maxX: Float, maxY: Float, width: Float, height: Float): CropRegion? {
            if (width <= 0f || height <= 0f) return null
            val left = (minX / width).coerceIn(0f, 1f)
            val right = (maxX / width).coerceIn(0f, 1f)
            val top = (minY / height).coerceIn(0f, 1f)
            val bottom = (maxY / height).coerceIn(0f, 1f)
            if (right - left < TAP_SPAN && bottom - top < TAP_SPAN) return null
            val (x0, x1) = grown(left, right)
            val (y0, y1) = grown(top, bottom)
            return CropRegion(x0, y0, x1, y1)
        }

        private fun grown(start: Float, end: Float): Pair<Float, Float> {
            if (end - start >= MIN_SPAN) return start to end
            val from = ((start + end) / 2f - MIN_SPAN / 2f).coerceIn(0f, 1f - MIN_SPAN)
            return from to from + MIN_SPAN
        }
    }
}
