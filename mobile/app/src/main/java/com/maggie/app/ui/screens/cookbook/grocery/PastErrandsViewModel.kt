package com.maggie.app.ui.screens.cookbook.grocery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.Errand
import com.maggie.app.data.model.endedAtOrNull
import com.maggie.app.data.repository.ErrandRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class PastErrandsUiState(
    /** The most recent first. */
    val errands: List<Errand> = emptyList(),
    val isLoading: Boolean = false,
    /** A first answer arrived: until then an empty list is not « no errand yet ». */
    val hasLoaded: Boolean = false,
    val isLoadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    val error: String? = null,
    /** The errand open in the detail sheet. */
    val selected: Errand? = null,
    val isDetailLoading: Boolean = false,
    val detailError: String? = null,
) {
    val isEmpty: Boolean get() = hasLoaded && errands.isEmpty() && error == null
}

/**
 * « Courses passées » (MAG-488): the errands « Terminer la course » archived, page by page,
 * and the one open in the sheet. Mercure carries each new errand whole, so it is inserted
 * as it is instead of re-reading the list — the list is served from Elasticsearch, which
 * may not have indexed it yet.
 */
class PastErrandsViewModel(
    private val repository: ErrandRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PastErrandsUiState())
    val uiState: StateFlow<PastErrandsUiState> = _uiState

    private var nextPage = 2
    private var totalItems: Int? = null
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    init {
        refresh()
        subscribe()
    }

    private fun subscribe() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.ERRANDS))
                .catch { /* MercureService reconnects by itself */ }
                .collect { apply(it) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            repository.getErrands(page = 1)
                .onSuccess { page ->
                    nextPage = 2
                    totalItems = page.totalItems
                    val errands = sorted(page.errands)
                    _uiState.value = _uiState.value.copy(
                        errands = errands,
                        isLoading = false,
                        hasLoaded = true,
                        canLoadMore = hasMore(errands.size, page.errands.size),
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = it.message ?: MESSAGE_LOAD)
                }
        }
    }

    /** The next page, appended; an errand already on screen (pushed in the meantime) is not listed twice. */
    fun loadMore() {
        val state = _uiState.value
        if (!state.canLoadMore || state.isLoadingMore || state.isLoading) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingMore = true, error = null)
            repository.getErrands(page = nextPage)
                .onSuccess { page ->
                    nextPage++
                    page.totalItems?.let { totalItems = it }
                    val errands = sorted((_uiState.value.errands + page.errands).distinctBy { it.id })
                    _uiState.value = _uiState.value.copy(
                        errands = errands,
                        isLoadingMore = false,
                        canLoadMore = page.errands.isNotEmpty() && hasMore(errands.size, page.errands.size),
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(isLoadingMore = false, error = it.message ?: MESSAGE_LOAD)
                }
        }
    }

    /** Opens the sheet at once with what the list has; reads the lines when the list did not carry them all. */
    fun openDetail(errand: Errand) {
        val complete = errand.items.size >= errand.itemCount
        _uiState.value = _uiState.value.copy(selected = errand, isDetailLoading = !complete, detailError = null)
        if (complete) return
        viewModelScope.launch {
            repository.getErrand(errand.id)
                .onSuccess { full ->
                    if (_uiState.value.selected?.id != errand.id) return@onSuccess
                    _uiState.value = _uiState.value.copy(
                        selected = full,
                        errands = _uiState.value.errands.map { if (it.id == full.id) full else it },
                        isDetailLoading = false,
                    )
                }
                .onFailure {
                    if (_uiState.value.selected?.id != errand.id) return@onFailure
                    _uiState.value = _uiState.value.copy(isDetailLoading = false, detailError = it.message ?: MESSAGE_DETAIL)
                }
        }
    }

    fun closeDetail() {
        _uiState.value = _uiState.value.copy(selected = null, isDetailLoading = false, detailError = null)
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    // A create carries the whole errand; a payload that does not (a partial update) is re-read.
    private fun apply(event: MercureEvent) {
        val payload = runCatching { json.parseToJsonElement(event.data).jsonObject }.getOrNull() ?: return
        val id = payload["@id"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: return refresh()
        if (payload["deleted"]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } == true) {
            remove(id)
            return
        }
        val errand = runCatching {
            json.decodeFromJsonElement(Errand.serializer(), JsonObject(payload + ("id" to JsonPrimitive(id))))
        }.getOrNull() ?: return refresh()
        upsert(errand)
    }

    private fun upsert(errand: Errand) {
        val state = _uiState.value
        val known = state.errands.any { it.id == errand.id }
        if (!known) totalItems = totalItems?.plus(1)
        val errands = if (known) state.errands.map { if (it.id == errand.id) errand else it } else state.errands + errand
        _uiState.value = state.copy(
            errands = sorted(errands),
            hasLoaded = true,
            selected = if (state.selected?.id == errand.id) errand else state.selected,
        )
    }

    private fun remove(id: String) {
        val state = _uiState.value
        if (state.errands.none { it.id == id }) return
        totalItems = totalItems?.minus(1)
        _uiState.value = state.copy(
            errands = state.errands.filter { it.id != id },
            selected = state.selected?.takeIf { it.id != id },
        )
    }

    // Without a total, a full page says there may be another.
    private fun hasMore(loaded: Int, lastPageSize: Int): Boolean =
        totalItems?.let { loaded < it } ?: (lastPageSize >= PAGE_SIZE)

    private fun sorted(errands: List<Errand>) = errands.sortedByDescending { it.endedAtOrNull()?.toInstant() }

    private companion object {
        /** API Platform's default page size, which the API keeps for errands. */
        const val PAGE_SIZE = 30
        const val MESSAGE_LOAD = "Impossible de charger les courses passées"
        const val MESSAGE_DETAIL = "Impossible de charger les articles de cette course"
    }
}
