package com.maggie.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Account(
    val id: String,
    val name: String,
    val bank: String? = null,
    val type: String = "checking",
    val currency: String = "EUR",
    val balanceCents: Int = 0,
    // The API serialises isCushion() as "cushion" — Symfony drops the "is"
    // prefix. Without this the flag reads false forever and the "Matelas"
    // badge never appears.
    @SerialName("cushion") val isCushion: Boolean = false,
)

/** « Boursorama · EUR · Compte courant »: two banks often both have a « Compte courant ». */
fun accountLabel(name: String, bank: String?, currency: String?): String =
    listOf(bank, currency, name).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.joinToString(" · ")

/** [fallbackBank] names the bank of an account that has none typed in but a live link. */
fun Account.label(fallbackBank: String? = null): String = accountLabel(name, bank?.takeIf { it.isNotBlank() } ?: fallbackBank, currency)

/** Human-readable French label for an account type code. */
fun accountTypeLabel(type: String): String = when (type) {
    "checking" -> "Compte courant"
    "savings" -> "Épargne"
    "investment" -> "Investissement"
    "cash" -> "Espèces"
    else -> type
}

/** Format an integer amount in cents to a "1 250,00 EUR"-style string. */
fun formatCents(cents: Int, currency: String = "EUR"): String {
    val amount = cents / 100.0
    return "%.2f %s".format(amount, currency)
}
