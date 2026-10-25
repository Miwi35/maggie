package com.maggie.app.ui.screens.cookbook.cooking

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.Recipe
import com.maggie.app.data.model.RecipeStep
import com.maggie.app.data.repository.MealRepository
import com.maggie.app.data.repository.RecipeRepository
import com.maggie.app.ui.navigation.DeepLinks
import com.maggie.app.ui.screens.cookbook.meals.recipeIds
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

enum class CookingLoad {
    LOADING,

    /** The API is unreachable: « Réessayer », and the activity never closes by itself. */
    ERROR,

    /** A meal with no recipe, or an address that says nothing to cook. */
    EMPTY,
    READY,
}

data class CookingUiState(
    val load: CookingLoad = CookingLoad.LOADING,
    val session: CookingSession? = null,
    /** The recipes of the queue by id, scaled to the session's servings once the preparation is reached. */
    val recipes: Map<String, Recipe> = emptyMap(),
    /** True while the recipes of the current servings are still on their way. */
    val refreshing: Boolean = false,
) {
    val recipe: Recipe? get() = session?.current?.let { recipes[it.recipeId] }

    val steps: List<RecipeStep> get() = recipe?.orderedSteps().orEmpty()

    /** The earlier steps the current step still waits for; empty on the preparation. */
    val pending: List<Int>
        get() = session?.takeIf { it.phase == CookingPhase.COOKING && it.step > 0 }
            ?.let { pendingWaits(steps, it.step, it.ready) }
            .orEmpty()
}

/**
 * The Cuisine activity (MAG-580): reads what it cooks (`GET /api/meals/{id}`, `GET /api/recipes/{id}?guests=N`)
 * and keeps the [CookingSession] in the [SavedStateHandle], so a rotation resumes on the same recipe, step and guests.
 * The origin comes from the navigation arguments: `meal` or `recipe`.
 */
class CookingViewModel(
    private val savedState: SavedStateHandle,
    private val mealRepository: MealRepository,
    private val recipeRepository: RecipeRepository,
) : ViewModel() {

    // Ids go into API paths: a link carrying anything else cooks nothing.
    private val origin: CookingOrigin? = savedState.get<String>(DeepLinks.ARG_MEAL)?.takeIf(DeepLinks::isValidId)
        ?.let { CookingOrigin(CookingOrigin.Kind.MEAL, it) }
        ?: savedState.get<String>(DeepLinks.ARG_RECIPE)?.takeIf(DeepLinks::isValidId)
            ?.let { CookingOrigin(CookingOrigin.Kind.RECIPE, it) }

    private val _uiState = MutableStateFlow(CookingUiState())
    val uiState: StateFlow<CookingUiState> = _uiState

    private var startJob: Job? = null
    private var recipesJob: Job? = null

    /** What the recipes on screen were read for: `id:servings` per recipe, servings empty before « Pour combien ? » is answered. */
    private var loadedKey = ""

    init {
        start()
    }

    fun retry() = start()

    fun setGuests(guests: Int) = change { it.withGuests(guests) }

    fun confirmServings() = change { it.confirmServings() }

    fun previous() = change { it.back() }

    fun skipRecipe() = change { it.skipRecipe() }

    /** The next step; held back while the current one waits for a timed step, until « C'est prêt ». */
    fun next() {
        val state = _uiState.value
        if (state.recipe == null || state.pending.isNotEmpty()) return
        change { it.advance(state.steps.size) }
    }

    /** « C'est prêt »: what the current step waits for no longer holds it back. */
    fun ready() {
        val pending = _uiState.value.pending
        if (pending.isEmpty()) return
        change { it.markReady(pending) }
    }

    private fun start() {
        startJob?.cancel()
        recipesJob?.cancel()
        loadedKey = ""
        _uiState.update { it.copy(load = CookingLoad.LOADING, refreshing = false) }

        startJob = viewModelScope.launch {
            val launch = origin
            if (launch == null) {
                _uiState.update { it.copy(load = CookingLoad.EMPTY) }
                return@launch
            }

            val stored = restore()?.takeIf { it.resumes(launch) }
            if (stored != null) {
                _uiState.update { it.copy(session = stored, load = CookingLoad.READY) }
                syncRecipes()
                return@launch
            }

            when (launch.kind) {
                CookingOrigin.Kind.MEAL -> {
                    val meal = mealRepository.getMeal(launch.id).getOrElse { return@launch fail() }
                    val ids = meal.recipeIds()
                    if (ids.isEmpty()) {
                        _uiState.update { it.copy(load = CookingLoad.EMPTY) }
                        return@launch
                    }
                    publish(mealSession(launch.id, ids, meal.guests))
                }

                CookingOrigin.Kind.RECIPE -> {
                    val recipe = recipeRepository.getRecipe(launch.id).getOrElse { return@launch fail() }
                    val session = recipeSession(launch.id, recipe.servings)
                    loadedKey = keyOf(session)
                    _uiState.update { it.copy(recipes = mapOf(recipe.id to recipe)) }
                    publish(session)
                }
            }
            _uiState.update { it.copy(load = CookingLoad.READY) }
            syncRecipes()
        }
    }

    private fun change(transform: (CookingSession) -> CookingSession) {
        val current = _uiState.value.session ?: return
        val next = transform(current)
        if (next == current) return
        publish(next)
        syncRecipes()
    }

    private fun publish(session: CookingSession) {
        savedState[KEY_SESSION] = Json.encodeToString(CookingSession.serializer(), session)
        _uiState.update { it.copy(session = session) }
    }

    private fun restore(): CookingSession? = savedState.get<String>(KEY_SESSION)?.let { raw ->
        runCatching { Json.decodeFromString(CookingSession.serializer(), raw) }.getOrNull()
    }

    private fun fail() {
        _uiState.update { it.copy(load = CookingLoad.ERROR, refreshing = false) }
    }

    private fun keyOf(session: CookingSession): String =
        if (session.phase == CookingPhase.DONE) "" else session.queue.joinToString("|") { entry ->
            "${entry.recipeId}:${if (session.phase == CookingPhase.SERVINGS) "" else entry.servings}"
        }

    /** Reads the queue again when the number of people changed; a quick run of − / + only sends the last one. */
    private fun syncRecipes() {
        val session = _uiState.value.session ?: return
        val wanted = keyOf(session)
        if (wanted.isEmpty() || wanted == loadedKey) return
        recipesJob?.cancel()
        _uiState.update { it.copy(refreshing = true) }

        recipesJob = viewModelScope.launch {
            delay(SCALE_DEBOUNCE_MS)
            val scaled = session.phase == CookingPhase.COOKING
            val fetched = coroutineScope {
                session.queue.map { entry ->
                    async {
                        val result = if (scaled) {
                            recipeRepository.getScaledRecipe(entry.recipeId, entry.servings)
                        } else {
                            recipeRepository.getRecipe(entry.recipeId)
                        }
                        result.getOrElse { return@async null }
                    }
                }.awaitAll()
            }
            if (fetched.any { it == null }) {
                fail()
                return@launch
            }
            loadedKey = wanted
            _uiState.update { state ->
                state.copy(recipes = state.recipes + fetched.filterNotNull().associateBy { it.id }, refreshing = false)
            }
        }
    }

    companion object {
        private const val KEY_SESSION = "cooking-session"
        private const val SCALE_DEBOUNCE_MS = 200L
    }
}
