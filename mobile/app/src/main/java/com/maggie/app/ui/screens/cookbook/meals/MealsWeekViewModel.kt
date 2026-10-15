package com.maggie.app.ui.screens.cookbook.meals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalescedAndRechecked
import com.maggie.app.data.model.Meal
import com.maggie.app.data.model.MealSlot
import com.maggie.app.data.repository.MealRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

data class MealsWeekUiState(
    val weekStart: LocalDate = LocalDate.now().with(WeekFields.of(Locale.FRANCE).dayOfWeek(), 1),
    val mealsByDaySlot: Map<LocalDate, Map<MealSlot, List<Meal>>> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String? = null,
    /** The meal whose deletion is in flight: asked once, never twice. */
    val deletingMealId: String? = null,
    /** Why a meal could not be deleted; the meal is still there. */
    val deleteError: String? = null,
)

class MealsWeekViewModel(
    private val mealRepository: MealRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MealsWeekUiState())
    val uiState: StateFlow<MealsWeekUiState> = _uiState

    init {
        refresh()
        subscribeToMercure()
    }

    fun previousWeek() {
        _uiState.update { it.copy(weekStart = it.weekStart.minusWeeks(1)) }
        refresh()
    }

    fun nextWeek() {
        _uiState.update { it.copy(weekStart = it.weekStart.plusWeeks(1)) }
        refresh()
    }

    fun refresh() = load(quiet = false)

    /**
     * Deletes the meal; it leaves the week at once on success. On failure it stays, and
     * [MealsWeekUiState.deleteError] says why.
     */
    fun deleteMeal(meal: Meal) {
        var alreadyDeleting = false
        _uiState.update { state ->
            alreadyDeleting = state.deletingMealId != null
            if (alreadyDeleting) state else state.copy(deletingMealId = meal.id, deleteError = null)
        }
        if (alreadyDeleting) return

        viewModelScope.launch {
            mealRepository.deleteMeal(meal.id)
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            mealsByDaySlot = state.mealsByDaySlot.without(meal.id),
                            deletingMealId = null,
                        )
                    }
                }
                .onFailure { failure ->
                    _uiState.update {
                        it.copy(
                            deletingMealId = null,
                            deleteError = failure.message ?: "Le repas n'a pas pu être supprimé.",
                        )
                    }
                }
        }
    }

    /** A meal the sheet saved or deleted replaces or leaves the week at once: the index serving the week may not have caught up yet. */
    fun mealSaved(meal: Meal) {
        _uiState.update { state ->
            state.copy(
                mealsByDaySlot = state.mealsByDaySlot.mapValues { (_, slots) ->
                    slots.mapValues { (_, meals) -> meals.map { if (it.id == meal.id) meal else it } }
                },
            )
        }
    }

    fun mealDeleted(mealId: String) {
        _uiState.update { it.copy(mealsByDaySlot = it.mealsByDaySlot.without(mealId)) }
    }

    fun dismissDeleteError() {
        _uiState.update { it.copy(deleteError = null) }
    }

    // A quiet load (a change announced elsewhere) swaps the meals in place: the spinner would
    // replace the grid and send it back to the top.
    private fun load(quiet: Boolean) {
        viewModelScope.launch {
            val weekStart = _uiState.value.weekStart
            val weekEnd = weekStart.plusDays(6)
            if (!quiet) _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                // Days, both ends included. A meal is a day and a slot
                // (MAG-251): there is no instant to convert, so there is no
                // offset to get wrong — which is what put meals on the day
                // before.
                val meals = mealRepository.getMeals(
                    fromDay = weekStart.toString(),
                    toDay = weekEnd.toString(),
                ).getOrThrow()

                val grouped = meals.groupBy { meal ->
                    LocalDate.parse(meal.date)
                }.mapValues { (_, dayMeals) ->
                    dayMeals.groupBy { it.slot }
                }

                _uiState.update { it.copy(mealsByDaySlot = grouped, isLoading = false) }
            } catch (e: Exception) {
                if (quiet) return@launch
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.MEALS))
                .coalescedAndRechecked()
                .catch { /* SSE reconnects automatically */ }
                .collect { load(quiet = true) }
        }
    }

    private fun Map<LocalDate, Map<MealSlot, List<Meal>>>.without(
        mealId: String,
    ): Map<LocalDate, Map<MealSlot, List<Meal>>> =
        mapValues { (_, slots) -> slots.mapValues { (_, meals) -> meals.filterNot { it.id == mealId } } }
}
