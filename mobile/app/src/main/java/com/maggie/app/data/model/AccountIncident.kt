package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * A payment the bank rejected, as `GET /api/accounts/{id}/incidents` reads it: the debit
 * and the credit that gave it back are one line (MAG-375). Either id is null when its
 * leg is not on the account.
 */
@Serializable
data class AccountIncident(
    val debitId: String? = null,
    val creditId: String? = null,
    val bookedAt: String,
    val rejectedAt: String? = null,
    val counterpartyName: String,
    val amountCents: Int = 0,
    // "direct_debit" or "transfer".
    val kind: String = "direct_debit",
) {
    /** The leg a tap opens: the payment itself, or the credit when the payment is not followed. */
    val openId: String? get() = debitId ?: creditId

    /** The line to show while the detail screen reads the rest. */
    fun asTransaction(): Transaction? = openId?.let { id ->
        Transaction(
            id = id,
            label = counterpartyName,
            amountCents = if (debitId != null) -amountCents else amountCents,
            bookedAt = bookedAt,
            transferKind = "rejected",
        )
    }
}

@Serializable
data class AccountIncidents(
    val incidents: List<AccountIncident> = emptyList(),
)

fun incidentKindLabel(kind: String): String = when (kind) {
    "transfer" -> "Virement rejeté"
    else -> "Prélèvement rejeté"
}

/** The tab title: « Incidents (2) », bare while the count is unknown. */
fun incidentsTabTitle(count: Int?): String = if (count == null) "Incidents" else "Incidents ($count)"
