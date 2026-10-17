package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * What a transfer to or from someone close is (MAG-453), the `peerKind` of a line. An incoming one is
 * an income, a repayment of créances or a new dette; an outgoing one repays dettes, makes a new créance,
 * or is something else (a spend like any other).
 */
object PeerKind {
    const val INCOME = "income"
    const val REPAYMENT = "repayment"
    const val NEW_DEBT = "new_debt"
    const val NEW_CLAIM = "new_claim"
    const val OTHER = "other"

    const val INCOMING = "incoming"
    const val OUTGOING = "outgoing"

    /** The choices « Ce virement est » offers, in order, for a transfer going [direction]. */
    fun choicesFor(direction: String): List<String> =
        if (direction == OUTGOING) listOf(REPAYMENT, NEW_CLAIM, OTHER) else listOf(INCOME, REPAYMENT, NEW_DEBT)

    /** « Une recette », « Un remboursement »…: the wording of a choice, by direction. */
    fun label(kind: String, direction: String): String = when (kind) {
        INCOME -> "Une recette"
        REPAYMENT -> if (direction == OUTGOING) "Rembourse une dette" else "Un remboursement"
        NEW_DEBT -> "À rembourser"
        NEW_CLAIM -> "Nouvelle créance"
        OTHER -> "Autre"
        else -> kind
    }
}

/** One share this transaction covers, and how much of it. */
@Serializable
data class PeerAllocationLine(
    val shareId: String,
    val peerDebtId: String? = null,
    val amountCents: Int = 0,
)

/**
 * An open share the transaction may cover: a créance's share for money received, a dette's share for
 * money sent. [availableCents] is the most this transaction may put on it (what is left plus what it
 * already covers); [fromContact] marks the shares of the transaction's own contact, listed first.
 */
@Serializable
data class PeerAllocationCandidate(
    val shareId: String,
    val peerDebtId: String? = null,
    val `object`: String? = null,
    val contactId: String? = null,
    val contactName: String = "",
    val amountCents: Int = 0,
    val repaidCents: Int = 0,
    val remainingCents: Int = 0,
    val allocatedCents: Int = 0,
    val availableCents: Int = 0,
    val fromContact: Boolean = false,
)

/** `GET|PUT|DELETE /api/finance/transactions/{id}/allocation`: what a transfer is, and what it covers. */
@Serializable
data class TransactionAllocation(
    val transactionId: String,
    val amountCents: Int = 0,
    val direction: String = PeerKind.INCOMING,
    // False for an internal transfer or a rejection: nothing to say of them.
    val canBeAllocated: Boolean = false,
    // Null until « Ce virement est » is answered.
    val peerKind: String? = null,
    val contact: ContactRef? = null,
    val allocatedCents: Int = 0,
    // For money received: the part that stays an income (« Non affecté »).
    val unallocatedCents: Int = 0,
    val allocations: List<PeerAllocationLine> = emptyList(),
    val candidates: List<PeerAllocationCandidate> = emptyList(),
    // The dette (new_debt) or the créance (new_claim) this transaction made.
    val peerDebt: PeerDebt? = null,
)

@Serializable
data class PeerAllocationRequestLine(
    val shareId: String,
    val amountCents: Int,
)

/** kind = new_debt: whom the transfer is to be given back to, and how much of it. */
@Serializable
data class PeerAllocationDebt(
    val contactId: String? = null,
    val amountCents: Int,
    val `object`: String? = null,
    val notes: String? = null,
)

/** kind = new_claim: the whole amount becomes a créance on that contact. */
@Serializable
data class PeerAllocationClaim(
    val contactId: String? = null,
    val `object`: String? = null,
    val notes: String? = null,
)

/** `PUT …/allocation`: [kind] and, by kind, the shares covered, the dette or the créance. */
@Serializable
data class PeerAllocationRequest(
    val kind: String,
    val allocations: List<PeerAllocationRequestLine>? = null,
    val debt: PeerAllocationDebt? = null,
    val claim: PeerAllocationClaim? = null,
)
