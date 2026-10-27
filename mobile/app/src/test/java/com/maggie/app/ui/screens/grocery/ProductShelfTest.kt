package com.maggie.app.ui.screens.grocery

import com.maggie.app.data.model.Aisle
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductAisle
import com.maggie.app.data.model.ProductCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The products cut by aisle once a store is chosen (MAG-606). */
class ProductShelfTest {

    private val frais = Aisle("a1", "/api/stores/s1", "Frais", 1)
    private val epicerie = Aisle("a2", "/api/stores/s1", "Épicerie", 2)
    private val boissons = Aisle("a3", "/api/stores/s1", "Boissons", 3)

    private fun product(name: String, vararg aisles: ProductAisle) =
        Product(id = name, name = name, category = ProductCategory.OTHER, aisles = aisles.toList())

    @Test
    fun `the aisles come in the order of the way through the store, whatever the list order`() {
        val products = listOf(
            product("Cola", ProductAisle("s1", "a3")),
            product("Biscottes", ProductAisle("s1", "a2")),
            product("Yaourt", ProductAisle("s1", "a1")),
        )

        val sections = groupByAisle(products, "s1", listOf(boissons, frais, epicerie))

        assertEquals(listOf("Frais", "Épicerie", "Boissons"), sections.map { it.title })
        assertEquals(listOf("Yaourt"), sections[0].products.map { it.name })
    }

    @Test
    fun `Sans rayon comes last, with the products that have no aisle in this store`() {
        val products = listOf(
            product("Sel"),
            product("Yaourt", ProductAisle("s1", "a1")),
            product("Riz", ProductAisle("s2", "elsewhere")),
            product("Lait", ProductAisle("s1", null)),
        )

        val sections = groupByAisle(products, "s1", listOf(frais, epicerie))

        assertEquals(listOf("Frais", NO_AISLE_TITLE), sections.map { it.title })
        assertNull(sections.last().aisleId)
        assertEquals(listOf("Sel", "Riz", "Lait"), sections.last().products.map { it.name })
    }

    @Test
    fun `an aisle with no product shows no header`() {
        val sections = groupByAisle(listOf(product("Yaourt", ProductAisle("s1", "a1"))), "s1", listOf(frais, epicerie, boissons))

        assertEquals(listOf("Frais"), sections.map { it.title })
    }

    @Test
    fun `a product keeps the place the API gave it inside its aisle`() {
        val products = listOf(
            product("Yaourt", ProductAisle("s1", "a1")),
            product("Beurre", ProductAisle("s1", "a1")),
            product("Crème", ProductAisle("s1", "a1")),
        )

        val sections = groupByAisle(products, "s1", listOf(frais))

        assertEquals(listOf("Yaourt", "Beurre", "Crème"), sections.single().products.map { it.name })
    }

    @Test
    fun `an aisle the store no longer has counts as Sans rayon`() {
        val sections = groupByAisle(listOf(product("Yaourt", ProductAisle("s1", "gone"))), "s1", listOf(frais))

        assertEquals(listOf(NO_AISLE_TITLE), sections.map { it.title })
    }

    @Test
    fun `no product gives no section`() {
        assertTrue(groupByAisle(emptyList(), "s1", listOf(frais)).isEmpty())
    }

    @Test
    fun `the categories come in their usual order, Autre last, with no header for an empty one`() {
        val products = listOf(
            Product(id = "1", name = "Beurre", category = ProductCategory.OTHER),
            Product(id = "2", name = "Lait", category = ProductCategory.DAIRY),
            Product(id = "3", name = "Ail", category = ProductCategory.PRODUCE),
            Product(id = "4", name = "Poireau", category = ProductCategory.PRODUCE),
        )

        val sections = groupByCategory(products)

        assertEquals(listOf("Fruits et légumes", "Produits laitiers", "Autre"), sections.map { it.title })
        assertEquals(listOf("Ail", "Poireau"), sections[0].products.map { it.name })
    }
}
