package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.CategoryTree
import com.maggie.app.data.model.Contact
import com.maggie.app.data.model.Qualification
import com.maggie.app.data.model.RETROSPECT_UNRATED
import com.maggie.app.data.model.ReviewCounts
import com.maggie.app.data.model.ReviewPeriod
import com.maggie.app.data.model.ReviewPreset
import com.maggie.app.data.model.ReviewQueue
import com.maggie.app.data.model.ReviewQueueItem
import com.maggie.app.data.model.ReviewState
import com.maggie.app.data.model.SortResult
import com.maggie.app.data.model.TO_SORT_REASON_PEER_TRANSFER
import com.maggie.app.data.model.TransferLeg
import com.maggie.app.data.model.counts
import com.maggie.app.data.model.inReviewOrder
import com.maggie.app.data.model.isIn
import com.maggie.app.data.model.reviewMonthOf
import com.maggie.app.data.model.shiftReviewMonth
import com.maggie.app.data.model.sortedSummary
import com.maggie.app.data.repository.CategorizationRuleRepository
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.ContactRepository
import com.maggie.app.data.repository.PeerDebtRepository
import com.maggie.app.data.repository.ReviewRepository
import com.maggie.app.data.repository.TransactionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/** The candidates of « Virement interne »: the other legs the API found for the open line. */
data class TransferChoice(
    val isLoading: Boolean = true,
    val candidates: List<TransferLeg> = emptyList(),
)

/** « Associer à un contact ? » open: the owner's contacts, read when the picker opens. */
data class ContactChoice(
    val isLoading: Boolean = true,
    val contacts: List<Contact> = emptyList(),
)

/** « Corriger » on a recognised line: the category and the qualification the owner is putting by hand in place of the operation's. */
data class ReviewCorrection(
    val line: ReviewQueueItem,
    val categoryId: String?,
    val qualification: Qualification = Qualification.NONE,
    /** The qualification the operation set is being read: saving before would overwrite it with nothing. */
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
) {
    val canSave: Boolean get() = !isLoading && !isSaving && categoryId != null
}

