package com.maggie.app.ui.screens.bilan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalesced
import com.maggie.app.data.repository.ActivityStatusRepository
import com.maggie.app.util.DateRanges
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
import java.time.Instant

/**
 * The Bilan in progress, for the places that launch it (MAG-751): « ▶ Reprendre » while a bilan of today is
 * left open, nothing from the next morning. Read when a screen starts showing it and each time Mercure says the
 * status moved; a read that fails keeps the last answer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BilanResumeViewModel(
    private val statusRepository: ActivityStatusRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
    private val clock: () -> Instant = { Instant.now() },
) : ViewModel() {
    private var last: BilanSession? = null

    val resume: StateFlow<BilanSession?> = changes()
        .mapLatest { read().getOrElse { last }.also { last = it } }
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

    private suspend fun read(): Result<BilanSession?> = statusRepository.get(BILAN_ACTIVITY).map { status ->
        val today = clock().atZone(DateRanges.zone).toLocalDate()
        status?.toBilanSession()?.takeIf { it.isOpenOn(today) }
    }
}
