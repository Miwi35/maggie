package com.maggie.app.ui.screens.cookbook.cooking

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.CookingTimerRefusedException
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.CookingTimer
import com.maggie.app.data.model.CookingTimerAction
import com.maggie.app.data.model.CookingTimerStatus
import com.maggie.app.data.model.Recipe
import com.maggie.app.data.model.RecipeStep
import com.maggie.app.data.repository.ActivityStatusRepository
import com.maggie.app.data.repository.CookingTimerRepository
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CookingLoad {
    LOADING,

    /** The API is unreachable: « Réessayer », and the activity never closes by itself. */
    ERROR,

    /** A meal with no recipe, or an address that says nothing to cook. */
    EMPTY,

    /** Another Cuisine is in progress: « Reprendre » it or « Recommencer » with this one — never lost silently. */
    CONFLICT,
    READY,
}

data class CookingUiState(
    val load: CookingLoad = CookingLoad.LOADING,
    val session: CookingSession? = null,
    /** The Cuisine in progress on another origin, while [load] is [CookingLoad.CONFLICT]. */
    val conflict: CookingConflict? = null,
    /** The recipes of the queue by id, scaled to the session's servings once the preparation is reached. */
    val recipes: Map<String, Recipe> = emptyMap(),
    /** True while the recipes of the current servings are still on their way. */
    val refreshing: Boolean = false,
    /** The user's timers still on screen (MAG-582): running, paused, rung — as the API last said. */
    val timers: List<CookingTimer> = emptyList(),
    /** When each running timer ends, on this device's clock: read from `remainingSeconds` as it arrived. */
    val deadlines: Map<String, Long> = emptyMap(),
    /** The clock the screen counts down with, moved each second by [CookingViewModel.tick]. */
    val now: Long = 0L,
    /** A timer that could not be started or changed: the step stays where it is. */
    val timerError: String? = null,
    /** A timer is being started: the button does not send a second one. */
    val starting: Boolean = false,
    /** The API's timers were read once (or could not be): until then a step's timer may already run elsewhere. */
    val timersLoaded: Boolean = false,
    /** Steps (recipe id, position) a timer was started on, even if it was since acknowledged or cancelled. */
    val launched: Set<Pair<String, Int>> = emptySet(),
) {
    val recipe: Recipe? get() = session?.current?.let { recipes[it.recipeId] }

    val steps: List<RecipeStep> get() = recipe?.orderedSteps().orEmpty()

    /** Steps per recipe of the queue, by id; a recipe not read yet counts none. */
    val stepCounts: Map<String, Int>
        get() = session?.queue.orEmpty().associate { it.recipeId to (recipes[it.recipeId]?.steps?.size ?: 0) }

    /** Every step of the meal, recipe after recipe: « 6 / 11 faites ». */
    val totalSteps: Int get() = stepCounts.values.sum()

    val doneCount: Int get() = session?.doneCount(stepCounts) ?: 0

    /** The current step, null on the preparation. */
    val currentStep: RecipeStep?
        get() = session?.takeIf { it.phase == CookingPhase.COOKING && it.step > 0 }?.let { steps.getOrNull(it.step - 1) }

    /** The timer on screen for a step of a recipe: the last one started when there are several. */
    fun timerFor(recipeId: String, position: Int): CookingTimer? =
        timers.lastOrNull { it.recipeId == recipeId && it.stepPosition == position }

    /** The timer of a step of the current recipe. */
    fun timerOf(position: Int): CookingTimer? = session?.current?.recipeId?.let { timerFor(it, position) }

    /** « Lancer et continuer » is offered once per step: a timer acknowledged or cancelled does not bring it back. */
    fun hasLaunched(position: Int): Boolean {
        val recipeId = session?.current?.recipeId ?: return false
        return (recipeId to position) in launched || timerFor(recipeId, position) != null
    }

    /** A timer rang and was not acknowledged: its step's line is lit. */
    val ringing: List<CookingTimer> get() = timers.filter { it.status == CookingTimerStatus.DONE }

    /** Seconds left: counted down from the deadline while it runs, the rest while paused, none once rung. */
    fun remainingOf(timer: CookingTimer): Int = when (timer.status) {
        CookingTimerStatus.RUNNING -> deadlines[timer.id]
            ?.let { ((it - now + 999) / 1000).toInt().coerceAtLeast(0) }
            ?: timer.remainingSeconds
        CookingTimerStatus.PAUSED -> timer.remainingSeconds
        else -> 0
    }
}

