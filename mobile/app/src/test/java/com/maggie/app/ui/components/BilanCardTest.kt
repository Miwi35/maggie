package com.maggie.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.BilanBlock
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BilanCardTest {

    @get:Rule
    val compose = ScreenRule()

    private val block = BilanBlock(day = "2026-11-12", route = "maggie://activity/bilan")

    @Test
    fun `the card reads its title and offers Ouvrir`() {
        compose.setContent { BilanCard(block, onOpen = {}) }

        compose.onNodeWithTag(UiTags.CHAT_BILAN_CARD).assertIsDisplayed()
        compose.onNodeWithText("Bilan de la journée").assertIsDisplayed()
        compose.onNodeWithText("Ouvrir").assertIsDisplayed()
    }

    @Test
    fun `Ouvrir opens the activity`() {
        var opened = 0
        compose.setContent { BilanCard(block, onOpen = { opened++ }) }

        compose.onNodeWithTag(UiTags.CHAT_BILAN_CARD_OPEN).performClick()

        assertEquals(1, opened)
    }
}
