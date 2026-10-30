package com.maggie.app.ui.screens.digest

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.AgendaHomeEvent
import com.maggie.app.data.model.AgendaHomeState
import com.maggie.app.data.model.AudioAttachment
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.data.model.CuisineHomeMeal
import com.maggie.app.data.model.CuisineHomeSlot
import com.maggie.app.data.model.CuisineHomeState
import com.maggie.app.data.model.FinanceHomeRing
import com.maggie.app.data.model.FinanceHomeState
import com.maggie.app.data.model.HomeTask
import com.maggie.app.data.model.HomeTasks
import com.maggie.app.data.model.Weather
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.navigation.Screen
import com.maggie.app.ui.screens.home.HomeModules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

/** The Digest du matin screen (MAG-710): the order of the page, only cards that speak, each opening its module. */
@RunWith(AndroidJUnit4::class)
class DigestScreenTest {
    @get:Rule
    val compose = ScreenRule()

    private val full = DigestUiState(
        isLoading = false,
        date = LocalDate.of(2026, 10, 27),
        now = Instant.parse("2026-10-27T06:30:00Z"),
        voice = ChatMessage(id = "m0", role = "assistant", content = "[warmly] Bonjour", audio = AudioAttachment(durationMs = 65_000, transcript = "Bonjour")),
        message = ChatMessage(id = "m1", role = "assistant", content = "[warmly] Bonjour, voici votre journée."),
        weather = Weather(minC = 5.0, maxC = 12.0, rainProbability = 20.0, windKmh = 15.0, condition = "partly_cloudy"),
        agenda = AgendaHomeState("2026-10-27", listOf(AgendaHomeEvent(id = "e1", summary = "Dentiste", startAt = "2026-10-27T09:00:00+01:00"))),
        tasks = HomeTasks(tasks = listOf(HomeTask("t1", "Appeler Paul", overdueDays = 1)), total = 1),
        cuisine = CuisineHomeState(listOf(CuisineHomeSlot("lunch", listOf(CuisineHomeMeal("ml", "Soupe", 2, listOf("Soupe")))), CuisineHomeSlot("dinner"))),
        finance = FinanceHomeState(week = FinanceHomeRing(spentCents = 20_000, expectedSpentCents = 30_000, plannedCents = 50_000), accountCount = 1),
    )

    private fun draw(ui: DigestUiState, opened: MutableList<String> = mutableListOf(), onBack: () -> Unit = {}, onChat: () -> Unit = {}) =
        compose.setContent { DigestContent(ui, onBack, { opened += it }, onChat) }

    private fun top(tag: String) = compose.onNodeWithTag(tag).getBoundsInRoot().top.value

    @Test
    fun `the page reads in order from the date to the cards`() {
        draw(full)

        compose.onNodeWithTag(UiTags.DIGEST_DATE).assertTextEquals("Mardi 27 octobre")
        compose.onNodeWithTag(UiTags.DIGEST_GREETING).assertTextEquals("Bonjour")
        compose.onNodeWithTag(UiTags.DIGEST_MESSAGE_TEXT).assertTextEquals("Bonjour, voici votre journée.")
        val tops = listOf(
            top(UiTags.DIGEST_DATE), top(UiTags.DIGEST_GREETING), top(UiTags.VOICE_PLAY), top(UiTags.DIGEST_MESSAGE_TEXT), top(UiTags.DIGEST_WEATHER),
            top(UiTags.homeCard(HomeModules.AGENDA)), top(UiTags.homeCard(HomeModules.TASKS)),
            top(UiTags.homeCard(HomeModules.CUISINE)), top(UiTags.homeCard(HomeModules.FINANCE)),
        )
        assertEquals(tops.sorted(), tops)
        compose.onNodeWithText("Répondre à Maggie…").assertIsDisplayed()
    }

    @Test
    fun `the cards say what the module has`() {
        draw(full)

        compose.onNodeWithText("Dentiste").assertIsDisplayed()
        compose.onNodeWithText("reportée hier").assertIsDisplayed()
        compose.onNodeWithText("Déjeuner").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.DIGEST_FINANCE_WEEK).assertIsDisplayed()
    }

    @Test
    fun `no voice tag and no informal address reach the page`() {
        draw(full)

        assertTrue(compose.onAllNodesWithText("[warmly]", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a card with nothing to say is not drawn`() {
        draw(full.copy(weather = null, agenda = AgendaHomeState("2026-10-27"), tasks = HomeTasks(), cuisine = CuisineHomeState(), finance = FinanceHomeState()))

        compose.onNodeWithTag(UiTags.DIGEST_WEATHER).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.homeCard(HomeModules.AGENDA)).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.homeCard(HomeModules.TASKS)).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.homeCard(HomeModules.CUISINE)).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.homeCard(HomeModules.FINANCE)).assertDoesNotExist()
    }

    @Test
    fun `each card opens its module`() {
        val opened = mutableListOf<String>()
        draw(full, opened)

        compose.onNodeWithTag(UiTags.homeCardHeader(HomeModules.AGENDA)).performClick()
        compose.onNodeWithTag(UiTags.homeCardHeader(HomeModules.TASKS)).performClick()
        compose.onNodeWithTag(UiTags.homeCardHeader(HomeModules.CUISINE)).performClick()
        compose.onNodeWithTag(UiTags.homeCardHeader(HomeModules.FINANCE)).performClick()

        assertEquals(listOf(Screen.Calendar.route, Screen.Tasks.route, Screen.Cookbook.route, Screen.FinanceDashboard.route), opened)
    }

    @Test
    fun `back and the answer bar do their work`() {
        var back = 0
        var chat = 0
        draw(full, onBack = { back++ }, onChat = { chat++ })

        compose.onNodeWithTag(UiTags.DIGEST_BACK).performClick()
        compose.onNodeWithTag(UiTags.CHAT_OPEN).performClick()

        assertEquals(1, back)
        assertEquals(1, chat)
    }
}
