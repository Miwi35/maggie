package com.maggie.app.ui.screens.cookbook.grocery

import com.maggie.app.data.model.RecipeNeedMeal
import org.junit.Assert.assertEquals
import org.junit.Test

class RecipeNeedMealTest {

    @Test
    fun `shortLine reads name, short day and quantity`() {
        val meal = RecipeNeedMeal(name = "Riz cantonais", date = "2026-10-28", quantity = 500.0, unit = "g")

        assertEquals("Riz cantonais mer. 500 g", meal.shortLine())
    }

    @Test
    fun `shortLine drops the day when the date is not one`() {
        val meal = RecipeNeedMeal(name = "Riz cantonais", date = "", quantity = 500.0, unit = "g")

        assertEquals("Riz cantonais 500 g", meal.shortLine())
    }
}
