package com.maggie.app.ui.screens.cookbook.meals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.MealUpdateRequest
import com.maggie.app.data.model.Meal
import com.maggie.app.data.model.Recipe
import com.maggie.app.data.repository.MealRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MealDetailUiState(
    /** The meal as the week shows it; null until [MealDetailViewModel.open]. */
    val meal: Meal? = null,
    /** The menu being edited: recipe ids, in the order they were added. */
    val recipeIds: List<String> = emptyList(),
    /** The title typed. Blank: the meal is titled after its recipes. */
    val title: String = "",
    /** From the first tap on « Enregistrer » until the sheet closes — a second tap sends nothing. */
    val isSaving: Boolean = false,
    val isDeleting: Boolean = false,
    val error: String? = null,
) {
    val isBusy: Boolean get() = isSaving || isDeleting

    val hasChanges: Boolean
        get() = meal != null &&
            (recipeIds != meal.recipeIds() || title.trim() != (meal.customTitle ?: ""))
}

/**
 * The sheet of a meal planned (MAG-378): its menu — recipes added and removed — its title,
 * and its deletion. Saving and deleting are asked once whatever the number of taps: the state
 * lives here, in a ViewModel, as it does for the creation.
 */
class MealDetailViewModel(
    private val mealRepository: MealRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MealDetailUiState())
    val uiState: StateFlow<MealDetailUiState> = _uiState

    /** Starts from [meal] as it is now; whatever a previous opening left is dropped. */
    fun open(meal: Meal) {
        _uiState.value = MealDetailUiState(
            meal = meal,
            recipeIds = meal.recipeIds(),
            title = meal.customTitle ?: "",
        )
    }

    fun setTitle(title: String) {
        _uiState.update { it.copy(title = title) }
    }

    fun add(recipe: Recipe) {
        _uiState.update { state ->
            if (recipe.id in state.recipeIds) state else state.copy(recipeIds = state.recipeIds + recipe.id)
        }
    }

    fun remove(recipeId: String) {
        _uiState.update { state -> state.copy(recipeIds = state.recipeIds - recipeId) }
    }

    /** [onSaved] gets the meal as the API now has it. The sending state stays on: the sheet closes. */
    fun save(onSaved: (Meal) -> Unit) {
        val state = claim { it.copy(isSaving = true, error = null) } ?: return
        val request = MealUpdateRequest(
            recipes = state.recipeIds.map { "/api/recipes/$it" },
            customTitle = state.title.trim(),
        )
        viewModelScope.launch {
            mealRepository.updateMeal(state.meal!!.id, request)
                .onSuccess(onSaved)
                .onFailure { failure ->
                    _uiState.update {
                        it.copy(isSaving = false, error = failure.message ?: "Le repas n'a pas pu être enregistré.")
                    }
                }
        }
    }

    /** [onDeleted] runs once the API has deleted it. On failure the meal stays, and [MealDetailUiState.error] says why. */
    fun delete(onDeleted: () -> Unit) {
        val state = claim { it.copy(isDeleting = true, error = null) } ?: return
        viewModelScope.launch {
            mealRepository.deleteMeal(state.meal!!.id)
                .onSuccess { onDeleted() }
                .onFailure { failure ->
                    _uiState.update {
                        it.copy(isDeleting = false, error = failure.message ?: "Le repas n'a pas pu être supprimé.")
                    }
                }
        }
    }

    // Checks and sets in one atomic step on the main thread: a second tap finds it already taken.
    private inline fun claim(taking: (MealDetailUiState) -> MealDetailUiState): MealDetailUiState? {
        var taken: MealDetailUiState? = null
        _uiState.update { state ->
            if (state.meal == null || state.isBusy) {
                taken = null
                state
            } else {
                taking(state).also { taken = it }
            }
        }
        return taken
    }
}

/** The ids behind the recipes' IRIs (`/api/recipes/{id}`). */
fun Meal.recipeIds(): List<String> = recipes.map { it.substringAfterLast('/') }
