package com.maggie.app.data.model

/**
 * The six places of a transaction's sheet, in the order they are drawn. Each one is a named
 * slot: the later lots plug their content into [Partage] and [Jugement] without touching the order.
 */
enum class TransactionSheetSection(val key: String, val title: String) {
    Nature("nature", "Nature"),
    Classement("classement", "Classement"),
    Recurrence("recurrence", "Récurrence"),
    Partage("partage", "Partage"),
    Jugement("jugement", "Jugement"),
    Note("note", "Note"),
}

/** The nature decides the rest: a line that is not ordinary keeps only its nature and its note. */
fun visibleSheetSections(transferKind: String): List<TransactionSheetSection> =
    if (transferKind == "none") {
        TransactionSheetSection.entries
    } else {
        listOf(TransactionSheetSection.Nature, TransactionSheetSection.Note)
    }

enum class TransactionPillKind { INTERNAL, REJECTED, SERIES, SHARED, TO_SORT }

data class TransactionPill(val kind: TransactionPillKind, val label: String)

/**
 * The one row of pills a line wears in the lists. [seriesName] is the name of the series the line
 * is attached to, when the screen knows it. Nothing emits SHARED yet: the sharing lot (MAG-451) does.
 */
fun transactionPills(transaction: Transaction, seriesName: String? = null): List<TransactionPill> = buildList {
    transferBadgeLabel(transaction.transferKind, transaction.amountCents)?.let {
        add(TransactionPill(if (transaction.isRejected) TransactionPillKind.REJECTED else TransactionPillKind.INTERNAL, it))
    }
    if (transaction.isAttachedToRecurring) {
        add(TransactionPill(TransactionPillKind.SERIES, if (seriesName.isNullOrBlank()) "Série" else "Série · $seriesName"))
    }
    if (transaction.transferKind == "none" && transaction.category == null) {
        add(TransactionPill(TransactionPillKind.TO_SORT, "À trier"))
    }
}

/** « Régularisé » once the credit that gave a rejected payment back is known. */
fun incidentRegularisationLabel(counterpart: TransferLeg?): String =
    if (counterpart != null) "Régularisé" else "Non régularisé"

/** The direction a rule drafted from a line takes: the sign of its amount. */
fun ruleDirectionFor(amountCents: Int): String = if (amountCents < 0) "debit" else "credit"
