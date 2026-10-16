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

data class RecipeEditUiState(
    val isLoading: Boolean = true,
    val form: RecipeForm = RecipeForm(),
    /** The recipe changed elsewhere under a field being edited: that field was kept, the owner may reload. */
    val changedElsewhere: Boolean = false,
    val isSaving: Boolean = false,
    /** What the API refused that no step explains, or why the save failed. */
    val error: String? = null,
    /** The API's refusal of a step, by the step's key. */
    val stepErrors: Map<Int, String> = emptyMap(),
)

class RecipeEditViewModel(
    private val recipeId: String,
    private val recipeRepository: RecipeRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecipeEditUiState())
    val uiState: StateFlow<RecipeEditUiState> = _uiState

    // What the server last said: a field differing from it is being edited.
    private var recipe: Recipe? = null

    // A change published while the first fetch was in flight may be newer than its answer.
    private var changedBeforeLoad = false

    init {
        load()
        subscribeToMercure()
    }

    fun onName(value: String) = edit { it.copy(name = value) }
    fun onServings(value: String) = edit { it.copy(servings = value) }
    fun onTags(value: String) = edit { it.copy(tagsText = value) }
    fun onNotes(value: String) = edit { it.copy(notes = value) }
    fun onIngredients(value: List<IngredientRow>) = edit { it.withIngredients(value) }

    fun addStep() = edit { it.addStep() }
    fun removeStep(key: Int) = edit { it.removeStep(key) }
    fun moveStep(key: Int, delta: Int) = edit { it.moveStep(key, delta) }
    fun addUtensil(utensil: UtensilRef) = edit { it.addUtensil(utensil) }
    fun removeUtensil(utensil: UtensilRef) = edit { it.removeUtensil(utensil) }

    /** Sends the form; [onSaved] runs once the API accepted it. A refusal stays on the sheet, on its step when it names one. */
    fun save(onSaved: () -> Unit) {
        val sent = _uiState.value.form
        if (_uiState.value.isSaving) return
        _uiState.value = _uiState.value.copy(isSaving = true, error = null, stepErrors = emptyMap())
        viewModelScope.launch {
            recipeRepository.updateRecipe(recipeId, sent.toRequest())
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

    /** Drops the changes in progress for what the server now holds. */
    fun reload() {
        val current = recipe ?: return
        _uiState.value = _uiState.value.copy(form = RecipeForm.of(current), changedElsewhere = false)
    }

    /** Any change of the form; the refusal shown on a step goes when that step changes. */
    fun edit(change: (RecipeForm) -> RecipeForm) {
        val before = _uiState.value.form
        val after = change(before)
        _uiState.value = _uiState.value.copy(
            form = after,
            error = null,
            stepErrors = _uiState.value.stepErrors.stillValid(before, after),
        )
    }

    private fun load() {
        viewModelScope.launch {
            recipeRepository.getRecipe(recipeId).onSuccess { show(it) }
            if (changedBeforeLoad) {
                changedBeforeLoad = false
                recipeRepository.getRecipe(recipeId).onSuccess { show(it) }
            }
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    private fun show(loaded: Recipe) {
        recipe = loaded
        _uiState.value = _uiState.value.copy(form = RecipeForm.of(loaded), changedElsewhere = false)
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
            RecipeMessage.Elsewhere, RecipeMessage.Deleted -> Unit
            RecipeMessage.Unreadable -> recipeRepository.getRecipe(recipeId).onSuccess { merge(it) }
            is RecipeMessage.Changed -> recipe?.let { merge(message.patch.applyTo(it)) } ?: run { changedBeforeLoad = true }
        }
    }

    // Each field not touched follows the server; a field being edited keeps what is typed.
    private fun merge(incoming: Recipe) {
        val before = RecipeForm.of(recipe ?: return)
        val now = RecipeForm.of(incoming)
        val form = _uiState.value.form

        val merged = RecipeForm(
            name = if (form.name == before.name) now.name else form.name,
            servings = if (form.servings == before.servings) now.servings else form.servings,
            tagsText = if (form.tagsText == before.tagsText) now.tagsText else form.tagsText,
            notes = if (form.notes == before.notes) now.notes else form.notes,
            ingredients = if (form.ingredients == before.ingredients) now.ingredients else form.ingredients,
            utensils = if (form.utensils == before.utensils) now.utensils else form.utensils,
            steps = if (form.steps == before.steps) now.steps else form.steps,
        )
        // Only a field edited here AND changed there, to something else, is worth a warning.
        val conflict = (form.name != before.name && now.name != before.name && form.name != now.name) ||
            (form.servings != before.servings && now.servings != before.servings && form.servings != now.servings) ||
            (form.tagsText != before.tagsText && now.tagsText != before.tagsText && form.tagsText != now.tagsText) ||
            (form.notes != before.notes && now.notes != before.notes && form.notes != now.notes) ||
            (form.ingredients != before.ingredients && now.ingredients != before.ingredients && form.ingredients != now.ingredients) ||
            (form.utensils != before.utensils && now.utensils != before.utensils && form.utensils != now.utensils) ||
            (form.steps != before.steps && now.steps != before.steps && form.steps != now.steps)

        recipe = incoming
        _uiState.value = _uiState.value.copy(form = merged, changedElsewhere = _uiState.value.changedElsewhere || conflict)
    }
}
