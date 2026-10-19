package com.maggie.app.ui.screens.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewSheetNavTest {

    private val before = listOf("a", "b", "c", "d")

    @Test
    fun `the cursor goes to the line that followed the removed one`() {
        assertEquals("c", neighbourAfterRemoval(before, "b", listOf("a", "c", "d")))
    }

    @Test
    fun `the last line removed leaves the cursor on the one before`() {
        assertEquals("c", neighbourAfterRemoval(before, "d", listOf("a", "b", "c")))
    }

    @Test
    fun `the first line removed leaves the cursor on the new first`() {
        assertEquals("b", neighbourAfterRemoval(before, "a", listOf("b", "c", "d")))
    }

    @Test
    fun `a whole run removed skips to the next line still there`() {
        assertEquals("d", neighbourAfterRemoval(before, "b", listOf("a", "d")))
    }

    @Test
    fun `nothing left means no cursor`() {
        assertNull(neighbourAfterRemoval(listOf("a"), "a", emptyList()))
    }

    @Test
    fun `a line unknown to the previous order falls back to the first of the new one`() {
        assertEquals("x", neighbourAfterRemoval(before, "zz", listOf("x", "y")))
    }
}
