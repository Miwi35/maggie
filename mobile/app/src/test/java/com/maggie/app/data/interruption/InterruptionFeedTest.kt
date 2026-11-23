package com.maggie.app.data.interruption

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.Notification
import com.maggie.app.data.model.PendingApproval
import com.maggie.app.data.repository.ApprovalRepository
import com.maggie.app.data.repository.NotificationRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** While the app is open, the notifications and held actions Maggie raises reach the queue (MAG-314). */
@OptIn(ExperimentalCoroutinesApi::class)
class InterruptionFeedTest {

    private val center = InterruptionCenter()
    private val notifications = MutableSharedFlow<MercureEvent>()
    private val approvalEvents = MutableSharedFlow<MercureEvent>()
    private val timerEvents = MutableSharedFlow<MercureEvent>()
    private val cleared = mutableListOf<String>()

    private val mercure = mockk<MercureService> {
        every { subscribe(MercureTopics.userScoped("u-1", MercureTopics.NOTIFICATIONS)) } returns notifications
        every { subscribe(MercureTopics.agentScoped("u-1", MercureTopics.APPROVALS)) } returns approvalEvents
        every { subscribe(MercureTopics.userScoped("u-1", MercureTopics.COOKING_TIMERS)) } returns timerEvents
    }

    private val approvals = mockk<ApprovalRepository>()
    private val notificationRepository = mockk<NotificationRepository> {
        coEvery { getNotifications(unreadOnly = true) } returns Result.success(emptyList())
    }
    private val auth = mockk<AuthRepository> { coEvery { getUserId() } returns "u-1" }

