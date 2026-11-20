package com.maggie.app.ui.screens.grocery

import com.maggie.app.data.model.Aisle
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductAisle
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.Store
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

    private val superU = Store(id = "s1", name = "Super U")
    private val biocoop = Store(id = "s2", name = "Biocoop")
    private val aislesByStore = mapOf(
        "s1" to listOf(Aisle("a1", "/api/stores/s1", "Légumes", 1)),
        "s2" to listOf(Aisle("b1", "/api/stores/s2", "Fruits et légumes", 1)),
    )

    private fun where(product: Product) = productWhere(product, listOf(superU, biocoop), aislesByStore)

    @Test
    fun `the usual store and its aisle, then the fallback and its aisle`() {
        val p = product("Poireau", ProductAisle("s1", "a1"), ProductAisle("s2", "b1"))
            .copy(preferredStore = "/api/stores/s1", fallbackStore = "/api/stores/s2")

        assertEquals("Super U · Légumes — à défaut Biocoop · Fruits et légumes", where(p))
    }

    @Test
    fun `a store with no aisle there is named alone`() {
        val p = product("Poireau").copy(preferredStore = "/api/stores/s1", fallbackStore = "/api/stores/s2")

        assertEquals("Super U — à défaut Biocoop", where(p))
    }

    @Test
    fun `only a fallback reads à défaut`() {
        val p = product("Poireau").copy(fallbackStore = "/api/stores/s2")

        assertEquals("à défaut Biocoop", where(p))
    }

    @Test
    fun `no store gives no line`() {
        assertNull(where(product("Poireau")))
    }

    @Test
    fun `a store the list does not know is left out`() {
        val p = product("Poireau").copy(preferredStore = "/api/stores/gone", fallbackStore = "/api/stores/s2")

        assertEquals("à défaut Biocoop", where(p))
    }
}
