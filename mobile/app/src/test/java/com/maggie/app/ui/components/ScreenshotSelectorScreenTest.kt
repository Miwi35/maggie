package com.maggie.app.ui.components

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.voice.CropRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Circling a zone of the assistant's screenshot (MAG-316). */
@RunWith(AndroidJUnit4::class)
class ScreenshotSelectorScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val image = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888).asImageBitmap()

    private fun show(region: CropRegion? = null, picked: MutableList<CropRegion?>) = compose.setContent {
        ScreenshotSelector(image, region, onSelect = { picked += it })
    }

    @Test
    fun `a stroke across the image picks the zone around it`() {
        val picked = mutableListOf<CropRegion?>()
        show(picked = picked)

        compose.onNodeWithTag(UiTags.ASSIST_SCREENSHOT).performTouchInput {
            swipe(Offset(width * 0.25f, height * 0.25f), Offset(width * 0.75f, height * 0.5f), durationMillis = 200)
        }

        compose.waitForIdle()
        assertEquals(1, picked.size)
        val zone = picked.single()!!
        // The first points of a drag go to the touch slop: the zone is inside the stroke, never outside it.
        assertTrue("left ${zone.left}", zone.left >= 0.24f)
        assertTrue("right ${zone.right}", zone.right <= 0.76f)
        assertTrue("top ${zone.top}", zone.top >= 0.24f)
        assertTrue("bottom ${zone.bottom}", zone.bottom <= 0.51f)
        assertTrue("width ${zone.right - zone.left}", zone.right - zone.left >= CropRegion.MIN_SPAN)
    }

    @Test
    fun `a tap picks nothing`() {
        val picked = mutableListOf<CropRegion?>()
        show(picked = picked)

        compose.onNodeWithTag(UiTags.ASSIST_SCREENSHOT).performClick()

        compose.waitForIdle()
        assertTrue(picked.isEmpty())
    }

    @Test
    fun `without a zone the screen is the default, and the hint says how to circle`() {
        show(picked = mutableListOf())

        compose.onNodeWithText("Entourez une zone pour ne l'envoyer qu'elle").assertExists()
        compose.onNodeWithTag(UiTags.ASSIST_SCREENSHOT_RESET).assertDoesNotExist()
    }

    @Test
    fun `a zone can be dropped to send the whole screen again`() {
        val picked = mutableListOf<CropRegion?>()
        show(region = CropRegion(0.1f, 0.1f, 0.6f, 0.6f), picked = picked)

        compose.onNodeWithTag(UiTags.ASSIST_SCREENSHOT_RESET).performClick()

        assertEquals(listOf<CropRegion?>(null), picked)
    }
}
