package com.maggie.app.ui.screens.cookbook.cooking

import com.maggie.app.data.model.AisleSummary
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.GroceryList
import com.maggie.app.data.model.Need
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.Recipe
import com.maggie.app.data.model.RecipeIngredient
import com.maggie.app.data.model.Store
import com.maggie.app.ui.screens.cookbook.grocery.buildStoreGroups
import java.time.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shopping list seen from the Cuisine activity (MAG-583): what is of the meal, in which order, and the cart's figure. */
class CookingGroceryTest {

    private val today = LocalDate.of(2026, 10, 26)
    private val carrefour = Store(id = "s-1", name = "Carrefour", visitOrder = 1)
    private val biocoop = Store(id = "s-2", name = "Biocoop", visitOrder = 2)

    private fun product(id: String, name: String = id) = Product(id = id, name = name, category = ProductCategory.DAIRY)

    private fun packed(id: String) = Product(
        id = id,
        name = id,
        category = ProductCategory.GRAIN,
        packagingUnit = CookbookUnit.PACK,
        packagingSize = 500f,
        packagingSizeUnit = CookbookUnit.G,
    )

    private fun item(
        id: String,
        productId: String? = null,
        store: Store? = carrefour,
        quantity: Float? = null,
        unit: CookbookUnit? = null,
        checked: Boolean = false,
        position: Int = 0,
        aisle: AisleSummary? = null,
        buyAfter: String? = null,
        product: Product? = productId?.let(::product),
    ) = GroceryItem(
        id = id,
        label = id,
        product = product,
        quantity = quantity,
        unit = unit,
        checked = checked,
        store = store,
        position = position,
        aisle = aisle,
        buyAfter = buyAfter,
    )

    private fun line(productId: String?, quantity: Float, unit: CookbookUnit) = RecipeIngredient(
        ingredient = productId?.let { JsonObject(mapOf("id" to JsonPrimitive(it))) },
        quantity = quantity,
        unit = unit,
    )

    private fun recipe(vararg lines: RecipeIngredient) = Recipe(id = "r", name = "Recette", ingredients = lines.toList())

    private fun stores(items: List<GroceryItem>, needs: Map<String, List<Need>>) =
        cookingGroceryStores(buildStoreGroups(GroceryList("l", items), today), needs)

    @Test
    fun `the needs of a meal add up the lines of its recipes by product, in base units`() {
        val needs = mealNeeds(
            listOf(
                recipe(line("parmesan", 100f, CookbookUnit.G), line("lait", 0.5f, CookbookUnit.L)),
                recipe(line("parmesan", 0.2f, CookbookUnit.KG), line(null, 1f, CookbookUnit.PIECE)),
            ),
        )

        assertEquals(setOf("parmesan", "lait"), needs.keys)
        assertEquals(listOf(Need(300.0, "g")), needs["parmesan"])
        assertEquals(listOf(Need(500.0, "ml")), needs["lait"])
    }

    @Test
    fun `two guests and four guests give two needs, since the recipes arrive already scaled`() {
        val forTwo = mealNeeds(listOf(recipe(line("parmesan", 60f, CookbookUnit.G))))
        val forFour = mealNeeds(listOf(recipe(line("parmesan", 120f, CookbookUnit.G))))

        assertEquals("60 g", stores(listOf(item("Parmesan", "parmesan")), forTwo).single().meal.single().needed)
        assertEquals("120 g", stores(listOf(item("Parmesan", "parmesan")), forFour).single().meal.single().needed)
    }

    @Test
    fun `the items of the meal come first in their store, the others keep the list's order`() {
        val items = listOf(
            item("Pain", "pain", position = 0),
            item("Sel", position = 1),
            item("Parmesan", "parmesan", position = 2),
            item("Lait", "lait", position = 3),
            item("Avoine", "avoine", store = biocoop, position = 0),
        )
        val result = stores(items, mealNeeds(listOf(recipe(line("parmesan", 100f, CookbookUnit.G), line("lait", 1f, CookbookUnit.L)))))

        assertEquals(listOf("Carrefour", "Biocoop"), result.map { it.store?.name })
        assertEquals(listOf("Parmesan", "Lait"), result[0].meal.map { it.item.label })
        assertEquals(listOf("Pain", "Sel"), result[0].others.flatMap { it.items }.map { it.label })
        assertTrue(result[1].meal.isEmpty())
        assertEquals(listOf("Avoine"), result[1].others.flatMap { it.items }.map { it.label })
    }

