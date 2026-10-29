package com.maggie.app.ui.components.markdown

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownFormatTest {

    private fun at(text: String, from: Int = text.length, to: Int = from) = TextFieldValue(text, TextRange(from, to))

    @Test
    fun `ticking the first box rewrites only that box`() {
        val text = "Avant\n- [ ] Douche solaire\n- [ ] Pompe\n- [x] Filtre"

        assertEquals("Avant\n- [x] Douche solaire\n- [ ] Pompe\n- [x] Filtre", MarkdownFormat.toggleCheckbox(text, 0))
        assertEquals("Avant\n- [ ] Douche solaire\n- [ ] Pompe\n- [ ] Filtre", MarkdownFormat.toggleCheckbox(text, 2))
    }

    @Test
    fun `the progress counts the boxes, none gives null`() {
        assertEquals(1 to 3, MarkdownFormat.progress("- [x] a\n- [ ] b\n- [ ] c"))
        assertNull(MarkdownFormat.progress("- a\nb"))
    }

    @Test
    fun `a text without Markdown is one paragraph per line`() {
        assertEquals(
            listOf(MdBlock.Paragraph("Les cartons"), MdBlock.Blank, MdBlock.Paragraph("sont à la cave")),
            MarkdownFormat.parse("Les cartons\n\nsont à la cave"),
        )
        assertEquals(listOf(MdSpan("2 * 3 = 6")), MarkdownFormat.spans("2 * 3 = 6"))
    }

    @Test
    fun `bold, italic and links are cut out of a line`() {
        assertEquals(
            listOf(MdSpan("a "), MdSpan("b", bold = true), MdSpan(" "), MdSpan("c", italic = true), MdSpan(" "), MdSpan("d", url = "https://x.fr")),
            MarkdownFormat.spans("a **b** *c* [d](https://x.fr)"),
        )
    }

    @Test
    fun `titles and bullets are read`() {
        assertEquals(
            listOf(MdBlock.Heading(2, "Courses"), MdBlock.Bullet("lait", 0)),
            MarkdownFormat.parse("## Courses\n- lait"),
        )
    }

    @Test
    fun `the case button writes a box at the cursor's line, and takes it off again`() {
        val on = MarkdownFormat.toggleCheck(at("Douche", 3))

        assertEquals("- [ ] Douche", on.text)
        assertEquals(TextRange(9), on.selection)
        assertEquals("Douche", MarkdownFormat.toggleCheck(on).text)
    }

    @Test
    fun `the puces button turns a box into a bullet`() {
        assertEquals("- a", MarkdownFormat.toggleBullet(at("- [ ] a")).text)
        assertEquals("- a", MarkdownFormat.toggleBullet(at("a")).text)
        assertEquals("a", MarkdownFormat.toggleBullet(at("- a")).text)
    }

    @Test
    fun `the title button works on the line of the cursor only`() {
        assertEquals("un\n# deux", MarkdownFormat.toggleHeading(at("un\ndeux")).text)
        assertEquals("deux", MarkdownFormat.toggleHeading(at("# deux")).text)
    }

    @Test
    fun `bold wraps the selection, or opens a pair around the cursor`() {
        assertEquals("a **bc** d", MarkdownFormat.bold(at("a bc d", 2, 4)).text)
        val empty = MarkdownFormat.bold(at("ab", 1))
        assertEquals("a****b", empty.text)
        assertEquals(TextRange(3), empty.selection)
    }

    @Test
    fun `the link button leaves the cursor where the address goes`() {
        val withText = MarkdownFormat.link(at("voir ici", 5, 8))
        assertEquals("voir [ici]()", withText.text)
        assertEquals(TextRange(11), withText.selection)

        val empty = MarkdownFormat.link(at(""))
        assertEquals("[]()", empty.text)
        assertEquals(TextRange(1), empty.selection)
    }

    @Test
    fun `clearing takes the marks off the line`() {
        assertEquals("Un lien gras", MarkdownFormat.clear(at("- [x] Un [lien](https://x.fr) **gras**")).text)
        assertEquals("Titre", MarkdownFormat.clear(at("# Titre")).text)
    }

    @Test
    fun `Enter after an item opens the next one, a box comes back unchecked`() {
        val old = at("- [x] Pompe")
        val typed = TextFieldValue("- [x] Pompe\n", TextRange(12))
        val next = MarkdownFormat.onChange(old, typed)

        assertEquals("- [x] Pompe\n- [ ] ", next.text)
        assertEquals(TextRange(18), next.selection)
        assertEquals("- a\n- ", MarkdownFormat.onChange(at("- a"), TextFieldValue("- a\n", TextRange(4))).text)
    }

    @Test
    fun `Enter on an empty item leaves the list`() {
        val left = MarkdownFormat.onChange(at("- a\n- [ ] "), TextFieldValue("- a\n- [ ] \n", TextRange(11)))

        assertEquals("- a\n", left.text)
        assertEquals(TextRange(4), left.selection)
    }

    @Test
    fun `Enter outside a list and ordinary typing are left alone`() {
        val typed = TextFieldValue("abc\n", TextRange(4))
        assertEquals(typed, MarkdownFormat.onChange(at("abc"), typed))
        val letter = TextFieldValue("- ab", TextRange(4))
        assertEquals(letter, MarkdownFormat.onChange(at("- a"), letter))
    }
}
