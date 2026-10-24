package com.maggie.app.ui.screens.grocery

import com.maggie.app.data.model.Aisle
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductAisle
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.ProductStockState
import com.maggie.app.data.model.Store
import com.maggie.app.data.model.formatQuantity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/** The two shops a product carries: the usual one and the one to fall back on. */
enum class StoreSlot { PREFERRED, FALLBACK }

/**
 * The aisle chosen in a shop: [value] is the id of one of its aisles, or — when [isNew] — the
 * name of one to create, which the API files last in that shop.
 */
data class AisleChoice(val value: String, val isNew: Boolean = false)

/**
 * What the product sheet holds while the owner types (MAG-605). Stores are bare ids, an aisle
 * only means something with its store: changing the store empties it. Numbers stay text until
 * they are saved, so « 0, » is not rounded under the finger.
 */
data class ProductDraft(
    val name: String,
    val category: ProductCategory,
    val defaultUnit: CookbookUnit?,
    val ciqualAlimCode: String?,
    val packagingUnit: CookbookUnit?,
    val packagingSize: String,
    val packagingSizeUnit: CookbookUnit?,
    val stockState: ProductStockState,
    val restockQuantity: String,
    val autoRestock: Boolean,
    val preferredStoreId: String?,
    val preferredAisle: AisleChoice?,
    val fallbackStoreId: String?,
    val fallbackAisle: AisleChoice?,
    val seasonMonths: List<Int>,
) {
    fun storeId(slot: StoreSlot): String? = if (slot == StoreSlot.PREFERRED) preferredStoreId else fallbackStoreId

    fun aisle(slot: StoreSlot): AisleChoice? = if (slot == StoreSlot.PREFERRED) preferredAisle else fallbackAisle

    /** Another shop empties the aisle of the slot; the same shop keeps it. */
    fun withStore(slot: StoreSlot, storeId: String?): ProductDraft {
        if (storeId == this.storeId(slot)) return this
        return if (slot == StoreSlot.PREFERRED) {
            copy(preferredStoreId = storeId, preferredAisle = null)
        } else {
            copy(fallbackStoreId = storeId, fallbackAisle = null)
        }
    }

    fun withAisle(slot: StoreSlot, choice: AisleChoice?): ProductDraft =
        if (slot == StoreSlot.PREFERRED) copy(preferredAisle = choice) else copy(fallbackAisle = choice)

    /** A packaging unit is what gives a size its meaning: without one, no size is kept. */
    fun withPackagingUnit(unit: CookbookUnit?): ProductDraft =
        if (unit == null) copy(packagingUnit = null, packagingSize = "", packagingSizeUnit = null) else copy(packagingUnit = unit)

    /** The sentence to show when the sheet cannot be saved as it is, null when it can. */
    fun problem(): String? = when {
        name.isBlank() -> "Le nom du produit est obligatoire."
        packagingSize.isNotBlank() && packagingSize.toDecimalOrNull() == null -> "La taille du conditionnement n'est pas un nombre."
        restockQuantity.isNotBlank() && restockQuantity.trim().toIntOrNull() == null -> "La quantité de réassort n'est pas un nombre entier."
        else -> null
    }

    companion object {
        fun from(product: Product): ProductDraft {
            val preferred = product.preferredStore?.bareId()
            val fallback = product.fallbackStore?.bareId()
            return ProductDraft(
                name = product.name,
                category = product.category,
                defaultUnit = product.defaultUnit,
                ciqualAlimCode = product.ciqualAlimCode,
                packagingUnit = product.packagingUnit,
                packagingSize = product.packagingSize?.let(::formatQuantity).orEmpty(),
                packagingSizeUnit = product.packagingSizeUnit,
                stockState = product.stockState,
                restockQuantity = product.restockQuantity?.toString().orEmpty(),
                autoRestock = product.autoRestock,
                preferredStoreId = preferred,
                preferredAisle = product.aisleChoiceIn(preferred),
                fallbackStoreId = fallback,
                fallbackAisle = product.aisleChoiceIn(fallback),
                seasonMonths = product.seasonMonths.orEmpty().sorted(),
            )
        }
    }
}

/** `/api/stores/01H…` → `01H…`: the form the aisles of a product speak. */
internal fun String.bareId(): String = substringAfterLast('/')

