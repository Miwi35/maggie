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
    /** « Manquant » marks made and not yet received by the API: article id → missing or not (MAG-764). */
    val pendingMissing: Map<String, Boolean> = emptyMap(),
    /** The summary of « Terminer le magasin » is open (MAG-765). */
    val recap: Boolean = false,
    /** « Montant payé » as typed, in euros: « 42,80 »; empty when not given. */
    val paidAmount: String = "",
    /** [paidAmount] is not an amount in euros. */
    val amountInvalid: Boolean = false,
    /** The store is being finished: `end-errand` is on its way. */
    val finishing: Boolean = false,
    /** Finishing the store failed (network, or a tick still waiting): the summary stays, « Valider » again. */
    val finishFailed: Boolean = false,
    /** « Supprimer » asked on a missing article without a fallback, and not yet received by the API. */
    val removing: Set<String> = emptySet(),
    /** A « Supprimer » the API refused: the article stays, the choice is asked again. */
    val removeFailed: Boolean = false,
    /** The screen « Fin »: the tour is over and every missing article without a fallback has its answer. */
    val ended: Boolean = false,
) {
    fun isChecked(item: GroceryItem): Boolean = pending[item.id] ?: item.checked

    fun isMissing(item: GroceryItem): Boolean = pendingMissing[item.id] ?: (item.missingAt != null)

    private fun place(item: GroceryItem): RunPlace? = item.runPlace(isMissing(item), run.tour)

    private val currentItems: List<GroceryItem> get() = items.filter { place(it)?.storeId == run.current }

    /** The articles of the store being done, by aisle in the order of the store; a missing one moves with [place]. */
    val currentAisles: List<RunAisle> get() = runAisles(currentItems, ::isChecked, mealTags) { place(it)?.missing }

    val progress: RunProgress get() = RunProgress(
        currentItems.count { isChecked(it) || place(it)?.missing == RunMissing.NoFallback },
        currentItems.size,
    )

    /** « Magasin k sur N »: the place of the store being done in the tour. */
    val tourPosition: Pair<Int, Int>? get() = run.current?.let { run.tour.indexOf(it) }?.takeIf { it >= 0 }?.let { it + 1 to run.tour.size }

    private val byId: Map<String, RunStore> get() = stores.associateBy { it.id }

    /** The stores visited today, in the order of the session. */
    val tour: List<RunStore> get() = run.tour.mapNotNull(byId::get)

    /** The stores of « pas aujourd'hui », in their habitual order. */
    val skipped: List<RunStore> get() = stores.filter { it.id in run.skipped }

    val current: RunStore? get() = run.current?.let(byId::get)

    val canStart: Boolean get() = tour.isNotEmpty()

    private fun RunPlace?.name(): String? = this?.storeId?.let(byId::get)?.name

    /** What the summary of « Terminer le magasin » lists as taken: the ticked articles of the store being done. */
    val recapTaken: List<RecapLine> get() = currentItems.filter(::isChecked).map { RecapLine(it.id.orEmpty(), it.label, it.runQuantity()) }

    /** The missing articles of the store being done, and the store each one goes to; null when it has no fallback in the tour. */
    val recapMissing: List<RecapLine> get() = items
        .filter { it.origin?.id == run.current && isMissing(it) }
        .map { item -> RecapLine(item.id.orEmpty(), item.label, item.runQuantity(), place(item)?.takeIf { it.storeId != run.current }.name()) }

    /**
     * « Manquants sans repli » (MAG-765): the missing articles of the tour's stores that found no store further in
     * the tour; one that moved to its fallback is not one of them.
     */
    val leftovers: List<RecapLine> get() = items
        .filter { item ->
            item.id != null && item.id !in removing && isMissing(item) && item.origin?.id in run.tour &&
                place(item)?.missing == RunMissing.NoFallback
        }
        .map { RecapLine(it.id.orEmpty(), it.label, it.runQuantity(), it.origin?.name) }
}

/** An article as the end of a store or of the tour lists it; [store] is where it goes, or comes from. */
data class RecapLine(val id: String, val label: String, val quantity: String?, val store: String? = null)

