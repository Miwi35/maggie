package com.maggie.app.ui.screens.cookbook.meals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.MealUpdateRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalescedAndRechecked
import com.maggie.app.data.model.Ingredient
import com.maggie.app.data.model.Meal
import com.maggie.app.data.model.MealSlot
import com.maggie.app.data.model.Recipe
import com.maggie.app.data.repository.IngredientRepository
import com.maggie.app.data.repository.MealRepository
import com.maggie.app.data.repository.RecipeRepository
import com.maggie.app.ui.screens.cookbook.recipes.MAX_SIMULATED_GUESTS
import com.maggie.app.ui.screens.cookbook.recipes.SIMULATION_DEBOUNCE_MS
import com.maggie.app.ui.screens.cookbook.recipes.SIMULATION_ERROR
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

data class MealsWeekUiState(
    val weekStart: LocalDate = LocalDate.now().with(WeekFields.of(Locale.FRANCE).dayOfWeek(), 1),
    val mealsByDaySlot: Map<LocalDate, Map<MealSlot, List<Meal>>> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String? = null,
    /** The meal whose deletion is in flight: asked once, never twice. */
    val deletingMealId: String? = null,
    /** Why a meal could not be deleted; the meal is still there. */
    val deleteError: String? = null,
    /** Each recipe of each meal of the week, with the portions it is simulated for. */
    val portions: Map<MealRecipeKey, RecipePortions> = emptyMap(),
    /** The catalogue's ingredients by id: what a person brings is counted from their values. */
    val products: Map<String, Ingredient> = emptyMap(),
    /** Why the convives of a meal could not be saved; the number is back to what it was. */
    val guestsError: String? = null,
)

/** A recipe in a meal: the same recipe in two meals has two simulations. */
data class MealRecipeKey(val mealId: String, val recipeId: String)

/**
 * « Pour N personnes » of one recipe of a meal: a simulation for the screen alone, nothing is saved
 * (MAG-628). [recipe] is what the API scaled to [guests] — null until its first answer, and kept
 * (with [error]) when a later one fails. A recipe that [followsMeal] takes the convives of its meal.
 */
data class RecipePortions(
    val guests: Int,
    val recipe: Recipe? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val followsMeal: Boolean = true,
)

