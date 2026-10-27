package com.maggie.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroceryNavigationTest {

    @Test
    fun `Courses has three sections, Liste first, Produits then Magasins`() {
        assertEquals(listOf("list", "products", "stores"), GrocerySection.entries.map { it.key })
        assertEquals(listOf("Liste", "Produits", "Magasins"), GrocerySection.entries.map { it.label })
    }

    @Test
    fun `each section is the page of its own route`() {
        assertEquals(Screen.Grocery.route, GrocerySection.LIST.route)
        assertEquals(Screen.ProductList.route, GrocerySection.PRODUCTS.route)
        assertEquals(Screen.StoreList.route, GrocerySection.STORES.route)
    }

    @Test
    fun `pageOf names the section a route wears the breadcrumb of`() {
        assertEquals(GrocerySection.LIST, GrocerySection.pageOf(Screen.Grocery.route))
        assertEquals(GrocerySection.PRODUCTS, GrocerySection.pageOf(Screen.ProductList.route))
        assertEquals(GrocerySection.STORES, GrocerySection.pageOf(Screen.StoreList.route))
    }

    @Test
    fun `a detail of the module or a route outside it wears no breadcrumb`() {
        assertNull(GrocerySection.pageOf(Screen.PastErrands.route))
        assertNull(GrocerySection.pageOf(Screen.StoreAisles.route))
        assertNull(GrocerySection.pageOf(Screen.Calendar.route))
        assertNull(GrocerySection.pageOf(null))
    }

    @Test
    fun `byKey finds a section by the key of its breadcrumb entry`() {
        assertEquals(GrocerySection.STORES, GrocerySection.byKey("stores"))
        assertNull(GrocerySection.byKey("recurring"))
        assertNull(GrocerySection.byKey(null))
    }

    @Test
    fun `the breadcrumb entries are the sections in order`() {
        assertEquals(
            listOf("list" to "Liste", "products" to "Produits", "stores" to "Magasins"),
            GrocerySection.entries.map { it.breadcrumb.key to it.breadcrumb.label },
        )
    }

    @Test
    fun `with the list underneath a section pops back to it, so the sections are siblings`() {
        assertEquals(
            GrocerySectionAction(route = Screen.StoreList.route, popToList = true),
            grocerySectionAction(GrocerySection.STORES, listInBackStack = true),
        )
        assertEquals(
            GrocerySectionAction(route = Screen.Grocery.route, popToList = true),
            grocerySectionAction(GrocerySection.LIST, listInBackStack = true),
        )
    }

    @Test
    fun `without the list underneath the current screen is the one replaced`() {
        assertEquals(
            GrocerySectionAction(route = Screen.ProductList.route, popToList = false),
            grocerySectionAction(GrocerySection.PRODUCTS, listInBackStack = false),
        )
    }
}
