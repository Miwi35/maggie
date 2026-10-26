package com.maggie.app.ui.screens.cookbook.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.Ingredient
import com.maggie.app.data.model.Recipe
import com.maggie.app.data.repository.IngredientRepository
import com.maggie.app.data.repository.RecipeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Normalizer

data class RecipeListUiState(
    val recipes: List<Recipe> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    /** What the search field holds: matched against the names, without case or accents. */
    val query: String = "",
    /** The tag of the pill that is on; null is « Toutes ». */
    val tag: String? = null,
    /** The server's recipes with their ingredients and steps (the local copy has neither), by id. */
    val details: Map<String, Recipe> = emptyMap(),
    /** The catalogue's ingredients by id: the kcal of a recipe is counted from their values. */
    val products: Map<String, Ingredient> = emptyMap(),
) {
    /** One pill per tag the recipes carry, in alphabetical order: nothing is offered that no recipe has. */
    val tags: List<String>
        get() = recipes.flatMap { it.tags }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sortedBy { it.fold() }

    /** The recipes the search and the pill let through. */
    val visible: List<Recipe>
        get() = recipes.filter { recipe ->
            (tag == null || tag in recipe.tags) && recipe.name.fold().contains(query.trim().fold())
        }

    /** « 515 kcal · 35 min »: each part only when it is known; null when neither is. */
    fun facts(recipe: Recipe): String? {
        val full = details[recipe.id] ?: return null
        val kcal = nutritionPerServing(full.ingredients, full.servings, products)?.kcal
        val minutes = full.steps.sumOf { it.durationMinutes ?: 0 }
        return listOfNotNull(
            kcal?.let { "$it kcal" },
            if (minutes > 0) formatMinutes(minutes) else null,
        ).joinToString(" · ").ifEmpty { null }
    }
}

private fun String.fold(): String =
    Normalizer.normalize(lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

class RecipeListViewModel(
    private val recipeRepository: RecipeRepository,
    private val ingredientRepository: IngredientRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecipeListUiState())
    val uiState: StateFlow<RecipeListUiState> = _uiState

    init {
        observeRecipes()
        refresh()
        loadProducts()
        subscribeToMercure()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val server = recipeRepository.refreshRecipes().getOrThrow()
                _uiState.update { it.copy(isLoading = false, details = server.associateBy(Recipe::id)) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    fun search(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    /** A pill: the same one again turns it off, like « Toutes ». */
    fun filterBy(tag: String?) {
        _uiState.update { it.copy(tag = if (it.tag == tag) null else tag) }
    }

    private fun observeRecipes() {
        viewModelScope.launch {
            recipeRepository.observeRecipes().collect { recipes ->
                // A pill whose last recipe went away would leave an empty list and nothing to tap.
                _uiState.update { state ->
                    state.copy(
                        recipes = recipes,
                        tag = state.tag?.takeIf { tag -> recipes.any { tag in it.tags } },
                    )
                }
            }
        }
    }

    // A catalogue that cannot be read only leaves the kcal out: the list is still the recipes.
    private fun loadProducts() {
        viewModelScope.launch {
            ingredientRepository.getIngredients().onSuccess { ingredients ->
                _uiState.update { it.copy(products = ingredients.associateBy(Ingredient::id)) }
            }
        }
    }

    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.RECIPES))
                .catch { /* SSE reconnects automatically */ }
                .collect { refresh() }
        }
    }
}
