package com.maggie.app.ui.interruption

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.fcm.PushInteraction
import com.maggie.app.data.fcm.PushPayload
import com.maggie.app.data.interruption.InterruptionCenter
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.Notification
import com.maggie.app.data.model.PendingApproval
import com.maggie.app.data.model.Trip
import com.maggie.app.data.repository.ApprovalRepository
import com.maggie.app.data.repository.NotificationRepository
import com.maggie.app.data.repository.TripRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** What the bell lists and counts (MAG-712): trips, questions, held actions, and the last 24 hours. */
@OptIn(ExperimentalCoroutinesApi::class)
class InterruptionsListViewModelTest {

    private val now = Instant.parse("2026-10-29T10:00:00Z")
    private val approvals = mockk<ApprovalRepository>()
    private val notifications = mockk<NotificationRepository>(relaxed = true)
    private val trips = mockk<TripRepository>()
    private val auth = mockk<AuthRepository>()
    private val mercure = mockk<MercureService>(relaxed = true)
    private val center = InterruptionCenter()
    private val closed = mutableListOf<String>()

    private fun departure(id: String, readAt: String? = null, expiresAt: String? = null) = Notification(
        id = id,
        type = "departure",
        title = "Pars maintenant",
        relatedEntityIri = "/api/trips/t-$id",
        readAt = readAt,
        expiresAt = expiresAt,
        createdAt = "2026-10-29T09:00:00Z",
    )

    private fun trip(id: String) = Trip(id = "t-$id", event = "/api/events/e", durationMinutes = 25, departAt = "2026-10-29T09:30:00Z", navigationUrl = "https://maps.example/$id")

    private fun approval(id: String, status: String, decidedAt: String? = null, expiresAt: String? = null) =
        PendingApproval(id = id, toolName = "delete_task", summary = "Supprimer la tâche $id", status = status, decidedAt = decidedAt, expiresAt = expiresAt)

    private fun viewModel() = InterruptionsListViewModel(approvals, notifications, trips, center, mercure, auth, closeSystem = { closed += it }, now = { now })

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { auth.getUserId() } returns null
        coEvery { approvals.getPending() } returns Result.success(emptyList())
        coEvery { approvals.getDecided(any()) } returns Result.success(emptyList())
        coEvery { notifications.getNotifications(any()) } returns Result.success(emptyList())
        coEvery { trips.getTrips(any()) } returns Result.success(emptyList())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `an unread departure is a trip with its duration and its navigation link`() {
        coEvery { notifications.getNotifications(any()) } returns Result.success(listOf(departure("n1")))
        coEvery { trips.getTrips(any()) } returns Result.success(listOf(trip("n1")))

        val state = viewModel().uiState.value

        assertEquals(listOf(TripEntry("n1", "Pars maintenant", 25, "https://maps.example/n1")), state.trips)
        assertEquals(1, state.pendingCount)
    }

    @Test
    fun `an expired departure is not pending`() {
        coEvery { notifications.getNotifications(any()) } returns Result.success(listOf(departure("n1", expiresAt = "2026-10-29T09:59:00Z")))

        assertTrue(viewModel().uiState.value.trips.isEmpty())
    }

    @Test
    fun `a read departure of the last day is settled as Itinéraire ouvert`() {
        coEvery { notifications.getNotifications(any()) } returns Result.success(
            listOf(departure("n1", readAt = "2026-10-29T08:00:00Z"), departure("old", readAt = "2026-10-27T08:00:00Z")),
        )
        coEvery { trips.getTrips(any()) } returns Result.success(listOf(trip("n1")))

        val state = viewModel().uiState.value

        assertEquals(listOf("n1"), state.settled.map { it.id })
        assertEquals("Itinéraire ouvert", state.settled.single().answer)
        assertEquals("https://maps.example/n1", state.settled.single().navigationUrl)
        assertEquals(0, state.pendingCount)
    }

    @Test
    fun `pending approvals are confirmations and decided ones are settled with their answer`() {
        coEvery { approvals.getPending() } returns Result.success(listOf(approval("a1", PendingApproval.STATUS_PENDING)))
        coEvery { approvals.getDecided(any()) } returns Result.success(
            listOf(
                approval("a2", PendingApproval.STATUS_APPROVED, decidedAt = "2026-10-29T09:00:00Z"),
                approval("a3", PendingApproval.STATUS_DENIED, decidedAt = "2026-10-29T09:30:00Z"),
            ),
        )

        val state = viewModel().uiState.value

        assertEquals(listOf(ConfirmationEntry("a1", "Supprimer la tâche a1")), state.confirmations)
        assertEquals(listOf("a3" to "Refusé", "a2" to "Autorisé"), state.settled.map { it.id to it.answer })
    }

    @Test
    fun `the questions the center holds are listed and count in the badge`() {
        center.offer(
            PushPayload(
                notificationId = "q1", type = "reminder", channel = "reminders", title = "Q", text = "Je décale ?",
                link = null, actionLabel = null, approvalId = null, interaction = PushInteraction(kind = "choice", options = listOf("Oui", "Non")),
            ),
        )

        val state = viewModel().uiState.value

        assertEquals(listOf("q1"), state.questions.map { it.notificationId })
        assertEquals(1, state.pendingCount)
    }

    @Test
    fun `using a trip takes down its notification and marks it read`() {
        coEvery { notifications.getNotifications(any()) } returns Result.success(listOf(departure("n1")))
        val viewModel = viewModel()

        viewModel.tripOpened(viewModel.uiState.value.trips.single())

        assertEquals(listOf("n1"), closed)
        coVerify { notifications.markRead("n1") }
    }

    @Test
    fun `a failed read keeps what was listed`() {
        coEvery { notifications.getNotifications(any()) } returns Result.success(listOf(departure("n1")))
        val viewModel = viewModel()
        coEvery { notifications.getNotifications(any()) } returns Result.failure(RuntimeException("offline"))

        viewModel.refresh()

        assertEquals(1, viewModel.uiState.value.trips.size)
    }
}
