package com.maggie.app.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.SearchResponse
import com.maggie.app.data.model.SearchResult
import com.maggie.app.data.repository.SearchRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val isLoading: Boolean = false,
    val error: String? = null,
)

class SearchViewModel(
    private val searchRepository: SearchRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState

    private var searchJob: Job? = null

    fun onQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(query = query)

        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.value = SearchUiState(query = query)
            return
        }

        searchJob = viewModelScope.launch {
            delay(300)
            search(query, page = 1)
        }
    }

    fun loadNextPage() {
        val state = _uiState.value
        if (state.isLoading || state.results.size >= state.total) return

        viewModelScope.launch {
            search(state.query, page = state.page + 1)
        }
    }

    private suspend fun search(query: String, page: Int) {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        try {
            val response = searchRepository.search(query = query, page = page).getOrThrow()
            val newResults = if (page == 1) response.results else _uiState.value.results + response.results
            _uiState.value = _uiState.value.copy(
                results = newResults,
                total = response.total,
                page = page,
                isLoading = false,
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                error = e.message,
                isLoading = false,
            )
        }
    }
}
