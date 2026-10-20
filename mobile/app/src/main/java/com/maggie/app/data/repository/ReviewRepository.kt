package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.ApplyPlanRequest
import com.maggie.app.data.model.PeriodBalance
import com.maggie.app.data.model.PeriodPlan
import com.maggie.app.data.model.PlanItem
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

    /** The bilan of the quarter, semester or year the review of [month] closes (MAG-462). */
    suspend fun getPeriodBalance(month: String): Result<PeriodBalance> = runCatching {
        apiService.getPeriodBalance(month)
    }.rethrowCancellation()

    /** The plan proposed for [period] (`2027-Q2`…), with what is already set on it. */
    suspend fun getPeriodPlan(period: String): Result<PeriodPlan> = runCatching {
        apiService.getPeriodPlan(period)
    }.rethrowCancellation()

    /** Writes [items] on [period] in one gesture: all of them, or none (a refusal is a `PlanRefusedException`). */
    suspend fun applyPeriodPlan(period: String, items: List<PlanItem>): Result<Unit> = runCatching {
        apiService.applyPeriodPlan(ApplyPlanRequest(period, items))
    }.rethrowCancellation()
}
