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

    private fun ready(
        index: Int = 0,
        lastTimes: Map<String, String> = emptyMap(),
        quitAsked: Boolean = false,
        session: SportState = SportState(exerciseIndex = index, done = List(6) { emptyList() }, loadKg = List(6) { null }),
        reps: Int = 4,
        remaining: Int? = null,
        on: Workout = workout,
    ) = SportActivityUiState(
        load = SportLoad.READY, workout = on, session = session, lastTimes = lastTimes, quitAsked = quitAsked, reps = reps, remaining = remaining,
    )

    // Tractions 3 × 5 with 2:00 of rest, a loaded squat, a held plank.
    private val plan = Workout(
        id = "w2", type = "strength", title = "Séance A", startedAt = "2026-11-11T18:30:00+01:00", durationMinutes = 45, source = WorkoutSource.PLANNED,
        exercises = listOf(
            WorkoutExercise(exercise = "pull", name = "Tractions négatives", sets = 3, target = 5, restSeconds = 120),
            WorkoutExercise(exercise = "squat", name = "Squat gobelet", sets = 3, target = 10, loadKg = 10.0, restSeconds = 90),
            WorkoutExercise(exercise = "plank", name = "Planche", unit = "seconds", sets = 3, target = 30, restSeconds = 60),
        ),
    )

    private fun planState(exercise: Int = 0, set: Int = 0, phase: String = SportPhase.WORK, done: List<List<SportSetDone>> = List(3) { emptyList() }, ends: String? = null, paused: Int? = null) =
        SportState(exerciseIndex = exercise, setIndex = set, phase = phase, timerEndsAt = ends, timerRemaining = paused, loadKg = listOf(null, 10.0, null), done = done)

    private fun showWith(state: SportActivityUiState, actions: SportActions) = compose.setContent {
        SportActivityContent(
            state = state, actions = actions, onQuit = {}, onConfirmQuit = {}, onDismissQuit = {}, onRetry = {}, onOpenMaggie = {}, immersion = NoImmersion,
        )
    }

    @Test
    fun `a set to do shows the plan, the pastilles, the stepper and what comes after`() {
        showWith(ready(session = planState(done = listOf(listOf(SportSetDone(reps = 5)), emptyList(), emptyList())).copy(setIndex = 1), reps = 3, on = plan), SportActions())

        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_PLAN).assertTextEquals("3 × 5")
        compose.onNodeWithTag("${UiTags.SPORT_ACTIVITY_PILL}0").assertTextEquals("5")
        compose.onNodeWithTag("${UiTags.SPORT_ACTIVITY_PILL}1").assertTextEquals("3")
        compose.onNodeWithTag("${UiTags.SPORT_ACTIVITY_PILL}2").assertTextEquals("5")
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_REPS_VALUE).assertTextEquals("3")
        compose.onNodeWithText("répétitions").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_NEXT).assertTextEquals("Ensuite · Repos 2:00 · série 3")
        compose.onNodeWithText("Série faite").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_HOLD).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_LOAD).assertDoesNotExist()
    }

    @Test
    fun `the stepper, the primary button and the back arrow report their touch`() {
        val taps = mutableListOf<String>()
        showWith(
            ready(session = planState(), on = plan),
            SportActions(onDone = { taps += "done" }, onBack = { taps += "back" }, onChangeReps = { taps += "reps$it" }),
        )

        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_REPS_MINUS).performClick()
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_REPS_PLUS).performClick()
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_BACK).performClick()
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_PRIMARY).performClick()
        assertEquals(listOf("reps-1", "reps1", "back", "done"), taps)
    }

    @Test
    fun `a rest shows the ring, its clock, minus and plus 15 and what is next`() {
        val taps = mutableListOf<String>()
        showWith(
            ready(session = planState(phase = SportPhase.REST, done = listOf(listOf(SportSetDone(reps = 3)), emptyList(), emptyList()), ends = "2026-11-11T17:00:00Z"), remaining = 82, on = plan),
            SportActions(onAdjustRest = { taps += "rest$it" }, onSkipRest = { taps += "skip" }),
        )

        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_TIMER_CLOCK).assertTextEquals("1:22")
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_TIMER_CAPTION).assertTextEquals("Repos")
        compose.onNodeWithTag("${UiTags.SPORT_ACTIVITY_PILL}0").assertTextEquals("3")
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_NEXT).assertTextEquals("Ensuite · Série 2 · 5")
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_TIMER_MINUS).performClick()
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_TIMER_PLUS).performClick()
        compose.onNodeWithText("Passer le repos").performClick()
        assertEquals(listOf("rest-15", "rest15", "skip"), taps)
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_REPS_VALUE).assertDoesNotExist()
    }

    @Test
    fun `a loaded exercise shows its lest with its steps of 5 kg`() {
        val taps = mutableListOf<Double>()
        showWith(ready(session = planState(exercise = 1), on = plan), SportActions(onChangeLoad = { taps += it }))

        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_PLAN).assertTextEquals("3 × 10 · 10 kg")
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_LOAD_VALUE).assertTextEquals("10 kg")
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_LOAD_PLUS).performClick()
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_LOAD_MINUS).performClick()
        assertEquals(listOf(5.0, -5.0), taps)
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_NEXT).assertTextEquals("Ensuite · Repos 1:30 · série 2")
    }

    @Test
    fun `a held exercise is ready, then counts down, then pauses`() {
        val taps = mutableListOf<String>()
        val actions = SportActions(onStartHold = { taps += "start" }, onPauseHold = { taps += "pause" })
        val held = planState(exercise = 2)
        showWith(ready(session = held, remaining = 30, on = plan), actions)

        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_PLAN).assertTextEquals("3 × 30 s")
        compose.onNodeWithTag("${UiTags.SPORT_ACTIVITY_PILL}0").assertTextEquals("30 s")
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_TIMER_CAPTION).assertTextEquals("Prêt")
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_TIMER_CLOCK).assertTextEquals("0:30")
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_TIMER_MINUS).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_HOLD).assertTextEquals("Démarrer")
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_HOLD).performClick()
        assertEquals(listOf("start"), taps)
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_NEXT).assertTextEquals("Ensuite · Repos 1:00 · série 2")
    }

    @Test
    fun `a running held exercise says Tenez and offers Pause`() {
        showWith(ready(session = planState(exercise = 2, phase = SportPhase.HOLD, ends = "2026-11-11T17:00:00Z"), remaining = 18, on = plan), SportActions())

        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_TIMER_CAPTION).assertTextEquals("Tenez")
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_TIMER_CLOCK).assertTextEquals("0:18")
        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_HOLD).assertTextEquals("Pause")
    }

    @Test
    fun `a pastille done on a held exercise shows the seconds held`() {
        showWith(ready(session = planState(exercise = 2, set = 1, done = listOf(emptyList(), emptyList(), listOf(SportSetDone(seconds = 12)))), remaining = 30, on = plan), SportActions())

        compose.onNodeWithTag("${UiTags.SPORT_ACTIVITY_PILL}0").assertTextEquals("12 s")
    }

    @Test
    fun `the end of an exercise says which one comes next with its plan`() {
        showWith(
            ready(session = planState(set = 2, phase = SportPhase.REST, done = listOf(List(3) { SportSetDone(reps = 5) }, emptyList(), emptyList()), ends = "2026-11-11T17:00:00Z"), remaining = 60, on = plan),
            SportActions(),
        )

        compose.onNodeWithTag(UiTags.SPORT_ACTIVITY_NEXT).assertTextEquals("Ensuite · Squat gobelet · 3 × 10")
    }

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
