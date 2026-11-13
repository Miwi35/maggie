package com.maggie.app.ui.screens.bilan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalesced
import com.maggie.app.data.model.UserPreference
import com.maggie.app.data.repository.ActivityStatusRepository
import com.maggie.app.data.repository.UserPreferenceRepository
import com.maggie.app.util.DateRanges
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import java.time.Instant
import java.time.LocalTime

/** The Bilan tile of the Accueil: « Reprendre » at its [session]'s step, or « Ouvrir » while none has started. */
data class BilanTile(val session: BilanSession?)

/**
 * The Bilan tile, for the places that launch it (MAG-751, MAG-826): « Reprendre » while a bilan of today is left open,
 * « Ouvrir » from the review time set in the settings while none has started (when the review is on), nothing once
 * « Bonne nuit » was said and nothing from the next morning. Read when a screen starts showing it, each time Mercure
 * says the status moved and when the review time comes; a read that fails keeps the last answer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BilanResumeViewModel(
    private val statusRepository: ActivityStatusRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
    private val preferenceRepository: UserPreferenceRepository,
    private val doneStore: BilanDoneStore,
    private val clock: () -> Instant = { Instant.now() },
) : ViewModel() {
    private var lastSession: BilanSession? = null
    private var lastPreference: UserPreference? = null

    val tile: StateFlow<BilanTile?> = changes()
        .transformLatest {
            val session = statusRepository.get(BILAN_ACTIVITY).map { status ->
                status?.toBilanSession()?.takeIf { it.isOpenOn(today()) }
            }.getOrElse { lastSession }.also { lastSession = it }
            val preference = preference()
            val reviewTime = preference.reviewTime()
            emit(tileFor(session, preference, reviewTime))
            if (session == null && preference.eveningReviewEnabled && reviewTime != null) {
                val wait = java.time.Duration.between(clock().atZone(DateRanges.zone).toLocalTime(), reviewTime)
                if (!wait.isNegative && !wait.isZero) {
                    delay(wait.toMillis())
                    emit(tileFor(session, preference, reviewTime))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    private fun today() = clock().atZone(DateRanges.zone).toLocalDate()

    private suspend fun preference(): UserPreference {
        val known = preferenceRepository.preference.value
            ?: preferenceRepository.refresh().getOrNull()
            ?: lastPreference
            ?: UserPreference()
        lastPreference = known
        return known
    }

    private fun UserPreference.reviewTime(): LocalTime? = runCatching { LocalTime.parse(eveningReviewTime) }.getOrNull()

    private suspend fun tileFor(session: BilanSession?, preference: UserPreference, reviewTime: LocalTime?): BilanTile? {
        if (doneStore.doneDay.first() == today()) return null
        if (session != null) return BilanTile(session)
        val reached = reviewTime != null && !clock().atZone(DateRanges.zone).toLocalTime().isBefore(reviewTime)
        return if (preference.eveningReviewEnabled && reached) BilanTile(null) else null
    }

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
