package com.maggie.app.ui.screens.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.HomeDay
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.tap
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.bilan.BilanSession
import com.maggie.app.ui.screens.bilan.BilanStep
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** « Reprendre » the Bilan on the Accueil (MAG-751). */
@RunWith(AndroidJUnit4::class)
class BilanHomeCardTest {

    @get:Rule
    val compose = ScreenRule()

    private val session = BilanSession(LocalDate.of(2026, 11, 8), BilanStep.FINANCE, JsonObject(emptyMap()))

    @Test
    fun `an open bilan offers Reprendre at its step`() {
        var resumed = 0
        compose.setContent { BilanHomeCardContent(HomeDay.TODAY, session) { resumed++ } }

        compose.onNodeWithText("Étape Finance", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.HOME_BILAN_RESUME).assertIsDisplayed()
        compose.onNodeWithText("Reprendre").tap()
        assertEquals(1, resumed)
    }

    @Test
    fun `no bilan in progress, nothing is drawn`() {
        compose.setContent { BilanHomeCardContent(HomeDay.TODAY, null) {} }

        compose.onNodeWithTag(UiTags.HOME_BILAN_RESUME).assertDoesNotExist()
    }

    @Test
    fun `another day than today draws nothing`() {
        compose.setContent { BilanHomeCardContent(HomeDay.TOMORROW, session) {} }

        compose.onNodeWithTag(UiTags.HOME_BILAN_RESUME).assertDoesNotExist()
    }
}
