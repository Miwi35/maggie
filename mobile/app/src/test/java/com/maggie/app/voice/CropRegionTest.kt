package com.maggie.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CropRegionTest {

    private fun around(minX: Float, minY: Float, maxX: Float, maxY: Float) =
        CropRegion.around(minX, minY, maxX, maxY, width = 200f, height = 400f)

    @Test
    fun `the region is the rectangle around the stroke, as fractions of the image`() {
        assertEquals(CropRegion(0.25f, 0.25f, 0.75f, 0.5f), around(50f, 100f, 150f, 200f))
    }

    @Test
    fun `a stroke running off the image is held to its edges`() {
        assertEquals(CropRegion(0f, 0.5f, 1f, 1f), around(-30f, 200f, 260f, 480f))
    }

    @Test
    fun `a tap is not a selection`() {
        assertNull(around(100f, 200f, 101f, 201f))
    }

    @Test
    fun `a straight line selects a strip of the minimum width`() {
        val region = around(100f, 80f, 100f, 320f)!!

        assertEquals(CropRegion.MIN_SPAN, region.right - region.left, 0.0001f)
        assertEquals(0.5f - CropRegion.MIN_SPAN / 2, region.left, 0.0001f)
        assertEquals(0.2f, region.top, 0.0001f)
        assertEquals(0.8f, region.bottom, 0.0001f)
    }

    @Test
    fun `a small loop in a corner keeps the minimum size inside the image`() {
        val region = around(0f, 0f, 10f, 20f)!!

        assertEquals(CropRegion(0f, 0f, CropRegion.MIN_SPAN, CropRegion.MIN_SPAN), region)
    }

    @Test
    fun `an image with no size selects nothing`() {
        assertNull(CropRegion.around(0f, 0f, 10f, 10f, width = 0f, height = 0f))
    }

    @Test
    fun `the region in pixels is inside the image and never empty`() {
        assertEquals(PixelRect(50, 120, 100, 80), CropRegion(0.25f, 0.3f, 0.75f, 0.5f).toPixels(200, 400))
        assertEquals(PixelRect(0, 0, 200, 400), CropRegion(0f, 0f, 1f, 1f).toPixels(200, 400))
        assertEquals(PixelRect(199, 399, 1, 1), CropRegion(1f, 1f, 1f, 1f).toPixels(200, 400))
    }
}
