package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.TransactionCreateRequest
import com.maggie.app.data.model.AccountIncident
import com.maggie.app.data.model.Envelope
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.model.InternalTransferAnswer
import com.maggie.app.data.model.Qualification
import com.maggie.app.data.model.SortRequest
import com.maggie.app.data.model.SortResult
import com.maggie.app.data.model.ToSortPile
import com.maggie.app.data.model.TransferInfo
import com.maggie.app.data.model.TransferLeg
import com.maggie.app.data.model.TransferUpdateRequest
import com.maggie.app.data.model.categoryIri
import com.maggie.app.data.model.bookedDate
import com.maggie.app.data.model.envelopeIri
import com.maggie.app.data.model.envelopeSpends
import com.maggie.app.data.model.periodStart
import com.maggie.app.util.rethrowCancellation

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

    /** The rejections the owner already settled, apart from the pile (MAG-424). */
    suspend fun getSettledIncidents(accountId: String): Result<List<AccountIncident>> = runCatching {
        apiService.getAccountIncidents(accountId, settled = true)
    }

    suspend fun settleRejection(transactionId: String): Result<Unit> = runCatching {
        apiService.settleRejection(transactionId)
    }

    suspend fun unsettleRejection(transactionId: String): Result<Unit> = runCatching {
        apiService.unsettleRejection(transactionId)
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

    /** [oldestFirst] walks the pile from its oldest line; [limit] bounds the page, the totals still count the whole pile. */
    suspend fun getToSort(limit: Int? = null, oldestFirst: Boolean = false): Result<ToSortPile> = runCatching {
        apiService.getTransactionsToSort(limit, oldestFirst)
    }.rethrowCancellation()

    /** Gives a spend its satisfaction: "keep", "avoidable", or "unrated" to take it back. */
    suspend fun setRetrospect(id: String, verdict: String): Result<Unit> = runCatching {
        apiService.rateTransaction(id, verdict)
    }.rethrowCancellation()

    /** Files the line under [categoryId]; [alwaysForMerchant] also writes the rule that files the merchant's other lines. */
    suspend fun sortToCategory(id: String, categoryId: String, alwaysForMerchant: Boolean): Result<SortResult> = runCatching {
        apiService.sortTransaction(id, SortRequest(categoryId = categoryId, alwaysForMerchant = alwaysForMerchant))
    }.rethrowCancellation()

    /** Answers « it is a move between my own accounts »; [counterpartId] null means the other leg is not imported. */
    suspend fun sortAsInternalTransfer(id: String, counterpartId: String?): Result<SortResult> = runCatching {
        apiService.sortTransaction(id, SortRequest(internalTransfer = InternalTransferAnswer(counterpartId)))
    }.rethrowCancellation()
}

private const val TRANSACTION_PAGE_SIZE = 100
