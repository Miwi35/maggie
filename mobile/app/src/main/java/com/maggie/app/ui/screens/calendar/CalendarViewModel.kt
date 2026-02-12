package com.maggie.app.ui.screens.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.Event
import com.maggie.app.data.repository.EventRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

data class CalendarUiState(
    val events: List<Event> = emptyList(),
    val eventsByDate: Map<LocalDate, List<Event>> = emptyMap(),
    val selectedDate: LocalDate = LocalDate.now(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class CalendarViewModel(
    private val repository: EventRepository,
    private val mercureService: MercureService,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState

    init {
        observeCachedEvents()
        refreshEvents()
        subscribeToEventUpdates()
    }

    /** Observe Room database — emits immediately with cached data. */
    private fun observeCachedEvents() {
        viewModelScope.launch {
            repository.observeEvents().collect { events ->
                _uiState.value = _uiState.value.copy(
                    events = events,
                    eventsByDate = groupEventsByDate(events),
                    isLoading = false,
                )
            }
        }
    }

    /** Network refresh — writes to Room, which triggers observeCachedEvents. */
    fun refreshEvents() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            repository.refreshEvents()
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        error = error.message,
                        isLoading = false,
                    )
                }
        }
    }

    fun loadEvents() = refreshEvents()

    fun selectDate(date: LocalDate) {
        _uiState.value = _uiState.value.copy(selectedDate = date)
    }

    private fun groupEventsByDate(events: List<Event>): Map<LocalDate, List<Event>> {
        return events.groupBy { event ->
            try {
                val zdt = ZonedDateTime.parse(event.startAt)
                zdt.withZoneSameInstant(ZoneId.of(event.timeZone)).toLocalDate()
            } catch (_: Exception) {
                LocalDate.now()
            }
        }
    }

    private fun subscribeToEventUpdates() {
        viewModelScope.launch {
            mercureService.subscribe("/api/events/{id}")
                .catch { /* SSE connection errors — silently retry on next app resume */ }
                .collect { refreshEvents() }
        }
    }
}
