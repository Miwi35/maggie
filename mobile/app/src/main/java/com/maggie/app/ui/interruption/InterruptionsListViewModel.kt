package com.maggie.app.ui.interruption

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.fcm.PushPayload
import com.maggie.app.data.interruption.InterruptionCenter
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.Notification
import com.maggie.app.data.model.PendingApproval
import com.maggie.app.data.model.Trip
import com.maggie.app.data.repository.ApprovalRepository
import com.maggie.app.data.repository.NotificationRepository
import com.maggie.app.data.repository.TripRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** A departure alert still waiting: « Pars maintenant » with the trip's duration and the link to navigate. */
data class TripEntry(
    val notificationId: String,
    val title: String,
    val minutes: Int?,
    val navigationUrl: String?,
)

/** A held action to confirm: the chat shows its card. */
data class ConfirmationEntry(val id: String, val preview: String)

enum class SettledKind { TRIP, CONFIRMATION }

/** What was answered in the last 24 hours, with the answer as the panel says it. */
data class SettledEntry(
    val id: String,
    val kind: SettledKind,
    val title: String,
    val answer: String,
    val navigationUrl: String? = null,
)

data class InterruptionsUiState(
    val trips: List<TripEntry> = emptyList(),
    val questions: List<PushPayload> = emptyList(),
    val confirmations: List<ConfirmationEntry> = emptyList(),
    val settled: List<SettledEntry> = emptyList(),
) {
    /** What the bell counts: everything that waits for an answer. */
    val pendingCount: Int get() = trips.size + questions.size + confirmations.size
}

/**
 * What Maggie waits on, for the bell of the header (MAG-712): the departures, the questions and the held
 * actions, and what was answered in the last 24 hours. The held actions and the departures come from the
 * API and are refreshed on Mercure; the questions are the ones the interruption center has seen.
 */
class InterruptionsListViewModel(
    private val approvals: ApprovalRepository,
    private val notifications: NotificationRepository,
    private val trips: TripRepository,
    private val center: InterruptionCenter,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
    /** Takes down the system notification of a departure that was used. */
    private val closeSystem: (String) -> Unit = {},
    private val now: () -> Instant = Instant::now,
) : ViewModel() {

    private data class Loaded(
        val trips: List<TripEntry> = emptyList(),
        val confirmations: List<ConfirmationEntry> = emptyList(),
        val settled: List<SettledEntry> = emptyList(),
    )

    private val loaded = MutableStateFlow(Loaded())

    val uiState: StateFlow<InterruptionsUiState> = combine(loaded, center.questions) { data, questions ->
        InterruptionsUiState(
            trips = data.trips,
            questions = questions,
            confirmations = data.confirmations,
            settled = data.settled,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, InterruptionsUiState())

    init {
        refresh()
        subscribeToMercure()
    }

    fun refresh() {
        viewModelScope.launch {
            val current = now()
            val since = current.minus(SETTLED_WINDOW)
            val pending = approvals.getPending().getOrNull()
            val decided = approvals.getDecided(since).getOrNull()
            val notes = notifications.getNotifications().getOrNull()
            val tripsById = if (notes == null) emptyMap() else tripsOf(LocalDate.now(PARIS))
            val before = loaded.value
            loaded.value = Loaded(
                trips = notes?.let { departures(it, tripsById, current) } ?: before.trips,
                confirmations = pending?.let { confirmations(it, current) } ?: before.confirmations,
                settled = if (notes == null && decided == null) {
                    before.settled
                } else {
                    (settledTrips(notes, tripsById, since) + settledConfirmations(decided)).sortedByDescending { it.at }.map { it.entry }
                },
            )
        }
    }

    /** « Y aller » was pressed: the alert is answered, here and in the tray. */
    fun tripOpened(entry: TripEntry) {
        closeSystem(entry.notificationId)
        viewModelScope.launch {
            notifications.markRead(entry.notificationId)
            refresh()
        }
    }

    /** A question of the panel takes the screen, as it would have when it was asked. */
    fun openQuestion(payload: PushPayload) = center.reopen(payload)

    private suspend fun tripsOf(day: LocalDate): Map<String, Trip> =
        trips.getTrips(day).getOrNull().orEmpty().associateBy { it.id }

    private fun departures(notes: List<Notification>, tripsById: Map<String, Trip>, current: Instant): List<TripEntry> =
        notes.filter { it.type == DEPARTURE && !it.isRead && !it.isOver(current) }
            .sortedBy { it.createdAt }
            .map { note ->
                val trip = note.tripId()?.let(tripsById::get)
                TripEntry(note.id, note.title, trip?.durationMinutes, trip?.navigationUrl?.takeIf { it.isNotBlank() })
            }

    private fun confirmations(pending: List<PendingApproval>, current: Instant): List<ConfirmationEntry> =
        pending.filter { it.isPending && !it.isOver(current) }
            .map { ConfirmationEntry(it.id, it.summary?.takeIf(String::isNotBlank) ?: it.toolName) }

    private class Dated(val at: Instant, val entry: SettledEntry)

    private fun settledTrips(notes: List<Notification>?, tripsById: Map<String, Trip>, since: Instant): List<Dated> =
        notes.orEmpty().filter { it.type == DEPARTURE && it.isRead }.mapNotNull { note ->
            val readAt = instantOf(note.readAt)?.takeIf { it.isAfter(since) } ?: return@mapNotNull null
            val url = note.tripId()?.let(tripsById::get)?.navigationUrl?.takeIf { it.isNotBlank() }
            Dated(readAt, SettledEntry(note.id, SettledKind.TRIP, note.title, "Itinéraire ouvert", url))
        }

    private fun settledConfirmations(decided: List<PendingApproval>?): List<Dated> =
        decided.orEmpty().mapNotNull { approval ->
            val answer = when (approval.status) {
                PendingApproval.STATUS_APPROVED -> "Autorisé"
                PendingApproval.STATUS_DENIED -> "Refusé"
                PendingApproval.STATUS_FAILED -> "Échec"
                else -> return@mapNotNull null
            }
            val at = instantOf(approval.decidedAt) ?: return@mapNotNull null
            Dated(at, SettledEntry(approval.id, SettledKind.CONFIRMATION, approval.summary?.takeIf(String::isNotBlank) ?: approval.toolName, answer))
        }

    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            launch {
                mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.NOTIFICATIONS))
                    .catch { /* the stream reconnects by itself */ }
                    .collect { refresh() }
            }
            launch {
                mercureService.subscribe(MercureTopics.agentScoped(userId, MercureTopics.APPROVALS))
                    .catch { /* the stream reconnects by itself */ }
                    .collect { refresh() }
            }
        }
    }

    private fun Notification.isOver(current: Instant): Boolean = instantOf(expiresAt)?.let { !it.isAfter(current) } == true

    private fun PendingApproval.isOver(current: Instant): Boolean = instantOf(expiresAt)?.let { !it.isAfter(current) } == true

    private fun Notification.tripId(): String? = relatedEntityIri?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    private fun instantOf(raw: String?): Instant? =
        raw?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

    private companion object {
        const val DEPARTURE = "departure"
        val SETTLED_WINDOW: Duration = Duration.ofHours(24)
        val PARIS: ZoneId = ZoneId.of("Europe/Paris")
    }
}
