package com.maggie.app.ui.screens.finance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MAG-474, MAG-502: the catalogue the picker lists, and the filter that narrows it.
 *
 * `design/category-icons.json` is the file both clients are generated from (`task icons:generate`);
 * the admin's `categoryIconCatalog.test.ts` reads the same one. It is found by walking up from the
 * working directory and its absence **fails**: a contract test that finds nothing proves nothing.
 */
class CategoryIconCatalogTest {

    private class Shared(val name: String, val label: String, val keywords: List<String>)

    private val shared: List<Shared> = Json.parseToJsonElement(catalogueFile().readText())
        .jsonObject["icons"]!!.jsonArray.map { element ->
            val icon: JsonObject = element.jsonObject
            Shared(
                icon["name"]!!.jsonPrimitive.content,
                icon["label"]!!.jsonPrimitive.content,
                icon["keywords"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
        }

    private fun names(query: String) = CategoryIconCatalog.filter(query).map { it.name }

    @Test
    fun `lists exactly the names, labels and keywords of design category-icons json, in order`() {
        assertEquals(
            shared.map { Triple(it.name, it.label, it.keywords) },
            CategoryIconCatalog.entries.map { Triple(it.name, it.label, it.keywords) },
        )
    }

    @Test
    fun `covers every Material icon the two clients share`() {
        assertTrue(CategoryIconCatalog.entries.size > 1500)
        assertEquals(CategoryIconCatalog.entries.size, CategoryIconCatalog.entries.map { it.name }.toSet().size)
    }

    @Test
    fun `every name of the catalogue has a vector to draw`() {
        val missing = CategoryIconCatalog.entries.map { it.name }.filter { categoryIcon(it) == null }

        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `names are kebab case`() {
        val kebab = Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")

        assertEquals(emptyList<String>(), CategoryIconCatalog.entries.map { it.name }.filterNot { kebab.matches(it) })
    }

    @Test
    fun `gives every icon a label`() {
        CategoryIconCatalog.entries.forEach { assertTrue(it.name, it.label.isNotBlank()) }
    }

    @Test
    fun `keeps the icons chosen before the catalogue grew, with their label`() {
        assertEquals(listOf("home", "shopping-cart", "repeat"), CategoryIconCatalog.entries.take(3).map { it.name })
        assertEquals("Voiture", CategoryIconCatalog.find("directions-car")?.label)
        assertEquals("Courses", CategoryIconCatalog.find(" Shopping-Cart ")?.label)
    }

    @Test
    fun `a blank query keeps everything`() {
        assertEquals(CategoryIconCatalog.entries.size, names("").size)
        assertEquals(CategoryIconCatalog.entries.size, names("   ").size)
    }

    @Test
    fun `finds an icon in French, with or without accents`() {
        assertTrue(names("chien").contains("pets"))
        assertTrue(names("médecin").contains("medical-services"))
        assertTrue(names("medecin").contains("medical-services"))
        assertTrue(names("bébé").contains("child-care"))
        assertTrue(names("parking").contains("local-parking"))
        assertTrue(names("train").contains("train"))
        assertTrue(names("carburant").contains("local-gas-station"))
    }

    @Test
    fun `the French label comes before a mere keyword`() {
        assertEquals("directions-car", names("voiture").first())
        assertEquals("shopping-cart", names("courses").first())
    }

    @Test
    fun `searches the English name, split into words`() {
        assertTrue(names("pets").contains("pets"))
        assertTrue(names("gas station").contains("local-gas-station"))
        assertTrue(names("local gas").contains("local-gas-station"))
        assertTrue(names("gas-station").contains("local-gas-station"))
    }

    @Test
    fun `every word of the query must match`() {
        assertEquals(emptyList<String>(), names("gas zzz"))
    }

    @Test
    fun `ignores case and accents on both sides`() {
        assertTrue(names("EPARGNE").contains("savings"))
        assertTrue(names("électricité").contains("bolt"))
        assertTrue(names("electricite").contains("bolt"))
        assertTrue(names("coeur").contains("favorite"))
    }

    @Test
    fun `an unknown word leaves nothing`() {
        assertEquals(emptyList<String>(), names("zzz"))
    }

    @Test
    fun `normalises accents, case, ligatures and separators`() {
        assertEquals("coeur a epargne", CategoryIconCatalog.normalize(" Cœur À Épargne "))
        assertEquals("local gas station", CategoryIconCatalog.normalize("local_gas-station"))
    }

    @Test
    fun `finds an icon by a name in any case, and none for a name we do not draw`() {
        assertEquals("bolt", CategoryIconCatalog.find(" Bolt ")?.name)
        assertNotNull(categoryIcon(" Bolt "))
        assertNull(CategoryIconCatalog.find("icone-disparue"))
        assertNull(categoryIcon("icone-disparue"))
        assertNull(CategoryIconCatalog.find(null))
    }

    @Test
    fun `a name outside the catalogue is listed first, kept as it is`() {
        val tiles = CategoryIconCatalog.tiles("", "icone-disparue")

        assertEquals("icone-disparue", tiles.first().name)
        assertEquals("icone-disparue", tiles.first().label)
        assertEquals(CategoryIconCatalog.entries.map { it.name }, tiles.drop(1).map { it.name })
    }

    @Test
    fun `a name outside the catalogue follows the search like the others`() {
        assertEquals(listOf("icone-disparue"), CategoryIconCatalog.tiles("disparue", "icone-disparue").map { it.name })
        assertEquals("directions-car", CategoryIconCatalog.tiles("voiture", "icone-disparue").first().name)
    }

    @Test
    fun `a name outside the catalogue keeps its case`() {
        assertEquals("Icone-Disparue", CategoryIconCatalog.tiles("", "Icone-Disparue").first().name)
    }

    @Test
    fun `a catalogue name is not listed twice`() {
        assertEquals(CategoryIconCatalog.entries.map { it.name }, CategoryIconCatalog.tiles("", "bolt").map { it.name })
        assertEquals(CategoryIconCatalog.entries.map { it.name }, CategoryIconCatalog.tiles("", null).map { it.name })
    }

    private fun catalogueFile(): File {
        var candidate: File? = File("").absoluteFile
        while (candidate != null) {
            val file = File(candidate, "design/category-icons.json")
            if (file.isFile) return file
            candidate = candidate.parentFile
        }
        throw AssertionError(
            "No design/category-icons.json found above ${File("").absolutePath}. " +
                "It is the source of both icon catalogues — run `task icons:generate`.",
        )
    }
}
