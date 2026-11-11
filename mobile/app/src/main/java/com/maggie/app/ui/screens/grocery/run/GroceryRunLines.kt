package com.maggie.app.ui.screens.grocery.run

import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.GroceryRecipeNeed
import com.maggie.app.data.model.frenchLabel
import com.maggie.app.ui.screens.cookbook.grocery.formatQuantity
import com.maggie.app.ui.screens.cookbook.grocery.unitLabel
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** One article of the store being done (MAG-763). */
data class RunLine(
    val id: String,
    val label: String,
    /** « 2 paquets de 500 g »; null when no quantity was asked. */
    val quantity: String?,
    val checked: Boolean,
    /** « ce soir · bœuf bourguignon », shown as « pour : … »; null when no meal uses the article. */
    val forMeal: String?,
    /** How the article shows once marked « Manquant » (MAG-764); null when it is not. */
    val missing: RunMissing? = null,
) {
    /** Missing in this store, the store it was planned in: the mockup's orange « – » box and tinted line. */
    val missingHere: Boolean get() = !checked && (missing == RunMissing.NoFallback || missing is RunMissing.GoesTo)

    /** Nothing left to do with it in this store: taken, or missing here. */
    val done: Boolean get() = checked || missingHere
}

/** A missing article (MAG-764), as the store it shows in reads it. */
sealed interface RunMissing {
    /** It moved here, its fallback further in the tour: « manquant chez [store] ». */
    data class MovedFrom(val store: String) : RunMissing

    /** Seen from the store it was missing in, its fallback further in the tour: « manquant · ira à [store] ». */
    data class GoesTo(val store: String) : RunMissing

    /** No fallback further in the tour: it stays in its store, « manquant · pas de repli ». */
    data object NoFallback : RunMissing
}

/** The store an article is taken from today, and how it shows when missing. */
data class RunPlace(val storeId: String, val missing: RunMissing?)

/**
 * Where the article stands in today's [tour]: in its store, or — [missing] — in its product's fallback store when
 * that one comes later in the tour, else still in its store, « pas de repli ». The API has already moved a missing
 * article to its fallback (`store`, its own store in `missingFromStore`); one marked offline still waits for it,
 * so its product's fallback is read instead. Null for an article without a store.
 */
fun GroceryItem.runPlace(missing: Boolean, tour: List<String>): RunPlace? {
    val origin = missingFromStore ?: store ?: return null
    if (!missing) return RunPlace(origin.id, null)
    val fallback = if (missingAt != null) store?.id?.takeIf { it != origin.id } else product?.fallbackStore?.substringAfterLast('/')
    val to = fallback?.let(tour::indexOf) ?: -1
    return if (to >= 0 && to > tour.indexOf(origin.id)) {
        RunPlace(fallback!!, RunMissing.MovedFrom(origin.name))
    } else {
        RunPlace(origin.id, RunMissing.NoFallback)
    }
}

/** An aisle of the store and its articles; [name] is null for « Sans rayon ». */
data class RunAisle(val id: String?, val name: String?, val lines: List<RunLine>)

/** Articles taken out of the articles of the store, « n / m ». */
data class RunProgress(val checked: Int, val total: Int)

/** « 2 paquets de 500 g », « 3 pièces », « 800 g »: what to take, counted in the packaging of a packaged product. */
fun GroceryItem.runQuantity(): String? {
    val quantity = quantity ?: return null
    val product = product
    val packaging = product?.packagingUnit
    if (packaging == null) {
        val unitText = unitLabel(unit, quantity)
        return formatQuantity(quantity) + if (unitText.isEmpty()) "" else " $unitText"
    }
    val size = product.packagingSize
    val sizeUnit = product.packagingSizeUnit
    val counted = "${formatQuantity(quantity)} ${unitLabel(packaging, quantity)}"
    return if (size == null || sizeUnit == null) counted else "$counted de ${formatQuantity(size)} ${sizeUnit.frenchLabel(size)}"
}

/** « ce soir · bœuf bourguignon », « demain midi · … », « samedi soir · … ». */
fun GroceryRecipeNeed.runTag(today: LocalDate): String {
    val day = runCatching { LocalDate.parse(date) }.getOrNull()
    val slotText = if (slot == "dinner") "soir" else "midi"
    val moment = when (day) {
        null, today -> "ce $slotText"
        today.plusDays(1) -> "demain $slotText"
        else -> "${day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.FRANCE)} $slotText"
    }
    return if (mealName.isEmpty()) moment else "$moment · ${mealName.replaceFirstChar { it.lowercase() }}"
}

/** The tag of each product, from the soonest meal that uses it (the API answers soonest first). */
fun List<GroceryRecipeNeed>.runTags(today: LocalDate): Map<String, String> {
    val tags = LinkedHashMap<String, String>()
    forEach { tags.putIfAbsent(it.productId, it.runTag(today)) }
    return tags
}

/**
 * The articles of one store, aisle by aisle in the order of the store, « Sans rayon » last; inside an aisle the
 * ones still to take come first and the ticked ones — and the missing ones with no fallback — sink, in their place order.
 */
fun runAisles(
    items: List<GroceryItem>,
    isChecked: (GroceryItem) -> Boolean,
    tags: Map<String, String>,
    missing: (GroceryItem) -> RunMissing? = { null },
): List<RunAisle> = items
    .filter { it.id != null }
    .groupBy { it.aisle?.id }
    .values
    .sortedWith(compareBy({ it.first().aisle == null }, { it.first().aisle?.position ?: 0 }, { it.first().aisle?.name.orEmpty() }))
    .map { lines ->
        val aisle = lines.first().aisle
        RunAisle(
            id = aisle?.id,
            name = aisle?.name,
            lines = lines
                .map {
                    RunLine(
                        id = it.id.orEmpty(),
                        label = it.label,
                        quantity = it.runQuantity(),
                        checked = isChecked(it),
                        forMeal = it.product?.id?.let(tags::get),
                        missing = missing(it),
                    ) to it.position
                }
                .sortedWith(compareBy({ it.first.done }, { it.second }))
                .map { it.first },
        )
    }