class MealsWeekViewModel(
    private val mealRepository: MealRepository,
    private val recipeRepository: RecipeRepository,
    private val ingredientRepository: IngredientRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MealsWeekUiState())
    val uiState: StateFlow<MealsWeekUiState> = _uiState

    // Quick taps send one request: the previous one is cancelled, and a pause precedes the next.
    private val portionJobs = mutableMapOf<MealRecipeKey, Job>()
    private val guestsJobs = mutableMapOf<String, Job>()

    // The convives the API holds for a meal being stepped: what a refused save goes back to.
    private val savedGuests = mutableMapOf<String, Int>()

    init {
        refresh()
        loadProducts()
        subscribeToMercure()
    }

    fun previousWeek() {
        _uiState.update { it.copy(weekStart = it.weekStart.minusWeeks(1)) }
        refresh()
    }

    fun nextWeek() {
        _uiState.update { it.copy(weekStart = it.weekStart.plusWeeks(1)) }
        refresh()
    }

    fun refresh() = load(quiet = false)

    /**
     * Deletes the meal; it leaves the week at once on success. On failure it stays, and
     * [MealsWeekUiState.deleteError] says why.
     */
    fun deleteMeal(meal: Meal) {
        var alreadyDeleting = false
        _uiState.update { state ->
            alreadyDeleting = state.deletingMealId != null
            if (alreadyDeleting) state else state.copy(deletingMealId = meal.id, deleteError = null)
        }
        if (alreadyDeleting) return

        viewModelScope.launch {
            mealRepository.deleteMeal(meal.id)
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            mealsByDaySlot = state.mealsByDaySlot.without(meal.id),
                            deletingMealId = null,
                        )
                    }
                    syncPortions()
                }
                .onFailure { failure ->
                    _uiState.update {
                        it.copy(
                            deletingMealId = null,
                            deleteError = failure.message ?: "Le repas n'a pas pu être supprimé.",
                        )
                    }
                }
        }
    }

    /** A meal the sheet saved or deleted replaces or leaves the week at once: the index serving the week may not have caught up yet. */
    fun mealSaved(meal: Meal) {
        _uiState.update { state ->
            state.copy(
                mealsByDaySlot = state.mealsByDaySlot.mapValues { (_, slots) ->
                    slots.mapValues { (_, meals) -> meals.map { if (it.id == meal.id) meal else it } }
                },
            )
        }
        syncPortions()
    }

    fun mealDeleted(mealId: String) {
        _uiState.update { it.copy(mealsByDaySlot = it.mealsByDaySlot.without(mealId)) }
        syncPortions()
    }

    fun dismissDeleteError() {
        _uiState.update { it.copy(deleteError = null) }
    }

    fun dismissGuestsError() {
        _uiState.update { it.copy(guestsError = null) }
    }

    /**
     * « − » and « + » of a meal's convives: the number shows at once and is saved on the meal, never
     * under one. The recipes that follow the meal follow it.
     */
    fun stepGuests(mealId: String, delta: Int) {
        val meal = findMeal(mealId) ?: return
        val guests = (meal.guests + delta).coerceIn(1, MAX_SIMULATED_GUESTS)
        if (guests == meal.guests) return

        savedGuests.getOrPut(mealId) { meal.guests }
        setMealGuests(mealId, guests)
        followingKeys(mealId).forEach { rescale(it, guests, followsMeal = true) }

        guestsJobs[mealId]?.cancel()
        guestsJobs[mealId] = viewModelScope.launch {
            delay(SIMULATION_DEBOUNCE_MS)
            val result = mealRepository.updateMeal(mealId, MealUpdateRequest(guests = guests))
            ensureActive()
            result
                .onSuccess { saved ->
                    savedGuests.remove(mealId)
                    mealSaved(saved.copy(guests = guests, guestsIsDefault = false))
                }
                .onFailure { failure ->
                    val back = savedGuests.remove(mealId) ?: return@onFailure
                    setMealGuests(mealId, back)
                    followingKeys(mealId).forEach { rescale(it, back, followsMeal = true) }
                    _uiState.update { it.copy(guestsError = failure.message ?: "Les convives n'ont pas pu être enregistrés.") }
                }
        }
    }

    /**
     * « − » and « + » of a recipe's portions: a simulation for this screen, never saved. It starts at the
     * meal's convives and may go above or below them, never under one.
     */
    fun stepPortions(mealId: String, recipeId: String, delta: Int) {
        val key = MealRecipeKey(mealId, recipeId)
        val current = _uiState.value.portions[key] ?: return
        val guests = (current.guests + delta).coerceIn(1, MAX_SIMULATED_GUESTS)
        if (guests == current.guests) return
        rescale(key, guests, followsMeal = false)
    }

    private fun findMeal(mealId: String): Meal? =
        _uiState.value.mealsByDaySlot.values.asSequence().flatMap { it.values }.flatten().firstOrNull { it.id == mealId }

    private fun followingKeys(mealId: String): List<MealRecipeKey> =
        _uiState.value.portions.filter { (key, portions) -> key.mealId == mealId && portions.followsMeal }.keys.toList()

    private fun setMealGuests(mealId: String, guests: Int) {
        _uiState.update { state ->
            state.copy(
                mealsByDaySlot = state.mealsByDaySlot.mapValues { (_, slots) ->
                    slots.mapValues { (_, meals) ->
                        meals.map { if (it.id == mealId) it.copy(guests = guests, guestsIsDefault = false) else it }
                    }
                },
            )
        }
    }

    // [debounce] false is a first answer, asked at once; a tap waits for the next one.
    private fun rescale(key: MealRecipeKey, guests: Int, followsMeal: Boolean, debounce: Boolean = true) {
        portionJobs[key]?.cancel()
        _uiState.update { state ->
            val keeping = state.portions[key]?.recipe
            state.copy(portions = state.portions + (key to RecipePortions(guests, keeping, isLoading = true, followsMeal = followsMeal)))
        }
        portionJobs[key] = viewModelScope.launch {
            if (debounce) delay(SIMULATION_DEBOUNCE_MS)
            val result = recipeRepository.getScaledRecipe(key.recipeId, guests)
            ensureActive()
            _uiState.update { state ->
                val current = state.portions[key] ?: return@update state
                val answered = result.fold(
                    onSuccess = { current.copy(recipe = it, isLoading = false, error = null) },
                    onFailure = { current.copy(isLoading = false, error = SIMULATION_ERROR) },
                )
                state.copy(portions = state.portions + (key to answered))
            }
        }
    }

    // The simulations follow the week: one for each recipe of each meal in it, none for what left.
    // A recipe whose first answer failed is asked again by the next load.
    private fun syncPortions() {
        val meals = _uiState.value.mealsByDaySlot.values.flatMap { it.values }.flatten()
        val wanted = meals.flatMap { meal -> meal.recipeIds().map { MealRecipeKey(meal.id, it) to meal } }.toMap()

        _uiState.update { state -> state.copy(portions = state.portions.filterKeys { it in wanted }) }
        portionJobs.keys.filterNot { it in wanted }.forEach { portionJobs.remove(it)?.cancel() }

        wanted.forEach { (key, meal) ->
            val current = _uiState.value.portions[key]
            when {
                current == null -> rescale(key, meal.guests, followsMeal = true, debounce = false)
                current.followsMeal && current.guests != meal.guests && guestsJobs[meal.id]?.isActive != true ->
                    rescale(key, meal.guests, followsMeal = true, debounce = false)
                current.recipe == null && !current.isLoading -> rescale(key, current.guests, current.followsMeal, debounce = false)
            }
        }
    }

    // A catalogue that cannot be read only leaves the kcal out: the card is still the meal.
    private fun loadProducts() {
        viewModelScope.launch {
            ingredientRepository.getIngredients().onSuccess { ingredients ->
                _uiState.update { it.copy(products = ingredients.associateBy(Ingredient::id)) }
            }
        }
    }

    // A quiet load (a change announced elsewhere) swaps the meals in place: the spinner would
    // replace the grid and send it back to the top.
    private fun load(quiet: Boolean) {
        viewModelScope.launch {
            val weekStart = _uiState.value.weekStart
            val weekEnd = weekStart.plusDays(6)
            if (!quiet) _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                // Days, both ends included. A meal is a day and a slot
                // (MAG-251): there is no instant to convert, so there is no
                // offset to get wrong — which is what put meals on the day
                // before.
                val meals = mealRepository.getMeals(
                    fromDay = weekStart.toString(),
                    toDay = weekEnd.toString(),
                ).getOrThrow()

                // A meal whose convives are being saved keeps the number on screen: the answer predates the tap.
                val pending = savedGuests.keys.mapNotNull { findMeal(it) }.associateBy { it.id }
                val grouped = meals
                    .map { meal -> pending[meal.id]?.let { meal.copy(guests = it.guests, guestsIsDefault = false) } ?: meal }
                    .groupBy { meal -> LocalDate.parse(meal.date) }
                    .mapValues { (_, dayMeals) -> dayMeals.groupBy { it.slot } }

                _uiState.update { it.copy(mealsByDaySlot = grouped, isLoading = false) }
                syncPortions()
            } catch (e: Exception) {
                if (quiet) return@launch
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.MEALS))
                .coalescedAndRechecked()
                .catch { /* SSE reconnects automatically */ }
                .collect { load(quiet = true) }
        }
    }

    private fun Map<LocalDate, Map<MealSlot, List<Meal>>>.without(
        mealId: String,
    ): Map<LocalDate, Map<MealSlot, List<Meal>>> =
        mapValues { (_, slots) -> slots.mapValues { (_, meals) -> meals.filterNot { it.id == mealId } } }
}
