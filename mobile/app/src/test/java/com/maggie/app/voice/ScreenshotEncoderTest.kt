package com.maggie.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenshotEncoderTest {

    @Test
    fun `a portrait screen is brought down to 1568 px on its height`() {
        assertEquals(706 to 1568, ScreenshotEncoder.targetSize(1080, 2400))
    }

    @Test
    fun `a landscape screen is brought down to 1568 px on its width`() {
        assertEquals(1568 to 706, ScreenshotEncoder.targetSize(2400, 1080))
    }

    @Test
    fun `a screen already small enough is never upscaled`() {
        assertEquals(720 to 1280, ScreenshotEncoder.targetSize(720, 1280))
        assertEquals(1568 to 1000, ScreenshotEncoder.targetSize(1568, 1000))
    }

    @Test
    fun `a thumbnail is subsampled by powers of two without going under the height asked`() {
        assertEquals(2, ScreenshotEncoder.sampleSize(height = 1568, maxHeight = 480))
        assertEquals(1, ScreenshotEncoder.sampleSize(height = 300, maxHeight = 480))
        assertEquals(4, ScreenshotEncoder.sampleSize(height = 2000, maxHeight = 480))
    }
}
