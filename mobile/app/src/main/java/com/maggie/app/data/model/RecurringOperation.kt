package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import java.time.LocalDate

const val DEFAULT_AMOUNT_TOLERANCE_PERCENT = 20
const val DEFAULT_DATE_TOLERANCE_DAYS = 5

/** What `GET /api/finance/recurring-operations/overview` reads. */
@Serializable
data class RecurringOperationsOverview(
    val operations: List<RecurringSeries> = emptyList(),
    val proposals: List<RecurringProposal> = emptyList(),
    val totals: RecurringTotals = RecurringTotals(),
)

@Serializable
data class PriceChange(
    val previousCents: Int = 0,
    val currentCents: Int = 0,
    val percent: Int = 0,
    val since: String = "",
)

@Serializable
data class MissedOccurrence(
    val occurrenceOn: String = "",
    val daysOverdue: Int = 0,
)

/** The latest attached transaction, against the due date and the amount it was attached to. */
@Serializable
data class LastOccurrence(
    val transactionId: String = "",
    val occurrenceOn: String = "",
    val bookedAt: String = "",
    val amountCents: Int = 0,
    val dateGapDays: Int = 0,
    val expectedAmountCents: Int = 0,
    val deviations: List<String> = emptyList(),
)

/** One recurring operation as the overview reads it: its cost, its next due date and its two warnings. */
@Serializable
data class RecurringSeries(
    val id: String,
    val label: String,
    val categoryId: String = "",
    val categoryName: String = "",
    val accountId: String = "",
    val accountName: String = "",
    val accountBank: String? = null,
    val period: String = "monthly",
    val dayRule: String = "fixed_day",
    val referenceAmountCents: Int = 0,
    val referenceSource: String = "measured",
    val currency: String = "EUR",
    val monthlyCostCents: Int = 0,
    val yearlyCostCents: Int = 0,
    val endsOn: String? = null,
    val nextOccurrenceOn: String? = null,
    val lastAttachedOn: String? = null,
    val attachmentCount: Int = 0,
    val lastOccurrence: LastOccurrence? = null,
    val missedOccurrence: MissedOccurrence? = null,
    val priceChange: PriceChange? = null,
)

@Serializable
data class RecurringTotals(
    val monthlyCostCents: Int = 0,
    val yearlyCostCents: Int = 0,
    val monthlyIncomeCents: Int = 0,
    val activeCount: Int = 0,
)

/** One series' claim on a transaction: which due date, how far off, what amount it expected. */
@Serializable
data class ProposalClaim(
    val recurringOperationId: String? = null,
    val recurringOperationLabel: String? = null,
    val occurrenceOn: String? = null,
    val dateGapDays: Int = 0,
    val referenceAmountCents: Int = 0,
    val amountChangePercent: Int = 0,
)

/**
 * A transaction the automatic pass only proposes to attach, with the tolerance it broke
 * (`reason`). Nothing is written until the owner says yes. When two series claim the
 * line (`ambiguous`), [candidates] names them and the top-level claim is empty.
 */
@Serializable
data class RecurringProposal(
    val transactionId: String,
    val label: String,
    val bookedAt: String = "",
    val amountCents: Int = 0,
    // Null on an attachment: nothing broke, there is no reason to give.
    val reason: String? = null,
    val recurringOperationId: String? = null,
    val recurringOperationLabel: String? = null,
    val occurrenceOn: String? = null,
    val dateGapDays: Int = 0,
    val referenceAmountCents: Int = 0,
    val amountChangePercent: Int = 0,
    val candidates: List<ProposalClaim> = emptyList(),
)

/** A series the bank history implies; none exists until the owner accepts it. */
@Serializable
data class RecurringSuggestion(
    val label: String,
    val counterpartyName: String? = null,
    val counterpartyKey: String? = null,
    val labelPattern: String? = null,
    val accountId: String,
    val accountName: String = "",
    // No defaults on what `accept` requires: the client does not encode a default, so a
    // « monthly » left implicit would never reach the API, which refuses a line without a period.
    val period: String,
    val anchorOn: String,
    val dayRule: String,
    val referenceAmountCents: Int,
    val amountSpreadPercent: Int = 0,
    val occurrences: Int = 0,
    val categoryId: String? = null,
    val categoryName: String? = null,
    val samples: List<String> = emptyList(),
)

@Serializable
data class RecurringSuggestionsResponse(
    val suggestions: List<RecurringSuggestion> = emptyList(),
)

