package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Product(
    val id: String,
    val name: String,
    val defaultUnit: CookbookUnit? = null,
    val category: ProductCategory,
    val kcalPer100g: Float? = null,
    val proteinPer100g: Float? = null,
    val carbsPer100g: Float? = null,
    val fatPer100g: Float? = null,
    val preferredStore: String? = null,
    val fallbackStore: String? = null,
    val shelfLifeDays: Int? = null,
    // What the product is bought in: « paquet de 500 g » is (PACK, 500, G).
    val packagingUnit: CookbookUnit? = null,
    val packagingSize: Float? = null,
    val packagingSizeUnit: CookbookUnit? = null,
    // What is left at home; restockQuantity is in packagings (rice: 2 packs).
    val stockState: ProductStockState = ProductStockState.IN_STOCK,
    val restockQuantity: Int? = null,
    val autoRestock: Boolean = false,
)
