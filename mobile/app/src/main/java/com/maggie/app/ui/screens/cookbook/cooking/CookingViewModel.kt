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
) {
    val recipe: Recipe? get() = session?.current?.let { recipes[it.recipeId] }

    val steps: List<RecipeStep> get() = recipe?.orderedSteps().orEmpty()

    /** The earlier steps the current step still waits for; empty on the preparation. A rung timer is ready. */
    val pending: List<Int>
        get() = session?.takeIf { it.phase == CookingPhase.COOKING && it.step > 0 }
            ?.let { session ->
                val rung = timers.filter { it.status == CookingTimerStatus.DONE && it.recipeId == session.current?.recipeId }
                    .map { it.stepPosition }
                pendingWaits(steps, session.step, session.ready + rung)
            }
            .orEmpty()

    /** The current step, null on the preparation. */
    val currentStep: RecipeStep?
        get() = session?.takeIf { it.phase == CookingPhase.COOKING && it.step > 0 }?.let { steps.getOrNull(it.step - 1) }

    /** The timer of a step of the current recipe. */
    fun timerOf(position: Int): CookingTimer? {
        val recipeId = session?.current?.recipeId ?: return null
        return timers.firstOrNull { it.recipeId == recipeId && it.stepPosition == position }
    }

    /** Seconds left: counted down from the deadline while it runs, the rest while paused, none once rung. */
    fun remainingOf(timer: CookingTimer): Int = when (timer.status) {
        CookingTimerStatus.RUNNING -> deadlines[timer.id]
            ?.let { ((it - now + 999) / 1000).toInt().coerceAtLeast(0) }
            ?: timer.remainingSeconds
        CookingTimerStatus.PAUSED -> timer.remainingSeconds
        else -> 0
    }

    /** The longest rest of the awaited timers still counting: « encore 02:10 »; null when none is started. */
    val awaitedLeft: Int?
        get() = pending.mapNotNull { timerOf(it) }.filter { it.status.isCounting }.maxOfOrNull { remainingOf(it) }

    /** The first timer that rang and was not acknowledged: its alert is over the step, the others wait. */
    val alert: CookingTimer?
        get() = timers.filter { it.status == CookingTimerStatus.DONE }.minByOrNull { it.finishedAt.orEmpty() }

    /** Leaving would stop a timer: « Quitter l'activité ? » first. */
    val confirmQuit: Boolean get() = timers.any { it.status.isCounting }
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
    private val timerRepository: CookingTimerRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
    private val clock: () -> Long = System::currentTimeMillis,
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

    private var timersJob: Job? = null
    private var lastPoll = 0L
    private val rung = mutableSetOf<String>()

    init {
        start()
        // Re-read, never restarted: a rotation or a return finds the timers as the API keeps them.
        loadTimers()
        followTimers()
    }

    fun retry() {
        start()
        loadTimers()
    }

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

    /**
     * « Lancer et continuer » on a step that cooks in the background: its timer starts, then the next step.
     * A refusal leaves the step where it is, with the reason.
     */
    fun startAndNext() {
        val state = _uiState.value
        if (state.pending.isNotEmpty()) return
        val step = state.currentStep
        if (step?.durationMinutes == null || state.timerOf(step.position) != null) return next()
        startTimer(step) { next() }
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

    /** « OK » on the alert: the timer is acknowledged everywhere, its notification leaves the other devices. */
    fun dismiss(id: String) {
        val timer = _uiState.value.timers.firstOrNull { it.id == id && it.status == CookingTimerStatus.DONE } ?: return
        // Gone from the screen at once; the API's answer, or Mercure, confirms it.
        applyTimer(timer.copy(status = CookingTimerStatus.DISMISSED))
        changeTimer(id, CookingTimerAction.DISMISS)
    }

    /** True once per rung timer: the sound and the vibration are not played again by a recomposition. */
    fun announce(id: String): Boolean = rung.add(id)

    /** « Quitter »: every timer still on screen is cancelled, after the activity is gone if need be. */
    fun quit() {
        val ids = _uiState.value.timers.map { it.id }
        if (ids.isEmpty()) return
        timerRepository.cancelInBackground(ids)
        _uiState.update { it.copy(timers = emptyList(), deadlines = emptyMap()) }
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

    private fun startTimer(step: RecipeStep, then: () -> Unit) {
        val state = _uiState.value
        val recipeId = state.session?.current?.recipeId ?: return
        val minutes = step.durationMinutes ?: return
        if (state.starting) return
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
                    // « OK » that did not get through: the alert comes back from the API's list.
                    if (action == CookingTimerAction.DISMISS) loadTimers() else _uiState.update { it.copy(timerError = CHANGE_FAILED) }
                },
            )
        }
    }

    private fun loadTimers() {
        timersJob?.cancel()
        timersJob = viewModelScope.launch {
            val timers = timerRepository.active().getOrElse { return@launch }
            val now = clock()
            _uiState.update { state ->
                state.copy(
                    timers = timers.filter { it.status.isActive },
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

    /** One timer as the API now says it is: kept, replaced, or gone; a rung one frees the steps that wait for it. */
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
            state.copy(timers = timers, deadlines = deadlines, now = now)
        }
        val session = _uiState.value.session
        val finished = timer.status == CookingTimerStatus.DONE || timer.status == CookingTimerStatus.DISMISSED
        if (finished && session != null && timer.recipeId == session.current?.recipeId) {
            change { it.markReady(listOf(timer.stepPosition)) }
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
        const val POLL_MS = 2_000L
        const val START_FAILED = "Le minuteur n'a pas pu démarrer : vérifie la connexion, puis réessaie."
        const val CHANGE_FAILED = "Le minuteur n'a pas pu être modifié : réessaie."
    }
}
