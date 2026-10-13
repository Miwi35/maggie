package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.CategoryCreateRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.CategoryRow
import com.maggie.app.data.model.CategoryTree
import com.maggie.app.data.model.MAX_CATEGORY_DEPTH
import com.maggie.app.data.model.categoryIri
import com.maggie.app.data.repository.CategoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The category the form is open on: [category] is null for a new one, [parent] the IRI it starts under. */
data class CategoryEditing(val category: Category? = null, val parent: String? = null)

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
    // The ids whose children are shown in the tree.
    val expanded: Set<String> = emptySet(),
    // [error] is the server's own sentence (a refused level), fit to show as it is.
    val errorIsRefusal: Boolean = false,
) {
    val tree: CategoryTree get() = CategoryTree(categories)

    val rows: List<CategoryRow> get() = tree.rows(expanded)

    /** What the open form can be attached to: levels 1 and 2, never itself nor its descendants. */
    val parentCandidates: List<Category> get() = tree.parentCandidates(editing?.category?.id)
}

class CategoryViewModel(
    private val categoryRepository: CategoryRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CategoryUiState())
    val uiState: StateFlow<CategoryUiState> = _uiState

    // The first load opens the main categories; after that the tree stays as the user left it.
    private var expansionSeeded = false

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
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, errorIsRefusal = false)
            try {
                val categories = categoryRepository.getCategories().getOrThrow()
                val current = _uiState.value
                val open = current.editing?.category
                val seed = if (expansionSeeded) emptySet() else CategoryTree(categories).children(null).map { it.id }
                expansionSeeded = true
                _uiState.value = current.copy(
                    categories = categories.sortedBy { it.name },
                    expanded = current.expanded + seed,
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

    /** Opens the form for a new category, under [parentId] when given and still room under it. */
    fun startCreating(parentId: String? = null) {
        val tree = _uiState.value.tree
        if (parentId != null && tree.level(parentId) >= MAX_CATEGORY_DEPTH) return
        _uiState.value = _uiState.value.copy(editing = CategoryEditing(parent = parentId?.let(::categoryIri)))
    }

    fun toggleExpanded(id: String) {
        val expanded = _uiState.value.expanded
        _uiState.value = _uiState.value.copy(expanded = if (id in expanded) expanded - id else expanded + id)
    }

    // The new node must be in sight when the list comes back.
    private fun withParentOpen(parentIri: String?): Set<String> {
        val parentId = parentIri?.substringAfterLast('/') ?: return _uiState.value.expanded
        return _uiState.value.expanded + parentId + _uiState.value.tree.ancestors(parentId)
    }

    private fun fail(e: Exception) {
        _uiState.value = _uiState.value.copy(
            isSaving = false,
            error = e.message,
            errorIsRefusal = e is ApiRefusalException,
        )
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
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    editing = null,
                    expanded = withParentOpen(request.parent),
                )
                refresh()
            } catch (e: Exception) {
                fail(e)
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
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    editing = null,
                    expanded = withParentOpen((changes["parent"] as? JsonPrimitive)?.contentOrNull),
                )
                refresh()
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** Opens the confirmation for the category being edited and reads what the deletion takes. */
    fun askDelete() {
        val category = _uiState.value.editing?.category ?: return
        _uiState.value = _uiState.value.copy(deletion = CategoryDeletion(category))
        viewModelScope.launch {
            // The database takes the whole subtree down with it, and its transactions
            // and rules with them: all of it counts.
            val children = _uiState.value.tree.descendants(category.id)
            val ids = listOf(category.id) + children
            val counts = ids.map { categoryRepository.countTransactions(it).getOrNull() }
            val transactions = if (counts.any { it == null }) null else counts.sumOf { it ?: 0 }
            val rules = categoryRepository.countRules(ids.toSet()).getOrNull()
            val pending = _uiState.value.deletion
            if (pending?.category?.id == category.id) {
                _uiState.value = _uiState.value.copy(
                    deletion = pending.copy(impact = CategoryDeletionImpact(transactions, rules, children.size)),
                )
            }
        }
    }

    fun cancelDelete() {
        _uiState.value = _uiState.value.copy(deletion = null)
    }

    fun confirmDelete() {
        val id = _uiState.value.deletion?.category?.id ?: return
        val gone = _uiState.value.tree.descendants(id) + id
        // The question is closed first: a second tap finds nothing to confirm.
        _uiState.value = _uiState.value.copy(deletion = null)
        viewModelScope.launch {
            try {
                categoryRepository.deleteCategory(id).getOrThrow()
                _uiState.value = _uiState.value.copy(
                    categories = _uiState.value.categories.filter { it.id !in gone },
                    editing = null,
                )
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null, errorIsRefusal = false)
    }
}
