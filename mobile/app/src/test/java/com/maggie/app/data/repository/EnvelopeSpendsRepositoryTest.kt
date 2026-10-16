package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.Transaction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the phone reads an envelope's lines: page by page, until the period is behind it (MAG-418). */
class EnvelopeSpendsRepositoryTest {
    private val api = mockk<MaggieApiService>()
    private val repository = TransactionRepository(api)

    private fun tx(id: String, bookedAt: String) =
        Transaction(id = id, label = id, amountCents = -1000, bookedAt = bookedAt, transferKind = "none")

    private fun fullPage(prefix: String, bookedAt: String) = List(100) { tx("$prefix-$it", bookedAt) }

    @Test
    fun `a short page is the whole category`() = runTest {
        coEvery { api.getTransactionsOfCategoryPage("cat-1", 1) } returns listOf(tx("a", "2026-07-10T00:00:00+00:00"))

        val lines = repository.getEnvelopeSpends(listOf("cat-1"), 2026, 7).getOrThrow()

        assertEquals(listOf("a"), lines.map { it.id })
        coVerify(exactly = 1) { api.getTransactionsOfCategoryPage("cat-1", any()) }
    }

    @Test
    fun `a full page still inside the period calls for the next one`() = runTest {
        coEvery { api.getTransactionsOfCategoryPage("cat-1", 1) } returns fullPage("p1", "2026-07-20T00:00:00+00:00")
        coEvery { api.getTransactionsOfCategoryPage("cat-1", 2) } returns listOf(tx("last", "2026-07-02T00:00:00+00:00"))

        val lines = repository.getEnvelopeSpends(listOf("cat-1"), 2026, 7).getOrThrow()

        assertEquals(101, lines.size)
        coVerify(exactly = 1) { api.getTransactionsOfCategoryPage("cat-1", 2) }
    }

    @Test
    fun `a full page that goes back before the period is the last one read`() = runTest {
        val page = List(99) { tx("in-$it", "2026-07-20T00:00:00+00:00") } + tx("old", "2026-06-28T00:00:00+00:00")
        coEvery { api.getTransactionsOfCategoryPage("cat-1", 1) } returns page

        val lines = repository.getEnvelopeSpends(listOf("cat-1"), 2026, 7).getOrThrow()

        assertEquals(99, lines.size)
        coVerify(exactly = 0) { api.getTransactionsOfCategoryPage("cat-1", 2) }
    }

    @Test
    fun `every category of the envelope is read and the lines are merged newest first`() = runTest {
        coEvery { api.getTransactionsOfCategoryPage("cat-1", 1) } returns listOf(tx("mother", "2026-07-05T00:00:00+00:00"))
        coEvery { api.getTransactionsOfCategoryPage("cat-2", 1) } returns listOf(tx("daughter", "2026-07-25T00:00:00+00:00"))

        val lines = repository.getEnvelopeSpends(listOf("cat-1", "cat-2"), 2026, 7).getOrThrow()

        assertEquals(listOf("daughter", "mother"), lines.map { it.id })
    }

    @Test
    fun `a failing page fails the whole reading`() = runTest {
        coEvery { api.getTransactionsOfCategoryPage(any(), any()) } throws RuntimeException("503")

        assertTrue(repository.getEnvelopeSpends(listOf("cat-1"), 2026, 7).isFailure)
    }
}
