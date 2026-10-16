package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalesced
import com.maggie.app.data.model.ToSortPile
import com.maggie.app.data.repository.TransactionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * What the « À qualifier · n · montant » pill of the dashboard and the budget says (MAG-458): the size of
 * the pile, read with a page of one line. A failed read leaves the pill as it was: it decorates, it never blocks.
 */
class ToQualifyViewModel(
    private val transactionRepository: TransactionRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _pile = MutableStateFlow<ToSortPile?>(null)
    val pile: StateFlow<ToSortPile?> = _pile

    init {
        refresh()
        subscribeToMercure()
    }

    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.TRANSACTIONS))
                .catch { /* SSE reconnects automatically */ }
                .coalesced()
                .collect { refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            transactionRepository.getToSort(limit = 1).onSuccess { _pile.value = it }
        }
    }
}
