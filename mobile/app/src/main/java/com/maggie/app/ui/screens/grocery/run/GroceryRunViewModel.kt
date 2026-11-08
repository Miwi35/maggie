package com.maggie.app.ui.screens.grocery.run

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalesced
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.GroceryList
import com.maggie.app.data.repository.ActivityStatusRepository
import com.maggie.app.data.repository.GroceryListRepository
import com.maggie.app.data.repository.GroceryTickQueue
import com.maggie.app.ui.screens.cookbook.grocery.isDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class GroceryRunLoad {
    LOADING,

    /** The API is unreachable: « Réessayer », and the activity never closes by itself. */
    ERROR,

    /** No store has an article left to take. */
    EMPTY,
    READY,
}

/** A store with articles to take, as the tour lists it. */
data class RunStore(val id: String, val name: String, val count: Int)

data class GroceryRunUiState(
    val load: GroceryRunLoad = GroceryRunLoad.LOADING,
    /** The stores that hold articles, in their habitual order (`Store.visitOrder`). */
    val stores: List<RunStore> = emptyList(),
    val run: GroceryRunState = GroceryRunState(),
    /** « Quitter les courses ? » is asked. */
    val quitAsked: Boolean = false,
    /** Articles ticked since the activity opened: from the first, leaving asks. */
    val checkedSinceStart: Int = 0,
    /** The open articles of the list that have a store, ticked or not. */
    val items: List<GroceryItem> = emptyList(),
    /** Ticks made and not yet received by the API: article id → state asked. */
    val pending: Map<String, Boolean> = emptyMap(),
    /** Product id → « ce soir · bœuf bourguignon ». */
    val mealTags: Map<String, String> = emptyMap(),
) {
    fun isChecked(item: GroceryItem): Boolean = pending[item.id] ?: item.checked

    private val currentItems: List<GroceryItem> get() = items.filter { it.store?.id == run.current }

    /** The articles of the store being done, by aisle in the order of the store. */
    val currentAisles: List<RunAisle> get() = runAisles(currentItems, ::isChecked, mealTags)

    val progress: RunProgress get() = RunProgress(currentItems.count(::isChecked), currentItems.size)

    /** « Magasin k sur N »: the place of the store being done in the tour. */
    val tourPosition: Pair<Int, Int>? get() = run.current?.let { run.tour.indexOf(it) }?.takeIf { it >= 0 }?.let { it + 1 to run.tour.size }

    private val byId: Map<String, RunStore> get() = stores.associateBy { it.id }

    /** The stores visited today, in the order of the session. */
    val tour: List<RunStore> get() = run.tour.mapNotNull(byId::get)

    /** The stores of « pas aujourd'hui », in their habitual order. */
    val skipped: List<RunStore> get() = stores.filter { it.id in run.skipped }

    val current: RunStore? get() = run.current?.let(byId::get)

    val canStart: Boolean get() = tour.isNotEmpty()
}

/**
 * The Courses activity (MAG-762): reads the open list's stores that hold articles, in their habitual order, and
 * lets the owner pick today's and reorder them for this session only. The session — tour, current store, stores
 * left out — lives in the activity status (`courses`), read on opening and written at each change, so « Reprendre »
 * finds it again, on any device. `Store.visitOrder` is never written.
 */
