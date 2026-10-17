package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/** One person's part of a créance (MAG-451): what they owe, and what they gave back so far. */
@Serializable
data class PeerDebtShare(
    val id: String,
    val contactId: String,
    val contactName: String,
    val amountCents: Int = 0,
    val repaidCents: Int = 0,
    val remainingCents: Int = 0,
)

/**
 * Money owed between the owner and the people around them (MAG-451): the part of a shared spend
 * the others owe (« owed_to_me »), split in one share per person.
 */
@Serializable
data class PeerDebt(
    val id: String,
    // "owed_to_me" | "i_owe".
    val direction: String = DIRECTION_OWED_TO_ME,
    val `object`: String? = null,
    val notes: String? = null,
    val originTransactionId: String? = null,
    val amountCents: Int = 0,
    val repaidCents: Int = 0,
    val remainingCents: Int = 0,
    // "open" | "settled".
    val status: String = STATUS_OPEN,
    val createdAt: String? = null,
    val shares: List<PeerDebtShare> = emptyList(),
) {
    val isOpen: Boolean get() = status == STATUS_OPEN

    companion object {
        const val DIRECTION_OWED_TO_ME = "owed_to_me"
        const val DIRECTION_I_OWE = "i_owe"
        const val STATUS_OPEN = "open"
    }
}

/** What `GET /api/finance/transactions/{id}/my-share` says of a line: the part kept and the créance of the rest. */
@Serializable
data class MyShare(
    val transactionId: String,
    val amountCents: Int = 0,
    // Null while the spend is not shared: the whole amount is the owner's.
    val myShareCents: Int? = null,
    val canBeShared: Boolean = false,
    val peerDebt: PeerDebt? = null,
)

/** One person of `PUT …/my-share`: no [amountCents] on every share asks the server to split equally. */
@Serializable
data class MyShareRequestShare(
    val contactId: String,
    val amountCents: Int? = null,
)

@Serializable
data class MyShareRequest(
    val myShareCents: Int,
    val `object`: String? = null,
    val notes: String? = null,
    val shares: List<MyShareRequestShare>,
)

/**
 * What `GET /api/finance/transactions/{id}/debt` says of a received transfer (MAG-452): whether it can be
 * declared « À rembourser », the contact its label suggests, and the dette (« i_owe », one share) once declared.
 */
@Serializable
data class TransactionDebt(
    val transactionId: String,
    val amountCents: Int = 0,
    val canBeDebt: Boolean = false,
    val suggestedContactId: String? = null,
    val peerDebt: PeerDebt? = null,
)

/** `POST …/debt`: whom the transfer is to be given back to, and how much of it. */
@Serializable
data class TransactionDebtRequest(
    val contactId: String,
    val amountCents: Int,
    val `object`: String? = null,
    val notes: String? = null,
)

/** `GET /api/finance/peer-debts/summary`: what is left to be given back, both ways. */
@Serializable
data class PeerDebtSummary(
    val owedToMeCents: Int = 0,
    val iOweCents: Int = 0,
)

/**
 * [totalCents] split in [count] equal parts, the last cent(s) on the last one — as the API
 * splits it when no share names an amount. Empty for no one; never negative.
 */
fun splitEqually(totalCents: Int, count: Int): List<Int> {
    if (count <= 0) return emptyList()
    val total = totalCents.coerceAtLeast(0)
    val each = total / count
    return List(count) { index -> if (index == count - 1) total - each * (count - 1) else each }
}

/** « te doit 200,00 EUR »: what is left of one person's share. */
fun owesYouLabel(remainingCents: Int, currency: String = "EUR"): String = "te doit ${formatCents(remainingCents, currency)}"

/** « tu lui dois 280,00 EUR »: what the owner still has to give back to one person (MAG-452). */
fun youOweLabel(remainingCents: Int, currency: String = "EUR"): String = "tu lui dois ${formatCents(remainingCents, currency)}"

/** One open créance of a contact, as their page lists it under « En cours ». */
data class ContactDebt(
    val debtId: String,
    val subject: String,
    /** « 2026-03-20 », the day the créance was made; null when unknown. */
    val createdOn: String?,
    /** What is left of this contact's share. */
    val remainingCents: Int,
    /** [PeerDebt.DIRECTION_OWED_TO_ME] (they owe the owner) or [PeerDebt.DIRECTION_I_OWE]. */
    val direction: String = PeerDebt.DIRECTION_OWED_TO_ME,
)

/** The open créances [contactId] still owes something on, the latest first. */
fun openDebtsOf(debts: List<PeerDebt>, contactId: String): List<ContactDebt> =
    debts
        .filter { it.isOpen }
        .mapNotNull { debt ->
            val share = debt.shares.firstOrNull { it.contactId == contactId && it.remainingCents > 0 } ?: return@mapNotNull null
            ContactDebt(
                debtId = debt.id,
                subject = debt.`object`.orEmpty().ifBlank { "Dépense partagée" },
                createdOn = debt.createdAt?.take(10),
                remainingCents = share.remainingCents,
                direction = debt.direction,
            )
        }
        .sortedByDescending { it.createdOn.orEmpty() }

private val DEBT_DAY_FORMAT = java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.FRANCE)

/** « 20 mars 2026 », or null when the day is unknown or does not parse. */
fun debtDayLabel(createdOn: String?): String? =
    createdOn?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }?.format(DEBT_DAY_FORMAT)
