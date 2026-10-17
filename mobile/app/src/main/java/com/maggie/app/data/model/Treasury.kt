package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Serializable
data class TreasurySalary(
    val isConfigured: Boolean = false,
    val expectedOn: String? = null,
    val referenceCents: Int? = null,
    val isReceived: Boolean = false,
    val isLate: Boolean = false,
    val delayDays: Int = 0,
    val shortfallCents: Int = 0,
)

@Serializable
data class TreasuryMovement(
    val id: String,
    val label: String,
    val amountCents: Int,
    val status: String = "",
    val accountId: String = "",
    val accountName: String = "",
    val source: String = "",
)

@Serializable
data class TreasuryDay(
    val date: String,
    val balanceCents: Int,
    val movements: List<TreasuryMovement> = emptyList(),
    val cumulativeInflowCents: Int = 0,
    val cumulativeOutflowCents: Int = 0,
)

@Serializable
data class BalanceBeforeSalary(
    val onDate: String,
    val balanceCents: Int,
)

@Serializable
data class TreasuryOverdraft(
    val isForecast: Boolean = false,
    val onDate: String? = null,
    val worstBalanceCents: Int = 0,
    val limitCents: Int = 0,
)

@Serializable
data class AdviceAlternative(
    val accountId: String,
    val accountName: String,
    val balanceCents: Int = 0,
    val amountCents: Int = 0,
    val isCushion: Boolean = false,
)

@Serializable
data class TreasuryAdvice(
    val amountCents: Int = 0,
    val sourceAccountId: String? = null,
    val sourceAccountName: String? = null,
    val sourceBalanceCents: Int = 0,
    val sourceAmountCents: Int = 0,
    val isCushion: Boolean = false,
    val alternatives: List<AdviceAlternative> = emptyList(),
    val uncoveredCents: Int = 0,
    val touchesCushion: Boolean = false,
)

@Serializable
data class TreasuryOutlook(
    val today: String = "",
    val salary: TreasurySalary = TreasurySalary(),
    val balanceCents: Int = 0,
    val projection: List<TreasuryDay> = emptyList(),
    val balanceBeforeSalary: BalanceBeforeSalary? = null,
    val overdraft: TreasuryOverdraft = TreasuryOverdraft(),
    val advice: TreasuryAdvice? = null,
    val outstandingAdvancesCents: Int = 0,
)

@Serializable
data class TreasurySettings(
    val salaryExpectedDay: Int? = null,
    val overdraftLimitCents: Int = 0,
)

/** One movement of the projection with its day: what the list under the chart draws. */
data class TreasuryMovementRow(
    val date: String,
    val label: String,
    val amountCents: Int,
    val isRecurring: Boolean,
)

/** Every movement of the projection, day by day, the days without any left out. */
fun TreasuryOutlook.movementRows(): List<TreasuryMovementRow> =
    projection.flatMap { day ->
        day.movements.map { TreasuryMovementRow(day.date, it.label, it.amountCents, it.source == "recurring") }
    }

/** A horizon the owner can pick: `months` null is the API's default, the end of next month. */
data class TreasuryHorizon(val months: Int?, val label: String)

val TREASURY_HORIZONS = listOf(
    TreasuryHorizon(null, "Fin du mois suivant"),
    TreasuryHorizon(1, "1 mois"),
    TreasuryHorizon(3, "3 mois"),
    TreasuryHorizon(6, "6 mois"),
    TreasuryHorizon(12, "1 an"),
)

/** « Taxe foncière (récurrent) (-977,00 €) » — one movement of a day, as the list and the touch detail read it. */
fun TreasuryMovement.text(): String =
    "$label${if (source == "recurring") " (récurrent)" else ""} (${formatCents(amountCents)})"

/** What a touch on a day of the balance curve says: the date, the balance and the day's movements. */
fun TreasuryDay.balanceDetail(): String =
    "${treasuryDate(date)} : solde ${formatCents(balanceCents)}" + movementsSuffix()

/** What a touch on a day of the flows curves says: the date, both cumulated amounts and the day's movements. */
fun TreasuryDay.flowsDetail(): String =
    "${treasuryDate(date)} : entrées cumulées ${formatCents(cumulativeInflowCents)}, " +
        "sorties cumulées ${formatCents(cumulativeOutflowCents)}" + movementsSuffix()

private fun TreasuryDay.movementsSuffix(): String =
    if (movements.isEmpty()) "" else " — " + movements.joinToString(", ") { it.text() }

/** The day under a touch at `x` of a chart `width` wide, `count` days drawn edge to edge; null when there is none. */
fun treasuryDayIndexAt(x: Float, width: Float, count: Int): Int? {
    if (count <= 0 || width <= 0f) return null
    if (count == 1) return 0

    return Math.round(x.coerceIn(0f, width) / width * (count - 1))
}

/** What the overdraft banner says: plain values, so any screen can draw it from its own state. */
data class TreasuryBannerState(
    val overdraftOn: String,
    val balanceBeforeSalaryCents: Int,
    val balanceBeforeSalaryOn: String,
)

/** The banner of the outlook, or null when no overdraft is forecast. */
fun TreasuryOutlook.toBanner(): TreasuryBannerState? {
    val on = overdraft.onDate
    val before = balanceBeforeSalary
    if (!overdraft.isForecast || on == null || before == null) return null

    return TreasuryBannerState(on, before.balanceCents, before.onDate)
}

private val SHORT_DATE = DateTimeFormatter.ofPattern("d MMMM", Locale.FRENCH)

/** « 25 octobre » from `2026-10-25`; the text itself when it is not a date. */
fun treasuryDate(iso: String): String =
    runCatching { LocalDate.parse(iso).format(SHORT_DATE) }.getOrDefault(iso)

fun salaryDelayLabel(days: Int): String =
    "Salaire en retard de $days ${if (days == 1) "jour" else "jours"}"

const val CUSHION_ADVANCE_WARNING =
    "entame le matelas, il se reconstituera quand tu rendras l'avance"

const val ADVANCE_LABEL = "Avance vers le compte courant"

/** What « Préparer l'avance » fills in on the new transaction; nothing is saved by it. */
@Serializable
data class TransactionPrefill(
    val label: String,
    val amountCents: Int,
    val status: String,
    val bookedAt: String,
)

/** The outflow from the savings account the advice names, or null when it names none. */
fun TreasuryAdvice.toPrefill(today: String): TransactionPrefill? {
    if (sourceAccountId == null || sourceAmountCents <= 0) return null

    return TransactionPrefill(ADVANCE_LABEL, -sourceAmountCents, "spent", today)
}

/** What the new-transaction form shows for a prefill: the amount as typed, without its sign. */
data class TransactionFormValues(
    val label: String,
    val amountText: String,
    val isIncome: Boolean,
    val status: String,
    val date: LocalDate,
)

fun TransactionPrefill.toFormValues(fallbackDate: LocalDate): TransactionFormValues = TransactionFormValues(
    label = label,
    amountText = String.format(Locale.ROOT, "%.2f", kotlin.math.abs(amountCents) / 100.0),
    isIncome = amountCents > 0,
    status = status,
    date = runCatching { LocalDate.parse(bookedAt) }.getOrDefault(fallbackDate),
)
