package com.maggie.app.ui.screens.grocery.run

import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.GroceryRecipeNeed
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class GroceryRunLinesTest {

    private val today = LocalDate.of(2026, 11, 8)

    private val rice = Product(
        id = "p", name = "Riz", category = ProductCategory.GRAIN,
        packagingUnit = CookbookUnit.PACK, packagingSize = 500f, packagingSizeUnit = CookbookUnit.G,
    )

    @Test
    fun `a packaged line reads its count and its packaging`() {
        assertEquals("2 paquets de 500 g", GroceryItem(label = "Riz", product = rice, quantity = 2f).runQuantity())
        assertEquals("1 paquet de 500 g", GroceryItem(label = "Riz", product = rice, quantity = 1f).runQuantity())
    }

    @Test
    fun `a line without packaging reads its quantity and unit, and none when nothing was asked`() {
        assertEquals("3 pièces", GroceryItem(label = "Œufs", quantity = 3f, unit = CookbookUnit.PIECE).runQuantity())
        assertEquals("800 g", GroceryItem(label = "Farine", quantity = 800f, unit = CookbookUnit.G).runQuantity())
        assertNull(GroceryItem(label = "Pain").runQuantity())
    }

    @Test
    fun `a meal tag says when and which meal`() {
        fun need(date: String, slot: String) = GroceryRecipeNeed("p", "Lait", date, slot, "m", "Bœuf bourguignon")
        assertEquals("ce soir · bœuf bourguignon", need("2026-11-08", "dinner").runTag(today))
        assertEquals("ce midi · bœuf bourguignon", need("2026-11-08", "lunch").runTag(today))
        assertEquals("demain soir · bœuf bourguignon", need("2026-11-09", "dinner").runTag(today))
        assertEquals("samedi midi · bœuf bourguignon", need("2026-11-14", "lunch").runTag(today))
    }

    private val superU = Store("s-u", "Super U")
    private val biocoop = Store("s-b", "Biocoop")
    private val milk = Product(id = "p-milk", name = "Lait", category = ProductCategory.DAIRY, fallbackStore = "/api/stores/s-b")

    @Test
    fun `an article not missing is taken in its store`() {
        assertEquals(RunPlace("s-u", null), GroceryItem(label = "Lait", store = superU, product = milk).runPlace(false, listOf("s-u", "s-b")))
        assertNull(GroceryItem(label = "Lait").runPlace(true, listOf("s-u")))
    }

    @Test
    fun `a missing article goes to its fallback only when it comes later in the tour`() {
        val line = GroceryItem(label = "Lait", store = superU, product = milk)
        assertEquals(RunPlace("s-b", RunMissing.MovedFrom("Super U")), line.runPlace(true, listOf("s-u", "s-b")))
        assertEquals(RunPlace("s-u", RunMissing.NoFallback), line.runPlace(true, listOf("s-b", "s-u")))
        assertEquals(RunPlace("s-u", RunMissing.NoFallback), line.runPlace(true, listOf("s-u")))
        assertEquals(RunPlace("s-u", RunMissing.NoFallback), GroceryItem(label = "Pain", store = superU).runPlace(true, listOf("s-u", "s-b")))
    }

    @Test
    fun `an article the API moved reads its own store from missingFromStore`() {
        val moved = GroceryItem(label = "Lait", store = biocoop, missingFromStore = superU, missingAt = "2026-11-08T10:00:00+00:00")
        assertEquals(RunPlace("s-b", RunMissing.MovedFrom("Super U")), moved.runPlace(true, listOf("s-u", "s-b")))
        assertEquals(RunPlace("s-u", RunMissing.NoFallback), moved.runPlace(true, listOf("s-u")))
        // « Annuler » not yet received: it is back in its store.
        assertEquals(RunPlace("s-u", null), moved.runPlace(false, listOf("s-u", "s-b")))
    }

    @Test
    fun `a missing article with no fallback sinks with the ticked ones`() {
        val items = listOf(
            GroceryItem(id = "a", label = "Pain", store = superU, position = 1),
            GroceryItem(id = "b", label = "Lait", store = superU, position = 2),
        )
        val lines = runAisles(items, { false }, emptyMap()) { if (it.id == "a") RunMissing.NoFallback else null }.single().lines
        assertEquals(listOf("b", "a"), lines.map { it.id })
        assertEquals(listOf(false, true), lines.map { it.done })
    }
}
