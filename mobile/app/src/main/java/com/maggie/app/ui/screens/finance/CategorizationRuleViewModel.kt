package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.CategorizationRuleCreateRequest
import com.maggie.app.data.model.CategorizationRule
import com.maggie.app.data.model.Category
import com.maggie.app.data.repository.CategorizationRuleRepository
import com.maggie.app.data.repository.CategoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class CategorizationRuleUiState(
    val rules: List<CategorizationRule> = emptyList(),
    val categories: List<Category> = emptyList(),
    val isLoading: Boolean = false,
    val isApplying: Boolean = false,
    val lastApplyMessage: String? = null,
    val error: String? = null,
)

class CategorizationRuleViewModel(
    private val ruleRepository: CategorizationRuleRepository,
    private val categoryRepository: CategoryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CategorizationRuleUiState())
    val uiState: StateFlow<CategorizationRuleUiState> = _uiState

    init {
        refresh()
        loadCategories()
    }

    private fun loadCategories() {
        viewModelScope.launch {
            categoryRepository.getCategories()
                .onSuccess { categories ->
                    _uiState.value = _uiState.value.copy(categories = categories.sortedBy { it.name })
                }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val rules = ruleRepository.getRules().getOrThrow()
                _uiState.value = _uiState.value.copy(
                    rules = rules.sortedByDescending { it.priority },
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
            }
        }
    }

    fun createRule(request: CategorizationRuleCreateRequest) {
        viewModelScope.launch {
            try {
                ruleRepository.createRule(request).getOrThrow()
                refresh()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun deleteRule(id: String) {
        viewModelScope.launch {
            try {
                ruleRepository.deleteRule(id).getOrThrow()
                _uiState.value = _uiState.value.copy(
                    rules = _uiState.value.rules.filter { it.id != id },
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    /** Runs the rules over the uncategorized history and reports the outcome. */
    fun applyRules() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isApplying = true, lastApplyMessage = null)
            try {
                val result = ruleRepository.applyRules().getOrThrow()
                _uiState.value = _uiState.value.copy(
                    isApplying = false,
                    lastApplyMessage = "${result.categorized} transaction(s) catégorisée(s) " +
                        "sur ${result.scanned} analysée(s)",
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isApplying = false, error = e.message)
            }
        }
    }

    fun clearApplyMessage() {
        _uiState.value = _uiState.value.copy(lastApplyMessage = null)
    }
}
