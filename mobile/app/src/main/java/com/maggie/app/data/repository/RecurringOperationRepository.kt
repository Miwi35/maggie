package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.RecurringOperationWriteRequest
import com.maggie.app.data.model.AcceptRecurringSuggestionsResult
import com.maggie.app.data.model.RecurringAttachReport
import com.maggie.app.data.model.RecurringOperationPreview
import com.maggie.app.data.model.RecurringOperationPreviewRequest
import com.maggie.app.data.model.RecurringOperationRecord
import com.maggie.app.data.model.RecurringOperationsOverview
import com.maggie.app.data.model.RecurringSuggestion
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.model.recurringOperationIri
import com.maggie.app.util.rethrowCancellation

class RecurringOperationRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getOverview(): Result<RecurringOperationsOverview> = runCatching {
        apiService.getRecurringOperationsOverview()
    }.rethrowCancellation()

    suspend fun getSuggestions(): Result<List<RecurringSuggestion>> = runCatching {
        apiService.getRecurringOperationSuggestions()
    }.rethrowCancellation()

    suspend fun acceptSuggestions(
        suggestions: List<RecurringSuggestion>,
    ): Result<AcceptRecurringSuggestionsResult> = runCatching {
        apiService.acceptRecurringOperationSuggestions(suggestions)
    }.rethrowCancellation()

    suspend fun attachHistory(dryRun: Boolean): Result<RecurringAttachReport> = runCatching {
        apiService.attachRecurringOperations(dryRun)
    }.rethrowCancellation()

    suspend fun previewSeries(request: RecurringOperationPreviewRequest): Result<RecurringOperationPreview> = runCatching {
        apiService.previewRecurringOperation(request)
    }.rethrowCancellation()

    suspend fun getRecords(): Result<List<RecurringOperationRecord>> = runCatching {
        apiService.getRecurringOperationRecords()
    }.rethrowCancellation()

    suspend fun create(request: RecurringOperationWriteRequest): Result<RecurringOperationRecord> = runCatching {
        apiService.createRecurringOperation(request)
    }.rethrowCancellation()

    suspend fun update(id: String, request: RecurringOperationWriteRequest): Result<RecurringOperationRecord> = runCatching {
        apiService.updateRecurringOperation(id, request)
    }.rethrowCancellation()

    suspend fun delete(id: String): Result<Unit> = runCatching {
        apiService.deleteRecurringOperation(id)
    }.rethrowCancellation()

    suspend fun getSeriesTransactions(seriesId: String): Result<List<Transaction>> = runCatching {
        apiService.getTransactionsOfRecurringOperation(seriesId)
    }.rethrowCancellation()

    /** How many transactions carry the series' qualification (those set by hand do not). */
    suspend fun countQualifiedBySeries(seriesId: String): Result<Int> = runCatching {
        apiService.countTransactionsQualifiedBy("qualifiedBySeries", recurringOperationIri(seriesId))
    }.rethrowCancellation()

    suspend fun attachTransaction(transactionId: String, seriesId: String, occurrenceOn: String): Result<Unit> = runCatching {
        apiService.setTransactionRecurrence(transactionId, seriesId, occurrenceOn)
    }.rethrowCancellation()

    suspend fun detachTransaction(transactionId: String): Result<Unit> = runCatching {
        apiService.setTransactionRecurrence(transactionId, null, null)
    }.rethrowCancellation()
}