    private fun kotlinx.coroutines.test.TestScope.start() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { InterruptionFeed(center, mercure, approvals, auth, notificationRepository, clearSystem = { cleared += it }, now = { NOW }).run() }
    }

    private fun unread(id: String, type: String = "proaction", createdAt: String = "2026-11-22T10:00:00+00:00", expiresAt: String? = null) =
        Notification(id = id, type = type, title = "Idée $id", createdAt = createdAt, expiresAt = expiresAt)

    private fun notification(id: String, extra: String = "") =
        MercureEvent(data = """{"@id":"/api/notifications/$id","id":"$id","type":"proaction","title":"Idée $id"$extra}""")

    @Test
    fun `a notification raised while the app is open is shown`() = runTest {
        coEvery { approvals.getPending() } returns Result.success(emptyList())
        start()

        notifications.emit(notification("n-1"))

        assertEquals("n-1", center.current.value?.notificationId)
    }

    @Test
    fun `a message notification from Mercure raises nothing`() = runTest {
        coEvery { approvals.getPending() } returns Result.success(emptyList())
        start()

        notifications.emit(MercureEvent(data = """{"@id":"/api/notifications/m-1","id":"m-1","type":"message","title":"Maggie","body":"Votre bilan du soir vous attend."}"""))

        assertNull(center.current.value)
    }

    @Test
    fun `the same notification twice is shown once`() = runTest {
        coEvery { approvals.getPending() } returns Result.success(emptyList())
        start()

        notifications.emit(notification("n-1"))
        notifications.emit(notification("n-2"))
        notifications.emit(notification("n-1"))
        center.close("n-1")

        assertEquals("n-2", center.current.value?.notificationId)
        center.close("n-2")
        assertNull(center.current.value)
    }

    @Test
    fun `a notification read on another device goes away`() = runTest {
        coEvery { approvals.getPending() } returns Result.success(emptyList())
        start()
        notifications.emit(notification("n-1"))

        notifications.emit(notification("n-1", extra = ""","readAt":"2026-10-08T10:00:00+00:00""""))

        assertNull(center.current.value)
    }

    @Test
    fun `the held actions already waiting when the app opens never take the screen`() = runTest {
        coEvery { approvals.getPending() } returns Result.success(listOf(PendingApproval(id = "ap-1", toolName = "t", summary = "Ajouter")))
        start()

        assertNull(center.current.value)
    }

    @Test
    fun `a question answered elsewhere is withdrawn`() = runTest {
        coEvery { approvals.getPending() } returns Result.success(listOf(PendingApproval(id = "ap-1", toolName = "t")))
        start()

        approvalEvents.emit(MercureEvent(data = """{"id":"ap-1","toolName":"t","status":"approved"}"""))

        assertNull(center.current.value)
    }

    @Test
    fun `a new held action does not take the screen`() = runTest {
        coEvery { approvals.getPending() } returns Result.success(emptyList())
        start()

        approvalEvents.emit(MercureEvent(data = """{"id":"ap-2","toolName":"t","status":"pending","summary":"Supprimer"}"""))

        assertNull(center.current.value)
    }

    @Test
    fun `a failing approvals request does not stop the notifications`() = runTest {
        coEvery { approvals.getPending() } returns Result.failure(RuntimeException("offline"))
        start()

        notifications.emit(notification("n-1"))

        assertEquals("n-1", center.current.value?.notificationId)
    }

    @Test
    fun `without a signed-in user nothing is listened to`() = runTest {
        coEvery { auth.getUserId() } returns null
        coEvery { approvals.getPending() } returns Result.success(listOf(PendingApproval(id = "ap-1", toolName = "t")))
        start()

        assertNull(center.current.value)
    }

    @Test
    fun `a sign-in state repeated by a token refresh does not restart the subscriptions`() = runTest {
        coEvery { approvals.getPending() } returns Result.success(emptyList())
        val signedIn = MutableSharedFlow<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            InterruptionFeed(center, mercure, approvals, auth, notificationRepository, clearSystem = { cleared += it }).runWhileSignedIn(signedIn)
        }

        signedIn.emit(true)
        signedIn.emit(true)
        signedIn.emit(true)

        verify(exactly = 1) { mercure.subscribe(MercureTopics.userScoped("u-1", MercureTopics.NOTIFICATIONS)) }
        notifications.emit(notification("n-1"))
        assertEquals("n-1", center.current.value?.notificationId)
    }

    @Test
    fun `signing out stops the feeds and signing in again restarts them`() = runTest {
        coEvery { approvals.getPending() } returns Result.success(emptyList())
        val signedIn = MutableStateFlow(true)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            InterruptionFeed(center, mercure, approvals, auth, notificationRepository, clearSystem = { cleared += it }).runWhileSignedIn(signedIn)
        }
        signedIn.value = false
        signedIn.value = true

        verify(exactly = 2) { mercure.subscribe(MercureTopics.userScoped("u-1", MercureTopics.NOTIFICATIONS)) }
    }

    @Test
    fun `a cooking timer acknowledged on another device leaves this tray`() = runTest {
        coEvery { approvals.getPending() } returns Result.success(emptyList())
        start()

        timerEvents.emit(MercureEvent(data = """{"@id":"/api/cooking/timers/t-1","id":"t-1","status":"running"}"""))
        timerEvents.emit(MercureEvent(data = """{"@id":"/api/cooking/timers/t-1","id":"t-1","status":"dismissed"}"""))

        assertEquals(listOf("cooking-timer-t-1"), cleared)
    }

    @Test
    fun `an interruption left unread while the app was away pops when it opens`() = runTest {
        coEvery { approvals.getPending() } returns Result.success(emptyList())
        coEvery { notificationRepository.getNotifications(unreadOnly = true) } returns Result.success(listOf(unread("n-1")))
        start()

        assertEquals("n-1", center.current.value?.notificationId)
    }

    @Test
    fun `a message left unread is never offered when the app opens`() = runTest {
        coEvery { approvals.getPending() } returns Result.success(emptyList())
        coEvery { notificationRepository.getNotifications(unreadOnly = true) } returns Result.success(listOf(unread("m-1", type = "message")))
        start()

        assertNull(center.current.value)
    }

    @Test
    fun `an expired notification left unread is not offered`() = runTest {
        coEvery { approvals.getPending() } returns Result.success(emptyList())
        coEvery { notificationRepository.getNotifications(unreadOnly = true) } returns
            Result.success(listOf(unread("n-1", expiresAt = "2026-11-22T10:20:00+00:00")))
        start()

        assertNull(center.current.value)
    }

    @Test
    fun `a notification already read is not offered`() = runTest {
        coEvery { approvals.getPending() } returns Result.success(emptyList())
        coEvery { notificationRepository.getNotifications(unreadOnly = true) } returns
            Result.success(listOf(unread("n-1").copy(readAt = "2026-11-22T10:30:00+00:00")))
        start()

        assertNull(center.current.value)
    }

    @Test
    fun `an unread notification read again and then received by Mercure is shown once`() = runTest {
        coEvery { approvals.getPending() } returns Result.success(emptyList())
        coEvery { notificationRepository.getNotifications(unreadOnly = true) } returns Result.success(listOf(unread("n-1")))
        start()

        notifications.emit(notification("n-1"))
        center.close("n-1")

        assertNull(center.current.value)
    }

    @Test
    fun `the oldest unread notification comes first, the next one waits`() = runTest {
        coEvery { approvals.getPending() } returns Result.success(emptyList())
        coEvery { notificationRepository.getNotifications(unreadOnly = true) } returns Result.success(
            listOf(unread("n-2", createdAt = "2026-11-22T10:05:00+00:00"), unread("n-1", createdAt = "2026-11-22T10:00:00+00:00")),
        )
        start()

        assertEquals("n-1", center.current.value?.notificationId)
        center.close("n-1")
        assertEquals("n-2", center.current.value?.notificationId)
    }

    @Test
    fun `a failing notifications request does not stop Mercure nor the held actions`() = runTest {
        coEvery { approvals.getPending() } returns Result.success(listOf(PendingApproval(id = "ap-1", toolName = "t")))
        coEvery { notificationRepository.getNotifications(unreadOnly = true) } returns Result.failure(RuntimeException("offline"))
        start()

        notifications.emit(notification("n-1"))
        approvalEvents.emit(MercureEvent(data = """{"id":"ap-1","toolName":"t","status":"approved"}"""))

        assertEquals("n-1", center.current.value?.notificationId)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-22T10:30:00Z")
    }
}
