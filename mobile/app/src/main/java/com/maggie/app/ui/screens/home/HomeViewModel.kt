package com.maggie.app.ui.screens.home

import androidx.lifecycle.ViewModel
import com.maggie.app.data.model.HomeDay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** What the Accueil itself holds (MAG-560): the day of the selector. Each card keeps its own state. */
data class HomeUiState(val day: HomeDay = HomeDay.TODAY)

/**
 * The selector « Aujourd'hui · Demain », and the cards each format draws, from [layout]. Plain state, like the
 * other screens' ViewModels: the ViewModel outlives a rotation, and a new session starts on today.
 */
class HomeViewModel(private val layout: HomeLayoutFile = HomeLayout.file) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState

    /** The card keys the screen of [format] stacks, in order; the registry says which of them have a component. */
    fun cards(format: HomeFormat): List<String> = layout.screenCards(format)

    fun selectDay(day: HomeDay) {
        _uiState.update { it.copy(day = day) }
    }
}