/** The store an article was planned in: the one it was missing from once the API moved it to its fallback. */
private val GroceryItem.origin get() = missingFromStore ?: store

/** « 42,80 » → 4280; null when it is not an amount in euros with at most two decimals. */
fun euroCents(text: String): Int? {
    val match = Regex("""^(\d{1,6})(?:[.,](\d{1,2}))?$""").matchEntire(text.trim().removeSuffix("€").trim()) ?: return null
    val (euros, cents) = match.destructured
    return euros.toInt() * 100 + cents.padEnd(2, '0').toInt()
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
        viewModelScope.launch { tickQueue.pending.collect(::onPending) }
        viewModelScope.launch { tickQueue.pendingMissing.collect { marks -> _uiState.update { it.copy(pendingMissing = marks) } } }
    }

    // A tick the API has received leaves the queue: it is kept on the article until the next read of the list says it.
    private fun onPending(pending: Map<String, Boolean>) = _uiState.update { state ->
        val received = state.pending.filter { (id, _) -> id !in pending }
        val items = if (received.isEmpty()) state.items else state.items.map { item -> received[item.id]?.let { item.copy(checked = it) } ?: item }
        state.copy(pending = pending, items = items)
    }

    /** A tap on an article's box: ticked becomes unticked and the other way round, shown at once, sent when the network allows. */
    fun check(itemId: String) {
        val state = _uiState.value
        val item = state.items.firstOrNull { it.id == itemId } ?: return
        tickQueue.tick(itemId, !state.isChecked(item))
        onPending(tickQueue.pending.value)
    }

    /**
     * « Manquant » on an article of the store being done, or « Annuler » on a missing one (MAG-764): shown at once —
     * in its fallback store when that one comes later in the tour — and sent when the network allows. A ticked
     * article is not missing.
     */
    fun markMissing(itemId: String) {
        val state = _uiState.value
        val item = state.items.firstOrNull { it.id == itemId } ?: return
        val missing = !state.isMissing(item)
        if (missing && state.isChecked(item)) return
        tickQueue.markMissing(itemId, missing)
        _uiState.update { it.copy(pendingMissing = tickQueue.pendingMissing.value) }
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

    /** « Terminer le magasin »: the summary of the store being done opens, with an empty « Montant payé ». */
    fun openRecap() {
        if (_uiState.value.run.current == null) return
        _uiState.update { it.copy(recap = true, paidAmount = "", amountInvalid = false, finishFailed = false) }
    }

    /** « Retour »: back to the store, nothing sent. */
    fun closeRecap() = _uiState.update { it.copy(recap = false, finishFailed = false) }

    fun setPaidAmount(text: String) = _uiState.update { it.copy(paidAmount = text, amountInvalid = false) }

    /**
     * « Valider » (MAG-765): the ticks still waiting are sent first, so the API archives every article taken; then
     * `end-errand` with the store and the amount paid, and the next store of the tour opens — after the last one,
     * « Manquants sans repli », or « Fin » when there is none.
     */
    fun finishStore() {
        val state = _uiState.value
        val storeId = state.run.current ?: return
        if (state.finishing) return
        val cents = if (state.paidAmount.isBlank()) null else euroCents(state.paidAmount)
        if (state.paidAmount.isNotBlank() && cents == null) {
            _uiState.update { it.copy(amountInvalid = true) }
            return
        }
        _uiState.update { it.copy(finishing = true, finishFailed = false) }
        viewModelScope.launch {
            tickQueue.flush()
            val waiting = tickQueue.pending.value.isNotEmpty() || tickQueue.pendingMissing.value.isNotEmpty()
            if (waiting || listRepository.endErrand(storeId, cents).isFailure) {
                _uiState.update { it.copy(finishing = false, finishFailed = true) }
                return@launch
            }
            val run = _uiState.value.run
            val next = run.nextAfter(storeId)
            _uiState.update { it.copy(finishing = false, recap = false, paidAmount = "") }
            update(run.copy(current = next, done = run.done + storeId, finished = next == null))
            listRepository.getGroceryList().getOrNull()?.let { show(it, _uiState.value.run) }
            endIfAnswered()
        }
    }

    /** « Prochaine fois » on a missing article without a fallback: its mark goes, it stays on the list for next time. */
    fun keepForNextTime(itemId: String) {
        if (_uiState.value.leftovers.none { it.id == itemId }) return
        tickQueue.markMissing(itemId, false)
        // Read as the API will store it, so the mark does not come back once the queue has sent it.
        _uiState.update { state ->
            state.copy(
                pendingMissing = tickQueue.pendingMissing.value,
                items = state.items.map { if (it.id == itemId) it.copy(store = it.origin, missingFromStore = null, missingAt = null) else it },
            )
        }
        endIfAnswered()
    }

    /** « Supprimer » on a missing article without a fallback: it leaves the list. */
    fun remove(itemId: String) {
        if (_uiState.value.leftovers.none { it.id == itemId }) return
        _uiState.update { it.copy(removing = it.removing + itemId, removeFailed = false) }
        viewModelScope.launch {
            val removed = listRepository.deleteItem(itemId).isSuccess
            _uiState.update {
                if (removed) {
                    it.copy(removing = it.removing - itemId, items = it.items.filter { item -> item.id != itemId })
                } else {
                    it.copy(removing = it.removing - itemId, removeFailed = true)
                }
            }
            endIfAnswered()
        }
    }

    // « Fin »: the tour is over and no missing article waits for its answer; the activity status is closed.
    private fun endIfAnswered() {
        val state = _uiState.value
        if (state.ended || !state.run.finished || state.removing.isNotEmpty() || state.leftovers.isNotEmpty()) return
        _uiState.update { it.copy(ended = true) }
        statusRepository.delete(GROCERY_RUN_ACTIVITY)
    }

    /** The ✕ or the back: true when the activity can be left now, false when « Quitter les courses ? » is asked. */
    fun requestQuit(): Boolean {
        val state = _uiState.value
        if (state.ended) return true
        if (state.checkedSinceStart == 0 && state.pending.isEmpty() && state.pendingMissing.isEmpty()) return true
        _uiState.update { it.copy(quitAsked = true) }
        return false
    }

    fun dismissQuit() = _uiState.update { it.copy(quitAsked = false) }

    private fun update(run: GroceryRunState) {
        _uiState.update { it.copy(run = run) }
        write(run)
    }

    private fun write(run: GroceryRunState) {
        if (_uiState.value.ended) return
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
            show(list, GroceryRunState.from(saved))
            endIfAnswered()
            loadTags()
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
        if (_uiState.value.ended || _uiState.value.load !in setOf(GroceryRunLoad.READY, GroceryRunLoad.EMPTY)) return
        val list = listRepository.getGroceryList().getOrNull() ?: return
        show(list, _uiState.value.run)
        loadTags()
    }

    // The meals of the week ahead, after the list is shown: a slow or failed read only leaves the « pour : … » tags out.
    private suspend fun loadTags() {
        val day = today()
        val tags = listRepository.getMealNeeds(day.toString(), day.plusDays(TAG_DAYS - 1).toString()).getOrNull()?.runTags(day) ?: return
        _uiState.update { it.copy(mealTags = tags) }
    }

    private fun show(list: GroceryList?, saved: GroceryRunState?) {
        val day = today()
        val lines = list?.items.orEmpty().filter { !isDeferred(it, day) && it.store != null }
        // A store whose last article was just ticked stays while it is the one being done: finishing it is its own step.
        val keep = saved?.current
        // A missing article is counted in its own store, not in the fallback the API moved it to: the fallback is
        // only where it is taken when that store is in today's tour (MAG-764).
        val stores = lines.groupBy { it.homeStore.id }
            .filter { (id, items) -> id == keep || items.any { !it.checked } }
            .values
            .map { items -> items.first().homeStore to items.count { !it.checked } }
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
                run = run,
                checkedSinceStart = (checked - baseline).coerceAtLeast(0),
            )
        }
        if (stores.isNotEmpty() && run != saved) write(run)
    }

    private val GroceryItem.homeStore get() = missingFromStore ?: store!!

    private companion object {
        const val TAG_DAYS = 7L
    }
}
