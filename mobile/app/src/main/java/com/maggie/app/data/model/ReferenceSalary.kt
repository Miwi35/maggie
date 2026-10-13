package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * The salary the month is judged on (MAG-275). [monthlyCents] is null — never 0 —
 * when nothing is known; [source] is `declared`, `expected`, `measured` or `unknown`.
 */
@Serializable
data class ReferenceSalary(
    val monthlyCents: Int? = null,
    val source: String = "unknown",
)

/** What the month consumed against the mark; both figures are null without one. */
@Serializable
data class ConsumedVsReference(
    val consumedCents: Int = 0,
    val differenceCents: Int? = null,
    val percentOfReference: Int? = null,
)

/** A mark exists only when the API gave an amount: `unknown` is never a 0 € salary. */
val ReferenceSalary.isKnown: Boolean
    get() = source != "unknown" && (monthlyCents ?: 0) > 0

fun referenceSalarySourceLabel(source: String): String = when (source) {
    "declared" -> "déclaré sur le matelas"
    "expected" -> "attendu (opération récurrente)"
    "measured" -> "mesuré sur 12 mois"
    else -> "non renseigné"
}

/** The amount per month, or « non renseigné » — never "0,00 EUR". */
fun referenceSalaryAmountLabel(reference: ReferenceSalary): String =
    if (reference.isKnown) "${formatCents(reference.monthlyCents ?: 0)} / mois" else referenceSalarySourceLabel("unknown")

/** The share of the mark the month consumed; null (so, nothing on screen) without a mark. */
fun referenceShareLabel(review: MonthlyReview): String? {
    val percent = review.consumedVsReference.percentOfReference ?: return null
    val monthly = review.referenceSalary.monthlyCents ?: return null

    return "$percent % du salaire de référence consommés " +
        "(${formatCents(monthly)}, ${referenceSalarySourceLabel(review.referenceSalary.source)})"
}