data class ReviewListUiState(
    /** True while a period is being read: the body shows a spinner, the filters stay. */
    val isLoading: Boolean = true,
    /** True once a read has succeeded: before it, an empty list means nothing. */
    val loaded: Boolean = false,
    val state: ReviewState = ReviewState.ALL,
    val period: ReviewPeriod = ReviewPeriod.MONTH,
    /** `YYYY-MM`: the month read when [period] is [ReviewPeriod.MONTH]. */
    val month: String = "",
    /** Every line of the period, the lines to qualify then the recognised ones, as the gestures of the session left them. */
    val items: List<ReviewQueueItem> = emptyList(),
    /** The ids [state] keeps, in the order the fiche walks them: it only changes on a read, a new filter or the way back to the list. */
    val visibleIds: List<String> = emptyList(),
    val counts: ReviewCounts = ReviewCounts(),
    /** The day the last review was completed; null when none was. */
    val sinceReviewDate: String? = null,
    /** The recognised lines checked from here (« Correct », « Corriger »): the server no longer lists them, the list keeps them ticked. */
    val controlledIds: Set<String> = emptySet(),
    val categories: List<Category> = emptyList(),
    /** The line the cursor is on: it stays when the fiche is closed, so the list can show where the owner was. */
    val selectedId: String? = null,
    /** True while a line's fiche is open; false on the list. */
    val ficheOpen: Boolean = false,
    val alwaysForMerchant: Boolean = false,
    /** « Obligatoire » and the type, chosen on the fiche before the category is: they go with the answer. */
    val qualification: Qualification = Qualification.NONE,
    val isSorting: Boolean = false,
    val isJudging: Boolean = false,
    val isControlling: Boolean = false,
    /** A contact being created or linked to the open line. */
    val isLinking: Boolean = false,
    val correction: ReviewCorrection? = null,
    /** The « Créer une opération récurrente » screen, opened from the fiche. */
    val ruleDraft: RuleDraftState? = null,
    val choosingCategory: Boolean = false,
    val transfer: TransferChoice? = null,
    val contactChoice: ContactChoice? = null,
    val message: String? = null,
    /** A list that could not be read. */
    val loadError: String? = null,
    /** A gesture that was refused or lost: the line stays where it was. */
    val sortError: String? = null,
    /** A satisfaction that was refused or lost: the line keeps the one it had. */
    val judgeError: String? = null,
    /** A check that was refused or lost. */
    val controlError: String? = null,
    /** A link refused (the label is someone else's) or lost, in the server's words. */
    val contactError: String? = null,
    /** « Ce virement est » of the open line, when it asks; null until its allocation is read. */
    val peer: PeerTransferEditor? = null,
    val peerContacts: ContactChoice? = null,
) {
    /** The lines [state] keeps, in the fiche's order, as they are now. */
    val visible: List<ReviewQueueItem>
        get() {
            val byId = items.associateBy { it.id }
            return visibleIds.mapNotNull(byId::get)
        }

    val unrecognisedRows: List<ReviewQueueItem> get() = visible.filter { !it.recognised }

    val recognisedRows: List<ReviewQueueItem> get() = visible.filter { it.recognised }

    val current: ReviewQueueItem? get() = selectedId?.let { id -> items.firstOrNull { it.id == id } }

    /** The editor of the open line only: one read for another line never shows on this fiche. */
    val currentPeer: PeerTransferEditor? get() = peer?.takeIf { it.transactionId == current?.id && current?.asksForPeerKind == true }

    /** 1-based place of [current] among the lines the filter keeps, 0 when none. */
    val position: Int get() = selectedId?.let { id -> visibleIds.indexOf(id) + 1 } ?: 0

    /** « 3 / 7 »: the filter's total. */
    val total: Int get() = visibleIds.size

    val hasPrevious: Boolean get() = position > 1

    val hasNext: Boolean get() = position in 1 until total

    val isEmpty: Boolean get() = loaded && visibleIds.isEmpty()

    val canValidate: Boolean get() = !isSorting && current?.suggestion != null && current?.recognised == false

    fun isControlled(line: ReviewQueueItem): Boolean = line.id in controlledIds
}

/**
 * The Revue (MAG-539): one list for the lines to qualify and the recognised ones, with a state pill, a period and a fiche.
 *
 * The period is read once with every state; the state pill filters on the phone, so the pills' counts, the fiche's
 * « 3 / 7 » and every gesture work on the same lines. A gesture changes its line in place: a validated line becomes
 * « Reconnue », the fiche stays on it, and « Suivant » goes to the next line the filter kept when the filter was applied.
 * The filters live here, so they survive a rotation and the way back from the fiche. The gestures are those of the three
 * pages this one replaces: qualify (« Valider », « Autre catégorie », « Toujours pour ce marchand », « Qualification »,
 * « Virement interne », « Créer une opération récurrente », the contact, « Ce virement est »), check (« Correct », « Corriger »)
 * and judge the spend.
 */
