package com.maggie.app.ui.screens.health

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.health.FakeHealthConnectGateway
import com.maggie.app.data.health.FakeHealthSyncScheduler
import com.maggie.app.data.health.HealthConnectAvailability
import com.maggie.app.data.health.HealthConnectPermissions
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.HealthProfile
import com.maggie.app.data.repository.HealthProfileRepository
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.assertTopToBottom
import com.maggie.app.screentest.tap
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.navigation.HealthSection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * « Santé › Configuration » as the Nutrition-Tel mockup draws it (MAG-707): the need card, « Calculé · Saisi », the
 * profile or the typed need, then « Activité ». The ticket's figures: Homme, 38 ans, 178 cm, 72 kg, Sédentaire.
 */
@RunWith(AndroidJUnit4::class)
class ConfigurationScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val repository = mockk<HealthProfileRepository>()
    private val gateway = FakeHealthConnectGateway()
    private val scheduler = FakeHealthSyncScheduler()
    private val clock = Clock.fixed(Instant.parse("2026-10-30T08:00:00Z"), ZoneId.of("Europe/Paris"))

    private val profile = HealthProfile(
        id = "01PROFILE",
        sex = "male",
        birthYear = 1988,
        age = 38,
        heightCm = 178,
        weightKg = 72.0,
        activityLevel = "sedentary",
        mode = "computed",
        enteredNeedKcal = null,
        bmrKcal = 1648,
        needKcal = 1980,
        factor = 1.2,
        stepGoal = 8000,
        suggestionWindowStart = "08:00",
        suggestionWindowEnd = "20:00",
        sessionMinutes = 20,
        stepSource = "health_connect",
    )

    private fun viewModel(shown: HealthProfile = profile): ConfigurationViewModel {
        coEvery { repository.getProfile() } returns Result.success(shown)
        val auth = mockk<AuthRepository>()
        coEvery { auth.getUserId() } returns "user-1"
        val mercure = mockk<MercureService>()
        every { mercure.subscribe(any()) } returns emptyFlow()
        return ConfigurationViewModel(repository, mercure, auth, gateway, scheduler, clock)
    }

    private fun waitForCard() =
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.NEED_CARD).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `the header is Santé, Configuration, last of its menu`() {
        val picked = mutableListOf<HealthSection>()
        compose.setContent { ConfigurationScreen(viewModel(), onSelectSection = { picked += it }) }
        waitForCard()

        compose.onNodeWithText("Santé").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).performClick()
        assertTopToBottom(
            "Suivi" to compose.onNodeWithTag(UiTags.breadcrumbMenu("suivi")),
            "Sport" to compose.onNodeWithTag(UiTags.breadcrumbMenu("sport")),
            "Configuration" to compose.onNodeWithTag(UiTags.breadcrumbMenu("configuration")),
        )
        compose.onNodeWithTag(UiTags.breadcrumbMenu("suivi")).performClick()

        assertEquals(listOf(HealthSection.SUIVI), picked)
    }

    @Test
    fun `the need card reads 1 980 kcal, besoin du jour, métabolisme de base 1 648 kcal and × 1,20`() {
        compose.setContent { ConfigurationScreen(viewModel()) }
        waitForCard()

        compose.onNodeWithTag(UiTags.NEED_VALUE).assertTextEquals("1 980 kcal")
        compose.onNodeWithText("besoin du jour").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.NEED_BMR).assertTextEquals("métabolisme de base 1 648 kcal")
        compose.onNodeWithTag(UiTags.NEED_FACTOR).assertTextEquals("× 1,20")
    }

    @Test
    fun `Calculé shows the profile in the mockup's order`() {
        compose.setContent { ConfigurationScreen(viewModel()) }
        waitForCard()

        compose.onNodeWithTag(UiTags.needMode("computed")).assertIsSelected()
        compose.onNodeWithText("Saisi").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.profileSex("male")).assertIsSelected()
        compose.onNodeWithText("Femme").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.PROFILE_AGE + "_value").assertTextEquals("38 ans")
        compose.onNodeWithTag(UiTags.PROFILE_HEIGHT + "_value").assertTextEquals("178 cm")
        compose.onNodeWithTag(UiTags.PROFILE_WEIGHT + "_value").assertTextEquals("72 kg")
        compose.onNodeWithTag(UiTags.profileActivity("sedentary")).assertIsSelected()
        listOf("Légère", "Modérée", "Active", "Très active").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("Mifflin-St Jeor").assertExists()
        assertTopToBottom(
            "card" to compose.onNodeWithTag(UiTags.NEED_CARD),
            "mode" to compose.onNodeWithTag(UiTags.needMode("computed")),
            "Sexe" to compose.onNodeWithText("Sexe"),
            "Âge" to compose.onNodeWithText("Âge"),
            "Taille" to compose.onNodeWithText("Taille"),
            "Poids" to compose.onNodeWithText("Poids"),
            "Sédentaire" to compose.onNodeWithText("Sédentaire"),
            "Mifflin-St Jeor" to compose.onNodeWithText("Mifflin-St Jeor"),
            "Objectif de pas" to compose.onNodeWithText("Objectif de pas"),
        )
    }

    @Test
    fun `a weight with a decimal keeps it`() {
        compose.setContent { ConfigurationScreen(viewModel(profile.copy(weightKg = 72.4))) }
        waitForCard()

        compose.onNodeWithTag(UiTags.PROFILE_WEIGHT + "_value").assertTextEquals("72,4 kg")
    }

    // The write after the pause is ConfigurationViewModelTest's: the test clock does not run the ViewModel's delay.
    @Test
    fun `a stepper shows its value, and the card its figures, at once`() {
        coEvery { repository.update(any()) } returns Result.success(profile.copy(heightCm = 179, bmrKcal = 1654, needKcal = 1980))
        compose.setContent { ConfigurationScreen(viewModel()) }
        waitForCard()

        compose.onNodeWithContentDescription("Taille : plus").performClick()

        compose.onNodeWithTag(UiTags.PROFILE_HEIGHT + "_value").assertTextEquals("179 cm")
        compose.onNodeWithTag(UiTags.NEED_BMR).assertTextEquals("métabolisme de base 1\u202F654 kcal")
    }

    @Test
    fun `Saisi shows only the typed need, by steps of 50`() {
        val entered = profile.copy(mode = "entered", enteredNeedKcal = 2450, needKcal = 2450, factor = 1.49)
        compose.setContent { ConfigurationScreen(viewModel(entered)) }
        waitForCard()

        compose.onNodeWithTag(UiTags.needMode("entered")).assertIsSelected()
        compose.onNodeWithTag(UiTags.NEED_VALUE).assertTextEquals("2 450 kcal")
        compose.onNodeWithTag(UiTags.NEED_FACTOR).assertTextEquals("× 1,49")
        compose.onNodeWithTag(UiTags.PROFILE_ENTERED_NEED + "_value").assertTextEquals("2 450 kcal")
        compose.onNodeWithContentDescription("Moins 50 kcal").assertExists()
        compose.onNodeWithContentDescription("Plus 50 kcal").assertExists()
        listOf("Sexe", "Âge", "Taille", "Mifflin-St Jeor").forEach {
            assertEquals("« $it » is Calculé's", 0, compose.onAllNodesWithText(it).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun `a profile not complete draws dashes and no factor`() {
        compose.setContent { ConfigurationScreen(viewModel(HealthProfile())) }
        waitForCard()

        compose.onNodeWithTag(UiTags.NEED_VALUE).assertTextEquals("— kcal")
        compose.onNodeWithTag(UiTags.NEED_BMR).assertTextEquals("métabolisme de base — kcal")
        assertEquals(0, compose.onAllNodesWithTag(UiTags.NEED_FACTOR).fetchSemanticsNodes().size)
        compose.onNodeWithTag(UiTags.PROFILE_AGE + "_value").assertTextEquals("— ans")
        compose.onNodeWithTag(UiTags.PROFILE_HEIGHT + "_value").assertTextEquals("— cm")
        compose.onNodeWithTag(UiTags.PROFILE_WEIGHT + "_value").assertTextEquals("— kg")
    }

    @Test
    fun `Activité holds the step goal, Maggie's window, a session's length and the steps' source`() {
        compose.setContent { ConfigurationScreen(viewModel()) }
        waitForCard()

        compose.onNodeWithTag(UiTags.PROFILE_STEP_GOAL + "_value").performScrollTo().assertTextEquals("8 000")
        compose.onNodeWithText("Maggie propose entre").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.PROFILE_WINDOW_START).assertTextEquals("08:00")
        compose.onNodeWithTag(UiTags.PROFILE_WINDOW_END).assertTextEquals("20:00")
        compose.onNodeWithText("Durée d’une session").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.profileSession(20)).assertIsSelected()
        listOf("15 min", "30 min").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("Source des pas").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.PROFILE_STEP_SOURCE).assertTextEquals("Health Connect ›")
        assertEquals(
            "the section label and the chips' label",
            2,
            compose.onAllNodesWithText("ACTIVITÉ").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `the steps' source is picked from a menu`() {
        coEvery { repository.update(any()) } returns Result.success(profile.copy(stepSource = "none"))
        compose.setContent { ConfigurationScreen(viewModel()) }
        waitForCard()

        compose.onNodeWithTag(UiTags.PROFILE_STEP_SOURCE).performScrollTo().performClick()
        compose.onNodeWithTag(UiTags.profileStepSource("none")).tap()

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Aucune ›").fetchSemanticsNodes().isNotEmpty()
        }
        coVerify { repository.update(JsonObject(mapOf("stepSource" to JsonPrimitive("none")))) }
    }

    @Test
    fun `a failed load offers to try again`() {
        coEvery { repository.getProfile() } returns Result.failure(RuntimeException("offline"))
        val auth = mockk<AuthRepository>().also { coEvery { it.getUserId() } returns "user-1" }
        val mercure = mockk<MercureService>().also { every { it.subscribe(any()) } returns emptyFlow() }
        compose.setContent { ConfigurationScreen(ConfigurationViewModel(repository, mercure, auth, gateway, scheduler, clock)) }

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.CONFIGURATION_EMPTY).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Profil indisponible").assertIsDisplayed()
    }

    @Test
    fun `permissions refused, the steps' source reads Non connecté and a tap asks Health Connect for them`() {
        gateway.granted = emptySet()
        compose.setContent { ConfigurationScreen(viewModel()) }
        waitForCard()

        compose.waitUntil(5_000) { compose.onAllNodesWithText("Non connecté ›").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(UiTags.PROFILE_STEP_SOURCE).performScrollTo().assertTextEquals("Non connecté ›")
        compose.onNodeWithTag(UiTags.PROFILE_STEP_SOURCE).performClick()
        compose.waitForIdle()

        assertEquals(listOf(HealthConnectPermissions.ALL), gateway.requested)
    }

    @Test
    fun `Health Connect not installed reads Non connecté too, and a tap does not ask for permissions`() {
        gateway.available = HealthConnectAvailability.NOT_INSTALLED
        compose.setContent { ConfigurationScreen(viewModel()) }
        waitForCard()

        compose.waitUntil(5_000) { compose.onAllNodesWithText("Non connecté ›").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(UiTags.PROFILE_STEP_SOURCE).performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(emptyList<Set<String>>(), gateway.requested)
    }
}
