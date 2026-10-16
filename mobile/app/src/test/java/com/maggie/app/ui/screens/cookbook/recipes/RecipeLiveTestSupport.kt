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

internal fun step(
    position: Int,
    text: String,
    minutes: Int? = null,
    background: Boolean = false,
    waitsFor: List<Int> = emptyList(),
    ingredients: List<com.maggie.app.data.model.StepIngredient> = emptyList(),
    utensils: List<com.maggie.app.data.model.Utensil> = emptyList(),
) = com.maggie.app.data.model.RecipeStep(
    id = "step-$position",
    position = position,
    text = text,
    durationMinutes = minutes,
    background = background,
    ingredients = ingredients,
    utensils = utensils,
    waitsFor = waitsFor,
)

/** The reference example of the steps spec: water on, vegetables, pasta (waits 1), sauté, drain (waits 3 and 4). */
internal fun pastaDinner() = Recipe(
    id = "recipe-1",
    name = "Pâtes aux légumes",
    servings = 4,
    ingredients = listOf(
        pastaLine(200f).copy(ingredient = kotlinx.serialization.json.JsonPrimitive("/api/ingredients/i-pasta")),
        pastaLine(300f).copy(
            id = "line-2",
            ingredientName = "Légumes",
            ciqualAlimCode = "20000",
            ingredient = kotlinx.serialization.json.JsonPrimitive("/api/ingredients/i-veg"),
        ),
    ),
    utensils = listOf(com.maggie.app.data.model.Utensil("u-pot", "Casserole")),
    steps = listOf(
        step(1, "Mettre l'eau à bouillir", 10, background = true, utensils = listOf(com.maggie.app.data.model.Utensil("u-pot", "Casserole"))),
        step(2, "Couper les légumes", ingredients = listOf(com.maggie.app.data.model.StepIngredient("i-veg", "Légumes"))),
        step(3, "Cuire les pâtes", 12, background = true, waitsFor = listOf(1), ingredients = listOf(com.maggie.app.data.model.StepIngredient("i-pasta", "Pâtes"))),
        step(4, "Faire revenir les légumes", 8),
        step(5, "Égoutter, mélanger", waitsFor = listOf(3, 4)),
    ),
)
