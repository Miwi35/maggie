package com.maggie.app.ui.screens.cookbook.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.Ingredient
import com.maggie.app.data.model.Recipe
import com.maggie.app.data.model.RecipeIngredient
import com.maggie.app.data.repository.HealthProfileRepository
import com.maggie.app.data.repository.IngredientRepository
import com.maggie.app.data.repository.RecipeRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal const val SIMULATION_DEBOUNCE_MS = 300L
internal const val MAX_SIMULATED_GUESTS = 999
internal const val SIMULATION_ERROR = "Impossible de calculer les quantités pour ce nombre de personnes."

/**
 * « Pour N personnes » (MAG-483): the quantities the API scaled to [guests]. Nothing is stored.
 * [ingredients] is null while no answer is held — and after a failed one, so the sheet falls back
 * to the recipe's own quantities and says why in [error].
 */
data class RecipeSimulation(
    val guests: Int,
    val ingredients: List<RecipeIngredient>? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

data class RecipeDetailUiState(
    val recipe: Recipe? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val simulation: RecipeSimulation? = null,
    /** The catalogue's ingredients by id: what a serving brings is counted from their values. */
    val products: Map<String, Ingredient> = emptyMap(),
    /** The day's need of Santé › Configuration (MAG-707), the ring's reference; null without a complete profile. */
    val dailyKcal: Int? = null,
) {
    /** « Par personne »: from the recipe's own quantities and servings, whatever the simulation shows. */
    val nutrition: RecipeNutrition? get() = recipe?.let { nutritionPerServing(it.ingredients, it.servings, products) }

    /** What the sheet lists: the scaled quantities during a simulation, the recipe's own otherwise. */
    val ingredients: List<RecipeIngredient> get() = simulation?.ingredients ?: recipe?.ingredients.orEmpty()
}

/** The open recipe sheet: it follows the recipe as it changes elsewhere, without a pull-to-refresh. */
class RecipeDetailViewModel(
    private val recipeId: String,
    private val recipeRepository: RecipeRepository,
    private val ingredientRepository: IngredientRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
    private val healthProfileRepository: HealthProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecipeDetailUiState())
    val uiState: StateFlow<RecipeDetailUiState> = _uiState

    // A change published while the first fetch was in flight may be newer than its answer.
    private var changedBeforeLoad = false

    init {
        load()
        loadProducts()
        loadDailyNeed()
        subscribeToMercure()
    }

    private var simulationJob: Job? = null

    /**
     * « − » and « + » of « Pour N personnes »: starts from the recipe's servings, never under one.
     * Quick taps send one request: the previous one is cancelled, and a pause precedes the next.
     */
    fun stepGuests(delta: Int) {
        val recipe = _uiState.value.recipe ?: return
        val current = _uiState.value.simulation
        val guests = ((current?.guests ?: recipe.servings) + delta).coerceIn(1, MAX_SIMULATED_GUESTS)
        simulate(guests, current?.ingredients)
    }

    /** « Parts de la recette »: back to the recipe's own quantities. */
    fun resetSimulation() {
        simulationJob?.cancel()
        _uiState.update { it.copy(simulation = null) }
    }

    private fun simulate(guests: Int, keeping: List<RecipeIngredient>?) {
        simulationJob?.cancel()
        _uiState.update { it.copy(simulation = RecipeSimulation(guests, keeping, isLoading = true)) }
        simulationJob = viewModelScope.launch {
            delay(SIMULATION_DEBOUNCE_MS)
            val result = recipeRepository.getScaledRecipe(recipeId, guests)
            ensureActive()
            result
                .onSuccess { scaled ->
                    _uiState.update { it.copy(simulation = RecipeSimulation(guests, scaled.ingredients)) }
                }
                .onFailure {
                    _uiState.update { it.copy(simulation = RecipeSimulation(guests, error = SIMULATION_ERROR)) }
                }
        }
    }

    private fun load() {
        viewModelScope.launch {
            var result = recipeRepository.getRecipe(recipeId)
            if (changedBeforeLoad) {
                changedBeforeLoad = false
                result = recipeRepository.getRecipe(recipeId)
            }
            result
                .onSuccess { recipe -> _uiState.update { it.copy(recipe = recipe, isLoading = false, error = null, simulation = null) } }
                .onFailure { failure -> _uiState.update { it.copy(isLoading = false, error = failure.message) } }
        }
    }

    // A catalogue that cannot be read only leaves « Par personne » out: the sheet is still the recipe.
    private fun loadProducts() {
        viewModelScope.launch {
            ingredientRepository.getIngredients().onSuccess { ingredients ->
                _uiState.update { it.copy(products = ingredients.associateBy(Ingredient::id)) }
            }
        }
    }

    // A profile that cannot be read, or is incomplete, only leaves the ring out (MAG-707).
    private fun loadDailyNeed() {
        viewModelScope.launch {
            healthProfileRepository.getProfile().onSuccess { profile ->
                _uiState.update { it.copy(dailyKcal = profile.needKcal) }
            }
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
            RecipeMessage.Deleted -> _uiState.update { RecipeDetailUiState(isLoading = false, error = "Cette recette a été supprimée.", products = it.products, dailyKcal = it.dailyKcal) }
            RecipeMessage.Unreadable -> recipeRepository.getRecipe(recipeId)
                .onSuccess { recipe -> _uiState.update { it.copy(recipe = recipe, isLoading = false, error = null, simulation = null) } }
            is RecipeMessage.Changed -> _uiState.value.recipe?.let { current ->
                _uiState.value = _uiState.value.copy(recipe = message.patch.applyTo(current))
                _uiState.value.simulation?.let { simulate(it.guests, it.ingredients) }
            } ?: run { changedBeforeLoad = true }
        }
    }
}
