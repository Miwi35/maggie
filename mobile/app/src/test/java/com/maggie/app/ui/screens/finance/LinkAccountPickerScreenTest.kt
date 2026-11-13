package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.LinkCandidate
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LinkAccountPickerScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val courant = LinkCandidate(id = "b1", name = "Compte courant", bank = "N26")
    private val livret = LinkCandidate(id = "b2", name = "Livret", bank = "N26")
    private var picked: LinkCandidate? = null

    private fun show() {
        compose.setContent { LinkAccountPickerScreen(candidates = listOf(courant, livret), onPick = { picked = it }, onBack = {}) }
        compose.waitForIdle()
    }

    @Test
    fun `filters by name`() {
        show()

        compose.onNodeWithTag(UiTags.ACCOUNT_LINK_SEARCH).performTextInput("livr")

        compose.onNodeWithText("Livret").assertIsDisplayed()
        compose.onNodeWithText("Compte courant").assertDoesNotExist()
    }

    @Test
    fun `picking an account calls back`() {
        show()

        compose.onNodeWithText("Compte courant").performClick()

        assertEquals(courant, picked)
    }
}
