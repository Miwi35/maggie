package com.maggie.app.ui.screens.home

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.AgendaHomeEvent
import com.maggie.app.data.model.AgendaHomeState
import com.maggie.app.data.model.HomeDay
import com.maggie.app.data.repository.AgendaCardRepository
import com.maggie.app.util.DateRanges
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * What the Agenda card shows (MAG-562). [state] is the last answer for [day]; [openId] is the event whose fiche
 * is open, and [opened] reads it from [state] so the fiche follows a refresh and vanishes with a deleted event.
 */
data class AgendaHomeUiState(
    val day: HomeDay = HomeDay.TODAY,
    val state: AgendaHomeState? = null,
    val loading: Boolean = true,
    val error: Boolean = false,
    val openId: String? = null,
    /** The device's clock when [state] was read, null for a day with no line (tomorrow); it moves on from there. */
    val readAt: Instant? = null,
    /** The device's monotonic clock at that moment. */
    val readAtElapsedMs: Long = 0,
) {
    val opened: AgendaHomeEvent? get() = openId?.let { id -> state?.events?.firstOrNull { it.id == id } }

    /** What the Accueil learns of the card: a day with no event is not drawn (MAG-594). */
    val status: HomeCardStatus
        get() = when {
            state == null -> if (error) HomeCardStatus.ERROR else HomeCardStatus.LOADING
            state.events.isEmpty() -> HomeCardStatus.EMPTY
            else -> HomeCardStatus.FILLED
        }
}

/**
 * Reads the Agenda card for the day the Accueil asks for from the events and agendas resources (MAG-589), and again
 * each time Mercure says one of them moved. The « maintenant » line is the device's clock at the read plus the time
 * elapsed since: a changed system clock does not make it jump.
 */
class AgendaHomeViewModel(
    private val repository: AgendaCardRepository,
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
    private val clock: () -> Instant = { Instant.now() },
) : ViewModel() {
    private val _uiState = MutableStateFlow(AgendaHomeUiState())
    val uiState: StateFlow<AgendaHomeUiState> = _uiState

    private var load: Job? = null

    init {
        viewModelScope.launch {
            repository.changes().collect { read(silently = true) }
        }
    }

    /** The Accueil's day. Another day drops what the card shows (tomorrow must not wear today's rows). */
    fun show(day: HomeDay) {
        _uiState.update { if (it.day == day) it else AgendaHomeUiState(day = day) }
        read(silently = false)
    }

    fun retry() = read(silently = false)

    fun open(eventId: String) {
        _uiState.update { it.copy(openId = eventId) }
    }

    fun close() {
        _uiState.update { it.copy(openId = null) }
    }

    /** The instant the line stands at, [elapsedRealtime] ms after the read; null when the day has no line. */
    fun nowAt(ui: AgendaHomeUiState): Instant? =
        ui.readAt?.plusMillis(elapsedRealtime() - ui.readAtElapsedMs)

    private fun read(silently: Boolean) {
        val day = _uiState.value.day
        load?.cancel()
        if (!silently) _uiState.update { it.copy(loading = it.state == null, error = false) }
        load = viewModelScope.launch {
            val asked = clock()
            val stamp = elapsedRealtime()
            val result = repository.read(day, asked.atZone(DateRanges.zone).toLocalDate())
            val readAt = if (day == HomeDay.TODAY) asked else null
            _uiState.update { current ->
                if (current.day != day) {
                    current
                } else {
                    result.fold(
                        onSuccess = { current.copy(state = it, loading = false, error = false, readAt = readAt, readAtElapsedMs = stamp) },
                        // A refresh that fails keeps what the card shows: the next one will do.
                        onFailure = { if (silently && current.state != null) current else current.copy(loading = false, error = true) },
                    )
                }
            }
        }
    }
}
