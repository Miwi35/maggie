package com.maggie.app.voice

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/** The zone the user circled is cut out of the screenshot, and is what gets encoded (MAG-316). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotCropTest {

    private fun jpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Left half red, right half blue: which half came out says which zone was cut.
        for (x in 0 until width) for (y in 0 until height) bitmap.setPixel(x, y, if (x < width / 2) Color.RED else Color.BLUE)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray): Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    @Test
    fun `only the circled zone comes out`() {
        val cut = ScreenshotEncoder.crop(jpeg(200, 300), CropRegion(0.5f, 0f, 1f, 0.5f))!!

        val image = decode(cut)
        assertEquals(100, image.width)
        assertEquals(150, image.height)
        // The right half of the screen was blue.
        assertEquals(true, Color.blue(image.getPixel(50, 75)) > 200)
        assertEquals(true, Color.red(image.getPixel(50, 75)) < 60)
    }

    @Test
    fun `the whole region gives the whole image back`() {
        val image = decode(ScreenshotEncoder.crop(jpeg(200, 300), CropRegion(0f, 0f, 1f, 1f))!!)

        assertEquals(200, image.width)
        assertEquals(300, image.height)
    }

    @Test
    fun `a zone is encoded as a JPEG`() {
        val cut = ScreenshotEncoder.crop(jpeg(200, 300), CropRegion(0.1f, 0.1f, 0.6f, 0.6f))!!

        assertNotNull(decode(cut))
        // JPEG magic number.
        assertEquals(0xFF, cut[0].toInt() and 0xFF)
        assertEquals(0xD8, cut[1].toInt() and 0xFF)
    }

    @Test
    fun `bytes that are not an image cut nothing`() {
        assertNull(ScreenshotEncoder.crop(byteArrayOf(1, 2, 3), CropRegion(0f, 0f, 0.5f, 0.5f)))
    }
}
