package com.maggie.app.ui.screens.cookbook.recipes

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeFormTest {

    private fun steps(request: JsonObject) = request["steps"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.ints(key: String) = this[key]!!.jsonArray.map { it.jsonPrimitive.int }

    private fun form() = RecipeForm.of(pastaDinner())

    @Test
    fun `the reference example is read as the steps in order, with dependencies as keys`() {
        val form = form()

        assertEquals(listOf(1, 2, 3, 4, 5), form.steps.map { it.key })
        assertEquals("10", form.steps[0].duration)
        assertTrue(form.steps[0].background)
        assertEquals(listOf(1), form.steps[2].waitsFor)
        assertEquals(listOf(3, 4), form.steps[4].waitsFor)
        assertEquals(listOf(UtensilRef("u-pot", "Casserole")), form.utensils)
    }

    @Test
    fun `the reference example is sent back identically`() {
        val request = form().toRequest()
        val sent = steps(request)

        assertEquals(listOf("Mettre l'eau à bouillir", "Couper les légumes", "Cuire les pâtes", "Faire revenir les légumes", "Égoutter, mélanger"), sent.map { it["text"]!!.jsonPrimitive.content })
        assertEquals(10, sent[0]["durationMinutes"]!!.jsonPrimitive.int)
        assertTrue(sent[0]["background"]!!.jsonPrimitive.boolean)
        assertEquals(JsonPrimitive(null as String?), sent[1]["durationMinutes"])
        assertEquals(listOf(1), sent[2].ints("waitsFor"))
        assertEquals(listOf(3, 4), sent[4].ints("waitsFor"))
        assertEquals(JsonArray(listOf(JsonPrimitive("i-pasta"))), sent[2]["ingredientIds"])
        assertEquals("u-pot", sent[0]["utensils"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("u-pot", request["utensils"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a step added gets the next key and no dependency`() {
        val form = form().addStep()

        assertEquals(6, form.steps.last().key)
        assertEquals(emptyList<Int>(), form.steps.last().waitsFor)
    }

    @Test
    fun `a step moved up keeps the others pointing at the steps they waited for`() {
        val form = form().moveStep(key = 2, delta = -1)

        assertEquals(listOf(2, 1, 3, 4, 5), form.steps.map { it.key })
        assertEquals(listOf(1), form.steps[2].waitsFor)
        // Sent by position: « Cuire les pâtes » is still third and waits for the step now second.
        assertEquals(listOf(2), steps(form.toRequest())[2].ints("waitsFor"))
        assertEquals(listOf(3, 4), steps(form.toRequest())[4].ints("waitsFor"))
    }

    @Test
    fun `a dependency that becomes a later step after a move is removed`() {
        // « Cuire les pâtes » (3) waits for « Mettre l'eau à bouillir » (1): moving 1 under 3 puts it after.
        val form = form().moveStep(key = 1, delta = 1).moveStep(key = 1, delta = 1)

        assertEquals(listOf(2, 3, 1, 4, 5), form.steps.map { it.key })
        assertEquals(emptyList<Int>(), form.steps.first { it.key == 3 }.waitsFor)
    }

    @Test
    fun `a step cannot move out of the list`() {
        val form = form()

        assertEquals(form, form.moveStep(key = 1, delta = -1))
        assertEquals(form, form.moveStep(key = 5, delta = 1))
    }

    @Test
    fun `a step removed is no longer waited for`() {
        val form = form().removeStep(3)

        assertEquals(listOf(1, 2, 4, 5), form.steps.map { it.key })
        assertEquals(listOf(4), form.steps.first { it.key == 5 }.waitsFor)
        assertEquals(listOf(3), steps(form.toRequest())[3].ints("waitsFor"))
    }

    @Test
    fun `a step waits only for an earlier one`() {
        val form = form()

        assertEquals(form, form.toggleStepDependency(key = 2, waitsForKey = 4))
        assertEquals(listOf(1), form.toggleStepDependency(key = 2, waitsForKey = 1).steps[1].waitsFor)
        assertEquals(emptyList<Int>(), form.toggleStepDependency(key = 3, waitsForKey = 1).steps[2].waitsFor)
    }

    @Test
    fun `without a duration a step is not in the background`() {
        val form = form().setStepDuration(key = 1, value = "")

        assertFalse(form.steps[0].background)
        assertEquals(form, form.setStepBackground(key = 1, background = true))
    }

    @Test
    fun `a duration keeps its digits only`() {
        assertEquals("12", form().setStepDuration(key = 3, value = "1a2 ").steps[2].duration)
    }

    @Test
    fun `an ingredient taken out of the recipe is taken out of its steps`() {
        val form = form()
        val withoutPasta = form.withIngredients(form.ingredients.filter { it.ciqualFoodName != "Pâtes" })

        assertEquals(emptyList<IngredientRef>(), withoutPasta.steps[2].ingredients)
        assertEquals(listOf(IngredientRef("i-veg", "Légumes")), withoutPasta.steps[1].ingredients)
    }

    @Test
    fun `an ingredient not yet saved is sent by its name`() {
        val form = RecipeForm(
            name = "Soupe",
            ingredients = listOf(IngredientRow(ciqualAlimCode = "20000", ciqualFoodName = "Poireau", quantity = "2")),
        ).addStep().let { it.toggleStepIngredient(1, it.ingredientChoices.single()) }

        assertEquals(JsonArray(listOf(JsonPrimitive("Poireau"))), steps(form.toRequest())[0]["ingredientIds"])
    }

    @Test
    fun `an ingredient the recipe does not send is not sent in a step`() {
        val form = RecipeForm(
            name = "Soupe",
            ingredients = listOf(IngredientRow(ciqualAlimCode = "20000", ciqualFoodName = "Poireau", quantity = "")),
        ).addStep().let { it.copy(steps = it.steps.map { s -> s.copy(ingredients = listOf(IngredientRef("", "Poireau"))) }) }

        assertEquals(JsonArray(emptyList()), steps(form.toRequest())[0]["ingredientIds"])
    }

    @Test
    fun `a utensil of a step is a utensil of the recipe, sent by name until the catalogue has it`() {
        val form = RecipeForm(name = "Soupe").addStep().addStepUtensil(1, UtensilRef(name = "Mixeur plongeant"))

        assertEquals(listOf(UtensilRef(name = "Mixeur plongeant")), form.utensils)
        val request = form.toRequest()
        assertEquals("Mixeur plongeant", request["utensils"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        assertNull(steps(request)[0]["utensils"]!!.jsonArray.single().jsonObject["id"])
    }

    @Test
    fun `a utensil taken out of the recipe is taken out of its steps`() {
        val form = form().removeUtensil(UtensilRef("u-pot", "Casserole"))

        assertEquals(emptyList<UtensilRef>(), form.utensils)
        assertEquals(emptyList<UtensilRef>(), form.steps[0].utensils)
    }

    @Test
    fun `a utensil is never in the list twice`() {
        val form = form().addUtensil(UtensilRef(name = "casserole ")).addUtensil(UtensilRef("u-pot", "Casserole"))

        assertEquals(1, form.utensils.size)
    }
}