/** What `accept` takes: the suggestion as read, with the category the owner chose when the API had none. */
@Serializable
data class AcceptRecurringSuggestionsRequest(
    val suggestions: List<RecurringSuggestion>,
)

@Serializable
data class AcceptRecurringSuggestionsResult(
    val created: Int = 0,
    val labels: List<String> = emptyList(),
    val attached: Int = 0,
    val proposed: Int = 0,
)

/** The catch-up over the history; a `dryRun` writes nothing and says what it would attach. */
@Serializable
data class RecurringAttachReport(
    val dryRun: Boolean = false,
    val scanned: Int = 0,
    val attached: Int = 0,
    val proposed: Int = 0,
    val attachments: List<RecurringProposal> = emptyList(),
    val proposals: List<RecurringProposal> = emptyList(),
)

@Serializable
data class RecurringAttachRequest(
    // No default: the client does not encode one, and a `dryRun` left implicit would not be sent.
    val dryRun: Boolean,
)

/**
 * A series as the resource stores it: what the overview does not carry and the form needs
 * to edit. `category` and `account` are IRIs, as everywhere in API Platform.
 */
@Serializable
data class RecurringOperationRecord(
    val id: String,
    val label: String,
    val counterpartyName: String? = null,
    val labelPattern: String? = null,
    val category: String = "",
    val qualification: Qualification = Qualification.NONE,
    val account: String = "",
    val period: String = "monthly",
    val anchorOn: String = "",
    val dayRule: String = "fixed_day",
    val referenceAmountCents: Int = 0,
    val referenceSource: String = "measured",
    val amountTolerancePercent: Int = DEFAULT_AMOUNT_TOLERANCE_PERCENT,
    val dateToleranceDays: Int = DEFAULT_DATE_TOLERANCE_DAYS,
    val endsOn: String? = null,
)

/** The pieces of an IRI the API hands back: `/api/categories/01H…` → `01H…`. */
fun idOfIri(iri: String): String = iri.substringAfterLast('/')

fun recurringOperationIri(id: String): String = "/api/recurring_operations/${idOfIri(id)}"

val PERIOD_CHOICES: List<Pair<String, String>> = listOf(
    "weekly" to "Chaque semaine",
    "monthly" to "Chaque mois",
    "quarterly" to "Chaque trimestre",
    "yearly" to "Chaque année",
)

val DAY_RULE_CHOICES: List<Pair<String, String>> = listOf(
    "fixed_day" to "Un jour fixe",
    "last_day_of_month" to "Le dernier jour du mois",
)

fun periodLabel(period: String): String = PERIOD_CHOICES.firstOrNull { it.first == period }?.second ?: period

fun referenceSourceLabel(source: String): String = when (source) {
    "declared" -> "déclaré"
    "measured" -> "mesuré"
    else -> source
}

private val WEEKDAYS = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")

private val MONTHS = listOf(
    "janvier", "février", "mars", "avril", "mai", "juin",
    "juillet", "août", "septembre", "octobre", "novembre", "décembre",
)

private fun ordinal(day: Int): String = if (day == 1) "1er" else day.toString()

private fun parseDay(iso: String?): LocalDate? =
    iso?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/** `2026-10-12` → `12/10/2026`. */
fun formatDay(iso: String): String = iso.take(10).split('-').reversed().joinToString("/")

/** `2026-10-12` → `12/10`. */
fun formatShortDay(iso: String): String = formatDay(iso).take(5)

/**
 * The expected day in plain French: « le 5 de chaque mois », « le dernier jour du mois ».
 * Without the series' anchor only the period can be said.
 */
fun describeSchedule(period: String, dayRule: String, anchorOn: String? = null): String {
    val lastDay = dayRule == "last_day_of_month"
    val anchor = parseDay(anchorOn)

    return when (period) {
        "weekly" -> anchor?.let { "chaque ${WEEKDAYS[it.dayOfWeek.value - 1]}" } ?: "chaque semaine"
        "monthly" -> when {
            lastDay -> "le dernier jour du mois"
            anchor != null -> "le ${ordinal(anchor.dayOfMonth)} de chaque mois"
            else -> "chaque mois"
        }
        "quarterly" -> when {
            lastDay -> "le dernier jour du mois, tous les trois mois"
            anchor != null -> "le ${ordinal(anchor.dayOfMonth)}, tous les trois mois"
            else -> "chaque trimestre"
        }
        "yearly" -> when {
            lastDay && anchor != null -> "le dernier jour de ${MONTHS[anchor.monthValue - 1]}, chaque année"
            anchor != null -> "le ${ordinal(anchor.dayOfMonth)} ${MONTHS[anchor.monthValue - 1]} de chaque année"
            else -> "chaque année"
        }
        else -> periodLabel(period)
    }
}

