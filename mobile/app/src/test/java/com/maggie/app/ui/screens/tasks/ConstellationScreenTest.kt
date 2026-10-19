package com.maggie.app.ui.screens.tasks

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Reward
import com.maggie.app.data.reward.Celebration
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.maggie.app.data.model.Stars

/** The constellation and the palier banner (MAG-509), drawn from plain state. */
@RunWith(AndroidJUnit4::class)
class ConstellationScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private fun stars(count: Int, month: String = "2026-10") = Stars(month = month, count = count, total = count + 7)

    private fun drawn() = compose.onAllNodesWithTag(UiTags.CONSTELLATION_STAR, useUnmergedTree = true).fetchSemanticsNodes().size +
        compose.onAllNodesWithTag(UiTags.CONSTELLATION_LATEST, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun `only earned stars are drawn, the latest apart, and no empty place`() {
        compose.setContent { Constellation(stars = stars(4), animate = true) }

        compose.onNodeWithTag(UiTags.CONSTELLATION_TITLE).assertTextEquals("Ta constellation d'octobre · 4 étoiles")
        assertEquals(4, drawn())
        assertEquals(1, compose.onAllNodesWithTag(UiTags.CONSTELLATION_LATEST, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `a month with no star draws none`() {
        compose.setContent { Constellation(stars = stars(0), animate = false) }

        assertEquals(0, drawn())
        compose.onNodeWithTag(UiTags.CONSTELLATION_TITLE).assertTextEquals("Ta constellation d'octobre · 0 étoile")
    }

    @Test
    fun `past a hundred, the last hundred are drawn and the rest is a plus n`() {
        compose.setContent { Constellation(stars = stars(137), animate = false) }

        assertEquals(100, drawn())
        compose.onNodeWithTag(UiTags.CONSTELLATION_MORE).assertTextEquals("+37")
    }

    @Test
    fun `the total since always sits under the grid when asked for`() {
        compose.setContent { Constellation(stars = stars(4), animate = false, showTotal = true) }

        compose.onNodeWithTag(UiTags.CONSTELLATION_TOTAL).assertIsDisplayed()
    }

    @Test
    fun `without arrows there is no navigation, with them the next month is out of reach on the current one`() {
        var previous = 0
        var next = 0
        compose.setContent {
            Constellation(stars = stars(4), animate = false, onPrevious = { previous++ }, onNext = null)
        }
        compose.onNodeWithTag(UiTags.CONSTELLATION_PREVIOUS).assertIsEnabled().performClick()
        compose.onNodeWithTag(UiTags.CONSTELLATION_NEXT).assertIsNotEnabled()
        assertEquals(1, previous)
        assertEquals(0, next)
    }

    @Test
    fun `the arrows ask for the month before and the month after`() {
        var previous = 0
        var next = 0
        compose.setContent {
            Constellation(stars = stars(7, "2026-09"), animate = false, onPrevious = { previous++ }, onNext = { next++ })
        }

        compose.onNodeWithTag(UiTags.CONSTELLATION_NEXT).assertIsEnabled().performClick()
        compose.onNodeWithTag(UiTags.CONSTELLATION_PREVIOUS).performClick()

        assertEquals(1, next)
        assertEquals(1, previous)
    }

    @Test
    fun `a constellation without arrows shows neither`() {
        compose.setContent { Constellation(stars = stars(4), animate = false) }

        compose.onNodeWithTag(UiTags.CONSTELLATION_PREVIOUS).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.CONSTELLATION_NEXT).assertDoesNotExist()
    }

    @Test
    fun `the palier banner shows the chapter's title and message until dismissed`() {
        val palier = Reward(
            id = "rw9",
            subjectType = "saga_chapter",
            subjectId = "c1",
            level = "palier",
            messageKey = "palier-doux.1",
            message = "Un chapitre de bouclé.",
            title = "Chapitre 1",
            star = false,
            earnedAt = "2026-10-15T20:00:00+00:00",
        )
        var dismissed = 0
        compose.setContent { PalierBanner(celebration = Celebration(palier, animate = false), animate = false, onDismiss = { dismissed++ }) }

        compose.onNodeWithTag(UiTags.PALIER_TITLE, useUnmergedTree = true).assertTextEquals("Chapitre 1")
        compose.onNodeWithTag(UiTags.PALIER_MESSAGE, useUnmergedTree = true).assertTextEquals("Un chapitre de bouclé.")
        compose.onNodeWithTag(UiTags.PALIER_DISMISS).performClick()

        assertEquals(1, dismissed)
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExist() = assertDoesNotExist()
