package com.maggie.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sources of a web answer, as links (MAG-384). */
class MessageLinksTest {

    private val page = "https://www.education.gouv.fr/le-calendrier-scolaire-2026-2027"

    private fun urls(text: String) = splitLinks(text).mapNotNull { it.url }

    @Test
    fun `a message without an address is one piece of text`() {
        assertEquals(listOf(TextPart("Bonjour")), splitLinks("Bonjour"))
        assertEquals(emptyList<TextPart>(), splitLinks(""))
    }

    @Test
    fun `the source line of a web answer yields its link`() {
        val parts = splitLinks("Du 17 octobre.\n\nSource : education.gouv.fr — $page")

        assertEquals(listOf(TextPart("Du 17 octobre.\n\nSource : education.gouv.fr — "), TextPart(page, page)), parts)
    }

    @Test
    fun `several sources on one line are as many links`() {
        val text = "Sources : a.fr — https://a.fr/x · b.fr — http://b.fr/y?q=1"

        assertEquals(listOf("https://a.fr/x", "http://b.fr/y?q=1"), urls(text))
        assertEquals(text, splitLinks(text).joinToString("") { it.text })
    }

    @Test
    fun `an address loses the punctuation that ends the sentence around it`() {
        assertEquals(listOf("https://a.fr/x"), urls("Voir https://a.fr/x, puis ailleurs."))
        assertEquals(listOf("https://a.fr/x"), urls("Voir https://a.fr/x."))
        assertEquals(listOf("https://a.fr/x"), urls("(voir https://a.fr/x)"))
    }

    @Test
    fun `a closing parenthesis that belongs to the address stays`() {
        val url = "https://fr.wikipedia.org/wiki/Rennes_(ville)"

        assertEquals(listOf(url), urls(url))
    }

    @Test
    fun `only http and https are links`() {
        assertEquals(emptyList<String>(), urls("javascript:alert(1) ftp://a.fr/x https://"))
    }

    @Test
    fun `the displayed text carries one link annotation per address, over the address only`() {
        val text = "Source : education.gouv.fr — $page"

        val annotated = linkified(text, Color.Blue)

        assertEquals(text, annotated.text)
        val links = annotated.getLinkAnnotations(0, text.length)
        assertEquals(1, links.size)
        val link = links.single()
        assertTrue(link.item is LinkAnnotation.Url)
        assertEquals(page, (link.item as LinkAnnotation.Url).url)
        assertEquals(text.length - page.length, link.start)
        assertEquals(text.length, link.end)
    }
}
