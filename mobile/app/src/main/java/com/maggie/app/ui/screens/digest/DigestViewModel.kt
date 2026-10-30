package com.maggie.app.ui.screens.digest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.AgendaHomeState
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.data.model.CuisineHomeState
import com.maggie.app.data.model.FinanceHomeState
import com.maggie.app.data.model.HomeDay
import com.maggie.app.data.model.HomeTasks
import com.maggie.app.data.model.Trip
import com.maggie.app.data.model.Weather
import com.maggie.app.data.repository.AgendaCardRepository
import com.maggie.app.data.repository.ChatRepository
import com.maggie.app.data.repository.CuisineCardRepository
import com.maggie.app.data.repository.FinanceCardRepository
import com.maggie.app.data.repository.TasksCardRepository
import com.maggie.app.data.repository.TripRepository
import com.maggie.app.data.repository.WeatherRepository
import com.maggie.app.util.DateRanges
import com.maggie.app.util.rethrowCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

/**
 * The Digest du matin (MAG-710). A source that failed or has nothing to say leaves its card out: [weather], [agenda],
 * [tasks], [cuisine] and [finance] are `null` when it failed, and the `shows…` flags are false when they are empty.
 */
data class DigestUiState(
    val isLoading: Boolean = true,
    val date: LocalDate? = null,
    val now: Instant? = null,
    /** Maggie's written digest; `null` without a link, or when it could not be read. */
    val message: ChatMessage? = null,
    /** Her vocal, sent next to the written message; `null` when she sent none. */
    val voice: ChatMessage? = null,
    val weather: Weather? = null,
    val agenda: AgendaHomeState? = null,
    val trips: List<Trip> = emptyList(),
    val tasks: HomeTasks? = null,
    val cuisine: CuisineHomeState? = null,
    val finance: FinanceHomeState? = null,
) {
    val showsWeather: Boolean get() = weather != null
    val showsAgenda: Boolean get() = agenda?.events?.isNotEmpty() == true
    val showsTasks: Boolean get() = tasks?.let { !it.isEmpty } == true
    val showsCuisine: Boolean get() = cuisine?.let { !it.isEmpty } == true
    val showsFinance: Boolean get() = finance?.let { !it.isEmpty } == true
}

/** Reads the message and the five sources of the digest at once; one that fails only empties its own card. */
class DigestViewModel(
    private val messageId: String?,
    private val chat: ChatRepository,
    private val weather: WeatherRepository,
    private val agenda: AgendaCardRepository,
    private val trips: TripRepository,
    private val tasks: TasksCardRepository,
    private val cuisine: CuisineCardRepository,
    private val finance: FinanceCardRepository,
    private val clock: () -> Instant = { Instant.now() },
) : ViewModel() {
    private val _uiState = MutableStateFlow(DigestUiState())
    val uiState: StateFlow<DigestUiState> = _uiState

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val now = clock()
            val zoned = now.atZone(DateRanges.zone)
            val day = zoned.toLocalDate()
            val state = coroutineScope {
                val read = async { readMessage() }
                val forecast = async { weather.read(day).getOrNull() }
                val events = async { agenda.read(HomeDay.TODAY, day).getOrNull() }
                val departures = async { trips.getTrips(day).getOrNull().orEmpty() }
                val todo = async { tasks.read(HomeDay.TODAY, day, zoned.toLocalTime()).getOrNull() }
                val meals = async { cuisine.read(day).getOrNull() }
                val money = async { finance.read().getOrNull() }
                DigestUiState(
                    isLoading = false,
                    date = day,
                    now = now,
                    message = read.await()?.first,
                    voice = read.await()?.second,
                    weather = forecast.await(),
                    agenda = events.await(),
                    trips = departures.await(),
                    tasks = todo.await(),
                    cuisine = meals.await(),
                    finance = money.await(),
                )
            }
            _uiState.value = state
        }
    }

    /** The written digest and its vocal: the linked message, and the neighbour of the other kind (the vocal is sent first). */
    private suspend fun readMessage(): Pair<ChatMessage?, ChatMessage?>? {
        val id = messageId ?: return null
        val context = runCatching { chat.loadMessageContext(id) }.rethrowCancellation().getOrNull() ?: return null
        val index = context.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return null
        val linked = context[index]
        val neighbours = listOfNotNull(context.getOrNull(index - 1), context.getOrNull(index + 1)).filter { it.role == linked.role }
        return if (linked.audio != null) {
            neighbours.firstOrNull { it.audio == null } to linked
        } else {
            linked to neighbours.firstOrNull { it.audio != null }
        }
    }
}
