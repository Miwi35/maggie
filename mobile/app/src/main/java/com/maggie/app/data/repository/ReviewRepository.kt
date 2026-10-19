package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.ReviewProgress
import com.maggie.app.data.model.ReviewProgressUpdate
import com.maggie.app.data.model.ReviewQueue
import com.maggie.app.util.rethrowCancellation

/** Activité Comptes · la revue (MAG-456): the lines it walks and where it stands, kept by the API for every device. */
class ReviewRepository(
    private val apiService: MaggieApiService,
) {
    /** [month] is `YYYY-MM`. */
    suspend fun getQueue(month: String): Result<ReviewQueue> = runCatching {
        apiService.getReviewQueue(month)
    }.rethrowCancellation()

    suspend fun getProgress(month: String): Result<ReviewProgress> = runCatching {
        apiService.getReviewProgress(month)
    }.rethrowCancellation()

    /** The review moved to [step]. */
    suspend fun moveTo(month: String, step: String): Result<ReviewProgress> = runCatching {
        apiService.putReviewProgress(ReviewProgressUpdate(month = month, step = step))
    }.rethrowCancellation()

    /** [transactionId] was left with « Valider »: a relaunch, on any device, starts after it. */
    suspend fun markHandled(month: String, transactionId: String): Result<ReviewProgress> = runCatching {
        apiService.putReviewProgress(ReviewProgressUpdate(month = month, handledTransactionIds = listOf(transactionId)))
    }.rethrowCancellation()
}
