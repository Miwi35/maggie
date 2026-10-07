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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** The fields of the edit sheet, as the owner types them. */
data class RecipeForm(
    val name: String = "",
    val servings: String = "",
    val tagsText: String = "",
    val notes: String = "",
    val ingredients: List<IngredientRow> = emptyList(),
) {
    fun toRequest(): JsonObject {
        val tags = tagsText.split(",").map { it.trim() }.filter { it.isNotBlank() }
        return buildJsonObject {
            put("name", name)
            put("servings", servings.toIntOrNull() ?: 4)
            putJsonArray("tags") { tags.forEach { add(JsonPrimitive(it)) } }
            put("notes", notes.ifBlank { null })
            putJsonArray("ingredients") {
                ingredients
                    .filter { (it.ciqualAlimCode.isNotBlank() || it.ingredientId.isNotBlank()) && it.quantity.isNotBlank() }
                    .forEach { row ->
                        addJsonObject {
                            if (row.ciqualAlimCode.isNotBlank()) {
                                put("ciqualAlimCode", row.ciqualAlimCode)
                            } else {
                                put("ingredientId", row.ingredientId)
                            }
                            put("quantity", row.quantity.toFloatOrNull() ?: 0f)
                            put("unit", row.unit.name.lowercase())
                        }
                    }
            }
        }
    }

    companion object {
        fun of(recipe: Recipe) = RecipeForm(
            name = recipe.name,
            servings = recipe.servings.toString(),
            tagsText = recipe.tags.joinToString(", "),
            notes = recipe.notes ?: "",
            ingredients = recipe.ingredients.map { ing ->
                IngredientRow(
                    ciqualAlimCode = ing.ciqualAlimCode ?: "",
                    ciqualFoodName = ing.ingredientName ?: "",
                    ingredientId = ing.ingredientId(),
                    quantity = ing.quantity.toString(),
                    unit = ing.unit,
                )
            },
        )
    }
}

data class RecipeEditUiState(
    val isLoading: Boolean = true,
    val form: RecipeForm = RecipeForm(),
    /** The recipe changed elsewhere under a field being edited: that field was kept, the owner may reload. */
    val changedElsewhere: Boolean = false,
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

    init {
        load()
        subscribeToMercure()
    }

    fun onName(value: String) = edit { it.copy(name = value) }
    fun onServings(value: String) = edit { it.copy(servings = value) }
    fun onTags(value: String) = edit { it.copy(tagsText = value) }
    fun onNotes(value: String) = edit { it.copy(notes = value) }
    fun onIngredients(value: List<IngredientRow>) = edit { it.copy(ingredients = value) }

    /** Drops the changes in progress for what the server now holds. */
    fun reload() {
        val current = recipe ?: return
        _uiState.value = _uiState.value.copy(form = RecipeForm.of(current), changedElsewhere = false)
    }

    private fun edit(change: (RecipeForm) -> RecipeForm) {
        _uiState.value = _uiState.value.copy(form = change(_uiState.value.form))
    }

    private fun load() {
        viewModelScope.launch {
            recipeRepository.getRecipe(recipeId).onSuccess { show(it) }
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
            is RecipeMessage.Changed -> recipe?.let { merge(message.patch.applyTo(it)) }
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
        )

        recipe = incoming
        _uiState.value = _uiState.value.copy(
            form = merged,
            changedElsewhere = _uiState.value.changedElsewhere || merged != now,
        )
    }
}
