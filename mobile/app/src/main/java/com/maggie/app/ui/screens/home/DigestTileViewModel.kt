package com.maggie.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalesced
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.data.repository.ChatRepository
import com.maggie.app.util.DateRanges
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * The morning digest announced at the head of the Accueil (MAG-808): today's digest, not opened yet, before its
 * expiry (14:00). It goes away when the chat topic says it was opened, or when the expiry passes. Read when a screen
 * starts showing it and each time Mercure says the chat moved; a read that fails keeps the last answer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DigestTileViewModel(
    private val chatRepository: ChatRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
    private val clock: () -> Instant = { Instant.now() },
) : ViewModel() {
    private var last: ChatMessage? = null

    val digest: StateFlow<ChatMessage?> = changes()
        .transformLatest {
            val message = runCatching { chatRepository.latestDigest() }.getOrElse { last }
            last = message
            val expiry = message?.digest?.takeIf { it.openedAt == null }?.expiry()
            val shown = message?.takeIf { isShown(it, expiry) }
            emit(shown)
            if (shown != null && expiry != null) {
                delay((expiry.toEpochMilli() - clock().toEpochMilli()).coerceAtLeast(0))
                emit(null)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    private fun isShown(message: ChatMessage, expiry: Instant?): Boolean {
        val block = message.digest ?: return false
        val now = clock()
        val today = now.atZone(DateRanges.zone).toLocalDate()
        val day = runCatching { LocalDate.parse(block.day) }.getOrNull()
        return day == today && block.openedAt == null && expiry != null && now.isBefore(expiry)
    }

    private fun changes(): Flow<Unit> = flow {
        emit(Unit)
        val userId = authRepository.getUserId() ?: return@flow
        emitAll(
            mercureService.subscribe(MercureTopics.agentScoped(userId, MercureTopics.CHAT))
                .catch { /* SSE reconnects automatically */ }
                .coalesced()
                .map { },
        )
    }

    private fun com.maggie.app.data.model.DigestBlock.expiry(): Instant? =
        runCatching { OffsetDateTime.parse(expiresAt).toInstant() }.getOrNull()
}
