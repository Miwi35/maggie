package com.maggie.app.ui.screens.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryColorsTest {

    @Test
    fun `a hex code is normalised to six upper-case digits`() {
        assertEquals("#4CAF50", CategoryColors.normalize("#4caf50"))
        assertEquals("#4CAF50", CategoryColors.normalize(" 4CAF50 "))
        assertEquals("#AABBCC", CategoryColors.normalize("#abc"))
    }

    @Test
    fun `something that is not a colour is refused`() {
        assertNull(CategoryColors.normalize(""))
        assertNull(CategoryColors.normalize("vert"))
        assertNull(CategoryColors.normalize("#12345"))
        assertNull(CategoryColors.normalize("#GGGGGG"))
    }

    @Test
    fun `a colour goes to hue saturation value and comes back unchanged`() {
        listOf("#FF0000", "#4CAF50", "#2196F3", "#000000", "#FFFFFF", "#795548").forEach { hex ->
            val (h, s, v) = requireNotNull(CategoryColors.toHsv(hex))
            assertEquals(hex, CategoryColors.fromHsv(h, s, v))
        }
    }

    @Test
    fun `the primaries sit where the hue wheel puts them`() {
        assertEquals(0f, CategoryColors.toHsv("#FF0000")!![0], 0.5f)
        assertEquals(120f, CategoryColors.toHsv("#00FF00")!![0], 0.5f)
        assertEquals(240f, CategoryColors.toHsv("#0000FF")!![0], 0.5f)
        assertEquals("#FF0000", CategoryColors.fromHsv(0f, 1f, 1f))
        assertEquals("#00FF00", CategoryColors.fromHsv(120f, 1f, 1f))
    }

    @Test
    fun `every colour of the palette is a valid code and none is repeated`() {
        assertTrue(CategoryColors.PALETTE.isNotEmpty())
        CategoryColors.PALETTE.forEach { assertNotNull(it, CategoryColors.normalize(it)) }
        assertEquals(CategoryColors.PALETTE.size, CategoryColors.PALETTE.toSet().size)
        assertTrue(CategoryColors.PALETTE.contains("#4CAF50"))
    }
}
