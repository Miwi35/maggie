package com.maggie.app.ui.screens.home

import com.maggie.app.data.model.HomeDay
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Accueil holds the day of its selector (MAG-560) and the cards each format draws, from the layout file (MAG-588). */
class HomeViewModelTest {

    private val viewModel = HomeViewModel()

    @Test
    fun `the Accueil opens on today`() {
        assertEquals(HomeDay.TODAY, viewModel.uiState.value.day)
    }

    @Test
    fun `choosing Demain moves the day to tomorrow`() {
        viewModel.selectDay(HomeDay.TOMORROW)

        assertEquals(HomeDay.TOMORROW, viewModel.uiState.value.day)
    }

    @Test
    fun `choosing Aujourd'hui again brings the day back to today`() {
        viewModel.selectDay(HomeDay.TOMORROW)
        viewModel.selectDay(HomeDay.TODAY)

        assertEquals(HomeDay.TODAY, viewModel.uiState.value.day)
    }

    @Test
    fun `the days are spelled as the API reads them`() {
        assertEquals("today", HomeDay.TODAY.apiValue)
        assertEquals("tomorrow", HomeDay.TOMORROW.apiValue)
    }

    @Test
    fun `the default layout stacks the cards in the spec's order for the phone and the tablet`() {
        val spec = listOf("tasks", "agenda", "grocery", "cuisine", "health", "finance", "leisure")

        assertEquals(spec, viewModel.cards(HomeFormat.PHONE))
        assertEquals(spec, viewModel.cards(HomeFormat.TABLET))
        assertEquals(emptyList<String>(), viewModel.cards(HomeFormat.KIOSK))
    }

    @Test
    fun `changing the order of a format's section changes that format only`() {
        val phone = listOf(HomeCardSetting("tasks"), HomeCardSetting("agenda"))
        val reordered = layoutOf(phone = phone, tablet = phone.reversed())

        assertEquals(listOf("tasks", "agenda"), HomeViewModel(reordered).cards(HomeFormat.PHONE))
        assertEquals(listOf("agenda", "tasks"), HomeViewModel(reordered).cards(HomeFormat.TABLET))
    }

    @Test
    fun `disabling a card in a format's section hides it in that format only`() {
        val phone = listOf(HomeCardSetting("tasks"), HomeCardSetting("agenda"))
        val tablet = listOf(HomeCardSetting("tasks"), HomeCardSetting("agenda", enabled = false))
        val layout = HomeViewModel(layoutOf(phone = phone, tablet = tablet))

        assertEquals(listOf("tasks", "agenda"), layout.cards(HomeFormat.PHONE))
        assertEquals(listOf("tasks"), layout.cards(HomeFormat.TABLET))
    }

    @Test
    fun `the launch cards head the screen of a format, a key named twice is drawn once`() {
        val file = HomeLayoutFile(
            mapOf(
                HomeFormat.PHONE to mapOf(
                    HomeSlot.LAUNCH to listOf(HomeCardSetting("health")),
                    HomeSlot.CARD to listOf(HomeCardSetting("tasks"), HomeCardSetting("health")),
                    HomeSlot.KIOSK_TILE to listOf(HomeCardSetting("finance")),
                ),
            ),
        )

        assertEquals(listOf("health", "tasks"), HomeViewModel(file).cards(HomeFormat.PHONE))
        assertEquals(listOf("finance"), file.cards(HomeFormat.PHONE, HomeSlot.KIOSK_TILE))
    }

    private fun layoutOf(phone: List<HomeCardSetting>, tablet: List<HomeCardSetting>) = HomeLayoutFile(
        mapOf(
            HomeFormat.PHONE to mapOf(HomeSlot.CARD to phone),
            HomeFormat.TABLET to mapOf(HomeSlot.CARD to tablet),
        ),
    )
}
