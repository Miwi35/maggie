package com.maggie.app.ui.screens.cookbook.meals

import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.MealGroceryIngredient
import com.maggie.app.data.model.MealGroceryOnList
import com.maggie.app.data.model.MealGroceryOtherMeal
import com.maggie.app.data.model.MealGroceryPackaging
import com.maggie.app.data.model.MealGroceryToBuy
import com.maggie.app.data.model.MealGroceryWithThisMeal
import com.maggie.app.data.model.Need
import com.maggie.app.data.model.alreadyPlannedLabel
import com.maggie.app.data.model.packagesTotalLabel
import com.maggie.app.data.model.shortFrenchWeekday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The two lines under an ingredient of the choice: what is already planned, and the packagings (MAG-380). */
class MealIngredientChoiceLabelsTest {

    private val pasta = MealGroceryIngredient(
        ingredientId = "pasta",
        name = "Pâtes",
        quantity = 500f,
        unit = CookbookUnit.G,
        packaging = MealGroceryPackaging(CookbookUnit.PACK, 500f, CookbookUnit.G),
        toBuy = MealGroceryToBuy(1f, CookbookUnit.PACK),
    )

    private val bolognese = MealGroceryOtherMeal("m0", "Bolognaise", "2026-10-15", 300.0, "g")

    @Test
    fun `a product another meal already needs says how much and which meal`() {
        val line = pasta.copy(onList = MealGroceryOnList(listOf(Need(300.0, "g")), 1), otherMeals = listOf(bolognese))

        assertEquals("déjà prévu : 300 g (Bolognaise, jeu.)", line.alreadyPlannedLabel())
    }

    @Test
    fun `several meals are listed with their day`() {
        val line = pasta.copy(
            onList = MealGroceryOnList(listOf(Need(500.0, "g"))),
            otherMeals = listOf(bolognese, MealGroceryOtherMeal("m1", "Carbonara", "2026-10-17", 200.0, "g")),
        )

        assertEquals("déjà prévu : 500 g (Bolognaise, jeu.; Carbonara, sam.)", line.alreadyPlannedLabel())
    }

    @Test
    fun `needs in units that cannot be added are all shown`() {
        val line = pasta.copy(
            onList = MealGroceryOnList(listOf(Need(300.0, "g"), Need(2.0, "piece"))),
            otherMeals = listOf(bolognese),
        )

        assertEquals("déjà prévu : 300 g + 2 pièces (Bolognaise, jeu.)", line.alreadyPlannedLabel())
    }

    @Test
    fun `nothing is said about a product no other meal needs`() {
        assertNull(pasta.alreadyPlannedLabel())
        assertNull(pasta.copy(onList = MealGroceryOnList()).alreadyPlannedLabel())
    }

    @Test
    fun `a date that is not a day leaves the meal without a weekday`() {
        val line = pasta.copy(otherMeals = listOf(bolognese.copy(date = "n'importe quoi")))

        assertNull(shortFrenchWeekday("n'importe quoi"))
        assertEquals("déjà prévu : 300 g (Bolognaise)", line.alreadyPlannedLabel())
    }

    @Test
    fun `every weekday has its short French name`() {
        assertEquals(
            listOf("lun.", "mar.", "mer.", "jeu.", "ven.", "sam.", "dim."),
            (12..18).map { shortFrenchWeekday("2026-10-$it") },
        )
    }

    @Test
    fun `the packagings once this meal is added are counted, with what it adds`() {
        val line = pasta.copy(withThisMeal = MealGroceryWithThisMeal(listOf(Need(800.0, "g")), packagesTotal = 2, packagesToAdd = 1))

        assertEquals("2 paquets au total (+1)", line.packagesTotalLabel())
    }

    @Test
    fun `a meal that adds no packaging shows the total alone`() {
        val line = pasta.copy(withThisMeal = MealGroceryWithThisMeal(listOf(Need(200.0, "g")), packagesTotal = 1, packagesToAdd = 0))

        assertEquals("1 paquet au total", line.packagesTotalLabel())
    }

    @Test
    fun `a product without packaging has no packagings to count`() {
        assertNull(pasta.copy(withThisMeal = MealGroceryWithThisMeal(listOf(Need(200.0, "g")))).packagesTotalLabel())
        assertNull(pasta.packagesTotalLabel())
    }
}
