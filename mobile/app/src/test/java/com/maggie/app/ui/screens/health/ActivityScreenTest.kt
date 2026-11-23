package com.maggie.app.ui.screens.health

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.DailyEnergy
import com.maggie.app.data.model.HealthProfile
import com.maggie.app.data.model.Workout
import com.maggie.app.data.repository.DailyEnergyRepository
import com.maggie.app.data.repository.HealthProfileRepository
import com.maggie.app.data.repository.WorkoutRepository
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.assertTopToBottom
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.navigation.HealthSection
import io.mockk.coEvery
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

/** « Santé › Activité » as drawn (MAG-898). Today is Saturday 7 November 2026, 12:40 in Paris. */
@RunWith(AndroidJUnit4::class)
class ActivityScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val clock = Clock.fixed(Instant.parse("2026-11-07T11:40:00Z"), ZoneId.of("Europe/Paris"))
    private val profileRepository = mockk<HealthProfileRepository>()
    private val energyRepository = mockk<DailyEnergyRepository>()
    private val workoutRepository = mockk<WorkoutRepository>()

    private val walk = Workout("w-1", "walk", "Marche du matin", "2026-11-07T08:30:00+01:00", 25, kcal = 110, source = "activity")
    private val bike = Workout("w-2", "bike", "Vélo", "2026-11-07T17:00:00+01:00", 40, kcal = 200, source = "planned")
    private val strength = Workout("w-3", "strength", "Haut du corps", "2026-11-07T18:30:00+01:00", 45, source = "planned")

    private fun viewModel(
        profile: HealthProfile = HealthProfile(stepSource = HealthProfile.STEP_SOURCE_HEALTH_CONNECT),
        energy: List<DailyEnergy> = listOf(DailyEnergy("2026-11-06", steps = 5000), DailyEnergy("2026-11-07", steps = 2100)),
        workouts: List<Workout> = listOf(walk, bike, strength),
    ): ActivityViewModel {
        coEvery { profileRepository.getProfile() } returns Result.success(profile)
        coEvery { energyRepository.getDailyEnergy(any(), any()) } returns Result.success(energy)
        coEvery { workoutRepository.getWorkoutsBetween(any(), any()) } returns Result.success(workouts)
        val auth = mockk<AuthRepository>()
        coEvery { auth.getUserId() } returns "user-1"
        val mercure = mockk<MercureService>()
        every { mercure.subscribe(any()) } returns emptyFlow()
        return ActivityViewModel(profileRepository, energyRepository, workoutRepository, mercure, auth, clock)
    }

    private fun waitForRing() =
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.ACTIVITY_RING).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `phone shows ring, sessions and week in that order`() {
        compose.setContent { ActivityScreen(viewModel()) }
        waitForRing()

        assertTopToBottom(
            "steps" to compose.onNodeWithTag(UiTags.ACTIVITY_STEPS),
            "sessions" to compose.onNodeWithTag(UiTags.ACTIVITY_SESSIONS),
            "week" to compose.onNodeWithTag(UiTags.ACTIVITY_WEEK),
        )
        compose.onNodeWithTag(UiTags.ACTIVITY_STEPS_VALUE).assertTextEquals("2 100")
        compose.onNodeWithText("Pas · aujourd’hui à 12 h 40").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.ACTIVITY_PROJECTION).assertTextEquals("À ce rythme : ~5 400 / 8 000 à 20 h")
        compose.onNodeWithTag(UiTags.ACTIVITY_GAP).assertTextEquals("Il manquerait ~2 600 pas")
        compose.onNodeWithText("1 faite · 1 prévue").assertIsDisplayed()
        compose.onNodeWithText("08:30 · Marche du matin").assertIsDisplayed()
        compose.onNodeWithText("Fait").assertIsDisplayed()
        compose.onNodeWithText("Prévue").assertIsDisplayed()
        compose.onNodeWithText("40 min · ~200 kcal").assertIsDisplayed()
        compose.onNodeWithText("18:30 · Séance prévue · Haut du corps").assertIsDisplayed()
        compose.onNodeWithText("objectif 8 000").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `tablet shows sessions in the right column`() {
        compose.setContent { ActivityScreen(viewModel()) }
        waitForRing()

        val root = compose.onNodeWithTag(UiTags.ACTIVITY).fetchSemanticsNode().boundsInRoot
        val steps = compose.onNodeWithTag(UiTags.ACTIVITY_STEPS).fetchSemanticsNode().boundsInRoot
        val week = compose.onNodeWithTag(UiTags.ACTIVITY_WEEK).fetchSemanticsNode().boundsInRoot
        val sessions = compose.onNodeWithTag(UiTags.ACTIVITY_SESSIONS).fetchSemanticsNode().boundsInRoot
        assert(steps.right <= root.center.x + 1f) { "steps $steps overflows the left column of $root" }
        assert(week.right <= root.center.x + 1f) { "week $week overflows the left column of $root" }
        assert(sessions.left >= root.center.x - 1f) { "sessions $sessions is not in the right column of $root" }
    }

    @Test
    fun `without a step source the ring says so and Relier opens Configuration`() {
        val picked = mutableListOf<HealthSection>()
        compose.setContent {
            ActivityScreen(
                viewModel(profile = HealthProfile(stepSource = HealthProfile.STEP_SOURCE_NONE), energy = emptyList()),
                onSelectSection = { picked += it },
            )
        }
        waitForRing()

        compose.onNodeWithTag(UiTags.ACTIVITY_STEPS_VALUE).assertTextEquals("—")
        compose.onNodeWithText("Aucune source de pas").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.ACTIVITY_LINK).performClick()

        assertEquals(listOf(HealthSection.CONFIGURATION), picked)
    }

    @Test
    fun `Sport › opens Sport`() {
        val picked = mutableListOf<HealthSection>()
        compose.setContent { ActivityScreen(viewModel(), onSelectSection = { picked += it }) }
        waitForRing()

        compose.onNodeWithTag(UiTags.activityCited("w-3")).performClick()

        assertEquals(listOf(HealthSection.SPORT), picked)
    }

    @Test
    fun `no session today says so`() {
        compose.setContent { ActivityScreen(viewModel(workouts = emptyList())) }
        waitForRing()

        compose.onNodeWithText("Aucune session aujourd’hui").assertIsDisplayed()
    }

    @Test
    fun `without a session the card has no count`() {
        compose.setContent { ActivityScreen(viewModel(workouts = emptyList())) }
        waitForRing()

        compose.onNodeWithText("0 faite", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Aucune session aujourd’hui").assertIsDisplayed()
    }
}
