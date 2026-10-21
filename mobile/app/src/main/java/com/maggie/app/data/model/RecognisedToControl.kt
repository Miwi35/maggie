package com.maggie.app.data.model

import kotlinx.serialization.Serializable

const val OPERATION_RULE = "rule"
const val OPERATION_SERIES = "series"

/**
 * `POST /api/finance/review/controls` (MAG-460), now sent from the Revue's fiche (MAG-539):
 * [satisfaction] is absent for « Correct » and `keep` for « Tout à conserver ».
 */
@Serializable
data class ControlRequest(
    val transactionIds: List<String>,
    val satisfaction: String? = null,
)

@Serializable
data class ControlResult(
    val controlledCount: Int = 0,
    val judgedCount: Int = 0,
)
