package com.maggie.app.data.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency
import java.util.Locale
import kotlin.math.abs

private const val THIN_NBSP = '\u202F'
private const val NBSP = '\u00A0'
private const val MINUS = '\u2212'
private const val DEFAULT_SYMBOL = "€"

/**
 * The one way an amount is shown (MAG-621), as the mock-ups write it: « 2 743,60 € », « −120,00 € ».
 * Thin no-break space between the thousands, comma, the symbol after the number — never the ISO code, never « XXX ».
 */
fun formatCents(cents: Long, currency: String? = "EUR"): String = format(abs(cents), cents < 0, 2, currency)

fun formatCents(cents: Int, currency: String? = "EUR"): String = formatCents(cents.toLong(), currency)

/** « 9 482 € »: whole units, rounded half up — for summary balances, targets and projections, which are not precise to the cent. */
fun formatCentsRounded(cents: Long, currency: String? = "EUR"): String {
    val units = BigDecimal(abs(cents)).movePointLeft(2).setScale(0, RoundingMode.HALF_UP).toLong()
    return format(units * 100, cents < 0 && units != 0L, 0, currency)
}

fun formatCentsRounded(cents: Int, currency: String? = "EUR"): String = formatCentsRounded(cents.toLong(), currency)

private fun format(magnitudeCents: Long, negative: Boolean, decimals: Int, currency: String?): String {
    val units = (magnitudeCents / 100).toString().reversed().chunked(3).joinToString(THIN_NBSP.toString()).reversed()
    val number = if (decimals == 0) units else "$units,${"%02d".format(Locale.ROOT, magnitudeCents % 100)}"
    return "${if (negative) MINUS.toString() else ""}$number$NBSP${currencySymbol(currency)}"
}

/** The bank may send « XXX » (no currency, MAG-376) or nothing: the symbol falls back to « € », the code is never shown. */
private fun currencySymbol(currency: String?): String {
    val code = currency?.trim()?.uppercase().orEmpty()
    if (code.isEmpty() || code == "EUR" || code == "XXX") return DEFAULT_SYMBOL
    return runCatching { Currency.getInstance(code).getSymbol(Locale.FRANCE) }.getOrDefault(DEFAULT_SYMBOL)
}
