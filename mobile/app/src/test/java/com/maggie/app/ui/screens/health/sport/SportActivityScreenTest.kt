package com.maggie.app.ui.screens.health.sport

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Workout
import com.maggie.app.data.model.WorkoutExercise
import com.maggie.app.data.model.WorkoutSource
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.activity.ActivityImmersion
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The labels of the Sport activity (MAG-779). */
@RunWith(AndroidJUnit4::class)
class SportActivityScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private object NoImmersion : ActivityImmersion {
        override fun enter() = Unit

        override fun exit() = Unit
    }

    private val log = mutableListOf<String>()

    private val workout = Workout(
        id = "w1",
        type = "strength",
        title = "Haut du corps",
        startedAt = "2026-11-11T18:30:00+01:00",
        durationMinutes = 30,
        source = WorkoutSource.PLANNED,
        exercises = List(6) {
            WorkoutExercise(
                exercise = "e$it",
                name = if (it == 0) "Tractions négatives" else "Exercice $it",
                step = if (it == 0) 4 else 0,
                sets = 3,
                target = 4,
                cue = if (it == 0) "Descendez en cinq secondes" else null,
            )
        },
    )

    private fun show(state: SportActivityUiState) = compose.setContent {
        SportActivityContent(
            state = state,
            onQuit = { log += "quit" },
            onConfirmQuit = { log += "confirm" },
            onDismissQuit = { log += "stay" },
            onRetry = { log += "retry" },
            onOpenMaggie = {},
            immersion = NoImmersion,
        )
    }

    private fun ready(index: Int = 0, lastTimes: Map<String, String> = emptyMap(), quitAsked: Boolean = false) =
        SportActivityUiState(load = SportLoad.READY, workout = workout, exerciseIndex = index, lastTimes = lastTimes, quitAsked = quitAsked)

    @Test
    fun `the first exercise shows its step, name, cue and the counter`() {
        show(ready(lastTimes = mapOf("e0" to "3 × 4")))

        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_STEP).assertTextEquals("Échelon 4")
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_NAME).assertTextEquals("Tractions négatives")
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_CUE).assertTextEquals("Descendez en cinq secondes")
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_COUNTER).assertTextEquals("1 / 6")
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_LAST_TIME).assertTextEquals("La dernière fois · 3 × 4")
    }

    @Test
    fun `a resumed session opens on its exercise`() {
        show(ready(index = 1))

        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_NAME).assertTextEquals("Exercice 1")
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_COUNTER).assertTextEquals("2 / 6")
    }

    @Test
    fun `the cross asks before leaving, and Continuer closes the question`() {
        show(ready(quitAsked = true))

        compose.onNodeWithText("Quitter la séance ?").assertIsDisplayed()
        compose.onNodeWithText("Les séries faites sont gardées.").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_QUIT_STAY).performClick()
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_QUIT_CONFIRM).performClick()
        assertEquals(listOf("stay", "confirm"), log)
    }

    @Test
    fun `a failed load offers to retry and a missing session says so`() {
        show(SportActivityUiState(load = SportLoad.ERROR))
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_RETRY).performClick()
        assertEquals(listOf("retry"), log)
    }

    @Test
    fun `a missing session says so`() {
        show(SportActivityUiState(load = SportLoad.NOT_FOUND))
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_EMPTY).assertIsDisplayed()
    }
}
