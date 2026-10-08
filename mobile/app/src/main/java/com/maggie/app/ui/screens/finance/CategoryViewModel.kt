package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.CategoryCreateRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.Category
import com.maggie.app.data.repository.CategorizationRuleRepository
import com.maggie.app.data.repository.CategoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** The category the form is open on: [category] is null for a new one. */
data class CategoryEditing(val category: Category? = null)

/**
 * What a deletion takes with it, as far as it could be counted. A `null` count is one that
 * could not be read — the confirmation then says so rather than a wrong number.
 */
data class CategoryDeletionImpact(
    val transactions: Int?,
    val rules: Int?,
    val subCategories: Int,
)

/** A deletion waiting for the yes: [impact] is null while the counts are being read. */
data class CategoryDeletion(
    val category: Category,
    val impact: CategoryDeletionImpact? = null,
)

data class CategoryUiState(
    val categories: List<Category> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val editing: CategoryEditing? = null,
    val isSaving: Boolean = false,
    val deletion: CategoryDeletion? = null,
)

/** The identifier API Platform hands out for a category, as a relation points at it. */
fun categoryIri(id: String): String = "/api/categories/$id"

class CategoryViewModel(
    private val categoryRepository: CategoryRepository,
    private val ruleRepository: CategorizationRuleRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CategoryUiState())
    val uiState: StateFlow<CategoryUiState> = _uiState

    init {
        refresh()
        subscribeToMercure()
    }

    // A change made elsewhere — the admin, Maggie — reaches the list without a pull.
    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.CATEGORIES))
                .catch { /* SSE reconnects automatically */ }
                .collect { refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val categories = categoryRepository.getCategories().getOrThrow()
                val current = _uiState.value
                val open = current.editing?.category
                _uiState.value = current.copy(
                    categories = categories.sortedBy { it.name },
                    isLoading = false,
                    // The form never overwrites what is being typed, but a category deleted
                    // elsewhere leaves nothing to save onto.
                    editing = if (open != null && categories.none { it.id == open.id }) null else current.editing,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
            }
        }
    }

    fun startCreating() {
        _uiState.value = _uiState.value.copy(editing = CategoryEditing())
    }

    fun startEditing(id: String) {
        val category = _uiState.value.categories.firstOrNull { it.id == id } ?: return
        _uiState.value = _uiState.value.copy(editing = CategoryEditing(category))
    }

    fun closeEditor() {
        _uiState.value = _uiState.value.copy(editing = null, deletion = null)
    }

    fun createCategory(request: CategoryCreateRequest) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            try {
                categoryRepository.createCategory(request).getOrThrow()
                _uiState.value = _uiState.value.copy(isSaving = false, editing = null)
                refresh()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSaving = false, error = e.message)
            }
        }
    }

    /** Sends [changes] — only the fields that moved. Nothing moved: nothing is sent. */
    fun updateCategory(id: String, changes: JsonObject) {
        if (changes.isEmpty()) {
            closeEditor()
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            try {
                categoryRepository.updateCategory(id, changes).getOrThrow()
                _uiState.value = _uiState.value.copy(isSaving = false, editing = null)
                refresh()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSaving = false, error = e.message)
            }
        }
    }

    /** Opens the confirmation for the category being edited and reads what the deletion takes. */
    fun askDelete() {
        val category = _uiState.value.editing?.category ?: return
        _uiState.value = _uiState.value.copy(deletion = CategoryDeletion(category))
        viewModelScope.launch {
            val transactions = categoryRepository.countTransactions(category.id).getOrNull()
            val rules = ruleRepository.getRules().getOrNull()?.count { it.categoryId == category.id }
            val subCategories = _uiState.value.categories.count { it.parent == categoryIri(category.id) }
            val pending = _uiState.value.deletion
            if (pending?.category?.id == category.id) {
                _uiState.value = _uiState.value.copy(
                    deletion = pending.copy(impact = CategoryDeletionImpact(transactions, rules, subCategories)),
                )
            }
        }
    }

    fun cancelDelete() {
        _uiState.value = _uiState.value.copy(deletion = null)
    }

    fun confirmDelete() {
        val id = _uiState.value.deletion?.category?.id ?: return
        viewModelScope.launch {
            try {
                categoryRepository.deleteCategory(id).getOrThrow()
                _uiState.value = _uiState.value.copy(
                    // The database takes the sub-categories down with it.
                    categories = _uiState.value.categories.filter { it.id != id && it.parent != categoryIri(id) },
                    editing = null,
                    deletion = null,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(deletion = null, error = e.message)
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
