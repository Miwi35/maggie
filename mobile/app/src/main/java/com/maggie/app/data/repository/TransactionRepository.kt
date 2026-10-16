package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.TransactionCreateRequest
import com.maggie.app.data.model.AccountIncident
import com.maggie.app.data.model.Envelope
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.model.Qualification
import com.maggie.app.data.model.TransferInfo
import com.maggie.app.data.model.TransferLeg
import com.maggie.app.data.model.TransferUpdateRequest
import com.maggie.app.data.model.categoryIri
import com.maggie.app.data.model.bookedDate
import com.maggie.app.data.model.envelopeIri
import com.maggie.app.data.model.envelopeSpends
import com.maggie.app.data.model.periodStart

class TransactionRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getTransactions(accountId: String? = null): Result<List<Transaction>> = runCatching {
        apiService.getTransactions(accountId)
    }

    /**
     * What weighs on an envelope in a period: the debits of [categoryIds] (its category and the
     * sub-categories it takes along), as the budget counts them. Each category is read newest
     * first, page by page, until it goes back past the start of the period.
     */
    suspend fun getEnvelopeSpends(categoryIds: Collection<String>, year: Int, month: Int?): Result<List<Transaction>> =
        runCatching {
            val lines = mutableListOf<Transaction>()
            for (categoryId in categoryIds) {
                var page = 1
                while (true) {
                    val batch = apiService.getTransactionsOfCategoryPage(categoryId, page)
                    lines += batch
                    val oldest = batch.lastOrNull()?.bookedAt?.let(::bookedDate)
                    if (batch.size < TRANSACTION_PAGE_SIZE || (oldest != null && oldest < periodStart(year, month))) break
                    page++
                }
            }
            envelopeSpends(lines, year, month)
        }

    suspend fun getIncidents(accountId: String): Result<List<AccountIncident>> = runCatching {
        apiService.getAccountIncidents(accountId)
    }

    suspend fun createTransaction(request: TransactionCreateRequest): Result<Transaction> = runCatching {
        apiService.createTransaction(request)
    }

    /** [categoryId] null takes the category away; the qualification goes with it. */
    suspend fun setQualification(id: String, categoryId: String?, qualification: Qualification): Result<Transaction> = runCatching {
        apiService.updateTransactionQualification(id, categoryId?.let(::categoryIri), qualification)
    }


    /** [envelopeId] null takes the spend back to its category's envelopes. */
    suspend fun setOneOffEnvelope(id: String, envelopeId: String?): Result<Transaction> = runCatching {
        apiService.updateTransactionOneOffEnvelope(id, envelopeId?.let(::envelopeIri))
    }

    /** The events a spend can be attributed to: the one-off envelopes, newest first. */
    suspend fun getOneOffEnvelopes(): Result<List<Envelope>> = runCatching {
        apiService.getEnvelopes().filter { it.isOneOff }.sortedByDescending { it.id }
    }

    /** A blank [note] erases it. */
    suspend fun setNote(id: String, note: String): Result<Transaction> = runCatching {
        apiService.updateTransactionNote(id, note.trim().ifEmpty { null })
    }

    suspend fun deleteTransaction(id: String): Result<Unit> = runCatching {
        apiService.deleteTransaction(id)
    }

    suspend fun getTransfer(id: String): Result<TransferInfo> = runCatching {
        apiService.getTransactionTransfer(id)
    }

    suspend fun getTransferCandidates(id: String): Result<List<TransferLeg>> = runCatching {
        apiService.getTransferCandidates(id)
    }

    /** [counterpartId] null marks a line with no counterpart, or releases it when [internal] is false. */
    suspend fun setTransfer(id: String, internal: Boolean, counterpartId: String? = null): Result<TransferInfo> = runCatching {
        apiService.setTransactionTransfer(
            id,
            TransferUpdateRequest(transferKind = if (internal) "internal" else "none", counterpartId = counterpartId),
        )
    }
}

private const val TRANSACTION_PAGE_SIZE = 100
