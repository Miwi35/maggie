package com.maggie.app.ui.screens.health

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.BodyMeasurement
import com.maggie.app.data.repository.BodyMeasurementRepository
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.assertTopToBottom
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.navigation.HealthSection
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
 * « Santé › Suivi » as drawn (MAG-706): the breadcrumb, the period selector, the Poids card and only it — the other
 * cards of the mockup are not delivered and leave no trace. Today is Wednesday 21 October 2026.
 */
@RunWith(AndroidJUnit4::class)
class SuiviScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val repository = mockk<BodyMeasurementRepository>()
    private val clock = Clock.fixed(Instant.parse("2026-10-21T08:15:30Z"), ZoneId.of("Europe/Paris"))

    private val today = BodyMeasurement("today", "2026-10-21T07:12:00+02:00", weightKg = 72.4, bodyFatPercent = 21.3)
    private val monday = BodyMeasurement("monday", "2026-10-19T07:05:00+02:00", weightKg = 72.8, bodyFatPercent = 21.6)

    private fun viewModel(measurements: List<BodyMeasurement> = listOf(today, monday)): SuiviViewModel {
        coEvery { repository.getBodyMeasurements(any()) } returns Result.success(measurements)
        val auth = mockk<AuthRepository>()
        coEvery { auth.getUserId() } returns "user-1"
        val mercure = mockk<MercureService>()
        every { mercure.subscribe(any()) } returns emptyFlow()
        return SuiviViewModel(repository, mercure, auth, clock)
    }

    private fun waitForCard() =
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.WEIGHT_CARD).fetchSemanticsNodes().isNotEmpty() }

    private fun assertAbsent(text: String) =
        assertEquals("« $text » is not delivered", 0, compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size)

    @Test
    fun `the header is Santé, Suivi, and its menu lists Suivi then Sport`() {
        val picked = mutableListOf<HealthSection>()
        compose.setContent { SuiviScreen(viewModel(), onSelectSection = { picked += it }) }
        waitForCard()

        compose.onNodeWithText("Santé").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).performClick()
        assertTopToBottom(
            "Suivi" to compose.onNodeWithTag(UiTags.breadcrumbMenu("suivi")),
            "Sport" to compose.onNodeWithTag(UiTags.breadcrumbMenu("sport")),
        )
        compose.onNodeWithTag(UiTags.breadcrumbMenu("sport")).performClick()

        assertEquals(listOf(HealthSection.SPORT), picked)
    }

    @Test
    fun `the period selector comes first, 30 days chosen, then the Poids card with today's values`() {
        compose.setContent { SuiviScreen(viewModel()) }
        waitForCard()

        assertTopToBottom(
            "periods" to compose.onNodeWithTag(UiTags.SUIVI_PERIODS),
            "card" to compose.onNodeWithTag(UiTags.WEIGHT_CARD),
        )
        listOf("7 j", "30 j", "90 j", "1 an").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithTag(UiTags.suiviPeriod("30")).assertIsSelected()
        compose.onNodeWithText("Poids").assertIsDisplayed()
        compose.onNodeWithText("Aujourd’hui").assertIsDisplayed()
        compose.onNodeWithText("72,4 kg").assertIsDisplayed()
        compose.onNodeWithText("— 72,6 kg").assertIsDisplayed()
        compose.onNodeWithText("· 21,3 % MG").assertIsDisplayed()
        compose.onNodeWithText("22 sept.").assertIsDisplayed()
        compose.onNodeWithText("21 oct.").assertIsDisplayed()
    }

    @Test
    fun `the cards not delivered are absent, without a placeholder`() {
        compose.setContent { SuiviScreen(viewModel()) }
        waitForCard()

        listOf("Pas", "Énergie", "Moral", "Nutrition", "fatigue").forEach(::assertAbsent)
    }

    @Test
    fun `a tap on a column shows that day's values`() {
        compose.setContent { SuiviScreen(viewModel()) }
        waitForCard()

        compose.onNodeWithContentDescription("lun. 19 oct. : 72,8 kg, tendance 72,8 kg").performClick()

        compose.onNodeWithText("lun. 19 oct.").assertIsDisplayed()
        compose.onNodeWithText("72,8 kg").assertIsDisplayed()
        compose.onNodeWithText("· 21,6 % MG").assertIsDisplayed()
        // A day without a weighing says so.
        compose.onNodeWithContentDescription("mar. 20 oct. : pas de pesée, tendance 72,8 kg").performClick()
        compose.onNodeWithTag(UiTags.WEIGHT_VALUE).assertIsDisplayed()
        compose.onNodeWithText("· — MG").assertIsDisplayed()
    }

    @Test
    fun `another period changes the columns and their labels`() {
        compose.setContent { SuiviScreen(viewModel()) }
        waitForCard()

        compose.onNodeWithTag(UiTags.suiviPeriod("365")).performClick()
        compose.onNodeWithText("Octobre 2026").assertIsDisplayed()
        compose.onNodeWithText("nov. 25").assertIsDisplayed()

        compose.onNodeWithTag(UiTags.suiviPeriod("90")).performClick()
        compose.onNodeWithText("Sem. du 15 oct.").assertIsDisplayed()
    }

    @Test
    fun `a long press on a weighed day offers Écarter, which sets its weighing aside`() {
        coEvery { repository.setExcluded("today", true) } returns Result.success(today.copy(excluded = true))
        compose.setContent { SuiviScreen(viewModel()) }
        waitForCard()

        compose.onNodeWithTag(UiTags.weightColumn(29)).performTouchInput { longClick() }
        compose.onNodeWithText("Écarter").performClick()

        compose.waitUntil(5_000) { compose.onAllNodesWithText("Pesée écartée").fetchSemanticsNodes().isNotEmpty() }
        coVerify { repository.setExcluded("today", true) }
    }

    @Test
    fun `a failed load offers to try again`() {
        coEvery { repository.getBodyMeasurements(any()) } returns Result.failure(RuntimeException("offline"))
        val auth = mockk<AuthRepository>().also { coEvery { it.getUserId() } returns "user-1" }
        val mercure = mockk<MercureService>().also { every { it.subscribe(any()) } returns emptyFlow() }
        compose.setContent { SuiviScreen(SuiviViewModel(repository, mercure, auth, clock)) }

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.SUIVI_EMPTY).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Pesées indisponibles").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `a tablet holds the period selector in its header and the Poids card in the left column`() {
        compose.setContent { SuiviScreen(viewModel()) }
        waitForCard()

        compose.onNodeWithTag(UiTags.SUIVI_PERIODS).assertIsDisplayed()
        compose.onNodeWithText("72,4 kg").assertIsDisplayed()
        val card = compose.onNodeWithTag(UiTags.WEIGHT_CARD).fetchSemanticsNode().boundsInRoot
        val periods = compose.onNodeWithTag(UiTags.SUIVI_PERIODS).fetchSemanticsNode().boundsInRoot
        val root = compose.onNodeWithTag(UiTags.SUIVI).fetchSemanticsNode().boundsInRoot
        // The card fills the left half; the selector sits at the right of the header, above the content.
        assert(card.right <= root.center.x + 1f) { "card $card overflows the left column of $root" }
        assert(periods.bottom <= root.top + 1f) { "periods $periods are not in the header" }
        listOf("Pas", "Énergie", "Moral", "Nutrition").forEach(::assertAbsent)
    }
}
