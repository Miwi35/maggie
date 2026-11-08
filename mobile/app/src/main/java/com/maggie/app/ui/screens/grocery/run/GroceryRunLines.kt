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
)

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
 * ones still to take come first and the ticked ones sink, in their place order.
 */
fun runAisles(
    items: List<GroceryItem>,
    isChecked: (GroceryItem) -> Boolean,
    tags: Map<String, String>,
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
                .sortedWith(compareBy({ isChecked(it) }, { it.position }))
                .map {
                    RunLine(
                        id = it.id.orEmpty(),
                        label = it.label,
                        quantity = it.runQuantity(),
                        checked = isChecked(it),
                        forMeal = it.product?.id?.let(tags::get),
                    )
                },
        )
    }
