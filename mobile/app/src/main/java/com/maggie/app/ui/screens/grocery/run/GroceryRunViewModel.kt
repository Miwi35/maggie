package com.maggie.app.ui.screens.grocery.run

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalesced
import com.maggie.app.data.model.AisleSummary
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

/** A line of the tour screen: a store, visited today or left out. */
data class RunRow(val store: RunStore, val visited: Boolean)

/** The answer given to a missing article without a fallback, at the end of the tour (MAG-765). */
enum class LeftoverChoice { NEXT_TIME, REMOVE }

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
    /** Marks the API has received and no read of the list says yet: article id → missing or not. */
    val receivedMissing: Map<String, Boolean> = emptyMap(),
    /** The summary of « Terminer le magasin » is open (MAG-765). */
    val recap: Boolean = false,
    /** « Montant payé » as typed, in euros: « 42,80 »; empty when not given. */
    val paidAmount: String = "",
    /** [paidAmount] is not an amount in euros. */
    val amountInvalid: Boolean = false,
    /** The store is being finished: `end-errand` is on its way. */
    val finishing: Boolean = false,
    /** Finishing the store failed (network, or a tick still waiting): the summary stays, to finish again. */
    val finishFailed: Boolean = false,
    /** « Pas trouvés »: the answer chosen for each missing article without a fallback, applied by « Finir les courses ». */
    val choices: Map<String, LeftoverChoice> = emptyMap(),
    /** « Finir les courses » is applying the answers. */
    val closing: Boolean = false,
    /** A « Supprimer » the API refused: the article stays, « Finir les courses » again. */
    val removeFailed: Boolean = false,
    /** The articles kept for next time, as « Courses finies » lists them. */
    val keptForNextTime: List<RecapLine> = emptyList(),
    /** The screen « Fin »: the tour is over and every missing article without a fallback has its answer. */
    val ended: Boolean = false,
    /** Articles the owner ticked in this session and has not unticked: leaving asks while there is one. */
    val ticked: Set<String> = emptySet(),
    /** The article whose « Manquant » sheet is open (MAG-764), not marked until the sheet says so. */
    val missingAsked: String? = null,
    /**
     * Each article's aisle as last read in its own store: the API gives a moved article its fallback's aisle, and its
     * « ira à » line stays under its own aisle (MAG-764).
     */
    val homeAisles: Map<String, AisleSummary?> = emptyMap(),
) {
    fun isChecked(item: GroceryItem): Boolean = pending[item.id] ?: item.checked

    fun isMissing(item: GroceryItem): Boolean = pendingMissing[item.id] ?: receivedMissing[item.id] ?: (item.missingAt != null)

    private fun place(item: GroceryItem): RunPlace? = item.runPlace(isMissing(item), run.tour)

    /** The articles taken in the store being done: a missing one moves with [place]. */
    private val currentItems: List<GroceryItem> get() = items.filter { place(it)?.storeId == run.current }

    /** [currentItems], and the missing ones of this store gone to a fallback further in the tour (the mockup's « ira à »). */
    private val shownItems: List<GroceryItem> get() = items
        .filter { place(it)?.storeId == run.current || goesFromCurrent(it) }
        // Moved by the API, shown in its own store (« ira à », or given back by « Annuler »): its own aisle, not the fallback's.
        .map { item -> if (item.movedFrom(run.current)) item.copy(aisle = homeAisles[item.id]) else item }

    private fun GroceryItem.movedFrom(storeId: String?): Boolean =
        missingFromStore?.id.let { it != null && it == storeId && it != store?.id }

    private fun goesFromCurrent(item: GroceryItem): Boolean =
        item.origin?.id == run.current && isMissing(item) && place(item)?.storeId.let { it != null && it != run.current }

    /** How a missing article reads in the store being done: « ira à » its fallback from its own store, else as [place] says. */
    private fun missingHere(item: GroceryItem): RunMissing? =
        if (goesFromCurrent(item)) RunMissing.GoesTo(place(item).name().orEmpty()) else place(item)?.missing

    /** The articles of the store being done, by aisle in the order of the store. */
    val currentAisles: List<RunAisle> get() = runAisles(shownItems, ::isChecked, mealTags, ::missingHere)

    val progress: RunProgress get() = currentAisles.flatMap { it.lines }.let { lines -> RunProgress(lines.count { it.done }, lines.size) }

    /** The sheet « <article> manquant » (MAG-764): the article asked about, and where it would be taken instead. */
    val missingSheet: MissingSheet? get() = missingAsked?.let { id -> items.firstOrNull { it.id == id } }?.let { item ->
        val to = item.runPlace(true, run.tour)?.storeId?.takeIf { it != item.origin?.id }
        MissingSheet(
            id = item.id.orEmpty(),
            label = item.label,
            quantity = item.runQuantity(),
            forMeal = item.product?.id?.let(mealTags::get),
            fallback = to?.let(byId::get)?.name,
            fallbackRank = to?.let { run.tour.indexOf(it) + 1 },
        )
    }

    /** The articles of the whole tour taken out of its articles, « 7 / 14 » at the right of the header. */
    val tourProgress: RunProgress get() = items.mapNotNull { item -> place(item)?.takeIf { it.storeId in run.tour }?.let { item to it } }.let { placed ->
        // The articles taken in a finished store are archived and leave the list: the tour keeps them, so it never goes back.
        val gone = run.ends.filter { it.storeId in run.tour }.sumOf { it.taken }
        RunProgress(gone + placed.count { (item, place) -> isChecked(item) || place.missing == RunMissing.NoFallback }, gone + placed.size)
    }

    /** « Magasin k sur N »: the place of the store being done in the tour. */
    val tourPosition: Pair<Int, Int>? get() = run.current?.let { run.tour.indexOf(it) }?.takeIf { it >= 0 }?.let { it + 1 to run.tour.size }

    private val byId: Map<String, RunStore> get() = stores.associateBy { it.id }

    /** The stores visited today, in the order of the session. */
    val tour: List<RunStore> get() = run.tour.mapNotNull(byId::get)

    /** The stores of « pas aujourd'hui », in their habitual order. */
    val skipped: List<RunStore> get() = stores.filter { it.id in run.skipped }

    /**
     * The tour screen's rows: every store in the order of the session, a store left out keeping the place it had.
     * Before any gesture sets [GroceryRunState.order]: a store left out at its habitual place, the stores visited
     * filling the other places.
     */
    val rows: List<RunRow> get() = sessionOrder.mapNotNull(byId::get).map { RunRow(it, it.id !in run.skipped) }

    /** The ids of [rows], in order. */
    val sessionOrder: List<String> get() {
        if (run.order.isNotEmpty()) {
            val known = run.order.filter { it in byId }
            return stores.map { it.id }.fold(known) { acc, id -> if (id in acc) acc else acc.insertedInHabitualOrder(id, stores.map { it.id }) }
        }
        val visited = tour.iterator()
        val rows = stores.mapNotNull { store ->
            if (store.id in run.skipped) store else if (visited.hasNext()) visited.next() else null
        }
        return (rows + visited.asSequence()).map { it.id }
    }

    /** The session order is not the habitual one: « ordre du jour ». */
    val reordered: Boolean get() = tour.map { it.id } != stores.map { it.id }.filter { it in run.tour }

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
     * « Pas trouvés » (MAG-765): the missing articles of the tour's stores that found no store further in
     * the tour; one that moved to its fallback is not one of them.
     */
    val leftovers: List<RecapLine> get() = items
        .filter { item ->
            item.id != null && isMissing(item) && item.origin?.id in run.tour &&
                place(item)?.missing == RunMissing.NoFallback
        }
        .map { RecapLine(it.id.orEmpty(), it.label, it.runQuantity(), it.origin?.name) }

    /** « Finir les courses » once each article of « Pas trouvés » has its answer. */
    val canEndRun: Boolean get() = !closing && leftovers.all { it.id in choices }

    /** The store the summary's button opens next; null after the last one, where it reads « Finir les courses ». */
    val nextStore: RunStore? get() = run.current?.let(run::nextAfter)?.let(byId::get)
}

