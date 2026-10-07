package com.maggie.app.ui.screens.cookbook.recipes

import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Recipe
import com.maggie.app.data.model.RecipeIngredient

internal fun pastaLine(quantity: Float) = RecipeIngredient(
    id = "line-1",
    ingredientName = "Pâtes",
    ciqualAlimCode = "9810",
    quantity = quantity,
    unit = CookbookUnit.G,
)

internal fun carbonara(quantity: Float = 200f) = Recipe(
    id = "recipe-1",
    name = "Carbonara",
    servings = 4,
    tags = listOf("pâtes"),
    notes = "Sans crème",
    ingredients = listOf(pastaLine(quantity)),
)

/** A Mercure message as the API publishes it: the recipe's IRI plus the parts that changed. */
internal fun published(fields: String, id: String = "recipe-1") = """{"@id":"/api/recipes/$id",$fields}"""

internal fun publishedLine(quantity: Number) =
    """"ingredients":[{"id":"line-1","ingredient":{"@id":"/api/ingredients/i-1","id":"i-1","name":"Pâtes","ciqualAlimCode":"9810"},""" +
        """"ingredientName":"Pâtes","ciqualAlimCode":"9810","quantity":$quantity,"unit":"g"}]"""
