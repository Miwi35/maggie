package com.maggie.app.ui.screens.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.onNodeWithText
import com.maggie.app.data.model.HealthHomeBalance
import com.maggie.app.data.model.HealthHomeFast
import com.maggie.app.data.model.HealthHomeState
import com.maggie.app.data.model.HealthHomeSteps
import com.maggie.app.data.model.HealthHomeWorkout
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Santé card (MAG-566): the labels of the mockup, today and tomorrow, and nothing for what the day does not have. */
@RunWith(AndroidJUnit4::class)
class HealthHomeCardScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val today = HealthHomeState(
        today = true,
        workout = HealthHomeWorkout("W1", "Course", "18:30", 45),
        steps = HealthHomeSteps(6240, 8000),
        balance = HealthHomeBalance(1240, 1980),
        fast = HealthHomeFast("open", "12:00", "20:00", 198),
    )

    private fun draw(ui: HealthHomeUiState, onOpen: () -> Unit = {}, onRetry: () -> Unit = {}) =
        compose.setContent { HealthHomeCardContent(ui, onOpen, onRetry) }

    @Test
    fun `today reads the mockup's labels`() {
        draw(HealthHomeUiState(today, isLoading = false))

        compose.onNodeWithTag(UiTags.HOME_HEALTH_WORKOUT).assertTextEquals("Course · 18 h 30 · 45 min")
        compose.onNodeWithTag(UiTags.HOME_HEALTH_STEPS).assertTextContains("6 240 · objectif 8 000")
        compose.onNodeWithTag(UiTags.HOME_HEALTH_BALANCE).assertTextContains("1 240 mangées · besoin 1 980 kcal")
        compose.onNodeWithTag(UiTags.HOME_HEALTH_FAST).assertTextContains("ferme à 20:00 · 3 h 18")
    }

    @Test
    fun `a session on the hour reads 18 h`() {
        draw(HealthHomeUiState(today.copy(workout = HealthHomeWorkout("W1", "Course", "18:00", 45)), isLoading = false))

        compose.onNodeWithTag(UiTags.HOME_HEALTH_WORKOUT).assertTextEquals("Course · 18 h · 45 min")
    }

    @Test
    fun `the bars - steps and balance full, the window soft, tomorrow's balance soft and the planned window a segment`() {
        assertEquals(HealthBarShape.Share(6240f / 8000, soft = false), stepsBar(HealthHomeSteps(6240, 8000)))
        assertEquals(HealthBarShape.Share(1240f / 1980, soft = false), balanceBar(HealthHomeBalance(1240, 1980), today = true))
        assertEquals(HealthBarShape.Share(1800f / 1980, soft = true), balanceBar(HealthHomeBalance(1800, 1980), today = false))
        assertEquals(null, balanceBar(HealthHomeBalance(1240, null), today = true))
        assertEquals(HealthBarShape.Share(0.4f, soft = true), fastBar(HealthHomeFast("open", "12:00", "20:00", 198, 0.4f), today = true))
        assertEquals(null, fastBar(HealthHomeFast("closed", "12:00", "20:00", null), today = true))
        val segment = fastBar(HealthHomeFast(null, "12:00", "20:00", null), today = false) as HealthBarShape.Segment
        assertEquals(0.5f, segment.start, 0.001f)
        assertEquals(1f / 3f, segment.length, 0.001f)
    }

    @Test
    fun `tomorrow reads the planned window and the projected calories, with no steps`() {
        draw(HealthHomeUiState(today.copy(today = false, steps = null, balance = HealthHomeBalance(1800, 1980), fast = today.fast?.copy(minutesToEdge = null)), isLoading = false))

        compose.onNodeWithTag(UiTags.HOME_HEALTH_FAST).assertTextContains("12:00 – 20:00")
        compose.onNodeWithTag(UiTags.HOME_HEALTH_BALANCE).assertTextContains("Calories projetées", substring = true)
        compose.onNodeWithTag(UiTags.HOME_HEALTH_STEPS).assertDoesNotExist()
    }

    @Test
    fun `without a window there is no fast line`() {
        draw(HealthHomeUiState(today.copy(fast = null), isLoading = false))

        compose.onNodeWithTag(UiTags.HOME_HEALTH_FAST).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.HOME_HEALTH_WORKOUT).assertIsDisplayed()
    }

    @Test
    fun `a day with nothing draws no card`() {
        draw(HealthHomeUiState(HealthHomeState(), isLoading = false))

        compose.onNodeWithTag(UiTags.homeCard(HomeModules.HEALTH)).assertDoesNotExist()
    }

    @Test
    fun `a failed read says so and offers to try again`() {
        var retried = 0
        draw(HealthHomeUiState(isLoading = false, failed = true), onRetry = { retried++ })

        compose.onNodeWithText("Impossible de lire la santé.").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.HOME_HEALTH_RETRY).performClick()
        assertEquals(1, retried)
    }

    @Test
    fun `the header opens Santé`() {
        var opened = 0
        draw(HealthHomeUiState(today, isLoading = false), onOpen = { opened++ })

        compose.onNodeWithTag(UiTags.homeCardHeader(HomeModules.HEALTH)).performClick()
        assertEquals(1, opened)
    }
}
