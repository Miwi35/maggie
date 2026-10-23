package com.maggie.app.ui.screens.home

import com.maggie.app.data.model.HomeDay
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Accueil holds one thing, the day of its selector (MAG-560). */
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
}
