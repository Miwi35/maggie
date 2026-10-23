package com.maggie.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * A simulated placement of the owner (MAG-555): an initial payment, a deposit paid every month,
 * quarter or year — or once, on day one — an annual rate and a duration. Nothing is booked: the
 * API computes what it would give ([finalCents], [paid], [interest]) and the rent it would pay
 * withdrawn at 4 % a year ([rent], always a simulation). Read from `GET /api/finance/placements`,
 * sorted by name.
 *
 * Every amount is in integer cents; [annualRate] is in basis points (600 = 6 %).
 */
@Serializable
data class Placement(
    val id: String,
    val name: String,
    val initial: Int = 0,
    val deposit: Int = 0,
    val frequency: String = PlacementFrequency.MONTHLY.key,
    val annualRate: Int = 0,
    val years: Int = 10,
    /** `final` on the wire: a Kotlin modifier is a poor name for a field. */
    @SerialName("final") val finalCents: Long = 0,
    val paid: Long = 0,
    val interest: Long = 0,
    val rent: Long = 0,
    /** What the deposits come to per month on average: 0 for a single one. */
    val monthlyDeposit: Long = 0,
)

/** How often the deposit is paid; [months] between two, 0 for a single deposit on day one. */
enum class PlacementFrequency(val key: String, val label: String, val per: String, val months: Int) {
    MONTHLY("monthly", "Mensuel", "par mois", 1),
    QUARTERLY("quarterly", "Trimestriel", "par trimestre", 3),
    YEARLY("yearly", "Annuel", "par an", 12),
    ONCE("once", "Ponctuel", "une fois", 0),
    ;

    companion object {
        fun of(key: String): PlacementFrequency = entries.firstOrNull { it.key == key } ?: MONTHLY
    }
}

/** `GET /api/finance/placements/{id}/simulation`: the figures and the capital month by month, month 0 to years × 12. */
@Serializable
data class PlacementSimulation(
    @SerialName("final") val finalCents: Long = 0,
    val paid: Long = 0,
    val interest: Long = 0,
    val rent: Long = 0,
    val curve: List<PlacementPoint> = emptyList(),
)

/** The capital at the end of [month], and what was paid in by then. */
@Serializable
data class PlacementPoint(
    val month: Int,
    val capital: Long = 0,
    val paid: Long = 0,
)

/** The bounds the API checks (`Placement::MAX_YEARS`, `MAX_RATE_BASIS_POINTS`). */
const val PLACEMENT_MAX_YEARS = 60
const val PLACEMENT_MAX_RATE = 10_000

/** A non-breaking space: « 74 430 € » and « 6 % » never break across two lines. */
private const val NBSP = '\u00A0'

/** « 74 430 € »: whole euros, rounded — a projection is not precise to the cent. One format per call: DecimalFormat is not thread-safe. */
fun formatEuros(cents: Long): String {
    val format = DecimalFormat("#,##0", DecimalFormatSymbols(Locale.FRANCE).apply { groupingSeparator = NBSP })
    return format.format(BigDecimal(cents).movePointLeft(2).setScale(0, RoundingMode.HALF_UP)) + "$NBSP€"
}

/** « 6 % », « 3,5 % », « 3,25 % » from basis points. */
fun formatPlacementRate(basisPoints: Int): String =
    BigDecimal(basisPoints).movePointLeft(2).stripTrailingZeros().toPlainString().replace('.', ',') + "$NBSP%"

/** « 1 an », « 20 ans ». */
fun yearsLabel(years: Int): String = if (years > 1) "$years ans" else "$years an"

/** What is paid each time: the deposit, or for a single payment everything paid on day one. */
fun placementPayment(placement: Placement): Int =
    if (PlacementFrequency.of(placement.frequency) == PlacementFrequency.ONCE) {
        placement.initial + placement.deposit
    } else {
        placement.deposit
    }

/** « 150 € par mois · 6 % · 20 ans », « 500 € une fois · 3 % · 10 ans ». */
fun placementTerms(placement: Placement): String {
    val frequency = PlacementFrequency.of(placement.frequency)
    return "${formatEuros(placementPayment(placement).toLong())} ${frequency.per} · " +
        "${formatPlacementRate(placement.annualRate)} · ${yearsLabel(placement.years)}"
}
