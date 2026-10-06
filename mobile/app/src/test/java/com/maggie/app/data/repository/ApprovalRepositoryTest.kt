package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.PendingApproval
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalRepositoryTest {
    private val api = mockk<MaggieApiService>()
    private val mercure = mockk<MercureService>()
    private val auth = mockk<AuthRepository>()
    private val repository = ApprovalRepository(api, mercure, auth)

    private val payload =
        """{"id":"ap-1","toolName":"delete_event","arguments":{"id":"evt-1"},"status":"approved"}"""

    @Test
    fun `observe subscribes with the signed-in user's id, not the placeholder`() = runTest {
        coEvery { auth.getUserId() } returns "01USER"
        every { mercure.subscribe("/approvals/01USER") } returns flowOf(MercureEvent(data = payload))

        val approvals = repository.observe().toList()

        assertEquals(listOf("ap-1"), approvals.map { it.id })
        assertEquals(PendingApproval.STATUS_APPROVED, approvals.single().status)
        verify(exactly = 1) { mercure.subscribe("/approvals/01USER") }
    }

    @Test
    fun `observe does not subscribe when nobody is signed in`() = runTest {
        coEvery { auth.getUserId() } returns null

        assertTrue(repository.observe().toList().isEmpty())
        verify(exactly = 0) { mercure.subscribe(any()) }
    }

    @Test
    fun `observe skips an event that is not an approval`() = runTest {
        coEvery { auth.getUserId() } returns "01USER"
        every { mercure.subscribe(any()) } returns flowOf(
            MercureEvent(data = "not json"),
            MercureEvent(data = payload),
        )

        assertEquals(listOf("ap-1"), repository.observe().toList().map { it.id })
    }

    @Test
    fun `a network failure comes back as a failed result`() = runTest {
        coEvery { api.approve("ap-1") } throws RuntimeException("timeout")
        coEvery { api.getPendingApprovals() } throws RuntimeException("timeout")

        assertTrue(repository.approve("ap-1").isFailure)
        assertTrue(repository.getPending().isFailure)
        coVerify(exactly = 0) { api.deny(any()) }
    }
}
