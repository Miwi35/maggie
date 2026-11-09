package com.maggie.app.ui.screens.health

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.HealthDay
import com.maggie.app.data.repository.HealthDayRepository
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
 * « Santé › Journal » as specified (MAG-750): the day's header, the two scales, the note, the indicators and the
 * previous days. Today is Saturday 7 November 2026, 21:50 in Paris.
 */
@RunWith(AndroidJUnit4::class)
class JournalScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val repository = mockk<HealthDayRepository>()
    private val clock = Clock.fixed(Instant.parse("2026-11-07T20:50:00Z"), ZoneId.of("Europe/Paris"))

    private val today = HealthDay(
        id = "d7", date = "2026-11-07", weightKg = 71.6, weighedAt = "2026-11-07T06:12:00Z", steps = 8000,
        eatenKcal = 1500, expendedKcal = 2100, balanceKcal = -600, sportMinutes = 45,
        mood = 7, fatigue = 4, note = "Bonne séance", notedBy = "maggie", notedAt = "2026-11-07T20:42:00Z",
    )
    private val friday = HealthDay(id = "d6", date = "2026-11-06", mood = 6, fatigue = 5, note = "Fatigué le soir")

    private fun viewModel(entry: HealthDay = today, days: List<HealthDay> = listOf(friday, entry)): JournalViewModel {
        coEvery { repository.getDay("2026-11-07") } returns Result.success(entry)
        coEvery { repository.getDay("2026-11-06") } returns Result.success(friday)
        coEvery { repository.getDays(any(), any()) } returns Result.success(days)
        val auth = mockk<AuthRepository>()
        coEvery { auth.getUserId() } returns "user-1"
        val mercure = mockk<MercureService>()
        every { mercure.subscribe(any()) } returns emptyFlow()
        return JournalViewModel(repository, mercure, auth, clock)
    }

    private fun waitForEntry() =
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.JOURNAL_FIGURES).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `the day's header, the scales, the note, the indicators, then the previous days`() {
        compose.setContent { JournalScreen(viewModel()) }
        waitForEntry()

        compose.onNodeWithTag(UiTags.JOURNAL_TITLE).assertTextEquals("Aujourd’hui samedi 7 nov.")
        assertTopToBottom(
            "title" to compose.onNodeWithTag(UiTags.JOURNAL_TITLE),
            "mood" to compose.onNodeWithTag(UiTags.JOURNAL_MOOD),
            "fatigue" to compose.onNodeWithTag(UiTags.JOURNAL_FATIGUE),
            "note" to compose.onNodeWithTag(UiTags.JOURNAL_NOTE),
            "figures" to compose.onNodeWithTag(UiTags.JOURNAL_FIGURES),
            "history" to compose.onNodeWithTag(UiTags.JOURNAL_HISTORY),
        )
        compose.onNodeWithText("Moral").assertExists()
        compose.onNodeWithText("Fatigue").assertExists()
        compose.onNodeWithText("Jours précédents").assertExists()
    }

    @Test
    fun `each scale has ten pastilles, the chosen one selected, and says when Maggie noted the day`() {
        compose.setContent { JournalScreen(viewModel()) }
        waitForEntry()

        (1..10).forEach {
            compose.onNodeWithTag(UiTags.journalMood(it)).assertExists()
            compose.onNodeWithTag(UiTags.journalFatigue(it)).assertExists()
        }
        compose.onNodeWithTag(UiTags.journalMood(7)).assertIsSelected()
        compose.onNodeWithTag(UiTags.journalMood(6)).assertIsNotSelected()
        compose.onNodeWithTag(UiTags.journalFatigue(4)).assertIsSelected()
        compose.onNodeWithTag(UiTags.JOURNAL_NOTED_BY).assertTextEquals("avec Maggie · 21:42")
    }

    @Test
    fun `a day the owner noted does not say avec Maggie`() {
        compose.setContent { JournalScreen(viewModel(entry = today.copy(notedBy = "user"))) }
        waitForEntry()

        assertEquals(0, compose.onAllNodesWithTag(UiTags.JOURNAL_NOTED_BY).fetchSemanticsNodes().size)
    }

    @Test
    fun `a tap on a pastille sends the PATCH at once`() {
        coEvery { repository.setMood("2026-11-07", 3) } returns Result.success(today.copy(mood = 3))
        compose.setContent { JournalScreen(viewModel()) }
        waitForEntry()

        compose.onNodeWithTag(UiTags.journalMood(3)).performClick()

        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(UiTags.journalMood(3)).assertIsSelected() }.isSuccess }
        coVerify(exactly = 1) { repository.setMood("2026-11-07", 3) }
        compose.onNodeWithTag(UiTags.journalMood(7)).assertIsNotSelected()
    }

    @Test
    fun `Enregistrer waits for a change, then sends the note`() {
        coEvery { repository.setNote("2026-11-07", "Longue marche") } returns Result.success(today.copy(note = "Longue marche"))
        compose.setContent { JournalScreen(viewModel()) }
        waitForEntry()

        compose.onNodeWithText("Bonne séance").assertExists()
        compose.onNodeWithTag(UiTags.JOURNAL_NOTE_SAVE).assertIsNotEnabled()
        compose.onNodeWithTag(UiTags.JOURNAL_NOTE).performTextReplacement("Longue marche")
        compose.onNodeWithTag(UiTags.JOURNAL_NOTE_SAVE).assertIsEnabled().performClick()

        compose.waitUntil(5_000) { compose.onAllNodesWithText("Note enregistrée").fetchSemanticsNodes().isNotEmpty() }
        coVerify(exactly = 1) { repository.setNote("2026-11-07", "Longue marche") }
    }

    @Test
    fun `the indicators show the API's figures and each opens its section`() {
        val picked = mutableListOf<HealthSection>()
        compose.setContent { JournalScreen(viewModel(), onSelectSection = { picked += it }) }
        waitForEntry()

        compose.onNodeWithTag(UiTags.JOURNAL_WEIGHT_VALUE, useUnmergedTree = true).assertTextEquals("71,6 kg · 07:12")
        compose.onNodeWithTag(UiTags.JOURNAL_STEPS_VALUE, useUnmergedTree = true).assertTextEquals("8 000")
        compose.onNodeWithTag(UiTags.JOURNAL_BALANCE_VALUE, useUnmergedTree = true).assertTextEquals("−600 kcal")
        compose.onNodeWithTag(UiTags.JOURNAL_SPORT_VALUE, useUnmergedTree = true).assertTextEquals("45 min")

        listOf(UiTags.JOURNAL_WEIGHT, UiTags.JOURNAL_STEPS, UiTags.JOURNAL_BALANCE, UiTags.JOURNAL_SPORT).forEach {
            compose.onNodeWithTag(it).performScrollTo().performClick()
        }

        assertEquals(listOf(HealthSection.SUIVI, HealthSection.SUIVI, HealthSection.NUTRITION, HealthSection.SPORT), picked)
    }

    @Test
    fun `a day without figures shows dashes`() {
        compose.setContent { JournalScreen(viewModel(entry = HealthDay(date = "2026-11-07"), days = listOf(friday))) }
        waitForEntry()

        compose.onNodeWithTag(UiTags.JOURNAL_WEIGHT_VALUE, useUnmergedTree = true).assertTextEquals("—")
        compose.onNodeWithTag(UiTags.JOURNAL_SPORT_VALUE, useUnmergedTree = true).assertTextEquals("—")
        compose.onNodeWithTag(UiTags.journalMood(7)).assertIsNotSelected()
    }

    @Test
    fun `the previous days list the date, the note and M F, and a tap chooses the day`() {
        compose.setContent { JournalScreen(viewModel()) }
        waitForEntry()

        compose.onNodeWithTag(UiTags.journalPast("2026-11-06")).performScrollTo()
        compose.onNodeWithText("ven. 6 nov.").assertExists()
        compose.onNodeWithText("Fatigué le soir").assertExists()
        compose.onNodeWithText("M 6 · F 5").assertExists()
        // Today is the entry, not a previous day.
        assertEquals(0, compose.onAllNodesWithTag(UiTags.journalPast("2026-11-07")).fetchSemanticsNodes().size)

        compose.onNodeWithTag(UiTags.journalPast("2026-11-06")).performClick()

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Hier vendredi 6 nov.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(UiTags.journalMood(6)).assertIsSelected()
        compose.onNodeWithTag(UiTags.JOURNAL_NEXT).assertIsEnabled()
    }

    @Test
    fun `no day after today`() {
        compose.setContent { JournalScreen(viewModel()) }
        waitForEntry()

        compose.onNodeWithTag(UiTags.JOURNAL_NEXT).assertIsNotEnabled()
        compose.onNodeWithTag(UiTags.JOURNAL_PREVIOUS).assertIsEnabled()
    }

    @Test
    fun `the header is Santé, Journal, after Sport and Nutrition in its menu`() {
        val picked = mutableListOf<HealthSection>()
        compose.setContent { JournalScreen(viewModel(), onSelectSection = { picked += it }) }
        waitForEntry()

        compose.onNodeWithText("Santé").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).performClick()
        assertTopToBottom(
            "Sport" to compose.onNodeWithTag(UiTags.breadcrumbMenu("sport")),
            "Nutrition" to compose.onNodeWithTag(UiTags.breadcrumbMenu("nutrition")),
            "Journal" to compose.onNodeWithTag(UiTags.breadcrumbMenu("journal")),
            "Configuration" to compose.onNodeWithTag(UiTags.breadcrumbMenu("configuration")),
        )
        compose.onNodeWithTag(UiTags.breadcrumbMenu("nutrition")).performClick()

        assertEquals(listOf(HealthSection.NUTRITION), picked)
    }

    @Test
    fun `a failed load offers to try again`() {
        coEvery { repository.getDay(any()) } returns Result.failure(RuntimeException("offline"))
        coEvery { repository.getDays(any(), any()) } returns Result.success(emptyList())
        val auth = mockk<AuthRepository>().also { coEvery { it.getUserId() } returns "user-1" }
        val mercure = mockk<MercureService>().also { every { it.subscribe(any()) } returns emptyFlow() }
        compose.setContent { JournalScreen(JournalViewModel(repository, mercure, auth, clock)) }

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.JOURNAL_EMPTY).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Journal indisponible").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `a tablet holds the days on the left and the entry on the right`() {
        compose.setContent { JournalScreen(viewModel()) }
        waitForEntry()

        val root = compose.onNodeWithTag(UiTags.JOURNAL).fetchSemanticsNode().boundsInRoot
        val history = compose.onNodeWithTag(UiTags.JOURNAL_HISTORY).fetchSemanticsNode().boundsInRoot
        val mood = compose.onNodeWithTag(UiTags.JOURNAL_MOOD).fetchSemanticsNode().boundsInRoot
        assert(history.right <= root.center.x + 1f) { "history $history overflows the left column of $root" }
        assert(mood.left >= root.left + root.width * 0.4f - 1f) { "mood $mood is not in the right column of $root" }
    }
}
