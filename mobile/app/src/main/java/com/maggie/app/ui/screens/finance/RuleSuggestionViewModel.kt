package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.AcceptRuleSuggestionsRequest
import com.maggie.app.data.model.AcceptedRuleSuggestion
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.RuleSuggestion
import com.maggie.app.data.model.acceptedRulesSummary
import com.maggie.app.data.repository.CategorizationRuleRepository
import com.maggie.app.data.repository.CategoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class RuleSuggestionUiState(
    val suggestions: List<RuleSuggestion> = emptyList(),
    val categories: List<Category> = emptyList(),
    /** The heading chosen for a merchant, by pattern. Absent means « à choisir ». */
    val choices: Map<String, String> = emptyMap(),
    /** The merchants refused during this visit; the API holds no « refused » list. */
    val dismissed: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val acceptingPattern: String? = null,
    val message: String? = null,
    val error: String? = null,
) {
    fun chosenCategoryId(pattern: String): String? = choices[pattern]

    fun canAccept(pattern: String): Boolean =
        acceptingPattern == null && choices[pattern] != null
}

/**
 * The rules the statement already implies.
 *
 * One merchant at a time: a phone shows a line, a heading and two answers — yes
 * writes the rule and files the history under it, no drops the line. Refusing is
 * not stored anywhere (the API holds no « refused » list), so a merchant that was
 * dropped comes back the next time the screen is opened — exactly as unticking a
 * row in the admin does.
 */
class RuleSuggestionViewModel(
    private val ruleRepository: CategorizationRuleRepository,
    private val categoryRepository: CategoryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RuleSuggestionUiState())
    val uiState: StateFlow<RuleSuggestionUiState> = _uiState

    init {
        refresh()
    }

    /**
     * The headings to choose from, read in the same pass as the suggestions
     * rather than beside them: the chips are the only way to answer here, so a
     * screen without them offers a yes that can never be given — and a failure
     * must neither be swallowed nor wiped by the other load's `error = null`.
     *
     * Read once and kept: an acceptance reloads the suggestions, not the
     * headings, which do not change in between.
     */
    private suspend fun loadCategories() {
        if (_uiState.value.categories.isNotEmpty()) {
            return
        }

        categoryRepository.getCategories()
            .onSuccess { categories ->
                _uiState.value = _uiState.value.copy(
                    categories = categories.sortedBy { it.name },
                )
            }
            .onFailure { failure ->
                _uiState.value = _uiState.value.copy(error = failure.message)
            }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            loadCategories()
            ruleRepository.getSuggestions()
                .onSuccess { suggestions ->
                    val current = _uiState.value
                    val kept = suggestions.filter { it.pattern !in current.dismissed }
                    // Only a merchant the dictionary recognised comes with an
                    // answer: the rest is a question, and a question should not
                    // answer itself. A heading the user picked wins over the guess.
                    val guessed = kept.mapNotNull { suggestion ->
                        suggestion.categoryId?.let { suggestion.pattern to it }
                    }.toMap()
                    val patterns = kept.map { it.pattern }.toSet()

                    _uiState.value = current.copy(
                        suggestions = kept,
                        choices = guessed + current.choices.filterKeys { it in patterns },
                        isLoading = false,
                    )
                }
                .onFailure { failure ->
                    _uiState.value = _uiState.value.copy(isLoading = false, error = failure.message)
                }
        }
    }

    fun chooseCategory(pattern: String, categoryId: String) {
        _uiState.value = _uiState.value.copy(
            choices = _uiState.value.choices + (pattern to categoryId),
        )
    }

    /** Writes the rule and runs it over the history, so the user sees it work. */
    fun accept(pattern: String) {
        val state = _uiState.value
        val suggestion = state.suggestions.firstOrNull { it.pattern == pattern } ?: return
        val categoryId = state.choices[pattern] ?: return
        if (state.acceptingPattern != null) {
            return
        }

        // Marked before the coroutine starts, not inside it: two taps land in the
        // same frame, and one yes must not write the rule twice.
        _uiState.value = state.copy(acceptingPattern = pattern, message = null, error = null)

        viewModelScope.launch {
            ruleRepository.acceptSuggestions(
                AcceptRuleSuggestionsRequest(
                    rules = listOf(
                        AcceptedRuleSuggestion(
                            pattern = pattern,
                            categoryId = categoryId,
                            direction = suggestion.direction,
                        ),
                    ),
                ),
            )
                .onSuccess { result ->
                    _uiState.value = _uiState.value.copy(
                        acceptingPattern = null,
                        message = acceptedRulesSummary(result),
                    )
                    // The merchant is covered now, so it is not a question any
                    // more — and the figures of the others moved with the pass.
                    refresh()
                }
                .onFailure { failure ->
                    _uiState.value = _uiState.value.copy(
                        acceptingPattern = null,
                        error = failure.message,
                    )
                }
        }
    }

    /** Drops the line for this visit. Nothing is written: a no is not a rule. */
    fun dismiss(pattern: String) {
        _uiState.value = _uiState.value.copy(
            suggestions = _uiState.value.suggestions.filter { it.pattern != pattern },
            choices = _uiState.value.choices - pattern,
            dismissed = _uiState.value.dismissed + pattern,
        )
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