    @Test
    fun `the others stay under their aisle headers, the meal's items above them`() {
        val dairy = AisleSummary("a-1", "Crèmerie", 1)
        val grocery = AisleSummary("a-2", "Épicerie", 2)
        val items = listOf(
            item("Sucre", "sucre", aisle = grocery),
            item("Beurre", "beurre", aisle = dairy),
            item("Parmesan", "parmesan", aisle = dairy),
        )
        val store = stores(items, mealNeeds(listOf(recipe(line("parmesan", 100f, CookbookUnit.G))))).single()

        assertEquals(listOf("Parmesan"), store.meal.map { it.item.label })
        assertFalse(store.flat)
        assertEquals(listOf("Crèmerie", "Épicerie"), store.others.map { it.aisle?.name })
    }

    @Test
    fun `a store whose items are all of the meal has nothing under it, one with none keeps them flat`() {
        val onlyMeal = stores(listOf(item("Parmesan", "parmesan")), mapOf("parmesan" to listOf(Need(100.0, "g")))).single()
        assertEquals(1, onlyMeal.meal.size)
        assertTrue(onlyMeal.others.isEmpty())

        val none = stores(listOf(item("Sel")), emptyMap()).single()
        assertTrue(none.meal.isEmpty())
        assertTrue(none.flat)
    }

    @Test
    fun `an item without a product is never of the meal`() {
        val result = stores(listOf(item("Parmesan")), mapOf("parmesan" to listOf(Need(100.0, "g")))).single()

        assertTrue(result.meal.isEmpty())
    }

    @Test
    fun `the mention says what is missing when the list plans less than the meal needs, in the same unit`() {
        val parmesan = item("Parmesan", "parmesan", quantity = 200f, unit = CookbookUnit.G)
        val line = stores(listOf(parmesan), mapOf("parmesan" to listOf(Need(500.0, "g")))).single().meal.single()

        assertEquals("500 g", line.needed)
        assertEquals("500 g", line.shortBy)
    }

    @Test
    fun `no mention when the list plans enough, another unit, or no quantity`() {
        fun short(item: GroceryItem) = stores(listOf(item), mapOf("parmesan" to listOf(Need(500.0, "g")))).single().meal.single().shortBy

        assertNull(short(item("Parmesan", "parmesan", quantity = 500f, unit = CookbookUnit.G)))
        assertNull(short(item("Parmesan", "parmesan", quantity = 1f, unit = CookbookUnit.KG)))
        assertNull(short(item("Parmesan", "parmesan", quantity = 2f, unit = CookbookUnit.PIECE)))
        assertNull(short(item("Parmesan", "parmesan")))
    }

    @Test
    fun `a packaged item plans its packagings times their content`() {
        fun short(packs: Float) = stores(
            listOf(item("Riz", quantity = packs, unit = CookbookUnit.PACK, product = packed("riz"))),
            mapOf("riz" to listOf(Need(800.0, "g"))),
        ).single().meal.single().shortBy

        assertEquals("800 g", short(1f))
        assertNull(short(2f))
    }

    @Test
    fun `a need above a kilo reads in kilos`() {
        val line = stores(listOf(item("Farine", "farine")), mapOf("farine" to listOf(Need(1500.0, "g")))).single().meal.single()

        assertEquals("1,5 kg", line.needed)
    }

    @Test
    fun `the cart counts what is left to buy, without the checked and the put off`() {
        val tomorrow = today.plusDays(1).toString()
        val groups = buildStoreGroups(
            GroceryList(
                "l",
                listOf(
                    item("a"),
                    item("b", checked = true),
                    item("c", store = biocoop),
                    item("d", buyAfter = tomorrow),
                    item("e", store = null),
                ),
            ),
            today,
        )

        assertEquals(3, remainingCount(groups))
    }

    @Test
    fun `the list opens as a sheet on a phone held upright, as a panel on a tablet or held sideways`() {
        assertEquals(GroceryPresentation.SHEET, groceryPresentation(smallestWidthDp = 411, landscape = false))
        assertEquals(GroceryPresentation.PANEL, groceryPresentation(smallestWidthDp = 411, landscape = true))
        assertEquals(GroceryPresentation.PANEL, groceryPresentation(smallestWidthDp = 800, landscape = false))
    }
}