private fun money(cents: Int, currency: String): String = formatCents(kotlin.math.abs(cents), currency)

private fun signedPercent(percent: Int): String = "${if (percent > 0) "+" else ""}$percent %"

/** « 13,49 € → 17,99 €, +33 % ». */
fun describePriceChange(change: PriceChange, currency: String = "EUR"): String =
    "${money(change.previousCents, currency)} → ${money(change.currentCents, currency)}, ${signedPercent(change.percent)}"

/** Why the pass only proposed the line, in the owner's words. */
fun describeReason(proposal: RecurringProposal, currency: String = "EUR"): String {
    val days = kotlin.math.abs(proposal.dateGapDays)
    val plural = if (days > 1) "s" else ""
    val due = formatShortDay(proposal.occurrenceOn ?: proposal.bookedAt)

    return when (proposal.reason) {
        "late" -> "arrivée $days jour$plural après l'échéance du $due"
        "early" -> "arrivée $days jour$plural avant l'échéance du $due"
        "amount_up", "amount_down" -> {
            val kind = if (proposal.reason == "amount_up") "hausse" else "baisse"
            "$kind de prix ${money(proposal.referenceAmountCents, currency)} → " +
                "${money(proposal.amountCents, currency)}, ${signedPercent(proposal.amountChangePercent)}"
        }
        "ambiguous" -> "plusieurs opérations récurrentes peuvent la réclamer"
        else -> proposal.reason.orEmpty()
    }
}

/** The claims a proposal can be attached to: one, or the candidates of an ambiguous line. */
fun claimsOf(proposal: RecurringProposal): List<ProposalClaim> =
    if (proposal.reason == "ambiguous") {
        proposal.candidates
    } else if (proposal.recurringOperationId != null) {
        listOf(
            ProposalClaim(
                recurringOperationId = proposal.recurringOperationId,
                recurringOperationLabel = proposal.recurringOperationLabel,
                occurrenceOn = proposal.occurrenceOn,
                dateGapDays = proposal.dateGapDays,
                referenceAmountCents = proposal.referenceAmountCents,
                amountChangePercent = proposal.amountChangePercent,
            ),
        )
    } else {
        emptyList()
    }

/** « 11 transactions Netflix depuis janvier, les rattacher ? » */
fun describeHistory(name: String, bookedAt: List<String>, today: LocalDate = LocalDate.now()): String {
    val oldest = parseDay(bookedAt.minOrNull()) ?: today
    val since = if (oldest.year == today.year) MONTHS[oldest.monthValue - 1] else "${MONTHS[oldest.monthValue - 1]} ${oldest.year}"
    val noun = if (bookedAt.size > 1) "transactions" else "transaction"

    return "${bookedAt.size} $noun $name depuis $since, les rattacher ?"
}

/** The sentence under a created series: what the first pass over the history did. */
fun acceptedRecurringSummary(result: AcceptRecurringSuggestionsResult): String {
    val created = if (result.created > 1) "${result.created} opérations récurrentes créées" else "${result.created} opération récurrente créée"
    return "$created, ${result.attached} transaction(s) rattachée(s), ${result.proposed} à confirmer."
}

/** What `POST /api/finance/recurring-operations/preview` is asked: the identity of a series still being written. */
@Serializable
data class RecurringOperationPreviewRequest(
    val referenceAmountCents: Int,
    val amountTolerancePercent: Int? = null,
    val counterpartyName: String? = null,
    val labelPattern: String? = null,
    val accountId: String? = null,
)

@Serializable
data class RecurringOperationPreviewMatch(
    val transactionId: String,
    val label: String = "",
    val amountCents: Int = 0,
    val bookedAt: String = "",
    val accountId: String? = null,
)

/** The lines a series would recognise: [total] of them, the first ones in [matches]. */
@Serializable
data class RecurringOperationPreview(
    val total: Int = 0,
    val matches: List<RecurringOperationPreviewMatch> = emptyList(),
)
