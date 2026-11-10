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

    @Test
    fun `the editor draws marks away, boxes as blanks and bullets as dots`() {
        val v = MarkdownFormat.visual("# Titre\n- [ ] a **b**\n- [x] c\n- d")

        assertEquals("Titre\n\u2003\u2003a b\n\u2003\u2003c\n• d", v.text)
        assertEquals(listOf(MdBox(0, false, 6), MdBox(1, true, 12)), v.boxes)
        assertEquals(MdStyle(0, 5, MdStyleKind.HEADING, 1), v.styles.first())
        assertEquals(true, v.styles.contains(MdStyle(10, 11, MdStyleKind.BOLD)))
        assertEquals(true, v.styles.contains(MdStyle(14, 15, MdStyleKind.DONE)))
    }

    @Test
    fun `a plain text is drawn as it is written`() {
        val text = "Les cartons\n\n2 * 3 = 6"
        assertEquals(text, MarkdownFormat.visual(text).text)
        assertEquals(emptyList<MdBox>(), MarkdownFormat.visual(text).boxes)
    }

    @Test
    fun `the cursor maps both ways around hidden marks`() {
        val text = "- [ ] a\nb"
        val v = MarkdownFormat.visual(text)

        assertEquals(2, v.toDisplayed(6))
        assertEquals(6, v.toOriginal(2))
        assertEquals(0, v.toDisplayed(0))
        assertEquals(text.length, v.toOriginal(v.text.length))
        assertEquals(v.text.length, v.toDisplayed(text.length))
    }

    @Test
    fun `a backspace in a hidden mark takes the whole mark off`() {
        assertEquals("a", MarkdownFormat.onChange(at("- [ ] a", 6), at("- [ ]a", 5)).text)
        assertEquals("a", MarkdownFormat.onChange(at("# a", 2), at("#a", 1)).text)
        assertEquals("x\na", MarkdownFormat.onChange(at("x\n- a", 4), at("x\n-a", 3)).text)
        assertEquals("- [ ] ab", MarkdownFormat.onChange(at("- [ ] abc", 9), at("- [ ] ab", 8)).text)
    }

    @Test
    fun `a backspace before a bold text unwraps it instead of leaving a stray star`() {
        assertEquals("Fort", MarkdownFormat.onChange(at("**Fort**", 2), at("*Fort**", 1)).text)
        assertEquals(0, MarkdownFormat.onChange(at("**Fort**", 2), at("*Fort**", 1)).selection.start)
        assertEquals("a\nGras b", MarkdownFormat.onChange(at("a\n**Gras** b", 4), at("a\n*Gras** b", 3)).text)
        assertEquals("- [ ] Fort", MarkdownFormat.onChange(at("- [ ] **Fort**", 8), at("- [ ] *Fort**", 7)).text)
        assertEquals("Fort", MarkdownFormat.onChange(at("*Fort*", 1), at("Fort*", 0)).text)
    }
}
