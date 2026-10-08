package com.maggie.app.ui.screens.notifications

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.interruption.InterruptionCenter
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.Notification
import com.maggie.app.data.repository.NotificationRepository
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Tapping a notification in the list makes Maggie say it again (MAG-314). */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationViewModelTest {

    private val repository = mockk<NotificationRepository>()
    private val center = InterruptionCenter()
    private lateinit var viewModel: NotificationViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { repository.getNotifications() } returns Result.success(emptyList())
        coEvery { repository.markRead(any()) } returns Result.success(Notification(id = "x", title = "x"))
        val auth = mockk<AuthRepository> { coEvery { getUserId() } returns null }
        viewModel = NotificationViewModel(repository, mockk<MercureService>(), auth, center)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a tap reopens the toaster on the notification and leaves it unread`() {
        viewModel.open(Notification(id = "n-1", type = "proaction", title = "Idée", body = "Un plat ?"))

        assertEquals("n-1", center.current.value?.notificationId)
        assertEquals("Idée", center.current.value?.title)
        coVerify(exactly = 0) { repository.markRead(any()) }
    }

    @Test
    fun `a notification already read can be said again`() {
        viewModel.open(Notification(id = "n-1", title = "Idée", readAt = "2026-10-08T09:00:00+00:00"))

        assertEquals("n-1", center.current.value?.notificationId)
    }

    @Test
    fun `what cannot be said is simply marked read`() {
        viewModel.open(Notification(id = "n-2", type = "approval", title = "Valider"))

        assertNull(center.current.value)
        coVerify { repository.markRead("n-2") }
    }
}
