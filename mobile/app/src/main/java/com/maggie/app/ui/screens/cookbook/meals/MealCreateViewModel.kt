package com.maggie.app.ui.screens.cookbook.meals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.MealCreateRequest
import com.maggie.app.data.model.Recipe
import com.maggie.app.data.repository.MealRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MealCreateUiState(
    /** The menu: the recipes chosen so far, in the order they were added. */
    val recipes: List<Recipe> = emptyList(),
    /** From the first tap on « Créer » until the dialog closes — a second tap sends nothing. */
    val isSubmitting: Boolean = false,
    val error: String? = null,
)

/**
 * The meal being planned: its menu, and the one request that creates it (MAG-378).
 *
 * The sending state lives here and not in a lambda of the NavGraph: a lambda keeps no state
 * between two taps, so two quick taps on « Créer » created the meal twice.
 */
class MealCreateViewModel(
    private val mealRepository: MealRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MealCreateUiState())
    val uiState: StateFlow<MealCreateUiState> = _uiState

    fun add(recipe: Recipe) {
        _uiState.update { state ->
            if (state.recipes.any { it.id == recipe.id }) state else state.copy(recipes = state.recipes + recipe)
        }
    }

    fun remove(recipeId: String) {
        _uiState.update { state -> state.copy(recipes = state.recipes.filterNot { it.id == recipeId }) }
    }

    /**
     * [onCreated] gets the new meal's id and whether it has recipes (a meal with none has no
     * ingredient to choose from). The sending state stays on after a success: the dialog is
     * closed by [onCreated], and [reset] runs when it leaves the screen.
     */
    fun submit(date: String, slot: String, onCreated: (mealId: String, withRecipes: Boolean) -> Unit) {
        // Read and set in one atomic step on the main thread: the second tap finds it already set.
        var alreadySending = false
        _uiState.update { state ->
            alreadySending = state.isSubmitting
            if (alreadySending) state else state.copy(isSubmitting = true, error = null)
        }
        if (alreadySending) return

        val recipes = _uiState.value.recipes
        viewModelScope.launch {
            val request = MealCreateRequest(
                date = date,
                slot = slot,
                recipes = recipes.map { "/api/recipes/${it.id}" },
            )
            mealRepository.createMeal(request)
                .onSuccess { onCreated(it.id, recipes.isNotEmpty()) }
                .onFailure { failure ->
                    _uiState.update {
                        it.copy(isSubmitting = false, error = failure.message ?: "Le repas n'a pas pu être créé.")
                    }
                }
        }
    }

    fun reset() {
        _uiState.value = MealCreateUiState()
    }
}
