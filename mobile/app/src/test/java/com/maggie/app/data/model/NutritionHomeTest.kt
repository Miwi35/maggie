package com.maggie.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The `/api/home/nutrition` answer as the Nutrition card reads it (MAG-564). */
class NutritionHomeTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val answer = """
        {"module":"nutrition","day":"today","date":"2026-10-14","slots":[
          {"slot":"lunch","meals":[]},
          {"slot":"dinner","meals":[
            {"id":"m1","title":"Salade verte","guests":2,
             "recipes":[{"id":"r1","name":"Salade verte"},{"id":"r2","name":"Tarte"},{"id":"r3","name":"Fruits"}],
             "missing":[{"id":"i1","name":"Parmesan"},{"id":"i2","name":"Basilic"}]},
            {"id":"m2","title":"Restes","guests":null,"recipes":[],"missing":[]}]}]}
    """.trimIndent()

    @Test
    fun `the answer decodes, an empty slot having no meal`() {
        val state = json.decodeFromString(NutritionHomeState.serializer(), answer)

        assertEquals("today", state.day)
        assertEquals(listOf("lunch", "dinner"), state.slots.map { it.slot })
        assertEquals(emptyList<NutritionHomeMeal>(), state.slots[0].meals)
        val meal = state.slots[1].meals[0]
        assertEquals(2, meal.guests)
        assertEquals(3, meal.recipes.size)
        assertEquals(listOf("Parmesan", "Basilic"), meal.missing.map { it.name })
        assertNull(state.slots[1].meals[1].guests)
    }

    @Test
    fun `all the recipes of a meal are on one line, the title when it has none`() {
        val meals = json.decodeFromString(NutritionHomeState.serializer(), answer).slots[1].meals

        assertEquals("Salade verte · Tarte · Fruits", meals[0].recipesLabel())
        assertEquals("Restes", meals[1].recipesLabel())
    }

    @Test
    fun `what is missing reads in lower case, and is null when nothing is`() {
        val meals = json.decodeFromString(NutritionHomeState.serializer(), answer).slots[1].meals

        assertEquals("manque : parmesan, basilic", meals[0].missingLabel())
        assertNull(meals[1].missingLabel())
    }

    @Test
    fun `the slots are named in French`() {
        assertEquals("Déjeuner", NutritionHomeSlot("lunch").label())
        assertEquals("Dîner", NutritionHomeSlot("dinner").label())
    }
}
