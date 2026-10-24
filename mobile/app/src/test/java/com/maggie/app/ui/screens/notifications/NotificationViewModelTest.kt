package com.maggie.app.ui.screens.notifications

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.fcm.PushActionHandler
import com.maggie.app.data.fcm.PushActionKind
import com.maggie.app.data.fcm.PushOutcome
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
    private val pushActions = mockk<PushActionHandler>()
    private lateinit var viewModel: NotificationViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { repository.getNotifications() } returns Result.success(emptyList())
        coEvery { repository.markRead(any()) } returns Result.success(Notification(id = "x", title = "x"))
        val auth = mockk<AuthRepository> { coEvery { getUserId() } returns null }
        viewModel = NotificationViewModel(repository, mockk<MercureService>(), auth, center, pushActions)
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

    // MAG-570: « Ajouter à la liste » on the row of a shortage alert.

    private val shortage = Notification(
        id = "n-9",
        type = "grocery",
        title = "Il manque : parmesan",
        relatedEntityIri = "/api/shortage-alerts/01HZX3K9P2M7Q4R8S5T6V0W1AB",
    )

    @Test
    fun `Ajouter à la liste answers the shortage and reloads the list`() {
        coEvery { pushActions.handle(PushActionKind.ADD_TO_LIST, any(), any(), any()) } returns PushOutcome.Closed

        viewModel.addToList(shortage)

        coVerify { pushActions.handle(PushActionKind.ADD_TO_LIST, match { it.shortageId == "01HZX3K9P2M7Q4R8S5T6V0W1AB" }, any(), any()) }
        coVerify(atLeast = 2) { repository.getNotifications() }
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a shortage that cannot be answered says why and keeps the row`() {
        coEvery { pushActions.handle(PushActionKind.ADD_TO_LIST, any(), any(), any()) } returns PushOutcome.Retry("Maggie est injoignable. Réessayez.")

        viewModel.addToList(shortage)

        assertEquals("Maggie est injoignable. Réessayez.", viewModel.uiState.value.error)
        coVerify(exactly = 1) { repository.getNotifications() }
    }

    @Test
    fun `a notification that is not a shortage alert has no such answer`() {
        viewModel.addToList(Notification(id = "n-1", type = "reminder", title = "Dentiste"))

        coVerify(exactly = 0) { pushActions.handle(any(), any(), any(), any()) }
    }
}
