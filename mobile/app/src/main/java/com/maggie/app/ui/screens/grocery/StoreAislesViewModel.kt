package com.maggie.app.ui.screens.grocery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.Aisle
import com.maggie.app.data.repository.StoreRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class StoreAislesUiState(
    val aisles: List<Aisle> = emptyList(),
    val isLoading: Boolean = false,
    /** Why the last change was refused; shown once, then dismissed. */
    val error: String? = null,
)

/**
 * The aisles of one store, in the order of the way through it (MAG-493): added,
 * renamed, moved by finger, deleted. A change made elsewhere (the admin, Maggie)
 * reaches Mercure and the list is read again whole — a store has a dozen aisles.
 */
class StoreAislesViewModel(
    private val storeId: String,
    private val storeRepository: StoreRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StoreAislesUiState())
    val uiState: StateFlow<StoreAislesUiState> = _uiState

    private val json = Json { ignoreUnknownKeys = true }

    init {
        refresh()
        subscribe()
    }

    private fun subscribe() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.AISLES))
                .catch { /* MercureService reconnects by itself */ }
                .collect { event ->
                    // Another store's aisle changes nothing here; a deletion does not say its store.
                    val otherStore = runCatching {
                        json.parseToJsonElement(event.data).jsonObject["storeId"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull()?.let { it != storeId } ?: false
                    if (!otherStore) refresh()
                }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            storeRepository.getAisles(storeId)
                .onSuccess { _uiState.value = _uiState.value.copy(aisles = it, isLoading = false) }
                .onFailure {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    fail(it, MESSAGE_LOAD)
                }
        }
    }

    fun addAisle(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            storeRepository.createAisle(storeId, trimmed)
                .onSuccess { created ->
                    _uiState.value = _uiState.value.copy(
                        aisles = (_uiState.value.aisles.filter { it.id != created.id } + created).sortedBy { it.position },
                    )
                }
                .onFailure { fail(it, MESSAGE_ADD) }
        }
    }

    fun renameAisle(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            storeRepository.renameAisle(id, trimmed)
                .onSuccess { renamed ->
                    _uiState.value = _uiState.value.copy(
                        aisles = _uiState.value.aisles.map { if (it.id == id) it.copy(name = renamed.name) else it },
                    )
                }
                .onFailure { fail(it, MESSAGE_RENAME) }
        }
    }

    /**
     * Follows the finger: the order changes on screen at once, nothing is sent until
     * [endMove]. The aisles keep the position the server gave them meanwhile.
     */
    fun previewMove(fromIndex: Int, toIndex: Int) {
        val aisles = _uiState.value.aisles
        if (fromIndex == toIndex || fromIndex !in aisles.indices || toIndex !in aisles.indices) return
        _uiState.value = _uiState.value.copy(
            aisles = aisles.toMutableList().apply { add(toIndex, removeAt(fromIndex)) },
        )
    }

    /** The finger is lifted: one PATCH with the aisle's new place, 1 being the first. */
    fun endMove(id: String) {
        val aisles = _uiState.value.aisles
        val index = aisles.indexOfFirst { it.id == id }
        if (index == -1) return
        val position = index + 1
        if (aisles[index].position == position) return
        _uiState.value = _uiState.value.copy(
            aisles = aisles.mapIndexed { i, aisle -> aisle.copy(position = i + 1) },
        )
        viewModelScope.launch {
            storeRepository.moveAisle(id, position)
                .onFailure {
                    fail(it, MESSAGE_MOVE)
                    refresh()
                }
        }
    }

    fun deleteAisle(id: String) {
        val before = _uiState.value.aisles
        _uiState.value = _uiState.value.copy(aisles = before.filter { it.id != id })
        viewModelScope.launch {
            storeRepository.deleteAisle(id)
                .onFailure {
                    fail(it, MESSAGE_DELETE)
                    refresh()
                }
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun fail(error: Throwable, fallback: String) {
        _uiState.value = _uiState.value.copy(error = error.message?.takeIf { it.isNotBlank() } ?: fallback)
    }

    companion object {
        const val MESSAGE_LOAD = "Les rayons n'ont pas pu être chargés"
        const val MESSAGE_ADD = "Le rayon n'a pas été ajouté"
        const val MESSAGE_RENAME = "Le rayon n'a pas été renommé"
        const val MESSAGE_MOVE = "Le rayon n'a pas été déplacé"
        const val MESSAGE_DELETE = "Le rayon n'a pas été supprimé"
    }
}
