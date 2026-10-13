package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryTreeTest {

    private fun cat(id: String, name: String, parent: String? = null, path: String? = null) =
        Category(id = id, name = name, parent = parent?.let(::categoryIri), path = path)

    private val loisirs = cat("l", "Loisirs")
    private val bars = cat("b", "Bars", parent = "l")
    private val obsydian = cat("o", "Obsydian", parent = "b")
    private val cinema = cat("c", "Cinéma", parent = "l")
    private val tree = CategoryTree(listOf(obsydian, cinema, bars, loisirs))

    @Test
    fun `levels count the distance from the main category`() {
        assertEquals(1, tree.level("l"))
        assertEquals(2, tree.level("b"))
        assertEquals(3, tree.level("o"))
    }

    @Test
    fun `rows follow the tree depth first, children by name, and only under an open node`() {
        assertEquals(listOf("Loisirs"), tree.rows(emptySet()).map { it.category.name })

        val rows = tree.rows(setOf("l", "b"))

        assertEquals(listOf("Loisirs", "Bars", "Obsydian", "Cinéma"), rows.map { it.category.name })
        assertEquals(listOf(1, 2, 3, 2), rows.map { it.level })
        assertTrue(rows[0].expanded)
        assertFalse(rows[2].hasChildren)
    }

    @Test
    fun `a third level node cannot take a child`() {
        val rows = tree.rows(setOf("l", "b")).associateBy { it.category.id }

        assertTrue(rows.getValue("b").canAddChild)
        assertFalse(rows.getValue("o").canAddChild)
    }

    @Test
    fun `ancestors, descendants and height`() {
        assertEquals(listOf("b", "l"), tree.ancestors("o"))
        assertEquals(setOf("b", "o", "c"), tree.descendants("l"))
        assertEquals(3, tree.height("l"))
        assertEquals(1, tree.height("o"))
    }

    @Test
    fun `the path comes from the API, or from the links without it`() {
        assertEquals("Loisirs › Bars › Obsydian", tree.path(obsydian))
        assertEquals("Chemin du serveur", tree.path(obsydian.copy(path = "Chemin du serveur")))
        assertEquals("Loisirs › Bars", tree.pathOf("b"))
        assertNull(tree.pathOf("nope"))
        assertNull(tree.pathOf(null))
    }

    @Test
    fun `a new category can go under a level 1 or 2 node only`() {
        assertEquals(listOf("b", "c", "l"), tree.parentCandidates(null).map { it.id }.sorted())
    }

    @Test
    fun `a category cannot go under itself or its descendants, nor deeper than its subtree allows`() {
        // Bars carries Obsydian: two levels, so only a main category can hold it.
        assertEquals(listOf("l"), tree.parentCandidates("b").map { it.id })
        // Cinéma is a leaf: it fits under Bars, but not under itself.
        assertEquals(listOf("b", "l"), tree.parentCandidates("c").map { it.id }.sorted())
        // Loisirs carries three levels: nowhere to go.
        assertEquals(emptyList<Category>(), tree.parentCandidates("l"))
    }

    @Test
    fun `a parent missing from the list makes its children main categories`() {
        val orphan = CategoryTree(listOf(bars))

        assertEquals(1, orphan.rows(emptySet()).single().level)
    }

    @Test
    fun `a cycle in the links does not loop`() {
        val a = cat("a", "A", parent = "b")
        val b = cat("b", "B", parent = "a")
        val cyclic = CategoryTree(listOf(a, b))

        cyclic.level("a")
        cyclic.ancestors("a")
        cyclic.descendants("a")
    }

    @Test
    fun `displayPath falls back to the name`() {
        assertEquals("Loisirs", loisirs.displayPath())
        assertEquals("A › B", loisirs.copy(path = "A › B").displayPath())
        assertEquals("Loisirs", loisirs.copy(path = " ").displayPath())
    }
}
