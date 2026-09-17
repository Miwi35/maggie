package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class CushionAccount(
    val id: String,
    val name: String,
    val balanceCents: Int = 0,
    val currency: String = "EUR",
)

@Serializable
data class CushionStatus(
    val state: String = "building",
    val targetMonths: Int = 3,
    val monthlyNetIncomeCents: Int = 0,
    val targetCents: Int = 0,
    val currentCents: Int = 0,
    val deficitCents: Int = 0,
    val coveragePercent: Int = 0,
    val monthsCovered: Double = 0.0,
    val rechargeCapCents: Int = 0,
    val rechargeTargetMonths: Int = 6,
    val monthlyRechargeCents: Int = 0,
    val rechargeMonths: Int = 0,
    val isCappedByRechargeCap: Boolean = false,
    val blocksGreenScore: Boolean = true,
    val isConfigured: Boolean = false,
    val accounts: List<CushionAccount> = emptyList(),
)

/** Human-readable French label for a cushion state code. */
fun cushionStateLabel(state: String): String = when (state) {
    "building" -> "Constitution en cours"
    "complete" -> "Complet"
    "recharging" -> "En recharge"
    else -> state
}

/** How the recharge reads once the cap has had its say. */
fun rechargePlanLabel(status: CushionStatus): String = when {
    status.deficitCents <= 0 -> "Matelas complet"
    status.monthlyRechargeCents <= 0 -> "Aucun plan de recharge"
    else -> "${formatCents(status.monthlyRechargeCents)} par mois pendant " +
        "${status.rechargeMonths} mois"
}
