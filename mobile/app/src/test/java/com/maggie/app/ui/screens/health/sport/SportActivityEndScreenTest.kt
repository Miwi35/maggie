package com.maggie.app.ui.screens.health.sport

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Workout
import com.maggie.app.data.model.WorkoutExercise
import com.maggie.app.data.model.WorkoutSetDone
import com.maggie.app.data.model.WorkoutSource
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.activity.ActivityImmersion
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The labels and the touches of the end of the Sport activity (MAG-781). */
@RunWith(AndroidJUnit4::class)
class SportActivityEndScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private object NoImmersion : ActivityImmersion {
        override fun enter() = Unit

        override fun exit() = Unit
    }

    private val log = mutableListOf<String>()

    private val done = Workout(
        id = "d1", type = "strength", title = "Haut du corps", startedAt = "2026-11-11T18:30:00+01:00", durationMinutes = 32,
        source = WorkoutSource.ACTIVITY, streak = 7,
        exercises = listOf(
            WorkoutExercise(exercise = "tractions", name = "Tractions", sets = 2, target = 5, done = listOf(WorkoutSetDone(reps = 5), WorkoutSetDone(reps = 4))),
            WorkoutExercise(exercise = "pompes", name = "Pompes", sets = 2, target = 7, done = listOf(WorkoutSetDone(reps = 8), WorkoutSetDone(reps = 7))),
        ),
    )

    private fun show(state: SportEndUiState) = compose.setContent {
        SportActivityEndContent(
            state = state,
            actions = SportEndActions(
                onDifficulty = { log += "difficulty $it" },
                onFeeling = { i, f -> log += "feeling $i $f" },
                onSave = { log += "save" },
                onRetry = { log += "retry" },
            ),
            onQuit = { log += "quit" },
            onConfirmQuit = { log += "confirm" },
            onDismissQuit = { log += "stay" },
            onOpenMaggie = {},
            immersion = NoImmersion,
        )
    }

    private fun ready(
        workout: Workout = done,
        difficulty: Int? = null,
        feelings: List<String?> = listOf(null, null),
        deltas: Map<String, SportDelta> = emptyMap(),
        saveFailed: Boolean = false,
        planned: Workout = done,
    ) = SportEndUiState(
        load = SportEndLoad.READY, planned = planned, done = workout, difficulty = difficulty, feelings = feelings, deltas = deltas, saveFailed = saveFailed,
    )

    @Test
    fun `the end says it was held, the streak, the card and the save`() {
        show(ready())

        compose.onNodeWithTag(UiTags.SPORT_END_TITLE).assertTextEquals("Tenue. La semaine prend forme.")
        compose.onNodeWithTag(UiTags.SPORT_END_STREAK_TEXT).assertTextContains("séances d'affilée", substring = true)
        compose.onNodeWithTag(UiTags.SPORT_END_DIFFICULTY).assertIsDisplayed()
        compose.onNodeWithText("Enregistrer").assertIsDisplayed()
    }

    @Test
    fun `without a streak there is none on screen`() {
        show(ready(workout = done.copy(streak = null)))

        assertEquals(0, compose.onAllNodesWithTagCount(UiTags.SPORT_END_STREAK))
    }

    @Test
    fun `a soft session has its own title`() {
        show(ready(planned = done.copy(variant = "soft")))

        compose.onNodeWithTag(UiTags.SPORT_END_TITLE).assertTextEquals("Faite, et en version douce : la série tient.")
    }

    @Test
    fun `each exercise shows its gap to the last time`() {
        show(ready(deltas = mapOf("tractions" to SportDelta("+2 rép.", same = false), "pompes" to SportDelta("=", same = true))))

        compose.onNodeWithTag("${UiTags.SPORT_END_DELTA_PREFIX}0").assertTextEquals("+2 rép.")
        compose.onNodeWithTag("${UiTags.SPORT_END_DELTA_PREFIX}1").assertTextEquals("=")
    }

    @Test
    fun `touching a cell, a feeling or the save tells the screen`() {
        show(ready(difficulty = 7, feelings = listOf("right", null)))

        compose.onNodeWithTag("${UiTags.SPORT_END_DIFFICULTY_CELL_PREFIX}7").assertIsSelected()
        compose.onNodeWithTag("${UiTags.SPORT_END_DIFFICULTY_CELL_PREFIX}6").assertIsNotSelected()
        compose.onNodeWithTag("${UiTags.SPORT_END_FEELING_PREFIX}0_right").assertIsSelected()
        compose.onNodeWithTag("${UiTags.SPORT_END_FEELING_PREFIX}1_right").assertIsNotSelected()

        compose.onNodeWithTag("${UiTags.SPORT_END_DIFFICULTY_CELL_PREFIX}9").performClick()
        compose.onNodeWithTag("${UiTags.SPORT_END_FEELING_PREFIX}1_too_hard").performClick()
        compose.onNodeWithTag(UiTags.SPORT_END_SAVE).performClick()

        assertEquals(listOf("difficulty 9", "feeling 1 too_hard", "save"), log)
    }

    @Test
    fun `a failed save says so and the button reads Réessayer`() {
        show(ready(saveFailed = true))

        compose.onNodeWithTag(UiTags.SPORT_END_SAVE_ERROR).assertIsDisplayed()
        compose.onNodeWithText("Réessayer").assertIsDisplayed()
    }

    @Test
    fun `an error on arrival offers Réessayer`() {
        show(SportEndUiState(load = SportEndLoad.ERROR))

        compose.onNodeWithTag(UiTags.SPORT_END_RETRY).performClick()

        assertEquals(listOf("retry"), log)
    }

    private fun ScreenRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
