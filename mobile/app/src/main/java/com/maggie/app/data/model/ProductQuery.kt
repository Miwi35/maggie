package com.maggie.app.data.model

/** What the products list is sorted by: the API's `order[name]`, `order[aisle]` and `order[stockState]` (MAG-604). */
enum class ProductSort(val label: String) {
    NAME("Nom"),
    AISLE("Rayon"),
    STOCK("Stock"),
}

/** The aisle filter of a store: any aisle, none at all (« Sans rayon »), or one aisle by id. */
sealed interface AisleFilter {
    data object All : AisleFilter
    data object None : AisleFilter
    data class One(val id: String) : AisleFilter
}

/**
 * The filters and sort the API applies to the products (MAG-604). An aisle belongs to a store and the
 * aisle order is a store's way through it, so [aisle] and [ProductSort.AISLE] mean nothing without a [storeId].
 */
data class ProductQuery(
    val storeId: String? = null,
    val aisle: AisleFilter = AisleFilter.All,
    val sort: ProductSort = ProductSort.NAME,
) {
    val isFiltered: Boolean get() = storeId != null
}
