package com.maggie.app.ui.screens.cookbook.meals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.Meal
import com.maggie.app.data.model.MealSlot
import com.maggie.app.data.repository.MealRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

data class MealsWeekUiState(
    val weekStart: LocalDate = LocalDate.now().with(WeekFields.of(Locale.FRANCE).dayOfWeek(), 1),
    val mealsByDaySlot: Map<LocalDate, Map<MealSlot, List<Meal>>> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class MealsWeekViewModel(
    private val mealRepository: MealRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MealsWeekUiState())
    val uiState: StateFlow<MealsWeekUiState> = _uiState

    init {
        refresh()
    }

    fun previousWeek() {
        _uiState.value = _uiState.value.copy(weekStart = _uiState.value.weekStart.minusWeeks(1))
        refresh()
    }

    fun nextWeek() {
        _uiState.value = _uiState.value.copy(weekStart = _uiState.value.weekStart.plusWeeks(1))
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val weekStart = _uiState.value.weekStart
            val weekEnd = weekStart.plusDays(7)
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val meals = mealRepository.getMeals(
                    startAfter = weekStart.atStartOfDay(ZoneId.systemDefault()).toInstant().toString(),
                    startBefore = weekEnd.atStartOfDay(ZoneId.systemDefault()).toInstant().toString(),
                ).getOrThrow()

                val grouped = meals.groupBy { meal ->
                    Instant.parse(meal.startAt).atZone(ZoneId.systemDefault()).toLocalDate()
                }.mapValues { (_, dayMeals) ->
                    dayMeals.groupBy { it.slot }
                }

                _uiState.value = _uiState.value.copy(
                    mealsByDaySlot = grouped,
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
            }
        }
    }
}
