package com.maggie.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The caps are not safety nets, they decide what the model reads (MAG-30), so
 * each one is pinned here.
 */
class AssistTextCollectorTest {

    private data class Node(
        override val text: String? = null,
        override val contentDescription: String? = null,
        override val children: List<AssistTextNode> = emptyList(),
    ) : AssistTextNode

    @Test
    fun `the tree is read in the order it is drawn`() {
        val roots = listOf(
            Node(
                text = "Panier",
                children = listOf(
                    Node(text = "Café moulu 250 g"),
                    Node(children = listOf(Node(text = "4,90 €"))),
                ),
            ),
        )

        assertEquals(listOf("Panier", "Café moulu 250 g", "4,90 €"), AssistTextCollector.collect(roots))
    }

    @Test
    fun `a node with no text falls back to its description`() {
        assertEquals(
            listOf("Photo du produit"),
            AssistTextCollector.collect(listOf(Node(contentDescription = "Photo du produit"))),
        )
    }

    @Test
    fun `text wins over a description that repeats it`() {
        val roots = listOf(Node(text = "Ajouter au panier", contentDescription = "Bouton Ajouter au panier"))

        assertEquals(listOf("Ajouter au panier"), AssistTextCollector.collect(roots))
    }

    @Test
    fun `blank nodes and whitespace are not lines of text`() {
        val roots = listOf(
            Node(text = "  "),
            Node(text = "\n\tDîner  chez   Léa\n"),
            Node(text = "", contentDescription = "   "),
        )

        assertEquals(listOf("Dîner chez Léa"), AssistTextCollector.collect(roots))
    }

    @Test
    fun `the same line is sent once, whatever its case`() {
        val roots = listOf(Node(text = "Panier"), Node(text = "panier"), Node(text = "Panier"))

        assertEquals(listOf("Panier"), AssistTextCollector.collect(roots))
    }

    @Test
    fun `a screen of labels is cut at the entry cap`() {
        val roots = (1..60).map { Node(text = "Ligne $it") }

        val collected = AssistTextCollector.collect(roots, maxEntries = 5)

        assertEquals(5, collected.size)
        assertEquals("Ligne 1", collected.first())
        assertEquals("Ligne 5", collected.last())
    }

    @Test
    fun `one long paragraph cannot eat the whole budget`() {
        val roots = listOf(Node(text = "a".repeat(400)), Node(text = "Le prix est de 4,90 €"))

        val collected = AssistTextCollector.collect(roots)

        assertEquals(AssistTextCollector.MAX_ENTRY_CHARS + 1, collected.first().length)
        assertTrue(collected.first().endsWith("…"))
        assertEquals("Le prix est de 4,90 €", collected[1])
    }

    @Test
    fun `the last line is truncated rather than dropped when the budget runs out`() {
        val roots = listOf(Node(text = "a".repeat(30)), Node(text = "b".repeat(30)))

        val collected = AssistTextCollector.collect(roots, maxChars = 50)

        assertEquals(2, collected.size)
        assertEquals("b".repeat(20) + "…", collected[1])
    }

    @Test
    fun `nothing more is collected once the budget leaves room for nothing useful`() {
        val roots = listOf(Node(text = "a".repeat(45)), Node(text = "b".repeat(30)))

        val collected = AssistTextCollector.collect(roots, maxChars = 50)

        assertEquals(listOf("a".repeat(45)), collected)
    }

    @Test
    fun `a screen with nothing to read yields nothing`() {
        assertEquals(emptyList<String>(), AssistTextCollector.collect(emptyList()))
    }

    @Test
    fun `a deep tree does not blow the stack`() {
        var deepest: AssistTextNode = Node(text = "fond")
        repeat(10_000) { deepest = Node(children = listOf(deepest)) }

        assertEquals(listOf("fond"), AssistTextCollector.collect(listOf(deepest)))
    }
}
