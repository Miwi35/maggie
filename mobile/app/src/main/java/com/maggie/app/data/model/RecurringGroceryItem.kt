package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Serializable
data class RecurringGroceryItem(
    val id: String,
    /** IRI of the product (`/api/products/{id}`); null for an item that is only a label. */
    val product: String? = null,
    /** The product's name, or the custom label. */
    val label: String = "",
    val quantity: Float? = null,
    val unit: CookbookUnit? = null,
    val frequency: RecurringFrequency,
    val lastAddedAt: String? = null,
    /** `yyyy-MM-dd`: when the item next reaches the list (today when it is overdue or was never added). */
    val nextDueAt: String? = null,
) {
    val productId: String? get() = product?.substringAfterLast('/')
}

private val NEXT_DUE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy")

/** « dd/MM/yyyy » for a `nextDueAt` value, null when it is not a date. */
fun RecurringGroceryItem.nextDueLabel(): String? =
    nextDueAt?.let { runCatching { LocalDate.parse(it.take(10)).format(NEXT_DUE_FORMAT) }.getOrNull() }

/** The choice as the owner reads it: « Toutes les semaines », « Toutes les 2 semaines », « Tous les mois ». */
fun RecurringFrequency.label(): String = when (this) {
    RecurringFrequency.WEEKLY -> "Toutes les semaines"
    RecurringFrequency.BIWEEKLY -> "Toutes les 2 semaines"
    RecurringFrequency.MONTHLY -> "Tous les mois"
}

/** The API's spelling, as sent in a request body. */
fun RecurringFrequency.apiValue(): String = name.lowercase()
