package com.maggie.app.ui.screens.cookbook.grocery

import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryPickerUtilsTest {

    private val stores = listOf(
        Store(id = "s1", name = "Carrefour", visitOrder = 1),
        Store(id = "s2", name = "Picard", visitOrder = 2),
        Store(id = "s3", name = "Biocoop", visitOrder = 3),
    )

    private val products = listOf(
        Product(
            id = "p1", name = "Pommes", category = ProductCategory.PRODUCE,
            defaultUnit = CookbookUnit.KG, preferredStore = "/api/stores/s1",
        ),
        Product(id = "p2", name = "Pommes de terre", category = ProductCategory.PRODUCE),
        Product(id = "p3", name = "Lait", category = ProductCategory.DAIRY),
        Product(id = "p4", name = "Pain", category = ProductCategory.GRAIN),
    )

    // --- filterProducts ---

    @Test
    fun `filterProducts returns empty for single char query`() {
        assertTrue(filterProducts("P", products).isEmpty())
    }

    @Test
    fun `filterProducts returns empty for empty query`() {
        assertTrue(filterProducts("", products).isEmpty())
    }

    @Test
    fun `filterProducts matches case-insensitive`() {
        val result = filterProducts("pomm", products)
        assertEquals(2, result.size)
        assertTrue(result.any { it.id == "p1" })
        assertTrue(result.any { it.id == "p2" })
    }

    @Test
    fun `filterProducts limits to 10 results`() {
        val manyProducts = (1..20).map {
            Product(id = "p$it", name = "Test Product $it", category = ProductCategory.OTHER)
        }
        assertEquals(10, filterProducts("Test", manyProducts).size)
    }

    @Test
    fun `filterProducts returns empty for no match`() {
        assertTrue(filterProducts("xyz", products).isEmpty())
    }

    @Test
    fun `filterProducts preserves order of original list`() {
        val result = filterProducts("pomm", products)
        assertEquals("p1", result[0].id)
        assertEquals("p2", result[1].id)
    }

    // --- filterStores ---

    @Test
    fun `filterStores returns all for blank query`() {
        assertEquals(stores, filterStores("", stores))
    }

    @Test
    fun `filterStores returns all for whitespace query`() {
        assertEquals(stores, filterStores("   ", stores))
    }

    @Test
    fun `filterStores matches case-insensitive`() {
        val result = filterStores("bio", stores)
        assertEquals(1, result.size)
        assertEquals("s3", result[0].id)
    }

    @Test
    fun `filterStores returns empty for no match`() {
        assertTrue(filterStores("Aldi", stores).isEmpty())
    }

    @Test
    fun `filterStores returns all matching stores`() {
        val storesWithDupes = stores + Store(id = "s4", name = "Carrefour Market", visitOrder = 4)
        val result = filterStores("Carrefour", storesWithDupes)
        assertEquals(2, result.size)
    }

    // --- shouldShowCreateStore ---

    @Test
    fun `shouldShowCreateStore false for blank query`() {
        assertFalse(shouldShowCreateStore("", stores))
    }

    @Test
    fun `shouldShowCreateStore false for whitespace query`() {
        assertFalse(shouldShowCreateStore("   ", stores))
    }

    @Test
    fun `shouldShowCreateStore false when exact match exists`() {
        assertFalse(shouldShowCreateStore("Carrefour", stores))
    }

    @Test
    fun `shouldShowCreateStore false case-insensitive exact match`() {
        assertFalse(shouldShowCreateStore("carrefour", stores))
    }

    @Test
    fun `shouldShowCreateStore true when no exact match`() {
        assertTrue(shouldShowCreateStore("Aldi", stores))
    }

    @Test
    fun `shouldShowCreateStore true for partial match`() {
        assertTrue(shouldShowCreateStore("Car", stores))
    }

    @Test
    fun `shouldShowCreateStore trims whitespace before comparison`() {
        assertFalse(shouldShowCreateStore(" Carrefour ", stores))
    }

    @Test
    fun `shouldShowCreateStore true with empty store list`() {
        assertTrue(shouldShowCreateStore("Aldi", emptyList()))
    }

    // --- resolvePreferredStore ---

    @Test
    fun `resolvePreferredStore returns store when IRI matches`() {
        val store = resolvePreferredStore(products[0], stores)
        assertNotNull(store)
        assertEquals("s1", store!!.id)
    }

    @Test
    fun `resolvePreferredStore returns null when no preferred store`() {
        assertNull(resolvePreferredStore(products[1], stores))
    }

    @Test
    fun `resolvePreferredStore returns null when store ID not found`() {
        val product = Product(
            id = "px", name = "X", category = ProductCategory.OTHER,
            preferredStore = "/api/stores/unknown",
        )
        assertNull(resolvePreferredStore(product, stores))
    }

    @Test
    fun `resolvePreferredStore extracts ID from IRI correctly`() {
        val product = Product(
            id = "ptest", name = "Test", category = ProductCategory.OTHER,
            preferredStore = "/api/stores/s3",
        )
        val store = resolvePreferredStore(product, stores)
        assertNotNull(store)
        assertEquals("Biocoop", store!!.name)
    }

    @Test
    fun `resolvePreferredStore handles nested IRI paths`() {
        val product = Product(
            id = "ptest", name = "Test", category = ProductCategory.OTHER,
            preferredStore = "/api/v2/stores/s2",
        )
        val store = resolvePreferredStore(product, stores)
        assertNotNull(store)
        assertEquals("s2", store!!.id)
    }
}
