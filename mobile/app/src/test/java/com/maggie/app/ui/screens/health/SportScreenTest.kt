package com.maggie.app.ui.screens.health

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.Workout
import com.maggie.app.data.repository.WorkoutRepository
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.assertTopToBottom
import com.maggie.app.screentest.tap
import com.maggie.app.ui.UiTags
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * « Santé › Sport » as drawn (MAG-705): the breadcrumb and « + », « Cette semaine » with its seven days, the sessions done
 * newest first, the detail in a sheet; on a tablet every session, planned ones too, beside the detail of the first.
 * The week is the one of Wednesday 21 October 2026.
 */
@RunWith(AndroidJUnit4::class)
class SportScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val repository = mockk<WorkoutRepository>()
    private val clock = Clock.fixed(Instant.parse("2026-10-21T08:15:30Z"), ZoneId.of("Europe/Paris"))

    private val run = Workout(
        "run", "run", "Course matinale", "2026-10-19T07:10:00+02:00", 32,
        distanceMeters = 5400, kcal = 386, avgHeartRate = 152, feeling = "good",
    )
    private val swim = Workout("swim", "swim", "Piscine", "2026-10-15T12:00:00+02:00", 65, source = "watch")
    private val gym = Workout("gym", "strength", "Haut du corps", "2026-10-21T18:30:00+02:00", 45, source = "planned")

    private fun viewModel(workouts: List<Workout> = listOf(swim, gym, run)): SportViewModel {
        coEvery { repository.getWorkouts() } returns Result.success(workouts)
        val auth = mockk<AuthRepository>()
        coEvery { auth.getUserId() } returns "user-1"
        val mercure = mockk<MercureService>()
        every { mercure.subscribe(any()) } returns emptyFlow()
        return SportViewModel(repository, mercure, auth, clock)
    }

    private fun waitForRow(id: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.workoutRow(id)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `the header is Santé, Sport, with a button to add a session`() {
        val opened = mutableListOf<String?>()
        compose.setContent { SportScreen(viewModel(), onOpenForm = { opened += it }) }
        waitForRow("run")

        compose.onNodeWithText("Santé").assertIsDisplayed()
        compose.onNodeWithText("Sport").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ajouter une séance").performClick()

        assertEquals(listOf<String?>(null), opened)
    }

    @Test
    fun `the week card counts the sessions and describes each day`() {
        compose.setContent { SportScreen(viewModel(), onOpenForm = {}) }
        waitForRow("run")

        compose.onNodeWithText("Cette semaine").assertIsDisplayed()
        compose.onNodeWithText("1 faite · 1 prévue").assertIsDisplayed()
        compose.onNodeWithContentDescription("L 19 : séance faite").assertExists()
        compose.onNodeWithContentDescription("M 21 : séance prévue").assertExists()
        compose.onNodeWithContentDescription("J 22").assertExists()
    }

    @Test
    fun `a phone lists the sessions done, newest first, and not the planned ones`() {
        compose.setContent { SportScreen(viewModel(), onOpenForm = {}) }
        waitForRow("run")

        assertTopToBottom(
            "Course" to compose.onNodeWithText("Course matinale"),
            "Piscine" to compose.onNodeWithText("Piscine"),
        )
        compose.onNodeWithText("Lun. 19 oct. · 32 min · 5,4 km").assertIsDisplayed()
        compose.onNodeWithText("386 kcal").assertIsDisplayed()
        compose.onNodeWithText("Saisie").assertIsDisplayed()
        compose.onNodeWithText("Montre").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag(UiTags.workoutRow("gym")).fetchSemanticsNodes().size)
    }

    @Test
    fun `a tap on a row opens the detail with the figures the session has`() {
        compose.setContent { SportScreen(viewModel(), onOpenForm = {}) }
        waitForRow("run")

        compose.onNodeWithTag(UiTags.workoutRow("run")).performClick()

        compose.onNodeWithTag(UiTags.WORKOUT_DETAIL).assertIsDisplayed()
        compose.onNodeWithText("Course à pied · Lun. 19 oct.").assertIsDisplayed()
        compose.onNodeWithText("32 min").assertIsDisplayed()
        compose.onNodeWithText("5,4 km").assertIsDisplayed()
        compose.onNodeWithText("152 bpm").assertIsDisplayed()
        compose.onNodeWithText("Ressenti").assertIsDisplayed()
        compose.onNodeWithText("Bien").assertIsDisplayed()
    }

    @Test
    fun `the detail leaves out the figures the session does not have`() {
        compose.setContent { SportScreen(viewModel(), onOpenForm = {}) }
        waitForRow("swim")

        compose.onNodeWithTag(UiTags.workoutRow("swim")).performClick()

        compose.onNodeWithText("1 h 05").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Distance").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("FC moy.").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Ressenti").fetchSemanticsNodes().size)
    }

    @Test
    fun `Supprimer asks first, then takes the session out of the list`() {
        val viewModel = viewModel()
        coEvery { repository.deleteWorkout("run") } returns Result.success(Unit)
        compose.setContent { SportScreen(viewModel, onOpenForm = {}) }
        waitForRow("run")
        compose.onNodeWithTag(UiTags.workoutRow("run")).performClick()

        // The sheet is a window of its own: `tap()`, not `performClick()`.
        compose.onNodeWithTag(UiTags.WORKOUT_DELETE).tap()
        compose.onNodeWithText("Supprimer « Course matinale » ?").assertIsDisplayed()
        coVerify(exactly = 0) { repository.deleteWorkout(any()) }
        coEvery { repository.getWorkouts() } returns Result.success(listOf(swim, gym))
        compose.onNodeWithTag(UiTags.WORKOUT_DELETE_CONFIRM).tap()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.workoutRow("run")).fetchSemanticsNodes().isEmpty() }
        coVerify { repository.deleteWorkout("run") }
    }

    @Test
    fun `Modifier hands the id of the session to the form`() {
        val opened = mutableListOf<String?>()
        compose.setContent { SportScreen(viewModel(), onOpenForm = { opened += it }) }
        waitForRow("run")
        compose.onNodeWithTag(UiTags.workoutRow("run")).performClick()

        compose.onNodeWithTag(UiTags.WORKOUT_EDIT).tap()

        assertEquals(listOf<String?>("run"), opened)
    }

    @Test
    fun `without a session the screen offers to add one`() {
        val opened = mutableListOf<String?>()
        compose.setContent { SportScreen(viewModel(emptyList()), onOpenForm = { opened += it }) }

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.SPORT_EMPTY).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Ajouter une séance").performClick()

        assertEquals(listOf<String?>(null), opened)
    }

    @Test
    fun `a failed load says so and offers to retry`() {
        coEvery { repository.getWorkouts() } returns Result.failure(RuntimeException("offline"))
        val mercure = mockk<MercureService>().also { every { it.subscribe(any()) } returns emptyFlow() }
        val auth = mockk<AuthRepository>().also { coEvery { it.getUserId() } returns "user-1" }
        val failing = SportViewModel(repository, mercure, auth, clock)
        compose.setContent { SportScreen(failing, onOpenForm = {}) }

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.SPORT_EMPTY).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Séances indisponibles").assertIsDisplayed()
        compose.onAllNodesWithText("Réessayer")[0].assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `a tablet lists every session, planned included, beside the detail of the first`() {
        compose.setContent { SportScreen(viewModel(), onOpenForm = {}) }
        waitForRow("gym")

        compose.onNodeWithTag(UiTags.workoutRow("run")).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.workoutRow("swim")).assertIsDisplayed()
        // The pill of the planned row and the one of its detail.
        compose.onAllNodesWithText("Prévue").assertCountEquals(2)
        // The first of the list, the planned session of the evening, is in the pane at once.
        compose.onNodeWithTag(UiTags.WORKOUT_DETAIL).assertIsDisplayed()
        compose.onNodeWithText("Musculation · Mer. 21 oct.").assertIsDisplayed()

        compose.onNodeWithTag(UiTags.workoutRow("run")).performClick()
        compose.onNodeWithText("Course à pied · Lun. 19 oct.").assertIsDisplayed()
        compose.onNodeWithText("152 bpm").assertIsDisplayed()
    }

    @Test
    fun `the form shows its errors under the fields and sends nothing until they are fixed`() {
        val viewModel = viewModel()
        coEvery { repository.createWorkout(any()) } returns Result.success(run)
        compose.setContent { WorkoutFormScreen(viewModel, workoutId = null, onClose = {}) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.WORKOUT_TITLE).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag(UiTags.WORKOUT_SAVE).performClick()
        compose.onNodeWithText("Donnez un titre à la séance.").assertIsDisplayed()
        compose.onNodeWithText("Indiquez une durée de 1 à 1440 minutes.").assertIsDisplayed()
        coVerify(exactly = 0) { repository.createWorkout(any()) }

        compose.onNodeWithTag(UiTags.WORKOUT_TITLE).performTextInput("Course")
        compose.onNodeWithTag(UiTags.WORKOUT_DURATION).performTextInput("32")
        compose.onNodeWithTag(UiTags.WORKOUT_DISTANCE).performTextInput("5,4")
        compose.onNodeWithTag(UiTags.WORKOUT_SAVE).performClick()

        compose.waitUntil(5_000) { viewModel.uiState.value.message == "Séance ajoutée" }
        coVerify { repository.createWorkout(match { it.distanceMeters == 5400 && it.durationMinutes == 32 && it.title == "Course" }) }
    }
}
