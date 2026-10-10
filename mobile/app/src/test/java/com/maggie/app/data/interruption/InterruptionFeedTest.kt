package com.maggie.app.data.interruption

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.PendingApproval
import com.maggie.app.data.repository.ApprovalRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** While the app is open, the notifications and held actions Maggie raises reach the queue (MAG-314). */
@OptIn(ExperimentalCoroutinesApi::class)
class InterruptionFeedTest {

    private val center = InterruptionCenter()
    private val notifications = MutableSharedFlow<MercureEvent>()
    private val approvalEvents = MutableSharedFlow<MercureEvent>()

    private val mercure = mockk<MercureService> {
        every { subscribe(MercureTopics.userScoped("u-1", MercureTopics.NOTIFICATIONS)) } returns notifications
        every { subscribe(MercureTopics.agentScoped("u-1", MercureTopics.APPROVALS)) } returns approvalEvents
    }
    private val approvals = mockk<ApprovalRepository>()
    private val auth = mockk<AuthRepository> { coEvery { getUserId() } returns "u-1" }

    private fun kotlinx.coroutines.test.TestScope.start() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { InterruptionFeed(center, mercure, approvals, auth).run() }
    }

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
    fun `the questions already waiting when the app opens are asked`() = runTest {
        coEvery { approvals.getPending() } returns Result.success(listOf(PendingApproval(id = "ap-1", toolName = "t", summary = "Ajouter")))
        start()

        assertEquals("approval:ap-1", center.current.value?.key)
    }

    @Test
    fun `a question answered elsewhere is withdrawn`() = runTest {
        coEvery { approvals.getPending() } returns Result.success(listOf(PendingApproval(id = "ap-1", toolName = "t")))
        start()

        approvalEvents.emit(MercureEvent(data = """{"id":"ap-1","toolName":"t","status":"approved"}"""))

        assertNull(center.current.value)
    }

    @Test
    fun `a new held action is asked as it is raised`() = runTest {
        coEvery { approvals.getPending() } returns Result.success(emptyList())
        start()

        approvalEvents.emit(MercureEvent(data = """{"id":"ap-2","toolName":"t","status":"pending","summary":"Supprimer"}"""))

        assertEquals("Supprimer", center.current.value?.text)
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
}
