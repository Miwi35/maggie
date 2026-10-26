package com.maggie.app.ui.screens.cookbook.cooking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalesced
import com.maggie.app.data.repository.ActivityStatusRepository
import com.maggie.app.data.repository.CookingTimerRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn

/**
 * The Cuisine in progress, for the places that launch it (MAG-615): « ▶ Reprendre » instead of « Cuisiner » and
 * « ▶ Lancer ». Read when a screen starts showing it and each time Mercure says the status or a timer moved;
 * a read that fails keeps the last answer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CookingResumeViewModel(
    private val statusRepository: ActivityStatusRepository,
    private val timerRepository: CookingTimerRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private var last: CookingResume? = null

    val resume: StateFlow<CookingResume?> = changes()
        .mapLatest { read().getOrElse { last }.also { last = it } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    private fun changes(): Flow<Unit> = flow {
        emit(Unit)
        val userId = authRepository.getUserId() ?: return@flow
        emitAll(
            listOf(MercureTopics.ACTIVITY_STATUS, MercureTopics.COOKING_TIMERS)
                .map { mercureService.subscribe(MercureTopics.userScoped(userId, it)).catch { /* SSE reconnects automatically */ } }
                .merge()
                .coalesced()
                .map { },
        )
    }

    private suspend fun read(): Result<CookingResume?> = statusRepository.get(COOKING_ACTIVITY).map { status ->
        val session = status?.toCookingSession() ?: return@map null
        val timers = timerRepository.active().getOrDefault(emptyList()).filter { it.status.isCounting }
        CookingResume(session, timers)
    }
}
