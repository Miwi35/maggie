package com.maggie.app.ui.screens.cookbook.ingredients

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.Ingredient
import com.maggie.app.data.repository.IngredientRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class IngredientListUiState(
    val ingredients: List<Ingredient> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val query: String = "",
    val pill: IngredientPill = IngredientPill.ALL,
) {
    val visible: List<Ingredient>
        get() = filterIngredients(ingredients, query, pill)
}

class IngredientListViewModel(
    private val ingredientRepository: IngredientRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(IngredientListUiState())
    val uiState: StateFlow<IngredientListUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            ingredientRepository.getIngredients()
                .onSuccess { list -> _uiState.update { it.copy(ingredients = list, isLoading = false) } }
                .onFailure { e -> _uiState.update { it.copy(error = e.message, isLoading = false) } }
        }
    }

    fun search(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    fun filterBy(pill: IngredientPill) {
        _uiState.update { it.copy(pill = pill) }
    }
}
