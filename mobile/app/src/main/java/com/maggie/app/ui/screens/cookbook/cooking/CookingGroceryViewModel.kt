package com.maggie.app.ui.screens.cookbook.cooking

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.GroceryList
import com.maggie.app.data.repository.GroceryListRepository
import com.maggie.app.ui.screens.cookbook.grocery.StoreGroup
import com.maggie.app.ui.screens.cookbook.grocery.buildStoreGroups
import com.maggie.app.ui.screens.cookbook.grocery.withMercureUpdate
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

enum class GroceryLoad { LOADING, READY, ERROR }

data class CookingGroceryUiState(
    /** The list is on screen. Kept in the saved state: a rotation neither opens nor closes it. */
    val open: Boolean = false,
    val load: GroceryLoad = GroceryLoad.LOADING,
    val list: GroceryList? = null,
    /** Why the last tick did not go through; the box went back to where it was. */
    val message: String? = null,
) {
    /** The list's stores as it shows them, what is put off to a later day left out. */
    val groups: List<StoreGroup> get() = buildStoreGroups(list)

    /** What is still to buy, shown on the cart. */
    val remaining: Int get() = remainingCount(groups)
}

/**
 * The shopping list inside the Cuisine activity (MAG-583): read on opening, followed live through Mercure, ticked
 * with one tap. Apart from [CookingViewModel] so that opening or closing it moves nothing in the step.
 */
class CookingGroceryViewModel(
    private val savedState: SavedStateHandle,
    private val groceryListRepository: GroceryListRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CookingGroceryUiState(open = savedState.get<Boolean>(OPEN_KEY) == true))
    val uiState: StateFlow<CookingGroceryUiState> = _uiState

    private val json = Json { ignoreUnknownKeys = true }
    private var loadJob: Job? = null

    init {
        load()
        follow()
    }

    fun open() = setOpen(true)

    fun close() = setOpen(false)

    private fun setOpen(open: Boolean) {
        savedState[OPEN_KEY] = open
        _uiState.update { it.copy(open = open, message = null) }
        // The stream may have missed an update while the list was out of sight.
        if (open) load(quiet = true)
    }

    fun retry() = load()

    fun dismissMessage() = _uiState.update { it.copy(message = null) }

    /** One tap: the box changes at once; if the API refuses, it goes back and says so. */
    fun toggle(item: GroceryItem) {
        val itemId = item.id ?: return
        val current = _uiState.value.list?.items?.firstOrNull { it.id == itemId } ?: return
        val checked = !current.checked
        setChecked(itemId, checked)
        _uiState.update { it.copy(message = null) }
        viewModelScope.launch {
            groceryListRepository.checkItem(itemId, checked).onFailure {
                // Another tap or another device may have moved the box since: only a box still as we left it goes back.
                if (_uiState.value.list?.items?.firstOrNull { item -> item.id == itemId }?.checked == checked) {
                    setChecked(itemId, !checked)
                }
                _uiState.update { it.copy(message = "« ${current.label} » n'a pas pu être ${if (checked) "coché" else "décoché"} : réessaie.") }
            }
        }
    }

    private fun setChecked(itemId: String, checked: Boolean) = _uiState.update { state ->
        state.copy(list = state.list?.let { list -> list.copy(items = list.items.map { if (it.id == itemId) it.copy(checked = checked) else it }) })
    }

    /** [quiet]: a failure keeps what is shown instead of replacing it with the error. */
    private fun load(quiet: Boolean = false) {
        loadJob?.cancel()
        if (!quiet || _uiState.value.load == GroceryLoad.ERROR) _uiState.update { it.copy(load = GroceryLoad.LOADING) }
        loadJob = viewModelScope.launch {
            groceryListRepository.getGroceryList().fold(
                onSuccess = { list -> _uiState.update { it.copy(load = GroceryLoad.READY, list = list) } },
                onFailure = { if (_uiState.value.list == null) _uiState.update { it.copy(load = GroceryLoad.ERROR) } else _uiState.update { it.copy(load = GroceryLoad.READY) } },
            )
        }
    }

    private fun follow() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            launch { mercureService.reconnections(MercureTopics.userScoped(userId, MercureTopics.GROCERY_LISTS)).catch { }.collect { load(quiet = true) } }
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.GROCERY_LISTS))
                .catch { /* SSE reconnects automatically */ }
                .collect { event ->
                    // No list yet (a new account): the first line written creates it, so there is nothing to patch.
                    val list = _uiState.value.list ?: return@collect load(quiet = true)
                    val updated = runCatching { list.withMercureUpdate(json.parseToJsonElement(event.data).jsonObject, json) }.getOrNull()
                    if (updated == null) load(quiet = true) else _uiState.update { it.copy(list = updated) }
                }
        }
    }

    private companion object {
        const val OPEN_KEY = "cooking_grocery_open"
    }
}
