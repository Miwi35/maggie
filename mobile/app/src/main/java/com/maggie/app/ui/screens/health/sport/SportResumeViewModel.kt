package com.maggie.app.ui.screens.health.sport

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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * The Sport session in progress, for the places that launch it (MAG-779): « ▶ Reprendre · 2/6 » instead of « Lancer ».
 * Read when a screen starts showing it and each time Mercure says an activity status moved; a failed read keeps the
 * last answer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SportResumeViewModel(
    private val statusRepository: ActivityStatusRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private var last: SportResume? = null

    val resume: StateFlow<SportResume?> = changes()
        .mapLatest {
            statusRepository.get(SPORT_ACTIVITY).map { it?.toSportResume() }.getOrElse { last }.also { last = it }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    private fun changes(): Flow<Unit> = flow {
        emit(Unit)
        val userId = authRepository.getUserId() ?: return@flow
        emitAll(
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.ACTIVITY_STATUS))
                .catch { /* SSE reconnects automatically */ }
                .coalesced()
                .map { },
        )
    }
}
