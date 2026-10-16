package com.maggie.app.ui.screens.cookbook.recipes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecipeStepLabelsTest {

    private val steps = pastaDinner().steps

    @Test
    fun `minutes read as minutes, hours and hours with minutes`() {
        assertEquals("12 min", formatMinutes(12))
        assertEquals("1 h", formatMinutes(60))
        assertEquals("1 h 30", formatMinutes(90))
        assertEquals("2 h 05", formatMinutes(125))
    }

    @Test
    fun `a step in the background says so after its duration`() {
        assertEquals("12 min · en fond", stepDurationLabel(steps[2]))
        assertEquals("10 min · en fond", stepDurationLabel(steps[0]))
    }

    @Test
    fun `a step on the cook's hands shows its duration alone`() {
        assertEquals("8 min", stepDurationLabel(steps[3]))
    }

    @Test
    fun `a step with no duration shows none`() {
        assertNull(stepDurationLabel(steps[1]))
    }

    @Test
    fun `a step lists the steps it waits for`() {
        assertEquals("attend 3, 4", stepWaitsLabel(steps[4]))
        assertEquals("attend 1", stepWaitsLabel(steps[2]))
        assertNull(stepWaitsLabel(steps[0]))
    }
}
