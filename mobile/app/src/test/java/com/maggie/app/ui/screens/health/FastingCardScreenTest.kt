package com.maggie.app.ui.screens.health

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.FastingProtocol
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.theme.MaggieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The protocol button of « Jeûne » wears its chevron, « 16:8 ▾ » (MAG-770, recette of 11 Nov.: on the owner's
 * 360 dp S24, « Fenêtre ouverte » took the row and squeezed the chevron to nothing). Native graphics: the legacy
 * mode measures one pixel per character, so the title would never crowd the button.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FastingCardScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private fun assertChevronWhole(view: FastingView) {
        // The card as Santé › Nutrition lays it out: 16 dp from each edge of the phone.
        compose.setContent { MaggieTheme { FastingCard(view, onOpenSheet = {}, modifier = Modifier.padding(horizontal = 16.dp)) } }

        val card = compose.onNodeWithTag(UiTags.FASTING_CARD).getUnclippedBoundsInRoot()
        val chevron = compose.onNodeWithTag(UiTags.FASTING_CHEVRON, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals("chevron width", 16f, (chevron.right - chevron.left).value, 0.5f)
        assertTrue("chevron $chevron overflows the card $card", chevron.right <= card.right)
        compose.onNodeWithTag(UiTags.FASTING_STATE).assertIsDisplayed()
    }

    @Test
    fun `an open window keeps the 16 dp chevron after the protocol`() {
        assertChevronWhole(FastingView(FastingProtocol.P16_8, open = true, 0.7f, 140, "12 h 10 → 20 h 00", "Jeûne de la nuit · 16 h 05"))
    }

    @Test
    fun `a fast keeps the 16 dp chevron after the longest protocol`() {
        assertChevronWhole(FastingView(FastingProtocol.P14_10, open = false, 0.4f, 475, "12 h 00 → 22 h 00", null))
    }
}
