package com.maggie.app.ui.activity

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** The halo of the mockups (MAG-617), as numbers. */
class ActivityHaloTest {

    private val cuisine = Color(0xFF8FB8FF)

    private fun assertOffset(x: Float, y: Float, actual: Offset) {
        assertEquals(x, actual.x, 0.01f)
        assertEquals(y, actual.y, 0.01f)
    }

    @Test
    fun `the frame's halo is the tablet mockup's, 900 by 640 at 78 and 30 percent`() {
        val (center, radiusX, radiusY) = ActivityHalo.Frame.ellipse(Size(1280f, 800f))

        assertOffset(998.4f, 240f, center)
        assertEquals(900f, radiusX, 0.01f)
        assertEquals(640f, radiusY, 0.01f)
    }

    @Test
    fun `the review's halo is the finance mockup's, 900 by 600 at 85 and 30 percent`() {
        val (center, radiusX, radiusY) = ActivityHalo.Review.ellipse(Size(1280f, 800f))

        assertOffset(1088f, 240f, center)
        assertEquals(900f, radiusX, 0.01f)
        assertEquals(600f, radiusY, 0.01f)
    }

    @Test
    fun `a phone gets the same halo in proportion`() {
        val (center, radiusX, radiusY) = ActivityHalo.Frame.ellipse(Size(400f, 800f))

        assertOffset(312f, 240f, center)
        assertEquals(281.25f, radiusX, 0.01f)
        assertEquals(640f, radiusY, 0.01f)
    }

    @Test
    fun `it is the activity's colour at 16 percent, fading to the same colour at zero`() {
        val stops = ActivityHalo.Frame.stops(cuisine)

        assertEquals(listOf(0f, 0.62f), stops.map { it.first })
        assertEquals(cuisine.copy(alpha = 0.16f), stops[0].second)
        assertEquals(cuisine.copy(alpha = 0f), stops[1].second)
    }

    @Test
    fun `the mint review halo is at 13 percent`() {
        val stops = ActivityHalo.Review.stops(Color(0xFF7FD1AE))

        assertEquals(Color(0xFF7FD1AE).copy(alpha = 0.13f), stops[0].second)
        assertEquals(0.60f, stops[1].first, 0f)
    }
}
