package com.maggie.app.ui.screens.grocery.run

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalesced
import com.maggie.app.data.repository.ActivityStatusRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * Whether a Courses tour is in progress, for the Accueil's « ▶ Lancer » (MAG-762): « ▶ Reprendre » when it is.
 * Read when the card starts showing it and each time Mercure says an activity status moved; a failed read keeps
 * the last answer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroceryRunResumeViewModel(
    private val statusRepository: ActivityStatusRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private var last = false

    val resume: StateFlow<Boolean> = changes()
        .mapLatest {
            statusRepository.get(GROCERY_RUN_ACTIVITY).map { it != null }.getOrElse { last }.also { last = it }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    private fun changes(): Flow<Unit> = flow {
        emit(Unit)
        val userId = authRepository.getUserId() ?: return@flow
        mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.ACTIVITY_STATUS))
            .catch { /* SSE reconnects automatically */ }
            .coalesced()
            .collect { emit(Unit) }
    }
}