/**
 * The sheet « <article> manquant »: the article, what is taken and for which meal, and its fallback with its place
 * in the tour (« 2ᵉ de la tournée ») when that one comes later in it; both null when it has none.
 */
data class MissingSheet(
    val id: String,
    val label: String,
    val quantity: String?,
    val forMeal: String?,
    val fallback: String?,
    val fallbackRank: Int?,
)

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
        viewModelScope.launch { tickQueue.pendingMissing.collect(::onPendingMissing) }
    }

    // A mark the API has received leaves the queue: it is kept until a read of the list says it, as a read answered
    // before the API had it would otherwise put « Manquant » back on the line.
    private fun onPendingMissing(pending: Map<String, Boolean>) = _uiState.update { state ->
        val received = state.pendingMissing.filter { (id, _) -> id !in pending }
        state.copy(pendingMissing = pending, receivedMissing = state.receivedMissing - pending.keys + received)
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
        val ticked = !state.isChecked(item)
        tickQueue.tick(itemId, ticked)
        _uiState.update { it.copy(ticked = if (ticked) it.ticked + itemId else it.ticked - itemId) }
        onPending(tickQueue.pending.value)
    }

    /** « Manquant » on an article to take (MAG-764): opens its sheet, nothing is marked yet. A ticked article is not missing. */
    fun askMissing(itemId: String) {
        val state = _uiState.value
        val item = state.items.firstOrNull { it.id == itemId } ?: return
        if (state.isChecked(item) || state.isMissing(item)) return
        _uiState.update { it.copy(missingAsked = itemId) }
    }

    /** The sheet's option: the article is marked missing. */
    fun confirmMissing() {
        val itemId = _uiState.value.missingAsked ?: return
        _uiState.update { it.copy(missingAsked = null) }
        markMissing(itemId)
    }

    /** The sheet's « Annuler », or a tap beside it: the article stays as it was. */
    fun dismissMissing() = _uiState.update { it.copy(missingAsked = null) }

    /**
     * The sheet's option, or « Annuler » on a missing article (MAG-764): shown at once — « ira à » its fallback when
     * that one comes later in the tour, and in that store — and sent when the network allows. A ticked article is not
     * missing.
     */
    fun markMissing(itemId: String) {
        val state = _uiState.value
        val item = state.items.firstOrNull { it.id == itemId } ?: return
        val missing = !state.isMissing(item)
        if (missing && state.isChecked(item)) return
        tickQueue.markMissing(itemId, missing)
        onPendingMissing(tickQueue.pendingMissing.value)
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
        val changed = when {
            visited && storeId !in run.tour ->
                run.copy(tour = run.tour.insertedInHabitualOrder(storeId, state.sessionOrder), skipped = run.skipped - storeId, order = state.sessionOrder)
            !visited && storeId in run.tour ->
                run.copy(
                    tour = run.tour - storeId, skipped = run.skipped + storeId,
                    current = run.current.takeIf { it != storeId }, order = state.sessionOrder,
                )
            else -> return
        }
        update(changed)
    }

    /** A store dragged to another row of the tour screen: the session's order only; a store left out can be passed. */
    fun move(from: Int, to: Int) {
        val state = _uiState.value
        val rows = state.rows
        if (from !in rows.indices || to !in rows.indices || from == to || !rows[from].visited) return
        val order = rows.map { it.store.id }.toMutableList().apply { add(to, removeAt(from)) }
        val tour = order.filter { it in state.run.tour }
        if (tour == state.run.tour && order == state.sessionOrder) return
        update(state.run.copy(tour = tour, order = order))
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

    /** The back on the summary: back to the store, nothing sent. */
    fun closeRecap() = _uiState.update { it.copy(recap = false, finishFailed = false) }

    fun setPaidAmount(text: String) = _uiState.update { it.copy(paidAmount = text, amountInvalid = false) }

    /**
     * « Magasin suivant » or, after the last store, « Finir les courses » (MAG-765): the ticks still waiting are sent first, so the API archives every article taken; then
     * `end-errand` with the store and the amount paid, and the next store of the tour opens — after the last one,
     * « Pas trouvés », or « Courses finies » when there is none.
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
        val end = StoreEnd(storeId, state.current?.name.orEmpty(), state.recapTaken.size, state.recapMissing.mapNotNull { it.store }, cents)
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
            update(run.copy(current = next, done = run.done + storeId, finished = next == null, ends = run.ends.filter { it.storeId != storeId } + end))
            listRepository.getGroceryList().getOrNull()?.let { show(it, _uiState.value.run) }
            endIfAnswered()
        }
    }

    /** « Prochaine fois » or « Supprimer » on an article of « Pas trouvés »: only chosen, applied by « Finir les courses ». */
    fun choose(itemId: String, choice: LeftoverChoice) {
        if (_uiState.value.leftovers.none { it.id == itemId }) return
        _uiState.update { it.copy(choices = it.choices + (itemId to choice), removeFailed = false) }
    }

    /**
     * « Finir les courses » (MAG-765): « Prochaine fois » takes the mark off and leaves the article on the list,
     * « Supprimer » takes it off the list; then « Courses finies ». A « Supprimer » the API refuses keeps the
     * article on the step, to finish again.
     */
    fun endRun() {
        val state = _uiState.value
        if (!state.run.finished || state.ended || !state.canEndRun) return
        val leftovers = state.leftovers
        val kept = leftovers.filter { state.choices[it.id] == LeftoverChoice.NEXT_TIME }
        val toRemove = leftovers.filter { state.choices[it.id] == LeftoverChoice.REMOVE }.map { it.id }
        _uiState.update { it.copy(closing = true, removeFailed = false) }
        kept.forEach { tickQueue.markMissing(it.id, false) }
        onPendingMissing(tickQueue.pendingMissing.value)
        viewModelScope.launch {
            val removed = toRemove.filter { listRepository.deleteItem(it).isSuccess }.toSet()
            val keptIds = kept.map { it.id }.toSet()
            _uiState.update { s ->
                s.copy(
                    closing = false,
                    removeFailed = removed.size < toRemove.size,
                    keptForNextTime = s.keptForNextTime + kept,
                    // Read as the API will store it, so the mark does not come back once the queue has sent it.
                    items = s.items
                        .filter { it.id !in removed }
                        .map { if (it.id in keptIds) it.copy(store = it.origin, missingFromStore = null, missingAt = null) else it },
                )
            }
            endIfAnswered()
        }
    }

    // « Fin »: the tour is over and no missing article waits for its answer; the activity status is closed.
    private fun endIfAnswered() {
        val state = _uiState.value
        if (state.ended || !state.run.finished || state.closing || state.leftovers.isNotEmpty()) return
        _uiState.update { it.copy(ended = true) }
        statusRepository.delete(GROCERY_RUN_ACTIVITY)
    }

    /** The ✕ or the back: true when the activity can be left now, false when « Quitter les courses ? » is asked. */
    fun requestQuit(): Boolean {
        val state = _uiState.value
        if (state.ended) return true
        if (state.checkedSinceStart == 0 && state.ticked.isEmpty() && state.pending.isEmpty() && state.pendingMissing.isEmpty()) return true
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
        // Nothing left to take anywhere: the stores of the session stay, so the header and the selection are not lost.
        val sessionStores = if (saved != null && !saved.finished && lines.none { !it.checked }) saved.tour.toSet() + saved.done else emptySet()
        val stores = lines.groupBy { it.homeStore.id }
            .filter { (id, items) -> id == keep || id in sessionStores || items.any { !it.checked } }
            .values
            .map { items -> items.first().homeStore to items.count { !it.checked } }
            .sortedWith(compareBy({ it.first.visitOrder }, { it.first.name }))
            .map { (store, count) -> RunStore(store.id, store.name, count) }
        val checked = list?.items.orEmpty().count { it.checked && it.store != null }
        val baseline = baselineChecked ?: checked.also { baselineChecked = it }
        listId = list?.id
        tickQueue.retainOnly(list?.items.orEmpty().mapNotNull { it.id }.toSet())
        val run = (saved ?: GroceryRunState()).reconciledWith(stores.map { it.id })
        val read = lines.associateBy { it.id }
        _uiState.update {
            it.copy(
                receivedMissing = it.receivedMissing.filter { (id, missing) -> read[id]?.let { item -> (item.missingAt != null) != missing } ?: false },
                // The sheet of an article gone from the list, or ticked elsewhere, does not come back with it.
                missingAsked = it.missingAsked?.takeIf { id -> read[id]?.checked == false },
                homeAisles = it.homeAisles + lines.filter { item -> item.missingFromStore == null }.mapNotNull { item -> item.id?.let { id -> id to item.aisle } },
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
