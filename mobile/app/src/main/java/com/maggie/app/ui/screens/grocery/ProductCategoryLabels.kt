package com.maggie.app.ui.screens.grocery

import com.maggie.app.data.model.ProductCategory

internal fun ProductCategory.label(): String = when (this) {
    ProductCategory.PRODUCE -> "Fruits et légumes"
    ProductCategory.DAIRY -> "Produits laitiers"
    ProductCategory.MEAT -> "Viande"
    ProductCategory.FISH -> "Poisson"
    ProductCategory.GRAIN -> "Féculents"
    ProductCategory.SPICE -> "Épices"
    ProductCategory.CONDIMENT -> "Condiments"
    ProductCategory.FROZEN -> "Surgelés"
    ProductCategory.BEVERAGE -> "Boissons"
    ProductCategory.HOUSEHOLD -> "Maison"
    ProductCategory.HYGIENE -> "Hygiène"
    ProductCategory.CLEANING -> "Entretien"
    ProductCategory.OTHER -> "Autre"
}