/**
 * The Cuisine activity (MAG-580): reads what it cooks (`GET /api/meals/{id}`, `GET /api/recipes/{id}?guests=N`).
 * The [CookingSession] lives in the activity status (MAG-615): read on opening, written at each change, so leaving
 * loses nothing and another device resumes on the same recipe, step and guests. The origin comes from the
 * navigation arguments: `meal` or `recipe`.
 */
class CookingViewModel(
    savedState: SavedStateHandle,
    private val mealRepository: MealRepository,
    private val recipeRepository: RecipeRepository,
    private val timerRepository: CookingTimerRepository,
    private val statusRepository: ActivityStatusRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    // Ids go into API paths: a link carrying anything else cooks nothing.
    private var origin: CookingOrigin? = savedState.get<String>(DeepLinks.ARG_MEAL)?.takeIf(DeepLinks::isValidId)
        ?.let { CookingOrigin(CookingOrigin.Kind.MEAL, it) }
        ?: savedState.get<String>(DeepLinks.ARG_RECIPE)?.takeIf(DeepLinks::isValidId)
            ?.let { CookingOrigin(CookingOrigin.Kind.RECIPE, it) }

    private val _uiState = MutableStateFlow(CookingUiState())
    val uiState: StateFlow<CookingUiState> = _uiState

    private var startJob: Job? = null
    private var recipesJob: Job? = null

    /** What the recipes on screen were read for: `id:servings` per recipe, servings empty before « Pour combien ? » is answered. */
    private var loadedKey = ""

    private var timersJob: Job? = null
    private var lastPoll = 0L
    private val rung = mutableSetOf<String>()

    init {
        start()
        // Re-read, never restarted: a rotation or a return finds the timers as the API keeps them.
        // An address that names nothing to cook asks the API nothing, its timers included.
        if (origin != null) {
            loadTimers()
            followTimers()
        }
    }

    fun retry() {
        start()
        if (origin != null) loadTimers()
    }

    fun setGuests(guests: Int) = change { it.withGuests(guests) }

    fun confirmServings() = change { it.confirmServings() }

    fun previous() = change { it.back() }

    fun skipRecipe() = change { it.skipRecipe(clock()) }

    /** « Suivant »: the step on screen is ticked off, then the next one. Nothing holds it back. */
    fun next() {
        val state = _uiState.value
        if (state.recipe == null) return
        change { it.advance(state.steps.size, now = clock()) }
    }

    /**
     * A step touched in the list, of the current recipe or another of the queue: it is shown, not ticked.
     * Touching the line of a timer that rang acknowledges it.
     */
    fun openStep(recipeIndex: Int, position: Int) {
        val state = _uiState.value
        val recipeId = state.session?.queue?.getOrNull(recipeIndex)?.recipeId ?: return
        change { it.goToStep(recipeIndex, position, state.stepCounts[recipeId] ?: 0) }
        state.timerFor(recipeId, position)?.takeIf { it.status == CookingTimerStatus.DONE }?.let { dismiss(it.id) }
    }

    /**
     * « Lancer et continuer » on a step that cooks in the background: its timer starts, then the next step,
     * the step itself not ticked. A refusal leaves the step where it is, with the reason.
     */
    fun startAndNext() {
        val state = _uiState.value
        val step = state.currentStep
        if (step?.durationMinutes == null || state.hasLaunched(step.position)) return next()
        val from = state.session?.let { it.recipeIndex to it.step }
        startTimer(step) {
            // The cook may have moved while the timer was on its way: go on only from the step it was started on.
            val session = _uiState.value.session
            if (session != null && session.recipeIndex to session.step == from) {
                change { it.advance(_uiState.value.steps.size, validate = false, now = clock()) }
            }
        }
    }

    /** Démarrer, Pause or Reprendre on the current step's timer. */
    fun toggleTimer() {
        val state = _uiState.value
        val step = state.currentStep ?: return
        val timer = state.timerOf(step.position)
        when (timer?.status) {
            null -> if (step.durationMinutes != null) startTimer(step) {}
            CookingTimerStatus.RUNNING -> changeTimer(timer.id, CookingTimerAction.PAUSE)
            CookingTimerStatus.PAUSED -> changeTimer(timer.id, CookingTimerAction.RESUME)
            else -> Unit
        }
    }

    /** −1 / +1 min on the current step's timer; never below zero, which rings it at once. */
    fun addMinutes(minutes: Int) {
        val state = _uiState.value
        val timer = state.currentStep?.let { state.timerOf(it.position) }?.takeIf { it.status.isCounting } ?: return
        changeTimer(timer.id, CookingTimerAction.ADD, minutes * 60)
    }

    /** A rung timer is acknowledged everywhere, its notification leaves the other devices. */
    fun dismiss(id: String) {
        val timer = _uiState.value.timers.firstOrNull { it.id == id && it.status == CookingTimerStatus.DONE } ?: return
        // Gone from the screen at once; the API's answer, or Mercure, confirms it.
        applyTimer(timer.copy(status = CookingTimerStatus.DISMISSED))
        changeTimer(id, CookingTimerAction.DISMISS)
    }

    /** True once per rung timer: the sound and the vibration are not played again by a recomposition. */
    fun announce(id: String): Boolean = rung.add(id)

    /** « Reprendre » on the choice: the Cuisine in progress, from where it stopped. */
    fun resumeInProgress() {
        val session = _uiState.value.conflict?.session ?: return
        origin = session.origin
        _uiState.update { it.copy(conflict = null) }
        adopt(session)
    }

    /** « Recommencer avec cette recette / ce repas »: the one in progress ends, this one starts new. */
    fun replaceInProgress() {
        val launch = origin ?: return
        if (_uiState.value.conflict == null) return
        statusRepository.delete(COOKING_ACTIVITY)
        cancelTimers()
        _uiState.update { it.copy(conflict = null, load = CookingLoad.LOADING) }
        startJob?.cancel()
        startJob = viewModelScope.launch { begin(launch) }
    }

    /**
     * « Recommencer »: the status ends, the timers stop, and the cook is back on the first preparation for the same
     * guests. Nothing is written until the next change.
     */
    fun restart() {
        val current = _uiState.value.session ?: return
        statusRepository.delete(COOKING_ACTIVITY)
        cancelTimers()
        publish(current.restarted(now = clock()))
        syncRecipes()
    }

    private fun cancelTimers() {
        val ids = _uiState.value.timers.map { it.id }
        if (ids.isNotEmpty()) timerRepository.cancelInBackground(ids)
        _uiState.update { it.copy(timers = emptyList(), deadlines = emptyMap(), launched = emptySet()) }
    }

    /**
     * Each second, from the screen: the clocks move on. A timer whose count reached zero is read again
     * every [POLL_MS] until the API says it rang, in case its Mercure update is late.
     */
    fun tick() {
        val now = clock()
        _uiState.update { it.copy(now = now) }
        val state = _uiState.value
        val overdue = state.timers.any { it.status == CookingTimerStatus.RUNNING && state.remainingOf(it) == 0 }
        if (overdue && now - lastPoll >= POLL_MS && timersJob?.isActive != true) {
            lastPoll = now
            loadTimers()
        }
    }

    private fun start() {
        startJob?.cancel()
        recipesJob?.cancel()
        loadedKey = ""
        _uiState.update { it.copy(load = CookingLoad.LOADING, refreshing = false, conflict = null) }

        startJob = viewModelScope.launch {
            val launch = origin
            if (launch == null) {
                _uiState.update { it.copy(load = CookingLoad.EMPTY) }
                return@launch
            }

            val status = statusRepository.get(COOKING_ACTIVITY).getOrElse { return@launch fail() }
            val saved = status?.toCookingSession()
            when {
                saved == null -> begin(launch)
                saved.origin == launch -> adopt(saved)
                else -> conflict(launch, saved)
            }
        }
    }

    /** Nothing in progress: the preparation of a meal, « Pour combien ? » for a recipe. */
    private suspend fun begin(launch: CookingOrigin) {
        when (launch.kind) {
            CookingOrigin.Kind.MEAL -> {
                val meal = mealRepository.getMeal(launch.id).getOrElse { return fail() }
                val ids = meal.recipeIds()
                if (ids.isEmpty()) {
                    _uiState.update { it.copy(load = CookingLoad.EMPTY) }
                    return
                }
                publish(mealSession(launch.id, ids, meal.guests, clock()))
            }

            CookingOrigin.Kind.RECIPE -> {
                val recipe = recipeRepository.getRecipe(launch.id).getOrElse { return fail() }
                val session = recipeSession(launch.id, recipe.servings, clock())
                loadedKey = keyOf(session)
                _uiState.update { it.copy(recipes = mapOf(recipe.id to recipe)) }
                publish(session)
            }
        }
        _uiState.update { it.copy(load = CookingLoad.READY) }
        syncRecipes()
    }

    /** The Cuisine left in progress, as it was: its recipes are read for its guests. */
    private fun adopt(session: CookingSession) {
        recipesJob?.cancel()
        loadedKey = ""
        _uiState.update { it.copy(session = session, load = CookingLoad.READY) }
        syncRecipes()
    }

    private suspend fun conflict(launch: CookingOrigin, saved: CookingSession) {
        val recipe = saved.current?.let { recipeRepository.getRecipe(it.recipeId).getOrNull() }
        _uiState.update {
            it.copy(
                load = CookingLoad.CONFLICT,
                conflict = CookingConflict(saved, launch.kind, recipe?.name, recipe?.steps?.size),
            )
        }
    }

    private fun startTimer(step: RecipeStep, then: () -> Unit) {
        val state = _uiState.value
        val recipeId = state.session?.current?.recipeId ?: return
        val minutes = step.durationMinutes ?: return
        // Before the first read a re-entered step's timer may already run: no second one.
        if (state.starting || !state.timersLoaded) return
        _uiState.update { it.copy(starting = true, timerError = null) }
        viewModelScope.launch {
            timerRepository.start(timerLabel(step.text, step.position), recipeId, step.position, minutes * 60).fold(
                onSuccess = { timer ->
                    _uiState.update { it.copy(starting = false) }
                    applyTimer(timer)
                    then()
                },
                onFailure = { _uiState.update { it.copy(starting = false, timerError = START_FAILED) } },
            )
        }
    }

    private fun changeTimer(id: String, action: CookingTimerAction, seconds: Int? = null) {
        _uiState.update { it.copy(timerError = null) }
        viewModelScope.launch {
            timerRepository.change(id, action, seconds).fold(
                onSuccess = ::applyTimer,
                onFailure = { error ->
                    // Refused: the timer as it stands, so the screen shows what can still be done with it.
                    (error as? CookingTimerRefusedException)?.timer?.let(::applyTimer)
                    // An acknowledgement that did not get through: the timer comes back from the API's list.
                    if (action == CookingTimerAction.DISMISS) loadTimers() else _uiState.update { it.copy(timerError = CHANGE_FAILED) }
                },
            )
        }
    }

    private fun loadTimers() {
        timersJob?.cancel()
        timersJob = viewModelScope.launch {
            val timers = timerRepository.active().getOrElse {
                _uiState.update { it.copy(timersLoaded = true) }
                return@launch
            }
            val now = clock()
            _uiState.update { state ->
                state.copy(
                    timersLoaded = true,
                    timers = timers.filter { it.status.isActive },
                    launched = state.launched + timers.mapNotNull { t -> t.recipeId?.let { it to t.stepPosition } },
                    deadlines = timers.filter { it.status == CookingTimerStatus.RUNNING }
                        .associate { it.id to now + it.remainingSeconds * 1000L },
                    now = now,
                )
            }
        }
    }

    private fun followTimers() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            val topic = MercureTopics.userScoped(userId, MercureTopics.COOKING_TIMERS)
            launch {
                mercureService.reconnections(topic).catch { }.collect { loadTimers() }
            }
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.COOKING_TIMERS))
                .catch { /* SSE reconnects automatically */ }
                .collect { event -> parseCookingTimer(event.data)?.let(::applyTimer) }
        }
    }

    /** One timer as the API now says it is: kept, replaced, or gone; a rung one lights its step's line. */
    private fun applyTimer(timer: CookingTimer) {
        val now = clock()
        _uiState.update { state ->
            val others = state.timers.filter { it.id != timer.id }
            val index = state.timers.indexOfFirst { it.id == timer.id }
            val timers = when {
                !timer.status.isActive -> others
                index < 0 -> state.timers + timer
                else -> state.timers.toMutableList().also { it[index] = timer }
            }
            val deadlines = state.deadlines - timer.id +
                if (timer.status == CookingTimerStatus.RUNNING) mapOf(timer.id to now + timer.remainingSeconds * 1000L) else emptyMap()
            state.copy(
                timers = timers,
                deadlines = deadlines,
                now = now,
                // A timer ended since (cancelled by « Recommencer », acknowledged) does not mark its step launched again.
                launched = timer.recipeId?.takeIf { timer.status.isActive }?.let { state.launched + (it to timer.stepPosition) } ?: state.launched,
            )
        }
    }

    private fun change(transform: (CookingSession) -> CookingSession) {
        val current = _uiState.value.session ?: return
        val next = transform(current)
        if (next == current) return
        publish(next)
        // « Repas servi » ends the Cuisine: nothing is left to resume.
        if (next.phase == CookingPhase.DONE) {
            statusRepository.delete(COOKING_ACTIVITY)
        } else {
            statusRepository.put(COOKING_ACTIVITY, next.statusOrigin(), next.statusState())
        }
        syncRecipes()
    }

    private fun publish(session: CookingSession) {
        _uiState.update { it.copy(session = session) }
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
        private const val SCALE_DEBOUNCE_MS = 200L
        const val POLL_MS = 2_000L
        const val START_FAILED = "Le minuteur n'a pas pu démarrer : vérifie la connexion, puis réessaie."
        const val CHANGE_FAILED = "Le minuteur n'a pas pu être modifié : réessaie."
    }
}
