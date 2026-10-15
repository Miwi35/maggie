package com.maggie.app.ui.screens.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MAG-474: the catalogue the picker lists, and the filter that narrows it. */
class CategoryIconCatalogTest {

    /** The reference list: the admin's `categoryIconCatalog.test.ts` carries the same one. Add a name to both. */
    private val referenceNames = listOf(
        "home", "shopping-cart", "repeat", "water-drop", "bolt", "wifi", "tv", "cloud", "sports-esports",
        "trending-up", "local-gas-station", "account-balance", "error-outline", "payments", "star",
        "volunteer-activism", "undo", "redeem", "restaurant", "directions-car", "flight", "school",
        "favorite", "fitness-center", "movie", "savings",
    )

    private fun names(query: String) = CategoryIconCatalog.filter(query).map { it.name }

    @Test
    fun `lists the same icons as the reference list, in order`() {
        assertEquals(referenceNames, CategoryIconCatalog.entries.map { it.name })
        referenceNames.forEach { assertNotNull(it, categoryIcon(it)) }
    }

    @Test
    fun `gives every icon a label and at least one search word`() {
        CategoryIconCatalog.entries.forEach {
            assertTrue(it.name, it.label.isNotBlank())
            assertTrue(it.name, it.keywords.isNotEmpty())
        }
    }

    @Test
    fun `a blank query keeps everything`() {
        assertEquals(referenceNames, names(""))
        assertEquals(referenceNames, names("   "))
    }

    @Test
    fun `voiture leaves the car alone and courses the cart alone`() {
        assertEquals(listOf("directions-car"), names("voiture"))
        assertEquals(listOf("shopping-cart"), names("courses"))
    }

    @Test
    fun `searches the keywords and the icon name, not only the label`() {
        assertEquals(listOf("directions-car"), names("garage"))
        assertEquals(listOf("local-gas-station"), names("gas-station"))
    }

    @Test
    fun `ignores case and accents on both sides`() {
        assertEquals(listOf("savings"), names("EPARGNE"))
        assertEquals(listOf("bolt"), names("électricité"))
        assertEquals(listOf("bolt"), names("electricite"))
        assertEquals(listOf("favorite"), names("coeur"))
    }

    @Test
    fun `an unknown word leaves nothing`() {
        assertEquals(emptyList<String>(), names("zzz"))
    }

    @Test
    fun `finds an icon by a name in any case, and none for a name we do not draw`() {
        assertEquals("bolt", CategoryIconCatalog.find(" Bolt ")?.name)
        assertNull(CategoryIconCatalog.find("rocket"))
        assertNull(CategoryIconCatalog.find(null))
    }

    @Test
    fun `a name outside the catalogue is listed first, kept as it is`() {
        val tiles = CategoryIconCatalog.tiles("", "rocket")

        assertEquals("rocket", tiles.first().name)
        assertEquals("rocket", tiles.first().label)
        assertNull(tiles.first().icon)
        assertEquals(referenceNames, tiles.drop(1).map { it.name })
    }

    @Test
    fun `a name outside the catalogue follows the search like the others`() {
        assertEquals(listOf("rocket"), CategoryIconCatalog.tiles("rock", "rocket").map { it.name })
        assertEquals(listOf("directions-car"), CategoryIconCatalog.tiles("voiture", "rocket").map { it.name })
    }

    @Test
    fun `a catalogue name is not listed twice`() {
        assertEquals(referenceNames, CategoryIconCatalog.tiles("", "bolt").map { it.name })
        assertEquals(referenceNames, CategoryIconCatalog.tiles("", null).map { it.name })
    }
}