internal fun storeIri(storeId: String): String = "/api/stores/$storeId"

private fun Product.aisleChoiceIn(storeId: String?): AisleChoice? {
    storeId ?: return null
    return aisles.firstOrNull { it.store == storeId }?.aisle?.let { AisleChoice(it) }
}

/** « 1,5 » and « 1.5 » both: the French keyboard types a comma. */
private fun String.toDecimalOrNull(): Float? = trim().replace(',', '.').toFloatOrNull()

private inline fun <reified T : Any> encode(value: T?): JsonElement =
    if (value == null) JsonNull else Json.encodeToJsonElement(value)

/**
 * The merge-patch that turns [original] into this draft: only the fields that changed, in the
 * API's own spelling, a store as its IRI or `null`. The aisles go as one list replaced whole,
 * so the ones in shops the sheet does not touch are carried over.
 */
fun ProductDraft.toPatch(original: Product): JsonObject = buildJsonObject {
    val before = ProductDraft.from(original)
    if (name.trim() != before.name) put("name", name.trim())
    if (category != before.category) put("category", encode(category))
    if (defaultUnit != before.defaultUnit) put("defaultUnit", encode(defaultUnit))
    if (ciqualAlimCode != before.ciqualAlimCode) put("ciqualAlimCode", ciqualAlimCode)
    if (packagingUnit != before.packagingUnit) put("packagingUnit", encode(packagingUnit))
    if (packagingSize.trim() != before.packagingSize) put("packagingSize", packagingSize.toDecimalOrNull())
    if (packagingSizeUnit != before.packagingSizeUnit) put("packagingSizeUnit", encode(packagingSizeUnit))
    if (stockState != before.stockState) put("stockState", encode(stockState))
    val restock = restockQuantity.trim().toIntOrNull()
    if (restock != original.restockQuantity) put("restockQuantity", restock)
    if (autoRestock != before.autoRestock) put("autoRestock", autoRestock)
    if (preferredStoreId != before.preferredStoreId) put("preferredStore", preferredStoreId?.let(::storeIri))
    if (fallbackStoreId != before.fallbackStoreId) put("fallbackStore", fallbackStoreId?.let(::storeIri))
    if (seasonMonths.sorted() != before.seasonMonths) {
        put("seasonMonths", JsonArray(seasonMonths.sorted().map { JsonPrimitive(it) }))
    }
    aislesToSend(original)?.let { aisles ->
        put(
            "aisles",
            JsonArray(
                aisles.map {
                    buildJsonObject {
                        put("store", it.store)
                        put("aisle", it.aisle)
                    }
                },
            ),
        )
    }
}

/**
 * The product's whole aisle list once this draft is saved, or null when it does not change.
 * A shop the draft no longer names keeps what it had; a shop it names has the draft's aisle,
 * or none.
 */
internal fun ProductDraft.aislesToSend(original: Product): List<ProductAisle>? {
    val named = listOfNotNull(preferredStoreId, fallbackStoreId)
    val kept = original.aisles.filter { it.store !in named && it.aisle != null }
    val chosen = listOfNotNull(
        preferredStoreId?.let { store -> preferredAisle?.let { ProductAisle(store, it.value) } },
        fallbackStoreId?.let { store -> fallbackAisle?.let { ProductAisle(store, it.value) } },
    )
    val after = kept + chosen
    val before = original.aisles.filter { it.aisle != null }.toSet()
    val creates = listOfNotNull(preferredAisle, fallbackAisle).any { it.isNew }
    return if (creates || after.toSet() != before) after else null
}

/** The store IRIs of a product's list line: « Super U · Épicerie », the aisle only when it has one. */
internal fun storeAisleLabel(
    storeIri: String?,
    product: Product,
    stores: List<Store>,
    aislesByStore: Map<String, List<Aisle>>,
): String? {
    val storeId = storeIri?.bareId() ?: return null
    val store = stores.firstOrNull { it.id == storeId } ?: return null
    val aisleId = product.aisles.firstOrNull { it.store == storeId }?.aisle
    val aisle = aisleId?.let { id -> aislesByStore[storeId].orEmpty().firstOrNull { it.id == id }?.name }
    return if (aisle != null) "${store.name} · $aisle" else store.name
}
