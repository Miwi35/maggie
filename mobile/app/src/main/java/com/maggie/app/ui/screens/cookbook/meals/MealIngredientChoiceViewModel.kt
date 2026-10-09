package com.maggie.app.ui.screens.cookbook.meals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.MealGroceryIngredient
import com.maggie.app.data.repository.MealRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal const val PREVIEW_ERROR = "Impossible de charger les ingrédients du repas."
internal const val ADD_ERROR = "Les ingrédients n'ont pas été ajoutés aux courses. Le repas, lui, est bien planifié."

data class MealIngredientChoiceUiState(
    val isLoading: Boolean = true,
    val ingredients: List<MealGroceryIngredient> = emptyList(),
    /** Ingredient ids. A product in two recipe units is two lines but one choice, as the API takes it. */
    val selected: Set<String> = emptySet(),
    val isSending: Boolean = false,
    val isDone: Boolean = false,
    val error: String? = null,
) {
    val selectedCount: Int get() = selected.size
    val canSubmit: Boolean get() = selected.isNotEmpty() && !isSending && !isDone
}

/**
 * Which ingredients of a freshly planned meal go on the grocery list (MAG-297).
 *
 * The preview says what each line is and whether its stock makes it worth buying;
 * only those start ticked. The meal itself is already created: leaving the screen
 * without sending adds nothing and loses nothing.
 */
class MealIngredientChoiceViewModel(
    private val mealId: String,
    private val mealRepository: MealRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MealIngredientChoiceUiState())
    val uiState: StateFlow<MealIngredientChoiceUiState> = _uiState

    init {
        load()
    }

    fun load() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            mealRepository.groceryPreview(mealId)
                .onSuccess { preview ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            ingredients = preview.ingredients,
                            selected = preview.ingredients.filter { line -> line.suggested }
                                .map { line -> line.ingredientId }
                                .toSet(),
                        )
                    }
                }
                .onFailure {
                    _uiState.update { it.copy(isLoading = false, error = PREVIEW_ERROR) }
                }
        }
    }

    fun toggle(ingredientId: String) {
        _uiState.update {
            val selected = if (ingredientId in it.selected) it.selected - ingredientId else it.selected + ingredientId
            it.copy(selected = selected)
        }
    }

    fun selectAll() {
        _uiState.update { state -> state.copy(selected = state.ingredients.map { it.ingredientId }.toSet()) }
    }

    fun submit() {
        val state = _uiState.value
        if (!state.canSubmit) return
        // In the order of the preview, whatever order the ticks came in.
        val chosen = state.ingredients.map { it.ingredientId }.distinct().filter { it in state.selected }
        _uiState.update { it.copy(isSending = true, error = null) }
        viewModelScope.launch {
            mealRepository.addToGroceries(mealId, chosen)
                .onSuccess { _uiState.update { it.copy(isSending = false, isDone = true) } }
                .onFailure { _uiState.update { it.copy(isSending = false, error = ADD_ERROR) } }
        }
    }
}
