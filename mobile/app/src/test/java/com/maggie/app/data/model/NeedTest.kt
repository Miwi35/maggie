package com.maggie.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the meals need of a product, as the owner reads it (MAG-380). */
class NeedTest {
    @Test
    fun `a need is read in grams, millilitres or its own unit`() {
        assertEquals("800 g", Need(800.0, "g").label())
        assertEquals("250 ml", Need(250.0, "ml").label())
        assertEquals("2 pièces", Need(2.0, "piece").label())
        assertEquals("1 pièce", Need(1.0, "piece").label())
        assertEquals("3", Need(3.0, null).label())
    }

    @Test
    fun `a need from a thousand grams or millilitres is read in kilos or litres`() {
        assertEquals("1 kg", Need(1000.0, "g").label())
        assertEquals("1,5 kg", Need(1500.0, "g").label())
        assertEquals("2 l", Need(2000.0, "ml").label())
        assertEquals("999 g", Need(999.0, "g").label())
    }

    @Test
    fun `a unit the app does not know is shown as the API wrote it`() {
        assertEquals("2 tranche", Need(2.0, "tranche").label())
    }

    @Test
    fun `several needs that cannot be added are joined`() {
        assertEquals("300 g + 2 pièces", listOf(Need(300.0, "g"), Need(2.0, "piece")).neededLabel())
        assertEquals("800 g", listOf(Need(800.0, "g")).neededLabel())
        assertNull(emptyList<Need>().neededLabel())
        assertNull(null.neededLabel())
    }

    @Test
    fun `a grocery item decodes its needs, and one without them`() {
        val json = Json { ignoreUnknownKeys = true }

        val withNeeds = json.decodeFromString<GroceryItem>(
            """{"id":"i1","label":"Riz","quantity":2,"unit":"pack","needed":[{"quantity":800,"unit":"g"},{"quantity":2,"unit":null}]}""",
        )
        val without = json.decodeFromString<GroceryItem>("""{"id":"i2","label":"Sel","needed":null}""")
        val older = json.decodeFromString<GroceryItem>("""{"id":"i3","label":"Sel"}""")

        assertEquals(listOf(Need(800.0, "g"), Need(2.0, null)), withNeeds.needed)
        assertNull(without.needed)
        assertNull(older.needed)
    }
}
