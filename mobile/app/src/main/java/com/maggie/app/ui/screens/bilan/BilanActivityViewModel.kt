package com.maggie.app.ui.screens.bilan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalesced
import com.maggie.app.data.repository.ActivityStatusRepository
import com.maggie.app.util.DateRanges
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

enum class BilanLoad { LOADING, READY, ERROR }

data class BilanUiState(
    val load: BilanLoad = BilanLoad.LOADING,
    val session: BilanSession? = null,
    /** « Bonne nuit » was said, here or on another device: the activity is over. */
    val finished: Boolean = false,
) {
    val step: BilanStep get() = session?.step ?: BilanStep.JOURNEE

    /** « Journée · 1 sur 5 ». */
    val counter: String get() = "${step.label} · ${step.ordinal + 1} sur ${BilanStep.entries.size}"
}

/**
 * The Bilan de la journée (MAG-751). Where it stands lives in `activity-status/bilan`, not here: opening starts it
 * at « Journée » when nothing is in progress for today and resumes the stored step otherwise; each change of step
 * is written at once; quitting writes nothing and asks nothing, so « Reprendre » opens the same step until the
 * morning. « Bonne nuit » ends it. Another device moving it (Mercure) moves this screen.
 */
class BilanActivityViewModel(
    private val statusRepository: ActivityStatusRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
    private val clock: () -> Instant = { Instant.now() },
) : ViewModel() {
    private val _uiState = MutableStateFlow(BilanUiState())
    val uiState: StateFlow<BilanUiState> = _uiState

    init {
        load()
        follow()
    }

    private fun today(): LocalDate = clock().atZone(DateRanges.zone).toLocalDate()

    fun retry() {
        _uiState.value = BilanUiState()
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val status = statusRepository.get(BILAN_ACTIVITY).getOrElse {
                _uiState.update { s -> s.copy(load = BilanLoad.ERROR) }
                return@launch
            }
            val stored = status?.toBilanSession()?.takeIf { it.isOpenOn(today()) }
            val session = stored ?: BilanSession(today(), BilanStep.JOURNEE).also(::write)
            _uiState.update { it.copy(load = BilanLoad.READY, session = session) }
        }
    }

    fun next() {
        val session = _uiState.value.session ?: return
        val following = session.step.next
        if (following == null) goodNight() else moveTo(following)
    }

    fun previous() {
        val step = _uiState.value.session?.step?.previous ?: return
        moveTo(step)
    }

    fun goTo(step: BilanStep) = moveTo(step)

    private fun moveTo(step: BilanStep) {
        val session = _uiState.value.session ?: return
        if (step == session.step) return
        val moved = session.at(step)
        _uiState.update { it.copy(session = moved) }
        write(moved)
    }

    private fun goodNight() {
        statusRepository.delete(BILAN_ACTIVITY)
        _uiState.update { it.copy(finished = true) }
    }

    private fun write(session: BilanSession) {
        statusRepository.put(BILAN_ACTIVITY, session.statusOrigin(), session.statusState())
    }

    private fun follow() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.ACTIVITY_STATUS))
                .catch { /* SSE reconnects automatically */ }
                .coalesced()
                .collect { adopt() }
        }
    }

    // What another device (or Maggie, with save_bilan_step) wrote: the step it stands at, or its end.
    private suspend fun adopt() {
        if (_uiState.value.load != BilanLoad.READY || _uiState.value.finished) return
        val status = statusRepository.get(BILAN_ACTIVITY).getOrElse { return }
        val stored = status?.toBilanSession()
        _uiState.update { current ->
            when {
                status == null -> current.copy(finished = true)
                stored == null -> current
                else -> current.copy(session = stored)
            }
        }
    }
}
