package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A finished errand: what was bought, where and when (MAG-488). « Terminer la course »
 * archives the ticked lines into one per store instead of deleting them.
 */
@Serializable
data class Errand(
    val id: String,
    /** The store's name when the errand ended; null for lines that had no store. */
    val storeName: String? = null,
    /** ISO 8601 with its offset: `2026-10-02T18:00:00+02:00`. */
    val endedAt: String,
    val itemCount: Int = 0,
    val items: List<PurchasedItem> = emptyList(),
)

/** A line as it was bought: the product's packaging is copied, so it still reads right once the product changed. */
@Serializable
data class PurchasedItem(
    val label: String = "",
    val quantity: Float? = null,
    val unit: CookbookUnit? = null,
    val packagingUnit: CookbookUnit? = null,
    val packagingSize: Float? = null,
    val packagingSizeUnit: CookbookUnit? = null,
)

const val NO_STORE_LABEL = "Sans magasin"

/** The store as the owner reads it, « Sans magasin » for the lines that had none. */
val Errand.storeLabel: String get() = storeName?.takeIf { it.isNotBlank() } ?: NO_STORE_LABEL

/** When the errand ended, as an instant to sort on; null when the API sent something else. */
fun Errand.endedAtOrNull(): OffsetDateTime? = runCatching { OffsetDateTime.parse(endedAt) }.getOrNull()

private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRANCE)
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.FRANCE)

/** « Jeudi 2 octobre 2026 · 18:00 », in the phone's time zone; the raw value when it is no date. */
fun Errand.endedAtLabel(zone: ZoneId = ZoneId.systemDefault()): String {
    val at = endedAtOrNull()?.atZoneSameInstant(zone) ?: return endedAt
    val day = at.format(DAY_FORMAT).replaceFirstChar { it.titlecase(Locale.FRANCE) }
    return "$day · ${at.format(TIME_FORMAT)}"
}

/** « 1 article », « 3 articles ». */
fun itemCountLabel(count: Int): String = if (count > 1) "$count articles" else "$count article"
