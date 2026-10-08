package com.maggie.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CookbookUnit {
    @SerialName("g") G,
    @SerialName("kg") KG,
    @SerialName("ml") ML,
    @SerialName("l") L,
    @SerialName("cl") CL,
    @SerialName("piece") PIECE,
    @SerialName("bunch") BUNCH,
    @SerialName("can") CAN,
    @SerialName("bottle") BOTTLE,
    @SerialName("pack") PACK,
    @SerialName("sachet") SACHET,
    @SerialName("jar") JAR,
}

@Serializable
enum class ProductCategory {
    @SerialName("produce") PRODUCE,
    @SerialName("dairy") DAIRY,
    @SerialName("meat") MEAT,
    @SerialName("fish") FISH,
    @SerialName("grain") GRAIN,
    @SerialName("spice") SPICE,
    @SerialName("condiment") CONDIMENT,
    @SerialName("frozen") FROZEN,
    @SerialName("beverage") BEVERAGE,
    @SerialName("household") HOUSEHOLD,
    @SerialName("hygiene") HYGIENE,
    @SerialName("cleaning") CLEANING,
    @SerialName("other") OTHER,
}

/** What is left of a product at home (MAG-293). */
@Serializable
enum class ProductStockState {
    @SerialName("in_stock") IN_STOCK,
    @SerialName("low") LOW,
    @SerialName("out") OUT,
}

@Serializable
enum class MealSlot {
    @SerialName("lunch") LUNCH,
    @SerialName("dinner") DINNER,
}

@Serializable
enum class GroceryItemSource {
    @SerialName("recipe") RECIPE,
    @SerialName("recurring") RECURRING,
    @SerialName("manual") MANUAL,
}

@Serializable
enum class RecurringFrequency {
    @SerialName("weekly") WEEKLY,
    @SerialName("biweekly") BIWEEKLY,
    @SerialName("monthly") MONTHLY,
}
