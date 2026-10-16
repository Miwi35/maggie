package com.maggie.app.ui.screens.cookbook.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.repository.RecipeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class RecipeCreateUiState(
    val form: RecipeForm = RecipeForm(servings = "4"),
    val isSaving: Boolean = false,
    val error: String? = null,
    val stepErrors: Map<Int, String> = emptyMap(),
)

/** The new-recipe sheet: the same form as the edit sheet, sent once as a creation. */
class RecipeCreateViewModel(
    private val recipeRepository: RecipeRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecipeCreateUiState())
    val uiState: StateFlow<RecipeCreateUiState> = _uiState

    fun edit(change: (RecipeForm) -> RecipeForm) {
        val before = _uiState.value.form
        val after = change(before)
        _uiState.value = _uiState.value.copy(
            form = after,
            error = null,
            stepErrors = _uiState.value.stepErrors.stillValid(before, after),
        )
    }

    fun addStep() = edit { it.addStep() }
    fun removeStep(key: Int) = edit { it.removeStep(key) }
    fun moveStep(key: Int, delta: Int) = edit { it.moveStep(key, delta) }

    /** Sends the recipe; [onSaved] runs once the API accepted it. A refusal stays on the sheet, on its step when it names one. */
    fun save(onSaved: () -> Unit) {
        if (_uiState.value.isSaving) return
        val sent = _uiState.value.form
        _uiState.value = _uiState.value.copy(isSaving = true, error = null, stepErrors = emptyMap())
        viewModelScope.launch {
            recipeRepository.createRecipe(sent.toRequest())
                .onSuccess {
                    _uiState.value = _uiState.value.copy(isSaving = false)
                    onSaved()
                }
                .onFailure { failure ->
                    val refusal = RecipeRefusal.of(sent, failure)
                    _uiState.value = _uiState.value.copy(isSaving = false, error = refusal.general, stepErrors = refusal.steps)
                }
        }
    }
}