class ReviewListViewModel(
    private val reviewRepository: ReviewRepository,
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    ruleRepository: CategorizationRuleRepository,
    private val contactRepository: ContactRepository,
    private val peerDebtRepository: PeerDebtRepository,
    private val today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReviewListUiState(month = reviewMonthOf(today)))
    val uiState: StateFlow<ReviewListUiState> = _uiState

    private var appliedPreset: ReviewPreset? = null

    /** The lines a gesture of this session changed: a read does not take them back. */
    private val touched = mutableSetOf<String>()

    /** The read in flight: an older one that comes back late is dropped. */
    private var readCount = 0

    // The rule is also applied to the history: its other lines are filed in the same stroke.
    private val ruleDraftSession = RuleDraftSession(
        scope = viewModelScope,
        ruleRepository = ruleRepository,
        read = { _uiState.value.ruleDraft },
        write = { _uiState.value = _uiState.value.copy(ruleDraft = it) },
        applyToExisting = true,
        onSaved = {
            _uiState.value = _uiState.value.copy(qualification = Qualification.NONE, message = "Opération récurrente créée")
            refreshQuietly()
        },
    )

    /**
     * The entry point's filters. A [preset] already applied changes nothing (a rotation, the way back from a sheet); a new one
     * replaces the filters. Without a preset the list opens on its defaults, once.
     */
    fun open(preset: ReviewPreset?) {
        if (preset == null) {
            if (appliedPreset == null) {
                appliedPreset = ReviewPreset.MONTHLY
                load()
            }
            return
        }
        if (preset == appliedPreset && _uiState.value.loaded) {
            return
        }
        appliedPreset = preset
        _uiState.value = _uiState.value.copy(
            state = preset.state,
            period = preset.period,
            month = preset.month ?: reviewMonthOf(today),
            ficheOpen = false,
            selectedId = null,
        )
        load()
    }

    /** Reads the period again, from scratch. */
    fun refresh() = load()

    private fun load() {
        val state = _uiState.value
        val read = ++readCount
        _uiState.value = state.copy(isLoading = true, loaded = false, loadError = null)

        viewModelScope.launch {
            loadCategories()
            reviewRepository.getList(state.period, state.month)
                .onSuccess { queue ->
                    if (read != readCount) return@onSuccess
                    touched.clear()
                    val items = queue.items
                    val now = _uiState.value
                    _uiState.value = now.copy(
                        isLoading = false,
                        loaded = true,
                        loadError = null,
                        items = items,
                        visibleIds = idsOf(items, now.state),
                        counts = queue.counts(),
                        sinceReviewDate = queue.sinceReviewDate,
                        controlledIds = emptySet(),
                        selectedId = now.selectedId?.takeIf { id -> items.any { it.id == id } },
                        ficheOpen = now.ficheOpen && items.any { it.id == now.selectedId },
                    )
                    loadPeerOfCurrent()
                }
                .onFailure { failure ->
                    if (read != readCount) return@onFailure
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        loadError = failure.message ?: "Chargement impossible.",
                    )
                }
        }
    }

    // The headings do not change between two gestures: read once, kept.
    private suspend fun loadCategories() {
        if (_uiState.value.categories.isNotEmpty()) return
        categoryRepository.getCategories()
            .onSuccess { categories -> _uiState.value = _uiState.value.copy(categories = categories) }
    }

    /**
     * A read after a gesture that may have filed other lines (a rule, a link): the lines the session changed keep their
     * state, the others follow the server, and the counts add what the server does not know about.
     */
    private fun refreshQuietly() {
        val state = _uiState.value
        val read = ++readCount

        viewModelScope.launch {
            reviewRepository.getList(state.period, state.month).onSuccess { queue ->
                if (read != readCount) return@onSuccess
                mergeRead(queue)
            }
        }
    }

    private fun mergeRead(queue: ReviewQueue) {
        val now = _uiState.value
        val local = now.items.associateBy { it.id }
        val server = queue.items.associateBy { it.id }
        val kept = touched.mapNotNull(local::get)

        // What the server counted as waiting or does not list at all but the session knows as recognised.
        var counts = queue.counts()
        kept.filter { it.recognised }.forEach { line ->
            val onServer = server[line.id]
            counts = when {
                onServer == null -> counts.withRecognised(1)
                !onServer.recognised -> counts.unrecognisedBecameRecognised(line.amountCents)
                else -> counts
            }
        }

        val merged = queue.items.map { if (it.id in touched) local[it.id] ?: it else it } +
            kept.filter { it.id !in server }
        val ids = merged.map { it.id }.toSet()
        val previous = now.visibleIds.filter { it in ids }
        val fresh = idsOf(merged.filter { it.id !in previous.toSet() }, now.state)
        val cursor = now.selectedId?.let { id ->
            if (id in ids) id else neighbourAfterRemoval(now.visibleIds, id, previous + fresh)
        }

        _uiState.value = now.copy(
            items = merged,
            visibleIds = previous + fresh,
            counts = counts,
            sinceReviewDate = queue.sinceReviewDate,
            selectedId = cursor,
            ficheOpen = now.ficheOpen && cursor != null,
        )
        loadPeerOfCurrent()
    }

    private fun idsOf(items: List<ReviewQueueItem>, state: ReviewState): List<String> =
        items.filter { it.isIn(state) }.inReviewOrder().map { it.id }

    /** A state pill: the same lines, filtered; the fiche's order is the list's again. */
    fun setState(state: ReviewState) {
        val now = _uiState.value
        if (now.state == state) return
        _uiState.value = now.copy(state = state, visibleIds = idsOf(now.items, state), ficheOpen = false, selectedId = keepCursor(now, state))
    }

    // The cursor stays when its line is still shown, so the list can show where the owner was.
    private fun keepCursor(now: ReviewListUiState, state: ReviewState): String? =
        now.selectedId?.takeIf { id -> now.items.firstOrNull { it.id == id }?.isIn(state) == true }

    fun setPeriod(period: ReviewPeriod) {
        val now = _uiState.value
        if (now.period == period) return
        _uiState.value = now.copy(period = period, ficheOpen = false, selectedId = null)
        load()
    }

    /** A month of the selector (`YYYY-MM`); the period becomes that month. */
    fun setMonth(month: String) {
        val now = _uiState.value
        if (now.month == month && now.period == ReviewPeriod.MONTH) return
        _uiState.value = now.copy(period = ReviewPeriod.MONTH, month = month, ficheOpen = false, selectedId = null)
        load()
    }

    fun previousMonth() = setMonth(shiftReviewMonth(_uiState.value.month, -1))

    fun nextMonth() = setMonth(shiftReviewMonth(_uiState.value.month, +1))

    /** Opens the fiche of [id]. */
    fun select(id: String) {
        if (_uiState.value.visibleIds.none { it == id }) return
        _uiState.value = _uiState.value.copy(selectedId = id, ficheOpen = true, alwaysForMerchant = false, qualification = Qualification.NONE)
        loadPeerOfCurrent()
    }

    /** Back to the list, which shows the filter again: a line that no longer fits leaves it. The cursor stays on the line last open. */
    fun backToList() {
        val now = _uiState.value
        _uiState.value = now.copy(ficheOpen = false, visibleIds = idsOf(now.items, now.state))
    }

    /** « Suivant »: the next line of the filter, in its order. */
    fun next() = move(+1)

    /** « Précédent »: the previous line of the filter, in its order. */
    fun previous() = move(-1)

    private fun move(step: Int) {
        val now = _uiState.value
        if (now.isSorting || now.position == 0) return
        val target = now.visibleIds.getOrNull(now.position - 1 + step) ?: return
        _uiState.value = now.copy(selectedId = target, alwaysForMerchant = false, qualification = Qualification.NONE)
        loadPeerOfCurrent()
    }

    // Changes one line in place, and remembers a read must not take the change back.
    private fun patch(id: String, change: (ReviewQueueItem) -> ReviewQueueItem) {
        touched += id
        _uiState.value = _uiState.value.copy(items = _uiState.value.items.map { if (it.id == id) change(it) else it })
    }

    // The line is filed by hand: it is recognised now, no longer by an operation, and the pills follow.
    private fun markRecognised(line: ReviewQueueItem, categoryId: String?, categoryPath: String?) {
        val wasWaiting = _uiState.value.items.firstOrNull { it.id == line.id }?.recognised == false
        patch(line.id) {
            it.copy(
                recognised = true,
                categoryId = categoryId,
                categoryPath = categoryPath,
                qualifiedBy = null,
                qualifiedBySeriesId = null,
                qualifiedByRuleId = null,
                operationLabel = null,
            )
        }
        if (wasWaiting) {
            _uiState.value = _uiState.value.copy(counts = _uiState.value.counts.unrecognisedBecameRecognised(line.amountCents))
        }
    }

    // The question needs the créances the transfer may cover: read once per line, when it becomes the fiche.
    private fun loadPeerOfCurrent() {
        val line = _uiState.value.current?.takeIf { _uiState.value.ficheOpen || it.asksForPeerKind } ?: return
        if (!line.asksForPeerKind || _uiState.value.peer?.transactionId == line.id) return
        _uiState.value = _uiState.value.copy(peer = null)
        viewModelScope.launch {
            peerDebtRepository.getAllocation(line.id).onSuccess { allocation ->
                if (_uiState.value.current?.id == line.id) {
                    _uiState.value = _uiState.value.copy(peer = PeerTransferEditor.of(allocation, line.label))
                }
            }
        }
    }

    private fun updatePeer(change: (PeerTransferEditor) -> PeerTransferEditor) {
        val peer = _uiState.value.currentPeer ?: return
        _uiState.value = _uiState.value.copy(peer = change(peer))
    }

    fun choosePeerKind(kind: String) = updatePeer { it.withKind(kind) }

    fun togglePeerShare(shareId: String) = updatePeer { it.toggle(shareId) }

    fun changePeerShareAmount(shareId: String, cents: Int) = updatePeer { it.withShareAmount(shareId, cents) }

    fun changePeerDebtAmount(cents: Int) = updatePeer { it.withDebtAmount(cents) }

    fun changePeerSubject(text: String) = updatePeer { it.withSubject(text) }

    fun changePeerNotes(text: String) = updatePeer { it.withNotes(text) }

    /**
     * « Enregistrer »: the answer is written. A line that only waited for it becomes recognised in place; one still
     * without a category keeps waiting, without the question.
     */
    fun savePeer() {
        val state = _uiState.value
        val line = state.current ?: return
        val peer = state.currentPeer ?: return
        val request = peer.toRequest() ?: return
        if (!peer.canSave) return
        _uiState.value = state.copy(peer = peer.copy(isSaving = true, error = null, message = null))

        viewModelScope.launch {
            peerDebtRepository.setAllocation(line.id, request)
                .onSuccess { saved ->
                    if (line.reason == TO_SORT_REASON_PEER_TRANSFER) {
                        markRecognised(line, line.categoryId, line.categoryPath)
                    }
                    patch(line.id) { it.copy(asksForPeerKind = false) }
                    _uiState.value = _uiState.value.copy(peer = null, message = peerSavedMessage(saved.peerKind))
                    refreshQuietly()
                }
                .onFailure { failure ->
                    _uiState.value.peer?.let {
                        _uiState.value = _uiState.value.copy(peer = it.copy(isSaving = false, error = failure.message ?: "Enregistrement impossible."))
                    }
                }
        }
    }

    /** « Envers qui » / « Qui te doit »: the full-screen picker, on the owner's contacts. */
    fun openPeerContactPicker() {
        if (_uiState.value.currentPeer == null) return
        _uiState.value = _uiState.value.copy(peerContacts = ContactChoice())
        viewModelScope.launch {
            // No list is no reason to refuse: « + Créer » stays possible.
            val contacts = contactRepository.getContacts().getOrDefault(emptyList())
            _uiState.value.peerContacts?.let {
                _uiState.value = _uiState.value.copy(peerContacts = ContactChoice(isLoading = false, contacts = contacts))
            }
        }
    }

    fun closePeerContactPicker() {
        _uiState.value = _uiState.value.copy(peerContacts = null)
    }

    fun pickPeerContact(contact: Contact) {
        closePeerContactPicker()
        updatePeer { it.withPerson(contact) }
    }

    /** « + Créer « Inès » »: the contact is created, then becomes the person of the dette or créance. */
    fun createPeerContact(name: String) {
        if (name.isBlank()) return
        closePeerContactPicker()
        val lineId = _uiState.value.current?.id ?: return
        viewModelScope.launch {
            contactRepository.createContact(name.trim())
                .onSuccess { contact -> if (_uiState.value.current?.id == lineId) pickPeerContact(contact) }
                .onFailure { e -> updatePeer { it.copy(error = e.message ?: "Création impossible.") } }
        }
    }

    fun setQualification(qualification: Qualification) {
        _uiState.value = _uiState.value.copy(qualification = qualification)
    }

    fun setAlwaysForMerchant(always: Boolean) {
        _uiState.value = _uiState.value.copy(alwaysForMerchant = always)
    }

    /** « Valider » on a line to qualify: the suggested heading, as it is. */
    fun validate() {
        val line = _uiState.value.current?.takeIf { !it.recognised } ?: return
        val suggestion = line.suggestion ?: return
        sortToCategory(suggestion.categoryId, suggestion.categoryPath)
    }

    fun openCategorySearch() {
        _uiState.value = _uiState.value.copy(choosingCategory = true)
    }

    fun closeCategorySearch() {
        _uiState.value = _uiState.value.copy(choosingCategory = false)
    }

    /** « Autre catégorie », once picked in the full-screen search. */
    fun chooseCategory(categoryId: String) {
        _uiState.value = _uiState.value.copy(choosingCategory = false)
        sortToCategory(categoryId, CategoryTree(_uiState.value.categories).pathOf(categoryId))
    }

    private fun sortToCategory(categoryId: String, categoryPath: String?) {
        val state = _uiState.value
        val line = state.current?.takeIf { !it.recognised } ?: return
        if (state.isSorting) return

        // Marked before the coroutine starts: two taps land in the same frame, and one answer must not be sent twice.
        _uiState.value = state.copy(isSorting = true, sortError = null, message = null)

        viewModelScope.launch {
            val result = transactionRepository.sortToCategory(line.id, categoryId, state.alwaysForMerchant)
                .getOrElse { return@launch failedSort(it) }
            // The sort does not carry « Obligatoire » nor the type: they follow, on the line it just filed.
            if (state.qualification != Qualification.NONE) {
                transactionRepository.setQualification(line.id, categoryId, state.qualification)
                    .onFailure {
                        // The line is filed all the same: it is recognised, the owner is told what was lost.
                        afterSort(line, categoryId, categoryPath, result)
                        _uiState.value = _uiState.value.copy(sortError = it.message ?: "Classement impossible.")
                        return@launch
                    }
            }
            afterSort(line, categoryId, categoryPath, result)
        }
    }

    /** « Virement interne »: opens the lines it may be the other leg of. */
    fun openTransfer() {
        val line = _uiState.value.current ?: return
        _uiState.value = _uiState.value.copy(transfer = TransferChoice())

        viewModelScope.launch {
            transactionRepository.getTransferCandidates(line.id)
                .onSuccess { candidates ->
                    _uiState.value = _uiState.value.copy(transfer = TransferChoice(isLoading = false, candidates = candidates))
                }
                // No candidate list is no reason to refuse the answer: « sans contrepartie » stays possible.
                .onFailure {
                    _uiState.value = _uiState.value.copy(transfer = TransferChoice(isLoading = false))
                }
        }
    }

    fun closeTransfer() {
        _uiState.value = _uiState.value.copy(transfer = null)
    }

    /** [counterpartId] null: the other leg is not imported. */
    fun markAsInternalTransfer(counterpartId: String?) {
        val state = _uiState.value
        val line = state.current?.takeIf { !it.recognised } ?: return
        if (state.isSorting) return

        _uiState.value = state.copy(isSorting = true, transfer = null, sortError = null, message = null)

        viewModelScope.launch {
            transactionRepository.sortAsInternalTransfer(line.id, counterpartId)
                .onSuccess { afterSort(line, null, "Virement interne", it) }
                .onFailure { failedSort(it) }
        }
    }

    // The answered line becomes « Reconnue » in place, the fiche stays; the read that follows only brings what a rule filed besides.
    private fun afterSort(line: ReviewQueueItem, categoryId: String?, categoryPath: String?, result: SortResult) {
        markRecognised(line, categoryId, categoryPath)
        _uiState.value = _uiState.value.copy(
            isSorting = false,
            alwaysForMerchant = false,
            qualification = Qualification.NONE,
            message = sortedSummary(result),
        )
        // A rule filed other lines of the merchant: only then is a second read worth it.
        if (result.alsoSorted > 0 || result.ruleCreated) refreshQuietly()
    }

    private fun failedSort(failure: Throwable) {
        _uiState.value = _uiState.value.copy(
            isSorting = false,
            sortError = failure.message ?: "Classement impossible.",
        )
    }

    /** « À conserver » / « Superflue »: tapping the verdict the line already has takes it back. */
    fun judge(verdict: String) {
        val state = _uiState.value
        val line = state.current ?: return
        if (!line.judgeable || state.isJudging) return
        val next = if (line.retrospect == verdict) RETROSPECT_UNRATED else verdict

        _uiState.value = state.copy(isJudging = true, judgeError = null)

        viewModelScope.launch {
            transactionRepository.setRetrospect(line.id, next)
                .onSuccess {
                    patch(line.id) { it.copy(retrospect = next) }
                    _uiState.value = _uiState.value.copy(isJudging = false)
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(isJudging = false, judgeError = it.message ?: "Jugement impossible.")
                }
        }
    }

    /** « Correct » on a recognised line: it is checked, and stays in the list, ticked. */
    fun control() {
        val state = _uiState.value
        val line = state.current?.takeIf { it.recognised && it.id !in state.controlledIds } ?: return
        if (state.isControlling) return

        // Marked before the coroutine starts: two taps land in the same frame, and one check must not be sent twice.
        _uiState.value = state.copy(isControlling = true, controlError = null, message = null)

        viewModelScope.launch {
            transactionRepository.controlRecognised(listOf(line.id))
                .onSuccess {
                    touched += line.id
                    _uiState.value = _uiState.value.copy(isControlling = false, controlledIds = _uiState.value.controlledIds + line.id)
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(isControlling = false, controlError = it.message ?: "Contrôle impossible.")
                }
        }
    }

    /** « Corriger »: opens the qualification by hand, starting from the line's own category and what the operation set. */
    fun startCorrection() {
        val line = _uiState.value.current?.takeIf { it.recognised } ?: return
        _uiState.value = _uiState.value.copy(correction = ReviewCorrection(line, line.categoryId), controlError = null)

        viewModelScope.launch {
            transactionRepository.getTransaction(line.id)
                .onSuccess { transaction ->
                    updateCorrection(line.id) { it.copy(qualification = transaction.qualification, isLoading = false) }
                }
                .onFailure {
                    // Saving over a qualification that could not be read would erase it: the line stays as it is.
                    if (_uiState.value.correction?.line?.id == line.id) {
                        _uiState.value = _uiState.value.copy(correction = null, controlError = it.message ?: "Correction impossible.")
                    }
                }
        }
    }

    fun setCorrectionCategory(categoryId: String?) = updateCorrection { it.copy(categoryId = categoryId) }

    fun setCorrectionQualification(qualification: Qualification) = updateCorrection { it.copy(qualification = qualification) }

    fun cancelCorrection() {
        if (_uiState.value.correction?.isSaving == true) return
        _uiState.value = _uiState.value.copy(correction = null)
    }

    /** Files the line by hand: no longer recognised by an operation, it stays « Reconnue », checked, with the category given. */
    fun saveCorrection() {
        val correction = _uiState.value.correction ?: return
        val categoryId = correction.categoryId
        if (!correction.canSave || categoryId == null) return

        updateCorrection { it.copy(isSaving = true, error = null) }

        viewModelScope.launch {
            transactionRepository.setQualification(correction.line.id, categoryId, correction.qualification)
                .onSuccess {
                    markRecognised(correction.line, categoryId, CategoryTree(_uiState.value.categories).pathOf(categoryId))
                    _uiState.value = _uiState.value.copy(
                        correction = null,
                        controlledIds = _uiState.value.controlledIds + correction.line.id,
                        message = "Ligne corrigée",
                    )
                }
                .onFailure { failure ->
                    updateCorrection { it.copy(isSaving = false, error = failure.message ?: "Correction impossible.") }
                }
        }
    }

    private fun updateCorrection(lineId: String? = null, change: (ReviewCorrection) -> ReviewCorrection) {
        val correction = _uiState.value.correction ?: return
        if (lineId != null && correction.line.id != lineId) return
        _uiState.value = _uiState.value.copy(correction = change(correction))
    }

    /** « Associer à un contact ? »: opens the full-screen picker on the owner's contacts. */
    fun openContactPicker() {
        if (_uiState.value.current == null) return
        _uiState.value = _uiState.value.copy(contactChoice = ContactChoice())

        viewModelScope.launch {
            // No list is no reason to refuse the answer: « + Créer » stays possible.
            val contacts = contactRepository.getContacts().getOrDefault(emptyList())
            _uiState.value.contactChoice?.let {
                _uiState.value = _uiState.value.copy(contactChoice = ContactChoice(isLoading = false, contacts = contacts))
            }
        }
    }

    fun closeContactPicker() {
        _uiState.value = _uiState.value.copy(contactChoice = null)
    }

    /** An existing contact, picked: the line's label becomes theirs. */
    fun pickContact(contact: Contact) {
        linkCurrent(contact.name) { Result.success(contact) }
    }

    /** « + Créer « Tom » »: the contact is created first, then the label is linked to them. */
    fun createContact(name: String) {
        if (name.isBlank()) return
        linkCurrent(name.trim()) { contactRepository.createContact(name.trim()) }
    }

    private fun linkCurrent(name: String, contactOf: suspend () -> Result<Contact>) {
        val state = _uiState.value
        val line = state.current ?: return
        if (state.isLinking) return
        _uiState.value = state.copy(isLinking = true, contactChoice = null, contactError = null, message = null)

        viewModelScope.launch {
            contactOf()
                .fold(
                    onSuccess = { contact -> contactRepository.link(contact.id, line.counterpartyLabel.ifBlank { line.label }) },
                    onFailure = { Result.failure(it) },
                )
                .onSuccess {
                    _uiState.value = _uiState.value.copy(isLinking = false, message = "Associé à $name")
                    refreshQuietly()
                }
                .onFailure { failure ->
                    _uiState.value = _uiState.value.copy(
                        isLinking = false,
                        contactError = failure.message ?: "Association impossible.",
                    )
                }
        }
    }

    /** « Créer une opération récurrente »: the rule draft, filled from the open line and the qualification chosen on the fiche. */
    fun openRuleDraft() {
        val state = _uiState.value
        val line = state.current ?: return
        ruleDraftSession.open(line.label, line.amountCents, line.suggestion?.categoryId ?: line.categoryId, state.qualification)
    }

    fun closeRuleDraft() = ruleDraftSession.close()

    fun changeRulePattern(text: String) = ruleDraftSession.changePattern(text)

    fun changeRuleAmount(amount: String, tolerance: String) = ruleDraftSession.changeAmount(amount, tolerance)

    fun changeRuleCategory(categoryId: String?) = ruleDraftSession.changeCategory(categoryId)

    fun changeRuleQualification(qualification: Qualification) = ruleDraftSession.changeQualification(qualification)

    fun saveRule() = ruleDraftSession.save()

    fun clearContactError() {
        _uiState.value = _uiState.value.copy(contactError = null)
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    fun clearSortError() {
        _uiState.value = _uiState.value.copy(sortError = null)
    }

    fun clearJudgeError() {
        _uiState.value = _uiState.value.copy(judgeError = null)
    }

    fun clearControlError() {
        _uiState.value = _uiState.value.copy(controlError = null)
    }
}
