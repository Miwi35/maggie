package com.maggie.app.ui.screens.cookbook.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.Recipe
import com.maggie.app.data.repository.RecipeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class RecipeDetailUiState(
    val recipe: Recipe? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

/** The open recipe sheet: it follows the recipe as it changes elsewhere, without a pull-to-refresh. */
class RecipeDetailViewModel(
    private val recipeId: String,
    private val recipeRepository: RecipeRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecipeDetailUiState())
    val uiState: StateFlow<RecipeDetailUiState> = _uiState

    // A change published while the first fetch was in flight may be newer than its answer.
    private var changedBeforeLoad = false

    init {
        load()
        subscribeToMercure()
    }

    private fun load() {
        viewModelScope.launch {
            var result = recipeRepository.getRecipe(recipeId)
            if (changedBeforeLoad) {
                changedBeforeLoad = false
                result = recipeRepository.getRecipe(recipeId)
            }
            result
                .onSuccess { _uiState.value = RecipeDetailUiState(recipe = it, isLoading = false) }
                .onFailure { _uiState.value = RecipeDetailUiState(isLoading = false, error = it.message) }
        }
    }

    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.RECIPES))
                .catch { /* SSE reconnects automatically */ }
                .collect { onMessage(it.data) }
        }
    }

    private suspend fun onMessage(data: String) {
        when (val message = parseRecipeMessage(data, recipeId)) {
            RecipeMessage.Elsewhere -> Unit
            RecipeMessage.Deleted -> _uiState.value = RecipeDetailUiState(isLoading = false, error = "Cette recette a été supprimée.")
            RecipeMessage.Unreadable -> recipeRepository.getRecipe(recipeId)
                .onSuccess { _uiState.value = RecipeDetailUiState(recipe = it, isLoading = false) }
            is RecipeMessage.Changed -> _uiState.value.recipe?.let { current ->
                _uiState.value = _uiState.value.copy(recipe = message.patch.applyTo(current))
            } ?: run { changedBeforeLoad = true }
        }
    }
}