class GroceryRunViewModel(
    private val listRepository: GroceryListRepository,
    private val statusRepository: ActivityStatusRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
    private val tickQueue: GroceryTickQueue,
    private val today: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {

    private val _uiState = MutableStateFlow(GroceryRunUiState())
    val uiState: StateFlow<GroceryRunUiState> = _uiState

    private var listId: String? = null
    private var baselineChecked: Int? = null
    private var loadJob: Job? = null

    init {
        load()
        followList()
        viewModelScope.launch { tickQueue.pending.collect { pending -> _uiState.update { it.copy(pending = pending) } } }
    }

    /** A tap on an article's box: ticked becomes unticked and the other way round, shown at once, sent when the network allows. */
    fun check(itemId: String) {
        val state = _uiState.value
        val item = state.items.firstOrNull { it.id == itemId } ?: return
        tickQueue.tick(itemId, !state.isChecked(item))
        _uiState.update { it.copy(pending = tickQueue.pending.value) }
    }

    /** « Réessayer ». */
    fun retry() {
        _uiState.update { it.copy(load = GroceryRunLoad.LOADING) }
        load()
    }

    /** « Je le visite » / « Pas aujourd'hui » on a store. */
    fun setVisited(storeId: String, visited: Boolean) {
        val state = _uiState.value
        if (state.stores.none { it.id == storeId }) return
        val run = state.run
        val habitual = state.stores.map { it.id }
        val changed = when {
            visited && storeId !in run.tour ->
                run.copy(tour = run.tour.insertedInHabitualOrder(storeId, habitual), skipped = run.skipped - storeId)
            !visited && storeId in run.tour ->
                run.copy(tour = run.tour - storeId, skipped = run.skipped + storeId, current = run.current.takeIf { it != storeId })
            else -> return
        }
        update(changed)
    }

    /** A store dragged to another place of the tour: the session's order only. */
    fun move(from: Int, to: Int) {
        val run = _uiState.value.run
        if (from !in run.tour.indices || to !in run.tour.indices || from == to) return
        val tour = run.tour.toMutableList().apply { add(to, removeAt(from)) }
        update(run.copy(tour = tour))
    }

    /** « C'est parti »: the first store of the tour is opened. */
    fun start() {
        val run = _uiState.value.run
        val first = run.tour.firstOrNull() ?: return
        update(run.copy(current = first))
    }

    /** The ✕ or the back: true when the activity can be left now, false when « Quitter les courses ? » is asked. */
    fun requestQuit(): Boolean {
        if (_uiState.value.checkedSinceStart == 0 && _uiState.value.pending.isEmpty()) return true
        _uiState.update { it.copy(quitAsked = true) }
        return false
    }

    fun dismissQuit() = _uiState.update { it.copy(quitAsked = false) }

    private fun update(run: GroceryRunState) {
        _uiState.update { it.copy(run = run) }
        write(run)
    }

    private fun write(run: GroceryRunState) {
        val id = listId ?: return
        statusRepository.put(GROCERY_RUN_ACTIVITY, GroceryRunState.origin(id), run.toJson())
    }

    private fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val list = listRepository.getGroceryList().getOrElse {
                _uiState.update { state -> state.copy(load = GroceryRunLoad.ERROR) }
                return@launch
            }
            val saved = statusRepository.get(GROCERY_RUN_ACTIVITY).getOrNull()
            show(list, GroceryRunState.from(saved), loadTags())
        }
    }

    private fun followList() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            val lists = MercureTopics.userScoped(userId, MercureTopics.GROCERY_LISTS)
            launch {
                mercureService.reconnections(lists).catch { /* SSE reconnects automatically */ }.collect {
                    tickQueue.flush()
                    refresh()
                }
            }
            listOf(MercureTopics.GROCERY_LISTS, MercureTopics.STORES)
                .map { mercureService.subscribe(MercureTopics.userScoped(userId, it)).catch { /* SSE reconnects automatically */ } }
                .merge()
                .coalesced()
                .collect { refresh() }
        }
    }

    // A change elsewhere moves the counts and the ticks, never the session's choices; a failed read keeps the screen as it is.
    private suspend fun refresh() {
        if (_uiState.value.load !in setOf(GroceryRunLoad.READY, GroceryRunLoad.EMPTY)) return
        val list = listRepository.getGroceryList().getOrNull() ?: return
        show(list, _uiState.value.run, loadTags())
    }

    // The meals of the week ahead; a failed read only leaves the « pour : … » tags out.
    private suspend fun loadTags(): Map<String, String> {
        val day = today()
        return listRepository.getMealNeeds(day.toString(), day.plusDays(TAG_DAYS - 1).toString()).getOrNull()?.runTags(day).orEmpty()
    }

    private fun show(list: GroceryList?, saved: GroceryRunState?, tags: Map<String, String>) {
        val day = today()
        val lines = list?.items.orEmpty().filter { !isDeferred(it, day) && it.store != null }
        // A store whose last article was just ticked stays while it is the one being done: finishing it is its own step.
        val keep = saved?.current
        val stores = lines.groupBy { it.store!!.id }
            .filter { (id, items) -> id == keep || items.any { !it.checked } }
            .values
            .map { items -> items.first().store!! to items.count { !it.checked } }
            .sortedWith(compareBy({ it.first.visitOrder }, { it.first.name }))
            .map { (store, count) -> RunStore(store.id, store.name, count) }
        val checked = list?.items.orEmpty().count { it.checked && it.store != null }
        val baseline = baselineChecked ?: checked.also { baselineChecked = it }
        listId = list?.id
        tickQueue.retainOnly(list?.items.orEmpty().mapNotNull { it.id }.toSet())
        val run = (saved ?: GroceryRunState()).reconciledWith(stores.map { it.id })
        _uiState.update {
            it.copy(
                load = if (stores.isEmpty()) GroceryRunLoad.EMPTY else GroceryRunLoad.READY,
                stores = stores,
                items = lines,
                mealTags = tags,
                run = run,
                checkedSinceStart = (checked - baseline).coerceAtLeast(0),
            )
        }
        if (stores.isNotEmpty() && run != saved) write(run)
    }

    private companion object {
        const val TAG_DAYS = 7L
    }
}
