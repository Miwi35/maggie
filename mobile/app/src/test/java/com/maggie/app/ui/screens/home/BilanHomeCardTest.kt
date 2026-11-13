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
import com.maggie.app.ui.screens.bilan.BilanTile
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** The Bilan tile on the Accueil (MAG-751, MAG-826). */
@RunWith(AndroidJUnit4::class)
class BilanHomeCardTest {

    @get:Rule
    val compose = ScreenRule()

    private val session = BilanSession(LocalDate.of(2026, 11, 8), BilanStep.FINANCE, JsonObject(emptyMap()))

    private val started = BilanTile(session)
    private val notStarted = BilanTile(null)

    @Test
    fun `showsResumeOnceStarted`() {
        var opened = 0
        compose.setContent { BilanHomeCardContent(HomeDay.TODAY, started) { opened++ } }

        compose.onNodeWithText("Étape Finance", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.HOME_BILAN_RESUME).assertIsDisplayed()
        compose.onNodeWithText("Reprendre").tap()
        assertEquals(1, opened)
    }

    @Test
    fun `showsOpenFromTheReviewTime`() {
        var opened = 0
        compose.setContent { BilanHomeCardContent(HomeDay.TODAY, notStarted) { opened++ } }

        compose.onNodeWithTag(UiTags.HOME_BILAN_RESUME).assertIsDisplayed()
        compose.onNodeWithText("Reprendre").assertDoesNotExist()
        compose.onNodeWithText("Ouvrir").tap()
        assertEquals(1, opened)
    }

    @Test
    fun `no tile, nothing is drawn`() {
        compose.setContent { BilanHomeCardContent(HomeDay.TODAY, null) {} }

        compose.onNodeWithTag(UiTags.HOME_BILAN_RESUME).assertDoesNotExist()
    }

    @Test
    fun `another day than today draws nothing`() {
        compose.setContent { BilanHomeCardContent(HomeDay.TOMORROW, notStarted) {} }

        compose.onNodeWithTag(UiTags.HOME_BILAN_RESUME).assertDoesNotExist()
    }
}
