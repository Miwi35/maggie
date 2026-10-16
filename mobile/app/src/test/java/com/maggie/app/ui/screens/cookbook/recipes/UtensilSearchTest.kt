package com.maggie.app.ui.screens.cookbook.recipes

import com.maggie.app.data.model.Utensil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UtensilSearchTest {

    private val catalogue = listOf(Utensil("u-2", "Fouet"), Utensil("u-1", "Casserole"), Utensil("u-3", "Poêle"))

    @Test
    fun `an empty search lists the catalogue in alphabetical order`() {
        assertEquals(listOf("Casserole", "Fouet", "Poêle"), utensilSearchResults(catalogue, emptyList(), "").map { it.name })
    }

    @Test
    fun `the search matches a part of the name whatever the case`() {
        assertEquals(listOf("Poêle"), utensilSearchResults(catalogue, emptyList(), "  PO").map { it.name })
    }

    @Test
    fun `a utensil already chosen is not offered again`() {
        val chosen = listOf(UtensilRef("u-1", "Casserole"))

        assertEquals(listOf("Fouet", "Poêle"), utensilSearchResults(catalogue, chosen, "").map { it.name })
    }

    @Test
    fun `a name the catalogue lacks is offered for creation, by name`() {
        assertEquals(UtensilRef(name = "Mixeur"), utensilToCreate(catalogue, emptyList(), " Mixeur "))
    }

    @Test
    fun `a name the catalogue or the recipe already holds is not offered for creation`() {
        assertNull(utensilToCreate(catalogue, emptyList(), "fouet"))
        assertNull(utensilToCreate(catalogue, listOf(UtensilRef(name = "Mixeur")), "mixeur"))
        assertNull(utensilToCreate(catalogue, emptyList(), "  "))
    }
}
